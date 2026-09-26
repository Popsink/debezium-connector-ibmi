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

/**
 * Whether a DDS keyed access path that allows duplicate key values keys the records decides whether a
 * merged destination holds every record of a file or one per key value, so the default has to survive
 * the round trip through {@link Configuration} - and the opt-out has to be reachable, because it is the
 * only way back to the old resolution for an operator who knows their key is unique in practice.
 */
class As400ConnectorConfigDdsKeyTest {

    private static As400ConnectorConfig config(String requireUnique) {
        final Configuration.Builder builder = Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX");
        if (requireUnique != null) {
            builder.with(As400ConnectorConfig.DDS_KEY_REQUIRE_UNIQUE, requireUnique);
        }
        return new As400ConnectorConfig(builder.build());
    }

    @Test
    void uniquenessIsRequiredByDefault() {
        assertThat(config(null).isUniqueDdsKeyRequired()).isTrue();
    }

    @Test
    void theOptOutIsHonoured() {
        assertThat(config("false").isUniqueDdsKeyRequired()).isFalse();
        assertThat(config("true").isUniqueDdsKeyRequired()).isTrue();
    }

    /** An option missing from the {@code ConfigDef} is rejected by Kafka Connect before the task starts. */
    @Test
    void theOptionIsDeclaredToKafkaConnect() {
        assertThat(As400ConnectorConfig.configDef().names())
                .contains(As400ConnectorConfig.DDS_KEY_REQUIRE_UNIQUE.name());
        assertThat(As400ConnectorConfig.ALL_FIELDS.asArray())
                .contains(As400ConnectorConfig.DDS_KEY_REQUIRE_UNIQUE);
    }
}
