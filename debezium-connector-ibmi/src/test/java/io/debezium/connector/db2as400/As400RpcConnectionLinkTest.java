/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package io.debezium.connector.db2as400;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class As400RpcConnectionLinkTest {

    @Test
    void ratesTheTransferNetOfTheRoundTrip() {
        // 8 MiB in 30 s with a 200 ms round trip: 280 KB/s, and 64 KiB in flight - a stock IBM i send buffer
        assertEquals("link rate 274 KB/s, 54 KB in flight per round trip", As400RpcConnection.describeLink(8 << 20, 30_000, 200));
        // 1 MiB in 490 ms with a 30 ms round trip, what PUB400 does from a good link
        assertEquals("link rate 2226 KB/s, 66 KB in flight per round trip", As400RpcConnection.describeLink(1 << 20, 490, 30));
    }

    @Test
    void nothingToSayWithoutABlockOrWhenTheSmallCallWasNotSmaller() {
        assertEquals("link rate n/a", As400RpcConnection.describeLink(0, 30_000, 200));
        assertEquals("link rate n/a", As400RpcConnection.describeLink(8 << 20, 150, 200), "the block cannot have transferred in negative time");
        assertEquals("link rate n/a", As400RpcConnection.describeLink(8 << 20, 200, 200));
    }
}
