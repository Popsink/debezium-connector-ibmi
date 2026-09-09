/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.pipeline.source.snapshot.incremental.SignalBasedIncrementalSnapshotContext;
import io.debezium.relational.Column;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;

/**
 * Verifies the DB2 for i query-governor hints the connector appends to the snapshot queries built by
 * Debezium core. Both the incremental chunk query and the blocking-snapshot boundary probe order by
 * the (often unindexed) chunk key; without an {@code OPTIMIZE FOR} hint the Predictive Query Governor
 * can reject them with {@code SQL0666} on large tables (issue #21).
 *
 * <p>These are pure query-string builders (they only concatenate strings and quote identifiers), so
 * they are exercised through a {@code CALLS_REAL_METHODS} mock — {@link As400JdbcConnection}'s
 * constructor eagerly resolves the real database name and would otherwise require a live AS400.
 */
public class As400JdbcConnectionQueryTest {

    private final As400JdbcConnection connection = mock(As400JdbcConnection.class, CALLS_REAL_METHODS);

    @Test
    void boundary_probe_is_hinted_with_optimize_for_one_row() {
        // Given - a boundary probe for a large-table chunk key at a high offset
        String sql = connection.buildSelectPrimaryKeyBoundaries(
                new TableId(null, "DTEST", "BIG"), 40_396_206L, "K1, K2, K3, K4", "K1, K2, K3, K4");

        // Then - the core OFFSET/FETCH probe is preserved and steered toward a first-row plan so the
        // governor estimate stays under QQRYTIMLMT instead of assuming a full sort (SQL0666).
        assertThat(sql)
                .contains("ORDER BY K1, K2, K3, K4")
                .contains("OFFSET 40396206 ROWS FETCH NEXT 1 ROWS ONLY")
                .endsWith(" OPTIMIZE FOR 1 ROW");
    }

    @Test
    void incremental_chunk_query_is_hinted_with_optimize_for_the_chunk_size() {
        // Given - an incremental snapshot chunk of 50k rows
        String sql = connection.buildSelectWithRowLimits(
                new TableId(null, "DTEST", "BIG"), 50_000, "*",
                Optional.empty(), Optional.empty(), "K1", Optional.empty());

        // Then - the chunk fetch is hinted to a first-n-rows plan sized to the chunk
        assertThat(sql).endsWith(" OPTIMIZE FOR 50000 ROWS");
    }

    @Test
    void rrn_boundary_probe_needs_no_table_access() {
        // Given - an RRN-chunked table with 1.26 billion slots split in four
        TableId id = new TableId(null, "ALPHAFP", "EVNT");
        String rrn = As400JdbcConnection.rrnExpression(id);

        // Then - the boundary at slot n is n itself: no OFFSET walk over the table or an index
        assertThat(rrn).isEqualTo("RRN(ALPHAFP.EVNT)");
        assertThat(connection.buildSelectPrimaryKeyBoundaries(id, 314_796_173L, rrn, rrn))
                .isEqualTo("SELECT CAST(314796173 AS BIGINT) FROM SYSIBM.SYSDUMMY1");
    }

    @Test
    void rrn_max_key_is_the_catalog_slot_count() {
        // Given - core's max-key probe (ORDER BY key DESC FETCH FIRST 1) for the RRN chunk key
        TableId id = new TableId(null, "ALPHAFP", "EVNT");
        String rrn = As400JdbcConnection.rrnExpression(id);

        // Then - rows plus deleted slots from the catalog, instead of walking the file backwards
        assertThat(connection.buildSelectWithRowLimits(id, 1, rrn, Optional.empty(), Optional.empty(), rrn + " DESC"))
                .isEqualTo("SELECT SUM(NUMBER_ROWS + NUMBER_DELETED_ROWS) FROM QSYS2.SYSPARTITIONSTAT "
                        + "WHERE SYSTEM_TABLE_SCHEMA = 'ALPHAFP' AND TABLE_NAME = 'EVNT'");
    }

    @Test
    void rrn_expression_reaches_the_sql_unquoted() {
        // The chunk key goes through quoteIdentifier in the boundary probe, the WHERE and the ORDER BY
        assertThat(connection.quoteIdentifier("RRN(ALPHAFP.EVNT)")).isEqualTo("RRN(ALPHAFP.EVNT)");
        assertThat(As400JdbcConnection.isRrnExpression("EVCH")).isFalse();
        // and mirrors the snapshot select's quoting of a table name that is not a plain identifier
        assertThat(As400JdbcConnection.rrnExpression(new TableId(null, "DTEST", "$SCHAR"))).isEqualTo("RRN(DTEST.\"$SCHAR\")");
    }

    @Test
    void incremental_rrn_window_predicate_spells_out_the_expression() {
        // Given - an incremental snapshot signalled with "surrogate-key": "RRN", past its first chunk
        doReturn("\"RRN\"").when(connection).quoteIdentifier("RRN");
        final As400ConnectorConfig config = new As400ConnectorConfig(Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX")
                .with(As400ConnectorConfig.SCHEMA, "ALPHAFP").build());
        final Table table = Table.editor().tableId(new TableId(null, "ALPHAFP", "EVNT"))
                .addColumn(Column.editor().name("EVCH").jdbcType(Types.CHAR).type("CHAR").length(2).optional(false).create())
                .create();
        final SignalBasedIncrementalSnapshotContext<TableId> context = new SignalBasedIncrementalSnapshotContext<>(false);
        context.addDataCollectionNamesToSnapshot("c1", List.of("ALPHAFP.EVNT"), List.of(), "RRN");
        context.maximumKey(new Object[]{ new BigDecimal(1_259_184_693L) });
        context.nextChunkPosition(new Object[]{ new BigDecimal(1024) });

        final As400RrnChunkQueryBuilder<TableId> builder = new As400RrnChunkQueryBuilder<>(config, connection);
        final Table prepared = builder.prepareTable(context, table);
        String sql = builder.buildChunkQuery(context, prepared, 1024, Optional.empty());

        // Then - the RRN is projected under an alias, but the window predicate uses the expression,
        // since DB2 for i cannot resolve a select-list alias in the WHERE clause (SQL0206)
        assertThat(prepared.retrieveColumnNames()).containsExactly("EVCH", "RRN");
        assertThat(sql)
                .startsWith("SELECT RRN(DBZ_RRN_ALIAS) AS \"RRN\", DBZ_RRN_ALIAS.* FROM \"ALPHAFP\".\"EVNT\" DBZ_RRN_ALIAS")
                .contains(" WHERE (RRN(DBZ_RRN_ALIAS) > ?) AND NOT (RRN(DBZ_RRN_ALIAS) > ?) ORDER BY \"RRN\"")
                .doesNotContain("\"RRN\" >")
                .endsWith(" OPTIMIZE FOR 1024 ROWS");
    }
}
