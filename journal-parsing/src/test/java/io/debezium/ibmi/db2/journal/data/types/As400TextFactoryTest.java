/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.ibmi.db2.journal.data.types;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.ibm.as400.access.AS400Text;
import com.ibm.as400.access.CharacterFieldDescription;

class As400TextFactoryTest {

    private static final int EBCDIC_US = 37;
    private static final int EBCDIC_INTERNATIONAL = 500;

    @Test
    void apiTextUsesTheSystemCcsid() {
        final As400TextFactory factory = As400TextFactory.forCcsid(EBCDIC_US);

        assertEquals(EBCDIC_US, factory.text(10).getCcsid());
        assertEquals(EBCDIC_US, factory.systemCcsid());
    }

    @Test
    void columnCcsidWinsOverTheSystemCcsid() {
        final As400TextFactory factory = As400TextFactory.forCcsid(EBCDIC_US);

        assertEquals(EBCDIC_INTERNATIONAL, factory.text(10, EBCDIC_INTERNATIONAL).getCcsid());
    }

    /**
     * A column the catalogue has no CCSID for (or one tagged 65535, which jt400 reads as "guess from
     * the default locale") must still decode against the system, not the JVM's locale.
     */
    @Test
    void unknownColumnCcsidFallsBackToTheSystemCcsid() {
        final As400TextFactory factory = As400TextFactory.forCcsid(EBCDIC_US);

        assertEquals(EBCDIC_US, factory.text(10, As400TextFactory.UNKNOWN_CCSID).getCcsid());
        assertEquals(EBCDIC_US, factory.text(10, 0).getCcsid());
        assertEquals(EBCDIC_US, factory.text(10, As400TextFactory.NO_CCSID).getCcsid());
    }

    @Test
    void charFieldCarriesTheSystemCcsid() {
        final CharacterFieldDescription field = As400TextFactory.forCcsid(EBCDIC_US).charField(4, "a field");

        assertEquals("a field", field.getFieldName());
        assertEquals(EBCDIC_US, ((AS400Text) field.getDataType()).getCcsid());
    }

    /**
     * The whole point of threading the CCSID through: the same bytes mean different things under
     * different code pages, so picking the page from the local locale rather than the remote system
     * silently corrupts text. 0x4F is {@code |} in CCSID 37 but {@code !} in CCSID 500.
     */
    @Test
    void differentCcsidsDecodeTheSameBytesDifferently() {
        final byte[] data = new byte[]{ (byte) 0x4F };

        final String asUs = As400TextFactory.forCcsid(EBCDIC_US).decode(data, 0, 1);
        final String asInternational = As400TextFactory.forCcsid(EBCDIC_INTERNATIONAL).decode(data, 0, 1);

        assertEquals("|", asUs);
        assertEquals("!", asInternational);
        assertNotEquals(asUs, asInternational);
    }

    @Test
    void varCharIsBoundToTheResolvedCcsid() {
        final As400TextFactory factory = As400TextFactory.forCcsid(EBCDIC_US);
        // [2 byte length prefix][max 5 chars x 2 bytes]
        final AS400VarChar varChar = factory.varChar(5, 2, As400TextFactory.UNKNOWN_CCSID);

        assertEquals(12, varChar.getByteLength());
    }

    /** An untagged column decodes as the system CCSID's superset (Popsink/data-plane#5831). */
    @Test
    void untaggedColumnUsesTheSystemCcsidsSuperset() {
        final As400TextFactory chinese = As400TextFactory.forCcsid(935);

        assertEquals(1388, chinese.text(10, As400TextFactory.NO_CCSID).getCcsid());
        assertEquals(935, chinese.text(10, As400TextFactory.UNKNOWN_CCSID).getCcsid(),
                "a column with no CCSID at all keeps the system CCSID");
        assertEquals(935, chinese.text(10).getCcsid(), "API text stays in the job's CCSID");
    }

    /** 1371 encodes the euro sign, which 937 itself reads back as the currency sign. */
    @Test
    void supersetDecodesWhatTheSystemCcsidCannot() {
        final byte[] euro = new AS400Text(1, 1371).toBytes("€");

        assertEquals("€", As400TextFactory.forCcsid(937).text(1, As400TextFactory.NO_CCSID).toObject(euro));
        assertNotEquals("€", new AS400Text(1, 937).toObject(euro));
    }

    /** The table the data-plane loaders pin in {@code docs/ibmi-untagged-ccsid.tsv}: change both or neither. */
    @Test
    void supersetIsTheTableTheBackfillLoadersUse() {
        assertEquals(Map.of(935, 1388, 836, 1388, 837, 1388, 937, 1371, 933, 1364,
                930, 1390, 5026, 1390, 939, 1399, 5035, 1399), UntaggedCcsid.SUPERSET);
    }

    @Test
    void localeDefaultLeavesTheCcsidToJt400() {
        assertEquals(As400TextFactory.UNKNOWN_CCSID, As400TextFactory.localeDefault().systemCcsid());
    }
}
