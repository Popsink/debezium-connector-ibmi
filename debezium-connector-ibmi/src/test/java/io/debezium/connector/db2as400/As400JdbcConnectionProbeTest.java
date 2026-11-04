/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;

import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.db2as400.As400JdbcConnection.Probe;

/**
 * The wrapper's liveness probe only replaces a connection whose failure is confirmed: on a slow link a
 * timed-out {@code isValid} closed a working connection under a running snapshot (Popsink/data-plane#5814).
 */
class As400JdbcConnectionProbeTest {

    private final Connection conn = mock(Connection.class);
    private final Statement statement = mock(Statement.class);

    @Test
    void a_valid_connection_is_alive_without_a_statement() throws Exception {
        when(conn.isValid(anyInt())).thenReturn(true);

        assertThat(As400JdbcConnection.probe(conn, 15_000)).isEqualTo(Probe.ALIVE);
        verify(conn, never()).createStatement();
    }

    @Test
    void a_slow_probe_on_a_connection_that_still_answers_keeps_it() throws Exception {
        // Given - isValid timed out, as a 3 s probe did on a ~0.5 s-per-query link, but the host answers
        when(conn.isValid(anyInt())).thenReturn(false);
        when(conn.createStatement()).thenReturn(statement);

        // Then - not replaced, and the statement ran under the same bound
        assertThat(As400JdbcConnection.probe(conn, 15_000)).isEqualTo(Probe.SLOW);
        verify(statement).execute(As400JdbcConnection.CONFIRM_DEAD_SQL);
        verify(statement).setQueryTimeout(15);
    }

    @Test
    void a_failed_probe_and_a_failed_statement_mean_dead() throws Exception {
        when(conn.isValid(anyInt())).thenReturn(false);
        when(conn.createStatement()).thenThrow(new SQLNonTransientConnectionException("The connection does not exist.", "08003"));

        assertThat(As400JdbcConnection.probe(conn, 15_000)).isEqualTo(Probe.DEAD);
    }

    @Test
    void an_is_valid_that_throws_is_confirmed_too() throws Exception {
        when(conn.isValid(anyInt())).thenThrow(new SQLException("boom"));
        when(conn.createStatement()).thenReturn(statement);

        assertThat(As400JdbcConnection.probe(conn, 15_000)).isEqualTo(Probe.SLOW);
    }

    @Test
    void the_probe_timeout_is_rounded_up_to_whole_seconds() throws Exception {
        when(conn.isValid(anyInt())).thenReturn(true);

        As400JdbcConnection.probe(conn, 2_500);

        verify(conn).isValid(3);
    }

    @Test
    void the_network_timeout_is_restored_after_the_confirming_statement() throws Exception {
        when(conn.isValid(anyInt())).thenReturn(false);
        when(conn.getNetworkTimeout()).thenReturn(0);
        when(conn.createStatement()).thenReturn(statement);

        As400JdbcConnection.probe(conn, 15_000);

        verify(conn).setNetworkTimeout(any(), eq(15_000));
        verify(conn).setNetworkTimeout(any(), eq(0));
    }

    @Test
    void the_timeout_defaults_to_15_seconds_and_reaches_the_jdbc_config() {
        final Configuration.Builder builder = Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX");
        assertThat(new As400ConnectorConfig(builder.build()).getJdbcConfig()
                .getInteger(As400ConnectorConfig.CONNECTION_PROBE_TIMEOUT_MS)).isEqualTo(15_000);

        builder.with(As400ConnectorConfig.CONNECTION_PROBE_TIMEOUT_MS, 30_000);
        assertThat(new As400ConnectorConfig(builder.build()).getJdbcConfig()
                .getInteger(As400ConnectorConfig.CONNECTION_PROBE_TIMEOUT_MS)).isEqualTo(30_000);
        assertThat(As400ConnectorConfig.configDef().names())
                .contains(As400ConnectorConfig.CONNECTION_PROBE_TIMEOUT_MS.name());
    }
}
