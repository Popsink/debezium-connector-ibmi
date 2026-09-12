/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;

import io.debezium.ibmi.db2.journal.retrieve.exception.LostJournalException;
import io.debezium.ibmi.db2.journal.retrieve.rnrn0200.DetailedJournalReceiver;

public class ReceiverPagination {
    static final Logger log = LoggerFactory.getLogger(ReceiverPagination.class);

    private final JournalInfoRetrieval journalInfoRetrieval;
    private final JournalInfo journalInfo;
    private final BigInteger maxServerSideEntriesBI;
    private DetailedJournalReceiver cachedEndPosition;
    private List<DetailedJournalReceiver> cachedReceivers = null;
    /** why the last poll resolved no range, so a refusal that repeats is not logged again at the same level */
    private String refusal = null;
    /** consecutive polls the current refusal has held for */
    private int refusals = 0;
    /**
     * How many consecutive refusals stop being the transient disagreement between the live receiver list and
     * the deliberately delayed journal head. That one clears within the delay window - seconds - whereas a
     * refusal that outlives this many polls is a connector that has stopped advancing and has to be seen.
     */
    private static final int REFUSALS_BEFORE_ESCALATING = 10;

    ReceiverPagination(JournalInfoRetrieval journalInfoRetrieval, int maxServerSideEntries, JournalInfo journalInfo) {
        this.journalInfoRetrieval = journalInfoRetrieval;
        maxServerSideEntriesBI = BigInteger.valueOf(maxServerSideEntries);
        this.journalInfo = journalInfo;
    }

    Optional<PositionRange> findRange(AS400 as400, JournalProcessedPosition startPosition) throws Exception {
        final Optional<DetailedJournalReceiver> endPositionOpt = journalInfoRetrieval.getDelayedDetailedJournalReceiver(as400, journalInfo);
        if (endPositionOpt.isPresent()) {
            return Optional.ofNullable(_findRange(as400, startPosition, endPositionOpt.get()));
        }
        return Optional.empty();
    }

    /**
     * Resolves the range for this poll, or {@code null} when {@link #resolveRange} would not resolve one.
     *
     * <p>The range is used as resolved: it is not compared against the position it was given, so a range
     * that starts before that position, or one that ends before its own start, is returned like any
     * other. The refusals that remain are the ones raised where the range is built, and they leave the
     * caller's position alone; {@link #refuse} reports them once when they start, and at ERROR if they
     * do not clear.</p>
     */
    PositionRange _findRange(AS400 as400, JournalProcessedPosition startPosition, DetailedJournalReceiver endPosition) throws Exception {
        final PositionRange range = resolveRange(as400, startPosition, endPosition);
        if (range == null) {
            // resolveRange refused it and said why; it leaves the caller's position alone
            return null;
        }
        resolved();
        return range;
    }

    /**
     * Reports a range we would not use, once per occurrence rather than once per poll.
     *
     * <p>The inversion this guards against recurs at every receiver roll for as long as the delayed journal
     * head takes to catch up, so on a journal that rolls every few minutes logging it at ERROR on every poll
     * buries the genuine cases (issue #79). The first poll of a refusal is a WARN carrying the detail, the
     * ones after it are DEBUG, and a refusal that holds for {@value #REFUSALS_BEFORE_ESCALATING} polls is an
     * ERROR: at that point streaming has stopped advancing and it is no longer a known transient.</p>
     */
    private void refuse(String reason, Supplier<String> detail) {
        if (!reason.equals(refusal)) {
            refusal = reason;
            refusals = 1;
            log.warn("REFUSING A RANGE: {}. No call is made this poll, the position is left where it is and the "
                    + "range is resolved again next poll. Detail: {}", reason, detail.get());
            return;
        }
        refusals++;
        if (refusals % REFUSALS_BEFORE_ESCALATING == 0) {
            log.error("REFUSING A RANGE: {}, for {} polls running - streaming is not advancing, this is no longer "
                    + "the transient disagreement between the receiver list and the delayed journal head. Detail: {}",
                    reason, refusals, detail.get());
            return;
        }
        log.debug("still refusing a range: {} ({} polls)", reason, refusals);
    }

