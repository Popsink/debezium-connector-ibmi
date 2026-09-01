/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.connector.db2as400.As400OffsetContext.Loader;
import io.debezium.ibmi.db2.journal.retrieve.JournalPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.jdbc.MainConnectionProvidingConnectionFactory;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.notification.NotificationService;
import io.debezium.pipeline.source.SnapshottingTask;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.spi.SnapshotResult;
import io.debezium.relational.RelationalSnapshotChangeEventSource;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.schema.SchemaChangeEvent;
import io.debezium.schema.SchemaChangeEvent.SchemaChangeEventType;
import io.debezium.snapshot.SnapshotterService;
import io.debezium.spi.schema.DataCollectionId;
import io.debezium.spi.snapshot.Snapshotter;
import io.debezium.util.Clock;

public class As400SnapshotChangeEventSource
        extends RelationalSnapshotChangeEventSource<As400Partition, As400OffsetContext> {
    private static final Logger log = LoggerFactory.getLogger(As400SnapshotChangeEventSource.class);

    private final As400ConnectorConfig connectorConfig;
    private final As400JdbcConnection jdbcConnection;
    private final As400RpcConnection rpcConnection;
    private final As400DatabaseSchema schema;
    protected final SnapshotterService snapshotterService;

    public As400SnapshotChangeEventSource(As400ConnectorConfig connectorConfig, As400RpcConnection rpcConnection,
                                          MainConnectionProvidingConnectionFactory<As400JdbcConnection> jdbcConnectionFactory,
                                          As400DatabaseSchema schema, EventDispatcher<As400Partition, TableId> dispatcher, Clock clock,
                                          SnapshotProgressListener<As400Partition> snapshotProgressListener,
                                          NotificationService<As400Partition, As400OffsetContext> notificationService,
                                          SnapshotterService snapshotterService) {

        super(connectorConfig, jdbcConnectionFactory, schema, dispatcher, clock, snapshotProgressListener,
                notificationService, snapshotterService);

        this.connectorConfig = connectorConfig;
        this.rpcConnection = rpcConnection;
        this.jdbcConnection = jdbcConnectionFactory.mainConnection();
        this.schema = schema;
        this.snapshotterService = snapshotterService;
    }

    @Override
    public SnapshotResult<As400OffsetContext> execute(ChangeEventSourceContext context, As400Partition partition,
                                                      As400OffsetContext previousOffset, SnapshottingTask snapshottingTask)
            throws InterruptedException {

        return super.execute(context, partition, previousOffset, snapshottingTask);
    }

    /**
     * Raise the DB2 for i Predictive Query Governor on the main snapshot connection. A full-table snapshot
     * of a large unindexed table can be estimated over {@code QQRYTIMLMT} and rejected with {@code SQL0666}
     * before any row is read; {@code CHGQRYA QRYTIMLMT(*NOMAX)} removes that ceiling for snapshot work only.
     */
    @Override
    protected void connectionCreated(RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext) throws Exception {
        super.connectionCreated(snapshotContext);
        applySnapshotQueryTimeLimit(jdbcConnection);
    }

    /**
     * Same governor raise for each additional connection in the parallel snapshot pool
     * ({@code snapshot.max.threads > 1}).
     */
    @Override
    protected void connectionPoolConnectionCreated(RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext,
                                                   JdbcConnection connection)
            throws SQLException {
        super.connectionPoolConnectionCreated(snapshotContext, connection);
        applySnapshotQueryTimeLimit(connection);
    }

    private void applySnapshotQueryTimeLimit(JdbcConnection connection) {
        final String configured = connectorConfig.snapshotQueryTimeLimit();
        final Optional<String> sql = queryTimeLimitCommand(configured);
        if (sql.isEmpty()) {
            if (configured != null && !configured.isBlank() && !"*SAME".equalsIgnoreCase(configured.trim())) {
                log.warn("Ignoring invalid snapshot.query.time.limit '{}'; expected *NOMAX, *SAME or a number of seconds", configured);
            }
            return;
        }
        // Best-effort: this is a job-attribute tweak, never data. It runs against the connection's OWN job
        // (so it needs no special authority — *JOBCTL is only required to change another job) and is
        // entirely optional. If the environment rejects it (authority, unsupported QCMDEXC, invalid value),
        // we swallow the error and roll the connection back so the snapshot proceeds unchanged with the
        // existing limit. This runs before any table lock or data read, so a rollback here loses nothing.
        try {
            final Connection jdbc = connection.connection();
            try (Statement statement = jdbc.createStatement()) {
                statement.execute(sql.get());
            }
            log.info("Raised DB2 for i query governor on snapshot connection: {}", sql.get());
        }
        catch (SQLException e) {
            log.warn("Could not set snapshot query governor time limit (continuing without it): {}", e.getMessage());
            rollbackQuietly(connection);
        }
    }

    private static void rollbackQuietly(JdbcConnection connection) {
        try {
            final Connection jdbc = connection.connection();
            if (!jdbc.getAutoCommit()) {
                jdbc.rollback();
            }
        }
        catch (SQLException e) {
            log.debug("Rollback after a failed query governor command also failed (ignored)", e);
        }
    }

    /**
     * Build the {@code CALL QSYS2.QCMDEXC('CHGQRYA QRYTIMLMT(...)')} statement for the configured limit, or
     * empty when the limit should not be applied ({@code *SAME}, blank) or is invalid. {@code CHGQRYA
     * QRYTIMLMT} only accepts {@code *NOMAX} or a non-negative number of seconds, so anything else is
     * rejected rather than interpolated into the CL command.
     */
    static Optional<String> queryTimeLimitCommand(String configured) {
        if (configured == null) {
            return Optional.empty();
        }
        final String value = configured.trim();
        if (value.isEmpty() || "*SAME".equalsIgnoreCase(value)) {
            return Optional.empty();
        }
        if (!"*NOMAX".equalsIgnoreCase(value) && !value.matches("\\d+")) {
            return Optional.empty();
        }
        return Optional.of("CALL QSYS2.QCMDEXC('CHGQRYA QRYTIMLMT(" + value.toUpperCase() + ")')");
    }

    @Override
    protected Set<TableId> getAllTableIds(RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext)
            throws Exception {
        // A single connector may capture tables across several libraries (sharing one journal),
        // so discover tables in every library referenced by the include list, not just the default.
        final String databaseName = jdbcConnection.getRealDatabaseName();
        final Set<TableId> tables = new LinkedHashSet<>();
        for (final String schema : connectorConfig.getCaptureSchemas()) {
            tables.addAll(jdbcConnection.readTableNames(databaseName, schema, null, new String[]{ "TABLE" }));
        }
        if (connectorConfig.skipUncapturableTables()) {
            dropMemberlessFiles(tables);
        }
        return tables;
    }

    /**
     * A memberless physical file fails any SQL access with {@code SQL0204} and would abort the snapshot of
     * every other table; under {@code errors.tolerance=all} it is dropped instead.
     */
    private void dropMemberlessFiles(Set<TableId> tables) {
        final Set<String> schemas = tables.stream().map(TableId::schema).collect(Collectors.toSet());
        for (final String schema : schemas) {
            final Set<String> withMembers;
            try {
                withMembers = jdbcConnection.tablesWithMembers(schema);
            }
            catch (final SQLException e) {
                log.error("failed to list the tables of {} with members, keeping them all in the snapshot", schema, e);
                continue;
            }
            final Iterator<TableId> iterator = tables.iterator();
            while (iterator.hasNext()) {
                final TableId tableId = iterator.next();
                if (schema.equals(tableId.schema()) && !withMembers.contains(tableId.table().toUpperCase())) {
                    log.error("errors.tolerance=all: dropping {} from the snapshot, the physical file has no member "
                            + "- nothing will be captured for it", tableId);
                    iterator.remove();
                }
            }
        }
    }

    /**
     * Names the {@code table.include.list} entries that matched no table on the source.
     * <p>
     * They are skipped by design - #33 settled that the include list is an allow list and that a table
     * missing from the source must not block startup - but the skip left no trace anywhere, not even at
     * DEBUG. On a connector configured for ~41 tables across two schemas only ~30 ever appeared in the
     * "Adding table" lines and the rest showed up in neither direction, so the only way to tell "never
     * captured because it does not exist" from "captured fine" was to diff the configured list against the
     * log by hand. Nothing about which tables are captured changes here.
     */
    private void logUnresolvedIncludeListEntries(Set<TableId> capturedTables) {
        final Map<String, Predicate<TableId>> matchers = connectorConfig.getTableIncludeListMatchers();
        final List<String> unresolved = matchers.entrySet().stream()
                .filter(entry -> capturedTables.stream().noneMatch(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
        if (!unresolved.isEmpty()) {
            log.info("{} of the {} table.include.list entries matched no table on the source and will not be "
                    + "captured: {}", unresolved.size(), matchers.size(), String.join(", ", unresolved));
        }
    }

    @Override
    protected void lockTablesForSchemaSnapshot(ChangeEventSourceContext sourceContext,
                                               RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext)
            throws Exception {
        final Duration lockTimeout = connectorConfig.snapshotLockTimeout();
        final Set<String> capturedTablesNames = snapshotContext.capturedTables.stream().map(As400ConnectorConfig.tableToString::toString).collect(Collectors.toSet());

        List<String> tableLockStatements = capturedTablesNames.stream()
                .map(tableId -> snapshotterService.getSnapshotLock().tableLockingStatement(lockTimeout, tableId))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        if (!tableLockStatements.isEmpty()) {

            String lineSeparator = System.lineSeparator();
            StringBuilder statements = new StringBuilder();

            // DB2 for the ibmi doesn't appear to support lock timeouts

            // there are 3 lock options SHARE MODE, EXCLUSIVE MODE ALLOW READ, EXCLUSIVE MODE
            // e.g.LOCK TABLE SCHEMA.NAME IN SHARE MODE;

            // no obvious mode prevents schema changes
            // DBZ-298 Quoting name in case it has been quoted originally; it doesn't do harm if it hasn't been quoted
            tableLockStatements.forEach(tableStatement -> statements.append(tableStatement).append(lineSeparator));

            log.info("Waiting for each table lock");
            jdbcConnection.executeWithoutCommitting(statements.toString());
        }
    }

    @Override
    protected void determineSnapshotOffset(
                                           RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext,
                                           As400OffsetContext previousOffset)
            throws Exception {
        boolean shouldSnapshotData = shouldSnapshotData(previousOffset, snapshotterService.getSnapshotter());
        boolean useOffset = !shouldSnapshotData || (shouldSnapshotData && !snapshotterService.getSnapshotter().shouldStreamEventsStartingFromSnapshot());
        if (previousOffset != null && previousOffset.isPositionSet() && useOffset) {
            snapshotContext.offset = previousOffset;
        }
        else {
            final Instant now = Instant.now();
            final JournalPosition position = rpcConnection.getCurrentPosition();
            // set last entry to processed, so we don't process it again
            final JournalProcessedPosition processedPos = new JournalProcessedPosition(position, now, true);
            snapshotContext.offset = new As400OffsetContext(connectorConfig, processedPos);
        }
    }

    @Override
    protected void readTableStructure(ChangeEventSourceContext sourceContext,
                                      RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext,
                                      As400OffsetContext offsetContext, SnapshottingTask snapshottingTask)
            throws Exception {
        final Set<String> schemas = snapshotContext.capturedTables.stream().map(TableId::schema)
                .collect(Collectors.toSet());

        logUnresolvedIncludeListEntries(snapshotContext.capturedTables);

        // reading info only for the schemas we're interested in as per the set of
        // captured tables;
        // while the passed table name filter alone would skip all non-included tables,
        // reading the schema
        // would take much longer that way
        for (final String schema : schemas) {
            if (!sourceContext.isRunning()) {
                throw new InterruptedException("Interrupted while reading structure of schema " + schema);
            }

            log.info("Reading structure of schema '{}'", schema);

            jdbcConnection.readSchema(snapshotContext.tables, // use snapshotContext.capturedSchemaTables?
                    jdbcConnection.getRealDatabaseName(), schema,
                    connectorConfig.getTableFilters().eligibleDataCollectionFilter(), null, false);

            try {
                // scoped to this schema's captured tables for the same reason as the read above: the
                // mapping is only ever consulted for those, and fetching the whole library's worth cost
                // tens of seconds of round trips on every start
                final Set<String> capturedInSchema = snapshotContext.capturedTables.stream()
                        .filter(id -> schema.equals(id.schema()))
                        .map(TableId::table)
                        .collect(Collectors.toSet());
                jdbcConnection.getAllSystemNames(schema, capturedInSchema);
            }
            catch (final Exception e) {
                log.warn("failure fetching table names for schema {}", schema, e);
            }
        }

        for (final TableId id : snapshotContext.capturedTables) {
            final Table table = snapshotContext.tables.forTable(id);
            if (table == null) {
                log.error("table schema not found for {}", id, new Exception("missing table definition"));
            }
            else {
                schema.addSchema(table);
            }
        }
    }

    @Override
    protected void releaseSchemaSnapshotLocks(
                                              RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext)
            throws Exception {
        // TODO unlock tables
    }

    @Override
    protected SchemaChangeEvent getCreateTableEvent(
                                                    RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext, Table table) {
        return SchemaChangeEvent.of(SchemaChangeEventType.CREATE, snapshotContext.partition, snapshotContext.offset,
                snapshotContext.catalogName, table.id().schema(), null, table, true);
    }

    @Override
    protected Optional<String> getSnapshotSelect(
                                                 RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext, TableId tableId,
                                                 List<String> columns) {
        String table = tableId.table();
        // quote names that aren't plain SQL identifiers (e.g. $SCHAR)
        if (!table.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            table = "\"" + table + "\"";
        }
        String fullTableName = String.format("%s.%s", tableId.schema(), table);
        return snapshotterService.getSnapshotQuery().snapshotQuery(fullTableName, columns);
    }

    @Override
    protected Long rowCountForTableChunked(TableId tableId) throws SQLException {
        try {
            return super.rowCountForTableChunked(tableId);
        }
        catch (SQLException e) {
            final String hint = switch (e.getErrorCode()) {
                case -204 -> " (SQL0204: the physical file may have no member, or the table was dropped after discovery)";
                case -551, -552 -> " (SQL0551/SQL0552: the connecting user profile is not authorized to the table)";
                case -913 -> " (SQL0913: the object is locked - a save or reorganize may be running)";
                default -> "";
            };
            throw new SQLException("Failed to count rows for table " + tableId + hint, e.getSQLState(), e.getErrorCode(), e);
        }
    }

    @Override
    public SnapshottingTask getSnapshottingTask(As400Partition partition, As400OffsetContext previousOffset) {
        final Snapshotter snapshotter = snapshotterService.getSnapshotter();
        final List<String> dataCollectionsToBeSnapshotted = connectorConfig.getDataCollectionsToBeSnapshotted();
        final Map<DataCollectionId, String> snapshotSelectOverridesByTable = connectorConfig.getSnapshotSelectOverridesByTable();

        boolean shouldSnapshotSchema = true; // connector needs the schema to decode the journal data
        boolean shouldSnapshotData = shouldSnapshotData(previousOffset, snapshotter);

        if (shouldSnapshotData && shouldSnapshotSchema) {
            log.info("According to the connector configuration both schema and data will be snapshotted.");
        }
        else if (shouldSnapshotSchema) {
            log.info("According to the connector configuration only schema will be snapshotted (this should always be done).");
        }

        return new SnapshottingTask(shouldSnapshotSchema,
                shouldSnapshotData, dataCollectionsToBeSnapshotted,
                snapshotSelectOverridesByTable, false);
    }

    private boolean shouldSnapshotData(As400OffsetContext previousOffset, final Snapshotter snapshotter) {
        boolean offsetExists = previousOffset != null;
        boolean snapshotInProgress = (offsetExists && !previousOffset.isSnapshotComplete());

        boolean shouldSnapshotData = snapshotter.shouldSnapshotData(offsetExists, snapshotInProgress);
        return shouldSnapshotData;
    }

    @Override
    protected SnapshotContext<As400Partition, As400OffsetContext> prepare(As400Partition partition, boolean onDemand) throws Exception {
        return new RelationalSnapshotContext<>(partition, jdbcConnection.getRealDatabaseName(), onDemand);
    }

    @Override
    protected As400OffsetContext copyOffset(
                                            RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext) {
        return new Loader(connectorConfig).load(snapshotContext.offset.getOffset());
    }
}
