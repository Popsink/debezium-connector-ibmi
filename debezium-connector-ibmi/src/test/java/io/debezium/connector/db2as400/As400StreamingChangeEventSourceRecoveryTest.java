/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400SecurityException;

import io.debezium.DebeziumException;
import io.debezium.connector.db2as400.As400ConnectorConfig.UnavailablePositionRecovery;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.RetrievalState;
import io.debezium.ibmi.db2.journal.retrieve.exception.InvalidJournalFilterException;
import io.debezium.ibmi.db2.journal.retrieve.exception.InvalidJournalRangeException;
import io.debezium.ibmi.db2.journal.retrieve.exception.JournalReceiverNotFoundException;
import io.debezium.ibmi.db2.journal.retrieve.exception.LostJournalException;
import io.debezium.ibmi.db2.journal.retrieve.exception.ObjectLockedException;
import io.debezium.pipeline.ErrorHandler;
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
        return source(mode, Clock.SYSTEM);
    }

    private As400StreamingChangeEventSource source(UnavailablePositionRecovery mode, Clock clock) {
        // the receiver the position names is gone from the chain, i.e. this really is a lost position and not
        // a range the connector resolved badly (issue #79); positionStillOnTheServer() is the other case
        when(dataConnection.isPositionStillAvailable(any())).thenReturn(false);
        when(config.getPollInterval()).thenReturn(Duration.ofMillis(1));
        when(config.getMaxRetrievalTimeout()).thenReturn(WATCHDOG_TIMEOUT_MS);
        when(config.getJournalLockTimeout()).thenReturn(As400ConnectorConfig.DEFAULT_JOURNAL_LOCK_TIMEOUT);
        when(config.getConnectionRetryTimeout()).thenReturn(As400ConnectorConfig.DEFAULT_CONNECTION_RETRY_TIMEOUT);
        when(config.getUnavailablePositionRecovery()).thenReturn(mode);
        when(config.getSnapshotMode()).thenReturn(As400ConnectorConfig.SnapshotMode.WHEN_NEEDED);
        when(jdbcConnection.getRealDatabaseName()).thenReturn("DB");
        when(offsetContext.getPosition()).thenReturn(new JournalProcessedPosition(
                BigInteger.valueOf(82), new JournalReceiver("RECV008", "RCV_LIB"), Instant.ofEpochSecond(123_456L), true));

        @SuppressWarnings("unchecked")
        final EventDispatcher<As400Partition, TableId> dispatcher = mock(EventDispatcher.class);
        return new As400StreamingChangeEventSource(config, dataConnection, jdbcConnection, dispatcher,
                mock(ErrorHandler.class), clock, mock(As400DatabaseSchema.class), new SnapshotActivity());
    }

    /** A minute passes every time the time is read, so budgets of hours go by in a few hundred polls. */
    private static Clock fastClock(AtomicLong now) {
        return new Clock() {
            @Override
            public long currentTimeInMillis() {
                return now.addAndGet(60_000);
            }
        };
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

    /**
     * The other reading of a failed call: the position is still in the journal's receiver chain, so nothing
     * was pruned - the range was resolved badly, which is what a CPF7054 at a receiver roll is. Resetting for
     * that is what threw production connectors back to the earliest retained receiver every time they caught
     * up with the head (issue #79).
     */
    @Test
    void aPositionStillInTheReceiverChainIsRetriedRatherThanReset() throws Exception {
        final As400StreamingChangeEventSource source = source(UnavailablePositionRecovery.EARLIEST);
        when(dataConnection.isPositionStillAvailable(any())).thenReturn(true);
        when(dataConnection.getJournalEntries(any(), any(), any(), any()))
                .thenThrow(new LostJournalException("CPF7054 failed to find offset or invalid offsets"))
                .thenReturn(RetrievalState.Success);

        execute(source, 2);

        verify(offsetContext, never()).setPosition(any());
        verify(dataConnection, times(2)).getJournalEntries(any(), any(), any(), any());
    }

    /** A refused range reports itself as its own exception, and takes the same route. */
    @Test
    void aRefusedRangeIsRetriedWhileThePositionIsStillThere() throws Exception {
        final As400StreamingChangeEventSource source = source(UnavailablePositionRecovery.EARLIEST);
        when(dataConnection.isPositionStillAvailable(any())).thenReturn(true);
        when(dataConnection.getJournalEntries(any(), any(), any(), any()))
                .thenThrow(new InvalidJournalRangeException("CPF7054 last < first"))
                .thenReturn(RetrievalState.Success);

        execute(source, 2);

        verify(offsetContext, never()).setPosition(any());
    }

    /**
     * An offset that no longer belongs to the journal - deleted and recreated under us - reports the same
     * CPF7054 as a badly resolved range does, and the receiver chain is what tells them apart.
     */
    @Test
    void aRefusedRangeWhosePositionHasGoneIsRecovered() throws Exception {
        final As400StreamingChangeEventSource source = source(UnavailablePositionRecovery.EARLIEST);
        when(dataConnection.getJournalEntries(any(), any(), any(), any()))
                .thenThrow(new InvalidJournalRangeException("CPF7054 offset does not belong to this journal"))
                .thenReturn(RetrievalState.Success);

        execute(source, 2);

        verify(offsetContext).setPosition(new JournalProcessedPosition());
    }

    @Test
    void repeatedRecoveryIsBoundedInTime() throws Exception {
        final As400StreamingChangeEventSource source = source(UnavailablePositionRecovery.EARLIEST, fastClock(new AtomicLong()));
        when(dataConnection.getJournalEntries(any(), any(), any(), any()))
                .thenThrow(new LostJournalException("CPF7053 break in receivers"));

        // the loop would otherwise recover forever, the retry window has to stop it
        assertThatThrownBy(() -> execute(source, 100_000))
                .isInstanceOf(NotRetriableException.class)
                .hasMessageContaining("the journal position cannot be read");

        verify(offsetContext, atLeastOnce()).setPosition(new JournalProcessedPosition());
    }

    /**
     * A member locked by a nightly batch job (CPF7007) clears on its own: waiting for it must not reconnect
     * nor move the position, and must outlast the minutes an unknown failure is retried for (issue #107).
     */
    @Test
    void aMemberLockedLongerThanAnUnknownFailureIsRetriedIsWaitedOut() throws Exception {
        final AtomicLong now = new AtomicLong();
        final As400StreamingChangeEventSource source = source(UnavailablePositionRecovery.EARLIEST, fastClock(now));
        final ObjectLockedException locked = new ObjectLockedException(
                "CPF7007 member locked by another job: Cannot allocate member TABLE1 file TABLE1 in LIB.");
        final AtomicInteger calls = new AtomicInteger();
        when(dataConnection.getJournalEntries(any(), any(), any(), any())).thenAnswer(invocation -> {
            // locked for two hours of the fast clock, then released
            if (now.get() < Duration.ofHours(2).toMillis()) {
                calls.incrementAndGet();
                throw locked;
            }
            return RetrievalState.Success;
        });

        execute(source, 1_000);

        assertThat(calls.get()).as("retried well past an unknown failure's 5 minutes").isGreaterThan(20);
        verify(dataConnection, never()).close();
        verify(offsetContext, never()).setPosition(any());
    }

    /**
     * A job that never releases its lock must not stall the source forever with only log lines: past
     * {@code journal.lock.timeout.ms} the task stops, with an error naming the lock and the setting.
     */
    @Test
    void aMemberLockedPastTheTimeoutStopsTheTaskNamingTheLock() throws Exception {
        final AtomicLong now = new AtomicLong();
        final As400StreamingChangeEventSource source = source(UnavailablePositionRecovery.EARLIEST, fastClock(now));
        when(dataConnection.getJournalEntries(any(), any(), any(), any())).thenThrow(new ObjectLockedException(
                "Call failed: Cannot allocate member TABLE1 file TABLE1 in LIB."));

        assertThatThrownBy(() -> execute(source, 100_000))
                .isInstanceOf(NotRetriableException.class)
                .hasMessageContaining(As400ConnectorConfig.JOURNAL_LOCK_TIMEOUT.name())
                .hasMessageContaining("Cannot allocate member TABLE1 file TABLE1 in LIB.")
                .hasCauseInstanceOf(ObjectLockedException.class);

        assertThat(now.get()).as("not before the timeout").isGreaterThanOrEqualTo(As400ConnectorConfig.DEFAULT_JOURNAL_LOCK_TIMEOUT);
        verify(dataConnection, never()).close();
    }

    /**
     * Reconnecting reports a rejected sign-on as an {@link IOException}, and retrying it as a lost connection
     * only adds failed sign-ons towards the limit that disables the profile: it stops the task at once.
     */
    @Test
    void aRejectedSignOnStopsTheTaskAtOnce() throws Exception {
        final As400StreamingChangeEventSource source = source(UnavailablePositionRecovery.EARLIEST);
        final AS400SecurityException rejected = mock(AS400SecurityException.class);
        when(rejected.getMessage()).thenReturn("User profile is disabled.");
        when(dataConnection.getJournalEntries(any(), any(), any(), any())).thenThrow(new IOException("Failed to reconnect", rejected));

        assertThatThrownBy(() -> execute(source, 100))
                .isInstanceOf(NotRetriableException.class)
                .hasMessageContaining("User profile is disabled.");

        verify(dataConnection, times(1)).getJournalEntries(any(), any(), any(), any());
    }
}
