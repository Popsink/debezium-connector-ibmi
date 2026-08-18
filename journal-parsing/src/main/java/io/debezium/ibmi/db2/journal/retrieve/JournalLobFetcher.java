/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.debezium.ibmi.db2.journal.retrieve.rjne0200.EntryHeader;

/**
 * Fetches the LOB data of a single journal entry, which
 * {@code QjoRetrieveJournalEntries} only hands back as a job local pointer.
 *
 * <p>
 * {@code QSYS2.DISPLAY_JOURNAL} reads the same journal entries but runs on the IBM i, in the job that
 * owns the pointers, so it can follow them: "when retrieving journal entries that are very large,
 * QjoRetrieveJournalEntries API returns pointers to the data, and the table function handles these
 * pointers and returns the actual data in the Entry_Data BLOB column". The LOB data is appended after
 * the record image, one segment per LOB column in column order, each preceded by a run of {@code 'Q'}
 * bytes as a separator.
 * </p>
 *
 * <p>
 * That makes the value the one that was journaled - correct for before images and for rows since
 * deleted - rather than whatever the row holds now.
 * </p>
 *
 * <p>
 * Entries are read a run at a time into a window rather than one query each, and the entry data is
 * cast to a non-LOB type on the way out - see {@link #WINDOW} for why both matter far more than they
 * look.
 * </p>
 *
 * @see <a href="https://www.ibm.com/docs/en/i/7.5?topic=services-display-journal-table-function">DISPLAY_JOURNAL
 *      table function</a>
 */
public class JournalLobFetcher {
    private static final Logger log = LoggerFactory.getLogger(JournalLobFetcher.class);

    /**
     * One entry, fetched as a whole {@code BLOB}. Only used for entries too large for {@link #WINDOW},
     * where the bytes dominate anyway so the per-query overhead is proportionally small.
     */
    private static final String ONE_ENTRY = """
            SELECT ENTRY_DATA, JOURNAL_ENTRY_TYPE, COUNT_OR_RRN FROM TABLE(QSYS2.DISPLAY_JOURNAL(
                CAST(? AS VARCHAR(10)), CAST(? AS VARCHAR(10)),%s
                STARTING_SEQUENCE => CAST(? AS DECIMAL(21,0)),
                ENDING_SEQUENCE => CAST(? AS DECIMAL(21,0)),
                JOURNAL_CODES => 'R',
                OBJECT_LIBRARY => CAST(? AS VARCHAR(10)),
                OBJECT_NAME => CAST(? AS VARCHAR(10)),
                OBJECT_OBJTYPE => '*FILE',
                OBJECT_MEMBER => CAST(? AS VARCHAR(10))))
            FETCH FIRST ROW ONLY
            """;

    /**
     * A run of entries for one table, read in a single query.
     *
     * <p>
     * Two things make this far cheaper than a query an entry. It covers a whole buffer's worth at once
     * rather than paying a round trip each. And it casts {@code ENTRY_DATA} to a non-LOB type: selected
     * as its declared {@code BLOB(2G)} the driver pays a LOB locator round trip per value regardless of
     * how small the value is - 62 ms a row for payloads of a hundred bytes, and no JDBC property
     * changes it - where the cast returns the bytes inline at 5.9 ms a row.
     * </p>
     *
     * <p>
     * The cast truncates silently, and asking {@code LENGTH(ENTRY_DATA)} alongside it to detect that
     * costs almost as much as fetching the LOB in the first place - measured at 22 ms a row against 6
     * without. It is not needed: a value that came back shorter than the limit cannot have been
     * truncated, so only one that fills the limit exactly is re-read whole by {@link #ONE_ENTRY}. That
     * re-reads the occasional entry that happens to be exactly the limit, which costs a round trip and
     * is still correct.
     * </p>
     */
    private static final String WINDOW = """
            SELECT SEQUENCE_NUMBER, JOURNAL_ENTRY_TYPE, COUNT_OR_RRN,
                   CAST(ENTRY_DATA AS VARBINARY(%d))
                FROM TABLE(QSYS2.DISPLAY_JOURNAL(
                    CAST(? AS VARCHAR(10)), CAST(? AS VARCHAR(10)),%s
                    STARTING_SEQUENCE => CAST(? AS DECIMAL(21,0)),
                    JOURNAL_CODES => 'R',
                    OBJECT_LIBRARY => CAST(? AS VARCHAR(10)),
                    OBJECT_NAME => CAST(? AS VARCHAR(10)),
                    OBJECT_OBJTYPE => '*FILE',
                    OBJECT_MEMBER => CAST(? AS VARCHAR(10))))
            FETCH FIRST %d ROWS ONLY
            """;

