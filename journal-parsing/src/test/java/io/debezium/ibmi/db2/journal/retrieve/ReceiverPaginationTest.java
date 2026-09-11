/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;

import io.debezium.ibmi.db2.journal.retrieve.exception.LostJournalException;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.DetailedJournalReceiver;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalReceiverInfo;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.JournalStatus;

@ExtendWith(MockitoExtension.class)
class ReceiverPaginationTest {
    ReceiverPagination receivers;
    @Mock
    JournalInfoRetrieval journalInfoRetrieval;
    JournalInfo journalInfo = new JournalInfo("journal", "journallib", false);
    @Mock
    AS400 as400;

    DetailedJournalReceiver dr3 = new DetailedJournalReceiver(new JournalReceiverInfo(new JournalReceiver("j3", "jlib"),
            new Date(3), JournalStatus.Attached, Optional.of(1)), BigInteger.valueOf(9), BigInteger.valueOf(17),
            Optional.empty(), 1, 1);
    DetailedJournalReceiver dr2 = new DetailedJournalReceiver(new JournalReceiverInfo(new JournalReceiver("j2", "jlib"),
            new Date(2), JournalStatus.OnlineSavedDetached, Optional.of(1)), BigInteger.valueOf(3),
            BigInteger.valueOf(8),
            Optional.of(dr3.info().receiver()), 1, 1);
    DetailedJournalReceiver dr1 = new DetailedJournalReceiver(new JournalReceiverInfo(new JournalReceiver("j1", "jlib"),
            new Date(1), JournalStatus.OnlineSavedDetached, Optional.of(1)), BigInteger.ONE, BigInteger.TWO,
            Optional.of(dr2.info().receiver()), 1, 1);

    @BeforeEach
    public void setUp() throws Exception {
    }

    @Test
    void findRangeWithinCurrentPosistion() throws Exception {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);

        final List<DetailedJournalReceiver> list = Arrays.asList(dr1);

        when(journalInfoRetrieval.getReceivers(any(), any())).thenReturn(list);

        when(journalInfoRetrieval.getDelayedDetailedJournalReceiver(any(), any())).thenReturn(Optional.of(dr1));

