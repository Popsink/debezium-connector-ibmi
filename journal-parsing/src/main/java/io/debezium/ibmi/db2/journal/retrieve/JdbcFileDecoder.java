/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.ibm.as400.access.AS400Bin2;
import com.ibm.as400.access.AS400Bin4;
import com.ibm.as400.access.AS400Bin8;
import com.ibm.as400.access.AS400ByteArray;
import com.ibm.as400.access.AS400DataType;
import com.ibm.as400.access.AS400Date;
import com.ibm.as400.access.AS400Float4;
import com.ibm.as400.access.AS400Float8;
import com.ibm.as400.access.AS400PackedDecimal;
import com.ibm.as400.access.AS400Structure;
import com.ibm.as400.access.AS400Text;
import com.ibm.as400.access.AS400Time;
import com.ibm.as400.access.AS400Timestamp;
import com.ibm.as400.access.AS400ZonedDecimal;
import com.ibm.as400.access.ExtendedIllegalArgumentException;

import io.debezium.ibmi.db2.journal.data.types.AS400Boolean;
import io.debezium.ibmi.db2.journal.data.types.AS400Lob;
import io.debezium.ibmi.db2.journal.data.types.AS400VarBin;
import io.debezium.ibmi.db2.journal.data.types.AS400VarChar;
import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.data.types.Diagnostics;
import io.debezium.ibmi.db2.journal.retrieve.JournalLobFetcher.LobSegment;
import io.debezium.ibmi.db2.journal.retrieve.SchemaCacheIF.Structure;
import io.debezium.ibmi.db2.journal.retrieve.SchemaCacheIF.TableInfo;
import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;

public class JdbcFileDecoder extends JournalFileEntryDecoder {

    private final AS400Float8 as400Float8 = new AS400Float8();
    private final AS400Float4 as400Float4 = new AS400Float4();
    private final AS400Timestamp as400Timestamp = new AS400Timestamp();
    private final AS400Bin8 as400Bin8 = new AS400Bin8();
    private final AS400Bin4 as400Bin4 = new AS400Bin4();
    private final AS400Bin2 as400Bin2 = new AS400Bin2();
    private final AS400Boolean as400Boolean = new AS400Boolean();
    private static final String GET_DATABASE_NAME = "values ( CURRENT_SERVER )";
    private static final String UNIQUE_KEYS = """
            SELECT c.column_name FROM qsys.QADBKATR k
                  INNER JOIN qsys2.SYSCOLUMNS c on c.table_schema=k.dbklib and c.system_table_name=k.dbkfil AND c.system_column_name=k.DBKFLD
                  WHERE k.dbklib=? AND k.dbkfil=? ORDER BY k.DBKPOS ASC
                 """;

    private final Connect<Connection, SQLException> jdbcConnect;
    private final String databaseName;
    private final SchemaCacheIF schemaCache;
    private final CcsidCache ccsidCache;
    private final BytesPerChar octetLengthCache;
    private final DateTimeFormatCache dateTimeFormatCache;
    private final As400TextFactory textFactory;
    /** Only known once the journal has been resolved, and null when lob fetching is turned off. */
    private JournalLobFetcher lobFetcher;
    /** Both hold the tables already logged about, so a per-entry problem is reported once, not per row. */
    private final Set<String> lobsUnfetchedLogged = ConcurrentHashMap.newKeySet();
    private final Set<String> recordLengthLogged = ConcurrentHashMap.newKeySet();
    private final Set<String> lobSplitLogged = ConcurrentHashMap.newKeySet();
    private final Set<String> descriptorLogged = ConcurrentHashMap.newKeySet();

    public JdbcFileDecoder(Connect<Connection, SQLException> con, String database, SchemaCacheIF schemaCache,
                           As400TextFactory textFactory, int fromCcsid, int toCcsid) {
        super();
        this.jdbcConnect = con;
        this.schemaCache = schemaCache;
        this.databaseName = database;
        this.textFactory = textFactory;
        this.lengthDecoder = textFactory.text(5);
        ccsidCache = new CcsidCache(con, fromCcsid, toCcsid);
        octetLengthCache = new BytesPerChar(con);
        dateTimeFormatCache = new DateTimeFormatCache(con);
    }

    /**
     * Sets how LOB columns are read. The journal entry only carries a pointer to the LOB data that
     * cannot be followed from here, so without a fetcher LOB columns decode as null.
     */
    public void setLobFetcher(JournalLobFetcher lobFetcher) {
        this.lobFetcher = lobFetcher;
    }

