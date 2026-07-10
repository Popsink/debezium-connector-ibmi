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
 * <li><b>Blocking-snapshot handshake convergence (issue #27).</b> An ad-hoc blocking snapshot is a
 * cooperative handshake: the coordinator flips {@code context.isPaused()}, the streaming thread must
 * acknowledge by pausing, and resume once the coordinator flips it back. The streaming thread's view
 * ({@link #pause()}/{@link #resume()}) staying out of sync with the coordinator's for more than
 * {@code pauseTimeout} ms means the handshake is wedged — a requested pause was never honored, or a
 * finished snapshot never resumed streaming — while the connector still reports live. No in-place
 * rescue is attempted: an interrupt cannot reliably resync the handshake (it does not unblock jt400
 * socket I/O, and where it lands it terminates the streaming loop anyway) and cannot unstick the
 * coordinator-side waits at all. Instead the task is failed with an {@link IOException}, which
 * Debezium's {@code ErrorHandler} classifies as retriable, so the task restarts itself (bounded by
 * {@code errors.max.retries}) — the one remedy that clears both wedge directions.</li>
 * </ol>
 */
public class WatchDog {
    private static final Logger log = LoggerFactory.getLogger(WatchDog.class);

    private final Thread notify;
    private final long wait;
    private final long pauseTimeout;
    private final BooleanSupplier pausePending;
    private final Consumer<Throwable> onWedged;

    private volatile long lastSeen = System.currentTimeMillis();
    private volatile boolean paused = false;
    // handshake state, touched only by the single scheduled checker thread
    private long desyncSince = 0;
    private boolean wedgeReported = false;
    private ScheduledExecutorService executor;

    /**
     * @param notify       the streaming thread to interrupt when journal activity stalls
     * @param wait         staleness threshold for {@link #alive()} (ms)
     * @param pauseTimeout how long the streaming thread's paused view may stay out of sync with the
     *                     coordinator's blocking-snapshot pause before the task is failed (ms)
     * @param pausePending the coordinator's view of the blocking-snapshot pause, typically
     *                     {@code context::isPaused}
     * @param onWedged     invoked with a retriable exception when the handshake is wedged; typically
     *                     the connector's {@code ErrorHandler::setProducerThrowable}
     */
    public WatchDog(Thread notify, long wait, long pauseTimeout, BooleanSupplier pausePending, Consumer<Throwable> onWedged) {
        this.notify = notify;
        this.wait = wait;
        this.pauseTimeout = pauseTimeout;
        this.pausePending = pausePending;
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
        // yet honored — unsticking a stalled thread lets it reach the pause handshake.
        if (!paused && now - lastSeen > wait) {
            log.warn("No update since {} interrupting streaming thread {}", new Date(lastSeen), notify.getName());
            // grant a full window before interrupting again, whatever the tick
            lastSeen = now;
            notify.interrupt();
        }
        // invariant 2: the paused view must converge with the coordinator within pauseTimeout. A
        // short-lived mismatch is normal (the handshake is reached between journal entries); only a
        // persistent one is a wedge.
        final boolean coordinatorPaused = pausePending.getAsBoolean();
        if (paused == coordinatorPaused) {
            desyncSince = 0;
            wedgeReported = false;
            return;
        }
        if (desyncSince == 0) {
            desyncSince = now;
            return;
        }
        if (!wedgeReported && now - desyncSince > pauseTimeout) {
            wedgeReported = true;
            final String direction = coordinatorPaused
                    ? "pause requested but streaming thread has not paused"
                    : "blocking snapshot finished but streaming thread has not resumed";
            log.error("Blocking-snapshot handshake wedged for {}ms ({}); failing the task so it restarts "
                    + "instead of stalling silently while reporting live (thread {}, issue #27)",
                    now - desyncSince, direction, notify.getName());
            // IOException is classified retriable by Debezium's ErrorHandler: the task restarts itself
            onWedged.accept(new IOException(
                    "Blocking-snapshot handshake wedged: " + direction + " for over " + pauseTimeout + "ms (issue #27)"));
        }
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
