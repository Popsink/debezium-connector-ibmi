/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;

/**
 * The decoder resolves the key of every table it streams, so it is the second place - next to the
 * connector's schema discovery - that has to refuse a DDS keyed access path allowing duplicates.
 * These check the flag actually reaches the query result, and that the file is still addressed by its
 * library and system name.
 */
class JdbcFileDecoderDdsKeyTest {

    private static final As400TextFactory TEXT_FACTORY = As400TextFactory.forCcsid(37);

    private final PreparedStatement statement = mock(PreparedStatement.class);

    private JdbcFileDecoder decoderReturning(String dbxunq, String... keyColumns) throws Exception {
        final ResultSet rs = mock(ResultSet.class);
        final Boolean[] more = new Boolean[keyColumns.length];
        for (int i = 0; i < keyColumns.length; i++) {
            more[i] = i < keyColumns.length - 1;
        }
        when(rs.next()).thenReturn(true, more);
        final String[] rest = new String[keyColumns.length - 1];
        for (int i = 1; i < keyColumns.length; i++) {
            rest[i - 1] = keyColumns[i];
        }
        when(rs.getString(1)).thenReturn(keyColumns[0], rest);
        when(rs.getString(2)).thenReturn(dbxunq);
        when(statement.executeQuery()).thenReturn(rs);

        final Connection con = mock(Connection.class);
        when(con.prepareStatement(anyString())).thenReturn(statement);
        return new JdbcFileDecoder(() -> con, "DB", new SchemaCacheHash(), TEXT_FACTORY, -1, -1);
    }

    @Test
    void aUniqueDdsKeyKeysTheRecordsAndTheFileIsBoundByLibraryAndSystemName() throws Exception {
        final JdbcFileDecoder decoder = decoderReturning("U", "ORDER_NO");

        assertEquals(List.of("ORDER_NO"), decoder.ddsPrimaryKeys("ORDRS", "MYLIB"));
        verify(statement).setString(1, "MYLIB");
        verify(statement).setString(2, "ORDRS");
    }

    @Test
    void aDuplicateAllowingDdsKeyLeavesTheRecordsUnkeyed() throws Exception {
        final JdbcFileDecoder decoder = decoderReturning("D", "ACCOUNT", "POSTED_ON");

        assertEquals(List.of(), decoder.ddsPrimaryKeys("GLDTL", "MYLIB"));
    }

    @Test
    void theDuplicateAllowingKeyIsKeptOnceUniquenessIsNoLongerRequired() throws Exception {
        final JdbcFileDecoder decoder = decoderReturning("D", "ACCOUNT", "POSTED_ON");
        decoder.setRequireUniqueDdsKey(false);

        assertEquals(List.of("ACCOUNT", "POSTED_ON"), decoder.ddsPrimaryKeys("GLDTL", "MYLIB"));
    }
}
