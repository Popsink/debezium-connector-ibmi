/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.jdbc.JdbcValueConverters.DecimalMode;
import io.debezium.relational.Column;
import io.debezium.relational.RelationalDatabaseConnectorConfig;
import io.debezium.time.MicroTime;
import io.debezium.time.MicroTimestamp;
import io.debezium.time.NanoTimestamp;

/**
 * An IBM i application writes {@code 0001-01-01 00:00:00} for an unset timestamp; under the adaptive
 * default it does not fit a {@code NanoTimestamp} and the event carrying it is dropped (Popsink/data-plane#4904).
 */
class As400ValueConvertersTimePrecisionTest {

    /** What jt400 reports for a {@code TIMESTAMP(6)}: length 26, the width of its text form. */
    private static final Column TIMESTAMP_COLUMN = Column.editor().name("UPDATED").type("TIMESTAMP")
            .jdbcType(Types.TIMESTAMP).length(26).scale(6).optional(false).create();
    private static final Column TIME_COLUMN = Column.editor().name("AT").type("TIME")
            .jdbcType(Types.TIME).length(8).optional(false).create();
    private static final Timestamp YEAR_ONE = Timestamp.valueOf(LocalDateTime.of(1, 1, 1, 0, 0));

    private static As400ValueConverters converters(String precisionMode) {
        Configuration.Builder config = Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX");
        if (precisionMode != null) {
            config = config.with(RelationalDatabaseConnectorConfig.TIME_PRECISION_MODE, precisionMode);
        }
        return new As400ValueConverters(DecimalMode.PRECISE, new As400ConnectorConfig(config.build()));
    }

    private static Object convert(As400ValueConverters converters, Object value) {
        final SchemaBuilder builder = converters.schemaBuilder(TIMESTAMP_COLUMN);
        final Field field = new Field(TIMESTAMP_COLUMN.name(), 0, builder.build());
        return converters.converter(TIMESTAMP_COLUMN, field).convert(value);
    }

    @Test
    void keepsNanoTimestampWhenTheModeIsUnset() {
        // Given - a connector that never set time.precision.mode, as every one deployed so far
        final As400ValueConverters converters = converters(null);

        // When
        final Schema schema = converters.schemaBuilder(TIMESTAMP_COLUMN).build();

        // Then - its schema does not move under an existing table
        assertThat(schema.name()).isEqualTo(NanoTimestamp.SCHEMA_NAME);
    }

    @Test
    void convertsYearOneUnderMicroseconds() {
        // Given
        final As400ValueConverters converters = converters("microseconds");

        // When
        final Object converted = convert(converters, YEAR_ONE);

        // Then - the value is kept rather than turned into a null the NOT NULL column refuses
        assertThat(converters.schemaBuilder(TIMESTAMP_COLUMN).build().name()).isEqualTo(MicroTimestamp.SCHEMA_NAME);
        assertThat(converted).isEqualTo(ChronoUnit.MICROS.between(
                LocalDateTime.of(1970, 1, 1, 0, 0).toInstant(ZoneOffset.UTC),
                YEAR_ONE.toLocalDateTime().toInstant(ZoneOffset.UTC)));
    }

    @Test
    void readsATimeAsMicrosecondsUnderMicroseconds() {
        assertThat(converters("microseconds").schemaBuilder(TIME_COLUMN).build().name())
                .isEqualTo(MicroTime.SCHEMA_NAME);
    }
}
