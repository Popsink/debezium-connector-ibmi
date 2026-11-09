/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.data.types;

import java.util.Map;

/**
 * How an untagged (CCSID 65535) text column is read: in the extended superset of the system CCSID,
 * unless a sample of it does not read as text (Popsink/data-plane#5831, #5989).
 *
 * <p>
 * The superset decodes every character of the original identically and adds the ones it lacks. The
 * data-plane backfill loaders carry the same table and guard, pinned by
 * {@code docs/ibmi-untagged-ccsid.json} there: a change here is a change there, or a backfilled row
 * and a streamed row stop hashing alike.
 * </p>
 */
public final class UntaggedCcsid {

    /** System CCSID to its extended superset; a CCSID absent here is its own. */
    public static final Map<Integer, Integer> SUPERSET = Map.of(
            935, 1388, 836, 1388, 837, 1388,
            937, 1371,
            933, 1364,
            930, 1390, 5026, 1390,
            939, 1399, 5035, 1399);

    /**
     * At most one non-blank sampled value in this many may fail {@link #readsAsText} and the column is still
     * text (Popsink/data-plane#5989): a host's text holds the odd value damaged on entry (a stored SUB, an
     * unmapped DBCS code), while binary data fails on most. Below this many values, none may fail. The
     * loaders' {@code UntaggedCcsid.Verdict} is the same rule.
     */
    public static final int VALUES_PER_FAILURE = 20;

    /** EBCDIC space: a {@code CHAR} holding nothing is padded with it. */
    private static final int EBCDIC_BLANK = 0x40;

    /** A code point, not a char literal: the build's formatter rewrites unicode escapes into raw characters. */
    private static final char REPLACEMENT = 0xFFFD;

    private UntaggedCcsid() {
    }

    /** @return the CCSID untagged text decodes with on a system of {@code systemCcsid} */
    public static int superset(int systemCcsid) {
        return SUPERSET.getOrDefault(systemCcsid, systemCcsid);
    }

    /** @return whether a column whose sample held {@code nonBlank} values, {@code failing} of them not text, is text */
    public static boolean isTextColumn(int nonBlank, int failing) {
        return failing * VALUES_PER_FAILURE <= nonBlank;
    }

    /** @return whether {@code value} is EBCDIC blanks only, or empty: it says nothing about the column */
    public static boolean isBlank(byte[] value) {
        for (final byte b : value) {
            if ((b & 0xFF) != EBCDIC_BLANK) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether a decoded value looks like text: no replacement character and no ISO control (C0, DEL,
     * C1 - SUB, what an unmapped EBCDIC byte decodes to, among them) other than tab, CR and LF. The
     * loaders' {@code UntaggedCcsid.readsAsText} is the same test. Shift-out/shift-in bytes of a mixed
     * CCSID never reach here, {@code AS400Text} consumes them.
     */
    public static boolean readsAsText(String decoded) {
        for (int i = 0; i < decoded.length(); i++) {
            final char c = decoded.charAt(i);
            if (c == REPLACEMENT || (Character.isISOControl(c) && c != '\t' && c != '\r' && c != '\n')) {
                return false;
            }
        }
        return true;
    }
}
