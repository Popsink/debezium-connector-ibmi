/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400Bin2;
import com.ibm.as400.access.AS400DataType;
import com.ibm.as400.access.AS400PackedDecimal;
import com.ibm.as400.access.AS400Structure;
import com.ibm.as400.access.AS400Text;
import com.ibm.as400.access.AS400ZonedDecimal;

import io.debezium.ibmi.db2.journal.data.types.AS400Lob;
import io.debezium.ibmi.db2.journal.data.types.AS400VarChar;
import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.retrieve.JournalLobFetcher.LobSegment;

public class JdbcFileDecoderTest {

    /** Pinned so the tests decode the same way whatever locale they run under. */
    private static final int EBCDIC_37 = 37;
    private static final As400TextFactory TEXT_FACTORY = As400TextFactory.forCcsid(EBCDIC_37);

    @Test
    public void testToDataType() throws Exception {
        final JdbcFileDecoder decoder = new JdbcFileDecoder(null, null, new SchemaCacheHash(), TEXT_FACTORY, -1, -1);
        final AS400DataType passwordNoLength = decoder.toDataType("schem", "table", "password", "CHAR () FOR BIT DATA",
                10, 0);
        assertEquals(AS400DataType.TYPE_BYTE_ARRAY, passwordNoLength.getInstanceType());
        final AS400DataType passwordLength = decoder.toDataType("schem", "table", "password", "CHAR (20) FOR BIT DATA",
                10, 0);
        assertEquals(AS400DataType.TYPE_BYTE_ARRAY, passwordLength.getInstanceType());
        assertEquals(20, passwordLength.getByteLength());
        final AS400DataType varPasswordNoLength = decoder.toDataType("schem", "table", "password",
                "VARCHAR () FOR BIT DATA", 10, 0);
        assertEquals(-1, varPasswordNoLength.getInstanceType());
        final AS400DataType varPasswordLength = decoder.toDataType("schem", "table", "password",
                "VARCHAR (20) FOR BIT DATA", 10, 0);
        assertEquals(-1, varPasswordLength.getInstanceType());
        assertEquals(20, passwordLength.getByteLength());
    }

    private static final JdbcFileDecoder DECODER = new JdbcFileDecoder(null, null, new SchemaCacheHash(), TEXT_FACTORY, -1, -1);

    // [CHAR(3), DECIMAL(5,0), CHAR(2)] -> 3 + 3 + 2 = 8 bytes
    private static AS400Structure threeFieldStructure() {
        return new AS400Structure(new AS400DataType[]{ new AS400Text(3), new AS400PackedDecimal(5, 0), new AS400Text(2) });
    }

    /** Blank-fills [from, to) with EBCDIC blanks (0x40), the way a null numeric column appears in a journal image. */
    private static byte[] blankFill(byte[] data, int from, int to) {
        Arrays.fill(data, from, to, (byte) 0x40);
        return data;
    }

    @Test
    public void testDecodeEntryDecodesAllFieldsWhenNoneNull() {
        final AS400Structure structure = threeFieldStructure();
        final byte[] data = structure.toBytes(new Object[]{ "ABC", new BigDecimal(123), "ZZ" });

        final Object[] result = DECODER.decodeEntry(structure, data, 0, null);

        assertEquals(3, result.length);
        assertEquals("ABC", result[0]);
        assertEquals(0, new BigDecimal(123).compareTo((BigDecimal) result[1]));
        assertEquals("ZZ", result[2]);
    }

    /**
     * The non-null path must stay byte-for-byte identical to the driver's own
     * {@link AS400Structure#toObject(byte[], int)}: same fields, same offset progression.
     */
    @Test
    public void testDecodeEntryMatchesDriverWhenNoNulls() {
        final AS400Structure structure = threeFieldStructure();
        final byte[] data = structure.toBytes(new Object[]{ "XYZ", new BigDecimal(98765), "qq" });

        final Object[] expected = (Object[]) structure.toObject(data, 0);
        final Object[] actual = DECODER.decodeEntry(structure, data, 0, null);

        assertArrayEquals(expected, actual);
    }

