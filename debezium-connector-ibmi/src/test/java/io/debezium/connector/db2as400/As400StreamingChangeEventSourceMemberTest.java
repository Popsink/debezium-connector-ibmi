/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.db2as400.As400RpcConnection.BlockingReceiverConsumer;
import io.debezium.ibmi.db2.journal.retrieve.JournalEntryType;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.RetrievalState;
import io.debezium.ibmi.db2.journal.retrieve.RetrieveJournal;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.source.spi.ChangeEventSource.ChangeEventSourceContext;
import io.debezium.relational.TableId;
import io.debezium.util.Clock;

/**
 * Journal retrieval selects {@code *ALL} members of a captured file, so entries from every member of a
 * multi-member physical file reach the connector and land on the same table. These tests pin the member
 * each entry came from onto {@code source.member}, and check that a before-image is only paired with the
 * after-image of the same member.
 */
public class As400StreamingChangeEventSourceMemberTest {

    private static final String DATABASE = "DTEST";
    private static final String LIBRARY = "LIB1";
    private static final String TABLE = "T1";
    private static final String MEMBER_A = "MBRA";
    private static final String MEMBER_B = "MBRB";
    private static final Instant ENTRY_TIME = Instant.ofEpochSecond(1_700_000_000L);

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

    /** a journal entry of the captured file, with the row image the decoder hands back for it */
    private record Entry(JournalEntryType type, String member, long rrn, Object[] row) {
    }

    /** what was dispatched, with {@code source.member} read at dispatch time */
    private record Dispatched(As400ChangeRecordEmitter emitter, String member) {
    }

    private As400ConnectorConfig config() {
        return new As400ConnectorConfig(Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "server1")
                .with(As400ConnectorConfig.DATABASE_NAME, DATABASE)
                .with(As400ConnectorConfig.SCHEMA, LIBRARY)
                .with(As400ConnectorConfig.TABLE_INCLUDE_LIST, LIBRARY + "." + TABLE)
                .with(CommonConnectorConfig.POLL_INTERVAL_MS, 1)
                .build());
    }

    private static JournalProcessedPosition positionAt(long offset) {
        return new JournalProcessedPosition(BigInteger.valueOf(offset), new JournalReceiver("RECV001", "RCVLIB"),
                ENTRY_TIME, true);
    }

    private static EntryHeader header(Entry entry) {
        final EntryHeader eheader = mock(EntryHeader.class);
        when(eheader.getJournalEntryType()).thenReturn(entry.type());
        when(eheader.getJournalCode()).thenReturn('R');
        when(eheader.getLibrary()).thenReturn(LIBRARY);
        when(eheader.getFile()).thenReturn(TABLE);
        when(eheader.getMember()).thenReturn(entry.member());
        when(eheader.getRelativeRecordNumber()).thenReturn(BigInteger.valueOf(entry.rrn()));
        when(eheader.getTime()).thenReturn(ENTRY_TIME);
        // commit cycle 0: not under commitment control, so each change is dispatched immediately
        when(eheader.getCommitCycle()).thenReturn(BigInteger.ZERO);
        return eheader;
    }

    @SuppressWarnings("unchecked")
    private List<Dispatched> stream(Entry... entries) throws Exception {
        final As400ConnectorConfig config = config();
        final As400OffsetContext offsetContext = new As400OffsetContext(config, positionAt(0));

        final As400RpcConnection dataConnection = mock(As400RpcConnection.class);
        when(dataConnection.getJournalEntries(any(), any(), any(), any())).thenAnswer(invocation -> {
            final BlockingReceiverConsumer consumer = invocation.getArgument(2);
            for (int i = 0; i < entries.length; i++) {
                final RetrieveJournal retrieveJournal = mock(RetrieveJournal.class);
                when(retrieveJournal.decode(any())).thenReturn(entries[i].row());
                consumer.accept(BigInteger.valueOf(i + 1), retrieveJournal, header(entries[i]));
            }
            return RetrievalState.Success;
        });

        final As400JdbcConnection jdbcConnection = mock(As400JdbcConnection.class);
        when(jdbcConnection.getRealDatabaseName()).thenReturn(DATABASE);
        when(jdbcConnection.getLongName(LIBRARY, TABLE)).thenReturn(TABLE);

        final List<Dispatched> dispatched = new ArrayList<>();
        final EventDispatcher<As400Partition, TableId> dispatcher = mock(EventDispatcher.class);
        doAnswer(invocation -> {
            dispatched.add(new Dispatched(invocation.getArgument(2),
                    offsetContext.getSourceInfo().getString(SourceInfo.MEMBER_KEY)));
            return true;
        }).when(dispatcher).dispatchDataChangeEvent(any(), any(), any());

        final As400StreamingChangeEventSource source = new As400StreamingChangeEventSource(config,
                dataConnection, jdbcConnection, dispatcher, mock(ErrorHandler.class), Clock.SYSTEM,
                mock(As400DatabaseSchema.class), new SnapshotActivity());
        source.execute(new SingleIterationContext(), new As400Partition(config.getLogicalName()), offsetContext);
        return dispatched;
    }

    @Test
    public void eachEventIsTaggedWithTheMemberItCameFrom() throws Exception {
        final List<Dispatched> dispatched = stream(
                new Entry(JournalEntryType.ADD_ROW1, MEMBER_A, 1, new Object[]{ "a1" }),
                new Entry(JournalEntryType.ADD_ROW2, MEMBER_B, 1, new Object[]{ "b1" }),
                new Entry(JournalEntryType.DELETE_ROW, MEMBER_B, 1, new Object[]{ "b1" }),
                new Entry(JournalEntryType.BEFORE_IMAGE, MEMBER_A, 1, new Object[]{ "a1" }),
                new Entry(JournalEntryType.AFTER_IMAGE, MEMBER_A, 1, new Object[]{ "a1'" }));

        assertThat(dispatched).extracting(Dispatched::member)
                .containsExactly(MEMBER_A, MEMBER_B, MEMBER_B, MEMBER_A);
    }

    @Test
    public void emptyMemberIsPublishedAsNull() throws Exception {
        final List<Dispatched> dispatched = stream(
                new Entry(JournalEntryType.ADD_ROW1, "", 1, new Object[]{ "x" }));

        assertThat(dispatched).extracting(Dispatched::member).containsExactly((String) null);
    }

    @Test
    public void beforeImageIsPairedWithTheAfterImageOfTheSameMember() throws Exception {
        final List<Dispatched> dispatched = stream(
                new Entry(JournalEntryType.BEFORE_IMAGE, MEMBER_A, 1, new Object[]{ "a-before" }),
                new Entry(JournalEntryType.BEFORE_IMAGE, MEMBER_B, 1, new Object[]{ "b-before" }),
                new Entry(JournalEntryType.AFTER_IMAGE, MEMBER_A, 1, new Object[]{ "a-after" }),
                new Entry(JournalEntryType.AFTER_IMAGE, MEMBER_B, 1, new Object[]{ "b-after" }));

        assertThat(dispatched).hasSize(2);
        assertThat(dispatched.get(0).member()).isEqualTo(MEMBER_A);
        assertThat(dispatched.get(0).emitter().getOldColumnValues()).containsExactly("a-before");
        assertThat(dispatched.get(0).emitter().getNewColumnValues()).containsExactly("a-after");
        assertThat(dispatched.get(1).member()).isEqualTo(MEMBER_B);
        assertThat(dispatched.get(1).emitter().getOldColumnValues()).containsExactly("b-before");
        assertThat(dispatched.get(1).emitter().getNewColumnValues()).containsExactly("b-after");
    }
}
