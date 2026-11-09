/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Types;

import org.junit.jupiter.api.Test;

import io.debezium.connector.db2as400.conversion.UntaggedText;
import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.retrieve.UntaggedColumns;
import io.debezium.ibmi.db2.journal.retrieve.UntaggedDecisions;
import io.debezium.relational.Column;
import io.debezium.relational.ColumnEditor;
import io.debezium.relational.TableId;

/** The snapshot's columns: an untagged column decided text is typed as text, one kept binary is not (#5831). */
class As400JdbcConnectionUntaggedTest {

    private static final TableId TABLE = new TableId("DB", "LIB", "T");

    private static Column snapshotColumn(String name, String type, UntaggedColumns untagged) {
        final ColumnEditor column = Column.editor().name(name).type(type).jdbcType(Types.BINARY);
        return As400JdbcConnection.untaggedText(column, TABLE, untagged).create();
    }

    @Test
    void untaggedColumnsAreTypedByTheirDecision() {
        final UntaggedDecisions decisions = new UntaggedDecisions();
        decisions.load("LIB.T.NAME=1388;LIB.T.PWD=binary");
        final UntaggedColumns untagged = new UntaggedColumns(null, decisions, As400TextFactory.forCcsid(935));

        final Column name = snapshotColumn("NAME", "CHAR () FOR BIT DATA", untagged);
        assertThat(name.jdbcType()).isEqualTo(Types.CHAR);
        assertThat(UntaggedText.ccsidOf(name)).hasValue(1388);

        final Column pwd = snapshotColumn("PWD", "CHAR () FOR BIT DATA", untagged);
        assertThat(pwd.jdbcType()).isEqualTo(Types.BINARY);
        assertThat(UntaggedText.ccsidOf(pwd)).isEmpty();
    }

    @Test
    void withoutTheSchemaWiredEveryColumnIsLeftAlone() {
        assertThat(snapshotColumn("NAME", "CHAR () FOR BIT DATA", null).jdbcType()).isEqualTo(Types.BINARY);
    }
}