    /*
     * https://www.ibm.com/support/knowledgecenter/ssw_ibm_i_74/apis/QJORJRNE.htm
     * This journal entry's entry specific data Offset Type Field Dec Hex 0 0
     * CHAR(5) Length of entry specific data 5 5 CHAR(11) Reserved 16 16 CHAR(*)
     * Entry specific data
     */
    private final AS400Text lengthDecoder;
    /** Rows seen per undecodable (library.file.column), to keep the warning from repeating per row. */
    private final Map<String, AtomicLong> undecodableColumnCounts = new ConcurrentHashMap<>();
    private static final Object[] EMPTY = new Object[]{};
    private static final byte[] EMPTY_BYTES = new byte[0];

    @Override
    public Object[] decodeFile(EntryHeader entryHeader, byte[] data, int offset, boolean[] isNull) throws Exception {
        final Optional<TableInfo> tableInfoOpt = getRecordFormat(entryHeader.getFile(), entryHeader.getLibrary());

        return tableInfoOpt.map(tableInfo -> {
            final String lengthStr = (String) lengthDecoder.toObject(data,
                    offset + entryHeader.getEntrySpecificDataOffset());
            final int length = Integer.parseInt(lengthStr);
            if (length > 0) {
                final List<LobField> lobFields = new ArrayList<>();
                final List<UndecodableField> undecodable = new ArrayList<>();
                final int recordStart = offset + entryHeader.getEntrySpecificDataOffset() + ENTRY_SPECIFIC_DATA_OFFSET;
                final Object[] os = decodeEntry(tableInfo.getAs400Structure(), data, recordStart, isNull,
                        lobFields, undecodable);
                if (!lobFields.isEmpty()) {
                    resolveLobs(entryHeader, tableInfo, os, lobFields, length);
                }
                if (!undecodable.isEmpty()) {
                    // reported here rather than in decodeEntry, which knows the types but not the column names
                    reportUndecodable(entryHeader, tableInfo, undecodable,
                            Diagnostics.hex(data, recordStart, length, Diagnostics.MAX_LOGGED_RECORD_BYTES));
                }
                return os;
            }
            else {
                log.error("Empty journal entry for {}.{} is (before image) journalling set corretly for this table?",
                        entryHeader.getLibrary(), entryHeader.getFile());
                return EMPTY;
            }
        }).orElse(EMPTY);
    }

    /**
     * Decodes the structure field-by-field, mirroring {@link AS400Structure#toObject(byte[], int)}
     * (sequential decode advancing the offset by each member's byte length, no alignment), with one
     * difference: fields flagged as null by {@code isNull} are not decoded. Their slot in the record
     * image is skipped (the offset is still advanced) and the value is left null, because a null
     * column's bytes are not a valid encoding for strict types (e.g. blank-filled packed decimal),
     * which would otherwise throw and abort the whole record.
     */
    public Object[] decodeEntry(AS400Structure entryDetailStructure, byte[] data, int offset, boolean[] isNull) {
        return decodeEntry(entryDetailStructure, data, offset, isNull, new ArrayList<>());
    }

    public Object[] decodeEntry(AS400Structure entryDetailStructure, byte[] data, int offset, boolean[] isNull,
                                List<LobField> lobFields) {
        return decodeEntry(entryDetailStructure, data, offset, isNull, lobFields, new ArrayList<>());
    }

