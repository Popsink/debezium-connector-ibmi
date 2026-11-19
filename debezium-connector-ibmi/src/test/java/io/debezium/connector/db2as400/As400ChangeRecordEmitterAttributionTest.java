/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.data.Envelope;
import io.debezium.data.Envelope.Operation;
import io.debezium.ibmi.db2.journal.retrieve.JournalEntryType;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;
import io.debezium.jdbc.MainConnectionProvidingConnectionFactory;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.notification.NotificationService;
import io.debezium.pipeline.source.snapshot.incremental.IncrementalSnapshotChangeEventSource;
import io.debezium.pipeline.source.spi.DataChangeEventListener;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.relational.TableId;
import io.debezium.relational.TableSchema;
import io.debezium.snapshot.SnapshotterService;
import io.debezium.spi.schema.DataCollectionId;
import io.debezium.util.Clock;

/**
 * Debezium converts a row's values inside the emitter: the emitter makes the row it emits the one a
 * converter reports an altered value against, whether it is a journal entry, a buffered one or a snapshot
 * row (#58).
 */
class As400ChangeRecordEmitterAttributionTest {

    private static final TableId TABLE = new TableId("DTEST", "LIB", "ORDERS");

    private static As400ConnectorConfig config() {
        return new As400ConnectorConfig(Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "DTEST")
                .build());
    }

    /** A table schema that only records which row was bound while its values were being converted. */
    private static TableSchema recordingSchema(List<RowAttribution> bound) {
        final TableSchema schema = mock(TableSchema.class);
        when(schema.id()).thenReturn(TABLE);
        when(schema.keyFromColumnData(any())).thenAnswer(invocation -> {
            bound.add(RowAttribution.current() == null ? null : RowAttribution.current().row());
            return null;
        });
        when(schema.valueFromColumnData(any())).thenAnswer(invocation -> {
            bound.add(RowAttribution.current() == null ? null : RowAttribution.current().row());
            return null;
        });
        final Schema optional = SchemaBuilder.struct().optional().build();
        when(schema.getEnvelopeSchema()).thenReturn(Envelope.defineSchema().withName("e").withRecord(optional)
                .withSource(optional).build());
        return schema;
    }

    private static OffsetContext offset() {
        return mock(OffsetContext.class);
    }

    private static RowAttribution journalRow() {
        final EntryHeader eheader = mock(EntryHeader.class);
        when(eheader.getSequenceNumber()).thenReturn(BigInteger.valueOf(12345));
        when(eheader.getRelativeRecordNumber()).thenReturn(BigInteger.valueOf(7));
        when(eheader.getJournalCode()).thenReturn('R');
        when(eheader.getJournalEntryType()).thenReturn(JournalEntryType.ADD_ROW2);
        when(eheader.getCommitCycle()).thenReturn(BigInteger.ZERO);
        return RowAttribution.ofJournalEntry(TABLE, eheader);
    }

    @Test
    void bindsItsRowWhileTheValuesAreConverted() throws InterruptedException {
        final As400ConnectorConfig config = config();
        final RowAttribution row = journalRow();
        final List<RowAttribution> bound = new ArrayList<>();
        final As400ChangeRecordEmitter emitter = new As400ChangeRecordEmitter(new As400Partition("serverX"), offset(),
                Operation.CREATE, null, new Object[]{ "x" }, Clock.SYSTEM, config, row);

        emitter.emitChangeRecords(recordingSchema(bound), (partition, schema, operation, key, value, offset, headers) -> {
        });

        assertThat(bound).isNotEmpty().containsOnly(row);
        assertThat(RowAttribution.current()).as("released after the row").isNull();
    }

    @Test
    void withoutAPositionStillNamesTheTable() throws InterruptedException {
        final List<RowAttribution> bound = new ArrayList<>();
        final As400ChangeRecordEmitter emitter = new As400ChangeRecordEmitter(new As400Partition("serverX"), offset(),
                Operation.CREATE, null, new Object[]{ "x" }, Clock.SYSTEM, config());

        emitter.emitChangeRecords(recordingSchema(bound), (partition, schema, operation, key, value, offset, headers) -> {
        });

        assertThat(bound).isNotEmpty().allSatisfy(r -> assertThat(r.toString()).isEqualTo("table " + TABLE));
    }

    @Test
    void aSnapshotRowIsReportedAgainstItsTable() throws InterruptedException {
        final As400ConnectorConfig config = config();
        final List<RowAttribution> bound = new ArrayList<>();
        final As400SnapshotChangeEventSource.AttributedSnapshotRecordEmitter emitter = new As400SnapshotChangeEventSource.AttributedSnapshotRecordEmitter(
                new As400Partition("serverX"), offset(), new Object[]{ "x" }, Clock.SYSTEM, config, RowAttribution.ofSnapshot(TABLE));

        emitter.emitChangeRecords(recordingSchema(bound), (partition, schema, operation, key, value, o, headers) -> {
        });

        assertThat(bound).isNotEmpty()
                .allSatisfy(r -> assertThat(r.toString()).isEqualTo("table " + TABLE + ", read by snapshot"));
        assertThat(RowAttribution.current()).isNull();
    }

    @Test
    void anIncrementalSnapshotRowIsReportedAgainstItsTable() throws InterruptedException {
        final As400ConnectorConfig config = new As400ConnectorConfig(Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "DTEST")
                .with(CommonConnectorConfig.SIGNAL_DATA_COLLECTION, "DTEST.LIB.SIGNALS")
                .build());
        @SuppressWarnings("unchecked")
        final MainConnectionProvidingConnectionFactory<As400JdbcConnection> connections = mock(MainConnectionProvidingConnectionFactory.class);
        when(connections.mainConnection()).thenReturn(mock(As400JdbcConnection.class));
        @SuppressWarnings("unchecked")
        final EventDispatcher<As400Partition, TableId> dispatcher = mock(EventDispatcher.class);
        final As400ChangeEventSourceFactory factory = new As400ChangeEventSourceFactory(config, config, mock(As400RpcConnection.class),
                connections, mock(ErrorHandler.class), dispatcher, Clock.SYSTEM, mock(As400DatabaseSchema.class),
                mock(SnapshotterService.class));

        @SuppressWarnings("unchecked")
        final SnapshotProgressListener<As400Partition> progress = mock(SnapshotProgressListener.class);
        @SuppressWarnings("unchecked")
        final DataChangeEventListener<As400Partition> changes = mock(DataChangeEventListener.class);
        @SuppressWarnings("unchecked")
        final NotificationService<As400Partition, As400OffsetContext> notifications = mock(NotificationService.class);
        final IncrementalSnapshotChangeEventSource<As400Partition, ? extends DataCollectionId> source = factory
                .getIncrementalSnapshotChangeEventSource(mock(As400OffsetContext.class), progress, changes, notifications)
                .orElseThrow();

        // Debezium's own incremental snapshot emitter binds no row: the connector must supply its own
        assertThat(source).isInstanceOf(As400IncrementalSnapshotChangeEventSource.class);
        final List<RowAttribution> bound = new ArrayList<>();
        ((As400IncrementalSnapshotChangeEventSource) source)
                .getChangeRecordEmitter(new As400Partition("serverX"), TABLE, offset(), new Object[]{ "x" })
                .emitChangeRecords(recordingSchema(bound), (partition, schema, operation, key, value, o, headers) -> {
                });

        assertThat(bound).isNotEmpty()
                .allSatisfy(r -> assertThat(r.toString()).isEqualTo("table " + TABLE + ", read by incremental snapshot"));
        assertThat(RowAttribution.current()).isNull();
    }
}
