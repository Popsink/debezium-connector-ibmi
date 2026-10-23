/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.kafka.connect.source.SourceRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.DebeziumException;
import io.debezium.bean.StandardBeanNames;
import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.config.Field;
import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.connector.base.DefaultQueueProvider;
import io.debezium.connector.common.BaseSourceTask;
import io.debezium.connector.common.CdcSourceTaskContext;
import io.debezium.connector.common.DebeziumHeaderProducer;
import io.debezium.connector.db2as400.metrics.As400ChangeEventSourceMetricsFactory;
import io.debezium.connector.db2as400.metrics.As400StreamingChangeEventSourceMetrics;
import io.debezium.document.DocumentReader;
import io.debezium.ibmi.db2.journal.retrieve.FileFilter;
import io.debezium.ibmi.db2.journal.retrieve.JournalInfoRetrieval;
import io.debezium.ibmi.db2.journal.retrieve.JournalLobFetcher;
import io.debezium.ibmi.db2.journal.retrieve.JournalProcessedPosition;
import io.debezium.jdbc.DefaultMainConnectionProvidingConnectionFactory;
import io.debezium.jdbc.MainConnectionProvidingConnectionFactory;
import io.debezium.pipeline.ChangeEventSourceCoordinator;
import io.debezium.pipeline.DataChangeEvent;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.notification.NotificationService;
import io.debezium.pipeline.signal.SignalProcessor;
import io.debezium.pipeline.spi.Offsets;
import io.debezium.relational.CustomConverterRegistry;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.relational.TableId;
import io.debezium.schema.SchemaFactory;
import io.debezium.schema.SchemaNameAdjuster;
import io.debezium.snapshot.SnapshotterService;
import io.debezium.spi.topic.TopicNamingStrategy;
import io.debezium.util.Clock;
import io.debezium.util.Strings;

public class As400ConnectorTask extends BaseSourceTask<As400Partition, As400OffsetContext> {
    private static final Logger LOGGER = LoggerFactory.getLogger(As400ConnectorTask.class);
    private volatile ChangeEventQueue<DataChangeEvent> queue;
    private static final String CONTEXT_NAME = "db2as400-server-connector-task";
    private As400DatabaseSchema schema;
    private ErrorHandler errorHandler;
    private As400ConnectorConfig connectorConfig;
    private CdcSourceTaskContext<As400ConnectorConfig> taskContext;
    private As400JdbcConnection jdbcConnection;
    private As400RpcConnection rpcConnection;

    @Override
    public String version() {
        return Module.version();
    }

    @Override
    public CdcSourceTaskContext<? extends CommonConnectorConfig> preStart(Configuration config) {

        connectorConfig = new As400ConnectorConfig(config);
        // fail fast on a recovery strategy that contradicts errors.tolerance, rather than discovering the
        // contradiction at the first pruned receiver, when it would already have cost data
        connectorConfig.validateUnavailablePositionRecovery();
        taskContext = new CdcSourceTaskContext<>(config, connectorConfig, connectorConfig.getCustomMetricTags());

        return taskContext;
    }

