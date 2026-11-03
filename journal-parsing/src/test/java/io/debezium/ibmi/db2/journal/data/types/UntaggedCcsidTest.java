/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.data.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400Text;

class UntaggedCcsidTest {

    /** The table the data-plane loaders pin in {@code docs/ibmi-untagged-ccsid.json}: change both or neither. */
    @Test
    void supersetIsTheTableTheBackfillLoadersUse() {
        assertEquals(Map.of(935, 1388, 836, 1388, 837, 1388, 937, 1371, 933, 1364,
                930, 1390, 5026, 1390, 939, 1399, 5035, 1399), UntaggedCcsid.SUPERSET);
        assertEquals(1388, UntaggedCcsid.superset(935));
        assertEquals(1399, UntaggedCcsid.superset(5035));
        assertEquals(37, UntaggedCcsid.superset(37), "a CCSID with no superset is its own");
    }

    @Test
    void ebcdicTextReadsAsText() {
        // "D2000106" in CCSID 37, the shape of the value in the issue
        final byte[] bytes = { (byte) 0xC4, (byte) 0xF2, (byte) 0xF0, (byte) 0xF0, (byte) 0xF0, (byte) 0xF1,
                (byte) 0xF0, (byte) 0xF6 };
        final String decoded = (String) new AS400Text(bytes.length, 37).toObject(bytes);

        assertEquals("D2000106", decoded);
        assertTrue(UntaggedCcsid.readsAsText(decoded));
        assertTrue(UntaggedCcsid.readsAsText("line one\r\n\tline two"), "tab, CR and LF are text");
    }

    /** Built from code points: the build's formatter rewrites unicode escapes into raw characters. */
    private static String around(int codePoint) {
        return "ab" + (char) codePoint + "c";
    }

    @Test
    void binaryDoesNotReadAsText() {
        assertFalse(UntaggedCcsid.readsAsText(around(0xFFFD)), "replacement character");
        assertFalse(UntaggedCcsid.readsAsText(around(0x1A)), "SUB, what an unmapped EBCDIC byte decodes to");
        assertFalse(UntaggedCcsid.readsAsText(around(0x00)), "C0 control");
        assertFalse(UntaggedCcsid.readsAsText(around(0x1F)), "C0 control");
        assertFalse(UntaggedCcsid.readsAsText(around(0x85)), "C1 control");
        assertFalse(UntaggedCcsid.readsAsText(around(0x9F)), "C1 control");
        assertTrue(UntaggedCcsid.readsAsText(around(0xA0)), "just past C1 is text");
    }

    /** A password hash read as EBCDIC is full of control characters. */
    @Test
    void hashedBytesDoNotReadAsText() {
        final byte[] hash = { 0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF };

        assertFalse(UntaggedCcsid.readsAsText((String) new AS400Text(hash.length, 37).toObject(hash)));
    }
}