    /**
     * Only the first entry read from a receiver carries its name, so the range is narrowed to that
     * receiver when it is known and otherwise left to the default, the chain the journal is attached
     * to - which the entry belongs to either way as long as it is one we have just read.
     */
    private static final String STARTING_RECEIVER = """

                STARTING_RECEIVER_NAME => CAST(? AS VARCHAR(10)),
                STARTING_RECEIVER_LIBRARY => CAST(? AS VARCHAR(10)),\
            """;

    /** {@code VARBINARY} tops out at 32740 on DB2 for i; leave room rather than sit on the boundary. */
    private static final int CAST_LIMIT = 32000;

    /**
     * Entries read per window, with {@link #WINDOW_BYTE_BUDGET} capping how much they may carry. Both
     * matter on catch-up from a stale offset, where the range behind us is enormous.
     */
    private static final int WINDOW_ROWS = 512;

    /** Entries held across all windows before the unconsumed tails of earlier ones are dropped. */
    private static final int MAX_CACHED_ENTRIES = WINDOW_ROWS * 4;

    /**
     * How much lob data one fill will take in before it stops early and leaves the rest for the next.
     * The row cap alone is not a memory bound: a full window of entries each holding up to
     * {@link #CAST_LIMIT} bytes is tens of megabytes, and several tables can have one at once. Stopping
     * short costs nothing - the entries left behind are simply fetched by the fill that needs them.
     */
    private static final long WINDOW_BYTE_BUDGET = 4L * 1024 * 1024;

    private static final String ONE_ENTRY_WITH_RECEIVER = String.format(ONE_ENTRY, STARTING_RECEIVER);
    private static final String ONE_ENTRY_PLAIN = String.format(ONE_ENTRY, "");
    private static final String WINDOW_WITH_RECEIVER = String.format(WINDOW, CAST_LIMIT, STARTING_RECEIVER, WINDOW_ROWS);
    private static final String WINDOW_PLAIN = String.format(WINDOW, CAST_LIMIT, "", WINDOW_ROWS);

    /** Selecting an object without naming a member is rejected with SQL0443 MEMBER NOT VALID. */
    private static final String ALL_MEMBERS = "*ALL";

    /**
     * The separator DISPLAY_JOURNAL puts between the record image and each LOB segment, 16 {@code 'Q'}
     * characters. The entry data is a BLOB of untranslated bytes, so match the character in EBCDIC as
     * well as in ASCII rather than assume which side built the buffer.
     */
    private static final int SEPARATOR_LENGTH = 16;
    /** A run this long is taken as a separator; any {@code 'Q'} beyond it belongs to the data. */
    private static final int MIN_SEPARATOR_LENGTH = 15;
    private static final byte EBCDIC_Q = (byte) 0xD8;
    private static final byte ASCII_Q = (byte) 0x51;

    private final Connect<Connection, SQLException> jdbcConnect;
    private final JournalInfo journalInfo;

    /**
     * Statements kept prepared, and the connection they belong to. Preparing costs a round trip, and
     * they are only valid on the connection they were prepared against, so a connection that has been
     * replaced - a reconnect - discards them rather than running them against the wrong one.
     *
     * <p>
     * Unguarded, like the window below: entries are decoded on the streaming thread and nowhere else.
     * </p>
     */
    private final Map<String, PreparedStatement> statements = new HashMap<>();
    private Connection preparedOn;

    /**
     * The entries the last window query brought back, by sequence number. {@code data} is null for an
     * entry the cast truncated, which is a marker to re-read that one whole rather than a cache miss.
     */
    private record Cached(String entryType, BigDecimal rrn, byte[] data) {
    }

    /**
     * Cached entries keyed by table as well as sequence, so that two lob tables changing in the same
     * buffer keep a window each. Keying by sequence alone meant every switch between them discarded the
     * other's window and refetched it, which costs far more than the per-entry query it replaces.
     */
    private record Key(String table, BigInteger sequence) {
    }

