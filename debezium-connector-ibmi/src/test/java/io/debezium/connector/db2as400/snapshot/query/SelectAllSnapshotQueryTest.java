/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400.snapshot.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the snapshot query builder, in particular the trailing RRN column added so that
 * op=r snapshot events carry source.rrn (#25).
 */
public class SelectAllSnapshotQueryTest {

    private final SelectAllSnapshotQuery query = new SelectAllSnapshotQuery();

    @Test
    public void appendsRrnAsTheLastColumnWithACorrelation() {
        final Optional<String> sql = query.snapshotQuery("MYLIB.MYTABLE", List.of("COL1", "COL2"));

        assertThat(sql).isPresent();
        assertThat(sql.get())
                .isEqualTo("SELECT COL1, COL2, RRN(DBZ_T) AS \"" + SelectAllSnapshotQuery.RRN_COLUMN_ALIAS
                        + "\" FROM MYLIB.MYTABLE DBZ_T");
    }

    @Test
    public void rrnStaysLastAndTableGetsTheCorrelation() {
        final String sql = query.snapshotQuery("MYLIB.MYTABLE", List.of("COL1")).orElseThrow();

        // the RRN scalar must be the final projected column, and must reference the FROM correlation
        assertThat(sql).endsWith("RRN(DBZ_T) AS \"" + SelectAllSnapshotQuery.RRN_COLUMN_ALIAS + "\" FROM MYLIB.MYTABLE DBZ_T");
        assertThat(sql.indexOf("RRN(")).isGreaterThan(sql.indexOf("COL1"));
    }

    @Test
    public void keepsExistingSingleToDoubleQuoteColumnRewrite() {
        // single quotes would otherwise turn the column names into 00001,00002,...
        final String sql = query.snapshotQuery("MYLIB.MYTABLE", List.of("'$SCHAR'", "COL2")).orElseThrow();

        assertThat(sql).startsWith("SELECT \"$SCHAR\", COL2, ");
        assertThat(sql).contains("RRN(DBZ_T) AS \"" + SelectAllSnapshotQuery.RRN_COLUMN_ALIAS + "\"");
    }
}