    /**
     * Variable-length types ({@link AS400VarChar}) occupy a fixed slot (max width + 2-byte length
     * prefix) but only decode {@code actualLength} bytes. The driver advances the offset by the
     * fixed {@code getByteLength()}, not the actual data length — decodeEntry must do exactly the
     * same, or every field after a varchar would be misaligned. Proven by equivalence.
     */
    @Test
    public void testDecodeEntryMatchesDriverForVariableLengthTypes() {
        // [VARCHAR(max 5), CHAR(2)] -> (5 + 2) + 2 = 9 bytes
        final AS400Structure structure = new AS400Structure(
                new AS400DataType[]{ new AS400VarChar(5, 1, EBCDIC_37), new AS400Text(2) });
        final byte[] data = new byte[structure.getByteLength()];
        new AS400Bin2().toBytes((short) 3, data, 0); // varchar actual length = 3 (< max 5)

        final Object[] expected = (Object[]) structure.toObject(data, 0);
        final Object[] actual = DECODER.decodeEntry(structure, data, 0, null);

        assertArrayEquals(expected, actual);
    }

    /**
     * Guards against a vacuous regression test: the blank-filled DECIMAL slot must genuinely make
     * the driver throw, so that skipping the null field is what prevents the failure.
     */
    @Test
    public void testRawDriverThrowsOnBlankFilledDecimal() {
        final AS400Structure structure = threeFieldStructure();
        final byte[] data = blankFill(structure.toBytes(new Object[]{ "ABC", new BigDecimal(123), "ZZ" }), 3, 6);

        assertThrows(NumberFormatException.class, () -> structure.toObject(data, 0));
    }

    @Test
    public void testDecodeEntrySkipsNullPackedDecimalField() {
        final AS400Structure structure = threeFieldStructure();
        // Blank-fill the DECIMAL slot (bytes 3..5) as a null numeric column would appear.
        final byte[] data = blankFill(structure.toBytes(new Object[]{ "ABC", new BigDecimal(123), "ZZ" }), 3, 6);

        final Object[] result = DECODER.decodeEntry(structure, data, 0, new boolean[]{ false, true, false });

        assertEquals(3, result.length);
        assertEquals("ABC", result[0]);
        assertNull(result[1]);
        assertEquals("ZZ", result[2]);
    }

    /** Same problem class for zoned decimal (NUMERIC) — the fix is type-agnostic. */
    @Test
    public void testDecodeEntrySkipsNullZonedDecimalField() {
        // [NUMERIC(5,0), CHAR(2)] -> 5 + 2 = 7 bytes
        final AS400Structure structure = new AS400Structure(
                new AS400DataType[]{ new AS400ZonedDecimal(5, 0), new AS400Text(2) });
        final byte[] data = blankFill(structure.toBytes(new Object[]{ new BigDecimal(42), "ok" }), 0, 5);

        final Object[] result = DECODER.decodeEntry(structure, data, 0, new boolean[]{ true, false });

        assertNull(result[0]);
        assertEquals("ok", result[1]);
    }

    /**
     * Offset integrity: a null field at the first and last positions must not desync the offset of
     * the field between them, even when both neighbours hold invalid (blank) bytes.
     */
    @Test
    public void testDecodeEntryNullsAtBoundariesKeepMiddleAligned() {
        // [DECIMAL(5,0), CHAR(3), DECIMAL(5,0)] -> 3 + 3 + 3 = 9 bytes
        final AS400Structure structure = new AS400Structure(
                new AS400DataType[]{ new AS400PackedDecimal(5, 0), new AS400Text(3), new AS400PackedDecimal(5, 0) });
        byte[] data = structure.toBytes(new Object[]{ new BigDecimal(11), "MID", new BigDecimal(22) });
        data = blankFill(data, 0, 3); // first DECIMAL
        data = blankFill(data, 6, 9); // last DECIMAL

        final Object[] result = DECODER.decodeEntry(structure, data, 0, new boolean[]{ true, false, true });

        assertNull(result[0]);
        assertEquals("MID", result[1]);
        assertNull(result[2]);
    }