    private final Map<Key, Cached> window = new HashMap<>();
    /**
     * The receiver the windows were filled from, written only by {@link #invalidateOnReceiverChange} -
     * which is the same place the window is cleared, so the two can never drift apart.
     */
    private String windowReceiver = "";

    public JournalLobFetcher(Connect<Connection, SQLException> jdbcConnect, JournalInfo journalInfo) {
        this.jdbcConnect = jdbcConnect;
        this.journalInfo = journalInfo;
    }

    /**
     * The entry specific data of one journal entry, with its LOB pointers resolved.
     *
     * <p>
     * Served from the window the last query filled where possible. A miss fills a fresh window starting
     * at this entry, so the first LOB entry of a buffer pays for the whole run behind it and the rest
     * are free.
     * </p>
     *
     * @return the complete entry specific data, or empty when the entry could not be re-read
     */
    public Optional<byte[]> entryData(EntryHeader entryHeader) {
        // before the lookup, not after: a stale entry of the old chain sitting at this sequence would
        // otherwise be a hit and be served
        invalidateOnReceiverChange(entryHeader);
        Cached cached = cachedFor(entryHeader);
        if (cached == null) {
            if (!fillWindow(entryHeader)) {
                // the fill just failed on this connection; asking it again for the same entry would
                // only fail again, once per entry for the rest of the buffer
                return Optional.empty();
            }
            cached = cachedFor(entryHeader);
        }
        if (cached == null) {
            // the window genuinely does not hold it - read the one entry
            return oneEntry(entryHeader);
        }
        if (!isSameEntry(entryHeader, cached.entryType(), cached.rrn())) {
            return Optional.empty();
        }
        // truncated by the cast, so the whole value has to come back as a blob
        return cached.data() != null ? Optional.of(cached.data()) : oneEntry(entryHeader);
    }

    /**
     * Drops the window when the journal says we have moved to another receiver.
     *
     * <p>
     * A sequence number only identifies an entry within one receiver chain, and a chain whose numbers
     * were reset holds them again from the start - so a cached entry from the old chain would otherwise
     * be served to an entry of the new one sitting at the same sequence. The entry type and record
     * number cannot tell those apart, because both legitimately match.
     * </p>
     *
     * <p>
     * Only the first entry read from a receiver carries its name; the rest are blank precisely because
     * they belong to the same chain as the last named one. So a name that differs from the one the
     * window was filled against is exactly the signal that it has gone stale.
     * </p>
     */
    private void invalidateOnReceiverChange(EntryHeader entryHeader) {
        final String receiver = entryHeader.getReceiver();
        if (!receiver.isEmpty() && !receiver.equals(windowReceiver)) {
            window.clear();
            windowReceiver = receiver;
        }
    }

    /**
     * Takes the entry out of the window as it hands it over. Every entry is decoded once and never
     * asked for again, so keeping it would hold its lob data - up to {@link #WINDOW_ROWS} entries of
     * it - reachable for the rest of the run. A re-read simply refills.
     */
    private Cached cachedFor(EntryHeader entryHeader) {
        return window.remove(new Key(tableOf(entryHeader), entryHeader.getSequenceNumber()));
    }

    private static String tableOf(EntryHeader entryHeader) {
        return entryHeader.getLibrary() + "." + entryHeader.getFile();
    }

