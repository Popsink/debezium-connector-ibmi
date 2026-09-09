/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.sql.Types;

import io.debezium.jdbc.JdbcConnection;
import io.debezium.pipeline.source.snapshot.incremental.IncrementalSnapshotContext;
import io.debezium.pipeline.source.snapshot.incremental.PhysicalRowIdentifierChunkQueryBuilder;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.relational.Table;
import io.debezium.spi.schema.DataCollectionId;

/**
 * Incremental-snapshot chunk queries keyed on the relative record number when the signal names
 * {@code "surrogate-key": "RRN"}: {@code RRN(DBZ_RRN_ALIAS) AS "RRN", DBZ_RRN_ALIAS.* FROM t DBZ_RRN_ALIAS
 * WHERE RRN(DBZ_RRN_ALIAS) > ? ORDER BY RRN(DBZ_RRN_ALIAS)}. Arrival-sequence paging with no index and no
 * cascading-OR predicate, for tables whose key is composite or missing. Without that surrogate key the
 * stock key-based chunking applies unchanged.
 */
public class As400RrnChunkQueryBuilder<T extends DataCollectionId> extends PhysicalRowIdentifierChunkQueryBuilder<T> {

    public static final String RRN = "RRN";
    static final String TABLE_ALIAS = "DBZ_RRN_ALIAS";
    static final String RRN_EXPRESSION = RRN + "(" + TABLE_ALIAS + ")";

    public As400RrnChunkQueryBuilder(RelationalDatabaseConnectorConfig config, JdbcConnection jdbcConnection) {
        // RRN() is DECIMAL(15,0) on DB2 for i
        super(config, jdbcConnection, RRN, RRN_EXPRESSION, Types.DECIMAL, Types.DECIMAL, "DECIMAL", 15, 0, true, TABLE_ALIAS);
    }

    /**
     * Core writes the window predicate against the projected column, {@code "RRN" > ?}, which is fine
     * for Oracle's real ROWID pseudo-column but on DB2 for i a select-list alias cannot be referenced in
     * the WHERE clause (SQL0206). Spell the expression out instead; the ORDER BY may keep the alias.
     */
    @Override
    protected void addLowerBound(IncrementalSnapshotContext<T> context, Table table, Object[] boundaryKey, StringBuilder sql) {
        final StringBuilder bound = new StringBuilder();
        super.addLowerBound(context, table, boundaryKey, bound);
        final boolean rrnKey = getQueryColumns(context, table).stream().anyMatch(c -> RRN.equalsIgnoreCase(c.name()));
        sql.append(rrnKey ? bound.toString().replace(jdbcConnection.quoteIdentifier(RRN), RRN_EXPRESSION) : bound);
    }
}
