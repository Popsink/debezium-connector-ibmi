/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400.conversion;

import java.sql.Types;
import java.util.OptionalInt;

import io.debezium.ibmi.db2.journal.retrieve.JdbcFileDecoder;
import io.debezium.relational.Column;
import io.debezium.relational.ColumnEditor;

/**
 * Marks an untagged (CCSID 65535) column decided text on its Debezium column: typed {@code CHAR} or
 * {@code VARCHAR} so its Kafka schema is a string, and its CCSID carried as the charset name, so the value
 * converter can decode the {@code byte[]} a snapshot reads over JDBC (Popsink/data-plane#5831). The type
 * name stays {@code ... FOR BIT DATA}: the journal decoder keys off it.
 */
public final class UntaggedText {

    private UntaggedText() {
    }

    public static ColumnEditor mark(ColumnEditor column, String typeName, int ccsid) {
        return column.jdbcType(JdbcFileDecoder.isVarBitData(typeName) ? Types.VARCHAR : Types.CHAR)
                .charsetName(Integer.toString(ccsid));
    }

    /** @return the CCSID of a column {@link #mark}ed as text, empty for any other column */
    public static OptionalInt ccsidOf(Column column) {
        if (column == null || !JdbcFileDecoder.isBitData(column.typeName()) || column.charsetName() == null) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of(Integer.parseInt(column.charsetName()));
        }
        catch (final NumberFormatException e) {
            return OptionalInt.empty();
        }
    }
}
