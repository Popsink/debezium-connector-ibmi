/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.debezium.ibmi.db2.journal.retrieve.RetrievalState;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.ChangeEventSource.ChangeEventSourceContext;
import io.debezium.util.Clock;

/**
 * Verifies that {@link As400StreamingChangeEventSource} cooperates with the coordinator's ad-hoc
 * blocking-snapshot pause handshake: that it suspends the {@link WatchDog} for the duration of the
 * snapshot so the parked streaming thread is not interrupted, and that it keeps acknowledging the pause
 * so back-to-back blocking snapshots cannot deadlock it (issue #74).
 */
public class As400StreamingChangeEventSourcePauseTest {

    // the watchdog must fire several times during the simulated snapshot to make this test meaningful
    private static final int WATCHDOG_TIMEOUT_MS = 50;
    private static final long SNAPSHOT_DURATION_MS = 300;

    /**
     * Fake context that requests a pause on the first streaming iteration and simulates a snapshot that
     * runs longer than the watchdog timeout. Mirrors the real coordinator's {@code isPaused()}, which is
     * a stable flag: true from when the blocking snapshot is requested until it completes and streaming
     * is resumed.
     */
    private static final class PausingContext implements ChangeEventSourceContext {
        // paused for the whole simulated snapshot; cleared when the snapshot "resumes" streaming
        private volatile boolean paused = true;
        final AtomicInteger acknowledgements = new AtomicInteger();

        @Override
        public boolean isRunning() {
            // stay running while paused, then end the streaming loop after the first journal poll
            return paused;
        }

        @Override
        public boolean isPaused() {
            return paused;
        }

        @Override
        public void streamingPaused() {
            acknowledgements.incrementAndGet();
        }

        @Override
        public void waitSnapshotCompletion() {
            throw new AssertionError("the streaming thread must not park on the coordinator's condition (issue #74)");
        }

        @Override
        public void resumeStreaming() {
        }

        @Override
        public void waitStreamingPaused() {
        }
    }

    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void blockingSnapshotPausesStreamingWithoutWatchdogInterruption() throws Exception {
        final As400RpcConnection dataConnection = dataConnection();
        final ErrorHandler errorHandler = mock(ErrorHandler.class);
        final As400StreamingChangeEventSource source = source(dataConnection, errorHandler, new SnapshotActivity());

        final PausingContext context = new PausingContext();
        final ExecutorService snapshotThread = Executors.newSingleThreadExecutor();
        try {
            // stand in for a blocking snapshot that outlasts the watchdog timeout: without WatchDog.pause()
            // the streaming thread would be interrupted while parked and the snapshot would be aborted
            snapshotThread.submit(() -> {
                Thread.sleep(SNAPSHOT_DURATION_MS);
                context.paused = false;
                return null;
            });

            source.execute(context, new As400Partition("server"), mock(As400OffsetContext.class));
        }
        finally {
            snapshotThread.shutdownNow();
        }

        // the handshake happened: streaming acknowledged the pause
        assertThat(context.acknowledgements.get()).isPositive();
        // the watchdog did not interrupt the parked streaming thread mid-snapshot
        assertThat(Thread.interrupted()).isFalse();
        // streaming actually resumed afterwards (the journal was polled once after the pause)
        verify(dataConnection, times(1)).getJournalEntries(any(), any(), any(), any());
        verify(errorHandler, never()).setProducerThrowable(any());
    }

    /**
     * Regression test for issue #74. Reproduces the coordinator's handshake field for field
     * ({@code ChangeEventSourceCoordinator.ChangeEventSourceContextImpl}, 3.5.0.Final) and runs two
     * blocking snapshots back to back on a single thread, as the coordinator's single-threaded
     * {@code blockingSnapshotExecutor} does when the orchestrator queues a per-table backfill.
     * <p>
     * With the streaming thread parked in {@code waitSnapshotCompletion()} this deadlocked: the second
     * task re-set {@code paused} before the woken streaming thread could re-read it, so the thread parked
     * again while the task waited for an acknowledgement it would never send. Both sides then read
     * "paused" forever, the second snapshot never ran, and the connector froze while reporting live.
     */
    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    public void queuedBlockingSnapshotsAllRunAndStreamingResumes() throws Exception {
        final FakeCoordinator coordinator = new FakeCoordinator();
        final SnapshotActivity snapshotActivity = new SnapshotActivity();
        final As400RpcConnection dataConnection = dataConnection();
        final ErrorHandler errorHandler = mock(ErrorHandler.class);
        final As400StreamingChangeEventSource source = source(dataConnection, errorHandler, snapshotActivity);

        // the first request is already pending when streaming reaches the handshake, as in the incident
        coordinator.beginSnapshot();

        final ExecutorService blockingSnapshotExecutor = Executors.newSingleThreadExecutor();
        try {
            final Future<?> snapshots = blockingSnapshotExecutor.submit(
                    () -> coordinator.runQueuedSnapshots(snapshotActivity, "TABLE_1", "TABLE_2"));

            source.execute(coordinator, new As400Partition("server"), mock(As400OffsetContext.class));

            // surfaces a handshake that never completed on the coordinator side
            snapshots.get(30, TimeUnit.SECONDS);
        }
        finally {
            blockingSnapshotExecutor.shutdownNow();
        }

        // both queued snapshots ran: the second one's pause was acknowledged even though the streaming
        // thread was already paused when it was requested
        assertThat(coordinator.snapshotsRun).containsExactly("TABLE_1", "TABLE_2");
        // the pause is re-acknowledged while it lasts, which is what unwedges the second request
        assertThat(coordinator.acknowledgements.get()).isGreaterThan(1);
        // streaming resumed once the last snapshot finished
        verify(dataConnection, atLeastOnce()).getJournalEntries(any(), any(), any(), any());
        // no wedge was reported: the handshake completed on its own
        verify(errorHandler, never()).setProducerThrowable(any());
    }

