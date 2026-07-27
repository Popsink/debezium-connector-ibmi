/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.data.types;

import java.sql.Connection;
import java.sql.SQLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400;
import com.ibm.as400.access.AS400DataType;
import com.ibm.as400.access.AS400JDBCConnection;
import com.ibm.as400.access.AS400Text;
import com.ibm.as400.access.CharacterFieldDescription;

import io.debezium.ibmi.db2.journal.retrieve.Connect;

/**
 * Creates every CCSID sensitive {@link AS400DataType} against the CCSID of the remote IBM i, rather
 * than one guessed from the locale of the JVM running the connector.
 *
 * <p>
 * jt400's {@code new AS400Text(length)} constructor is not told which system the bytes belong to, so
 * it falls back to {@code ExecutionEnvironment.getBestGuessAS400Ccsid()} - a guess derived from the
 * <em>local</em> default locale. Whenever the connector's locale differs from the IBM i it reads (a
 * container defaulting to en_US against a French or Japanese system, say) that guess picks the wrong
 * EBCDIC page: journal API parameters such as {@code *FIRST} or a qualified journal name are encoded
 * with bytes the server does not recognise, and the journal headers and record images that come back
 * decode to mojibake.
 * </p>
 *
 * <p>
 * The CCSID used here is the one the system itself reports at sign-on ({@link AS400#getCcsid()}), so
 * it follows the remote machine and not the process.
 * </p>
 */
public class As400TextFactory {
    private static final Logger log = LoggerFactory.getLogger(As400TextFactory.class);

    /**
     * jt400 and IBM i both use 65535 to mean "no CCSID / no translation". Passed to
     * {@link AS400Text} it re-enables the default locale guess, so it is treated the same as an
     * unknown CCSID.
     */
    public static final int NO_CCSID = 65535;

    /** Sentinel for "CCSID unknown", used by {@code qsys2.syscolumns} lookups that find nothing. */
    public static final int UNKNOWN_CCSID = -1;

    private final int systemCcsid;

    private As400TextFactory(int systemCcsid) {
        this.systemCcsid = systemCcsid;
    }

    /**
     * Binds to the CCSID the remote system reported when signing on. {@link AS400#getCcsid()} never
     * throws: it connects if it has to and only falls back to the default locale guess when the
     * system cannot be reached at all.
     */
    public static As400TextFactory forSystem(AS400 system) {
        final int ccsid = system.getCcsid();
        log.info("using remote system ccsid {} for character conversion", ccsid);
        return forCcsid(ccsid);
    }

    /**
     * Binds to the CCSID of the system behind a jt400 JDBC connection, which owns its own
     * {@link AS400}. Used where the connector holds a JDBC connection but not yet an RPC one; both
     * resolve to the same system so both see the same CCSID.
     */
    public static As400TextFactory forConnection(Connect<Connection, SQLException> jdbcConnect) {
        try {
            final AS400JDBCConnection connection = jdbcConnect.connection().unwrap(AS400JDBCConnection.class);
            return forSystem(connection.getSystem());
        }
        catch (final Exception e) {
            log.error("unable to determine the remote system ccsid from the jdbc connection, character "
                    + "conversion will fall back to guessing one from the local locale", e);
            return localeDefault();
        }
    }

    public static As400TextFactory forCcsid(int ccsid) {
        return new As400TextFactory(normalise(ccsid));
    }

    /**
     * Leaves the CCSID to jt400, which guesses it from the local default locale. Only correct when
     * the connector and the IBM i share a locale - reserved for tests and for the case where the
     * system CCSID could not be retrieved.
     */
    public static As400TextFactory localeDefault() {
        return new As400TextFactory(UNKNOWN_CCSID);
    }

    /** @return the remote system CCSID, or {@link #UNKNOWN_CCSID} when it could not be resolved */
    public int systemCcsid() {
        return systemCcsid;
    }

    /**
     * Fixed length text in the system CCSID - for the IBM i API parameters and headers this library
     * encodes and decodes, which are always in the CCSID of the job serving the call.
     */
    public AS400Text text(int length) {
        return text(length, UNKNOWN_CCSID);
    }

    /**
     * Fixed length text in a column's own CCSID, falling back to the system CCSID when the column
     * has none. A column tagged 65535 holds untranslated bytes; reading those as the system CCSID is
     * at least stable across hosts, where the locale guess is not.
     */
    public AS400Text text(int length, int columnCcsid) {
        final int ccsid = resolve(columnCcsid);
        return (ccsid == UNKNOWN_CCSID) ? new AS400Text(length) : new AS400Text(length, ccsid);
    }

    /** Variable length text, see {@link #text(int, int)} for how the CCSID is chosen. */
    public AS400VarChar varChar(int maxLength, int bytesPerChar, int columnCcsid) {
        return new AS400VarChar(maxLength, bytesPerChar, resolve(columnCcsid));
    }

    /** Convenience for the record format descriptions the {@code rjne0200}/{@code rnrn0200} decoders build. */
    public CharacterFieldDescription charField(int length, String name) {
        return new CharacterFieldDescription(text(length), name);
    }

    /** Decodes {@code length} bytes of system CCSID text starting at {@code offset}. */
    public String decode(byte[] data, int offset, int length) {
        return (String) text(length).toObject(data, offset);
    }

    private int resolve(int columnCcsid) {
        final int normalised = normalise(columnCcsid);
        return (normalised == UNKNOWN_CCSID) ? systemCcsid : normalised;
    }

    private static int normalise(int ccsid) {
        return (ccsid <= 0 || ccsid == NO_CCSID) ? UNKNOWN_CCSID : ccsid;
    }

    @Override
    public String toString() {
        return String.format("As400TextFactory [systemCcsid=%d]", systemCcsid);
    }
}
