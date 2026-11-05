/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
 * authority a connector user does not have on every system: PUB400 refuses it with {@code SQL0551}.
 * {@code SYSFILES} needs only {@code *OBJOPR} on the file and {@code *EXECUTE} on its library, but only
 * exists from IBM i 7.4 TR7 / 7.5 TR1. So {@link Reader} tries {@code SYSFILES}, falls back on
 * {@code QADBXREF} when the view is missing ({@code SQL0204}), and when that is refused too reads the
 * key columns alone with an unknown uniqueness, handled as a duplicate-allowing file
 * (data-plane#5932: a release without {@code SYSFILES} crash-looped at every start).
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

    /** {@link #KEY_COLUMNS} for a release without {@code SYSFILES}: {@code DBXUNQ} is {@code U} for a unique file. */
    public static final String KEY_COLUMNS_QADBXREF = """
            SELECT c.column_name, x.DBXUNQ FROM qsys.QADBKATR k
                  INNER JOIN qsys2.SYSCOLUMNS c on c.table_schema=k.dbklib and c.system_table_name=k.dbkfil AND c.system_column_name=k.DBKFLD
                  INNER JOIN qsys.QADBXREF x on x.DBXLIB=k.DBKLIB AND x.DBXFIL=k.DBKFIL
                  WHERE k.dbklib=? AND k.dbkfil=? ORDER BY k.DBKPOS ASC
                 """;

    /** The key columns alone, when neither catalog of the uniqueness can be read. */
    public static final String KEY_COLUMNS_ONLY = """
            SELECT c.column_name FROM qsys.QADBKATR k
                  INNER JOIN qsys2.SYSCOLUMNS c on c.table_schema=k.dbklib and c.system_table_name=k.dbkfil AND c.system_column_name=k.DBKFLD
                  WHERE k.dbklib=? AND k.dbkfil=? ORDER BY k.DBKPOS ASC
                 """;

    /** jt400's {@link SQLException#getErrorCode()} for SQL0204, an object that does not exist. */
    private static final int OBJECT_NOT_FOUND = -204;
    /** SQL0551 / SQL0552: the user profile is not authorized to the object. */
    private static final int NOT_AUTHORIZED = -551;
    private static final int NOT_AUTHORIZED_OPERATION = -552;

    /** {@code SYSFILES.ACCESS_PATH_TYPE} of a file created with {@code UNIQUE}; every other keyed type allows duplicates. */
    private static final String UNIQUE = "KEYED UNIQUE";
    /** {@code QADBXREF.DBXUNQ} of a file created with {@code UNIQUE}; {@code D} allows duplicates. */
    private static final String DBXUNQ_UNIQUE = "U";

    /** What the catalog says of a file's key values. */
    public enum Uniqueness {
        UNIQUE,
        DUPLICATES_ALLOWED,
        /** Neither {@code SYSFILES} nor {@code QADBXREF} could be read; handled as {@link #DUPLICATES_ALLOWED}. */
        UNKNOWN
    }

    private DdsKeys() {
    }

    /**
     * The key of one file: its columns in key order, and whether the file requires them to be unique.
     */
    public record DdsKey(List<String> columns, Uniqueness uniqueness) {

        public DdsKey(List<String> columns, boolean unique) {
            this(columns, unique ? Uniqueness.UNIQUE : Uniqueness.DUPLICATES_ALLOWED);
        }

        public boolean unique() {
            return uniqueness == Uniqueness.UNIQUE;
        }

        /**
         * The columns to key records on, empty when the file may allow duplicate key values and
         * {@code requireUnique} is set. Either way the caller is told, once per table, what was decided
         * and what it costs - this is the difference between a destination holding every record and one
         * holding one record per key value, so it must never be silent.
         */
        public List<String> asRecordKey(boolean requireUnique, String schema, String table) {
            if (columns.isEmpty() || unique()) {
                return columns;
            }
            final String why = uniqueness == Uniqueness.UNKNOWN
                    ? "may allow duplicate key values (neither QSYS2.SYSFILES nor QSYS.QADBXREF could be read to tell)"
                    : "allows duplicate key values (its access path is not KEYED UNIQUE)";
            if (requireUnique) {
                log.warn("{}.{} has a DDS keyed access path on {} but {}, so it is not used as the record key and the "
                        + "records are produced without one. Set dds.key.require.unique=false to key them on it anyway, "
                        + "which is only safe when the key is known to be unique in practice.", schema, table, columns, why);
                return List.of();
            }
            log.warn("dds.key.require.unique=false: {}.{} {} but its DDS keyed access path {} is used as the record key "
                    + "anyway; records sharing a key value collapse into one in any keyed destination.",
                    schema, table, why, columns);
            return columns;
        }
    }

    /**
     * Reads DDS keys from the first catalog the system offers, remembered so a release without
     * {@code SYSFILES} pays one failing round trip per connection, not one per table. One per
     * connection: the catalogs are a property of the system it is connected to.
     */
    public static final class Reader {

        private enum Source {
            SYSFILES(KEY_COLUMNS),
            QADBXREF(KEY_COLUMNS_QADBXREF),
            KEY_ONLY(KEY_COLUMNS_ONLY);

            private final String query;

            Source(String query) {
                this.query = query;
            }
        }

        private volatile Source source = Source.SYSFILES;

        public DdsKey read(Connection con, String schema, String table) throws SQLException {
            while (true) {
                final Source current = source;
                try (PreparedStatement ps = con.prepareStatement(current.query)) {
                    ps.setString(1, schema);
                    ps.setString(2, table);
                    try (ResultSet rs = ps.executeQuery()) {
                        return switch (current) {
                            case SYSFILES -> DdsKeys.read(rs);
                            case QADBXREF -> readColumns(rs, DBXUNQ_UNIQUE);
                            case KEY_ONLY -> readColumns(rs, null);
                        };
                    }
                }
                catch (SQLException e) {
                    final Source next = fallback(current, e);
                    if (next == null) {
                        throw e;
                    }
                    log.warn("Reading the uniqueness of DDS keys from {} failed ({}); falling back to {} for this connection.{}",
                            current, e.getMessage(), next,
                            next == Source.KEY_ONLY ? " Every DDS key is then handled as allowing duplicate key values"
                                    + " (see dds.key.require.unique)." : "");
                    source = next;
                }
            }
        }

        /** The next catalog to try, or null when this failure is not the catalog being missing or refused. */
        private static Source fallback(Source failed, SQLException e) {
            final int code = e.getErrorCode();
            return switch (failed) {
                case SYSFILES -> code == OBJECT_NOT_FOUND ? Source.QADBXREF : null;
                case QADBXREF -> code == OBJECT_NOT_FOUND || code == NOT_AUTHORIZED || code == NOT_AUTHORIZED_OPERATION
                        ? Source.KEY_ONLY
                        : null;
                case KEY_ONLY -> null;
            };
        }
    }

    /** Reads a whole {@link #KEY_COLUMNS} result set. A file with no keyed access path returns no rows. */
    public static DdsKey read(ResultSet rs) throws SQLException {
        return readColumns(rs, UNIQUE);
    }

    /** {@code uniqueValue} is column 2's value for a unique file; null when there is no column 2 to tell. */
    private static DdsKey readColumns(ResultSet rs, String uniqueValue) throws SQLException {
        final List<String> columns = new ArrayList<>();
        boolean unique = false;
        while (rs.next()) {
            columns.add(StringHelpers.safeTrim(rs.getString(1)));
            if (uniqueValue != null) {
                // one row per key field, all carrying the same access path type
                unique = uniqueValue.equalsIgnoreCase(StringHelpers.safeTrim(rs.getString(2)));
            }
        }
        if (uniqueValue == null) {
            return new DdsKey(columns, Uniqueness.UNKNOWN);
        }
        return new DdsKey(columns, unique);
    }
}
