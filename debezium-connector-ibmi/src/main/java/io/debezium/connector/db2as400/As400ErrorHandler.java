/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.io.IOException;
import java.sql.SQLNonTransientConnectionException;
import java.util.Set;

import io.debezium.connector.base.ChangeEventQueue;
import io.debezium.pipeline.ErrorHandler;
import io.debezium.util.Collect;

/**
 * Error handler for IBM i.
 * <p>
 * The base handler only treats {@link IOException} as a lost connection, but the jt400 JDBC driver reports
 * a dropped connection as a {@link SQLNonTransientConnectionException}. Without it a connection failure
 * outside the streaming loop - starting the task, a snapshot - stops the task instead of restarting it, so
 * a routine network or IBM i outage would need operator action to recover from.
 * <p>
 * A {@link NotRetriableException} is never retried, whatever its cause chain holds: the streaming loop
 * throws it once it has already retried a failure for as long as that failure is worth it, or knows no
 * retry can fix it. Restarting the task in place would run the same retries again on top of the loop's.
 */
public class As400ErrorHandler extends ErrorHandler {

    public As400ErrorHandler(As400ConnectorConfig connectorConfig, ChangeEventQueue<?> queue, ErrorHandler replacedErrorHandler) {
        super(As400RpcConnector.class, connectorConfig, queue, replacedErrorHandler);
    }

    @Override
    protected boolean isRetriable(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof NotRetriableException) {
                return false;
            }
        }
        return super.isRetriable(throwable);
    }

    @Override
    protected Set<Class<? extends Exception>> communicationExceptions() {
        return Collect.unmodifiableSet(IOException.class, SQLNonTransientConnectionException.class);
    }
}
