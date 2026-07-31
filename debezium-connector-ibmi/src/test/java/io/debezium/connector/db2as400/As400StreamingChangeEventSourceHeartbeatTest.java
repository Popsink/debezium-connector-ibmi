/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.connect.source.SourceRecord;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.connector.db2as400.As400RpcConnection.BlockingReceiverConsumer;
import io.debezium.heartbeat.Heartbeat;
import io.debezium.heartbeat.HeartbeatFactory;
import io.debezium.ibmi.db2.journal.retrieve.JournalEntryType;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.RetrievalState;
import io.debezium.ibmi.db2.journal.retrieve.RetrieveJournal;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;
import io.debezium.pipeline.DataChangeEvent;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.ChangeEventSource.ChangeEventSourceContext;
import io.debezium.relational.TableId;
import io.debezium.spi.topic.TopicNamingStrategy;
import io.debezium.util.Clock;
import io.debezium.util.LoggingContext;

/**
 * The journal read position advances for every entry retrieved, including entries belonging to
 * non-captured files that {@link As400StreamingChangeEventSource} filters out before dispatch. Those
 * never produce a record, so the periodic heartbeat is the only thing that carries the advanced
 * position to the offsets topic. These tests pin the heartbeat to every iteration of the streaming
 * loop: while it was confined to the {@link RetrievalState#NotCalled} branch a busy shared journal
 * kept the connector permanently in {@code Success}/{@code MoreDataAvailable} and the committed
 * offset never moved.
 */
public class As400StreamingChangeEventSourceHeartbeatTest {

    private static final String DATABASE = "DTEST";
    private static final String LIBRARY = "SHARED";
    private static final String CAPTURED_TABLE = "CAPTURED";
    private static final String NON_CAPTURED_TABLE = "OTHERAPP";
    private static final String RECEIVER = "RECV042";
    private static final String RECEIVER_LIBRARY = "RCVLIB";

    private static final Instant ENTRY_TIME = Instant.ofEpochSecond(1_700_000_000L);
    private static final BigInteger BLOCK_START = BigInteger.valueOf(101);
    private static final int ENTRIES_IN_BLOCK = 5;
    /** the continuation offset the server hands back for the block, one past its last entry */
    private static final BigInteger CONTINUATION_OFFSET = BLOCK_START.add(BigInteger.valueOf(ENTRIES_IN_BLOCK));

    private static final int HEARTBEAT_INTERVAL_MS = 1;
    /** the journal retrieval must outlast the heartbeat interval, as a real RPC call would */
    private static final long RPC_DURATION_MS = 20;

    /**
     * Runs the streaming loop for exactly one iteration.
     */
    private static final class SingleIterationContext implements ChangeEventSourceContext {
        private final AtomicInteger runningChecks = new AtomicInteger();

        @Override
        public boolean isRunning() {
            return runningChecks.getAndIncrement() == 0;
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

    private As400ConnectorConfig config() {
        return new As400ConnectorConfig(Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "server1")
                .with(As400ConnectorConfig.DATABASE_NAME, DATABASE)
                .with(As400ConnectorConfig.SCHEMA, LIBRARY)
                .with(As400ConnectorConfig.TABLE_INCLUDE_LIST, LIBRARY + "." + CAPTURED_TABLE)
                .with(Heartbeat.HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL_MS)
                .with(CommonConnectorConfig.POLL_INTERVAL_MS, 1)
                .build());
    }

    private static JournalProcessedPosition positionAt(BigInteger offset, boolean processed) {
        return new JournalProcessedPosition(offset, new JournalReceiver(RECEIVER, RECEIVER_LIBRARY), ENTRY_TIME,
                processed);
    }

    private static EntryHeader nonCapturedEntryHeader() {
        final EntryHeader eheader = mock(EntryHeader.class);
        when(eheader.getJournalEntryType()).thenReturn(JournalEntryType.ADD_ROW1);
        // neither 'J' nor 'C', so it is not one of the always-processed journal/transaction entries
        when(eheader.getJournalCode()).thenReturn('R');
        when(eheader.getLibrary()).thenReturn(LIBRARY);
        when(eheader.getFile()).thenReturn(NON_CAPTURED_TABLE);
        return eheader;
    }

    /**
     * Mirrors {@link As400RpcConnection#getJournalEntries} for a block in which every entry belongs to a
     * non-captured file: each entry is handed to the consumer, which filters it out, while the position
     * still tracks it, and the offset context finishes on the block's continuation offset.
     */
    private static Answer<RetrievalState> nonCapturedBlock(RetrievalState state) {
        final RetrieveJournal retrieveJournal = mock(RetrieveJournal.class);
        return invocation -> {
            final As400OffsetContext offsetContext = invocation.getArgument(1);
            final BlockingReceiverConsumer consumer = invocation.getArgument(2);
            Thread.sleep(RPC_DURATION_MS);
            for (int i = 0; i < ENTRIES_IN_BLOCK; i++) {
                final BigInteger sequence = BLOCK_START.add(BigInteger.valueOf(i));
                consumer.accept(sequence, retrieveJournal, nonCapturedEntryHeader());
                offsetContext.getPosition().setPosition(positionAt(sequence, true));
            }
            offsetContext.setPosition(positionAt(CONTINUATION_OFFSET, false));
            return state;
        };
    }

