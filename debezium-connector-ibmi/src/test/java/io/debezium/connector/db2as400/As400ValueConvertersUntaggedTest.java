/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.Configuration;
import io.debezium.connector.db2as400.conversion.UntaggedText;
import io.debezium.jdbc.JdbcValueConverters.DecimalMode;
import io.debezium.relational.Column;

/** A snapshot reads an untagged column over JDBC as bytes; one decided text is delivered as its text (#5831). */
class As400ValueConvertersUntaggedTest {

    private static final byte[] D2000106_PADDED = { (byte) 0xC4, (byte) 0xF2, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0,
            (byte) 0xF1, (byte) 0xF0, (byte) 0xF6, 0x40, 0x40 };

    private static Object convert(Column column, Object value) {
        final As400ValueConverters converters = new As400ValueConverters(DecimalMode.PRECISE,
                new As400ConnectorConfig(Configuration.create()
                        .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                        .with(As400ConnectorConfig.DATABASE_NAME, "serverX")
                        .build()));
        return converters.converter(column, new Field(column.name(), 0, Schema.OPTIONAL_STRING_SCHEMA)).convert(value);
    }

    @Test
    void bytesOfAColumnReadAsTextAreDecodedInItsCcsid() {
        final Column column = UntaggedText.mark(Column.editor().name("NAME").type("CHAR () FOR BIT DATA").length(10),
                "CHAR () FOR BIT DATA", 37).create();

        assertThat(convert(column, D2000106_PADDED)).isEqualTo("D2000106");
    }
}