    @Override
    protected ChangeEventSourceCoordinator<As400Partition, As400OffsetContext> start(Configuration config) {
        LOGGER.info("starting connector task {}", version());
        // TODO resolve schema FIELD_NAME_ADJUSTMENT_MODE to be SchemaNameAdjuster.AVRO_FIELD_NAMER

        @SuppressWarnings("unchecked")
        final TopicNamingStrategy<TableId> topicNamingStrategy = connectorConfig
                .getTopicNamingStrategy(As400ConnectorConfig.TOPIC_NAMING_STRATEGY, true);

        final SchemaNameAdjuster schemaNameAdjuster = connectorConfig.schemaNameAdjuster();

        final MainConnectionProvidingConnectionFactory<As400JdbcConnection> jdbcConnectionFactory = new DefaultMainConnectionProvidingConnectionFactory<>(
                () -> new As400JdbcConnection(connectorConfig.getJdbcConfig()));
        this.jdbcConnection = jdbcConnectionFactory.mainConnection();
        registerServiceProviders(connectorConfig.getServiceRegistry());

        CustomConverterRegistry customConverterRegistry = connectorConfig.getServiceRegistry().tryGetService(CustomConverterRegistry.class);

        this.schema = new As400DatabaseSchema(connectorConfig, jdbcConnection, topicNamingStrategy, schemaNameAdjuster, customConverterRegistry, taskContext);

        Offsets<As400Partition, As400OffsetContext> previousOffsetPartition = getPreviousOffsets(
                new As400Partition.Provider(connectorConfig), new As400OffsetContext.Loader(connectorConfig));
        As400OffsetContext previousOffset = previousOffsetPartition.getTheOnlyOffset();

        // Manual Bean Registration
        connectorConfig.getBeanRegistry().add(StandardBeanNames.CONFIGURATION, config);
        connectorConfig.getBeanRegistry().add(StandardBeanNames.CONNECTOR_CONFIG, connectorConfig);
        connectorConfig.getBeanRegistry().add(StandardBeanNames.DATABASE_SCHEMA, schema);
        connectorConfig.getBeanRegistry().add(StandardBeanNames.CDC_SOURCE_TASK_CONTEXT, taskContext);
        connectorConfig.getBeanRegistry().add(StandardBeanNames.JDBC_CONNECTION, jdbcConnection);
        connectorConfig.getBeanRegistry().add(StandardBeanNames.OFFSETS, previousOffsetPartition);

        // Service providers

        // Set up the task record queue ...
        this.queue = new ChangeEventQueue.Builder<DataChangeEvent>()
                .maxBatchSize(connectorConfig.getMaxBatchSize())
                .maxQueueSize(connectorConfig.getMaxQueueSize())
                .queueProvider(new DefaultQueueProvider<>(connectorConfig.getMaxQueueSize()))
                .pollInterval(connectorConfig.getPollInterval())
                .loggingContextSupplier(() -> taskContext.configureLoggingContext(CONTEXT_NAME)).build();

        errorHandler = new As400ErrorHandler(connectorConfig, queue, errorHandler);

        final SnapshotterService snapshotterService = connectorConfig.getServiceRegistry().tryGetService(SnapshotterService.class);

        final As400EventMetadataProvider metadataProvider = new As400EventMetadataProvider();

        final As400TaskContext taskContext = new As400TaskContext(config,
                connectorConfig,
                connectorConfig.getCustomMetricTags());
        final As400ConnectorConfig newConfig = taskContext.getConfig();

        final As400StreamingChangeEventSourceMetrics streamingMetrics = new As400StreamingChangeEventSourceMetrics(
                taskContext, queue, metadataProvider, schema::tableIds);

        // journal reading needs raw schema.table names, not the regex form tableIncludeList() returns
        String configuredIncludes = newConfig.getRawTableIncludeList();
        String signalDataCollection = config.getString(RelationalDatabaseConnectorConfig.SIGNAL_DATA_COLLECTION);
        if (!Strings.isNullOrBlank(signalDataCollection)) {
            configuredIncludes = configuredIncludes.length() > 0 ? String.format("%s,%s", configuredIncludes, signalDataCollection) : "";
        }

        final List<FileFilter> shortIncludes = jdbcConnection.shortIncludes(schema.getSchemaName(),
                configuredIncludes, connectorConfig.skipUncapturableTables());
        // No filters means "no include list", i.e. read the whole library's journal - which every configured
        // table having been dropped must not silently turn into.
        if (!Strings.isNullOrBlank(configuredIncludes) && shortIncludes.isEmpty()) {
            throw new DebeziumException("none of the included tables exists, there is nothing to capture. Tables "
                    + "requested: " + configuredIncludes);
        }

        final long cacheWait = JournalInfoRetrieval.getJournalCacheDurationInMilliseconds(jdbcConnection);

        this.rpcConnection = new As400RpcConnection(connectorConfig, streamingMetrics,
                shortIncludes, cacheWait);

        // the journal is only resolved once the rpc connection is up, and lob data can only be read
        // back out of the journal it was written to
        if (connectorConfig.isLobFetchEnabled()) {
            rpcConnection.getJournalInfo().ifPresentOrElse(
                    journalInfo -> schema.getFileDecoder()
                            .setLobFetcher(new JournalLobFetcher(jdbcConnection, journalInfo)),
                    // without this the only clue is the decoder's own warning, which points at the
                    // lob.fetch setting - the one thing that is not the problem here
                    () -> LOGGER.warn("lob.fetch is enabled but the journal could not be resolved, so lob "
                            + "columns will stream as null. The lob.fetch setting is not the cause; see "
                            + "the earlier failure to resolve the journal for {}", connectorConfig.getSchema()));
        }

        // An operator-requested reposition runs first: it is the escape hatch for a connector that cannot
        // catch up, and it has to work whether or not the stored position is still available - including
        // under the default 'fail' recovery, which would otherwise throw before we got here.
        applyOperatorReposition(connectorConfig, rpcConnection, previousOffsetPartition);

        // Detect and recover from a stored position whose receiver has been pruned, before Debezium
        // core turns an unavailable position into a generic crash-loop.
        applyUnavailablePositionRecovery(connectorConfig, rpcConnection, previousOffsetPartition);

        validateSchemaHistory(connectorConfig, rpcConnection::validateLogPosition, previousOffsetPartition, schema,
                snapshotterService.getSnapshotter());

        As400ConnectorConfig snapshotConnectorConfig = connectorConfig;

        final SignalProcessor<As400Partition, As400OffsetContext> signalProcessor = new SignalProcessor<>(
                As400RpcConnector.class, connectorConfig, Map.of(),
                getAvailableSignalChannels(),
                DocumentReader.defaultReader(),
                previousOffsetPartition);

        final EventDispatcher<As400Partition, TableId> dispatcher = new EventDispatcher<>(connectorConfig, // CommonConnectorConfig
                topicNamingStrategy, // TopicSelector
                schema, // DatabaseSchema
                queue, // ChangeEventQueue
                newConfig.getTableFilters().dataCollectionFilter(), // DataCollectionFilter
                DataChangeEvent::new, // ! ChangeEventCreator
                metadataProvider,
                schemaNameAdjuster,
                signalProcessor,
                connectorConfig.getServiceRegistry().tryGetService(DebeziumHeaderProducer.class));

        final Clock clock = Clock.system();

        final As400ChangeEventSourceFactory changeFactory = new As400ChangeEventSourceFactory(newConfig, snapshotConnectorConfig, rpcConnection,
                jdbcConnectionFactory, errorHandler, dispatcher, clock, schema, snapshotterService);

        final NotificationService<As400Partition, As400OffsetContext> notificationService = new NotificationService<>(getNotificationChannels(),
                connectorConfig, SchemaFactory.get(), dispatcher::enqueueNotification);

        final ChangeEventSourceCoordinator<As400Partition, As400OffsetContext> coordinator = new ChangeEventSourceCoordinator<>(
                previousOffsetPartition, errorHandler, As400RpcConnector.class, newConfig, changeFactory,
                new As400ChangeEventSourceMetricsFactory(streamingMetrics), dispatcher, schema,
                signalProcessor, notificationService, snapshotterService);
        coordinator.start(taskContext, this.queue, metadataProvider);

        return coordinator;
    }

