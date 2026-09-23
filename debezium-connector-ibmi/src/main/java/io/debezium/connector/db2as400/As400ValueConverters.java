/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Types;
import java.time.ZoneOffset;

import org.apache.kafka.connect.data.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.config.CommonConnectorConfig.BinaryHandlingMode;
import io.debezium.jdbc.JdbcValueConverters;
import io.debezium.jdbc.TemporalPrecisionMode;
import io.debezium.relational.Column;

/**
 * Conversion of DB2 specific datatypes.
 *
 */
public class As400ValueConverters extends JdbcValueConverters {
    private static final Logger log = LoggerFactory.getLogger(As400ValueConverters.class);
    private final As400ConnectorConfig config;
    private static final byte[] EMPTY_BYTES = new byte[0];

    public As400ValueConverters(DecimalMode decimalMode, As400ConnectorConfig config) {
        super(decimalMode, TemporalPrecisionMode.ADAPTIVE, ZoneOffset.UTC, null, null, null);
        this.config = config;
    }

    @Override
    protected Object convertString(Column column, Field fieldDefn, Object data) {
        if (data == null) {
            return super.convertString(column, fieldDefn, data);
        }
        if (data instanceof final Clob clob) {
            // snapshots read CLOB columns over JDBC, where jt400 hands back a Clob; the base converter
            // would deliver its toString(), i.e. the object's identity. Streaming has no Clob to
            // materialise, the journal decoder produces the text itself.
            data = materialise(column, clob);
        }
        // an xml column is left alone, which is what trim.non.xml.charsequence.field.mode says. A
        // snapshot reads it as a SQLXML, but the journal decoder produces the document as a String, so
        // the column's own type is what decides - not the class of the value
        if (!(data instanceof SQLXML) && !isXml(column)) {
            String str = data.toString();
            Pair fixed = removeBadCharacters(str);
            if (fixed.modified) {
                String cname = (column == null) ? "" : String.format(" column name %s", column.name());
                String fname = (fieldDefn == null) ? "" : String.format(" fieldDefn name %s", fieldDefn.name());
                log.warn("removed binary data from{}{}", cname, fname);
            }
            return super.convertString(column, fieldDefn, config.getCharSequenceTrimMode().strip(fixed.value));
        }
        return super.convertString(column, fieldDefn, data);
    }

    private static boolean isXml(Column column) {
        return column != null && column.jdbcType() == Types.SQLXML;
    }

    @Override
    protected Object convertBinary(Column column, Field fieldDefn, Object data, BinaryHandlingMode mode) {
        if (data instanceof final Blob blob) {
            // as for CLOB columns above: a snapshot reads a Blob, which the base converter only reports
            // as an unexpected type. Streaming decodes the bytes out of the journal itself.
            data = materialise(column, blob);
        }
        return super.convertBinary(column, fieldDefn, data, mode);
    }

    /**
     * Reads a BLOB into bytes, or leaves the value alone if it cannot be read - the base converter then
     * reports the type it could not handle, rather than this failing the whole snapshot.
     */
    private static Object materialise(Column column, Blob blob) {
        try {
            final long length = blob.length();
            if (length > Integer.MAX_VALUE) {
                log.error("blob column {} is {} bytes, too long to read into an array", column.name(), length);
                return blob;
            }
            return (length == 0) ? EMPTY_BYTES : blob.getBytes(1, (int) length);
        }
        catch (final SQLException e) {
            log.error("failed to read blob column {}", column.name(), e);
            return blob;
        }
    }

    /**
     * Reads a CLOB into a String, or leaves the value alone if it cannot be read - the base converter
     * then reports the type it could not handle, rather than this failing the whole snapshot.
     */
    private static Object materialise(Column column, Clob clob) {
        try {
            final long length = clob.length();
            if (length > Integer.MAX_VALUE) {
                log.error("clob column {} is {} characters, too long to read into a string", column.name(), length);
                return clob;
            }
            return (length == 0) ? "" : clob.getSubString(1, (int) length);
        }
        catch (final SQLException e) {
            log.error("failed to read clob column {}", column.name(), e);
            return clob;
        }
    }

    public static Pair removeBadCharacters(String rawString) {
        if (rawString == null) {
            return new Pair(false, rawString);
        }
        StringBuilder newString = new StringBuilder(rawString.length());
        boolean modified = false;
        for (int offset = 0; offset < rawString.length();) {
            int codePoint = rawString.codePointAt(offset);
            offset += Character.charCount(codePoint);

            // Replace invisible control characters and unused code points
            switch (Character.getType(codePoint)) {
                case Character.CONTROL: // \p{Cc}
                case Character.FORMAT: // \p{Cf}
                case Character.PRIVATE_USE: // \p{Co}
                case Character.SURROGATE: // \p{Cs}
                case Character.UNASSIGNED: // \p{Cn}
                    newString.append('?');
                    modified = true;
                    break;
                default:
                    newString.append(Character.toChars(codePoint));
                    break;
            }
        }
        return new Pair(modified, newString.toString());
    }

    public static class Pair {
        boolean modified;
        String value;

        public Pair(boolean modified, String value) {
            super();
            this.modified = modified;
            this.value = value;
        }

    }
}
