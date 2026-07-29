/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve.exception;

/**
 * Raised when a journal receiver referenced by a stored position no longer exists on the server
 * (it has been rotated/pruned or deleted on the IBM i). This is a <em>non-transient</em> condition: the
 * receiver will never come back, so callers must not treat it as a retriable error. It is kept
 * distinct from a transient RPC/connection failure so that recovery can react deterministically
 * instead of crash-looping.
 * <p>
 * A missing receiver is one way of losing the journal, hence a {@link LostJournalException}: callers that
 * only care that the position is gone can catch that one exception.
 */
public class JournalReceiverNotFoundException extends LostJournalException {

    /** IBM i message id that identified the object-not-found condition (may be {@code null}). */
    private final String messageId;

    public JournalReceiverNotFoundException(String message) {
        this(message, null);
    }

    public JournalReceiverNotFoundException(String message, String messageId) {
        super(message);
        this.messageId = messageId;
    }

    public String getMessageId() {
        return messageId;
    }
}
