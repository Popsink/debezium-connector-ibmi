/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import java.io.IOException;
import java.sql.SQLNonTransientConnectionException;
import java.util.Set;

import com.ibm.as400.access.AS400SecurityException;

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
 * Two failures are never retried, whatever their cause chain holds. A {@link NotRetriableException}: the
 * streaming loop has already retried the failure for as long as it is worth it. And a rejected sign-on
 * ({@link AS400SecurityException}: wrong or expired password, disabled or unknown profile, missing authority),
 * which reconnecting reports as an {@link IOException}: no retry can fix it, and every attempt is one more
 * failed sign-on counted towards the system's limit ({@code QMAXSIGN}), which can disable the profile.
 */
public class As400ErrorHandler extends ErrorHandler {

    public As400ErrorHandler(As400ConnectorConfig connectorConfig, ChangeEventQueue<?> queue, ErrorHandler replacedErrorHandler) {
        super(As400RpcConnector.class, connectorConfig, queue, replacedErrorHandler);
    }

    @Override
    protected boolean isRetriable(Throwable throwable) {
        if (causedBy(throwable, NotRetriableException.class) != null || causedBy(throwable, AS400SecurityException.class) != null) {
            return false;
        }
        return super.isRetriable(throwable);
    }

    /** The first throwable of {@code type} in the cause chain of {@code throwable}, or null. */
    static Throwable causedBy(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return t;
            }
        }
        return null;
    }

    @Override
    protected Set<Class<? extends Exception>> communicationExceptions() {
        return Collect.unmodifiableSet(IOException.class, SQLNonTransientConnectionException.class);
    }
}
