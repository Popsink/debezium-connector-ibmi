/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.db2as400.As400ConnectorConfig.UnavailablePositionRecovery;

class As400ConnectorConfigRecoveryTest {

    private static As400ConnectorConfig config(String recovery) {
        return config(recovery, null);
    }

    private static As400ConnectorConfig config(String recovery, String errorsTolerance) {
        Configuration.Builder builder = Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX");
        if (recovery != null) {
            builder.with(As400ConnectorConfig.UNAVAILABLE_POSITION_RECOVERY, recovery);
        }
        if (errorsTolerance != null) {
            builder.with(As400ConnectorConfig.ERRORS_TOLERANCE, errorsTolerance);
        }
        return new As400ConnectorConfig(builder.build());
    }

    @Test
    void defaultsToFail() {
        assertThat(config(null).getUnavailablePositionRecovery()).isEqualTo(UnavailablePositionRecovery.FAIL);
    }

    @Test
    void parsesConfiguredValues() {
        assertThat(config("snapshot").getUnavailablePositionRecovery()).isEqualTo(UnavailablePositionRecovery.SNAPSHOT);
        assertThat(config("earliest").getUnavailablePositionRecovery()).isEqualTo(UnavailablePositionRecovery.EARLIEST);
        assertThat(config("EARLIEST").getUnavailablePositionRecovery()).isEqualTo(UnavailablePositionRecovery.EARLIEST);
        assertThat(config("latest").getUnavailablePositionRecovery()).isEqualTo(UnavailablePositionRecovery.LATEST);
        assertThat(config("LATEST").getUnavailablePositionRecovery()).isEqualTo(UnavailablePositionRecovery.LATEST);
    }

    @Test
    void invalidValueFallsBackToDefault() {
        assertThat(config("bogus").getUnavailablePositionRecovery()).isEqualTo(UnavailablePositionRecovery.FAIL);
    }

    @Test
    void errorsToleranceDefaultsToConnectsOwnDefault() {
        assertThat(config(null).getErrorsTolerance()).isEqualTo("none");
        assertThat(config(null, "  ").getErrorsTolerance()).isEqualTo("none");
        assertThat(config(null, " all ").getErrorsTolerance()).isEqualTo("all");
    }

    @Test
    void latestRequiresErrorsToleranceAll() {
        assertThatCode(() -> config("latest", "all").validateUnavailablePositionRecovery()).doesNotThrowAnyException();
        assertThatCode(() -> config("latest", "ALL").validateUnavailablePositionRecovery()).doesNotThrowAnyException();
    }

    @Test
    void latestIsRejectedWhenARecordMayNotBeDropped() {
        // explicit errors.tolerance=none, and Connect's default of none when the setting is absent
        for (As400ConnectorConfig config : new As400ConnectorConfig[]{ config("latest", "none"), config("latest") }) {
            assertThatThrownBy(config::validateUnavailablePositionRecovery)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("journal.unavailable.position.recovery=latest")
                    .hasMessageContaining("errors.tolerance=none")
                    // points at the recovery that fills the gap rather than skipping it
                    .hasMessageContaining("journal.unavailable.position.recovery=snapshot");
        }
    }

    @Test
    void theOtherStrategiesAreIndependentOfErrorsTolerance() {
        for (String recovery : new String[]{ "fail", "snapshot", "earliest" }) {
            assertThatCode(() -> config(recovery, "none").validateUnavailablePositionRecovery()).doesNotThrowAnyException();
            assertThatCode(() -> config(recovery, "all").validateUnavailablePositionRecovery()).doesNotThrowAnyException();
        }
    }
}
