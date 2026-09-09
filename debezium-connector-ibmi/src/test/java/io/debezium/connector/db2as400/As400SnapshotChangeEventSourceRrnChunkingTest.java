/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.jdbc.MainConnectionProvidingConnectionFactory;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.notification.NotificationService;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.relational.Column;
import io.debezium.relational.Table;
import io.debezium.relational.TableEditor;
import io.debezium.relational.TableId;
import io.debezium.snapshot.SnapshotterService;
import io.debezium.util.Clock;

/**
 * Which column a parallel blocking snapshot chunks on: the key, or the relative record number when the
 * key is composite or missing (ALPHAFP.EVNT: 1.26 billion rows, no primary key, a 7-column unique index).
 */
class As400SnapshotChangeEventSourceRrnChunkingTest {

    private static final TableId EVNT = new TableId(null, "ALPHAFP", "EVNT");
    private static final String RRN = "RRN(ALPHAFP.EVNT)";
    private static final String[] EVNTL1 = { "EVCH", "EVCE", "EVCS", "EVCOL", "EVPT", "EVDISC", "EVCHR" };

    private final As400JdbcConnection connection = mock(As400JdbcConnection.class);
    private final As400DatabaseSchema schema = mock(As400DatabaseSchema.class);

    private static Table table(String... keyColumns) {
        final TableEditor editor = Table.editor().tableId(EVNT);
        for (String name : List.of("EVCH", "EVCE", "EVCS", "EVCOL", "EVPT", "EVDISC", "EVCHR", "EVTYEV")) {
            editor.addColumn(Column.editor().name(name).jdbcType(Types.CHAR).type("CHAR").length(10).optional(false).create());
        }
        return editor.setPrimaryKeyNames(keyColumns).create();
    }

    @SuppressWarnings("unchecked")
    private As400SnapshotChangeEventSource source(String mode) {
        final Configuration.Builder builder = Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX")
                .with(As400ConnectorConfig.SCHEMA, "ALPHAFP");
        if (mode != null) {
            builder.with(As400ConnectorConfig.SNAPSHOT_CHUNK_KEY_MODE, mode);
        }
        final MainConnectionProvidingConnectionFactory<As400JdbcConnection> factory = mock(MainConnectionProvidingConnectionFactory.class);
        when(factory.mainConnection()).thenReturn(connection);
        return new As400SnapshotChangeEventSource(new As400ConnectorConfig(builder.build()),
                mock(As400RpcConnection.class), factory, schema,
                mock(EventDispatcher.class), Clock.SYSTEM, mock(SnapshotProgressListener.class),
                mock(NotificationService.class), mock(SnapshotterService.class));
    }

    private static List<String> chunkKey(As400SnapshotChangeEventSource source, Table table) {
        return source.getKeyColumnsForChunking(table).stream().map(Column::name).toList();
    }

    @Test
    void auto_mode_chunks_a_composite_key_by_rrn() {
        assertThat(chunkKey(source(null), table(EVNTL1))).containsExactly(RRN);
    }

    @Test
    void auto_mode_chunks_a_keyless_table_by_rrn() {
        // core would otherwise read the whole table as a single chunk
        assertThat(chunkKey(source(null), table())).containsExactly(RRN);
    }

    @Test
    void auto_mode_keeps_a_single_column_key() {
        assertThat(chunkKey(source(null), table("EVCH"))).containsExactly("EVCH");
    }

    @Test
    void key_mode_is_the_stock_behaviour() {
        assertThat(chunkKey(source("key"), table(EVNTL1))).containsExactly(EVNTL1);
        assertThat(chunkKey(source("key"), table())).isEmpty();
    }

    @Test
    void rrn_mode_overrides_a_single_column_key() {
        assertThat(chunkKey(source("rrn"), table("EVCH"))).containsExactly(RRN);
    }

    @Test
    void rrn_column_is_the_decimal_rrn_returns() {
        final Column rrn = source(null).getKeyColumnsForChunking(table()).get(0);
        assertThat(rrn.jdbcType()).isEqualTo(Types.DECIMAL);
        assertThat(rrn.isOptional()).isFalse();
    }

    @Test
    void rrn_chunked_table_takes_its_slot_count_from_the_catalog() throws Exception {
        when(schema.tableFor(EVNT)).thenReturn(table(EVNTL1));
        when(connection.queryAndMap(contains("QSYS2.SYSPARTITIONSTAT"), any())).thenReturn(1_259_184_693L);

        assertThat(source(null).rowCountForTableChunked(EVNT)).isEqualTo(1_259_184_693L);
    }

    @Test
    void key_chunked_table_is_still_counted_by_core() throws Exception {
        when(schema.tableFor(EVNT)).thenReturn(table("EVCH"));
        when(connection.queryAndMap(contains("COUNT(1)"), any())).thenReturn(42L);

        assertThat(source(null).rowCountForTableChunked(EVNT)).isEqualTo(42L);
    }
}
