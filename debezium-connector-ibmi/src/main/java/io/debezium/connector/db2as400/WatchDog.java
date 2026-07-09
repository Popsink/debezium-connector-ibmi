/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.util.Date;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;

/**
 * Guards the streaming thread against two silent-stall failure modes:
 *
 * <ol>
 * <li><b>No journal activity.</b> If {@link #alive()} is not called for {@code wait} ms the current
 * retrieval (or poll loop) is assumed stalled and the streaming thread is interrupted. This is the
 * original behaviour, and only applies while the thread is actively streaming (not parked in a
 * snapshot).</li>
 * <li><b>Blocking-snapshot handshake wedge (issue #27).</b> An ad-hoc blocking snapshot is a
 * cooperative handshake: the coordinator flips {@code context.isPaused()} true, the streaming thread
 * must acknowledge by pausing, run the snapshot, then resume when the coordinator flips it back to
 * false. Two observed wedges break this while the connector still reports {@code live}:
 * <ul>
 * <li><i>pause requested but never honored</i> — the thread is churning inside {@code getJournalEntries}
 * over a large shared journal, keeps calling {@link #alive()} (so failure mode 1 never fires) and never
 * reaches the pause handshake, so the snapshot never starts;</li>
 * <li><i>snapshot finished but streaming never resumes</i> — the snapshot completes and the coordinator
 * clears the pause, but the streaming thread stays parked and later ad-hoc signals pile up unprocessed.</li>
 * </ul>
 * Both reduce to the streaming thread's view of paused ({@link #pause()}/{@link #resume()}) being out of
 * sync with the coordinator's {@code isPaused()}. When that mismatch persists for {@code pauseTimeout} ms
 * the watchdog interrupts the streaming thread to force it back in sync; if repeated interrupts do not
 * resolve it after {@link #MAX_UNHONORED_PAUSE_INTERRUPTS} attempts it escalates to a fatal error so the
 * wedge surfaces (task fails, orchestrator/monitoring can act) instead of stalling silently.</li>
 * </ol>
 */
