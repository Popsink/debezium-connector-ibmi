/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.retrieve.SchemaCacheIF.Structure;
import io.debezium.ibmi.db2.journal.retrieve.SchemaCacheIF.TableInfo;

/** The journal's record format types an untagged column decided text as text, so it streams as a string (#5831). */
class JdbcFileDecoderUntaggedTest {

    /** Two {@code CHAR(8) FOR BIT DATA} columns, as jt400's metadata reports them. */
    private static ResultSet columns() throws SQLException {
        final ResultSet rs = mock(ResultSet.class);
        final String[] names = { "NAME", "PWD" };
        final int[] at = { -1 };
        when(rs.next()).thenAnswer(i -> ++at[0] < names.length);
        when(rs.getString(4)).thenAnswer(i -> names[at[0]]);
        when(rs.getString(6)).thenReturn("CHAR () FOR BIT DATA");
        when(rs.getInt(5)).thenReturn(Types.BINARY);
        when(rs.getInt(7)).thenReturn(8);
        when(rs.getInt(16)).thenReturn(8);
        when(rs.getInt(17)).thenAnswer(i -> at[0] + 1);
        return rs;
    }

    @Test
    void anUntaggedColumnDecidedTextIsTypedAsText() throws SQLException {
        final Connection connection = mock(Connection.class, RETURNS_DEEP_STUBS);
        final DatabaseMetaData metadata = mock(DatabaseMetaData.class, RETURNS_DEEP_STUBS);
        when(connection.getMetaData()).thenReturn(metadata);
        final ResultSet columns = columns();
        when(metadata.getColumns(any(), eq("LIB"), eq("T"), any())).thenReturn(columns);

        final UntaggedDecisions decisions = new UntaggedDecisions();
        decisions.load("LIB.T.NAME=1388;LIB.T.PWD=hex");
        final JdbcFileDecoder decoder = new JdbcFileDecoder(() -> connection, "DB", new SchemaCacheHash(),
                As400TextFactory.forCcsid(935), -1, -1, decisions);

        final TableInfo format = decoder.getRecordFormat("T", "LIB").orElseThrow();
        final List<Structure> structure = format.getStructure();

        assertEquals(Types.CHAR, structure.get(0).getJdcbType());
        assertEquals(1388, structure.get(0).getCcsid());
        assertEquals(Types.BINARY, structure.get(1).getJdcbType());
        assertEquals(-1, structure.get(1).getCcsid());
        assertEquals(16, format.getAs400Structure().getByteLength(), "both stay 8 bytes wide in the record");
    }
}
