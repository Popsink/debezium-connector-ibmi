/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.data.types;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400Bin4;
import com.ibm.as400.access.AS400DataType;
import com.ibm.as400.access.InternalErrorException;
import com.ibm.as400.access.Trace;

/**
 * The slot a large object column (CLOB, DBCLOB, BLOB or XML) occupies in a journal entry's record
 * image.
 *
 * <p>
 * The journal never returns the LOB data inline. The record image only carries a descriptor holding
 * the length of the data and a 16 byte pointer to it, the data itself living in the journal
 * receiver. That pointer is job local - "it cannot be passed on to another job, nor can it be
 * stored to use at a later date" - and can only be dereferenced by ILE code running in the job that
 * called {@code QjoRetrieveJournalEntries}, so it is of no use from here. It is decoded only so that
 * the length is available and so the columns that follow the LOB in the record are read at the right
 * offset; the value itself is fetched separately, see
 * {@link io.debezium.ibmi.db2.journal.retrieve.JournalLobFetcher}.
 * </p>
 *
 * <p>
 * Descriptor layout:
 * </p>
 *
 * <pre>
 *   0 to 15 bytes  x'00' padding, so the pointer lands on a 16 byte boundary
 *   1 byte         system information: x'00', or x'01' for double byte text
 *   4 bytes        length of the LOB data, in the unit the column's length is declared in
 *   8 bytes        x'00'
 *   16 bytes       pointer to the LOB data in the journal receiver
 * </pre>
 *
 * <p>
 * The column therefore occupies {@code padding + 29} bytes of the record image - between 29 and 44 -
 * and how many depends on where it starts, since a record image is a plain concatenation of its
 * columns with no alignment between them. The padding is worked out once, when the record format is
 * built and the offsets of the preceding columns are known.
 * </p>
 *
 * <p>
 * Confirmed against live journal entries for {@code (ID INT, HEAD CHAR(5), BODY CLOB(1M), MID CHAR(3),
 * RAW BLOB(1M), DOC XML, WIDE DBCLOB(1M) CCSID 13488, TAIL CHAR(2))}: a 146 byte record image, with the
 * four descriptors padded by 10, 0, 3 and 3 so that each pointer lands on a multiple of 16, and the
 * fixed width columns between them read where those widths put them.
 * </p>
 *
 * @see <a href="https://www.ibm.com/docs/en/i/7.5?topic=journaling-lob-considerations">LOB
 *      considerations for journaling</a>
 * @see <a href="https://www.ibm.com/docs/en/i/7.5?topic=entries-working-pointers-journal">Working
 *      with pointers in journal entries</a>
 */
public class AS400Lob implements AS400DataType {
    private static final Logger log = LoggerFactory.getLogger(AS400Lob.class);

    /** The descriptor itself: 1 byte of system information, the 4 byte length, 8 reserved, the pointer. */
    public static final int DESCRIPTOR_LENGTH = 29;
    /** Widest a LOB column can be, with the full 15 bytes of alignment padding in front of it. */
    public static final int MAX_BYTE_LENGTH = DESCRIPTOR_LENGTH + 15;
    /** Displacement of the pointer within the descriptor - the field the padding aligns. */
    private static final int POINTER_DISPLACEMENT = 13;
    private static final int ALIGNMENT = 16;
    /** Displacement of the 4 byte length, after the single byte of system information. */
    private static final int LENGTH_DISPLACEMENT = 1;
    private static final int RESERVED_DISPLACEMENT = 5;

    private static final AS400Bin4 LENGTH_DECODER = new AS400Bin4();

    /** Returned instead of a length when the descriptor is not where the column offsets put it. */
    public static final int UNKNOWN_LENGTH = -1;

    private final String typeName;
    private final int ccsid;
    private final int bytesPerChar;
    private final int recordOffset;
    private final int padding;

    private static final String BLOB = "BLOB";

    /**
     * @param typeName     the catalogue type name: {@code CLOB}, {@code DBCLOB}, {@code NCLOB} (a dbclob
     *                     whose CCSID is a Unicode one), {@code BLOB} or {@code XML}
     * @param ccsid        the CCSID the data is encoded in, or {@link As400TextFactory#UNKNOWN_CCSID};
     *                     not used by {@code BLOB}, whose data is bytes
     * @param bytesPerChar bytes per character of the column's CCSID, used to size double byte data
     * @param recordOffset offset of the column within the record image, i.e. the widths of every column
     *                     in front of it, which is what decides the alignment padding and so the width
     *                     of the column itself
     */
    public AS400Lob(String typeName, int ccsid, int bytesPerChar, int recordOffset) {
        this.typeName = typeName;
        this.ccsid = ccsid;
        this.bytesPerChar = bytesPerChar;
        this.recordOffset = recordOffset;
        this.padding = paddingFor(recordOffset);
    }

    public String getTypeName() {
        return typeName;
    }

    public int getCcsid() {
        return ccsid;
    }

    /** The offset this column was built for, so a decoder can check it is reading where it expects. */
    public int getRecordOffset() {
        return recordOffset;
    }

    /** Whether the data is bytes rather than text, i.e. is not to be decoded through a CCSID. */
    public boolean isBinary() {
        return BLOB.equals(typeName);
    }