        final JournalProcessedPosition startPosition = new JournalProcessedPosition(BigInteger.ONE,
                new JournalReceiver("j1", "jlib"), Instant.ofEpochSecond(0), true);
        final Optional<PositionRange> result = jreceivers.findRange(as400, startPosition);
        final PositionRange rangeAnswer = new PositionRange(false, startPosition,
                new JournalPosition(dr1.end(), dr1.info().receiver()));
        assertEquals(rangeAnswer, result.get());
    }

    @Test
    void findRangeInList() throws Exception {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);

        final List<DetailedJournalReceiver> list = Arrays.asList(dr1, dr2);

        when(journalInfoRetrieval.getReceivers(any(), any())).thenReturn(list);

        when(journalInfoRetrieval.getDelayedDetailedJournalReceiver(any(), any())).thenReturn(Optional.of(dr2));

        final JournalProcessedPosition startPosition = new JournalProcessedPosition(BigInteger.ONE,
                new JournalReceiver("j1", "jlib"), Instant.ofEpochSecond(0), true);
        final Optional<PositionRange> result = jreceivers.findRange(as400, startPosition);
        final PositionRange rangeAnswer = new PositionRange(false, startPosition,
                new JournalPosition(dr2.end(), dr2.info().receiver()));
        assertEquals(rangeAnswer, result.get());
    }

    @Test
    void findRangeReFetchList() throws Exception {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 15, journalInfo);

        final DetailedJournalReceiver detailedEnd = dr2;
        final List<DetailedJournalReceiver> list = Arrays.asList(dr1, detailedEnd);

        final DetailedJournalReceiver detailedEnd2 = dr3;
        final List<DetailedJournalReceiver> list2 = Arrays.asList(dr1, dr2, detailedEnd2);
        when(journalInfoRetrieval.getReceivers(any(), any())).thenReturn(list).thenReturn(list2);
        when(journalInfoRetrieval.getDelayedDetailedJournalReceiver(any(), any())).thenReturn(Optional.of(detailedEnd)).thenReturn(Optional.of(detailedEnd2));

        final JournalProcessedPosition startPosition = new JournalProcessedPosition(BigInteger.ONE,
                new JournalReceiver("j1", "jlib"), Instant.ofEpochSecond(0), true);
        final Optional<PositionRange> result = jreceivers.findRange(as400, startPosition);
        final PositionRange rangeAnswer = new PositionRange(false, startPosition,
                new JournalPosition(BigInteger.valueOf(8), detailedEnd.info().receiver()));
        assertEquals(rangeAnswer, result.get());

        final JournalProcessedPosition startPosition2 = new JournalProcessedPosition(BigInteger.valueOf(2),
                new JournalReceiver("j2", "jlib"), Instant.ofEpochSecond(0), true);
        final Optional<PositionRange> result2 = jreceivers.findRange(as400, startPosition2);
        final PositionRange rangeAnswer2 = new PositionRange(false, startPosition2,
                new JournalPosition(BigInteger.valueOf(17), detailedEnd2.info().receiver()));
        assertEquals(rangeAnswer2, result2.get());

    }

    @Test
    void testFindRangeMidFirstEntry() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(5), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(6), BigInteger.valueOf(20), Optional.of(new JournalReceiver("j3", "jlib")), 1, 1);
        final DetailedJournalReceiver j3 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j3", "jlib"), new Date(3),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(21), BigInteger.valueOf(22), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = Arrays.asList(j1, j2, j3);
        final Optional<PositionRange> position = jreceivers.findPosition(
                new JournalProcessedPosition(BigInteger.ONE, j1.info().receiver(), Instant.ofEpochSecond(0), true),
                BigInteger.valueOf(3), list, j3);
        assertTrue(position.isPresent());
        assertEquals("j1", position.get().end().getReceiver().name());
        assertEquals(BigInteger.valueOf(4), position.get().end().getOffset());
    }

    @Test
    void testFindRangeMidSecondEntryReset() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(11), BigInteger.valueOf(20), Optional.of(new JournalReceiver("j3", "jlib")), 1, 1);
        final DetailedJournalReceiver j3 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j3", "jlib"), new Date(3),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(21), BigInteger.valueOf(30), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = Arrays.asList(j1, j2, j3);
        final Optional<PositionRange> position = jreceivers.findPosition(
                new JournalProcessedPosition(BigInteger.ONE, j1.info().receiver(), Instant.ofEpochSecond(0), true),
                BigInteger.valueOf(15), list, j3);
        assertTrue(position.isPresent());
        assertEquals("j2", position.get().end().getReceiver().name());
        assertEquals(BigInteger.valueOf(16), position.get().end().getOffset());
    }

    @Test
    void testFindRangeMidSecondContiguous() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(2), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(3), BigInteger.valueOf(20), Optional.of(new JournalReceiver("j3", "jlib")), 1, 1);
        final DetailedJournalReceiver j3 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j3", "jlib"), new Date(3),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(21), BigInteger.valueOf(22), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = Arrays.asList(j1, j2, j3);
        final Optional<PositionRange> position = jreceivers.findPosition(
                new JournalProcessedPosition(BigInteger.ONE, j1.info().receiver(), Instant.ofEpochSecond(0), true),
                BigInteger.valueOf(10), list, j3);
        assertTrue(position.isPresent());
        assertEquals("j2", position.get().end().getReceiver().name());
        assertEquals(BigInteger.valueOf(11), position.get().end().getOffset());
    }

    @Test
    void testFindRangeMidEndEntry() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(2), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(3), BigInteger.valueOf(20), Optional.of(new JournalReceiver("j3", "jlib")), 1, 1);
        final DetailedJournalReceiver j3 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j3", "jlib"), new Date(3),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(21), BigInteger.valueOf(35), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = Arrays.asList(j1, j2, j3);
        final Optional<PositionRange> position = jreceivers.findPosition(
                new JournalProcessedPosition(BigInteger.ONE, j1.info().receiver(), Instant.ofEpochSecond(0), true),
                BigInteger.valueOf(30), list, j3);
        assertTrue(position.isPresent());
        assertEquals("j3", position.get().end().getReceiver().name());
        assertEquals(BigInteger.valueOf(31), position.get().end().getOffset());
    }

    @Test
    void testFindRangePastEnd() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(2), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(3), BigInteger.valueOf(20), Optional.of(new JournalReceiver("j3", "jlib")), 1, 1);
        final DetailedJournalReceiver j3 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j3", "jlib"), new Date(3),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(21), BigInteger.valueOf(35), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = List.of(j1, j2, j3);
        final Optional<PositionRange> position = jreceivers.findPosition(
                new JournalProcessedPosition(BigInteger.ONE, j1.info().receiver(), Instant.ofEpochSecond(0), true),
                BigInteger.valueOf(100), list, j3);
        assertTrue(position.isPresent());
        assertEquals("j3", position.get().end().getReceiver().name());
        assertEquals(BigInteger.valueOf(35), position.get().end().getOffset());
    }

    @Test
    void testFindRangeEqualsEnd() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 10, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = List.of(j1);
        final Optional<PositionRange> position = jreceivers.findPosition(
                new JournalProcessedPosition(BigInteger.ONE, j1.info().receiver(), Instant.ofEpochSecond(0), true),
                BigInteger.valueOf(100), list, j1);
        assertTrue(position.isPresent());
        assertEquals("j1", position.get().end().getReceiver().name());
        assertEquals(BigInteger.valueOf(10), position.get().end().getOffset());
    }

    @Test
    void testFindMidStartingMid() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(11), BigInteger.valueOf(20), Optional.of(new JournalReceiver("j3", "jlib")), 1, 1);
        final DetailedJournalReceiver j3 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j3", "jlib"), new Date(3),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(21), BigInteger.valueOf(30), Optional.of(new JournalReceiver("j4", "jlib")), 1, 1);
        final DetailedJournalReceiver j4 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j4", "jlib"), new Date(4),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(41), BigInteger.valueOf(50), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = List.of(j1, j2, j3, j4);
        final JournalProcessedPosition start = new JournalProcessedPosition(BigInteger.valueOf(25),
                j3.info().receiver(), Instant.ofEpochSecond(0), true);
        final Optional<PositionRange> position = jreceivers.findPosition(start, BigInteger.valueOf(10), list, j4);
        assertTrue(position.isPresent());
        assertEquals("j4", position.get().end().getReceiver().name());
        assertEquals(BigInteger.valueOf(45), position.get().end().getOffset());

    }

    @Test
    void testFindStartingPastEnd() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(11), BigInteger.valueOf(20), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = List.of(j1, j2);
        final Optional<PositionRange> position = jreceivers
                .findPosition(new JournalProcessedPosition(BigInteger.valueOf(30), new JournalReceiver("j3", "jlib"),
                        Instant.ofEpochSecond(0), true), BigInteger.valueOf(15), list, j2);
        assertTrue(position.isEmpty());
    }

    @Test
    void testPaginateInSameReceiverEnd() throws Exception {
        final int maxOffset = 1000;
        final BigInteger maxServerSideEntriesBI = BigInteger.valueOf(maxOffset);
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, maxOffset, journalInfo);

        final JournalProcessedPosition startPosition = new JournalProcessedPosition(BigInteger.ONE,
                new JournalReceiver("j1", "jlib"), Instant.ofEpochSecond(0), true);
        final DetailedJournalReceiver endJournalPosition = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(100), Optional.empty(), 1, 1);

        final PositionRange range = jreceivers.paginateInSameReceiver(startPosition, endJournalPosition,
                maxServerSideEntriesBI);
        assertEquals(endJournalPosition.end(), range.end().getOffset());
    }

    @Test
    void testPaginateInSameReceiverLimited() throws Exception {
        final int maxOffset = 10;
        final BigInteger maxServerSideEntriesBI = BigInteger.valueOf(maxOffset);
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, maxOffset, journalInfo);

        final JournalProcessedPosition startPosition = new JournalProcessedPosition(BigInteger.ONE,
                new JournalReceiver("j1", "jlib"), Instant.ofEpochSecond(0), true);
        final DetailedJournalReceiver endJournalPosition = new DetailedJournalReceiver(
                new JournalReceiverInfo(startPosition.getReceiver(), new Date(1), JournalStatus.OnlineSavedDetached,
                        Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(100), Optional.empty(), 1, 1);

        final PositionRange range = jreceivers.paginateInSameReceiver(startPosition, endJournalPosition,
                maxServerSideEntriesBI);
        assertEquals(startPosition.getOffset().add(maxServerSideEntriesBI), range.end().getOffset());
    }

    @Test
    void testUpdateEndPosition() {
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(11), BigInteger.valueOf(20), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = Arrays.asList(j1, j2);

        final DetailedJournalReceiver endPosition = new DetailedJournalReceiver(
                new JournalReceiverInfo(j2.info().receiver(), new Date(2), JournalStatus.OnlineSavedDetached,
                        Optional.of(1)),
                BigInteger.valueOf(11), BigInteger.valueOf(200), Optional.empty(), 1, 1);
        ReceiverPagination.updateEndPosition(list, endPosition);

        assertEquals(endPosition, list.get(1));
    }

    @Test
    void testFindMissingCurrentReceiver() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(11), BigInteger.valueOf(20), Optional.empty(), 1, 1);
        final DetailedJournalReceiver j3 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j3", "jlib"), new Date(3),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(21), BigInteger.valueOf(31), Optional.of(new JournalReceiver("j4", "jlib")), 1, 1);
        final List<DetailedJournalReceiver> list = List.of(j1, j2);
        final Optional<PositionRange> position = jreceivers
                .findPosition(new JournalProcessedPosition(BigInteger.valueOf(30), j1.info().receiver(),
                        Instant.ofEpochSecond(0), true), BigInteger.valueOf(15), list, j3);
        assertTrue(position.isEmpty());
    }

    @Test
    void testContainsEndPosition() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final List<DetailedJournalReceiver> list = List.of(dr2, dr3);
        assertTrue(jreceivers.containsEndPosition(list, dr3), "last entry found");
        assertTrue(jreceivers.containsEndPosition(list, dr2), "first entry found");
    }

    @Test
    void testNotContainsEndPosition() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final List<DetailedJournalReceiver> list = List.of(dr2, dr3);
        final boolean found = jreceivers.containsEndPosition(list, dr1);
        assertFalse(found, "receiver does not exist in list");
    }

    @Test
    void testStartEqualsEndNotProcessed() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = List.of(j1);

        final JournalProcessedPosition start = new JournalProcessedPosition(BigInteger.valueOf(10),
                j1.info().receiver(), Instant.ofEpochSecond(0), false);

        final Optional<PositionRange> found = jreceivers.findPosition(start, BigInteger.valueOf(15), list, j1);
        assertEquals(start, found.get().start());
        assertEquals(start.asJournalPosition(), found.get().end());
        assertFalse(found.get().startEqualsEnd());
    }

    @Test
    void testStartEqualsEndProcessed() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = List.of(j1);

        final JournalProcessedPosition start = new JournalProcessedPosition(BigInteger.valueOf(10),
                j1.info().receiver(), Instant.ofEpochSecond(0), true);

        final Optional<PositionRange> found = jreceivers
                .findPosition(start, BigInteger.valueOf(15), list, j1);
        assertEquals(start, found.get().start());
        assertEquals(start.asJournalPosition(), found.get().end());
        assertTrue(found.get().startEqualsEnd());
    }

    @Test
    void testStartEqualsEndProcessedResetReceiver() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(1), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = List.of(j1, j2);

        final JournalProcessedPosition start = new JournalProcessedPosition(BigInteger.valueOf(10),
                j1.info().receiver(), Instant.ofEpochSecond(10), true);

        final Optional<PositionRange> found = jreceivers.findPosition(start, BigInteger.valueOf(20), list, j2);
        assertEquals(
                new JournalProcessedPosition(JournalPosition.startPosition(j2), start.getTimeOfLastProcessed(), false),
                found.get().start());
        assertEquals(JournalPosition.endPosition(j2), found.get().end());
        assertFalse(found.get().startEqualsEnd());
    }

    @Test
    void testStartEqualsEndNotProcessedResetReceivers() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(1), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = List.of(j1, j2);

        final JournalProcessedPosition start = new JournalProcessedPosition(BigInteger.valueOf(10),
                j1.info().receiver(), Instant.ofEpochSecond(10), false);

        final Optional<PositionRange> found = jreceivers.findPosition(start, BigInteger.valueOf(20), list, j2);
        assertEquals(start, found.get().start());
        assertEquals(JournalPosition.endPosition(j1), found.get().end());
        assertFalse(found.get().startEqualsEnd());
    }

    @Test
    void testStartEqualsEndProcessedResetReceiversPaginate() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = List.of(j1, j2);

        final JournalProcessedPosition start = new JournalProcessedPosition(BigInteger.valueOf(10),
                j1.info().receiver(), Instant.ofEpochSecond(10), true);

        final Optional<PositionRange> found = jreceivers.findPosition(start, BigInteger.valueOf(5), list, j2);
        assertEquals(
                new JournalProcessedPosition(JournalPosition.startPosition(j2), start.getTimeOfLastProcessed(), false),
                found.get().start());
        assertEquals(new JournalPosition(BigInteger.valueOf(5l), j2.info().receiver()), found.get().end());
        assertFalse(found.get().startEqualsEnd());
    }

    private static final Logger log = LoggerFactory.getLogger(ReceiverPaginationTest.class);

    @Test
    void testStopBeforeJournalResetsPaginateOver() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 20, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(11), BigInteger.valueOf(20), Optional.of(new JournalReceiver("j3", "jlib")), 1, 1);
        final DetailedJournalReceiver j3 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j3", "jlib"), new Date(4),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(100), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = List.of(j1, j2, j3);
        final JournalProcessedPosition start = new JournalProcessedPosition(BigInteger.valueOf(5),
                j1.info().receiver(), Instant.ofEpochSecond(0), true);
        final Optional<PositionRange> position = jreceivers.findPosition(start, BigInteger.valueOf(16), list, j1);
        assertTrue(position.isPresent());
        assertEquals("j2", position.get().end().getReceiver().name());
        assertEquals(j2.end(), position.get().end().getOffset());

    }

    @Test
    void testStopBeforeJournalResetsPaginateExact() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 20, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(11), BigInteger.valueOf(20), Optional.of(new JournalReceiver("j3", "jlib")), 1, 1);
        final DetailedJournalReceiver j3 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j3", "jlib"), new Date(4),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(100), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = List.of(j1, j2, j3);
        final JournalProcessedPosition start = new JournalProcessedPosition(BigInteger.valueOf(5),
                j1.info().receiver(), Instant.ofEpochSecond(0), true);
        final Optional<PositionRange> position = jreceivers.findPosition(start, BigInteger.valueOf(15), list, j1);
        assertTrue(position.isPresent());
        assertEquals("j2", position.get().end().getReceiver().name());
        assertEquals(j2.end(), position.get().end().getOffset());

    }

    @Test
    void testStopOneBeforeJournalResetsPaginate() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 20, journalInfo);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(10), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(11), BigInteger.valueOf(20), Optional.of(new JournalReceiver("j3", "jlib")), 1, 1);
        final DetailedJournalReceiver j3 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j3", "jlib"), new Date(4),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(100), Optional.empty(), 1, 1);
        final List<DetailedJournalReceiver> list = List.of(j1, j2, j3);
        final JournalProcessedPosition start = new JournalProcessedPosition(BigInteger.valueOf(5),
                j1.info().receiver(), Instant.ofEpochSecond(0), true);
        final Optional<PositionRange> position = jreceivers.findPosition(start, BigInteger.valueOf(14), list, j1);
        assertTrue(position.isPresent());
        assertEquals("j2", position.get().end().getReceiver().name());
        assertEquals(j2.end().subtract(BigInteger.ONE), position.get().end().getOffset());
    }

    @Test
    void testSkippingOverEndOfFirst() {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 40, journalInfo);
        final DetailedJournalReceiver j0 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j0", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(111111), Optional.of(new JournalReceiver("j1", "jlib")), 1, 1);
        final DetailedJournalReceiver j1 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j1", "jlib"), new Date(1),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(1), BigInteger.valueOf(20), Optional.of(new JournalReceiver("j2", "jlib")), 1, 1);
        final DetailedJournalReceiver j2 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j2", "jlib"), new Date(2),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(21), BigInteger.valueOf(30), Optional.of(new JournalReceiver("j3", "jlib")), 1, 1);
        final DetailedJournalReceiver j3 = new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver("j3", "jlib"), new Date(3),
                        JournalStatus.OnlineSavedDetached, Optional.of(1)),
                BigInteger.valueOf(31), BigInteger.valueOf(40), Optional.of(new JournalReceiver("j4", "jlib")), 1, 1);
        final List<DetailedJournalReceiver> list = List.of(j0, j1, j2, j3);

        final JournalProcessedPosition start = new JournalProcessedPosition(BigInteger.valueOf(111111),
                j0.info().receiver(), Instant.ofEpochSecond(10), true);

        final Optional<PositionRange> found = jreceivers.findPosition(start, BigInteger.valueOf(40), list, j1);
        assertEquals(start, found.get().start());
        assertEquals(new JournalPosition(BigInteger.valueOf(40), j3.info().receiver()), found.get().end());
    }

    /**
     * Starting over has to work from a fresh receiver list. The cached one is only refreshed when the
     * attached receiver changes, which deleting older receivers does not do, so a connector resetting to the
     * earliest receiver after losing its position would otherwise be sent straight back to a deleted one.
     */
    @Test
    void findRangeFromBeginningRefetchesReceivers() throws Exception {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);

        // j1 is deleted between the two calls while j2 stays attached
        when(journalInfoRetrieval.getReceivers(any(), any())).thenReturn(Arrays.asList(dr1, dr2)).thenReturn(Arrays.asList(dr2));
        when(journalInfoRetrieval.getDelayedDetailedJournalReceiver(any(), any())).thenReturn(Optional.of(dr2));

        final JournalProcessedPosition inFirstReceiver = new JournalProcessedPosition(BigInteger.ONE,
                dr1.info().receiver(), Instant.ofEpochSecond(0), true);
        jreceivers.findRange(as400, inFirstReceiver);

        final Optional<PositionRange> result = jreceivers.findRange(as400, new JournalProcessedPosition());
        assertEquals(dr2.info().receiver(), result.get().start().getReceiver());
        assertEquals(dr2.start(), result.get().start().getOffset());
    }

    /**
     * The receiver list is only refreshed when the attached receiver changes, so a position living in a
     * receiver the cached list never saw looks exactly like a position that is no longer in the journal
     * (issue #30). The list has to be re-read before concluding the position is unresolvable.
     */
    @Test
    void findRangeRefetchesReceiversBeforeGivingUpOnPosition() throws Exception {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);

        // the cached list is missing j1, a re-read of the list finds it
        when(journalInfoRetrieval.getReceivers(any(), any())).thenReturn(Arrays.asList(dr2, dr3))
                .thenReturn(Arrays.asList(dr1, dr2, dr3));
        when(journalInfoRetrieval.getDelayedDetailedJournalReceiver(any(), any())).thenReturn(Optional.of(dr3));

        final JournalProcessedPosition inMissingReceiver = new JournalProcessedPosition(BigInteger.ONE,
                dr1.info().receiver(), Instant.ofEpochSecond(0), true);
        final Optional<PositionRange> result = jreceivers.findRange(as400, inMissingReceiver);

        assertEquals(new PositionRange(false, inMissingReceiver,
                new JournalPosition(dr3.end(), dr3.info().receiver())), result.get(),
                "position resolved from the refreshed list");
        verify(journalInfoRetrieval, times(2)).getReceivers(any(), any());
    }

    @Test
    void findRangeThrowsLostJournalOnlyAfterRefetchingReceivers() throws Exception {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100, journalInfo);

        // j1 really is gone, both reads agree
        when(journalInfoRetrieval.getReceivers(any(), any())).thenReturn(Arrays.asList(dr2, dr3));
        when(journalInfoRetrieval.getDelayedDetailedJournalReceiver(any(), any())).thenReturn(Optional.of(dr3));

        final JournalProcessedPosition inDeletedReceiver = new JournalProcessedPosition(BigInteger.ONE,
                dr1.info().receiver(), Instant.ofEpochSecond(0), true);

        assertThrows(LostJournalException.class, () -> jreceivers.findRange(as400, inDeletedReceiver));
        verify(journalInfoRetrieval, times(2)).getReceivers(any(), any());
    }

    // ---- issue #79: a resolved range must never take streaming backwards ----

    /** A receiver chain long enough to tell "rewound to the oldest retained receiver" apart from a small slip. */
    private static DetailedJournalReceiver receiver(String name, long attach, long start, long end, JournalStatus status) {
        return new DetailedJournalReceiver(
                new JournalReceiverInfo(new JournalReceiver(name, "jlib"), new Date(attach), status, Optional.of(1)),
                BigInteger.valueOf(start), BigInteger.valueOf(end), Optional.empty(), 1, 1);
    }

    private final DetailedJournalReceiver oldest = receiver("jOLD", 10, 1_000, 1_999, JournalStatus.OnlineSavedDetached);
    private final DetailedJournalReceiver middle = receiver("jMID", 20, 2_000, 2_999, JournalStatus.OnlineSavedDetached);
    private final DetailedJournalReceiver newest = receiver("jNEW", 30, 3_000, 3_500, JournalStatus.Attached);

    @Test
    void findRangeRefusesToRestartFromTheOldestReceiverWhenTheOffsetIsZeroed() throws Exception {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100_000, journalInfo);

        when(journalInfoRetrieval.getReceivers(any(), any())).thenReturn(Arrays.asList(oldest, middle, newest));
        when(journalInfoRetrieval.getDelayedDetailedJournalReceiver(any(), any())).thenReturn(Optional.of(newest));

        // streaming on the newest receiver with the offset zeroed: resolving this gives the start of the
        // oldest retained receiver, which is the 71M entry rewind of issue #79
        final JournalProcessedPosition zeroed = new JournalProcessedPosition(BigInteger.ZERO,
                new JournalReceiver("jNEW", "jlib"), Instant.ofEpochSecond(0), true);

        assertTrue(jreceivers.findRange(as400, zeroed).isEmpty(), "the rewinding range must be refused");
        assertEquals(new JournalReceiver("jNEW", "jlib"), zeroed.getReceiver(),
                "the caller's position must be left where it was, or the next poll resolves the same range again");
        assertEquals(BigInteger.ZERO, zeroed.getOffset(), "the caller's offset must be left where it was");
    }

    @Test
    void findRangeRefusesARangeThatEndsBeforeItStarts() throws Exception {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100_000, journalInfo);

        when(journalInfoRetrieval.getReceivers(any(), any())).thenReturn(Arrays.asList(newest));
        when(journalInfoRetrieval.getDelayedDetailedJournalReceiver(any(), any())).thenReturn(Optional.of(newest));

        // caught up past the delayed head reading: it only knows about 3500, we have processed 3550
        final JournalProcessedPosition aheadOfTheDelayedHead = new JournalProcessedPosition(BigInteger.valueOf(3_550),
                new JournalReceiver("jNEW", "jlib"), Instant.ofEpochSecond(0), true);

        assertTrue(jreceivers.findRange(as400, aheadOfTheDelayedHead).isEmpty(), "the inverted range must be refused");
        assertEquals(BigInteger.valueOf(3_550), aheadOfTheDelayedHead.getOffset(), "the caller's position must be untouched");
    }

    @Test
    void findRangeRefusesTheRewindARollOntoAReceiverStartingAtZeroLeadsTo() throws Exception {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100_000, journalInfo);

        // freshly attached and reporting no entries yet, so it starts before the receiver it follows
        final DetailedJournalReceiver empty = receiver("jNEW", 30, 0, 0, JournalStatus.Attached);
        when(journalInfoRetrieval.getReceivers(any(), any())).thenReturn(Arrays.asList(oldest, middle, empty));
        when(journalInfoRetrieval.getDelayedDetailedJournalReceiver(any(), any())).thenReturn(Optional.of(empty));

        // caught up: exactly on the end of the previous receiver, processed
        final JournalProcessedPosition position = new JournalProcessedPosition(BigInteger.valueOf(2_999),
                new JournalReceiver("jMID", "jlib"), Instant.ofEpochSecond(0), true);

        // the roll itself moves the position onto the new receiver, which is forward and allowed - but the
        // receiver reports a start of zero, so it leaves a zeroed offset behind
        jreceivers.findRange(as400, position);
        assertEquals(new JournalReceiver("jNEW", "jlib"), position.getReceiver());
        assertEquals(BigInteger.ZERO, position.getOffset(), "the zeroed offset the rewind is resolved from");

        // the next poll is where issue #79 bites: a zeroed offset resolves to the oldest retained
        // receiver. Refusing it stalls the connector, loudly, instead of silently re-reading 9 hours
        assertTrue(jreceivers.findRange(as400, position).isEmpty(), "the rewinding range must be refused");
        assertEquals(new JournalReceiver("jNEW", "jlib"), position.getReceiver(), "position left as the refused call found it");
        assertEquals(BigInteger.ZERO, position.getOffset(), "position left as the refused call found it");
    }

    @Test
    void findRangeAllowsAnOrdinaryForwardRange() throws Exception {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100_000, journalInfo);

        when(journalInfoRetrieval.getReceivers(any(), any())).thenReturn(Arrays.asList(oldest, middle, newest));
        when(journalInfoRetrieval.getDelayedDetailedJournalReceiver(any(), any())).thenReturn(Optional.of(newest));

        // part way through the oldest receiver, catching up towards the head
        final JournalProcessedPosition catchingUp = new JournalProcessedPosition(BigInteger.valueOf(1_500),
                new JournalReceiver("jOLD", "jlib"), Instant.ofEpochSecond(0), true);

        final Optional<PositionRange> result = jreceivers.findRange(as400, catchingUp);
        assertEquals(new PositionRange(false, catchingUp,
                new JournalPosition(newest.end(), newest.info().receiver())), result.get());
    }

    @Test
    void findRangeAllowsARollOnAJournalThatResetsSequenceNumbers() throws Exception {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100_000, journalInfo);

        // SEQOPT(*RESET): every receiver restarts numbering at 1, which is the case on almost every
        // journal of the test system. The roll must not read as a rewind, or the guard stalls the
        // connector at every receiver change
        final DetailedJournalReceiver s1 = receiver("s1", 10, 1, 999, JournalStatus.OnlineSavedDetached);
        final DetailedJournalReceiver s2 = receiver("s2", 20, 1, 999, JournalStatus.OnlineSavedDetached);
        final DetailedJournalReceiver s3 = receiver("s3", 30, 1, 500, JournalStatus.Attached);
        when(journalInfoRetrieval.getReceivers(any(), any())).thenReturn(new ArrayList<>(Arrays.asList(s1, s2, s3)));
        when(journalInfoRetrieval.getDelayedDetailedJournalReceiver(any(), any())).thenReturn(Optional.of(s3));

        // caught up on the end of s1, so the roll moves the position onto s2, back down to sequence 1
        final JournalProcessedPosition position = new JournalProcessedPosition(BigInteger.valueOf(999),
                new JournalReceiver("s1", "jlib"), Instant.ofEpochSecond(0), true);

        final Optional<PositionRange> result = jreceivers.findRange(as400, position);
        assertEquals(new JournalReceiver("s2", "jlib"), result.get().start().getReceiver(), "moved on to the next receiver");
        assertEquals(BigInteger.ONE, result.get().start().getOffset(), "a lower sequence number, but forward in the chain");
    }

    @Test
    void findRangeAllowsAFreshStartFromTheEarliestReceiver() throws Exception {
        final ReceiverPagination jreceivers = new ReceiverPagination(journalInfoRetrieval, 100_000, journalInfo);

        when(journalInfoRetrieval.getReceivers(any(), any())).thenReturn(Arrays.asList(oldest, middle, newest));
        when(journalInfoRetrieval.getDelayedDetailedJournalReceiver(any(), any())).thenReturn(Optional.of(newest));

        // no offset at all: nothing has been dispatched, so the earliest receiver is the right answer
        final Optional<PositionRange> result = jreceivers.findRange(as400, new JournalProcessedPosition());

        assertEquals(oldest.start(), result.get().start().getOffset());
        assertEquals(oldest.info().receiver(), result.get().start().getReceiver());
    }

}
