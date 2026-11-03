/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.data.types;

import java.util.Map;

/**
 * How an untagged (CCSID 65535) text column is read: in the extended superset of the system CCSID,
 * unless a sample of it does not read as text (Popsink/data-plane#5831).
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

    /** Code points, not char literals: the build's formatter rewrites unicode escapes into raw characters. */
    private static final int REPLACEMENT = 0xFFFD;
    private static final int SUB = 0x1A;

    private UntaggedCcsid() {
    }

    /** @return the CCSID untagged text decodes with on a system of {@code systemCcsid} */
    public static int superset(int systemCcsid) {
        return SUPERSET.getOrDefault(systemCcsid, systemCcsid);
    }

    /**
     * Whether a decoded value looks like text: no replacement character, no SUB (what an unmapped
     * EBCDIC byte decodes to) and no C0/C1 control other than tab, CR and LF. Shift-out/shift-in bytes
     * of a mixed CCSID never reach here, {@code AS400Text} consumes them.
     */
    public static boolean readsAsText(String decoded) {
        for (int i = 0; i < decoded.length(); i++) {
            final int c = decoded.charAt(i);
            if (c == REPLACEMENT || c == SUB) {
                return false;
            }
            final boolean control = c < 0x20 || (c >= 0x80 && c <= 0x9F);
            if (control && c != '\t' && c != '\r' && c != '\n') {
                return false;
            }
        }
        return true;
    }
}