public class WatchDog implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(As400StreamingChangeEventSource.class);

    /**
     * Number of {@code pauseTimeout} windows a paused/resumed desync is interrupted before the connector
     * is failed outright. After this many interrupts the streaming thread is presumed genuinely stuck.
     */
    static final int MAX_UNHONORED_PAUSE_INTERRUPTS = 3;

    private volatile long lastSeen = System.currentTimeMillis();
    private final Thread notify;
    private volatile boolean running = true;
    private volatile boolean paused = false;
    private final long wait;
    private final long pauseTimeout;
    private final BooleanSupplier pausePending;
    private final Consumer<Throwable> onFatal;
    private long desyncSince = 0;
    private int desyncInterrupts = 0;
    private boolean fatalRaised = false;
    private Thread watchDogThread;

    /**
     * @param notify       the streaming thread to interrupt on a stall
     * @param wait         staleness threshold for {@link #alive()} (ms)
     * @param pauseTimeout how long a pending blocking-snapshot pause may go unacknowledged before the
     *                     streaming thread is interrupted to force it (ms)
     * @param pausePending tells the watchdog whether the coordinator has a blocking-snapshot pause
     *                     pending, typically {@code context::isPaused}
     * @param onFatal      invoked when an unhonored pause cannot be cleared; typically fails the task
     *                     via the connector's {@code ErrorHandler}
     */
    public WatchDog(Thread notify, long wait, long pauseTimeout, BooleanSupplier pausePending, Consumer<Throwable> onFatal) {
        this.notify = notify;
        this.wait = wait;
        this.pauseTimeout = pauseTimeout;
        this.pausePending = pausePending;
        this.onFatal = onFatal;
    }

    @Override
    public void run() {
        watchDogThread = Thread.currentThread();
        // tick often enough to honor whichever bound is smaller
        final long tick = Math.max(1, Math.min(wait, pauseTimeout));
        while (running) {
            try {
                Thread.sleep(tick);
                final long now = System.currentTimeMillis();
                final boolean coordinatorPaused = pausePending.getAsBoolean();
                if (paused == coordinatorPaused) {
                    // the streaming thread's view matches the coordinator's; the handshake is in sync
                    desyncSince = 0;
                    desyncInterrupts = 0;
                    fatalRaised = false;
                    // only guard journal activity while actively streaming, never during a snapshot
                    if (!paused && now - lastSeen > wait) {
                        log.warn("No update since {} interrupting streaming thread {}", new Date(lastSeen), notify.getName());
                        notify.interrupt();
                    }
                    continue;
                }
                checkHandshakeDesync(now, coordinatorPaused);
            }
            catch (InterruptedException e) {
                log.info("Interrupted is shuttingdown: {}", !running);
            }
        }

    }

    /**
     * The streaming thread's paused view disagrees with the coordinator's {@code isPaused()}: either a
     * blocking-snapshot pause was requested but not honored, or a finished snapshot never resumed
     * streaming. See the class javadoc for the wedges this guards (issue #27). A short-lived mismatch is
     * normal (the streaming thread reaches the handshake between journal polls); only a mismatch that
     * outlasts {@code pauseTimeout} is treated as a wedge.
     */
    private void checkHandshakeDesync(long now, boolean coordinatorPaused) {
        if (desyncSince == 0) {
            desyncSince = now;
            return;
        }
        if (now - desyncSince <= pauseTimeout) {
            return;
        }
        if (fatalRaised) {
            return;
        }
        // coordinatorPaused == true -> pause requested but streaming has not paused
        // coordinatorPaused == false -> snapshot finished but streaming has not resumed
        final String direction = coordinatorPaused
                ? "pause requested but streaming thread has not paused"
                : "blocking snapshot finished but streaming thread has not resumed";
        if (desyncInterrupts < MAX_UNHONORED_PAUSE_INTERRUPTS) {
            desyncInterrupts++;
            log.warn("Blocking-snapshot handshake stuck for {}ms ({}); interrupting streaming thread {} to "
                    + "force it back in sync (attempt {}/{})",
                    now - desyncSince, direction, notify.getName(), desyncInterrupts, MAX_UNHONORED_PAUSE_INTERRUPTS);
            desyncSince = now; // give the thread another pauseTimeout window to react to the interrupt
            notify.interrupt();
        }
        else {
            fatalRaised = true;
            log.error("Blocking-snapshot handshake still stuck after {} interrupts over ~{}ms ({}); failing the "
                    + "connector so the wedge surfaces instead of stalling silently (thread {})",
                    MAX_UNHONORED_PAUSE_INTERRUPTS, (long) MAX_UNHONORED_PAUSE_INTERRUPTS * pauseTimeout, direction, notify.getName());
            onFatal.accept(new DebeziumException(
                    "Streaming thread did not honor the blocking-snapshot handshake (" + direction + ") within "
                            + ((long) MAX_UNHONORED_PAUSE_INTERRUPTS * pauseTimeout) + "ms (issue #27)"));
        }
    }

    public void start() {
        lastSeen = System.currentTimeMillis();
        new Thread(this, "watchdog_" + notify.getName()).start();
    }

    public void stop() {
        this.running = false;
        if (watchDogThread != null) {
            watchDogThread.interrupt();
        }
    }

    public void alive() {
        lastSeen = System.currentTimeMillis();
    }

    /**
     * Suspends interruption while the streaming thread is legitimately idle, e.g. parked waiting
     * for an ad-hoc blocking snapshot to complete. Without this the watchdog would interrupt the
     * paused streaming thread after {@code wait} ms and abort the snapshot. Also clears the
     * handshake-desync escalation state, since reaching this point means the pause was honored.
     */
    public void pause() {
        this.paused = true;
        this.desyncSince = 0;
        this.desyncInterrupts = 0;
        this.fatalRaised = false;
    }

    /**
     * Resumes interruption monitoring, resetting the activity timer so a long pause does not cause
     * an immediate interruption on the next check.
     */
    public void resume() {
        this.lastSeen = System.currentTimeMillis();
        this.paused = false;
        this.desyncSince = 0;
        this.desyncInterrupts = 0;
        this.fatalRaised = false;
    }
}