    /**
     * @param lobFields   collects the LOB columns found, in column order, for
     *                    {@link #resolveLobs} to fill in - their value is never in the record image
     * @param undecodable collects the columns left null because their bytes could not be decoded, for the
     *                    caller to report against the column names it knows
     */
    public Object[] decodeEntry(AS400Structure entryDetailStructure, byte[] data, int offset, boolean[] isNull,
                                List<LobField> lobFields, List<UndecodableField> undecodable) {
        final AS400DataType[] members = entryDetailStructure.getMembers();
        final Object[] result = new Object[members.length];
        int fieldOffset = offset;
        for (int i = 0; i < members.length; i++) {
            final AS400DataType member = members[i];
            final boolean fieldIsNull = isNull != null && i < isNull.length && isNull[i];
            if (member instanceof final AS400Lob lob) {
                if (lob.getRecordOffset() != fieldOffset - offset) {
                    // its width was worked out from that offset, so everything after it is now adrift.
                    // Detail only: the format is the same for every entry of the table, and resolveLobs
                    // reports the mismatch once for it.
                    log.debug("lob column {} was built for record offset {} but is being read at {}, the record "
                            + "format does not match the journal entry", i, lob.getRecordOffset(), fieldOffset - offset);
                }
                // a null column's descriptor holds no length, and reading one is pointless anyway
                final int dataLength = fieldIsNull ? AS400Lob.UNKNOWN_LENGTH : lob.dataLength(data, fieldOffset);
                lobFields.add(new LobField(i, lob, fieldIsNull, dataLength));
            }
            else if (!fieldIsNull) {
                if (isBlankFilledDecimal(member, data, fieldOffset)) {
                    // a 0x40-filled numeric is how an uninitialised value is held in an old physical file,
                    // not corruption, and the journal does not flag it null because the column is not
                    // null-capable. Left null rather than handed to jt400, which would throw
                    undecodable.add(new UndecodableField(i, member, null));
                }
                else {
                    try {
                        result[i] = member.toObject(data, fieldOffset);
                    }
                    catch (final NumberFormatException | ExtendedIllegalArgumentException e) {
                        // one strict-typed column must not cost the whole record. The offset comes from
                        // getByteLength(), not from the decode, so every field after this one stays
                        // aligned; anything the driver rejects for other reasons (a record image that
                        // does not match the format, say) still aborts the entry, because tolerating
                        // that would produce plausible garbage instead of an attributable failure
                        undecodable.add(new UndecodableField(i, member, e));
                    }
                }
            }
            fieldOffset += member.getByteLength();
        }
        return result;
    }

    /**
     * A column left null because its bytes could not be decoded.
     *
     * @param cause what the driver threw, or {@code null} when the field was blank-filled and so was never
     *              offered to it
     */
    public record UndecodableField(int index, AS400DataType type, RuntimeException cause) {
    }

    /** EBCDIC space, what an uninitialised fixed-width field is filled with. */
    private static final byte EBCDIC_BLANK = 0x40;

    /**
     * Whether a zoned or packed decimal field holds nothing but EBCDIC blanks, the legacy encoding for "no
     * value" in physical files old enough to predate null-capable columns. Cheaper than letting jt400 throw,
     * and it separates the expected case from a genuinely corrupt one in the log.
     */
    private static boolean isBlankFilledDecimal(AS400DataType member, byte[] data, int offset) {
        if (!(member instanceof AS400PackedDecimal) && !(member instanceof AS400ZonedDecimal)) {
            return false;
        }
        final int end = offset + member.getByteLength();
        if (offset < 0 || end > data.length) {
            // truncated: let the driver report it, the record image does not match the format
            return false;
        }
        for (int at = offset; at < end; at++) {
            if (data[at] != EBCDIC_BLANK) {
                return false;
            }
        }
        return true;
    }

    /**
     * Warns about columns that decoded to null, once per (table, column) and then on each power of ten, so a
     * column that fails on every row of a hot table states the problem and its running total without
     * drowning the log - the pre-existing behaviour was one line per row, at 24 an hour on the deployment in
     * issue #52.
     */
    private void reportUndecodable(EntryHeader entryHeader, TableInfo tableInfo, List<UndecodableField> fields,
                                   String recordImageHex) {
        final List<Structure> columns = tableInfo.getStructure();
        for (final UndecodableField field : fields) {
            final String column = field.index() < columns.size()
                    ? columns.get(field.index()).getName()
                    : "#" + field.index();
            final String key = String.format("%s.%s.%s", entryHeader.getLibrary(), entryHeader.getFile(), column);
            final long seen = undecodableColumnCounts.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
            if (!isFirstOrPowerOfTen(seen)) {
                continue;
            }
            if (field.cause() == null) {
                log.warn("column {} of {}.{} is blank-filled and not flagged null, read as null instead of "
                        + "failing the record ({} rows so far), RRN {} record image (hex) {}", column,
                        entryHeader.getLibrary(), entryHeader.getFile(), seen, entryHeader.getRelativeRecordNumber(),
                        recordImageHex);
            }
            else {
                log.warn("column {} of {}.{} could not be decoded as {}, read as null instead of failing the "
                        + "record ({} rows so far), RRN {} record image (hex) {}", column, entryHeader.getLibrary(),
                        entryHeader.getFile(), field.type().getClass().getSimpleName(), seen,
                        entryHeader.getRelativeRecordNumber(), recordImageHex, field.cause());
            }
        }
    }