    /**
     * When the stored journal position points at a receiver that has been pruned off the server, apply
     * the configured {@link As400ConnectorConfig.UnavailablePositionRecovery} strategy instead of letting
     * Debezium core throw a generic, retried-to-death engine failure. Transient validation failures are
     * left to propagate so the engine restart is a legitimate retry.
     * <p>
     * A receiver can also be pruned once streaming is under way; that is handled by the equivalent recovery
     * in {@code As400StreamingChangeEventSource.applyUnavailablePositionRecovery}.
     */
    /**
     * Moves streaming to the current journal head when a reposition token is armed and has not been applied yet.
     * <p>
     * This is the deliberate, operator-driven counterpart of
     * {@link As400ConnectorConfig.UnavailablePositionRecovery#LATEST}, which only fires on a pruned position. A
     * connector whose read rate is below the journal's own growth rate never catches up even though its position
     * stays perfectly available, and there is no other supported way to move it forward.
     * <p>
     * The applied token is written into the offset, so the reposition happens once rather than on every task
     * restart - a reposition that repeated silently would skip the journal again each time the pod moved. The
     * skipped range is a declared data gap and must be recovered by a backfill; the {@code errors.tolerance=all}
     * requirement is enforced in {@link As400ConnectorConfig#validateUnavailablePositionRecovery()}.
     */
    static void applyOperatorReposition(As400ConnectorConfig connectorConfig, As400RpcConnection rpcConnection,
                                        Offsets<As400Partition, As400OffsetContext> previousOffsets) {
        final String token = connectorConfig.getRepositionToken();
        if (token == null || token.isBlank()) {
            return;
        }
        for (Map.Entry<As400Partition, As400OffsetContext> entry : previousOffsets) {
            final As400OffsetContext offset = entry.getValue();
            if (offset == null) {
                continue;
            }
            if (token.equals(offset.getRepositionToken())) {
                LOGGER.info("journal reposition '{}' was already applied; leaving streaming at {}. Change "
                        + "'{}' to arm another one.", token, offset.getPosition(), As400ConnectorConfig.REPOSITION_TOKEN.name());
                continue;
            }
            final JournalProcessedPosition head = currentJournalHead(rpcConnection, offset);
            LOGGER.warn("DATA GAP: operator-requested reposition '{}': moving streaming from {} to the current journal "
                    + "head {}. Every change between the two is skipped and can only be recovered by a backfill.",
                    token, offset.getPosition(), head);
            offset.setPosition(head);
            offset.setRepositionToken(token);
        }
    }

