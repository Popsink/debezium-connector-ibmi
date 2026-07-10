/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

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
     * Issue #27 (first wedge): a blocking-snapshot pause stays pending (context.isPaused() == true)
     * while the streaming thread keeps calling alive() — so the activity timeout never fires. The
     * watchdog must not interrupt (rescue attempts are unreliable) but must report the wedge with a
     * retriable exception so the task restarts.
     */
    @Test
    public void testUnhonoredPauseFailsTask() throws Exception {
        final AtomicReference<Throwable> wedged = new AtomicReference<>();
        // long activity timeout so failure mode 1 cannot fire; short pause timeout is what we test
        final WatchDog testSubject = new WatchDog(Thread.currentThread(), 10_000, 30, () -> true, wedged::set);
        testSubject.start();
        Exception thrown = null;
        try {
            final long deadline = System.currentTimeMillis() + 400;
            while (System.currentTimeMillis() < deadline) {
                testSubject.alive(); // keep the activity watchdog satisfied, as the #27 drain loop did
                Thread.sleep(5);
            }
        }
        catch (final Exception e) {
            thrown = e;
        }
        finally {
            testSubject.stop();
        }
        Assertions.assertThat(thrown).isNull();
        // IOException is what Debezium's ErrorHandler classifies as retriable
        Assertions.assertThat(wedged.get()).isInstanceOf(java.io.IOException.class);
    }

    /**
     * Issue #27 (second wedge): the blocking snapshot finished so the coordinator cleared the pause
     * (pausePending == false), but the streaming thread stayed parked (paused == true) and never
     * resumed. The watchdog must report the wedge without interrupting the parked thread.
     */
    @Test
    public void testStuckResumeFailsTask() throws Exception {
        final AtomicReference<Throwable> wedged = new AtomicReference<>();
        // coordinator is no longer paused, but the streaming thread still believes it is paused
        final WatchDog testSubject = new WatchDog(Thread.currentThread(), 10_000, 30, () -> false, wedged::set);
        testSubject.pause();
        testSubject.start();
        Exception thrown = null;
        try {
            Thread.sleep(400);
        }
        catch (final Exception e) {
            thrown = e;
        }
        finally {
            testSubject.stop();
        }
        Assertions.assertThat(thrown).isNull();
        Assertions.assertThat(wedged.get()).isInstanceOf(java.io.IOException.class);
    }

    /**
     * The pause handshake normally completes between journal entries: a mismatch that resolves within
     * pauseTimeout is not a wedge and must not fail the task.
     */
    @Test
    public void testPauseHonoredWithinTimeoutIsNotAWedge() throws Exception {
        final AtomicReference<Throwable> wedged = new AtomicReference<>();
        final WatchDog testSubject = new WatchDog(Thread.currentThread(), 10_000, 200, () -> true, wedged::set);
        testSubject.start();
        try {
            // honor the pending pause well before the timeout, then linger past several check ticks
            Thread.sleep(50);
            testSubject.pause();
            Thread.sleep(500);
        }
        finally {
            testSubject.stop();
        }
        Assertions.assertThat(wedged.get()).isNull();
        Assertions.assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }
}
