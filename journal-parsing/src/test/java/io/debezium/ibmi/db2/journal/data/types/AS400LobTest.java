/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.data.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400Bin4;

public class AS400LobTest {

    private static final int EBCDIC_37 = 37;

    private static AS400Lob clobAt(int recordOffset) {
        return new AS400Lob("CLOB", EBCDIC_37, 1, recordOffset);
    }

    /** The padding that puts the pointer of a column starting at {@code recordOffset} on a 16 byte boundary. */
    private static int paddingAt(int recordOffset) {
        return Math.floorMod(-(recordOffset + 13), 16);
    }

    /**
     * The record image slot of a LOB column: alignment padding, a byte of system information, the
     * 4 byte length, 8 reserved bytes, then the pointer - which is left as zeros, nothing reads it.
     */
    private static byte[] lobSlot(int recordOffset, int dataLength) {
        final int padding = paddingAt(recordOffset);
        final byte[] slot = new byte[padding + AS400Lob.DESCRIPTOR_LENGTH];
        new AS400Bin4().toBytes(dataLength, slot, padding + 1);
        return slot;
    }

    /** A record image with the LOB column preceded by {@code recordOffset} bytes of other columns. */
    private static byte[] record(int recordOffset, int dataLength) {
        final byte[] slot = lobSlot(recordOffset, dataLength);
        final byte[] record = new byte[recordOffset + slot.length];
        Arrays.fill(record, 0, recordOffset, (byte) 0xC1); // EBCDIC 'A', any non-zero column data
        System.arraycopy(slot, 0, record, recordOffset, slot.length);
        return record;
    }

    /**
     * The width is the padding plus the 29 byte descriptor, so it depends on where the column starts.
     * The live case: a CLOB at record offset 9 is padded by 10 and so is 39 bytes wide, its pointer
     * landing on 32.
     */
    @Test
    public void widthIsThePaddingPlusTheDescriptor() {
        assertEquals(39, clobAt(9).getByteLength());
        assertEquals(10, paddingAt(9));
        assertEquals(AS400Lob.DESCRIPTOR_LENGTH, clobAt(3).getByteLength(), "no padding needed at offset 3");
        for (int recordOffset = 0; recordOffset < 64; recordOffset++) {
            final int width = clobAt(recordOffset).getByteLength();
            assertEquals(0, (recordOffset + width - 16) % 16, "pointer not 16 byte aligned at " + recordOffset);
            assertTrue(width >= AS400Lob.DESCRIPTOR_LENGTH && width <= AS400Lob.MAX_BYTE_LENGTH);
        }
    }

    @Test
    public void noValueComesFromTheRecordImage() {
        assertNull(clobAt(5).toObject(record(5, 11), 5));
    }

    /**
     * The padding is whatever puts the pointer on a 16 byte boundary, so it changes with the width of
     * the columns in front of the LOB. Every offset within an alignment cycle has to be read.
     */
    @Test
    public void readsTheLengthAtEveryAlignment() {
        for (int recordOffset = 0; recordOffset < 16; recordOffset++) {
            final byte[] data = record(recordOffset, 1234);

            assertEquals(1234, clobAt(recordOffset).dataLength(data, recordOffset),
                    "at record offset " + recordOffset);
        }
    }

    /** The column need not start the buffer: the padding follows the record, not the buffer. */
    @Test
    public void readsTheDescriptorWhenTheRecordIsNotAtTheStartOfTheBuffer() {
        final int recordOffset = 5;
        final int bufferOffset = 40; // a journal entry's record image starts well into the buffer
        final byte[] record = record(recordOffset, 7);
        final byte[] data = new byte[bufferOffset + record.length];
        System.arraycopy(record, 0, data, bufferOffset, record.length);

        assertEquals(7, clobAt(recordOffset).dataLength(data, bufferOffset + recordOffset));
    }

