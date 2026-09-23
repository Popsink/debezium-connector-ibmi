/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.io.IOException;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dead-man detector for the streaming thread. A single scheduled checker enforces two invariants:
 *
 * <ol>
 * <li><b>Progress.</b> While streaming (not paused), {@link #alive()} must be called within
 * {@code wait} ms, otherwise the streaming thread is interrupted. This unsticks interruptible
 * waits (a full queue, a metronome pause); blocked socket reads are bounded separately by the
 * connection's SO_TIMEOUT. Original behaviour, unchanged.</li>
 * <li><b>Blocking-snapshot handshake liveness (issues #27, #74).</b> An ad-hoc blocking snapshot is a
 * cooperative handshake: the coordinator flips {@code context.isPaused()}, the streaming thread must
 * acknowledge by pausing, and resume once the coordinator flips it back. Three states are wedges:
 * <ul>
 * <li>the coordinator asked for a pause the streaming thread has not honored,</li>
 * <li>the coordinator resumed but the streaming thread is still paused,</li>
 * <li>both sides agree on "paused" while no snapshot is running - the direction that made issue #74
 * invisible, since a stale pause flag looks exactly like a healthy pause to a checker that only
 * compares the two views against each other.</li>
 * </ul>
 * Any of them lasting longer than {@code pauseTimeout} means the connector has stopped streaming
 * while still reporting live. No in-place rescue is attempted: an interrupt cannot reliably resync
 * the handshake (it does not unblock jt400 socket I/O, and where it lands it terminates the
 * streaming loop anyway) and cannot unstick the coordinator-side waits at all. Instead the task is
 * failed with an {@link IOException}, which Debezium's {@code ErrorHandler} classifies as retriable,
 * so the task restarts itself (bounded by {@code errors.max.retries}) - the one remedy that clears
 * every wedge direction.</li>
 * </ol>
 */
public class WatchDog {
    private static final Logger log = LoggerFactory.getLogger(WatchDog.class);

    private final Thread notify;
    private final long wait;
    private final long pauseTimeout;
    private final BooleanSupplier pausePending;
    private final BooleanSupplier snapshotRunning;
    private final Consumer<Throwable> onWedged;

    private volatile long lastSeen = System.currentTimeMillis();
    private volatile boolean paused = false;
    // handshake state, touched only by the single scheduled checker thread
    private long wedgedSince = 0;
    private boolean wedgeReported = false;
    private ScheduledExecutorService executor;

    /**
     * @param notify          the streaming thread to interrupt when journal activity stalls
     * @param wait            staleness threshold for {@link #alive()} (ms)
     * @param pauseTimeout    how long the blocking-snapshot handshake may stay in a state that is not
     *                        making progress before the task is failed (ms)
     * @param pausePending    the coordinator's view of the blocking-snapshot pause, typically
     *                        {@code context::isPaused}
     * @param snapshotRunning whether a snapshot is currently running, typically
     *                        {@code snapshotActivity::isSnapshotRunning}; the pause is only legitimate
     *                        while this is true
     * @param onWedged        invoked with a retriable exception when the handshake is wedged; typically
     *                        the connector's {@code ErrorHandler::setProducerThrowable}
     */
    public WatchDog(Thread notify, long wait, long pauseTimeout, BooleanSupplier pausePending, BooleanSupplier snapshotRunning,
                    Consumer<Throwable> onWedged) {
        this.notify = notify;
        this.wait = wait;
        this.pauseTimeout = pauseTimeout;
        this.pausePending = pausePending;
        this.snapshotRunning = snapshotRunning;
        this.onWedged = onWedged;
    }

    public void start() {
        lastSeen = System.currentTimeMillis();
        // tick often enough to honor whichever bound is smaller
        final long tick = Math.max(1, Math.min(wait, pauseTimeout));
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            final Thread t = new Thread(r, "watchdog_" + notify.getName());
            t.setDaemon(true);
            return t;
        });
        executor.scheduleWithFixedDelay(this::check, tick, tick, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void check() {
        try {
            doCheck();
        }
        catch (final RuntimeException e) {
            // a throw would silently cancel the scheduled task and end all monitoring
            log.error("Watchdog check failed", e);
        }
    }

    private void doCheck() {
        final long now = System.currentTimeMillis();
        // invariant 1: journal activity while streaming. Also applies while a pause is pending but not
        // yet honored - unsticking a stalled thread lets it reach the pause handshake.
        if (!paused && now - lastSeen > wait) {
            log.warn("No update since {} interrupting streaming thread {}", new Date(lastSeen), notify.getName());
            // grant a full window before interrupting again, whatever the tick
            lastSeen = now;
            notify.interrupt();
        }
        // invariant 2: the handshake must keep making progress. Every state below is transient in a
        // healthy connector (a pause is acknowledged between journal entries, a snapshot starts right
        // after the acknowledgement); only one that persists past pauseTimeout is a wedge.
        final String direction = wedgeDirection();
        if (direction == null) {
            wedgedSince = 0;
            wedgeReported = false;
            return;
        }
        if (wedgedSince == 0) {
            wedgedSince = now;
            return;
        }
        if (!wedgeReported && now - wedgedSince > pauseTimeout) {
            wedgeReported = true;
            log.error("Blocking-snapshot handshake wedged for {}ms ({}); failing the task so it restarts "
                    + "instead of stalling silently while reporting live (thread {}, issues #27/#74)",
                    now - wedgedSince, direction, notify.getName());
            // IOException is classified retriable by Debezium's ErrorHandler: the task restarts itself
            onWedged.accept(new IOException(
                    "Blocking-snapshot handshake wedged: " + direction + " for over " + pauseTimeout + "ms (issues #27/#74)"));
        }
    }

    /**
     * @return a description of the handshake state the streaming thread is stuck in, or {@code null}
     *         when the handshake is in a state it can legitimately stay in
     */
    private String wedgeDirection() {
        final boolean coordinatorPaused = pausePending.getAsBoolean();
        if (paused != coordinatorPaused) {
            return coordinatorPaused
                    ? "pause requested but streaming thread has not paused"
                    : "blocking snapshot finished but streaming thread has not resumed";
        }
        if (paused && !snapshotRunning.getAsBoolean()) {
            // issue #74: the coordinator's pause flag leaked. Both views read "paused" forever, so
            // comparing them detects nothing; only "nothing is being snapshotted" gives it away.
            return "streaming paused but no snapshot is running";
        }
        return null;
    }

    public void alive() {
        lastSeen = System.currentTimeMillis();
    }

    /**
     * Suspends interruption while the streaming thread is legitimately idle, i.e. parked waiting
     * for an ad-hoc blocking snapshot to complete. Without this the watchdog would interrupt the
     * paused streaming thread after {@code wait} ms and abort the snapshot.
     */
    public void pause() {
        this.paused = true;
    }

    /**
     * Resumes interruption monitoring, resetting the activity timer so a long pause does not cause
     * an immediate interruption on the next check.
     */
    public void resume() {
        this.lastSeen = System.currentTimeMillis();
        this.paused = false;
    }
}