    /** 1, 10, 100, ...: enough to state the magnitude of a column that fails on every row, without a line per row. */
    private static boolean isFirstOrPowerOfTen(long count) {
        for (long at = 1; at > 0 && at <= count; at *= 10) {
            if (at == count) {
                return true;
            }
        }
        return false;
    }

    /**
     * A LOB column of a decoded record, whose value has still to be fetched.
     *
     * @param dataLength what its descriptor says it holds, or {@link AS400Lob#UNKNOWN_LENGTH} when that
     *                   could not be read - as it never is for a null column
     */
    public record LobField(int index, AS400Lob lob, boolean isNull, int dataLength) {

        boolean isKnown() {
            return dataLength != AS400Lob.UNKNOWN_LENGTH;
        }

        boolean isEmpty() {
            return dataLength == 0;
        }

        /** Bytes this column contributes to the data appended to the journal entry. */
        int appendedByteLength() {
            return (isNull || !isKnown()) ? 0 : lob.byteLengthOf(dataLength);
        }
    }

    /**
     * Fills in the LOB columns of a decoded record. A null LOB stays null and an empty one needs no
     * fetching; anything else is re-read from the journal, which is the only place the data can be had
     * from - see {@link JournalLobFetcher}. Without a fetcher configured, or when the fetch fails, the
     * column is left null rather than guessed at.
     *
     * @param recordLength the entry's own length of its record image, which is where the lob data the
     *                     fetcher returns is appended
     */
    private void resolveLobs(EntryHeader entryHeader, TableInfo tableInfo, Object[] result, List<LobField> lobFields,
                             int recordLength) {
        // the driver's own total, which is the sum of the column widths - no alignment between them
        final int formatLength = tableInfo.getAs400Structure().getByteLength();
        if (formatLength != recordLength && firstTimeFor(recordLengthLogged, entryHeader)) {
            // the entry says how long its record image is; the columns adding up to something else means
            // the format is out of step with it, and the values decoded from it are suspect
            log.warn("the record format of {}.{} is {} bytes but the journal entry's record image is {}",
                    entryHeader.getLibrary(), entryHeader.getFile(), formatLength, recordLength);
        }

        // check every descriptor before setting anything: the appended segments are sized from the
        // descriptors, so one that could not be read makes every segment after it guesswork. Bailing out
        // half way through the assignment below would leave the columns before the bad one filled in and
        // the ones after it null, which is an ordering accident rather than a decision.
        for (final LobField lobField : lobFields) {
            if (!lobField.isNull() && !lobField.isKnown()) {
                // the descriptor is where it is because of the record format, so this fails the same way
                // for every entry of the table - reported once rather than once a row
                if (firstTimeFor(descriptorLogged, entryHeader)) {
                    log.error("unreadable lob descriptor for column {} of {}.{}, so its lob columns will "
                            + "stream as null. Turn on debug for AS400Lob to see what was read where",
                            columnName(tableInfo, lobField.index()), entryHeader.getLibrary(),
                            entryHeader.getFile());
                }
                return;
            }
        }

        boolean anyToFetch = false;
        for (final LobField lobField : lobFields) {
            if (lobField.isNull()) {
                continue;
            }
            // an empty lob is empty according to its own descriptor, so it needs nothing fetched
            if (lobField.isEmpty()) {
                result[lobField.index()] = lobField.lob().isBinary() ? EMPTY_BYTES : "";
            }
            else {
                anyToFetch = true;
            }
        }
        if (!anyToFetch) {
            return;
        }
        if (lobFetcher == null) {
            logLobsUnfetched(entryHeader);
            return;
        }

        final int[] byteLengths = lobFields.stream().mapToInt(LobField::appendedByteLength).toArray();
        lobFetcher.entryData(entryHeader)
                .ifPresent(entryData -> {
                    final List<LobSegment> segments = JournalLobFetcher.splitLobs(entryData, recordLength, byteLengths);
                    if (segments.isEmpty() && firstTimeFor(lobSplitLogged, entryHeader)) {
                        // a misread descriptor is a property of the record format, so it fails for every
                        // entry of the table; reported once rather than once a row. Turn on debug for
                        // JournalLobFetcher to see which segment and which lengths did not fit.
                        log.error("the lob data of {}.{} could not be split into its columns, so they will "
                                + "stream as null. The record format is out of step with the journal "
                                + "entry - see the record length warning for this table",
                                entryHeader.getLibrary(), entryHeader.getFile());
                    }
                    for (int i = 0; i < segments.size(); i++) {
                        final LobField lobField = lobFields.get(i);
                        if (!lobField.isNull() && !lobField.isEmpty()) {
                            result[lobField.index()] = decodeLob(lobField.lob(), entryData, segments.get(i));
                        }
                    }
                });
    }