    /** Records sit at an offset inside the journal buffer — decoding must honour a non-zero start offset. */
    @Test
    public void testDecodeEntryHonoursStartOffset() {
        final AS400Structure structure = threeFieldStructure();
        final byte[] record = blankFill(structure.toBytes(new Object[]{ "ABC", new BigDecimal(123), "ZZ" }), 3, 6);
        final byte[] buffer = new byte[4 + record.length];
        System.arraycopy(record, 0, buffer, 4, record.length); // 4-byte prefix

        final Object[] result = DECODER.decodeEntry(structure, buffer, 4, new boolean[]{ false, true, false });

        assertEquals("ABC", result[0]);
        assertNull(result[1]);
        assertEquals("ZZ", result[2]);
    }

    @Test
    public void testDecodeEntryAllFieldsNull() {
        final AS400Structure structure = threeFieldStructure();
        final byte[] data = blankFill(new byte[structure.getByteLength()], 0, structure.getByteLength());

        final Object[] result = DECODER.decodeEntry(structure, data, 0, new boolean[]{ true, true, true });

        assertArrayEquals(new Object[]{ null, null, null }, result);
    }

    /** A shorter (or absent) indicator array must not break decoding: unflagged fields decode normally. */
    @Test
    public void testDecodeEntryToleratesShortIndicatorArray() {
        final AS400Structure structure = threeFieldStructure();
        final byte[] data = structure.toBytes(new Object[]{ "ABC", new BigDecimal(123), "ZZ" });

        final Object[] result = DECODER.decodeEntry(structure, data, 0, new boolean[]{ false }); // only covers field 0

        assertEquals("ABC", result[0]);
        assertEquals(0, new BigDecimal(123).compareTo((BigDecimal) result[1]));
        assertEquals("ZZ", result[2]);
    }

    /**
     * The gap #9's isNull skip left open, and the whole point of issue #52: a blank-filled numeric that the
     * journal does <em>not</em> flag null - a non-null-capable column that was never initialised, i.e. the
     * ordinary legacy pattern - used to reach jt400 and throw, which aborted the entry and dropped the whole
     * row. It must now cost that one column and nothing else.
     */
    @Test
    public void testDecodeEntryNullsABlankPackedDecimalThatIsNotFlaggedNull() {
        final AS400Structure structure = threeFieldStructure();
        final byte[] data = structure.toBytes(new Object[]{ "ABC", new BigDecimal(123), "ZZ" });
        blankFill(data, 3, 6); // the DECIMAL(5,0), untouched by the indicator array below

        final List<JdbcFileDecoder.UndecodableField> undecodable = new ArrayList<>();
        final Object[] result = DECODER.decodeEntry(structure, data, 0, new boolean[]{ false, false, false },
                new ArrayList<>(), undecodable);

        assertNull(result[1]);
        // and, the part that was being lost, every other column of the row is intact
        assertEquals("ABC", result[0]);
        assertEquals("ZZ", result[2]);
        assertEquals(1, undecodable.size());
        assertEquals(1, undecodable.get(0).index());
        // blank-filled, so it was never offered to the driver and there is nothing to attribute
        assertNull(undecodable.get(0).cause());
    }

    @Test
    public void testDecodeEntryNullsABlankZonedDecimalThatIsNotFlaggedNull() {
        // [CHAR(3), ZONED(5,0), CHAR(2)] -> 3 + 5 + 2 = 10 bytes
        final AS400Structure structure = new AS400Structure(
                new AS400DataType[]{ new AS400Text(3), new AS400ZonedDecimal(5, 0), new AS400Text(2) });
        final byte[] data = structure.toBytes(new Object[]{ "ABC", new BigDecimal(123), "ZZ" });
        blankFill(data, 3, 8);

        final List<JdbcFileDecoder.UndecodableField> undecodable = new ArrayList<>();
        final Object[] result = DECODER.decodeEntry(structure, data, 0, null, new ArrayList<>(), undecodable);

        assertNull(result[1]);
        assertEquals("ABC", result[0]);
        assertEquals("ZZ", result[2]);
        assertEquals(1, undecodable.size());
    }