    /**
     * The coordinator's blocking-snapshot handshake, copied from
     * {@code ChangeEventSourceCoordinator} (3.5.0.Final) so the connector is tested against the real
     * locking, and driven by a single thread like the real {@code blockingSnapshotExecutor}.
     */
    private static final class FakeCoordinator implements ChangeEventSourceContext {

        private final Lock lock = new ReentrantLock();
        private final Condition snapshotFinished = lock.newCondition();
        private final Condition streamingPausedCondition = lock.newCondition();

        // one journal poll once the last snapshot has resumed streaming, then the streaming loop ends
        private final AtomicInteger pollsAfterResume = new AtomicInteger(1);
        private volatile boolean paused = false;
        private volatile boolean streaming = false;

        final List<String> snapshotsRun = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger acknowledgements = new AtomicInteger();

        @Override
        public boolean isRunning() {
            return paused || pollsAfterResume.getAndDecrement() > 0;
        }

        @Override
        public boolean isPaused() {
            return paused;
        }

        @Override
        public void resumeStreaming() {
            lock.lock();
            try {
                snapshotFinished.signalAll();
            }
            finally {
                lock.unlock();
            }
        }

        @Override
        public void waitSnapshotCompletion() throws InterruptedException {
            lock.lock();
            try {
                while (paused) {
                    snapshotFinished.await();
                    streaming = true;
                }
            }
            finally {
                lock.unlock();
            }
        }

        @Override
        public void streamingPaused() {
            lock.lock();
            try {
                streaming = false;
                acknowledgements.incrementAndGet();
                streamingPausedCondition.signalAll();
            }
            finally {
                lock.unlock();
            }
        }

        @Override
        public void waitStreamingPaused() throws InterruptedException {
            lock.lock();
            try {
                long remaining = TimeUnit.SECONDS.toNanos(20);
                while (streaming) {
                    if (remaining <= 0) {
                        throw new AssertionError("the streaming thread never acknowledged the pause: handshake wedged");
                    }
                    remaining = streamingPausedCondition.awaitNanos(remaining);
                }
            }
            finally {
                lock.unlock();
            }
        }

        /** {@code doBlockingSnapshot}: request the pause and wait for the streaming thread to honor it. */
        void beginSnapshot() {
            lock.lock();
            try {
                paused = true;
                streaming = true;
            }
            finally {
                lock.unlock();
            }
        }

        Void runQueuedSnapshots(SnapshotActivity snapshotActivity, String... tables) throws InterruptedException {
            for (int i = 0; i < tables.length; i++) {
                beginSnapshot(); // a no-op for the chained requests, which were begun by endSnapshot below
                waitStreamingPaused();

                snapshotActivity.snapshotStarted();
                try {
                    Thread.sleep(50);
                    snapshotsRun.add(tables[i]);
                }
                finally {
                    snapshotActivity.snapshotFinished();
                }

                endSnapshot(i + 1 < tables.length);
            }
            return null;
        }

        /** {@code resumeStreaming}, immediately followed by the next queued request if there is one. */
        void endSnapshot(boolean anotherQueued) {
            lock.lock();
            try {
                paused = false;
                snapshotFinished.signalAll();
                // The woken streaming thread cannot re-read `paused` until it re-acquires this lock, and
                // the single-threaded executor is free to start the next queued request before then. That
                // interleaving is what wedged production; pinning it here makes the test deterministic.
                if (anotherQueued) {
                    paused = true;
                    streaming = true;
                }
            }
            finally {
                lock.unlock();
            }
        }
    }

    private As400RpcConnection dataConnection() throws Exception {
        final As400RpcConnection dataConnection = mock(As400RpcConnection.class);
        when(dataConnection.getJournalEntries(any(), any(), any(), any())).thenReturn(RetrievalState.Success);
        return dataConnection;
    }

    @SuppressWarnings("unchecked")
    private As400StreamingChangeEventSource source(As400RpcConnection dataConnection, ErrorHandler errorHandler,
                                                   SnapshotActivity snapshotActivity) {
        final As400ConnectorConfig config = mock(As400ConnectorConfig.class);
        when(config.getPollInterval()).thenReturn(Duration.ofMillis(1));
        when(config.getMaxRetrievalTimeout()).thenReturn(WATCHDOG_TIMEOUT_MS);
        // comfortably longer than the simulated snapshots so no pause is treated as a wedge
        when(config.getBlockingSnapshotPauseTimeout()).thenReturn(30_000L);

        final As400JdbcConnection jdbcConnection = mock(As400JdbcConnection.class);
        when(jdbcConnection.getRealDatabaseName()).thenReturn("DB");

        return new As400StreamingChangeEventSource(config, dataConnection, jdbcConnection,
                mock(EventDispatcher.class), errorHandler, Clock.SYSTEM, mock(As400DatabaseSchema.class),
                snapshotActivity);
    }
}
