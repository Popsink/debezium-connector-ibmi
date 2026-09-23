/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.data.types;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class DiagnosticsTest {

    private static final byte[] DATA = { 0x00, 0x40, (byte) 0xc1, (byte) 0xf9, 0x7f, (byte) 0x80, (byte) 0xff };

    @Test
    public void hexIsContiguousLowercaseOfTheRange() {
        assertEquals("40c1f9", Diagnostics.hex(DATA, 1, 3, 100));
    }

    @Test
    public void hexOfWholeBufferCoversEveryBytePattern() {
        assertEquals("0040c1f97f80ff", Diagnostics.hex(DATA, 0, DATA.length, 100));
    }

    @Test
    public void hexTruncatesAtMaxBytesAndSaysSo() {
        assertEquals("0040 ...(truncated, 7 bytes total)", Diagnostics.hex(DATA, 0, DATA.length, 2));
    }

    @Test
    public void hexClampsARangePastTheEndOfTheBuffer() {
        assertEquals("80ff", Diagnostics.hex(DATA, 5, 50, 100));
        assertEquals("80 ...(truncated, 2 bytes total)", Diagnostics.hex(DATA, 5, 50, 1));
    }

    @Test
    public void hexIsEmptyForNothingToShow() {
        assertEquals("", Diagnostics.hex(null, 0, 10, 100));
        assertEquals("", Diagnostics.hex(DATA, 0, 0, 100));
        assertEquals("", Diagnostics.hex(DATA, 0, -1, 100));
        assertEquals("", Diagnostics.hex(DATA, DATA.length, 1, 100));
        assertEquals("", Diagnostics.hex(DATA, -1, 1, 100));
    }

    @Test
    public void zeroMaxBytesKeepsOnlyTheTotal() {
        assertEquals(" ...(truncated, 7 bytes total)", Diagnostics.hex(DATA, 0, DATA.length, 0));
    }
}