    /**
     * The backstop for bytes that are not blank but that the driver still rejects - the NumberFormatException
     * on a bad nibble that issue #52 was filed on. Same outcome: one null column, not a dropped row.
     */
    @Test
    public void testDecodeEntryNullsAPackedDecimalTheDriverRejects() {
        final AS400Structure structure = threeFieldStructure();
        final byte[] data = structure.toBytes(new Object[]{ "ABC", new BigDecimal(123), "ZZ" });
        data[4] = 0x4A; // a non-blank, invalid nibble pair in the middle of the DECIMAL(5,0)

        final List<JdbcFileDecoder.UndecodableField> undecodable = new ArrayList<>();
        final Object[] result = DECODER.decodeEntry(structure, data, 0, null, new ArrayList<>(), undecodable);

        assertNull(result[1]);
        assertEquals("ABC", result[0]);
        assertEquals("ZZ", result[2]);
        assertEquals(1, undecodable.size());
        // this one did come from the driver, so it is attributable
        assertNotNull(undecodable.get(0).cause());
    }

    /**
     * A blank value is the legacy "no value", so it must not be confused with a real zero, which is what
     * silently defaulting the column would have produced.
     */
    @Test
    public void testDecodeEntryStillReadsARealZero() {
        final AS400Structure structure = threeFieldStructure();
        final byte[] data = structure.toBytes(new Object[]{ "ABC", BigDecimal.ZERO, "ZZ" });

        final List<JdbcFileDecoder.UndecodableField> undecodable = new ArrayList<>();
        final Object[] result = DECODER.decodeEntry(structure, data, 0, null, new ArrayList<>(), undecodable);

        assertEquals(0, BigDecimal.ZERO.compareTo((BigDecimal) result[1]));
        assertEquals(0, undecodable.size());
    }

    /** A blank CHAR column is a blank string, not a missing value: only decimals get this treatment. */
    @Test
    public void testDecodeEntryLeavesBlankTextAlone() {
        final AS400Structure structure = threeFieldStructure();
        final byte[] data = structure.toBytes(new Object[]{ "ABC", new BigDecimal(123), "ZZ" });
        blankFill(data, 0, 3);

        final List<JdbcFileDecoder.UndecodableField> undecodable = new ArrayList<>();
        final Object[] result = DECODER.decodeEntry(structure, data, 0, null, new ArrayList<>(), undecodable);

        assertEquals("   ", result[0]);
        assertEquals(0, undecodable.size());
    }

    /**
     * A record image that does not match the format is a different problem: tolerating it per field would
     * hand plausible garbage downstream, so it must still abort the entry, where the streaming loop logs it
     * with an offset and an RRN to attribute it (issue #31).
     */
    @Test
    public void testDecodeEntryStillFailsOnATruncatedRecord() {
        final AS400Structure structure = threeFieldStructure();
        final byte[] data = new byte[structure.getByteLength() - 3];

        assertThrows(RuntimeException.class,
                () -> DECODER.decodeEntry(structure, data, 0, null, new ArrayList<>(), new ArrayList<>()));
    }

    private static final int LOB_RECORD_OFFSET = 5;
    /** Padded by 14 bytes at record offset 5, so the column is 14 + 29 = 43 bytes wide. */
    private static final int LOB_BYTE_LENGTH = 43;

    /** [CHAR(5), CLOB, CHAR(2)] */
    private static AS400Structure lobStructure() {
        return new AS400Structure(new AS400DataType[]{ new AS400Text(5, EBCDIC_37),
                new AS400Lob("CLOB", EBCDIC_37, 1, LOB_RECORD_OFFSET), new AS400Text(2, EBCDIC_37) });
    }

    private static byte[] lobRecord(int dataLength) {
        final byte[] record = new byte[LOB_RECORD_OFFSET + LOB_BYTE_LENGTH + 2];
        new AS400Text(5, EBCDIC_37).toBytes("ABCDE", record, 0);
        final int padding = Math.floorMod(-(LOB_RECORD_OFFSET + 13), 16);
        new com.ibm.as400.access.AS400Bin4().toBytes(dataLength, record, LOB_RECORD_OFFSET + padding + 1);
        new AS400Text(2, EBCDIC_37).toBytes("ZZ", record, LOB_RECORD_OFFSET + LOB_BYTE_LENGTH);
        return record;
    }

