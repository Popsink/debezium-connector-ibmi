/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400.snapshot.query;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import io.debezium.annotation.ConnectorSpecific;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.connector.db2as400.As400ConnectorConfig;
import io.debezium.connector.db2as400.As400RpcConnector;
import io.debezium.snapshot.spi.SnapshotQuery;

@ConnectorSpecific(connector = As400RpcConnector.class)
public class SelectAllSnapshotQuery implements SnapshotQuery {

    /**
     * Correlation name given to the snapshotted table so the trailing {@code RRN()} scalar has an
     * unambiguous table designator to reference. Chosen to be unlikely to clash with a real column.
     */
    private static final String TABLE_CORRELATION = "DBZ_T";

    /**
     * Alias of the synthetic Relative Record Number column appended to the snapshot query (see #25).
     * It is emitted as the <em>last</em> projected column and is not a declared table column;
     * {@code As400SnapshotChangeEventSource} reads it out and strips it before the row is mapped to
     * the table columns.
     */
    public static final String RRN_COLUMN_ALIAS = "PS_RRN";

    /**
     * Whether to append the trailing {@code RRN()} column (see {@link #snapshotQuery}). Controlled by
     * {@link As400ConnectorConfig#SNAPSHOT_RRN_ENABLED}; defaults to its default when unconfigured.
     */
    private boolean rrnEnabled = As400ConnectorConfig.DEFAULT_SNAPSHOT_RRN_ENABLED;

    @Override
    public String name() {
        return CommonConnectorConfig.SnapshotQueryMode.SELECT_ALL.getValue();
    }

    @Override
    public void configure(Map<String, ?> properties) {
        final Object value = properties.get(As400ConnectorConfig.SNAPSHOT_RRN_ENABLED.name());
        if (value != null) {
            this.rrnEnabled = Boolean.parseBoolean(value.toString());
        }
    }

    @Override
    public Optional<String> snapshotQuery(String tableId, List<String> snapshotSelectColumns) {

        // if we include single quotes the column names turn into 00001,00002,... which we then can't map to the table
        final String columns = snapshotSelectColumns.stream().map(x -> x.replace("'", "\""))
                .collect(Collectors.joining(", "));
        if (!rrnEnabled) {
            // RRN population disabled: emit the plain projection so source.rrn stays unset for op=r events.
            return Optional.of("SELECT " + columns + " FROM " + tableId);
        }
        // Append the Relative Record Number as the last column so op=r snapshot events carry source.rrn
        // just like streaming events do (#25). It must stay last: the snapshot read path strips the
        // trailing, non-declared column before mapping the row back to the table's columns.
        return Optional.of("SELECT " + columns
                + ", RRN(" + TABLE_CORRELATION + ") AS \"" + RRN_COLUMN_ALIAS + "\""
                + " FROM " + tableId + " " + TABLE_CORRELATION);
    }
}