    /**
     * A blob's data is bytes; everything else is text in the column's own CCSID. Both read the segment
     * straight out of the entry data - only a blob has to copy, because its value is handed on as the
     * array itself and must not keep the whole entry, lob columns and all, reachable behind it.
     */
    Object decodeLob(AS400Lob lob, byte[] entryData, LobSegment segment) {
        final int offset = segment.offset();
        final int length = segment.length();
        if (lob.isBinary()) {
            return Arrays.copyOfRange(entryData, offset, offset + length);
        }
        if (length == 0) {
            return "";
        }
        return textFactory.text(length, lob.getCcsid()).toObject(entryData, offset);
    }

    private static String columnName(TableInfo tableInfo, int index) {
        final List<Structure> structure = tableInfo.getStructure();
        return (index < structure.size()) ? structure.get(index).getName() : String.valueOf(index);
    }

    private void logLobsUnfetched(EntryHeader entryHeader) {
        if (firstTimeFor(lobsUnfetchedLogged, entryHeader)) {
            log.warn("lob data is not being fetched, the lob columns of {}.{} will be null - see the {} setting",
                    entryHeader.getLibrary(), entryHeader.getFile(), "lob.fetch");
        }
    }

    private static boolean firstTimeFor(Set<String> logged, EntryHeader entryHeader) {
        return logged.add(String.format("%s.%s", entryHeader.getLibrary(), entryHeader.getFile()));
    }

