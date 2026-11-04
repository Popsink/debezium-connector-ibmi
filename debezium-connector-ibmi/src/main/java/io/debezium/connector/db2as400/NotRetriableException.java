/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import io.debezium.DebeziumException;

/**
 * The streaming loop has stopped retrying: either a failure outlived its time budget, or retrying cannot
 * fix it at all (a rejected sign-on). {@link As400ErrorHandler} never retries it, even when a connection
 * failure is somewhere in its cause chain, so Debezium's own in-place restarts do not stack on top of the
 * retries the loop has already made: the task stops and the orchestrator decides what happens next.
 */
public class NotRetriableException extends DebeziumException {
    private static final long serialVersionUID = 1L;

    public NotRetriableException(String message, Throwable cause) {
        super(message, cause);
    }
}