    /**
     * The data appended to the entry is bytes, while the descriptor counts in whatever unit the column's
     * length is declared in, so text is scaled by the width of one of its characters - as the fixed
     * width character types in this decoder are.
     *
     * <p>
     * That width is {@code character_octet_length / length}: 1 for single byte and for the mixed CCSIDs
     * whose length is already a byte count, making this a no-op for an ordinary CLOB or for XML, and 2
     * for the graphic and Unicode CCSIDs a DBCLOB/NCLOB uses. Live entries bear this out - an XML column
     * in CCSID 1208 reported 41 for a 40 character document holding one two byte character, while a
     * DBCLOB in 13488 reported 11 characters for 22 bytes of data.
     * </p>
     */
    public int byteLengthOf(int dataLength) {
        if (isBinary()) {
            return dataLength;
        }
        // the catalogue lookup reports -1 when it could not read the column
        return dataLength * Math.max(1, bytesPerChar);
    }

    /**
     * Reads how much LOB data this column holds, the only thing in the descriptor of any use to us - the
     * pointer beside it cannot be followed from here.
     *
     * @param data        the buffer holding the journal entry
     * @param fieldOffset offset of the column within {@code data}
     * @return the length, in the unit the column's length is declared in, or {@link #UNKNOWN_LENGTH}
     */
    public int dataLength(byte[] data, int fieldOffset) {
        if (fieldOffset < 0 || fieldOffset + getByteLength() > data.length) {
            // detail only: an unreadable descriptor is a property of the record format, so it repeats
            // for every entry of the table. JdbcFileDecoder reports it once; this is the evidence.
            log.debug("{} descriptor at offset {} runs past the end of the {} byte journal entry buffer",
                    typeName, fieldOffset, data.length);
            return UNKNOWN_LENGTH;
        }
        if (!describesLob(data, fieldOffset, padding)) {
            // reading a length from the wrong four bytes would size the appended data wrongly, and a
            // descriptor that is not where the column offsets put it means the record format is out of
            // step with the entry, so the columns after it are not to be trusted either
            log.debug("no {} descriptor at record offset {} padded by {} bytes, the column reads {}", typeName,
                    recordOffset, padding, Diagnostics.binAsHex(data, fieldOffset, getByteLength()));
            return UNKNOWN_LENGTH;
        }
        return lengthAt(data, fieldOffset, padding);
    }

    /** The padding that puts the pointer on a 16 byte boundary. */
    private static int paddingFor(int recordOffset) {
        return Math.floorMod(-(recordOffset + POINTER_DISPLACEMENT), ALIGNMENT);
    }

    /**
     * Whether the bytes at {@code padding} look like a descriptor: the alignment padding is zero, as are
     * the 8 reserved bytes between the length and the pointer, and the length is not negative.
     *
     * <p>
     * The byte of system information between them is not checked. The documentation calls it {@code
     * '00'x}, but a column holding double byte text carries {@code '01'x} there, so it identifies the
     * data rather than being reserved. It is of no use here: the length is at a fixed distance from the
     * pointer, which the padding aligns, whatever that byte says.
     * </p>
     */
    private static boolean describesLob(byte[] data, int fieldOffset, int padding) {
        if (!isZero(data, fieldOffset, padding)) {
            return false;
        }
        if (!isZero(data, fieldOffset + padding + RESERVED_DISPLACEMENT, POINTER_DISPLACEMENT - RESERVED_DISPLACEMENT)) {
            return false;
        }
        return lengthAt(data, fieldOffset, padding) >= 0;
    }

    private static boolean isZero(byte[] data, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (data[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static int lengthAt(byte[] data, int fieldOffset, int padding) {
        return (Integer) LENGTH_DECODER.toObject(data, fieldOffset + padding + LENGTH_DISPLACEMENT);
    }

    @Override
    public int getByteLength() {
        return padding + DESCRIPTOR_LENGTH;
    }

    @Override
    public Object getDefaultValue() {
        return null;
    }

    @Override
    public int getInstanceType() {
        return isBinary() ? AS400DataType.TYPE_BYTE_ARRAY : AS400DataType.TYPE_TEXT;
    }

    @Override
    public Class<?> getJavaType() {
        return isBinary() ? byte[].class : String.class;
    }

    @Override
    public byte[] toBytes(Object javaValue) {
        return new byte[0];
    }

    @Override
    public int toBytes(Object javaValue, byte[] as400Value) {
        return 0;
    }

    @Override
    public int toBytes(Object javaValue, byte[] as400Value, int offset) {
        return 0;
    }

    @Override
    public Object toObject(byte[] data) {
        return null;
    }

    /**
     * Always null: the record image holds no LOB data, and the decoder that knows which journal entry
     * this is fills the value in. Decoding a LOB column through {@link com.ibm.as400.access.AS400Structure}
     * rather than {@code JdbcFileDecoder.decodeEntry} therefore yields no value.
     */
    @Override
    public Object toObject(byte[] data, int offset) {
        return null;
    }

    @Override
    public Object clone() {
        try {
            return super.clone(); // Object.clone does not throw exception.
        }
        catch (final CloneNotSupportedException e) {
            Trace.log(Trace.ERROR, "Unexpected CloneNotSupportedException:", e);
            throw new InternalErrorException(InternalErrorException.UNEXPECTED_EXCEPTION);
        }
    }

    @Override
    public String toString() {
        return String.format("AS400Lob [typeName=%s, ccsid=%s, bytesPerChar=%s, recordOffset=%s, padding=%s]",
                typeName, ccsid, bytesPerChar, recordOffset, padding);
    }
}
