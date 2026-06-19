/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.debezium.ibmi.db2.journal.retrieve.RetrievalState;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.ChangeEventSource.ChangeEventSourceContext;
import io.debezium.relational.TableId;
import io.debezium.util.Clock;

/**
 * Verifies that {@link As400StreamingChangeEventSource} cooperates with the coordinator's ad-hoc
 * blocking-snapshot pause handshake, and that it suspends the {@link WatchDog} for the duration of
 * the snapshot so the parked streaming thread is not interrupted.
 */
public class As400StreamingChangeEventSourcePauseTest {

    // the watchdog must fire several times during the simulated snapshot to make this test meaningful
    private static final int WATCHDOG_TIMEOUT_MS = 50;
    private static final long SNAPSHOT_DURATION_MS = 300;

    /**
     * Fake context that requests a pause on the first streaming iteration and simulates a snapshot
     * that runs longer than the watchdog timeout.
     */
    private static final class PausingContext implements ChangeEventSourceContext {
        private final AtomicInteger runningChecks = new AtomicInteger();
        private final AtomicInteger pausedChecks = new AtomicInteger();
        boolean streamingPausedCalled = false;
        boolean waitSnapshotCompletionCalled = false;
        boolean interruptedDuringSnapshot = false;

        @Override
        public boolean isRunning() {
            // allow exactly one streaming iteration, then stop the loop
            return runningChecks.getAndIncrement() == 0;
        }

        @Override
        public boolean isPaused() {
            // a blocking snapshot is pending only on the first iteration
            return pausedChecks.getAndIncrement() == 0;
        }

        @Override
        public void streamingPaused() {
            streamingPausedCalled = true;
        }

        @Override
        public void waitSnapshotCompletion() throws InterruptedException {
            waitSnapshotCompletionCalled = true;
            try {
                // stand in for a long blocking snapshot; without WatchDog.pause() the streaming
                // thread would be interrupted here and the snapshot would be aborted
                Thread.sleep(SNAPSHOT_DURATION_MS);
            }
            catch (InterruptedException e) {
                interruptedDuringSnapshot = true;
                throw e;
            }
        }

        @Override
        public void resumeStreaming() {
        }

        @Override
        public void waitStreamingPaused() {
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void blockingSnapshotPausesStreamingWithoutWatchdogInterruption() throws Exception {
        final As400ConnectorConfig config = mock(As400ConnectorConfig.class);
        when(config.getPollInterval()).thenReturn(Duration.ofMillis(1));
        when(config.getMaxRetrievalTimeout()).thenReturn(WATCHDOG_TIMEOUT_MS);

        final As400JdbcConnection jdbcConnection = mock(As400JdbcConnection.class);
        when(jdbcConnection.getRealDatabaseName()).thenReturn("DB");

        final As400RpcConnection dataConnection = mock(As400RpcConnection.class);
        when(dataConnection.getJournalEntries(any(), any(), any(), any())).thenReturn(RetrievalState.Success);

        final EventDispatcher<As400Partition, TableId> dispatcher = mock(EventDispatcher.class);
        final ErrorHandler errorHandler = mock(ErrorHandler.class);
        final As400DatabaseSchema schema = mock(As400DatabaseSchema.class);

        final As400StreamingChangeEventSource source = new As400StreamingChangeEventSource(
                config, dataConnection, jdbcConnection, dispatcher, errorHandler, Clock.SYSTEM, schema);

        final PausingContext context = new PausingContext();
        final As400Partition partition = new As400Partition("server");
        final As400OffsetContext offsetContext = mock(As400OffsetContext.class);

        source.execute(context, partition, offsetContext);

        // the handshake happened: streaming acknowledged the pause and waited for the snapshot
        assertThat(context.streamingPausedCalled).isTrue();
        assertThat(context.waitSnapshotCompletionCalled).isTrue();
        // the watchdog did not interrupt the parked streaming thread mid-snapshot
        assertThat(context.interruptedDuringSnapshot).isFalse();
        // streaming actually resumed afterwards (the journal was polled once after the pause)
        verify(dataConnection, times(1)).getJournalEntries(any(), any(), any(), any());
    }
}
