/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import io.debezium.data.Envelope.Operation;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.relational.RelationalChangeRecordEmitter;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.relational.TableId;
import io.debezium.schema.DataCollectionSchema;
import io.debezium.spi.schema.DataCollectionId;
import io.debezium.util.Clock;

public class As400ChangeRecordEmitter extends RelationalChangeRecordEmitter<As400Partition> {

    private final Operation operation;
    private final Object[] data;
    private final Object[] dataNext;
    private final RowAttribution row;

    public As400ChangeRecordEmitter(As400Partition partition, OffsetContext offset, Operation operation, Object[] data, Object[] dataNext, Clock clock,
                                    RelationalDatabaseConnectorConfig connectorConfig) {
        this(partition, offset, operation, data, dataNext, clock, connectorConfig, null);
    }

    /**
     * @param row where the row was read, so a value the converters alter can be traced back to it; when
     *            {@code null} only the table is known
     */
    public As400ChangeRecordEmitter(As400Partition partition, OffsetContext offset, Operation operation, Object[] data, Object[] dataNext, Clock clock,
                                    RelationalDatabaseConnectorConfig connectorConfig, RowAttribution row) {
        super(partition, offset, clock, connectorConfig);

        this.operation = operation;
        this.data = data;
        this.dataNext = dataNext;
        this.row = row;
    }

    /** Where the row was read, or {@code null} when only its table is known. */
    public RowAttribution attribution() {
        return row;
    }

    @Override
    public void emitChangeRecords(DataCollectionSchema schema, Receiver<As400Partition> receiver) throws InterruptedException {
        // the values are converted in here: let a converter that alters one say which row it belongs to
        try (RowAttribution.Binding binding = attributionFor(schema.id()).bind()) {
            super.emitChangeRecords(schema, receiver);
        }
    }

    private RowAttribution attributionFor(DataCollectionId id) {
        if (row != null) {
            return row;
        }
        return RowAttribution.ofTable(id instanceof TableId tableId ? tableId : null);
    }

    @Override
    public Operation getOperation() {
        return operation;
    }

    @Override
    protected Object[] getOldColumnValues() {
        return data;
    }

    @Override
    protected Object[] getNewColumnValues() {
        return dataNext;
    }

}
