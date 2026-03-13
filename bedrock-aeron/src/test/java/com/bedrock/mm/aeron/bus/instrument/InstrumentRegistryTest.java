package com.bedrock.mm.aeron.bus.instrument;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentRegistryTest {

    private InstrumentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new InstrumentRegistry();
    }

    @Test
    void testRegisterInstrument() {
        String instrumentId = "binance:BTC-USDT";
        int base = registry.registerInstrument(instrumentId);

        assertEquals(1000, base, "First instrument should get base 1000");
        assertTrue(registry.isRegistered(instrumentId));
    }

    @Test
    void testRegisterMultipleInstruments() {
        String btc = "binance:BTC-USDT";
        String eth = "binance:ETH-USDT";

        int btcBase = registry.registerInstrument(btc);
        int ethBase = registry.registerInstrument(eth);

        assertEquals(1000, btcBase);
        assertEquals(11000, ethBase, "Second instrument should get base 11000 (1000 + 10000)");

        assertTrue(registry.isRegistered(btc));
        assertTrue(registry.isRegistered(eth));
    }

    @Test
    void testIdempotentRegistration() {
        String instrumentId = "binance:BTC-USDT";

        int base1 = registry.registerInstrument(instrumentId);
        int base2 = registry.registerInstrument(instrumentId);

        assertEquals(base1, base2, "Re-registering should return same base");
    }

    @Test
    void testGetStreamId() {
        String instrumentId = "binance:BTC-USDT";
        registry.registerInstrument(instrumentId);

        // mds.bbo → offset 1 → 1001
        int bboStream = registry.getStreamId(instrumentId, "mds.bbo");
        assertEquals(1001, bboStream);

        // mds.depth → offset 2 → 1002
        int depthStream = registry.getStreamId(instrumentId, "mds.depth");
        assertEquals(1002, depthStream);

        // mds.trade → offset 3 → 1003
        int tradeStream = registry.getStreamId(instrumentId, "mds.trade");
        assertEquals(1003, tradeStream);
    }

    @Test
    void testGetStreamIdForSecondInstrument() {
        String btc = "binance:BTC-USDT";
        String eth = "binance:ETH-USDT";

        registry.registerInstrument(btc);
        registry.registerInstrument(eth);

        // ETH base is 11000
        int ethBboStream = registry.getStreamId(eth, "mds.bbo");
        assertEquals(11001, ethBboStream);

        int ethDepthStream = registry.getStreamId(eth, "mds.depth");
        assertEquals(11002, ethDepthStream);
    }

    @Test
    void testGetStreamIdUnknownInstrument() {
        int streamId = registry.getStreamId("unknown", "mds.bbo");
        assertEquals(-1, streamId);
    }

    @Test
    void testGetStreamIdUnknownChannel() {
        String instrumentId = "binance:BTC-USDT";
        registry.registerInstrument(instrumentId);

        int streamId = registry.getStreamId(instrumentId, "unknown.channel");
        assertEquals(-1, streamId);
    }

    @Test
    void testGetBaseStreamId() {
        String instrumentId = "binance:BTC-USDT";
        registry.registerInstrument(instrumentId);

        int base = registry.getBaseStreamId(instrumentId);
        assertEquals(1000, base);
    }

    @Test
    void testGetBaseStreamIdUnknown() {
        int base = registry.getBaseStreamId("unknown");
        assertEquals(-1, base);
    }

    @Test
    void testGetAllInstruments() {
        registry.registerInstrument("binance:BTC-USDT");
        registry.registerInstrument("binance:ETH-USDT");

        Map<String, Integer> instruments = registry.getAllInstruments();
        assertEquals(2, instruments.size());
        assertTrue(instruments.containsKey("binance:BTC-USDT"));
        assertTrue(instruments.containsKey("binance:ETH-USDT"));
    }

    @Test
    void testChannelOffsets() {
        String instrumentId = "binance:BTC-USDT";
        registry.registerInstrument(instrumentId);

        // Verify all channel mappings
        assertEquals(1001, registry.getStreamId(instrumentId, "mds.bbo"));
        assertEquals(1002, registry.getStreamId(instrumentId, "mds.depth"));
        assertEquals(1003, registry.getStreamId(instrumentId, "mds.trade"));
        assertEquals(2001, registry.getStreamId(instrumentId, "pricing.target"));
        assertEquals(3001, registry.getStreamId(instrumentId, "oms.position"));
        assertEquals(3002, registry.getStreamId(instrumentId, "oms.order"));
        assertEquals(9001, registry.getStreamId(instrumentId, "mgmt.cmd"));
    }

    @Test
    void testConcurrentRegistration() throws InterruptedException {
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        String[] instrumentIds = new String[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            instrumentIds[i] = "binance:COIN" + i + "-USDT";
            threads[i] = new Thread(() -> registry.registerInstrument(instrumentIds[index]));
        }

        for (Thread t : threads) {
            t.start();
        }

        for (Thread t : threads) {
            t.join();
        }

        // All instruments should be registered with unique bases
        Map<String, Integer> instruments = registry.getAllInstruments();
        assertEquals(threadCount, instruments.size());

        // Bases should be unique
        long uniqueBases = instruments.values().stream().distinct().count();
        assertEquals(threadCount, uniqueBases);
    }
}
