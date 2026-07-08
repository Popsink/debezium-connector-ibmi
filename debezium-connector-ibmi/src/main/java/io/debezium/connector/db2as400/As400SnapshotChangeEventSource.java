/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.connector.SnapshotRecord;
import io.debezium.connector.db2as400.As400OffsetContext.Loader;
import io.debezium.connector.db2as400.snapshot.query.SelectAllSnapshotQuery;
import io.debezium.ibmi.db2.journal.retrieve.JournalPosition;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.jdbc.MainConnectionProvidingConnectionFactory;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.EventDispatcher.SnapshotReceiver;
import io.debezium.pipeline.notification.NotificationService;
import io.debezium.pipeline.source.SnapshottingTask;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.pipeline.spi.SnapshotResult;
import io.debezium.relational.Column;
import io.debezium.relational.RelationalSnapshotChangeEventSource;
import io.debezium.relational.Table;
import io.debezium.relational.TableId;
import io.debezium.schema.SchemaChangeEvent;
import io.debezium.schema.SchemaChangeEvent.SchemaChangeEventType;
import io.debezium.snapshot.SnapshotterService;
import io.debezium.spi.schema.DataCollectionId;
import io.debezium.spi.snapshot.Snapshotter;
import io.debezium.util.Clock;
import io.debezium.util.ColumnUtils;
import io.debezium.util.Strings;
import io.debezium.util.Threads;
import io.debezium.util.Threads.Timer;

