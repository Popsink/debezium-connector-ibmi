/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.debezium.DebeziumException;
import io.debezium.connector.db2as400.As400ConnectorConfig.UnavailablePositionRecovery;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.RetrievalState;
import io.debezium.ibmi.db2.journal.retrieve.exception.InvalidJournalFilterException;
import io.debezium.ibmi.db2.journal.retrieve.exception.JournalReceiverNotFoundException;
import io.debezium.ibmi.db2.journal.retrieve.exception.LostJournalException;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.ChangeEventSource.ChangeEventSourceContext;
import io.debezium.relational.TableId;
import io.debezium.util.Clock;

/**
 * Verifies that {@link As400StreamingChangeEventSource} applies the configured
 * {@link UnavailablePositionRecovery} strategy when the receiver it is streaming from is pruned
 * mid-stream, rather than silently skipping ahead or always failing the task.
 */
class As400StreamingChangeEventSourceRecoveryTest {

    private static final int WATCHDOG_TIMEOUT_MS = 60_000;

    private final As400ConnectorConfig config = mock(As400ConnectorConfig.class);
    private final As400RpcConnection dataConnection = mock(As400RpcConnection.class);
    private final As400JdbcConnection jdbcConnection = mock(As400JdbcConnection.class);
    private final As400OffsetContext offsetContext = mock(As400OffsetContext.class);

    /**
     * Runs the streaming loop for a bounded number of iterations, never pausing for a snapshot.
     */
    private static final class BoundedContext implements ChangeEventSourceContext {
        private final AtomicInteger iterations = new AtomicInteger();
        private final int maxIterations;

        BoundedContext(int maxIterations) {
            this.maxIterations = maxIterations;
        }

        @Override
        public boolean isRunning() {
            return iterations.getAndIncrement() < maxIterations;
        }

        @Override
        public boolean isPaused() {
            return false;
        }

        @Override
        public void streamingPaused() {
        }

        @Override
        public void waitSnapshotCompletion() {
        }

        @Override
        public void resumeStreaming() {
        }

        @Override
        public void waitStreamingPaused() {
        }
    }

    private As400StreamingChangeEventSource source(UnavailablePositionRecovery mode) {
        when(config.getPollInterval()).thenReturn(Duration.ofMillis(1));
        when(config.getMaxRetrievalTimeout()).thenReturn(WATCHDOG_TIMEOUT_MS);
        when(config.getUnavailablePositionRecovery()).thenReturn(mode);
        when(config.getSnapshotMode()).thenReturn(As400ConnectorConfig.SnapshotMode.WHEN_NEEDED);
        when(jdbcConnection.getRealDatabaseName()).thenReturn("DB");
        when(offsetContext.getPosition()).thenReturn(new JournalProcessedPosition(
                BigInteger.valueOf(82), new JournalReceiver("RECV008", "RCV_LIB"), Instant.ofEpochSecond(123_456L), true));

        @SuppressWarnings("unchecked")
        final EventDispatcher<As400Partition, TableId> dispatcher = mock(EventDispatcher.class);
        return new As400StreamingChangeEventSource(config, dataConnection, jdbcConnection, dispatcher,
                Clock.SYSTEM, mock(As400DatabaseSchema.class));
    }

    private void execute(As400StreamingChangeEventSource source, int iterations) throws InterruptedException {
        source.execute(new BoundedContext(iterations), new As400Partition("server"), offsetContext);
    }

    @Test
    void lostJournalWithEarliestResetsToTheEarliestReceiverAndKeepsStreaming() throws Exception {
        final As400StreamingChangeEventSource source = source(UnavailablePositionRecovery.EARLIEST);
        when(dataConnection.getJournalEntries(any(), any(), any(), any()))
                .thenThrow(new LostJournalException("CPF7053 break in receivers"))
                .thenReturn(RetrievalState.Success);

        execute(source, 2);

        // streaming resumed from a blank position, i.e. the earliest receiver still on the server
        verify(offsetContext).setPosition(new JournalProcessedPosition());
        verify(dataConnection, times(2)).getJournalEntries(any(), any(), any(), any());
    }

    @Test
    void lostJournalWithFailStopsWithTheOffsetUnavailableMarker() throws Exception {
        final As400StreamingChangeEventSource source = source(UnavailablePositionRecovery.FAIL);
        when(dataConnection.getJournalEntries(any(), any(), any(), any()))
                .thenThrow(new LostJournalException("CPF7053 break in receivers"));

        assertThatThrownBy(() -> execute(source, 5))
                .isInstanceOf(OffsetNoLongerAvailableException.class)
                .hasMessageContaining(OffsetNoLongerAvailableException.ERROR_CODE);

        verify(offsetContext, never()).setPosition(any());
    }

    @Test
    void lostJournalWithSnapshotStopsSoTheSnapshotRunsOnRestart() throws Exception {
        final As400StreamingChangeEventSource source = source(UnavailablePositionRecovery.SNAPSHOT);
        when(dataConnection.getJournalEntries(any(), any(), any(), any()))
                .thenThrow(new LostJournalException("CPF9801 receiver not found"));

        assertThatThrownBy(() -> execute(source, 5))
                .isInstanceOf(OffsetNoLongerAvailableException.class)
                .hasMessageContaining("when_needed");

        verify(offsetContext, never()).setPosition(any());
    }

    /**
     * A deleted receiver is reported as a {@link JournalReceiverNotFoundException}, which is a
     * {@link LostJournalException}, so it takes the same route.
     */
    @Test
    void deletedReceiverIsRecovered() throws Exception {
        final As400StreamingChangeEventSource source = source(UnavailablePositionRecovery.EARLIEST);
        when(dataConnection.getJournalEntries(any(), any(), any(), any()))
                .thenThrow(new JournalReceiverNotFoundException("Object RECV008 in library RCV_LIB not found.", "CPF9801"))
                .thenReturn(RetrievalState.Success);

        execute(source, 2);

        verify(offsetContext).setPosition(new JournalProcessedPosition());
    }

    /**
     * A fatal journal error that isn't a lost position, here a filtered object that isn't journalled, is a
     * different problem and must still fail loudly rather than reset the offset.
     */
    @Test
    void otherFatalJournalErrorsStillFail() throws Exception {
        final As400StreamingChangeEventSource source = source(UnavailablePositionRecovery.EARLIEST);
        when(dataConnection.getJournalEntries(any(), any(), any(), any()))
                .thenThrow(new InvalidJournalFilterException("CPF7060 object not found or not journaled"));

        assertThatThrownBy(() -> execute(source, 5))
                .isInstanceOf(DebeziumException.class)
                .isNotInstanceOf(OffsetNoLongerAvailableException.class)
                .hasMessageContaining("Unable to process offset");

        verify(offsetContext, never()).setPosition(any());
    }

    @Test
    void repeatedRecoveryIsBoundedByTheRetryLimit() throws Exception {
        final As400StreamingChangeEventSource source = source(UnavailablePositionRecovery.EARLIEST);
        when(dataConnection.getJournalEntries(any(), any(), any(), any()))
                .thenThrow(new LostJournalException("CPF7053 break in receivers"));

        // the loop would otherwise recover forever, the retry bound has to stop it
        assertThatThrownBy(() -> execute(source, 1000))
                .isInstanceOf(DebeziumException.class)
                .hasMessageContaining("after 20 retries");

        verify(offsetContext, atLeastOnce()).setPosition(new JournalProcessedPosition());
    }
}