    /**
     * Reads a run of this table's entries from this one onwards into the window. Entries the cast
     * truncated are recorded with no data, so they are re-read individually rather than looked up again.
     *
     * @return whether the fill ran; false means the query failed and this connection is not worth
     *         asking again for this entry
     */
    private boolean fillWindow(EntryHeader entryHeader) {
        final boolean hasReceiver = entryHeader.hasReceiver();
        final String table = tableOf(entryHeader);
        // entries are consumed as they are served, so what is left is the tail of earlier windows; drop
        // the lot rather than let a journal with many lob tables accumulate them without bound
        if (window.size() > MAX_CACHED_ENTRIES) {
            window.clear();
        }
        try {
            final PreparedStatement ps = statement(jdbcConnect.connection(),
                    hasReceiver ? WINDOW_WITH_RECEIVER : WINDOW_PLAIN);
            int p = 1;
            ps.setString(p++, journalInfo.journalLibrary());
            ps.setString(p++, journalInfo.journalName());
            if (hasReceiver) {
                ps.setString(p++, entryHeader.getReceiver());
                ps.setString(p++, entryHeader.getReceiverLibrary());
            }
            ps.setBigDecimal(p++, new BigDecimal(entryHeader.getSequenceNumber()));
            ps.setString(p++, entryHeader.getLibrary());
            ps.setString(p++, entryHeader.getFile());
            final String member = entryHeader.getMember();
            ps.setString(p, member.isEmpty() ? ALL_MEMBERS : member);
            int truncated = 0;
            int fetched = 0;
            long bytes = 0;
            try (ResultSet rs = ps.executeQuery()) {
                while (bytes < WINDOW_BYTE_BUDGET && rs.next()) {
                    fetched++;
                    final BigInteger sequence = rs.getBigDecimal(1).toBigInteger();
                    final byte[] head = rs.getBytes(4);
                    // shorter than the limit means the cast had room to spare, so nothing was cut off
                    final boolean complete = head != null && head.length < CAST_LIMIT;
                    if (!complete) {
                        truncated++;
                    }
                    bytes += head == null ? 0 : head.length;
                    window.put(new Key(table, sequence), new Cached(StringHelpers.safeTrim(rs.getString(2)),
                            rs.getBigDecimal(3), complete ? head : null));
                }
            }
            log.debug("lob window for {} from sequence {}: {} entries, {} bytes, {} too large for the cast", table,
                    entryHeader.getSequenceNumber(), fetched, bytes, truncated);
            return true;
        }
        catch (final SQLException e) {
            // whatever went wrong may have left the statement unusable, so prepare it again next time
            discardStatements();
            window.clear();
            log.error("failed to fill the lob window for {}.{} from sequence {}", entryHeader.getLibrary(),
                    entryHeader.getFile(), entryHeader.getSequenceNumber(), e);
            return false;
        }
    }

