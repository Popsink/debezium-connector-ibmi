/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.debezium.DebeziumException;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.RetrievalState;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.ChangeEventSource.ChangeEventSourceContext;
import io.debezium.relational.TableId;
import io.debezium.util.Clock;

/**
 * Verifies that the streaming loop's retry path actually waits between attempts.
 * <p>
 * The bug this covers was invisible to a count-based assertion: the retries were all made, they were
 * just made instantly, because the error path borrowed the streaming loop's {@link io.debezium.util.Metronome}
 * and that one advances its tick once per {@code pause()} call rather than by the wall clock. A connector
 * that had been streaming with data never called {@code pause()}, so its tick was behind by the whole
 * streaming time and every retry pause returned immediately - the whole retry budget went in milliseconds.
 * These tests therefore assert <em>elapsed time</em>, not attempt counts.
 */
class As400StreamingChangeEventSourceRetryBackoffTest {

    private static final int WATCHDOG_TIMEOUT_MS = 60_000;
    /** Small enough to keep the tests quick, large enough that the assertions aren't scheduler noise. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(5);
    /**
     * What {@link #POLL_INTERVAL} the 19 pauses of an exhausted retry budget add up to:
     * 1 + 2 + 4 (attempts 1-3) + 8 x 16 (attempts 4-19, capped).
     */
    private static final long TOTAL_BACKOFF_IN_POLL_INTERVALS = 135;

    private final As400ConnectorConfig config = mock(As400ConnectorConfig.class);
    private final As400RpcConnection dataConnection = mock(As400RpcConnection.class);
    private final As400JdbcConnection jdbcConnection = mock(As400JdbcConnection.class);
    private final As400OffsetContext offsetContext = mock(As400OffsetContext.class);

    /** Runs the streaming loop for a bounded number of iterations, never pausing for a snapshot. */
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

    private As400StreamingChangeEventSource source() {
        when(config.getPollInterval()).thenReturn(POLL_INTERVAL);
        when(config.getMaxRetrievalTimeout()).thenReturn(WATCHDOG_TIMEOUT_MS);
        when(config.getSnapshotMode()).thenReturn(As400ConnectorConfig.SnapshotMode.WHEN_NEEDED);
        when(jdbcConnection.getRealDatabaseName()).thenReturn("DB");
        when(offsetContext.getPosition()).thenReturn(new JournalProcessedPosition(
                BigInteger.valueOf(82), new JournalReceiver("RECV008", "RCV_LIB"), Instant.ofEpochSecond(123_456L), true));

        @SuppressWarnings("unchecked")
        final EventDispatcher<As400Partition, TableId> dispatcher = mock(EventDispatcher.class);
        return new As400StreamingChangeEventSource(config, dataConnection, jdbcConnection, dispatcher,
                mock(ErrorHandler.class), Clock.SYSTEM, mock(As400DatabaseSchema.class), new SnapshotActivity());
    }

    @Test
    void backoffGrowsWithEachRetryThenLevelsOffAtTheCap() {
        final As400StreamingChangeEventSource source = source();

        assertThat(source.retryBackoff(0)).isEqualTo(POLL_INTERVAL);
        assertThat(source.retryBackoff(1)).isEqualTo(POLL_INTERVAL.multipliedBy(2));
        assertThat(source.retryBackoff(2)).isEqualTo(POLL_INTERVAL.multipliedBy(4));
        assertThat(source.retryBackoff(3)).isEqualTo(POLL_INTERVAL.multipliedBy(8));
        // capped from here on, including at the very edge of the retry budget
        assertThat(source.retryBackoff(4)).isEqualTo(POLL_INTERVAL.multipliedBy(8));
        assertThat(source.retryBackoff(19)).isEqualTo(POLL_INTERVAL.multipliedBy(8));
        // and no overflow for a shift that would wrap a long
        assertThat(source.retryBackoff(Integer.MAX_VALUE)).isEqualTo(POLL_INTERVAL.multipliedBy(8));
    }

    /**
     * The regression: a connector that streamed with data for a while, then hit a failure burst, must still
     * spread its retries. Before the fix the first successful iteration alone was enough to put the shared
     * metronome's tick far enough behind that all 20 attempts were consumed with no waiting at all.
     */
    @Test
    void retriesStillWaitAfterTheLoopHasBeenStreamingWithData() throws Exception {
        final As400StreamingChangeEventSource source = source();
        // timed from the first failure, not from the start of the loop, so the streaming phase's own
        // duration is not what the assertion is measuring
        final AtomicLong firstFailureAt = new AtomicLong();
        when(dataConnection.getJournalEntries(any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    // one healthy iteration that takes real time, i.e. what a connector catching up does;
                    // this is what used to buy the error path 60 poll intervals of instant pauses
                    Thread.sleep(POLL_INTERVAL.multipliedBy(60).toMillis());
                    return RetrievalState.Success;
                })
                .thenAnswer(invocation -> {
                    firstFailureAt.compareAndSet(0, System.nanoTime());
                    throw new IOException("journal RPC call failed");
                });

        assertThatThrownBy(() -> source.execute(new BoundedContext(1000), new As400Partition("server"), offsetContext))
                .isInstanceOf(DebeziumException.class)
                .hasMessageContaining("after 20 retries");

        final Duration spentRetrying = Duration.ofNanos(System.nanoTime() - firstFailureAt.get());
        // the 19 pauses of an exhausted budget, halved to leave room for a coarse scheduler. Before the fix
        // this burst took about 33ms, i.e. an order of magnitude under the threshold, whatever the margin
        assertThat(spentRetrying).isGreaterThan(POLL_INTERVAL.multipliedBy(TOTAL_BACKOFF_IN_POLL_INTERVALS / 2));
    }

    /**
     * The idle path keeps the loop's own metronome, so a connector that is called but finds nothing still
     * polls at its configured interval rather than spinning.
     */
    @Test
    void anIdleLoopStillPacesItselfAtThePollInterval() throws Exception {
        final As400StreamingChangeEventSource source = source();
        when(dataConnection.getJournalEntries(any(), any(), any(), any())).thenReturn(RetrievalState.NotCalled);

        final long startedAt = System.nanoTime();
        source.execute(new BoundedContext(20), new As400Partition("server"), offsetContext);
        final Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isGreaterThan(POLL_INTERVAL.multipliedBy(20 / 2));
    }
}
