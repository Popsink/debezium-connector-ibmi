/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.kafka.common.config.ConfigDef;
import org.apache.kafka.common.config.ConfigDef.Importance;
import org.apache.kafka.common.config.ConfigDef.Type;
import org.apache.kafka.common.config.ConfigDef.Width;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.config.ConfigurationNames;
import io.debezium.config.EnumeratedValue;
import io.debezium.config.Field;
import io.debezium.connector.SourceInfoStructMaker;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.ibmi.db2.journal.retrieve.PointerHandles;
import io.debezium.ibmi.db2.journal.retrieve.RetrieveConfig;
import io.debezium.jdbc.JdbcConfiguration;
import io.debezium.relational.ColumnFilterMode;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.relational.RelationalTableFilters;
import io.debezium.relational.Selectors.TableIdToStringMapper;
import io.debezium.relational.TableId;
import io.debezium.relational.Tables.TableFilter;

//TODO  can we deliver HistorizedRelationalDatabaseConnectorConfig or should it be RelationalDatabaseConnectorConfig
public class As400ConnectorConfig extends RelationalDatabaseConnectorConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(As400ConnectorTask.class);

    public static TableIdToStringMapper tableToString = x -> {
        if (x.table() != null) {
            if (x.schema() != null) {
                return String.format("%s.%s", x.schema(), x.table());
            }
            LOGGER.error("missing schema name {}, did the function expect the database.schema.table?", x);

            return x.table();
        }
        LOGGER.error("missing table name {}", x);
        return "";
    };

    /**
     * Floor for the default {@code snapshot.fetch.size}. Matches Debezium's stock relational default.
     */
    private static final int DEFAULT_SNAPSHOT_FETCH_SIZE = 2_000;

    private final CharSequenceTrimMode charSequenceTrimMode;
    private final SnapshotMode snapshotMode;
    private final UnavailablePositionRecovery unavailablePositionRecovery;
    private final ErrorsTolerance errorsTolerance;
    private final Configuration config;
    private String incrementalTables = "";

    // used by the snapshot to limit the additional tables for a change in configuration
    private RelationalTableFilters tableFilters;

    /**
     * A field for the password to connect to the AS400. This field has no default
     * value.
     */
    public static final Field SCHEMA = Field.create(ConfigurationNames.DATABASE_CONFIG_PREFIX + "schema", "schema holding tables to capture");

    /**
     * A field for the size of buffer for fetching journal entries default 65535 (should not be smaller)
     */
    public static final Field BUFFER_SIZE = Field.create("buffer.size", "journal buffer size",
            "size of buffer for fetching journal entries default 131072 (should not be smaller)", "131072");

    /**
     * keep alive flag, should the driver use a secure connection defaults to false
     */
    public static final Field SECURE = Field.create("secure", "secure", "use secure connection", true);

    /**
     * The timeout to use for sockets
     */
    public static final Field SOCKET_TIMEOUT = Field.create("socket.timeout", "socket timeout in milliseconds",
            "socket timeout", 0);

    /**
     * If the ccsid is wrong on your tables and that is the least of your problems - just correct the CCSID before using this or as a last resort...
     * This applies to all tables - everything
     * mapping from.ccsid and to.ccsid must *both* be specified
     */
    public static final Field FROM_CCSID = Field.create("from.ccsid", "from ccsid",
            "when the table indicates this from_ccsid translate to the to_ccsid setting", -1);

    public static final Field TO_CCSID = Field.create("to.ccsid", "to ccsid",
            "when the table indicates the from_ccsid translate to this to_ccsid setting", -1);

    public static final Field DIAGNOSTICS_FOLDER = Field.create("diagnostics.folder",
            "folder to dump failed decodings to", "used when there is a decoding failure to aid diagnostics");

    /**
     * Query time limit applied to the DB2 for i Predictive Query Governor on the connector's snapshot
     * connections via {@code CHGQRYA QRYTIMLMT(...)}. The governor rejects a query at optimize time with
     * {@code SQL0666} when its estimated runtime exceeds this limit, which a full-table snapshot of a large
     * unindexed table routinely does. Accepted values: {@code *NOMAX} (no limit), {@code *SAME} (leave the
     * job attribute untouched), or a non-negative number of seconds. Applied only to snapshot connections,
     * so journal streaming is unaffected.
     */
    public static final Field SNAPSHOT_QUERY_TIME_LIMIT = Field.create("snapshot.query.time.limit",
            "Snapshot query time limit (CHGQRYA QRYTIMLMT)",
            "Query governor time limit for snapshot connections; '*NOMAX' avoids SQL0666 rejection, "
                    + "'*SAME' leaves the job attribute untouched, or a non-negative number of seconds.",
            "*NOMAX");

    /**
     * Maximum number of journal entries to process server side
     */
    public static final Field MAX_SERVER_SIDE_ENTRIES = Field.create("max.entries", "max server side entries",
            "Maximum number of journal entries to process server side when filtering", RetrieveConfig.DEFAULT_MAX_SERVER_SIDE_ENTRIES);

    public static final long DEFAULT_MAX_JOURNAL_TIMEOUT = 60000;
    /**
     * Maximum number of journal entries to process server side
     */
    public static final Field MAX_RETRIEVAL_TIMEOUT = Field.create("max.journal.timeout", "max time to fetch the journal entries",
            "Maximum time to fetch the journal entries in ms", DEFAULT_MAX_JOURNAL_TIMEOUT);

    public static final long DEFAULT_BLOCKING_SNAPSHOT_PAUSE_TIMEOUT = 120000;
    /**
     * How long (ms) the streaming thread's paused view may stay out of sync with the coordinator's
     * ad-hoc blocking-snapshot pause before the {@link WatchDog} fails the task with a retriable error
     * so it restarts. Guards the issue #27 wedges, where either a requested pause is never honored (the
     * thread churns inside {@code getJournalEntries} over a large shared journal, refreshing the activity
     * watchdog but never reaching the pause handshake, so the snapshot never starts) or a finished
     * snapshot never resumes streaming. Should be comfortably larger than {@code max.journal.timeout}
     * and than the worst-case time to process a single journal entry (including dispatching a large
     * buffered transaction into a backpressured queue), since the pause handshake is only reached
     * between entries.
     */
    public static final Field BLOCKING_SNAPSHOT_PAUSE_TIMEOUT = Field.create("blocking.snapshot.pause.timeout.ms",
            "blocking snapshot pause timeout",
            "Max time in ms the streaming thread may stay out of sync with a coordinator-requested ad-hoc "
                    + "blocking-snapshot pause/resume before the task is failed with a retriable error (and "
                    + "restarted) to avoid a silent wedge.",
            DEFAULT_BLOCKING_SNAPSHOT_PAUSE_TIMEOUT);

    public static final long DEFAULT_CACHE_ADDITIONAL_DELAY = 5000;

    public static final Field JOURNAL_CACHE_ADDITIONAL_DELAY = Field.create("journal.additional.delay", "additional delay when journal caching is enabled",
            "additional journal cache delay in ms used when journal caching is enabled", DEFAULT_CACHE_ADDITIONAL_DELAY);

    public static final boolean DEFAULT_TRANSACTION_MGMT_ENABLED = false;

    public static final Field TRANSACTION_MGMT_ENABLED = Field.create("transaction.management",
            "handle commit/rollback lifecycle",
            "event submission is delayed until a commit or rollback event. This requires relaxing a filter in AS400 RMI call, it may increase the load on the connector",
            DEFAULT_TRANSACTION_MGMT_ENABLED);

    public static final boolean DEFAULT_LOB_FETCH = false;

    public static final Field LOB_FETCH = Field.create("lob.fetch",
            "fetch the data of lob columns",
            "A journal entry carries no CLOB, DBCLOB, BLOB or XML data, only a pointer into the journal "
                    + "receiver that can only be followed on the IBM i itself, so the values are read back with "
                    + "QSYS2.DISPLAY_JOURNAL. Entries are fetched a run at a time rather than one by one, so the "
                    + "first lob entry of a batch pays for the ones behind it. Turn this off to skip the reads "
                    + "entirely, in which case lob columns stream as null, no extra query is made at all, and "
                    + "every other column of the table is unaffected. Snapshots read lob columns over JDBC "
                    + "either way.",
            DEFAULT_LOB_FETCH);

    public static final long DEFAULT_POINTER_HANDLE_THRESHOLD = PointerHandles.DEFAULT_THRESHOLD;

    public static final Field POINTER_HANDLE_THRESHOLD = Field.create("journal.pointer.handle.threshold",
            "pointer handles held before the connection is replaced",
            "Every journal entry of a table with a lob column comes back owning an allocation on the IBM i "
                    + "that is only released when the handle is deleted or the job that made the request ends. "
                    + "Deleting them one at a time costs a round trip per entry; instead they are counted and "
                    + "freed together once this many have accumulated by replacing the connection, so that the "
                    + "next retrieve runs in a different host server job. Nothing is cancelled or ended on "
                    + "the system: the connection is simply re-established, transparently. Lower it on a "
                    + "system with a constrained job storage limit, raise it to replace the connection less "
                    + "often.",
            DEFAULT_POINTER_HANDLE_THRESHOLD);

    public static final Field TOPIC_NAMING_STRATEGY = Field.create("topic.naming.strategy")
            .withDisplayName("Topic naming strategy class")
            .withType(Type.CLASS)
            .withWidth(Width.MEDIUM)
            .withImportance(Importance.MEDIUM)
            .withDescription("The name of the TopicNamingStrategy class that should be used to determine the topic name " +
                    "for data change, schema change, transaction, heartbeat event etc.")
            .withDefault(HostnamePrefixNamingScheme.class.getName());

    public static final Field TRIM_NON_XML_CHARSEQUENCE_FIELD_MODE = Field.create("trim.non.xml.charsequence.field.mode")
            .withDisplayName("Trim Non-XML Charsequence Field Mode")
            .withEnum(CharSequenceTrimMode.class, CharSequenceTrimMode.BOTH)
            .withImportance(Importance.LOW)
            .withDescription("An enum to control trimming the leading and trailing whitespace for non-XML columns that " +
                    "are CHAR/VARCHAR or other Character sequence types.  Defaults to both to conform to pre-existing " +
                    "functionality.  Options are none, both, leading, trailing.");

    public static final Field UNAVAILABLE_POSITION_RECOVERY = Field.create("journal.unavailable.position.recovery")
            .withDisplayName("Recovery strategy when the stored journal position is no longer available")
            .withEnum(UnavailablePositionRecovery.class, UnavailablePositionRecovery.FAIL)
            .withImportance(Importance.MEDIUM)
            .withDescription("Controls how the connector recovers when the journal position it wants to read is no longer "
                    + "on the server (typically after downtime longer than the journal retention, or because the journal "
                    + "or its receivers were deleted while the connector was streaming). "
                    + "'fail' (default) stops with a distinct, non-transient error so an orchestrator can "
                    + "reset the offset or alert; 'snapshot' resets the offset and lets the configured snapshot.mode take "
                    + "a fresh snapshot to fill the gap (detected while streaming it fails the task so the snapshot runs "
                    + "on restart); 'earliest' resets streaming to the earliest available journal "
                    + "receiver and continues, logging that changes between the lost position and the earliest receiver "
                    + "are unrecoverable.");

    /** Shares Kafka Connect's own {@code errors.tolerance}, so it is left out of {@link #configDef()}. */
    public static final Field ERRORS_TOLERANCE = Field.create("errors.tolerance")
            .withDisplayName("Tolerance for tables that cannot be captured")
            .withEnum(ErrorsTolerance.class, ErrorsTolerance.NONE)
            .withImportance(Importance.MEDIUM)
            .withDescription("What to do with a table in 'table.include.list' that exists but has no journal, "
                    + "either because it was never journaled or because it is an SQL view. 'none' (default) fails "
                    + "startup; 'all' logs it at ERROR level and captures the remaining tables. A table that does "
                    + "not exist is always dropped, as Debezium ignores include-list entries with no matching table. "
                    + "Tables spanning more than one journal, or none of them being capturable, always fails.");

    public As400ConnectorConfig(Configuration config) {
        // Debezium treats table.include.list as regex (filters + the base snapshot's re-filter via
        // tableIncludeList()), so names with metacharacters like $ are normalized. The journal path
        // reads the raw list via getRawTableIncludeList() instead.
        super(normalizeTableIncludeList(config), new SystemTablesPredicate(),
                tableToString, DEFAULT_SNAPSHOT_FETCH_SIZE, ColumnFilterMode.SCHEMA, false);
        this.config = config;
        this.snapshotMode = SnapshotMode.parse(config.getString(SNAPSHOT_MODE), SNAPSHOT_MODE.defaultValueAsString());
        this.tableFilters = new As400NormalRelationalTableFilters(normalizeTableIncludeList(config), new SystemTablesPredicate(), tableToString);
        this.charSequenceTrimMode = CharSequenceTrimMode.parse(config.getString(TRIM_NON_XML_CHARSEQUENCE_FIELD_MODE),
                TRIM_NON_XML_CHARSEQUENCE_FIELD_MODE.defaultValueAsString());
        this.unavailablePositionRecovery = UnavailablePositionRecovery.parse(
                config.getString(UNAVAILABLE_POSITION_RECOVERY), UNAVAILABLE_POSITION_RECOVERY.defaultValueAsString());
        this.errorsTolerance = ErrorsTolerance.parse(config.getString(ERRORS_TOLERANCE), ERRORS_TOLERANCE.defaultValueAsString());
    }

    /** The raw {@code table.include.list} as supplied (e.g. {@code PYP31."$SCHAR"}), for the journal path. */
    public String getRawTableIncludeList() {
        return config.getString(TABLE_INCLUDE_LIST);
    }

    /** Escapes AS400 table names with regex metacharacters so they match as literals. */
    private static Configuration normalizeTableIncludeList(Configuration config) {
        String includeList = config.getString(TABLE_INCLUDE_LIST);
        if (includeList == null || includeList.isBlank()) {
            return config;
        }
        String normalized = Arrays.stream(includeList.split(","))
                .map(String::trim)
                .map(As400ConnectorConfig::normalizeTablePattern)
                .collect(Collectors.joining(","));
        return config.edit().with(TABLE_INCLUDE_LIST, normalized).build();
    }

    private static String normalizeTablePattern(String pattern) {
        int dotIdx = pattern.lastIndexOf('.');
        if (dotIdx < 0) {
            return pattern;
        }
        String schema = pattern.substring(0, dotIdx);
        String table = pattern.substring(dotIdx + 1);
        // A quoted name is an explicit literal identifier; an unquoted name with $ would break as regex.
        if (table.startsWith("\"") && table.endsWith("\"") && table.length() > 2) {
            table = table.substring(1, table.length() - 1);
        }
        else if (!table.contains("$")) {
            return pattern;
        }
        return schema + "\\." + Pattern.quote(table);
    }

    // used by the snapshot to limit the additional tables for a change in configuration
    public As400ConnectorConfig(Configuration config, String incrementalTableFilters) {
        this(config);
        this.incrementalTables = incrementalTableFilters;
        this.tableFilters = new As400AdditionalRelationalTableFilters(
                normalizeTableIncludeList(config), new SystemTablesPredicate(), tableToString, incrementalTableFilters);
    }

    public SnapshotMode getSnapshotMode() {
        return snapshotMode;
    }

    public UnavailablePositionRecovery getUnavailablePositionRecovery() {
        return unavailablePositionRecovery;
    }

    /**
     * Whether an included table that exists but has no journal should be skipped with a loud ERROR instead
     * of failing startup. A table that does not exist is dropped regardless.
     */
    public boolean skipUncapturableTables() {
        return errorsTolerance == ErrorsTolerance.ALL;
    }

    @Override
    public Optional<? extends EnumeratedValue> getSnapshotLockingMode() {
        return Optional.empty();
    }

    public String getHostname() {
        return config.getString(HOSTNAME);
    }

    public String getUser() {
        return config.getString(USER);
    }

    public String getPassword() {
        return config.getString(PASSWORD);
    }

    public String getSchema() {
        return config.getString(SCHEMA).toUpperCase();
    }

    /**
     * The set of libraries (schemas) to capture, derived from the raw {@code table.include.list}.
     *
     * <p>A connector may capture tables spread across several libraries (e.g.
     * {@code LIB1.T1,LIB2.T2}). Each qualified entry contributes its own library; unqualified
     * entries fall back to {@link #getSchema()}, which is always included. The library is taken as
     * the segment before the table name, mirroring {@code As400JdbcConnection.shortIncludes}.</p>
     *
     * @return the distinct uppercased libraries, with {@link #getSchema()} first
     */
    public Set<String> getCaptureSchemas() {
        final Set<String> schemas = new LinkedHashSet<>();
        final String defaultSchema = getSchema();
        schemas.add(defaultSchema);

        final String includeList = getRawTableIncludeList();
        if (includeList == null || includeList.isBlank()) {
            return schemas;
        }
        for (String entry : includeList.split(",")) {
            entry = entry.trim();
            if (entry.isEmpty()) {
                continue;
            }
            int o = entry.lastIndexOf('.');
            if (o <= 0) {
                schemas.add(defaultSchema);
                continue;
            }
            String schemaName = entry.substring(0, o);
            // Handle the "database.schema.table" form: keep only the schema segment.
            o = schemaName.lastIndexOf('.');
            if (o > 0) {
                schemaName = schemaName.substring(o + 1);
            }
            schemas.add(schemaName.toUpperCase());
        }
        return schemas;
    }

    public Integer getJournalBufferSize() {
        return config.getInteger(BUFFER_SIZE);
    }

    public Integer getSocketTimeout() {
        return config.getInteger(SOCKET_TIMEOUT);
    }

    public Integer getMaxServerSideEntries() {
        return config.getInteger(MAX_SERVER_SIDE_ENTRIES);
    }

    public Integer getMaxRetrievalTimeout() {
        return config.getInteger(MAX_RETRIEVAL_TIMEOUT);
    }

    public long getBlockingSnapshotPauseTimeout() {
        return config.getLong(BLOCKING_SNAPSHOT_PAUSE_TIMEOUT);
    }

    public Integer getFromCcsid() {
        return config.getInteger(FROM_CCSID);
    }

    public Integer getToCcsid() {
        return config.getInteger(TO_CCSID);
    }

    public boolean isSecure() {
        return config.getBoolean(SECURE);
    }

    public String diagnosticsFolder() {
        return config.getString(DIAGNOSTICS_FOLDER);
    }

    public String snapshotQueryTimeLimit() {
        return config.getString(SNAPSHOT_QUERY_TIME_LIMIT);
    }

    public Long cacheAdditionalDelay() {
        return config.getLong(JOURNAL_CACHE_ADDITIONAL_DELAY);
    }

    public boolean isTransactionMgmtEnabled() {
        return config.getBoolean(TRANSACTION_MGMT_ENABLED);
    }

    public boolean isLobFetchEnabled() {
        return config.getBoolean(LOB_FETCH);
    }

    public Long getPointerHandleThreshold() {
        return config.getLong(POINTER_HANDLE_THRESHOLD);
    }

    public JournalProcessedPosition getOffset() {
        final String receiver = config.getString(As400OffsetContext.RECEIVER);
        final String lib = config.getString(As400OffsetContext.RECEIVER_LIBRARY);
        final String offset = config.getString(As400OffsetContext.EVENT_SEQUENCE);
        final Boolean processed = config.getBoolean(As400OffsetContext.PROCESSED);
        final Long configTime = config.getLong(As400OffsetContext.EVENT_TIME);
        final Instant time = (configTime == null) ? Instant.ofEpochSecond(0) : Instant.ofEpochSecond(configTime);
        return new JournalProcessedPosition(offset, receiver, lib, time, (processed == null) ? false : processed);
    }

    private static class SystemTablesPredicate implements TableFilter {

        @Override
        public boolean isIncluded(TableId t) {
            return !(t.schema().toLowerCase().equals("QSYS2") || t.schema().toLowerCase().equals("QSYS")); // TODO
        }
    }

    @Override
    public String getContextName() {
        return Module.contextName();
    }

    @Override
    public String getConnectorName() {
        return Module.name();
    }

    @Override
    protected SourceInfoStructMaker<?> getSourceInfoStructMaker(Version version) {
        return new As400SourceInfoStructMaker(Module.name(), Module.version(), this);
    }

    public static Field.Set ALL_FIELDS = Field.setOf(JdbcConfiguration.HOSTNAME, USER, PASSWORD, SCHEMA, BUFFER_SIZE,
            RelationalDatabaseConnectorConfig.SNAPSHOT_SELECT_STATEMENT_OVERRIDES_BY_TABLE, SOCKET_TIMEOUT,
            MAX_SERVER_SIDE_ENTRIES, TOPIC_NAMING_STRATEGY, FROM_CCSID, TO_CCSID, SECURE,
            DIAGNOSTICS_FOLDER, TRIM_NON_XML_CHARSEQUENCE_FIELD_MODE, JOURNAL_CACHE_ADDITIONAL_DELAY, TRANSACTION_MGMT_ENABLED,
            UNAVAILABLE_POSITION_RECOVERY, SNAPSHOT_QUERY_TIME_LIMIT, MAX_RETRIEVAL_TIMEOUT, BLOCKING_SNAPSHOT_PAUSE_TIMEOUT,
            ERRORS_TOLERANCE, LOB_FETCH, POINTER_HANDLE_THRESHOLD);

    public static ConfigDef configDef() {
        final ConfigDef c = RelationalDatabaseConnectorConfig.CONFIG_DEFINITION.edit()
                .name("ibmi")
                .type(
                        HOSTNAME, USER, PASSWORD, SCHEMA, BUFFER_SIZE,
                        SOCKET_TIMEOUT, FROM_CCSID, TO_CCSID, SECURE,
                        DIAGNOSTICS_FOLDER, TRIM_NON_XML_CHARSEQUENCE_FIELD_MODE, JOURNAL_CACHE_ADDITIONAL_DELAY, TRANSACTION_MGMT_ENABLED,
                        UNAVAILABLE_POSITION_RECOVERY, SNAPSHOT_QUERY_TIME_LIMIT, MAX_RETRIEVAL_TIMEOUT, BLOCKING_SNAPSHOT_PAUSE_TIMEOUT,
                        LOB_FETCH,
                        POINTER_HANDLE_THRESHOLD)
                .connector(
                        SCHEMA_NAME_ADJUSTMENT_MODE)
                .events(
                        As400OffsetContext.EVENT_SEQUENCE_FIELD,
                        As400OffsetContext.RECEIVER_FIELD,
                        As400OffsetContext.RECEIVER_LIBRARY_FIELD,
                        As400OffsetContext.PROCESSED_FIELD)
                .create().configDef();
        return c;
    }

    public static final Field SNAPSHOT_MODE = Field.create("snapshot.mode")
            .withDisplayName("Snapshot mode")
            .withGroup(Field.createGroupEntry(Field.Group.CONNECTOR_SNAPSHOT, 0))
            .withEnum(SnapshotMode.class, SnapshotMode.INITIAL)
            .withWidth(Width.SHORT)
            .withImportance(Importance.MEDIUM)
            .withDescription("The criteria for running a snapshot upon startup of the connector. "
                    + "Select one of the following snapshot options: "
                    + "'always': The connector runs a snapshot every time that it starts. After the snapshot completes, the connector begins to stream changes from the transaction log.; "
                    + "'initial' (default): If the connector does not detect any offsets for the logical server name, it runs a snapshot that captures the current full state of the configured tables. After the snapshot completes, the connector begins to stream changes from the transaction log. "
                    + "'initial_only': The connector performs a snapshot as it does for the 'initial' option, but after the connector completes the snapshot, it stops, and does not stream changes from the transaction log.; "
                    + "'never': The connector does not run a snapshot. Upon first startup, the connector immediately begins reading from the beginning of the transaction log. "
                    + "'exported': This option is deprecated; use 'initial' instead.; "
                    + "'custom': The connector loads a custom class  to specify how the connector performs snapshots. For more information, see Custom snapshotter SPI in the PostgreSQL connector documentation.");

    /**
     * The set of predefined Snapshotter options or aliases.
     */
    public enum SnapshotMode implements EnumeratedValue {

        /**
         * Always perform a snapshot when starting.
         */
        ALWAYS("always"),

        /**
         * Perform a snapshot only upon initial startup of a connector.
         */
        INITIAL("initial"),

        /**
         * Never perform a snapshot and only receive logical changes.
         */
        NO_DATA("no_data"),

        /**
         * Perform a snapshot and then stop before attempting to receive any logical changes.
         */
        INITIAL_ONLY("initial_only"),

        /**
         * Perform a snapshot when it is needed.
         */
        WHEN_NEEDED("when_needed"),

        /**
         * Allows control over snapshots by setting connectors properties prefixed with 'snapshot.mode.configuration.based'.
         */
        CONFIGURATION_BASED("configuration_based"),

        /**
         * Inject a custom snapshotter, which allows for more control over snapshots.
         */
        CUSTOM("custom");

        private final String value;

        SnapshotMode(String value) {
            this.value = value;

        }

        @Override
        public String getValue() {
            return value;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @return the matching option, or null if no match is found
         */
        public static SnapshotMode parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (SnapshotMode option : SnapshotMode.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }
            return null;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @param defaultValue the default value; may be null
         * @return the matching option, or null if no match is found and the non-null default is invalid
         */
        public static SnapshotMode parse(String value, String defaultValue) {
            SnapshotMode mode = parse(value);
            if (mode == null && defaultValue != null) {
                mode = parse(defaultValue);
            }
            return mode;
        }
    }

    /**
     * Recovery strategy applied when the journal position is no longer available on the server (receiver
     * pruned, rotated or deleted), both at startup and when it happens while streaming.
     */
    public enum UnavailablePositionRecovery implements EnumeratedValue {

        /**
         * Stop with a distinct, non-transient error ({@link OffsetNoLongerAvailableException}) so the
         * orchestrator can reset the offset / trigger a snapshot / alert. Default; preserves the
         * pre-existing "do not silently lose data" behaviour, but without a blind crash-loop.
         */
        FAIL("fail"),

        /**
         * Reset the offset and let the configured {@code snapshot.mode} take a fresh snapshot to
         * re-establish table state, then resume streaming from the current journal position. Detected while
         * streaming, the task is failed instead so the snapshot is taken by the startup recovery on restart.
         */
        SNAPSHOT("snapshot"),

        /**
         * Reset streaming to the earliest journal receiver still available and continue. Intended for
         * streaming-only / {@code no_data} connectors; changes between the lost position and the
         * earliest available receiver are unrecoverable and a loud warning is logged.
         */
        EARLIEST("earliest");

        private final String value;

        UnavailablePositionRecovery(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        public static UnavailablePositionRecovery parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (UnavailablePositionRecovery option : UnavailablePositionRecovery.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }
            return null;
        }

        public static UnavailablePositionRecovery parse(String value, String defaultValue) {
            UnavailablePositionRecovery mode = parse(value);
            if (mode == null && defaultValue != null) {
                mode = parse(defaultValue);
            }
            return mode;
        }
    }

    /**
     * How much of the include list the connector insists on being able to capture before it starts. The
     * default is strict: a configured table that silently never streams is worse than a visible failure.
     */
    public enum ErrorsTolerance implements EnumeratedValue {

        NONE("none"),

        ALL("all");

        private final String value;

        ErrorsTolerance(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        public static ErrorsTolerance parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (final ErrorsTolerance option : ErrorsTolerance.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }
            return null;
        }

        public static ErrorsTolerance parse(String value, String defaultValue) {
            ErrorsTolerance tolerance = parse(value);
            if (tolerance == null && defaultValue != null) {
                tolerance = parse(defaultValue);
            }
            return tolerance;
        }
    }

    @Override
    // used by the snapshot to limit the additional tables for a change in configuration
    public RelationalTableFilters getTableFilters() {
        if (tableFilters == null) {
            return super.getTableFilters();
        }
        return tableFilters;
    }

    @Override
    public JdbcConfiguration getJdbcConfig() {
        JdbcConfiguration dbConfig = super.getJdbcConfig();
        JdbcConfiguration.Builder dbFromConfig = JdbcConfiguration.create();
        dbFromConfig.with("secure", Boolean.toString(isSecure()));
        int fromCcsid = getFromCcsid();
        if (fromCcsid != -1) {
            dbFromConfig.with("from.ccsid", Integer.toString(fromCcsid));
        }
        int toCcsid = getToCcsid();
        if (toCcsid != -1) {
            dbFromConfig.with("to.ccsid", Integer.toString(toCcsid));
        }

        Configuration driverConfig = this.config.subset(CommonConnectorConfig.DRIVER_CONFIG_PREFIX, true);
        return JdbcConfiguration.adapt(dbConfig.merge(dbFromConfig.build()).merge(driverConfig));
    }

    public CharSequenceTrimMode getCharSequenceTrimMode() {
        return charSequenceTrimMode;
    }

    /**
     * The set of predefined ways of trimming charsequence fields.
     */
    public enum CharSequenceTrimMode implements EnumeratedValue {

        /**
         * Do not trim CharSequence columns.
         */
        NONE("none"),

        /**
         * Trim only leading whitespace.
         */
        LEADING("leading"),

        /**
         * Trim only trailing whitespace
         */
        TRAILING("trailing"),

        /**
         * Trim both leading and trailing whitespace
         */
        BOTH("both");

        private final String value;

        CharSequenceTrimMode(String value) {
            this.value = value;
        }

        @Override
        public String getValue() {
            return value;
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value the configuration property value; may not be null
         * @return the matching option, or null if no match is found
         */
        public static CharSequenceTrimMode parse(String value) {
            if (value == null) {
                return null;
            }
            value = value.trim();
            for (final CharSequenceTrimMode option : CharSequenceTrimMode.values()) {
                if (option.getValue().equalsIgnoreCase(value)) {
                    return option;
                }
            }
            throw new IllegalArgumentException("Value for CharSequenceTrim Mode of: \"" + value + "\" is not valid.");
        }

        /**
         * Determine if the supplied value is one of the predefined options.
         *
         * @param value        the configuration property value; may not be null
         * @param defaultValue the default value; may be null
         * @return the matching option, or null if no match is found and the non-null
         *         default is invalid
         */
        public static CharSequenceTrimMode parse(String value, String defaultValue) {
            CharSequenceTrimMode mode = parse(value);
            if (mode == null && defaultValue != null) {
                mode = parse(defaultValue);
            }
            return mode;
        }

        public String strip(String fixed) {
            return switch (this) {
                case NONE -> fixed;
                case LEADING -> fixed.stripLeading();
                case TRAILING -> fixed.stripTrailing();
                case BOTH -> fixed.trim();
            };
        }
    }
}
