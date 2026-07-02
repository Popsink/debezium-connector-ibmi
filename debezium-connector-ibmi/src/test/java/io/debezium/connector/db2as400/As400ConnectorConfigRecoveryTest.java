/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.db2as400.As400ConnectorConfig.UnavailablePositionRecovery;

class As400ConnectorConfigRecoveryTest {

    private static As400ConnectorConfig config(String recovery) {
        Configuration.Builder builder = Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX");
        if (recovery != null) {
            builder.with(As400ConnectorConfig.UNAVAILABLE_POSITION_RECOVERY, recovery);
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
    }

    @Test
    void invalidValueFallsBackToDefault() {
        assertThat(config("bogus").getUnavailablePositionRecovery()).isEqualTo(UnavailablePositionRecovery.FAIL);
    }
}
