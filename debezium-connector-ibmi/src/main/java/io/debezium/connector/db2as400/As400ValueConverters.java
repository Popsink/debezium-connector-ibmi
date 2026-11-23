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
import java.util.OptionalInt;

import org.apache.kafka.connect.data.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400Text;

import io.debezium.config.CommonConnectorConfig.BinaryHandlingMode;
import io.debezium.connector.db2as400.conversion.UntaggedText;
import io.debezium.jdbc.JdbcValueConverters;
import io.debezium.relational.Column;
import io.debezium.util.Clock;

/**
 * Conversion of DB2 specific datatypes.
 *
 */
public class As400ValueConverters extends JdbcValueConverters {
    private static final Logger log = LoggerFactory.getLogger(As400ValueConverters.class);
    private final As400ConnectorConfig config;
    private final SanitisedValues sanitisedValues;
    private static final byte[] EMPTY_BYTES = new byte[0];

    /**
     * Honours {@code time.precision.mode}, which this used to pin to {@code adaptive}. Under adaptive a
     * DB2 for i {@code TIMESTAMP(6)} reports length 26, so it is sized as a {@code NanoTimestamp} whose
     * INT64 stops at 1677 and 2262: the {@code 0001-01-01} an IBM i application writes for "not set" then
     * converts to null and a NOT NULL column drops the whole event (Popsink/data-plane#4904).
     */
    public As400ValueConverters(DecimalMode decimalMode, As400ConnectorConfig config) {
        this(decimalMode, config, new SanitisedValues(Clock.system()));
    }

    As400ValueConverters(DecimalMode decimalMode, As400ConnectorConfig config, SanitisedValues sanitisedValues) {
        super(decimalMode, config.getTemporalPrecisionMode(), ZoneOffset.UTC, null, null, null);
        this.config = config;
        this.sanitisedValues = sanitisedValues;
    }

    /** The values whose control characters this converter replaced. */
    public SanitisedValues sanitisedValues() {
        return sanitisedValues;
    }

    private static String columnName(Column column, Field fieldDefn) {
        if (column != null) {
            return column.name();
        }
        return (fieldDefn == null) ? "<unknown>" : fieldDefn.name();
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
        if (data instanceof final byte[] bytes) {
            // an untagged column read as text: jt400 hands a snapshot its bytes (Popsink/data-plane#5831)
            final OptionalInt ccsid = UntaggedText.ccsidOf(column);
            if (ccsid.isPresent()) {
                data = new AS400Text(bytes.length, ccsid.getAsInt()).toObject(bytes);
            }
        }
        // an xml column is left alone, which is what trim.non.xml.charsequence.field.mode says. A
        // snapshot reads it as a SQLXML, but the journal decoder produces the document as a String, so
        // the column's own type is what decides - not the class of the value
        if (!(data instanceof SQLXML) && !isXml(column)) {
            String str = data.toString();
            Pair fixed = removeBadCharacters(str);
            if (fixed.modified) {
                // nothing is removed: the characters are replaced in place and the value is delivered (#58)
                sanitisedValues.record(columnName(column, fieldDefn), fixed.replaced);
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
            return new Pair(0, rawString);
        }
        StringBuilder newString = new StringBuilder(rawString.length());
        int replaced = 0;
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
                    replaced++;
                    break;
                default:
                    newString.append(Character.toChars(codePoint));
                    break;
            }
        }
        return new Pair(replaced, newString.toString());
    }

    public static class Pair {
        boolean modified;
        /** How many code points were replaced with {@code '?'}. */
        int replaced;
        String value;

        public Pair(boolean modified, String value) {
            this(modified ? 1 : 0, value);
        }

        Pair(int replaced, String value) {
            this.modified = replaced > 0;
            this.replaced = replaced;
            this.value = value;
        }

    }
}
