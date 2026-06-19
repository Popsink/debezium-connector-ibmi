/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

public class WatchDogTest {
    private WatchDog createTestSubject() {
        return new WatchDog(Thread.currentThread(), 10);
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
}
