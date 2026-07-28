/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.data.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400Bin2;
import com.ibm.as400.access.AS400Text;

/**
 * A single {@link AS400VarChar}/{@link AS400VarBin} instance is shared by every decode of a table -
 * it lives inside the {@code AS400Structure} cached per table in {@code SchemaCacheIF}. Decoding
 * therefore must not park per-record state on the instance, or two concurrent decodes of the same
 * table read each other's length and silently return truncated or over-long values.
 */
class VarLengthConcurrentDecodeTest {

    private static final int EBCDIC_US = 37;
    private static final int THREADS = 8;
    private static final int ITERATIONS = 2000;

    /** [2 byte length][payload padded to max] laid out the way a journal record image is. */
    private static byte[] varCharRecord(String value, int max, int ccsid) {
        final byte[] data = new byte[2 + max];
        new AS400Bin2().toBytes((short) value.length(), data, 0);
        new AS400Text(value.length(), ccsid).toBytes(value, data, 2);
        return data;
    }

    @Test
    void varCharDecodesIndependentlyAcrossThreads() throws Exception {
        final int max = 8;
        final AS400VarChar shared = new AS400VarChar(max, 1, EBCDIC_US);
        // two records of different lengths through the one shared instance
        final byte[] shortRecord = varCharRecord("AB", max, EBCDIC_US);
        final byte[] longRecord = varCharRecord("ABCDEFGH", max, EBCDIC_US);

        runConcurrently(i -> {
            final boolean useShort = (i % 2 == 0);
            final Object decoded = shared.toObject(useShort ? shortRecord : longRecord, 0);
            assertEquals(useShort ? "AB" : "ABCDEFGH", decoded);
            return null;
        });
    }

    @Test
    void varBinDecodesIndependentlyAcrossThreads() throws Exception {
        final int max = 8;
        final AS400VarBin shared = new AS400VarBin(max);
        final byte[] shortRecord = new byte[2 + max];
        new AS400Bin2().toBytes((short) 2, shortRecord, 0);
        final byte[] longRecord = new byte[2 + max];
        new AS400Bin2().toBytes((short) 8, longRecord, 0);

        runConcurrently(i -> {
            final boolean useShort = (i % 2 == 0);
            final byte[] decoded = (byte[]) shared.toObject(useShort ? shortRecord : longRecord, 0);
            assertEquals(useShort ? 2 : 8, decoded.length);
            return null;
        });
    }

    private static void runConcurrently(ThrowingIntConsumer body) throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        try {
            final List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < THREADS; t++) {
                final int offsetSeed = t;
                tasks.add(() -> {
                    for (int i = 0; i < ITERATIONS; i++) {
                        body.accept(i + offsetSeed);
                    }
                    return null;
                });
            }
            final List<Future<Void>> futures = pool.invokeAll(tasks);
            for (final Future<Void> f : futures) {
                f.get(60, TimeUnit.SECONDS); // rethrows the assertion failure from the worker
            }
        }
        finally {
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        }
    }

    private interface ThrowingIntConsumer {
        Void accept(int i) throws Exception;
    }
}
