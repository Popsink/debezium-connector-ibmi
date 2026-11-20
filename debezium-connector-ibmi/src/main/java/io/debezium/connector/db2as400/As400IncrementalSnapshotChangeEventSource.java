/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import io.debezium.jdbc.JdbcConnection;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.notification.NotificationService;
import io.debezium.pipeline.source.snapshot.incremental.SignalBasedIncrementalSnapshotChangeEventSource;
import io.debezium.pipeline.source.spi.DataChangeEventListener;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.spi.ChangeRecordEmitter;
import io.debezium.pipeline.spi.OffsetContext;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.relational.TableId;
import io.debezium.schema.DatabaseSchema;
import io.debezium.util.Clock;

/**
 * The signal-based incremental snapshot, whose rows name their table when a converter alters one of their
 * values (#58), like the initial and blocking snapshots' rows do. Debezium's own emitter binds no row, so
 * those values would be reported against a bare column name and counted once per conversion.
 */
class As400IncrementalSnapshotChangeEventSource extends SignalBasedIncrementalSnapshotChangeEventSource<As400Partition, TableId> {

    private final Clock clock;

    As400IncrementalSnapshotChangeEventSource(RelationalDatabaseConnectorConfig config, JdbcConnection jdbcConnection,
                                              EventDispatcher<As400Partition, TableId> dispatcher, DatabaseSchema<?> databaseSchema,
                                              Clock clock, SnapshotProgressListener<As400Partition> progressListener,
                                              DataChangeEventListener<As400Partition> dataChangeEventListener,
                                              NotificationService<As400Partition, ? extends OffsetContext> notificationService) {
        super(config, jdbcConnection, dispatcher, databaseSchema, clock, progressListener, dataChangeEventListener, notificationService);
        this.clock = clock;
    }

    @Override
    protected ChangeRecordEmitter<As400Partition> getChangeRecordEmitter(As400Partition partition, TableId tableId,
                                                                         OffsetContext offsetContext, Object[] row) {
        return new As400SnapshotChangeEventSource.AttributedSnapshotRecordEmitter(partition, offsetContext, row, clock,
                connectorConfig, RowAttribution.ofIncrementalSnapshot(tableId));
    }
}