    /**
     * A descriptor that is not padded the way the column offset says means the record format is out of
     * step with the entry. Reading a length from the wrong four bytes would mis-size the lob data, so
     * nothing is reported rather than something wrong.
     */
    @Test
    public void reportsADescriptorPaddedOtherThanItsOffsetImplies() {
        final int recordOffset = 5;
        // padded as though the column started 3 bytes earlier
        final byte[] slot = lobSlot(recordOffset - 3, 99);
        final byte[] data = new byte[recordOffset + AS400Lob.MAX_BYTE_LENGTH];
        Arrays.fill(data, 0, recordOffset, (byte) 0xC1);
        System.arraycopy(slot, 0, data, recordOffset, slot.length);

        assertEquals(AS400Lob.UNKNOWN_LENGTH, clobAt(recordOffset).dataLength(data, recordOffset));
    }

    /**
     * The documentation calls the byte in front of the length {@code '00'x}, but a column holding double
     * byte text carries {@code '01'x} there. Requiring it to be zero rejected perfectly good DBCLOB
     * descriptors, so it is read as data about the column rather than as reserved.
     */
    @Test
    public void readsADescriptorWhoseSystemInformationByteIsNotZero() {
        final int recordOffset = 112;
        final byte[] data = record(recordOffset, 11);
        data[recordOffset + paddingAt(recordOffset)] = 0x01;

        assertEquals(11, new AS400Lob("NCLOB", 13488, 2, recordOffset).dataLength(data, recordOffset));
    }

    @Test
    public void anEmptyLobReadsAsZeroRatherThanUnknown() {
        assertEquals(0, clobAt(5).dataLength(record(5, 0), 5));
    }

    @Test
    public void reportsAnUnreadableDescriptor() {
        final byte[] data = new byte[5 + AS400Lob.MAX_BYTE_LENGTH];
        Arrays.fill(data, (byte) 0xC1); // no zeros anywhere, so nothing describes a lob

        assertEquals(AS400Lob.UNKNOWN_LENGTH, clobAt(5).dataLength(data, 5));
    }

    @Test
    public void reportsADescriptorRunningPastTheBuffer() {
        assertEquals(AS400Lob.UNKNOWN_LENGTH, clobAt(0).dataLength(new byte[20], 0));
    }

    /** Blob data is bytes, so it is never put through a CCSID; the other types are all text. */
    @Test
    public void onlyABlobIsBinary() {
        assertTrue(new AS400Lob("BLOB", EBCDIC_37, 1, 0).isBinary());
        assertEquals(byte[].class, new AS400Lob("BLOB", EBCDIC_37, 1, 0).getJavaType());
        assertFalse(new AS400Lob("XML", 1208, 1, 0).isBinary());
        assertEquals(String.class, new AS400Lob("XML", 1208, 1, 0).getJavaType());
        assertFalse(clobAt(0).isBinary());
    }

    /** Every large object type is held as the same descriptor, so all of them are the same width. */
    @Test
    public void allLargeObjectTypesShareTheDescriptor() {
        for (final String typeName : new String[]{ "CLOB", "DBCLOB", "NCLOB", "BLOB", "XML" }) {
            assertEquals(39, new AS400Lob(typeName, EBCDIC_37, 1, 9).getByteLength(), typeName);
        }
    }

    /**
     * A descriptor length is in characters, so double byte text needs doubling to size the bytes
     * appended to the entry. A Unicode dbclob is reported as an nclob, and either way it is the width of
     * a character in the column's CCSID that decides, not the name of the type.
     */
    @Test
    public void sizesTextByTheWidthOfItsCharacters() {
        assertEquals(20, new AS400Lob("DBCLOB", 13488, 2, 0).byteLengthOf(10));
        assertEquals(20, new AS400Lob("NCLOB", 13488, 2, 0).byteLengthOf(10));
        assertEquals(10, clobAt(0).byteLengthOf(10), "single byte text needs no scaling");
        assertEquals(10, new AS400Lob("BLOB", 65535, 1, 0).byteLengthOf(10), "a blob length is bytes already");
        // the catalogue lookup reports -1 when it cannot read the column
        assertEquals(10, new AS400Lob("CLOB", 37, -1, 0).byteLengthOf(10));
    }
}
