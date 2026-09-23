/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.data.types;

import java.awt.event.KeyEvent;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ibm.as400.access.AS400Text;

public class Diagnostics {
    private static final Logger log = LoggerFactory.getLogger(Diagnostics.class);

    public static boolean isPrintableChar(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return (!Character.isISOControl(c)) && c != KeyEvent.CHAR_UNDEFINED && block != null
                && block != Character.UnicodeBlock.SPECIALS;
    }

    public static void dumpToFile(String filename, byte[] data, int offset, int length) throws IOException {
        byte[] bdata = Arrays.copyOfRange(data, offset, offset + length);
        try (FileOutputStream fos = new FileOutputStream(filename)) {
            fos.write(bdata);
        }
    }

    /** Cap on the record image bytes {@link #hex} puts in a log line. */
    public static final int MAX_LOGGED_RECORD_BYTES = 4096;

    /** Contiguous lowercase hex of {@code data[offset, offset + length)}, at most {@code maxBytes} bytes. Clamps, never throws. */
    public static String hex(byte[] data, int offset, int length, int maxBytes) {
        if (data == null || length <= 0 || offset < 0 || offset >= data.length) {
            return "";
        }
        final int available = Math.min(length, data.length - offset);
        final int shown = Math.min(available, Math.max(0, maxBytes));
        final StringBuilder sb = new StringBuilder(shown * 2 + 40);
        for (int i = 0; i < shown; i++) {
            sb.append(Character.forDigit((data[offset + i] >> 4) & 0xf, 16));
            sb.append(Character.forDigit(data[offset + i] & 0xf, 16));
        }
        if (shown < available) {
            sb.append(" ...(truncated, ").append(available).append(" bytes total)");
        }
        return sb.toString();
    }

    public static String binAsHex(byte[] data, int offset, int length) {
        // byte[] bdata = Arrays.copyOfRange(data, offset, offset + length);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%04d: ", (offset)));
        for (int j = 0; j < length; j++) {
            byte b = data[j + offset];
            sb.append(String.format("%02x ", b));
            if ((j + 1) % 80 == 0) {
                sb.append("\n");
                sb.append(String.format("%04d: ", (j + offset)));
            }
        }
        return sb.toString();
    }

    public static String binAsAscii(byte[] data, int offset, int length) {
        byte[] bdata = Arrays.copyOfRange(data, offset, offset + length);
        StringBuilder sb = new StringBuilder("\n");
        int i = 0;
        for (byte b : bdata) {
            char c = (char) b;
            if (isPrintableChar(c)) {
                sb.append(c);
            }
            else {
                sb.append(".");
            }
            if (++i % 80 == 0) {
                sb.append("\n");
            }
        }
        sb.append("\n");
        return sb.toString();
    }

    public static String binAsEbcdic(As400TextFactory textFactory, byte[] data, int offset, int length) {
        // byte[] bdata = Arrays.copyOfRange(data, offset, offset + length);
        StringBuilder sb = new StringBuilder();
        AS400Text td = textFactory.text(1);
        sb.append(String.format("%04d: ", (offset)));
        for (int j = 0; j < length; j++) {
            String s = (String) td.toObject(data, j + offset);
            if (isPrintableChar(s.charAt(0))) {
                sb.append(s);
            }
            else {
                sb.append(".");
            }
            if ((j + 1) % 80 == 0) {
                sb.append("\n");
                sb.append(String.format("%04d: ", (j + offset)));
            }
        }
        sb.append("\n");
        return sb.toString();
    }
}
