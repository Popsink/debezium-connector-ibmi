/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.sql.SQLException;
import java.sql.Types;

import javax.sql.rowset.serial.SerialBlob;
import javax.sql.rowset.serial.SerialClob;

import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.junit.jupiter.api.Test;

import io.debezium.config.CommonConnectorConfig;
import io.debezium.config.CommonConnectorConfig.BinaryHandlingMode;
import io.debezium.config.Configuration;
import io.debezium.jdbc.JdbcValueConverters.DecimalMode;
import io.debezium.relational.Column;

/**
 * Snapshots read large object columns over JDBC, where jt400 returns an {@code AS400JDBCClob} or
 * {@code AS400JDBCBlob}: the stock converter delivers a Clob's {@code toString()}, that is the object's
 * identity, and reports a Blob as a type it did not expect.
 */
class As400ValueConvertersLobTest {

    private static final Column CLOB_COLUMN = Column.editor().name("BODY").type("CLOB")
            .jdbcType(Types.CLOB).create();
    private static final Field FIELD = new Field("body", 0, Schema.OPTIONAL_STRING_SCHEMA);
    private static final Column BLOB_COLUMN = Column.editor().name("RAW").type("BLOB")
            .jdbcType(Types.BLOB).create();
    private static final Field BYTES_FIELD = new Field("raw", 0, Schema.OPTIONAL_BYTES_SCHEMA);

    private static As400ValueConverters converters() {
        final Configuration config = Configuration.create()
                .with(CommonConnectorConfig.TOPIC_PREFIX, "serverX")
                .with(As400ConnectorConfig.DATABASE_NAME, "serverX")
                .build();
        return new As400ValueConverters(DecimalMode.PRECISE, new As400ConnectorConfig(config));
    }

    @Test
    void readsTheTextOfAClob() throws SQLException {
        final Object converted = converters().convertString(CLOB_COLUMN, FIELD,
                new SerialClob("some clob text".toCharArray()));

        assertThat(converted).isEqualTo("some clob text");
    }

    @Test
    void readsAnEmptyClob() throws SQLException {
        final Object converted = converters().convertString(CLOB_COLUMN, FIELD, new SerialClob(new char[0]));

        assertThat(converted).isEqualTo("");
    }

    @Test
    void stillConvertsPlainStrings() {
        assertThat(converters().convertString(CLOB_COLUMN, FIELD, "plain")).isEqualTo("plain");
    }

    @Test
    void readsTheBytesOfABlob() throws SQLException {
        final byte[] raw = { 0x00, (byte) 0xD8, (byte) 0xff };

        final Object converted = converters().convertBinary(BLOB_COLUMN, BYTES_FIELD, new SerialBlob(raw),
                BinaryHandlingMode.BYTES);

        assertThat(converted).isEqualTo(ByteBuffer.wrap(raw));
    }

    @Test
    void readsAnEmptyBlob() throws SQLException {
        final Object converted = converters().convertBinary(BLOB_COLUMN, BYTES_FIELD, new SerialBlob(new byte[0]),
                BinaryHandlingMode.BYTES);

        assertThat(converted).isEqualTo(ByteBuffer.wrap(new byte[0]));
    }

    @Test
    void stillConvertsPlainBytes() {
        final byte[] raw = { 0x01, 0x02 };

        assertThat(converters().convertBinary(BLOB_COLUMN, BYTES_FIELD, raw, BinaryHandlingMode.BYTES))
                .isEqualTo(ByteBuffer.wrap(raw));
    }
}
