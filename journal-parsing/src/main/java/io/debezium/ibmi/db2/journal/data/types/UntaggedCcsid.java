/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.data.types;

import java.util.Map;

/**
 * The CCSID an untagged (65535) text column is decoded with: the extended superset of the system
 * CCSID (Popsink/data-plane#5831).
 *
 * <p>
 * The superset decodes every character of the original identically and adds the ones it lacks, so
 * choosing it can only gain characters. The data-plane backfill loaders carry the same table, pinned
 * by {@code docs/ibmi-untagged-ccsid.tsv} there; a change here is a change there.
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

    private UntaggedCcsid() {
    }

    /** @return the CCSID untagged text decodes with on a system of {@code systemCcsid} */
    public static int forSystem(int systemCcsid) {
        return SUPERSET.getOrDefault(systemCcsid, systemCcsid);
    }
}