public class As400SnapshotChangeEventSource
        extends RelationalSnapshotChangeEventSource<As400Partition, As400OffsetContext> {
    private static final Logger log = LoggerFactory.getLogger(As400SnapshotChangeEventSource.class);
    // mirrors RelationalSnapshotChangeEventSource.LOG_INTERVAL (private there); used by our row-loop override
    private static final Duration LOG_INTERVAL = Duration.ofSeconds(10);

    private final As400ConnectorConfig connectorConfig;
    private final As400JdbcConnection jdbcConnection;
    private final As400RpcConnection rpcConnection;
    private final As400DatabaseSchema schema;
    protected final SnapshotterService snapshotterService;
    // Kept locally because the same members are private in RelationalSnapshotChangeEventSource but are
    // needed by our doCreateDataEventsForTable override (which mirrors the parent's row loop, see #25).
    private final SnapshotProgressListener<As400Partition> snapshotProgressListener;
    private final NotificationService<As400Partition, As400OffsetContext> notificationService;

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
        this.snapshotProgressListener = snapshotProgressListener;
        this.notificationService = notificationService;
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
        return tables;
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
                jdbcConnection.getAllSystemNames(schema);
            }
            catch (final Exception e) {
                log.warn("failure fetching table names", e);
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

    /**
     * Row loop for the initial snapshot. Mirrors
     * {@link RelationalSnapshotChangeEventSource#doCreateDataEventsForTable} (debezium 3.6) verbatim
     * except for the RRN handling below; keep it in sync when upgrading debezium.
     * <p>
     * The default snapshot query appends the Relative Record Number as a trailing, non-declared
     * column (see {@link SelectAllSnapshotQuery}). That column would make {@code ColumnUtils.toArray}
     * throw, so here we (a) build the {@link ColumnUtils.ColumnArray} from the declared columns only,
     * stripping the trailing RRN column, and (b) read the RRN out per row and stamp it on the offset
     * so {@code op=r} events carry {@code source.rrn} exactly like the streaming path (#25). If no RRN
     * column is present (e.g. a user {@code snapshot.select.statement.overrides}), behaviour is
     * identical to the parent and {@code source.rrn} stays unset.
     */
    @Override
    protected void doCreateDataEventsForTable(ChangeEventSourceContext sourceContext,
                                              RelationalSnapshotContext<As400Partition, As400OffsetContext> snapshotContext,
                                              As400OffsetContext offset, SnapshotReceiver<As400Partition> snapshotReceiver, Table table,
                                              boolean firstTable, boolean lastTable, int tableOrder, int tableCount, String selectStatement,
                                              OptionalLong rowCount, Set<TableId> rowCountTablesKeySet, JdbcConnection jdbcConnection)
            throws InterruptedException, SQLException {

        if (!sourceContext.isRunning()) {
            throw new InterruptedException("Interrupted while snapshotting table " + table.id());
        }

        long exportStart = clock.currentTimeInMillis();
        log.info("Exporting data from table '{}' ({} of {} tables)", table.id(), tableOrder, tableCount);

        notificationService.initialSnapshotNotificationService().notifyTableInProgress(
                snapshotContext.partition, snapshotContext.offset, table.id().identifier(), rowCountTablesKeySet);

        Instant sourceTableSnapshotTimestamp = getSnapshotSourceTimestamp(jdbcConnection, offset, table.id());

        try (Statement statement = readTableStatement(jdbcConnection, rowCount);
                ResultSet rs = resultSetForDataEvents(selectStatement, statement)) {

            final ResultSetMetaData metaData = rs.getMetaData();
            final int resultColumnCount = metaData.getColumnCount();
            // The RRN is projected as the last column and is not a declared table column.
            final boolean hasRrnColumn = resultColumnCount > 0
                    && table.columnWithName(metaData.getColumnName(resultColumnCount)) == null;
            final int rrnColumnIndex = hasRrnColumn ? resultColumnCount : -1;
            final ColumnUtils.ColumnArray columnArray = declaredColumnArray(metaData, table,
                    hasRrnColumn ? resultColumnCount - 1 : resultColumnCount);

            long rows = 0;
            Timer logTimer = getTableScanLogTimer();
            boolean hasNext = rs.next();

            if (hasNext) {
                while (hasNext) {
                    if (!sourceContext.isRunning()) {
                        throw new InterruptedException("Interrupted while snapshotting table " + table.id());
                    }

                    rows++;
                    final Object[] row = jdbcConnection.rowToArray(table, rs, columnArray);
                    // read the current row's RRN before rs.next() advances the cursor
                    final BigInteger rrn = readRrn(rs, rrnColumnIndex);

                    if (logTimer.expired()) {
                        long stop = clock.currentTimeInMillis();
                        if (rowCount.isPresent()) {
                            log.info("\t Exported {} of {} records for table '{}' after {}", rows, rowCount.getAsLong(),
                                    table.id(), Strings.duration(stop - exportStart));
                        }
                        else {
                            log.info("\t Exported {} records for table '{}' after {}", rows, table.id(),
                                    Strings.duration(stop - exportStart));
                        }
                        snapshotProgressListener.rowsScanned(snapshotContext.partition, table.id(), rows);
                        logTimer = getTableScanLogTimer();
                    }

                    hasNext = rs.next();
                    setSnapshotMarker(offset, firstTable, lastTable, rows == 1, !hasNext);

                    // Stamp the RRN before dispatch. getChangeRecordEmitter -> offset.event(...) re-stamps
                    // time/receiver/sequence but leaves the RRN untouched, so it flows into this op=r event.
                    offset.setRrn(rrn);
                    dispatcher.dispatchSnapshotEvent(snapshotContext.partition, table.id(),
                            getChangeRecordEmitter(snapshotContext.partition, offset, table.id(), row, sourceTableSnapshotTimestamp),
                            snapshotReceiver);
                }
            }
            else {
                setSnapshotMarker(offset, firstTable, lastTable, false, true);
            }

            log.info("\t Finished exporting {} records for table '{}' ({} of {} tables); total duration '{}'",
                    rows, table.id(), tableOrder, tableCount, Strings.duration(clock.currentTimeInMillis() - exportStart));
            snapshotProgressListener.dataCollectionSnapshotCompleted(snapshotContext.partition, table.id(), rows);
            notificationService.initialSnapshotNotificationService().notifyCompletedTableSuccessfully(
                    snapshotContext.partition, snapshotContext.offset, table.id().identifier(), rows, snapshotContext.capturedTables);
        }
    }

    /**
     * Like {@code ColumnUtils.toArray} but maps only the first {@code declaredColumnCount} result-set
     * columns to declared table columns, ignoring a trailing synthetic column (the RRN). Throws the
     * same way as core if a mapped column is unknown to the table.
     */
    private ColumnUtils.ColumnArray declaredColumnArray(ResultSetMetaData metaData, Table table, int declaredColumnCount)
            throws SQLException {
        final Column[] columns = new Column[declaredColumnCount];
        int greatestColumnPosition = 0;
        for (int i = 0; i < declaredColumnCount; i++) {
            final String columnName = metaData.getColumnName(i + 1);
            columns[i] = table.columnWithName(columnName);
            if (columns[i] == null) {
                throw new IllegalArgumentException("Column '" + columnName + "' not found in table '" + table.id() + "', " + table);
            }
            greatestColumnPosition = Math.max(greatestColumnPosition, columns[i].position());
        }
        return new ColumnUtils.ColumnArray(columns, greatestColumnPosition);
    }

    /** Reads the Relative Record Number from the given column, or null when absent/SQL NULL. */
    private BigInteger readRrn(ResultSet rs, int rrnColumnIndex) throws SQLException {
        if (rrnColumnIndex < 1) {
            return null;
        }
        final long value = rs.getLong(rrnColumnIndex);
        return rs.wasNull() ? null : BigInteger.valueOf(value);
    }

    // mirrors the (private) RelationalSnapshotChangeEventSource#setSnapshotMarker
    private void setSnapshotMarker(As400OffsetContext offset, boolean firstTable, boolean lastTable,
                                   boolean firstRecordInTable, boolean lastRecordInTable) {
        if (lastRecordInTable && lastTable) {
            offset.markSnapshotRecord(SnapshotRecord.LAST);
        }
        else if (firstRecordInTable && firstTable) {
            offset.markSnapshotRecord(SnapshotRecord.FIRST);
        }
        else if (lastRecordInTable) {
            offset.markSnapshotRecord(SnapshotRecord.LAST_IN_DATA_COLLECTION);
        }
        else if (firstRecordInTable) {
            offset.markSnapshotRecord(SnapshotRecord.FIRST_IN_DATA_COLLECTION);
        }
        else {
            offset.markSnapshotRecord(SnapshotRecord.TRUE);
        }
    }

    private Timer getTableScanLogTimer() {
        return Threads.timer(clock, LOG_INTERVAL);
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
