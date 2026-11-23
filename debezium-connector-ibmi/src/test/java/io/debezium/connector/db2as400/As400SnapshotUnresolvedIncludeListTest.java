/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.jdbc.MainConnectionProvidingConnectionFactory;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.notification.NotificationService;
import io.debezium.pipeline.source.spi.ChangeEventSource.ChangeEventSourceContext;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.relational.RelationalSnapshotChangeEventSource.RelationalSnapshotContext;
import io.debezium.relational.TableId;
import io.debezium.snapshot.SnapshotterService;
import io.debezium.util.Clock;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The "entries matched no table on the source" line (#49) must be computed against the tables discovered on the
 * source, not against the tables a snapshot captures: a blocking snapshot narrows the captured set to the requested
 * table, and every other entry was then named as non-existent (#73).
 */
public class As400SnapshotUnresolvedIncludeListTest {

    private static final TableId T1 = new TableId(null, "LIB1", "T1");
    private static final TableId T2 = new TableId(null, "LIB1", "T2");
    private static final String INCLUDE_LIST = "LIB1.T1,LIB1.T2,LIB1.GONE";

    private final As400JdbcConnection connection = mock(As400JdbcConnection.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    public void captureLog() {
        logger = (Logger) LoggerFactory.getLogger(As400SnapshotChangeEventSource.class);
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    public void releaseLog() {
        logger.detachAppender(logs);
    }

    @SuppressWarnings("unchecked")
    private As400SnapshotChangeEventSource source() throws SQLException {
        final Configuration config = Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX")
                .with(As400ConnectorConfig.SCHEMA, "LIB1")
                .with(RelationalDatabaseConnectorConfig.TABLE_INCLUDE_LIST, INCLUDE_LIST)
                .build();
        final MainConnectionProvidingConnectionFactory<As400JdbcConnection> factory = mock(MainConnectionProvidingConnectionFactory.class);
        when(factory.mainConnection()).thenReturn(connection);
        doReturn("RDB").when(connection).getRealDatabaseName();
        doReturn(new LinkedHashSet<>(List.of(T1, T2))).when(connection).readTableNames(any(), any(), any(), any());
        return new As400SnapshotChangeEventSource(new As400ConnectorConfig(config),
                mock(As400RpcConnection.class), factory, mock(As400DatabaseSchema.class),
                mock(EventDispatcher.class), Clock.SYSTEM, mock(SnapshotProgressListener.class),
                mock(NotificationService.class), mock(SnapshotterService.class), new SnapshotActivity());
    }

    /**
     * Runs step 2 (discovery) and step 5 (structure) the way Debezium does, with step 2's filtering replaced by the
     * captured set Debezium would derive: the whole include list for an initial snapshot, the requested tables only
     * for a blocking one.
     */
    private List<String> unresolvedLogLines(boolean onDemand, Set<TableId> captured) throws Exception {
        final As400SnapshotChangeEventSource source = source();
        final RelationalSnapshotContext<As400Partition, As400OffsetContext> context = new RelationalSnapshotContext<>(
                null, "RDB", onDemand);
        source.getAllTableIds(context);
        context.capturedTables = captured;
        final ChangeEventSourceContext running = mock(ChangeEventSourceContext.class);
        when(running.isRunning()).thenReturn(true);
        source.readTableStructure(running, context, null, null);
        return logs.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("matched no table on the source"))
                .toList();
    }

    @Test
    void blocking_snapshot_of_one_table_names_only_the_entries_missing_from_the_source() throws Exception {
        assertThat(unresolvedLogLines(true, Set.of(T1))).containsExactly(
                "1 of the 3 table.include.list entries matched no table on the source and will not be captured: LIB1.GONE");
    }

    @Test
    void initial_snapshot_names_the_entries_missing_from_the_source() throws Exception {
        assertThat(unresolvedLogLines(false, new LinkedHashSet<>(List.of(T1, T2)))).containsExactly(
                "1 of the 3 table.include.list entries matched no table on the source and will not be captured: LIB1.GONE");
    }
}