    static void applyUnavailablePositionRecovery(As400ConnectorConfig connectorConfig, As400RpcConnection rpcConnection,
                                                 Offsets<As400Partition, As400OffsetContext> previousOffsets) {
        if (!connectorConfig.isLogPositionCheckEnabled()) {
            return;
        }
        final As400ConnectorConfig.UnavailablePositionRecovery mode = connectorConfig.getUnavailablePositionRecovery();
        for (Map.Entry<As400Partition, As400OffsetContext> entry : previousOffsets) {
            final As400OffsetContext offset = entry.getValue();
            if (offset == null || rpcConnection.checkLogPosition(offset) != As400RpcConnection.PositionAvailability.PRUNED) {
                continue;
            }
            switch (mode) {
                case FAIL:
                    throw new OffsetNoLongerAvailableException(
                            "stored journal position " + offset.getPosition() + " is no longer available on the server "
                                    + "(pruned receiver). Reset the offset and trigger a snapshot, or set "
                                    + "'" + As400ConnectorConfig.UNAVAILABLE_POSITION_RECOVERY.name()
                                    + "' to 'snapshot' or 'earliest' to auto-recover, or to 'latest' together with '"
                                    + As400ConnectorConfig.ERRORS_TOLERANCE.name() + "="
                                    + As400ConnectorConfig.ErrorsTolerance.ALL.getValue()
                                    + "' to accept the declared gap.");
                case SNAPSHOT:
                    LOGGER.warn("Stored journal position {} is no longer available (pruned receiver); resetting the offset so "
                            + "snapshot.mode '{}' can take a fresh snapshot to fill the gap.", offset.getPosition(), connectorConfig.getSnapshotMode().getValue());
                    previousOffsets.resetOffset(entry.getKey());
                    break;
                case EARLIEST:
                    LOGGER.warn("DATA GAP: stored journal position {} is no longer available (pruned receiver); resetting streaming to "
                            + "the earliest available journal receiver. Changes between the lost position and the earliest available "
                            + "receiver are unrecoverable.", offset.getPosition());
                    offset.setPosition(new JournalProcessedPosition());
                    break;
                case LATEST:
                    final JournalProcessedPosition head = currentJournalHead(rpcConnection, offset);
                    LOGGER.warn("DATA GAP: stored journal position {} is no longer available (pruned receiver); resetting streaming to "
                            + "the current journal head {}. Changes between the lost position and the head are unrecoverable - as they "
                            + "are under 'earliest', which replays the whole retained journal to recover none of them.",
                            offset.getPosition(), head);
                    offset.setPosition(head);
                    break;
            }
        }
    }

    /**
     * The position the journal is currently attached at, marked processed so streaming resumes after it
     * rather than re-reading it. A failure here is transient (RPC/connection), so it propagates and the
     * engine restart is a legitimate retry rather than a silent reset to an arbitrary position.
     */
    private static JournalProcessedPosition currentJournalHead(As400RpcConnection rpcConnection, As400OffsetContext offset) {
        try {
            return new JournalProcessedPosition(rpcConnection.getCurrentPosition(), Instant.now(), true);
        }
        catch (As400RpcConnection.RpcException e) {
            throw new DebeziumException("transient failure while resolving the current journal head to recover lost journal position "
                    + offset.getPosition() + " from", e);
        }
    }

    @Override
    protected String connectorName() {
        return Module.name();
    }

    @Override
    protected List<SourceRecord> doPoll() throws InterruptedException {
        final List<DataChangeEvent> records = queue.poll();

        final List<SourceRecord> sourceRecords = records.stream().map(DataChangeEvent::getRecord)
                .collect(Collectors.toList());

        return sourceRecords;
    }

    @Override
    protected Optional<ErrorHandler> getErrorHandler() {
        return Optional.ofNullable(errorHandler);
    }

    @Override
    protected void doStop() {
        if (rpcConnection != null) {
            rpcConnection.close();
            rpcConnection = null;
        }

        try {
            if (jdbcConnection != null) {
                jdbcConnection.close();
                jdbcConnection = null;
            }
        }
        catch (final SQLException e) {
            LOGGER.error("Exception while closing JDBC connection", e);
        }

        if (schema != null) {
            schema.close();
            schema = null;
        }
    }

    @Override
    protected Iterable<Field> getAllConfigurationFields() {
        return As400ConnectorConfig.ALL_FIELDS;
    }
}