    /** Reads one entry whole, for values the cast could not carry and for anything the window missed. */
    private Optional<byte[]> oneEntry(EntryHeader entryHeader) {
        final boolean hasReceiver = entryHeader.hasReceiver();
        final BigDecimal sequence = new BigDecimal(entryHeader.getSequenceNumber());
        try {
            final PreparedStatement ps = statement(jdbcConnect.connection(),
                    hasReceiver ? ONE_ENTRY_WITH_RECEIVER : ONE_ENTRY_PLAIN);
            int p = 1;
            ps.setString(p++, journalInfo.journalLibrary());
            ps.setString(p++, journalInfo.journalName());
            if (hasReceiver) {
                ps.setString(p++, entryHeader.getReceiver());
                ps.setString(p++, entryHeader.getReceiverLibrary());
            }
            ps.setBigDecimal(p++, sequence);
            ps.setBigDecimal(p++, sequence);
            ps.setString(p++, entryHeader.getLibrary());
            ps.setString(p++, entryHeader.getFile());
            final String member = entryHeader.getMember();
            ps.setString(p, member.isEmpty() ? ALL_MEMBERS : member);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return isSameEntry(entryHeader, StringHelpers.safeTrim(rs.getString(2)), rs.getBigDecimal(3))
                            ? Optional.ofNullable(rs.getBytes(1))
                            : Optional.empty();
                }
            }
            log.error("no journal entry found for sequence {} of {}.{}, lob data unavailable", sequence,
                    entryHeader.getLibrary(), entryHeader.getFile());
        }
        catch (final SQLException e) {
            discardStatements();
            log.error("failed to fetch lob data for sequence {} of {}.{}", sequence, entryHeader.getLibrary(),
                    entryHeader.getFile(), e);
        }
        return Optional.empty();
    }

    /** The prepared statement for this query, preparing it if this is the first caller to need it. */
    private PreparedStatement statement(Connection con, String sql) throws SQLException {
        if (con != preparedOn) {
            discardStatements();
            preparedOn = con;
        }
        PreparedStatement ps = statements.get(sql);
        if (ps == null) {
            ps = con.prepareStatement(sql);
            statements.put(sql, ps);
        }
        return ps;
    }

    /** Gives up every statement. Closing one already closed, or one on a closed connection, is not a problem. */
    private void discardStatements() {
        for (final PreparedStatement ps : statements.values()) {
            try {
                ps.close();
            }
            catch (final SQLException e) {
                log.debug("failed to close a lob fetching statement", e);
            }
        }
        statements.clear();
    }

    /**
     * Whether the entry that came back really is the one asked for. A sequence number is only unique
     * within a receiver chain, and a chain whose sequence numbers were reset holds it more than once -
     * where "the first occurrence of starting sequence number is used". Since most entry headers carry
     * no receiver name to narrow the range with, the entry's own type and relative record number are
     * checked rather than trusted: attaching another row's lob data to this one would be silent
     * corruption.
     */
    private static boolean isSameEntry(EntryHeader entryHeader, String type, BigDecimal rrn) {
        final boolean sameType = entryHeader.getEntryType().trim().equals(type);
        // the column is null for the journal codes that count rather than number a record
        final boolean sameRow = rrn == null
                || rrn.toBigInteger().equals(entryHeader.getRelativeRecordNumber());
        if (sameType && sameRow) {
            return true;
        }
        log.error("sequence {} of {}.{} came back as entry type {} record {}, not the {} record {} being decoded - "
                + "no lob data will be used for it. Sequence numbers repeat across a receiver chain that has been "
                + "reset; the entry could not be told apart because its header carried no receiver name",
                entryHeader.getSequenceNumber(), entryHeader.getLibrary(), entryHeader.getFile(), type, rrn,
                entryHeader.getEntryType(), entryHeader.getRelativeRecordNumber());
        return false;
    }

    /**
     * Where one LOB column's data sits within the entry specific data it was appended to. A LOB runs to
     * megabytes, so the segments point into the entry rather than copy out of it: a copy per column
     * would hold every LOB of the entry twice while the record is decoded.
     */
    public record LobSegment(int offset, int length) {
    }

    /**
     * Locates the LOB segments appended after the record image.
     *
     * @param entryData    entry specific data as returned by DISPLAY_JOURNAL
     * @param recordLength length of the record image, where the appended segments start
     * @param byteLengths  length in bytes of each LOB column's data, in column order, as read from the
     *                     descriptors in the record image
     * @return one segment of {@code entryData} per requested length; empty when the data does not hold
     *         what was asked for, since a partial split would silently attribute one column's data to
     *         another
     */
    public static List<LobSegment> splitLobs(byte[] entryData, int recordLength, int[] byteLengths) {
        // logs at debug only: the caller knows which table this is and reports a failure once for it
        // the only way the offset can start outside the data: from here on it only moves forward, by a
        // separator run that is never negative and by a length the loop checks, so one guard covers it
        if (recordLength < 0) {
            // detail only: a split that fails does so for every entry of the table, so the caller
            // reports it once rather than letting it repeat per entry
            log.debug("the record image cannot be {} bytes long, no lob data will be split out of the "
                    + "{} byte journal entry", recordLength, entryData.length);
            return Collections.emptyList();
        }
        final List<LobSegment> segments = new ArrayList<>(byteLengths.length);
        int offset = recordLength;
        for (int i = 0; i < byteLengths.length; i++) {
            offset += separatorLengthAt(entryData, offset);
            final int length = byteLengths[i];
            // the remaining room is worked out by subtraction: a misread length added to the offset can
            // wrap round into a negative and slip past the check, leaving the segment to run off the end
            final int remaining = (offset > entryData.length) ? -1 : entryData.length - offset;
            if (length < 0 || length > remaining) {
                log.debug("lob segment {} of {} needs {} bytes at offset {} but the journal entry data is only {} bytes",
                        i + 1, byteLengths.length, length, offset, entryData.length);
                return Collections.emptyList();
            }
            segments.add(new LobSegment(offset, length));
            offset += length;
        }
        if (offset != entryData.length) {
            // detail only, like the failures above: the record length warning the caller raises once for
            // the table is the same condition seen from where the table is known
            log.debug("{} bytes of journal entry data left after the {} lob segment(s), the record image is either "
                    + "not {} bytes or the lob lengths were misread", entryData.length - offset, byteLengths.length,
                    recordLength);
        }
        return segments;
    }

    /** Length of the separator run at {@code offset}, 0 when the data does not start with one. */
    private static int separatorLengthAt(byte[] data, int offset) {
        int run = 0;
        while (offset + run < data.length && isSeparator(data[offset + run])) {
            run++;
        }
        if (run < MIN_SEPARATOR_LENGTH) {
            return 0;
        }
        return Math.min(run, SEPARATOR_LENGTH);
    }

    private static boolean isSeparator(byte b) {
        return b == EBCDIC_Q || b == ASCII_Q;
    }
}
