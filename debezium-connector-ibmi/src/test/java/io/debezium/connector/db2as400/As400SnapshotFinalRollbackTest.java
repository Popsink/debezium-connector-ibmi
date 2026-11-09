/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.jdbc.MainConnectionProvidingConnectionFactory;
import io.debezium.pipeline.EventDispatcher;
import io.debezium.pipeline.notification.NotificationService;
import io.debezium.pipeline.source.spi.SnapshotProgressListener;
import io.debezium.relational.RelationalSnapshotChangeEventSource;
import io.debezium.snapshot.SnapshotterService;
import io.debezium.util.Clock;

/**
 * Debezium takes the snapshot connection at step 1 and rolls it back in the snapshot's {@code finally}. When
 * the wrapper replaced that connection during step 5, the rollback hit a closed connection and failed a
 * {@code no_data} snapshot that had already persisted its schema history (Popsink/data-plane#5814).
 *
 * <p>The rollback is driven through core's own private {@code rollbackTransaction}, the exact call
 * {@code doExecute} makes on the object {@code createSnapshotConnection} returned.
 */
class As400SnapshotFinalRollbackTest {

    private static final SQLException CLOSED = new SQLNonTransientConnectionException("The connection does not exist.", "08003");

    private final As400JdbcConnection wrapper = mock(As400JdbcConnection.class);
    private final Connection stepOne = mock(Connection.class);
    private final Connection replacement = mock(Connection.class);
    private As400SnapshotChangeEventSource source;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        final Configuration config = Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX")
                .with(As400ConnectorConfig.SCHEMA, "LIB1")
                .build();
        final MainConnectionProvidingConnectionFactory<As400JdbcConnection> factory = mock(MainConnectionProvidingConnectionFactory.class);
        when(factory.mainConnection()).thenReturn(wrapper);
        source = new As400SnapshotChangeEventSource(new As400ConnectorConfig(config),
                mock(As400RpcConnection.class), factory, mock(As400DatabaseSchema.class),
                mock(EventDispatcher.class), Clock.SYSTEM, mock(SnapshotProgressListener.class),
                mock(NotificationService.class), mock(SnapshotterService.class), new SnapshotActivity());
        when(wrapper.isValid()).thenReturn(true);
        when(wrapper.connection()).thenReturn(stepOne);
    }

    /** Step 5's dead-connection probe closed the step-1 connection and opened a new one in auto-commit. */
    private void socketClosedDuringStepFive() throws SQLException {
        doThrow(CLOSED).when(stepOne).rollback();
        when(stepOne.isClosed()).thenReturn(true);
        when(replacement.getAutoCommit()).thenReturn(true);
        when(wrapper.connection()).thenReturn(replacement);
    }

    private void finalRollbackOn(Connection connection) throws Throwable {
        final Method rollback = RelationalSnapshotChangeEventSource.class.getDeclaredMethod("rollbackTransaction", Connection.class);
        rollback.setAccessible(true);
        try {
            rollback.invoke(source, connection);
        }
        catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    @Test
    void the_final_rollback_survives_a_connection_replaced_during_step_five() throws Throwable {
        // Given - step 1 hands Debezium its snapshot connection
        final Connection held = source.createSnapshotConnection();
        verify(stepOne).setAutoCommit(false);

        // When - the socket is closed mid-readSchema and the wrapper reconnects
        socketClosedDuringStepFive();

        // Then - the rollback in doExecute's finally no longer fails the snapshot
        assertThatCode(() -> finalRollbackOn(held)).doesNotThrowAnyException();
        verify(stepOne, never()).rollback();
    }

    /** The control: the raw step-1 connection is what failed the snapshot before the fix. */
    @Test
    void the_raw_step_one_connection_still_fails_the_rollback() throws Throwable {
        socketClosedDuringStepFive();

        assertThatThrownBy(() -> finalRollbackOn(stepOne))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(SQLNonTransientConnectionException.class);
    }

    @Test
    void an_unreachable_host_at_the_rollback_is_ignored_too() throws Throwable {
        final Connection held = source.createSnapshotConnection();
        when(wrapper.connection()).thenThrow(new SQLException("Communication link failure", "08S01"));

        assertThatCode(() -> finalRollbackOn(held)).doesNotThrowAnyException();
    }

    @Test
    void an_untouched_connection_is_still_rolled_back() throws Throwable {
        final Connection held = source.createSnapshotConnection();

        finalRollbackOn(held);

        verify(stepOne).rollback();
    }

    @Test
    void a_rollback_failure_on_a_live_connection_still_fails() throws Throwable {
        final Connection held = source.createSnapshotConnection();
        doThrow(new SQLException("[SQL0901] SQL system error.", "58004")).when(stepOne).rollback();

        assertThatThrownBy(() -> finalRollbackOn(held))
                .isInstanceOf(RuntimeException.class)
                .hasRootCauseMessage("[SQL0901] SQL system error.");
    }

    @Test
    void other_calls_reach_the_current_connection() throws Throwable {
        final Connection held = source.createSnapshotConnection();
        when(wrapper.connection()).thenReturn(replacement);

        held.commit();

        verify(replacement).commit();
        verify(stepOne, never()).commit();
    }
}
