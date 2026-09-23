/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400DataType;
import com.ibm.as400.access.AS400Structure;

import io.debezium.ibmi.db2.journal.data.types.AS400Lob;
import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.retrieve.JdbcFileDecoder;
import io.debezium.ibmi.db2.journal.retrieve.SchemaCacheHash;
import io.debezium.relational.Column;
import io.debezium.relational.Table;
import io.debezium.relational.TableEditor;
import io.debezium.relational.TableId;

/**
 * A snapshot builds the record format from a Debezium {@link Table} rather than from the JDBC metadata,
 * and {@code As400DatabaseSchema.addSchema} stores it in the same cache the streaming decoder reads. So
 * this second calculation of the column offsets has to agree with the first one, or a table would decode
 * correctly until a snapshot ran and then quietly shift - which a large object column makes easier,
 * since its own width is worked out from the widths in front of it.
 */
class SchemaInfoConversionLobTest {

    /** Pinned so the widths do not move with the locale the tests run under. */
    private static final As400TextFactory TEXT_FACTORY = As400TextFactory.forCcsid(37);

    private static JdbcFileDecoder decoder() {
        // no connection, so the ccsid and octet length lookups fail and fall back - the widths under test
        // do not depend on them for single byte text
        return new JdbcFileDecoder(() -> {
            throw new SQLException("no connection");
        }, "DB", new SchemaCacheHash(), TEXT_FACTORY, -1, -1);
    }

    private static TableEditor table() {
        return Table.editor().tableId(new TableId("DB", "PYP31", "CLOBTST"));
    }

    private static Column column(String name, String typeName, int jdbcType, int length, int position) {
        return Column.editor().name(name).type(typeName).jdbcType(jdbcType).length(length).position(position).create();
    }

    /**
     * The layout measured on a live journal entry for
     * {@code (ID INT, HEAD CHAR(5), BODY CLOB(1M), MID CHAR(3), RAW BLOB(1M), DOC XML, TAIL CHAR(2))}:
     * a 114 byte record image, the three descriptors padded by 10, 0 and 3 so each pointer lands on a
     * multiple of 16.
     */
    @Test
    void buildsTheSameLayoutAsTheJournalEntryHas() {
        final Table table = table()
                .addColumn(column("ID", "INTEGER", Types.INTEGER, 4, 1))
                .addColumn(column("HEAD", "CHAR", Types.CHAR, 5, 2))
                .addColumn(column("BODY", "CLOB", Types.CLOB, 1048576, 3))
                .addColumn(column("MID", "CHAR", Types.CHAR, 3, 4))
                .addColumn(column("RAW", "BLOB", Types.BLOB, 1048576, 5))
                .addColumn(column("DOC", "XML", Types.SQLXML, Integer.MAX_VALUE, 6))
                .addColumn(column("TAIL", "CHAR", Types.CHAR, 2, 7))
                .create();

        final AS400Structure structure = new SchemaInfoConversion(decoder()).table2As400Structure(table);

        assertThat(widths(structure)).containsExactly(4, 5, 39, 3, 29, 32, 2);
        assertThat(structure.getByteLength()).isEqualTo(114);
        assertThat(pointerOffsets(structure)).allSatisfy(offset -> assertThat(offset % 16).isZero());
    }

    /** The same columns in a different order have to be re-padded, not given the same widths. */
    @Test
    void repadsTheDescriptorsWhenTheColumnsMove() {
        final Table table = table()
                .addColumn(column("BODY", "CLOB", Types.CLOB, 1048576, 1))
                .addColumn(column("ID", "INTEGER", Types.INTEGER, 4, 2))
                .addColumn(column("TAIL", "CHAR", Types.CHAR, 2, 3))
                .create();

        final AS400Structure structure = new SchemaInfoConversion(decoder()).table2As400Structure(table);

        // a lob first is padded by 3, so 32 wide, and its pointer lands on 16
        assertThat(widths(structure)).containsExactly(32, 4, 2);
        assertThat(pointerOffsets(structure)).containsExactly(16);
    }

    /** Two large objects next to each other: the first one's width decides the second one's padding. */
    @Test
    void chainsAdjacentDescriptors() {
        final Table table = table()
                .addColumn(column("A", "CLOB", Types.CLOB, 1048576, 1))
                .addColumn(column("B", "BLOB", Types.BLOB, 1048576, 2))
                .addColumn(column("TAIL", "CHAR", Types.CHAR, 2, 3))
                .create();

        final AS400Structure structure = new SchemaInfoConversion(decoder()).table2As400Structure(table);

        assertThat(widths(structure)).containsExactly(32, 32, 2);
        assertThat(pointerOffsets(structure)).containsExactly(16, 48);
    }

    private static Integer[] widths(AS400Structure structure) {
        final AS400DataType[] members = structure.getMembers();
        final Integer[] widths = new Integer[members.length];
        for (int i = 0; i < members.length; i++) {
            widths[i] = members[i].getByteLength();
        }
        return widths;
    }

    /** Where each large object's 16 byte pointer sits in the record, which has to be 16 byte aligned. */
    private static Integer[] pointerOffsets(AS400Structure structure) {
        final AS400DataType[] members = structure.getMembers();
        final List<Integer> offsets = new ArrayList<>();
        int offset = 0;
        for (final AS400DataType member : members) {
            offset += member.getByteLength();
            if (member instanceof AS400Lob) {
                // the pointer is the last 16 bytes of the column
                offsets.add(offset - 16);
            }
        }
        return offsets.toArray(new Integer[0]);
    }
}