    /**
     * The point of decoding the LOB descriptor at all: the columns on the other side of it are read at
     * the right offset, and its length is known without the data being in the record image.
     */
    @Test
    public void decodeEntryReadsTheColumnsAroundALobAndCollectsIt() {
        final AS400Structure structure = lobStructure();
        final List<JdbcFileDecoder.LobField> lobFields = new ArrayList<>();

        final Object[] result = DECODER.decodeEntry(structure, lobRecord(1234), 0, null, lobFields);

        assertEquals("ABCDE", result[0]);
        assertNull(result[1], "the record image never holds the lob data");
        assertEquals("ZZ", result[2]);
        assertEquals(1, lobFields.size());
        final JdbcFileDecoder.LobField lobField = lobFields.get(0);
        assertEquals(1, lobField.index());
        assertEquals(1234, lobField.dataLength());
    }

    @Test
    public void decodeEntryDoesNotReadTheDescriptorOfANullLob() {
        final List<JdbcFileDecoder.LobField> lobFields = new ArrayList<>();

        DECODER.decodeEntry(lobStructure(), lobRecord(1234), 0, new boolean[]{ false, true, false }, lobFields);

        assertEquals(1, lobFields.size());
        assertEquals(true, lobFields.get(0).isNull());
    }

    @Test
    public void recordLengthCountsTheWholeLobSlot() {
        assertEquals(5 + LOB_BYTE_LENGTH + 2, lobStructure().getByteLength());
    }

    private static int paddingAt(int recordOffset) {
        return Math.floorMod(-(recordOffset + 13), 16);
    }

    /** Writes a descriptor for a column starting at {@code recordOffset}, and returns its width. */
    private static int writeLobSlot(byte[] record, int recordOffset, int dataLength) {
        final int padding = paddingAt(recordOffset);
        new com.ibm.as400.access.AS400Bin4().toBytes(dataLength, record, recordOffset + padding + 1);
        return padding + AS400Lob.DESCRIPTOR_LENGTH;
    }

    /**
     * A lob column's width depends on where it starts, so the widths have to chain: the column in front
     * decides this one's padding, and this one's width decides the next column's offset. The awkward
     * shapes are a lob first (nothing in front of it), a lob last (nothing to catch a bad width), and
     * two lobs with no fixed width column between them.
     */
    @Test
    public void chainsWidthsThroughAdjacentLargeObjects() {
        // [CLOB, BLOB, CHAR(2)]: the first at 0 is padded by 3 and so 32 wide, putting the second at 32,
        // padded by 3 as well, so the trailing char lands at 64
        assertEquals(3, paddingAt(0));
        assertEquals(3, paddingAt(32));
        final AS400Structure structure = new AS400Structure(new AS400DataType[]{
                new AS400Lob("CLOB", EBCDIC_37, 1, 0), new AS400Lob("BLOB", 65535, 1, 32),
                new AS400Text(2, EBCDIC_37) });
        assertEquals(66, structure.getByteLength());

        final byte[] record = new byte[66];
        assertEquals(32, writeLobSlot(record, 0, 11));
        assertEquals(32, writeLobSlot(record, 32, 22));
        new AS400Text(2, EBCDIC_37).toBytes("ZZ", record, 64);

        final List<JdbcFileDecoder.LobField> lobFields = new ArrayList<>();
        final Object[] result = DECODER.decodeEntry(structure, record, 0, null, lobFields);

        assertEquals("ZZ", result[2], "the column after two lobs is misplaced");
        assertEquals(2, lobFields.size());
        assertEquals(11, lobFields.get(0).dataLength());
        assertEquals(22, lobFields.get(1).dataLength());
    }

    /** A lob at the end of the record still takes its full width, or the record length is short. */
    @Test
    public void countsALobAtTheEndOfTheRecord() {
        final AS400Structure structure = new AS400Structure(new AS400DataType[]{ new AS400Text(5, EBCDIC_37),
                new AS400Lob("XML", 1208, 1, 5) });

        assertEquals(5 + 43, structure.getByteLength());
    }

    /** A row whose lob columns are all null keeps them null and still reads the rest. */
    @Test
    public void decodeEntryLeavesEveryNullLargeObjectNull() {
        final AS400Structure structure = lobStructure();
        final List<JdbcFileDecoder.LobField> lobFields = new ArrayList<>();

        final Object[] result = DECODER.decodeEntry(structure, lobRecord(1234), 0,
                new boolean[]{ false, true, false }, lobFields);

        assertEquals("ABCDE", result[0]);
        assertNull(result[1]);
        assertEquals("ZZ", result[2]);
        assertEquals(1, lobFields.size());
        assertEquals(0, lobFields.get(0).appendedByteLength(), "a null column contributes no appended bytes");
    }