    public static String getDatabaseName(Connection con) throws SQLException {
        try (PreparedStatement st = con.prepareStatement(GET_DATABASE_NAME)) {
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return StringHelpers.safeTrim(rs.getString(1));
                }
                else {
                    return "";
                }
            }
        }
        // debezium doesn't close connections
    }

    public void clearCache(String systemTableName, String schema) {
        final String longTableName = getLongName(schema, systemTableName).orElse(systemTableName);
        schemaCache.clearCache(databaseName, schema, longTableName);
    }

    /**
     * @see io.debezium.jdbc.JdbcConnection.readTableColumn
     * @param table
     * @return
     * @throws Exception
     *
     *                   note that some tables (e.g. INDICIA2) are created and
     *                   deleted during workflow so may not exist
     */
    public Optional<TableInfo> getRecordFormat(String systemTableName, String schema) {
        final String longTableName = getLongName(schema, systemTableName).orElse(systemTableName);

        try {
            TableInfo tableInfo = schemaCache.retrieve(databaseName, schema, longTableName);
            if (tableInfo != null) {
                return Optional.of(tableInfo);
            }

            log.info("missed cache fetching structure for {} {}", schema, systemTableName);

            final String databaseCatalog = null;
            final List<AS400DataType> as400structure = new ArrayList<>();
            final List<Structure> jdbcStructure = new ArrayList<>();
            int recordOffset = 0;

            final Connection con = jdbcConnect.connection();
            final DatabaseMetaData metadata = con.getMetaData();
            try (ResultSet columnMetadata = metadata.getColumns(databaseCatalog, schema, longTableName, null)) {
                while (columnMetadata.next()) {
                    // @see
                    // https://docs.oracle.com/en/java/javase/11/docs/api/java.sql/java/sql/DatabaseMetaData.html#getColumns(java.lang.String,java.lang.String,java.lang.String,java.lang.String)
                    final String name = columnMetadata.getString(4);
                    final String type = columnMetadata.getString(6);
                    final int precision = columnMetadata.getInt(9);
                    final int length = columnMetadata.getInt(7);
                    final int jdcbType = columnMetadata.getInt(5);

                    final boolean optional = isNullable(columnMetadata.getInt(11));
                    final int octectLength = columnMetadata.getInt(16);
                    final int position = columnMetadata.getInt(17);
                    final boolean autoInc = "YES".equalsIgnoreCase(columnMetadata.getString(23));

                    jdbcStructure
                            .add(new Structure(name, type, jdcbType, length, precision, optional,
                                    position, autoInc));
                    // the columns are read in record order, so their widths so far are the offset of the
                    // next one - which a lob column needs to know to work out its own width
                    final AS400DataType dataType = toDataType(schema, longTableName, name, type, length, precision,
                            recordOffset);
                    recordOffset += dataType.getByteLength();

                    as400structure.add(dataType);
                    octetLengthCache.add(schema, longTableName, name, length, octectLength);
                }
                final AS400Structure entryDetailStructure = new AS400Structure(
                        as400structure.toArray(new AS400DataType[as400structure.size()]));

                List<String> primaryKeys = primaryKeysFromMeta(longTableName, schema, databaseCatalog, metadata);

                if (primaryKeys.isEmpty()) {
                    primaryKeys = ddsPrimaryKeys(systemTableName, schema);
                }

                tableInfo = new TableInfo(jdbcStructure, primaryKeys, entryDetailStructure);
                schemaCache.store(databaseName, schema, longTableName, tableInfo);

                return Optional.of(tableInfo);
            }
        }
        catch (final Exception e) {
            log.error("Failed to retrieve table info for {} {}", schema, longTableName, e);
        }
        log.warn("No table structure found for {}", systemTableName);

        return Optional.empty();
    }

    private List<String> ddsPrimaryKeys(String table, String schema) throws SQLException {
        final List<String> primaryKeys = new ArrayList<>();
        final Connection con = jdbcConnect.connection();

        try (PreparedStatement ps = con.prepareStatement(UNIQUE_KEYS)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    final String columnName = StringHelpers.safeTrim(rs.getString(1));
                    primaryKeys.add(columnName);
                }
            }
        }
        return primaryKeys;
    }

    private List<String> primaryKeysFromMeta(String table, String schema, String databaseCatalog,
                                             DatabaseMetaData metadata)
            throws SQLException {
        final List<String> primaryKeys = new ArrayList<>();
        try (ResultSet rs = metadata.getPrimaryKeys(databaseCatalog, schema, table)) {
            while (rs.next()) {
                final String columnName = StringHelpers.safeTrim(rs.getString(4));
                primaryKeys.add(columnName);
            }
        }
        return primaryKeys;
    }

    // from debezium
    static boolean isNullable(int jdbcNullable) {
        return jdbcNullable == ResultSetMetaData.columnNullable
                || jdbcNullable == ResultSetMetaData.columnNullableUnknown;
    }

    private static final String GET_TABLE_NAME = "select table_name from qsys2.systables where table_schema=? AND system_table_name=?";
    private final Map<String, Optional<String>> systemToLongName = new HashMap<>();

    public Optional<String> getLongName(String schemaName, String systemName) {
        if (systemToLongName.containsKey(systemName)) {
            return systemToLongName.get(systemName);
        }
        else {
            try {
                final Connection con = jdbcConnect.connection();
                try (PreparedStatement ps = con.prepareStatement(GET_TABLE_NAME)) {
                    ps.setString(1, schemaName);
                    ps.setString(2, systemName);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            final Optional<String> longTableName = Optional.of(StringHelpers.safeTrim(rs.getString(1)));
                            systemToLongName.put(systemName, longTableName);
                            return longTableName;
                        }
                    }
                }
            }
            catch (final Exception e) {
                log.error("failed looking up long table name", e);
            }
        }
        log.warn("No long table name found for {}", systemName);
        systemToLongName.put(systemName, Optional.<String> empty());
        return Optional.<String> empty();
    }

    static final Pattern BIT_DATA = Pattern.compile("CHAR \\(([(0-9]*)\\) FOR BIT DATA");
    static final Pattern VAR_BIT_DATA = Pattern.compile("VARCHAR \\(([(0-9]*)\\) FOR BIT DATA");

    /**
     * The column's own CCSID from {@code qsys2.syscolumns} wins; where the catalogue has none the
     * remote system CCSID is used, so a connector running under a different locale from the IBM i
     * still decodes the text the same way.
     */
    AS400Text getText(int length, Integer ccsid) {
        return textFactory.text(length, ccsidOrUnknown(ccsid));
    }

    AS400VarChar getVarText(int length, int bytesPerChar, Integer ccsid) {
        return textFactory.varChar(length, bytesPerChar, ccsidOrUnknown(ccsid));
    }

    private static int ccsidOrUnknown(Integer ccsid) {
        // a table we can't see in syscolumns leaves the lookup with no entry at all
        return (ccsid == null) ? As400TextFactory.UNKNOWN_CCSID : ccsid;
    }

    public AS400DataType toDataType(String schema, String table, String columnName, String type, int length,
                                    Integer precision) {
        return toDataType(schema, table, columnName, type, length, precision, 0);
    }

    /**
     * @param recordOffset the widths of the columns in front of this one, needed only by LOB columns:
     *                     their own width depends on where they start, see {@link AS400Lob}
     */
    public AS400DataType toDataType(String schema, String table, String columnName, String type, int length,
                                    Integer precision, int recordOffset) {
        switch (type) {
            case "BOOLEAN":
                return as400Boolean;
            case "DECIMAL":
                return new AS400PackedDecimal(length, precision);
            case "CHAR () FOR BIT DATA": // password fields - treat as binary
                return new AS400ByteArray(length);
            case "VARCHAR () FOR BIT DATA": // password fields - treat as binary
                return new AS400VarBin(length);
            case "CHAR":
                return getText(length, ccsidCache.getCcsid(schema, table, columnName));
            case "NCHAR":
                return getText(length * octetLengthCache.getBytesPerChar(schema, table, columnName),
                        ccsidCache.getCcsid(schema, table, columnName));
            case "NVARCHAR":
                return getVarText(length, octetLengthCache.getBytesPerChar(schema, table, columnName),
                        ccsidCache.getCcsid(schema, table, columnName));
            case "VGRAPH":
                return getVarText(length, octetLengthCache.getBytesPerChar(schema, table, columnName),
                        ccsidCache.getCcsid(schema, table, columnName));
            case "TIMESTAMP":
                return as400Timestamp;
            case "VARCHAR":
                return getVarText(length, octetLengthCache.getBytesPerChar(schema, table, columnName),
                        ccsidCache.getCcsid(schema, table, columnName));
            case "NUMERIC":
                return new AS400ZonedDecimal(length, precision);
            case "DATE":
                return dateTimeFormatCache.getDate(schema, table, columnName)
                        .map(AS400DataType.class::cast)
                        .orElseGet(() -> {
                            log.warn("No DATFMT found for DATE column {}.{}.{}, defaulting to *ISO", schema, table,
                                    columnName);
                            return new AS400Date();
                        });
            case "TIME":
                return dateTimeFormatCache.getTime(schema, table, columnName)
                        .map(AS400DataType.class::cast)
                        .orElseGet(() -> {
                            log.warn("No TIMFMT found for TIME column {}.{}.{}, defaulting to *ISO", schema, table,
                                    columnName);
                            return new AS400Time();
                        });
            case "REAL":
                return as400Float4;
            case "DOUBLE":
                return as400Float8;
            case "SMALLINT":
                return as400Bin2;
            case "INTEGER":
                return as400Bin4;
            case "BIGINT":
                return as400Bin8;
            case "BINARY":
                return new AS400ByteArray(length);
            case "VARBINARY":
                return new AS400VarBin(length);
            case "CLOB":
            case "DBCLOB":
                // a dbclob whose ccsid is a Unicode one is reported as an nclob
            case "NCLOB":
            case "BLOB":
            case "XML":
                // the record image holds a descriptor, never the data itself - see AS400Lob
                return new AS400Lob(type, ccsidOrUnknown(ccsidCache.getCcsid(schema, table, columnName)),
                        octetLengthCache.getBytesPerChar(schema, table, columnName), recordOffset);
            default:
                final Optional<Integer> varLength = bitDataLengthFromRegex(type, length, VAR_BIT_DATA);
                if (varLength.isPresent()) {
                    return new AS400VarBin(varLength.get());
                }
                final Optional<Integer> fixedLenght = bitDataLengthFromRegex(type, length, BIT_DATA);
                if (fixedLenght.isPresent()) {
                    return new AS400ByteArray(fixedLenght.get());
                }
        }
        throw new IllegalArgumentException(String.format("Unsupported type %s for column %s", type, columnName));
    }

    private Optional<Integer> bitDataLengthFromRegex(String type, int length, Pattern regex) {
        final Matcher matcher = regex.matcher(type); // - treat as binary
        if (matcher.matches()) {
            final String size = matcher.group(1);
            if (size.isEmpty()) {
                return Optional.of(length);
            }
            else {
                final int l = Integer.parseInt(size);
                return Optional.of(l);
            }
        }
        return Optional.empty();
    }
}
