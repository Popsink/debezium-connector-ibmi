/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.retrieve.exception;

/**
 * CPF7007: a journaled member the call needs is allocated by another job, typically a nightly reorganise,
 * clear or save. Transient by definition - IBM's recovery text is "try the request again when the member
 * is available" - so it is not fatal, and is retried far longer than an unknown failure (issue #107).
 */
public class ObjectLockedException extends Exception {
    private static final long serialVersionUID = 1L;

    /** @param message the description of the failed call, with the server's text naming the member, file and library */
    public ObjectLockedException(String message) {
        super(message);
    }
}