    /**
     * The CCSID of the column decides how a segment is read: EBCDIC for a CLOB on this system, UTF-8 for
     * an XML column, and no conversion at all for a blob.
     */
    @Test
    public void decodesEachLargeObjectSegmentByItsType() {
        final byte[] ebcdic = new AS400Text(3, EBCDIC_37).toBytes("abc");
        assertEquals("abc", DECODER.decodeLob(new AS400Lob("CLOB", EBCDIC_37, 1, 0), ebcdic, whole(ebcdic)));

        final byte[] utf8 = "<a/>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("<a/>", DECODER.decodeLob(new AS400Lob("XML", 1208, 1, 0), utf8, whole(utf8)));

        // bytes are handed over as they are - a blob holding EBCDIC 'Q' (0xD8) must not be touched
        final byte[] raw = new byte[]{ 0x00, (byte) 0xD8, (byte) 0xff };
        assertArrayEquals(raw, (byte[]) DECODER.decodeLob(new AS400Lob("BLOB", EBCDIC_37, 1, 0), raw, whole(raw)));
    }

    /**
     * A segment points into the whole entry data, so every type has to read from its offset rather than
     * assume the data starts at zero.
     */
    @Test
    public void decodesLargeObjectSegmentsFromTheirOffsetInTheEntry() {
        final byte[] entryData = new byte[16];
        System.arraycopy(new AS400Text(3, EBCDIC_37).toBytes("abc"), 0, entryData, 5, 3);

        assertEquals("abc", DECODER.decodeLob(new AS400Lob("CLOB", EBCDIC_37, 1, 0), entryData, new LobSegment(5, 3)));

        final byte[] blob = (byte[]) DECODER.decodeLob(new AS400Lob("BLOB", EBCDIC_37, 1, 0), entryData,
                new LobSegment(5, 3));
        assertArrayEquals(new byte[]{ (byte) 0x81, (byte) 0x82, (byte) 0x83 }, blob);
        // a blob is copied out: handing back a view would keep the whole entry, every lob in it included,
        // reachable for as long as the record is
        assertNotSame(entryData, blob);
    }

    private static LobSegment whole(byte[] data) {
        return new LobSegment(0, data.length);
    }

    @Test
    public void largeObjectColumnsDecodeAsLobDescriptors() throws Exception {
        // the catalogue lookups fail without a connection, leaving the CCSID unknown, which must not stop
        // the column being recognised
        final JdbcFileDecoder decoder = new JdbcFileDecoder(() -> {
            throw new java.sql.SQLException("no connection");
        }, null, new SchemaCacheHash(), TEXT_FACTORY, -1, -1);

        for (final String type : new String[]{ "CLOB", "DBCLOB", "NCLOB", "BLOB", "XML" }) {
            final AS400DataType dataType = decoder.toDataType("schem", "table", "c", type, 1024, 0, LOB_RECORD_OFFSET);
            assertEquals(LOB_BYTE_LENGTH, dataType.getByteLength(), type);
            assertEquals(type, ((AS400Lob) dataType).getTypeName());
        }
    }

    @Test
    public void dateAndTimeFallBackToIsoWhenFormatUnknown() throws Exception {
        // null connection -> the format cache lookup fails and is swallowed; DATE/TIME must default to *ISO
        // rather than throwing (preserves prior behaviour for ISO files and never NPEs the decoder).
        final JdbcFileDecoder decoder = new JdbcFileDecoder(null, null, new SchemaCacheHash(), TEXT_FACTORY, -1, -1);

        final AS400DataType date = decoder.toDataType("schem", "table", "d", "DATE", 10, 0);
        assertEquals(AS400DataType.TYPE_DATE, date.getInstanceType());
        assertEquals(com.ibm.as400.access.AS400Date.FORMAT_ISO, ((com.ibm.as400.access.AS400Date) date).getFormat());

        final AS400DataType time = decoder.toDataType("schem", "table", "t", "TIME", 8, 0);
        assertEquals(AS400DataType.TYPE_TIME, time.getInstanceType());
    }
}