    /** Called when a range is resolved, so the next refusal is reported as a new occurrence. */
    private void resolved() {
        if (refusal != null) {
            log.info("resolving ranges again after {} refused poll(s): {}", refusals, refusal);
            refusal = null;
            refusals = 0;
        }
    }

    private PositionRange resolveRange(AS400 as400, JournalProcessedPosition startPosition, DetailedJournalReceiver endPosition) throws Exception {
        final BigInteger start = startPosition.getOffset();
        final boolean hasReceiver = startPosition.getReceiver() != null && startPosition.getReceiver().name() != null;
        // only a position with nothing in it is a fresh start. An explicit zero offset that names a receiver is
        // not: it is a position we were streaming from with its offset lost, and resolving it to the earliest
        // retained receiver is the rewind of issue #79 - tens of millions of entries re-read, silently. Refuse
        // it instead, so it shows up as a connector that has stopped rather than one that has started over
        if (startPosition.isOffsetSet() && start.signum() == 0 && hasReceiver) {
            final JournalProcessedPosition zeroed = new JournalProcessedPosition(startPosition);
            refuse("the position has a zero offset on receiver " + startPosition.getReceiver().name(),
                    () -> String.format("position %s carries a receiver but no usable offset; resolving it would "
                            + "restart from the earliest retained receiver of %s", zeroed, cachedReceivers));
            return null;
        }
        // a zero offset with no receiver at all is the blank position an unset offset also produces
        final boolean fromBeginning = !startPosition.isOffsetSet() || (start.signum() == 0 && !hasReceiver);

        if (cachedEndPosition == null) {
            cachedEndPosition = endPosition;
        }

        if (cachedReceivers == null || fromBeginning) {
            // the cached list is only refreshed when the attached receiver changes, so starting over would
            // otherwise resume from a receiver that has since been deleted and lose the journal again
            cachedReceivers = journalInfoRetrieval.getReceivers(as400, journalInfo);
        }

        if (fromBeginning) {
            DetailedJournalReceiver first = cachedReceivers.get(0);
            // every path below that resolves a concrete range returns fromBeginning=false, so
            // ParameterListBuilder's own "starting from beginning" warning never fires for them: say it
            // here instead, where it is true regardless of what the range ends up being (issue #79)
            log.warn("no usable offset in position {}, streaming will resume from the earliest retained receiver {}",
                    startPosition, first);
            startPosition = new JournalProcessedPosition(first.start(), first.info().receiver(), Instant.EPOCH, false);
        }

        if (cachedEndPosition.isSameReceiver(endPosition)) {
            // refresh end position in cached list
            updateEndPosition(cachedReceivers, endPosition);
            // we're currently on the same journal just check the relative offset is within range
            // don't update the cache as we are not going to know the real end offset for this journal receiver until we move on to the next
            if (startPosition.isSameReceiver(endPosition)) {
                return paginateInSameReceiver(startPosition, endPosition, maxServerSideEntriesBI);
            }
        }
        else {
            // last call to current position won't include the correct end offset so we need to refresh the list
            cachedReceivers = journalInfoRetrieval.getReceivers(as400, journalInfo);
            cachedEndPosition = endPosition;
        }

        // the delayed head, not the cached reading of it: it is the furthest we are allowed to read to
        // (issue #79)
        Optional<PositionRange> endOpt = findPosition(startPosition, maxServerSideEntriesBI, cachedReceivers,
                endPosition);
        if (endOpt.isEmpty()) {
            log.warn("retrying to find end offset");
            cachedReceivers = journalInfoRetrieval.getReceivers(as400, journalInfo);
            // this list is live, so it is clamped to the delayed head before anything is resolved from it
            updateEndPosition(cachedReceivers, endPosition);
            endOpt = findPosition(startPosition, maxServerSideEntriesBI, cachedReceivers, endPosition);
            if (endOpt.isEmpty()) {
                // our position isn't in the journal's receivers any more, e.g. the receivers were deleted
                throw new LostJournalException("unable to find receiver " + startPosition + " in " + cachedReceivers);
            }
        }

        log.debug("end {} journals {}", endPosition, cachedReceivers);

        final JournalProcessedPosition startf = new JournalProcessedPosition(startPosition);
        return endOpt.orElseGet(
                () -> new PositionRange(fromBeginning, startf,
                        new JournalPosition(endPosition.end(), endPosition.info().receiver())));
    }