    private As400RpcConnection dataConnection(RetrievalState state) throws Exception {
        final As400RpcConnection dataConnection = mock(As400RpcConnection.class);
        when(dataConnection.getJournalEntries(any(), any(), any(), any())).thenAnswer(nonCapturedBlock(state));
        return dataConnection;
    }

    private As400JdbcConnection jdbcConnection() {
        final As400JdbcConnection jdbcConnection = mock(As400JdbcConnection.class);
        when(jdbcConnection.getRealDatabaseName()).thenReturn(DATABASE);
        when(jdbcConnection.getLongName(LIBRARY, NON_CAPTURED_TABLE)).thenReturn(NON_CAPTURED_TABLE);
        return jdbcConnection;
    }

    /**
     * Drives one streaming iteration over a block of non-captured entries and returns the offset context
     * afterwards.
     */
    private As400OffsetContext streamOneBlock(As400ConnectorConfig config,
                                              EventDispatcher<As400Partition, TableId> dispatcher,
                                              RetrievalState state)
            throws Exception {
        final As400DatabaseSchema schema = mock(As400DatabaseSchema.class);
        final As400OffsetContext offsetContext = new As400OffsetContext(config,
                positionAt(BLOCK_START.subtract(BigInteger.ONE), true));

        final As400StreamingChangeEventSource source = new As400StreamingChangeEventSource(config,
                dataConnection(state), jdbcConnection(), dispatcher, mock(ErrorHandler.class), Clock.SYSTEM, schema);

        source.execute(new SingleIterationContext(), new As400Partition(config.getLogicalName()), offsetContext);
        return offsetContext;
    }

    @Test
    @SuppressWarnings("unchecked")
    public void blockOfNonCapturedEntriesPublishesAdvancedOffsetAsHeartbeat() throws Exception {
        final As400ConnectorConfig config = config();

        final ChangeEventQueue<DataChangeEvent> queue = new ChangeEventQueue.Builder<DataChangeEvent>()
                .maxBatchSize(config.getMaxBatchSize())
                .maxQueueSize(config.getMaxQueueSize())
                .pollInterval(Duration.ofMillis(10))
                .loggingContextSupplier(() -> LoggingContext.forConnector("IBMi", config.getLogicalName(), "test"))
                .build();

        final TopicNamingStrategy<TableId> topicNamingStrategy = config
                .getTopicNamingStrategy(As400ConnectorConfig.TOPIC_NAMING_STRATEGY, true);

        // the real dispatcher and the production heartbeat, so the whole path from the offset context to
        // an enqueued SourceRecord is exercised
        final EventDispatcher<As400Partition, TableId> dispatcher = new EventDispatcher<>(config,
                topicNamingStrategy,
                mock(As400DatabaseSchema.class),
                queue,
                config.getTableFilters().dataCollectionFilter(),
                DataChangeEvent::new,
                new As400EventMetadataProvider(),
                new HeartbeatFactory<TableId>().getScheduledHeartbeat(config, null, null, queue),
                config.schemaNameAdjuster(),
                null);

        final As400OffsetContext offsetContext = streamOneBlock(config, dispatcher, RetrievalState.Success);

        assertThat(offsetContext.getPosition().getOffset())
                .as("the read position advanced to the continuation offset of the filtered block")
                .isEqualTo(CONTINUATION_OFFSET);

        final List<DataChangeEvent> events = queue.poll();
        assertThat(events)
                .as("no captured table changed, so the heartbeat is the only record produced")
                .hasSize(1);
        final SourceRecord record = events.get(0).getRecord();
        assertThat(record.topic()).isEqualTo(topicNamingStrategy.heartbeatTopic());
        assertThat((Map<String, Object>) record.sourceOffset())
                .as("the heartbeat carries the advanced position, so it is what gets committed")
                .containsEntry(As400OffsetContext.EVENT_SEQUENCE, CONTINUATION_OFFSET.toString())
                .containsEntry(As400OffsetContext.RECEIVER, RECEIVER)
                .containsEntry(As400OffsetContext.RECEIVER_LIBRARY, RECEIVER_LIBRARY);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void heartbeatDispatchedWhateverTheRetrievalState() throws Exception {
        for (final RetrievalState state : RetrievalState.values()) {
            final As400ConnectorConfig config = config();
            final EventDispatcher<As400Partition, TableId> dispatcher = mock(EventDispatcher.class);

            final As400OffsetContext offsetContext = streamOneBlock(config, dispatcher, state);

            verify(dispatcher, times(1).description("heartbeat dispatched for retrieval state " + state))
                    .dispatchHeartbeatEventAlsoToIncrementalSnapshot(any(), eq(offsetContext));
            verify(dispatcher, times(0).description("no data change event for a non-captured table"))
                    .dispatchDataChangeEvent(any(), any(), any());
        }
    }
}
