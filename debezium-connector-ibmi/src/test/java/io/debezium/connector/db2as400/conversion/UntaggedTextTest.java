/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400.conversion;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.debezium.ibmi.db2.journal.retrieve.SchemaCacheIF.Structure;
import io.debezium.ibmi.db2.journal.retrieve.SchemaCacheIF.TableInfo;
import io.debezium.relational.Column;
import io.debezium.relational.Table;

/** An untagged column read as text is a string column, on both the journal and the snapshot paths (#5831). */
class UntaggedTextTest {

    private static Table journalTable(Structure... columns) {
        return SchemaInfoConversion.tableInfo2Table("DB", "LIB", "T", new TableInfo(List.of(columns), List.of(), null));
    }

    @Test
    void aJournalColumnReadAsTextIsAStringColumnCarryingItsCcsid() {
        final Table table = journalTable(
                new Structure("NAME", "CHAR () FOR BIT DATA", Types.CHAR, 8, 0, true, 1, false, 1388),
                new Structure("NOTE", "VARCHAR () FOR BIT DATA", Types.VARCHAR, 20, 0, true, 2, false, 1388),
                new Structure("PWD", "CHAR () FOR BIT DATA", Types.BINARY, 8, 0, true, 3, false));

        final Column name = table.columnWithName("NAME");
        assertThat(name.jdbcType()).isEqualTo(Types.CHAR);
        assertThat(UntaggedText.ccsidOf(name)).hasValue(1388);
        assertThat(table.columnWithName("NOTE").jdbcType()).isEqualTo(Types.VARCHAR);
        final Column pwd = table.columnWithName("PWD");
        assertThat(pwd.jdbcType()).isEqualTo(Types.BINARY);
        assertThat(UntaggedText.ccsidOf(pwd)).isEmpty();
    }

    /** Snapshot to journal: the table a snapshot registers keeps the CCSID when turned back into a format. */
    @Test
    void theCcsidSurvivesTheRoundTripToAStructure() {
        final Column marked = UntaggedText.mark(Column.editor().name("NAME").type("VARCHAR () FOR BIT DATA"),
                "VARCHAR () FOR BIT DATA", 1388).create();
        assertThat(marked.jdbcType()).isEqualTo(Types.VARCHAR);

        final Table table = Table.editor().tableId(new io.debezium.relational.TableId("DB", "LIB", "T"))
                .addColumn(marked).create();
        final Structure structure = SchemaInfoConversion.table2Structure(table).get(0);
        assertThat(structure.getCcsid()).isEqualTo(1388);
        assertThat(structure.getJdcbType()).isEqualTo(Types.VARCHAR);
    }

    @Test
    void anOrdinaryColumnHasNoUntaggedCcsid() {
        assertThat(UntaggedText.ccsidOf(Column.editor().name("C").type("CHAR").charsetName("1388").create())).isEmpty();
    }
}
