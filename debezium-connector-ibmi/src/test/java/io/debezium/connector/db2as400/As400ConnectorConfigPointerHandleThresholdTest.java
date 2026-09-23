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
import io.debezium.ibmi.db2.journal.retrieve.PointerHandles;

/**
 * The threshold decides how many pointer handles pile up before the host server job is ended to free
 * them. A default that did not survive the round trip through {@link Configuration} would be silently
 * destructive at both extremes: zero means ending the job on every poll, and a value beyond what the
 * system allows means never ending it at all and eventually losing journal retrieval.
 */
class As400ConnectorConfigPointerHandleThresholdTest {

    private static As400ConnectorConfig config(String threshold) {
        final Configuration.Builder builder = Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX");
        if (threshold != null) {
            builder.with(As400ConnectorConfig.POINTER_HANDLE_THRESHOLD, threshold);
        }
        return new As400ConnectorConfig(builder.build());
    }

    @Test
    void theDefaultSurvivesTheConfiguration() {
        assertThat(config(null).getPointerHandleThreshold())
                .isEqualTo(PointerHandles.DEFAULT_THRESHOLD);
    }

    @Test
    void anExplicitThresholdIsHonoured() {
        assertThat(config("1000").getPointerHandleThreshold()).isEqualTo(1000L);
    }

    /**
     * A threshold of zero would ask for the job to be ended on every poll, which costs a reconnect each
     * time. {@link PointerHandles} treats anything non-positive as "use the default" rather than
     * honouring it.
     */
    @Test
    void aNonPositiveThresholdDoesNotCycleOnEveryPoll() {
        assertThat(new PointerHandles(config("0").getPointerHandleThreshold()).threshold())
                .isEqualTo(PointerHandles.DEFAULT_THRESHOLD);
    }
}
