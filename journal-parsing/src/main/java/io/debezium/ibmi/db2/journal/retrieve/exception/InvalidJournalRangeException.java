/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve.exception;

/**
 * The server rejected the range we asked for, i.e. CPF7054 - "last < first, or an offset that does not
 * belong to the journal".
 *
 * <p>This is a <em>malformed request</em>, not a pruned receiver: the journal is still there and the
 * position is very probably still readable. It is kept apart from {@link LostJournalException} because
 * the two call for opposite reactions - a lost position has to be recovered from, whereas a bad range
 * has to be recalculated and retried, and self-heals as soon as the delayed journal head catches up with
 * the position (issue #79). Conflating them is what turned a transient inversion at a receiver roll into
 * an offset reset to the earliest retained receiver.</p>
 *
 * <p>An offset that really does not belong to the journal - a journal deleted and recreated under us -
 * reports the same message id, so a caller that sees this repeatedly must check whether the position is
 * still in the receiver list rather than assume either answer.</p>
 */
public class InvalidJournalRangeException extends Exception {

    public InvalidJournalRangeException(String message) {
        super(message);
    }

    public InvalidJournalRangeException(String message, Throwable cause) {
        super(message, cause);
    }
}
