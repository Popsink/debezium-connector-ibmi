/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The DDS keyed access path of a physical file, and whether it may stand in for a primary key.
 *
 * <p>A file created in DDS has no SQL primary key, only the {@code K} specifications of its keyed
 * access path, which the connector has always fallen back on to key its records. A DDS key is an
 * ordering though, not an identity: unless the file was created with {@code UNIQUE}, IBM i accepts
 * any number of records sharing one key value. Keying records on it then tells every downstream
 * consumer that those records are versions of one row, and a keyed destination - a Snowflake dynamic
 * table, a compacted topic - keeps only the last of them.
 *
 * <p>{@code QSYS2.SYSFILES.ACCESS_PATH_TYPE} is the access path's uniqueness: {@code KEYED UNIQUE}
 * when the file was created with {@code UNIQUE}, a {@code KEYED NO ORDER} / {@code FIFO} /
 * {@code LIFO} / {@code FCFO} path when duplicates are allowed. Only a unique path's key is an
 * identity, so only that key is used as a record key; a duplicate-allowing file streams without one,
 * which the destinations already handle (they fall back to whole-record matching). The one thing that
 * cannot be told apart without a key is two byte-identical records, which no key could tell apart
 * either.
 *
 * <p>{@code QSYS.QADBXREF.DBXUNQ} carries the same flag but reading the cross-reference file needs an
 * authority a connector user does not have on every system: PUB400 refuses it with {@code SQL0551},
 * which failed every table without a SQL primary key at start (data-plane {@code UniqueKeyTest},
 * {@code NonIsoDdsDateTest}). {@code SYSFILES} needs only {@code *OBJOPR} on the file and
 * {@code *EXECUTE} on its library, what reading the file needs anyway, and like {@code QADBKATR} it is
 * keyed on system names. It exists from IBM i 7.4 TR7 / 7.5 TR1; an older release fails this read
 * with {@code SQL0204} at the same place, never silently.
 *
 * <p>The read is shared because the same {@code QADBKATR} join is made from both the connector's
 * schema discovery ({@code As400JdbcConnection}) and the journal decoder
 * ({@link JdbcFileDecoder}), and the two must agree on the key of a table.
 */
public final class DdsKeys {

    private static final Logger log = LoggerFactory.getLogger(DdsKeys.class);

    /**
     * Columns of the keyed access path in key order, each carrying the access path's type.
     *
     * <p>{@code QADBKATR} holds one row per key field, so the type is joined in rather than read
     * separately: a second round trip per table would double the cost of schema discovery, and the two
     * reads could disagree if the file were recreated between them.
     */
    public static final String KEY_COLUMNS = """
            SELECT c.column_name, f.ACCESS_PATH_TYPE FROM qsys.QADBKATR k
                  INNER JOIN qsys2.SYSCOLUMNS c on c.table_schema=k.dbklib and c.system_table_name=k.dbkfil AND c.system_column_name=k.DBKFLD
                  INNER JOIN qsys2.SYSFILES f on f.SYSTEM_TABLE_SCHEMA=k.DBKLIB AND f.SYSTEM_TABLE_NAME=k.DBKFIL
                  WHERE k.dbklib=? AND k.dbkfil=? ORDER BY k.DBKPOS ASC
                 """;

    /** {@code SYSFILES.ACCESS_PATH_TYPE} of a file created with {@code UNIQUE}; every other keyed type allows duplicates. */
    private static final String UNIQUE = "KEYED UNIQUE";

    private DdsKeys() {
    }

    /**
     * The key of one file: its columns in key order, and whether the file requires them to be unique.
     */
    public record DdsKey(List<String> columns, boolean unique) {

        /**
         * The columns to key records on, empty when the file allows duplicate key values and
         * {@code requireUnique} is set. Either way the caller is told, once per table, what was decided
         * and what it costs - this is the difference between a destination holding every record and one
         * holding one record per key value, so it must never be silent.
         */
        public List<String> asRecordKey(boolean requireUnique, String schema, String table) {
            if (columns.isEmpty() || unique) {
                return columns;
            }
            if (requireUnique) {
                log.warn("{}.{} has a DDS keyed access path on {} but allows duplicate key values "
                        + "(QSYS2.SYSFILES.ACCESS_PATH_TYPE is not KEYED UNIQUE), so it is not used as the record key and the records are "
                        + "produced without one. Set dds.key.require.unique=false to key them on it anyway, which "
                        + "is only safe when the key is known to be unique in practice.", schema, table, columns);
                return List.of();
            }
            log.warn("dds.key.require.unique=false: {}.{} allows duplicate key values "
                    + "(QSYS2.SYSFILES.ACCESS_PATH_TYPE is not KEYED UNIQUE) but its DDS keyed access path {} is used as the record key "
                    + "anyway; records sharing a key value collapse into one in any keyed destination.",
                    schema, table, columns);
            return columns;
        }
    }

    /** Reads a whole {@link #KEY_COLUMNS} result set. A file with no keyed access path returns no rows. */
    public static DdsKey read(ResultSet rs) throws SQLException {
        final List<String> columns = new ArrayList<>();
        boolean unique = false;
        while (rs.next()) {
            columns.add(StringHelpers.safeTrim(rs.getString(1)));
            // one row per key field, all carrying the same access path type
            unique = UNIQUE.equalsIgnoreCase(StringHelpers.safeTrim(rs.getString(2)));
        }
        return new DdsKey(columns, unique);
    }
}
