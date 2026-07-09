/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class WatchDogTest {
    private WatchDog createTestSubject() {
        // no pause ever pending: exercises the original activity-timeout behaviour only
        return new WatchDog(Thread.currentThread(), 10, 10_000, () -> false, t -> {
        });
    }

    @AfterEach
    public void clearInterrupt() {
        // a watchdog interruption targets the test thread; make sure the flag does not leak between tests
        Thread.interrupted();
    }

    @Test
    public void testRun() throws Exception {
        final WatchDog testSubject = createTestSubject();
        testSubject.start();
        Exception thrown = null;
        try {
            Thread.sleep(200);
        }
        catch (final Exception e) {
            thrown = e;
        }
        Assertions.assertThat(thrown).isInstanceOf(InterruptedException.class);
        testSubject.stop();
    }

    @Test
    public void testPausedWatchDogDoesNotInterrupt() throws Exception {
        final WatchDog testSubject = createTestSubject();
        testSubject.pause();
        testSubject.start();
        Exception thrown = null;
        try {
            // far longer than the 10ms timeout: a non-paused watchdog would interrupt us several times over
            Thread.sleep(200);
        }
        catch (final Exception e) {
            thrown = e;
        }
        Assertions.assertThat(thrown).isNull();
        Assertions.assertThat(Thread.currentThread().isInterrupted()).isFalse();
        testSubject.stop();
    }

    @Test
    public void testResumeReenablesInterrupt() throws Exception {
        final WatchDog testSubject = createTestSubject();
        testSubject.pause();
        testSubject.start();

        // while paused, no interruption happens
        Exception whilePaused = null;
        try {
            Thread.sleep(100);
        }
        catch (final Exception e) {
            whilePaused = e;
        }
        Assertions.assertThat(whilePaused).isNull();

        // once resumed, the watchdog interrupts again after the timeout elapses
        testSubject.resume();
        Exception afterResume = null;
        try {
            Thread.sleep(200);
        }
        catch (final Exception e) {
            afterResume = e;
        }
        Assertions.assertThat(afterResume).isInstanceOf(InterruptedException.class);
        testSubject.stop();
    }

    /**
     * Issue #27: a blocking-snapshot pause stays pending (context.isPaused() == true) while the
     * streaming thread keeps calling alive() — so the activity timeout never fires. The watchdog must
     * still interrupt the thread once the pause has gone unacknowledged for pauseTimeout.
     */
    @Test
    public void testPendingPauseInterruptsEvenWhenAlive() throws Exception {
        // long activity timeout so failure mode 1 cannot fire; short pause timeout is what we test
        final WatchDog testSubject = new WatchDog(Thread.currentThread(), 10_000, 30, () -> true, t -> {
        });
        testSubject.start();
        final AtomicBoolean interrupted = new AtomicBoolean(false);
        try {
            final long deadline = System.currentTimeMillis() + 500;
            while (System.currentTimeMillis() < deadline) {
                testSubject.alive(); // keep the activity watchdog satisfied
                Thread.sleep(5);
            }
        }
        catch (final InterruptedException e) {
            interrupted.set(true);
        }
        Assertions.assertThat(interrupted).isTrue();
        testSubject.stop();
    }

    /**
     * Issue #27 (second wedge): the blocking snapshot finished so the coordinator cleared the pause
     * (pausePending == false), but the streaming thread stayed parked (paused == true) and never
     * resumed. The watchdog must detect this desync and interrupt to unstick it.
     */
    @Test
    public void testStuckResumeInterrupts() throws Exception {
        // coordinator is no longer paused, but the streaming thread still believes it is paused
        final WatchDog testSubject = new WatchDog(Thread.currentThread(), 10_000, 30, () -> false, t -> {
        });
        testSubject.pause();
        testSubject.start();
        Exception thrown = null;
        try {
            Thread.sleep(500);
        }
        catch (final Exception e) {
            thrown = e;
        }
        Assertions.assertThat(thrown).isInstanceOf(InterruptedException.class);
        testSubject.stop();
    }

    /**
     * Issue #27: if interrupting does not clear the desync after MAX_UNHONORED_PAUSE_INTERRUPTS windows,
     * the watchdog escalates to the fatal handler so the connector fails loudly instead of wedging.
     */
    @Test
    public void testUnhonoredPauseEscalatesToFatal() throws Exception {
        final AtomicReference<Throwable> fatal = new AtomicReference<>();
        // stay interruptible but never acknowledge the pause; short pause timeout so the test is quick
        final WatchDog testSubject = new WatchDog(Thread.currentThread(), 10_000, 20, () -> true, fatal::set);
        testSubject.start();
        try {
            // outlast MAX_UNHONORED_PAUSE_INTERRUPTS windows; swallow interrupts to mimic a stuck thread
            final long deadline = System.currentTimeMillis() + 20L * (WatchDog.MAX_UNHONORED_PAUSE_INTERRUPTS + 4);
            while (System.currentTimeMillis() < deadline) {
                testSubject.alive();
                try {
                    Thread.sleep(5);
                }
                catch (final InterruptedException e) {
                    // ignore, as a thread wedged in a tight loop would
                }
            }
        }
        finally {
            testSubject.stop();
        }
        Assertions.assertThat(fatal.get()).isInstanceOf(io.debezium.DebeziumException.class);
    }
}
