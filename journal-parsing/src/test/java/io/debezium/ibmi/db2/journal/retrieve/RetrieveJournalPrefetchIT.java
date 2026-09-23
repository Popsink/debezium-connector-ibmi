/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;

import io.debezium.ibmi.db2.journal.data.types.As400TextFactory;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.DetailedJournalReceiver;
import io.debezium.ibmi.db2.journal.test.TestConnector;

/**
 * Reads a whole journal on a live system with and without the prefetch: same entries, same order, same
 * end position (issue #68), and logs what each took. Needs {@code ISERIES_HOST}, {@code ISERIES_USER},
 * {@code ISERIES_PASSWORD}, {@code ISERIES_SCHEMA}; {@code ISERIES_JOURNAL} defaults to {@code JRNTEST}.
 */
class RetrieveJournalPrefetchIT {
    private static final Logger log = LoggerFactory.getLogger(RetrieveJournalPrefetchIT.class);
    private static final int BUFFER = 256 * 1024;

    private static Connect<AS400, IOException> as400Connect;
    private static As400TextFactory textFactory;
    private static JournalInfoRetrieval journalInfoRetrieval;
    private static JournalInfo journal;
    private static JournalProcessedPosition start;

    @BeforeAll
    static void setup() throws Exception {
        assumeTrue(System.getenv("ISERIES_HOST") != null && System.getenv("ISERIES_PASSWORD") != null,
                "needs ISERIES_HOST, ISERIES_USER, ISERIES_PASSWORD and ISERIES_SCHEMA for a live system");
        final TestConnector connector = new TestConnector();
        as400Connect = connector.getAs400();
        textFactory = connector.getTextFactory();
        journalInfoRetrieval = new JournalInfoRetrieval(textFactory, 0, 0, 2000);
        final String journalName = System.getenv().getOrDefault("ISERIES_JOURNAL", "JRNTEST");
        journal = new JournalInfo(journalName, connector.getSchema(), false);
        final List<DetailedJournalReceiver> receivers = journalInfoRetrieval.getReceivers(as400Connect.connection(), journal);
        final DetailedJournalReceiver first = receivers.stream().min((x, y) -> x.start().compareTo(y.start())).get();
        start = new JournalProcessedPosition(first.start(), first.info().receiver(), Instant.EPOCH, false);
        log.info("reading {} from {} over {} receivers", journal, start, receivers.size());
    }

    record Read(List<BigInteger> sequences, JournalProcessedPosition end, long totalMs, long rpcMs, long waitedMs, int blocks) {
    }

    private static Read read(boolean prefetch) throws Exception {
        final RetrieveConfig config = new RetrieveConfigBuilder().withAs400(as400Connect).withTextFactory(textFactory)
                .withJournalInfo(journal).withJournalBufferSize(BUFFER).withServerFiltering(false).withPrefetch(prefetch).build();
        final RetrieveJournal rj = new RetrieveJournal(config, journalInfoRetrieval);
        final List<BigInteger> sequences = new ArrayList<>();
        final JournalProcessedPosition position = new JournalProcessedPosition(start);
        long rpc = 0;
        long waited = 0;
        int blocks = 0;
        final long started = System.currentTimeMillis();
        RetrievalState state;
        do {
            state = rj.retrieveJournal(position);
            blocks++;
            rpc += rj.lastRpcMs();
            waited += rj.lastWaitMs();
            while (rj.nextEntry()) {
                sequences.add(rj.getEntryHeader().getSequenceNumber());
            }
            position.setPosition(rj.getPosition());
        } while (state == RetrievalState.MoreDataAvailable);
        assertFalse(rj.prefetchInFlight(), "nothing left running once the range is read to its end");
        return new Read(sequences, position, System.currentTimeMillis() - started, rpc, waited, blocks);
    }

    @Test
    void prefetchReadsTheSameJournal() throws Exception {
        final Read serial = read(false);
        final Read pipelined = read(true);
        log.info("serial:    {} entries in {} blocks, {} ms, rpc {} ms, waited {} ms", serial.sequences().size(), serial.blocks(),
                serial.totalMs(), serial.rpcMs(), serial.waitedMs());
        log.info("pipelined: {} entries in {} blocks, {} ms, rpc {} ms, waited {} ms (hidden {} ms)", pipelined.sequences().size(),
                pipelined.blocks(), pipelined.totalMs(), pipelined.rpcMs(), pipelined.waitedMs(), pipelined.rpcMs() - pipelined.waitedMs());

        assertTrue(serial.blocks() > 2, "the journal must span several blocks for the prefetch to be exercised");
        assertEquals(serial.sequences(), pipelined.sequences(), "same entries in the same order");
        assertEquals(serial.end(), pipelined.end(), "same position at the end");
        assertTrue(pipelined.waitedMs() <= pipelined.rpcMs(), "the wait can only be part of the round trip");
    }
}
