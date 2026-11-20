/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Types;
import java.util.List;
import java.util.Map;

import javax.management.StandardMBean;
import javax.management.openmbean.TabularData;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.base.ChangeEventQueueMetrics;
import io.debezium.connector.common.CdcSourceTaskContext;
import io.debezium.connector.db2as400.As400ConnectorConfig;
import io.debezium.connector.db2as400.As400ValueConverters;
import io.debezium.jdbc.JdbcValueConverters.DecimalMode;
import io.debezium.pipeline.source.spi.EventMetadataProvider;
import io.debezium.relational.Column;

/** The values whose control characters were replaced are visible without reading the log (#58). */
class As400StreamingChangeEventSourceMetricsSanitisedTest {

    @Test
    @SuppressWarnings("unchecked")
    void exposesTheValuesTheConvertersAltered() throws Exception {
        final As400ConnectorConfig config = new As400ConnectorConfig(Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX")
                .build());
        final As400ValueConverters converters = new As400ValueConverters(DecimalMode.PRECISE, config);
        final Column notes = Column.editor().name("NOTES").type("VARCHAR").jdbcType(Types.VARCHAR).create();
        converters.converter(notes, new Field("NOTES", 0, Schema.OPTIONAL_STRING_SCHEMA)).convert("a\u0000b");

        final CdcSourceTaskContext<As400ConnectorConfig> taskContext = mock(CdcSourceTaskContext.class);
        when(taskContext.getConfig()).thenReturn(config);
        when(taskContext.getConnectorType()).thenReturn("ibmi");
        when(taskContext.getConnectorLogicalName()).thenReturn("serverX");
        final As400StreamingChangeEventSourceMetrics metrics = new As400StreamingChangeEventSourceMetrics(taskContext,
                mock(ChangeEventQueueMetrics.class), mock(EventMetadataProvider.class), List::of,
                converters.sanitisedValues());

        assertThat(metrics.getSanitisedValues()).isEqualTo(1);
        assertThat(metrics.getSanitisedValuesByColumn()).isEqualTo(Map.of("NOTES", 1L));

        // what a JMX client reads: the map has to be expressible as MXBean open data
        final StandardMBean bean = new StandardMBean(metrics, As400ChangeEventSourceMetricsMXBean.class, true);
        assertThat(bean.getAttribute("SanitisedValues")).isEqualTo(1L);
        assertThat(((TabularData) bean.getAttribute("SanitisedValuesByColumn")).size()).isEqualTo(1);
    }
}
