/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import org.junit.jupiter.api.Test;

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
}
