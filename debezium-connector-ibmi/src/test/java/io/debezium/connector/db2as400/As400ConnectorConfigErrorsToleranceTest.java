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

class As400ConnectorConfigErrorsToleranceTest {

    private static As400ConnectorConfig config(String tolerance) {
        Configuration.Builder builder = Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX");
        if (tolerance != null) {
            builder.with(As400ConnectorConfig.ERRORS_TOLERANCE, tolerance);
        }
        return new As400ConnectorConfig(builder.build());
    }

    @Test
    void onlyAllSkipsUncapturableTables() {
        assertThat(config("all").skipUncapturableTables()).isTrue();
        assertThat(config("ALL").skipUncapturableTables()).isTrue();
        assertThat(config("none").skipUncapturableTables()).isFalse();
    }

    @Test
    void defaultAndInvalidValuesAreStrict() {
        assertThat(config(null).skipUncapturableTables()).isFalse();
        assertThat(config("bogus").skipUncapturableTables()).isFalse();
    }
}