    static void updateEndPosition(List<DetailedJournalReceiver> list, DetailedJournalReceiver endPosition) {
        // should be last entry
        for (int i = list.size() - 1; i >= 0; i--) {
            final DetailedJournalReceiver d = list.get(i);
            if (d.isSameReceiver(endPosition)) {
                list.set(i, endPosition);
                return;
            }
        }
        list.add(endPosition);
    }

    /**
     * only valid when startPosition and endJournalPosition are the same receiver and library
     * @param startPosition
     * @param endJournalPosition
     * @param maxServerSideEntriesBI
     * @return the range to read, or {@code null} when there is nothing to read because the journal head is
     *         behind the position
     * @throws Exception
     */
    PositionRange paginateInSameReceiver(JournalProcessedPosition startPosition, DetailedJournalReceiver endJournalPosition, BigInteger maxServerSideEntriesBI)
            throws Exception {
        if (!startPosition.isSameReceiver(endJournalPosition)) {
            throw new Exception(String.format("Error this method is only valid for same receiver start %s, end %s", startPosition, endJournalPosition));
        }
        final BigInteger diff = endJournalPosition.end().subtract(startPosition.getOffset());
        if (diff.signum() < 0) {
            // the head we are allowed to read up to is behind the position we have already dispatched, which
            // happens for as long as the delayed head takes to catch up with a position committed just after a
            // receiver roll. There is nothing to read, and asking the server for an inverted range earns a
            // CPF7054 that used to be read as a pruned journal and reset the offset (issue #79)
            refuse("the delayed journal head is behind the position on receiver " + endJournalPosition.info().receiver().name(),
                    () -> String.format("position %s is %s entries past the journal head reading %s, nothing to read "
                            + "until the head catches up", startPosition, diff.negate(), endJournalPosition));
            return null;
        }
        if (diff.compareTo(maxServerSideEntriesBI) > 0) {
            final BigInteger restricted = startPosition.getOffset().add(maxServerSideEntriesBI);
            return new PositionRange(false, startPosition,
                    new JournalPosition(restricted, startPosition.getReceiver()));
        }
        return new PositionRange(false, startPosition,
                new JournalPosition(endJournalPosition.end(), startPosition.getReceiver()));
    }

    /**
     * should handle reset offset numbers between subsequent entries in the list
     * @param startPosition
     * @param maxEntries
     * @param receivers
     * @return try and find end position at most offsetFromStart from start using the receiver list
     */
    Optional<PositionRange> findPosition(JournalProcessedPosition startPosition, BigInteger maxEntries,
                                         List<DetailedJournalReceiver> receivers, DetailedJournalReceiver endPosition) {

        if (!containsEndPosition(receivers, endPosition)) {
            log.warn("unable to find active journal {} in receiver list", endPosition);
            return Optional.empty();
        }

        final RangeFinder finder = new RangeFinder(startPosition, maxEntries);
        for (int i = 0; i < receivers.size(); i++) {
            final Optional<PositionRange> range = finder.next(receivers.get(i));
            if (range.isPresent()) {
                return range;
            }
        }
        final Optional<PositionRange> range = finder.endRange();
        if (!finder.startFound()) {
            log.warn("Current position {} not found in available receivers {}", startPosition, receivers);
        }
        return range;
    }

