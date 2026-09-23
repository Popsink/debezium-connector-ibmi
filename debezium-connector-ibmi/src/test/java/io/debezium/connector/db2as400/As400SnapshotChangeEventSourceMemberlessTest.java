/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.jdbc.MainConnectionProvidingConnectionFactory;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.notification.NotificationService;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.relational.TableId;
import io.debezium.snapshot.SnapshotterService;
import io.debezium.util.Clock;

/**
 * A physical file with no member fails any SQL access with {@code SQL0204}; with
 * {@code errors.tolerance=all} it is dropped from the snapshot, by default the failure names the table.
 */
public class As400SnapshotChangeEventSourceMemberlessTest {

    private static final TableId T1 = new TableId(null, "LIB1", "T1");
    private static final TableId EMPTY = new TableId(null, "LIB1", "NOMEMBER");

    private final As400JdbcConnection connection = mock(As400JdbcConnection.class);

    @SuppressWarnings("unchecked")
    private As400SnapshotChangeEventSource source(String tolerance) {
        Configuration.Builder builder = Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX")
                .with(As400ConnectorConfig.SCHEMA, "LIB1");
        if (tolerance != null) {
            builder.with(As400ConnectorConfig.ERRORS_TOLERANCE, tolerance);
        }
        final MainConnectionProvidingConnectionFactory<As400JdbcConnection> factory = mock(MainConnectionProvidingConnectionFactory.class);
        when(factory.mainConnection()).thenReturn(connection);
        return new As400SnapshotChangeEventSource(new As400ConnectorConfig(builder.build()),
                mock(As400RpcConnection.class), factory, mock(As400DatabaseSchema.class),
                mock(EventDispatcher.class), Clock.SYSTEM, mock(SnapshotProgressListener.class),
                mock(NotificationService.class), mock(SnapshotterService.class), new SnapshotActivity());
    }

    private void discovered(TableId... tables) throws SQLException {
        doReturn("RDB").when(connection).getRealDatabaseName();
        doReturn(new LinkedHashSet<>(List.of(tables))).when(connection).readTableNames(any(), any(), any(), any());
    }

    @Test
    void tolerant_mode_drops_a_memberless_file_from_the_snapshot() throws Exception {
        discovered(T1, EMPTY);
        doReturn(Set.of("T1")).when(connection).tablesWithMembers("LIB1");

        assertThat(source("all").getAllTableIds(null)).containsExactly(T1);
    }

    @Test
    void default_mode_keeps_a_memberless_file_and_fails_loud_later() throws Exception {
        discovered(T1, EMPTY);

        assertThat(source(null).getAllTableIds(null)).containsExactly(T1, EMPTY);
    }

    @Test
    void row_count_failure_names_the_table() throws Exception {
        doThrow(new SQLException("[SQL0551] Not authorized to object.", "42501", -551))
                .when(connection).queryAndMap(any(String.class), any());

        assertThatThrownBy(() -> source(null).rowCountForTableChunked(T1))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("LIB1.T1")
                .hasMessageContaining("not authorized");
    }
}