    boolean containsEndPosition(List<DetailedJournalReceiver> receivers, DetailedJournalReceiver endPosition) {
        boolean containsEndPosition = false;
        for (int i = receivers.size() - 1; i >= 0; i--) {
            if (receivers.get(i).info().receiver().equals(endPosition.info().receiver())) {
                containsEndPosition = true;
            }
        }
        return containsEndPosition;
    }

    static class RangeFinder {
        private boolean found = false;
        private DetailedJournalReceiver lastReceiver = null;
        private BigInteger remaining;
        private final JournalProcessedPosition startPosition;

        RangeFinder(JournalProcessedPosition startPosition, BigInteger maxEntries) {
            this.remaining = maxEntries;
            this.startPosition = startPosition;
        }

        public Optional<PositionRange> next(DetailedJournalReceiver nextReceiver) {
            if (found) {
                // if the next journal has wrapped use just go to the end of the previous one
                if (lastReceiver != null && nextReceiver.start().compareTo(lastReceiver.end()) < 0) {
                    // we're at the end and we've processed it move start on to next receiver
                    if (startEqualsEndAndProcessed(startPosition, lastReceiver)) {
                        // this is the caller's position, i.e. the connector's committed offset, being moved
                        // from inside range resolution: worth a line, it is the only place that happens
                        log.info("receiver {} starts at {}, before the end {} of {}: sequence numbers were reset, "
                                + "moving position {} on to the start of the new receiver",
                                nextReceiver.info().receiver(), nextReceiver.start(), lastReceiver.end(),
                                lastReceiver.info().receiver(), startPosition);
                        startPosition.setPosition(new JournalPosition(nextReceiver.start(), nextReceiver.info().receiver()), false);
                    }
                    else {
                        // the only way we can get here is if we have already checked for pagination
                        return Optional.of(new PositionRange(false, startPosition,
                                new JournalPosition(lastReceiver.end(), lastReceiver.info().receiver())));
                    }
                }

                final Optional<PositionRange> r = rangeWithinCurrentPosition(nextReceiver, nextReceiver.start());
                lastReceiver = nextReceiver;
                return r;
            }
            else {
                if (nextReceiver.isSameReceiver(startPosition)) {
                    found = true;
                    final Optional<PositionRange> r = rangeWithinCurrentPosition(nextReceiver, startPosition.getOffset());
                    lastReceiver = nextReceiver;
                    return r;
                }
            }
            lastReceiver = nextReceiver;
            return Optional.empty();
        }

        // adding one to the range and then adding as we include both ends
        // but we must not use the add one when setting the end point
        // i.e. 1-> 10 is a total of 10 entries but the range can only go to 10
        private Optional<PositionRange> rangeWithinCurrentPosition(DetailedJournalReceiver nextReceiver,
                                                                   BigInteger currentOffset) {
            final BigInteger difference = nextReceiver.end().subtract(currentOffset);
            final BigInteger entriesInJournal = difference.add(BigInteger.ONE); // add one as range is inclusive
            if (remaining.compareTo(difference) <= 0) { // range is inclusive but don't go past end when adding
                // remaining
                final BigInteger offset = currentOffset.add(remaining);
                return Optional.of(new PositionRange(false, startPosition,
                        new JournalPosition(offset, nextReceiver.info().receiver())));
            }
            remaining = remaining.subtract(entriesInJournal);
            return Optional.empty();
        }

        public Optional<PositionRange> endRange() {
            if (found && lastReceiver != null) {
                return Optional.of(
                        new PositionRange(false, startPosition, JournalPosition.endPosition(lastReceiver)));
            }
            return Optional.empty();
        }

        public boolean startFound() {
            return found;
        }

        private boolean startEqualsEndAndProcessed(JournalProcessedPosition start, DetailedJournalReceiver last) {
            return start.processed() && start.getOffset().equals(last.end());
        }
    }
}
