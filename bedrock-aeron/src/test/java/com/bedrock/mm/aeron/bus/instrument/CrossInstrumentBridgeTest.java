package com.bedrock.mm.aeron.bus.instrument;

import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.PayloadCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CrossInstrumentBridgeTest {

    private CrossInstrumentBridge bridge;

    @BeforeEach
    void setUp() {
        bridge = new CrossInstrumentBridge();
    }

    @Test
    void testSubscribeAndDispatch() throws InterruptedException {
        String btc = "binance:BTC-USDT";
        String eth = "binance:ETH-USDT";

        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger receivedSeq = new AtomicInteger(-1);

        // BTC strategy subscribes to ETH market ticks
        bridge.subscribe(btc, eth, EventType.MARKET_TICK, envelope -> {
            receivedSeq.set((int) envelope.getSeq());
            latch.countDown();
        });

        // Dispatch ETH market tick
        EventEnvelope envelope = new EventEnvelope(
                EventType.MARKET_TICK,
                "{}".getBytes(),
                PayloadCodec.JSON,
                "ETH-USDT",
                System.nanoTime(),
                100L);

        bridge.dispatch(eth, envelope);

        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(100, receivedSeq.get());
    }

    @Test
    void testMultipleSubscribers() throws InterruptedException {
        String btc = "binance:BTC-USDT";
        String sol = "binance:SOL-USDT";
        String eth = "binance:ETH-USDT";

        CountDownLatch latch = new CountDownLatch(2);

        // BTC and SOL strategies both subscribe to ETH
        bridge.subscribe(btc, eth, EventType.MARKET_TICK, envelope -> latch.countDown());
        bridge.subscribe(sol, eth, EventType.MARKET_TICK, envelope -> latch.countDown());

        EventEnvelope envelope = new EventEnvelope(
                EventType.MARKET_TICK,
                "{}".getBytes(),
                PayloadCodec.JSON,
                "ETH-USDT",
                System.nanoTime(),
                1L);

        bridge.dispatch(eth, envelope);

        assertTrue(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    void testEventTypeFiltering() throws InterruptedException {
        String btc = "binance:BTC-USDT";
        String eth = "binance:ETH-USDT";

        CountDownLatch tickLatch = new CountDownLatch(1);
        CountDownLatch depthLatch = new CountDownLatch(1);

        // Subscribe to MARKET_TICK only
        bridge.subscribe(btc, eth, EventType.MARKET_TICK, envelope -> tickLatch.countDown());

        // Dispatch MARKET_TICK (should trigger)
        EventEnvelope tickEnvelope = new EventEnvelope(
                EventType.MARKET_TICK,
                "{}".getBytes(),
                PayloadCodec.JSON,
                "ETH-USDT",
                System.nanoTime(),
                1L);
        bridge.dispatch(eth, tickEnvelope);

        // Dispatch BOOK_DELTA (should NOT trigger)
        EventEnvelope deltaEnvelope = new EventEnvelope(
                EventType.BOOK_DELTA,
                "{}".getBytes(),
                PayloadCodec.JSON,
                "ETH-USDT",
                System.nanoTime(),
                2L);
        bridge.dispatch(eth, deltaEnvelope);

        assertTrue(tickLatch.await(100, TimeUnit.MILLISECONDS));
        assertFalse(depthLatch.await(100, TimeUnit.MILLISECONDS), "BOOK_DELTA should not trigger MARKET_TICK subscription");
    }

    @Test
    void testUnsubscribe() throws InterruptedException {
        String btc = "binance:BTC-USDT";
        String eth = "binance:ETH-USDT";

        CountDownLatch latch = new CountDownLatch(2);

        bridge.subscribe(btc, eth, EventType.MARKET_TICK, envelope -> latch.countDown());

        // Dispatch first event
        EventEnvelope envelope1 = new EventEnvelope(
                EventType.MARKET_TICK,
                "{}".getBytes(),
                PayloadCodec.JSON,
                "ETH-USDT",
                System.nanoTime(),
                1L);
        bridge.dispatch(eth, envelope1);

        // Unsubscribe
        bridge.unsubscribe(btc, eth, EventType.MARKET_TICK);

        // Dispatch second event (should NOT trigger)
        EventEnvelope envelope2 = new EventEnvelope(
                EventType.MARKET_TICK,
                "{}".getBytes(),
                PayloadCodec.JSON,
                "ETH-USDT",
                System.nanoTime(),
                2L);
        bridge.dispatch(eth, envelope2);

        assertFalse(latch.await(100, TimeUnit.MILLISECONDS), "Should only receive 1 event before unsubscribe");
        assertEquals(1, latch.getCount());
    }

    @Test
    void testNoSubscriptions() {
        String eth = "binance:ETH-USDT";

        assertFalse(bridge.hasSubscriptions(eth));
        assertEquals(0, bridge.getSubscriptionCount(eth));

        // Dispatching to instrument with no subscriptions should be safe
        EventEnvelope envelope = new EventEnvelope(
                EventType.MARKET_TICK,
                "{}".getBytes(),
                PayloadCodec.JSON,
                "ETH-USDT",
                System.nanoTime(),
                1L);

        assertDoesNotThrow(() -> bridge.dispatch(eth, envelope));
    }

    @Test
    void testHasSubscriptions() {
        String btc = "binance:BTC-USDT";
        String eth = "binance:ETH-USDT";

        assertFalse(bridge.hasSubscriptions(eth));

        bridge.subscribe(btc, eth, EventType.MARKET_TICK, envelope -> {});

        assertTrue(bridge.hasSubscriptions(eth));
        assertEquals(1, bridge.getSubscriptionCount(eth));
    }

    @Test
    void testGetSourceInstruments() {
        String btc = "binance:BTC-USDT";
        String eth = "binance:ETH-USDT";
        String sol = "binance:SOL-USDT";

        bridge.subscribe(btc, eth, EventType.MARKET_TICK, envelope -> {});
        bridge.subscribe(btc, sol, EventType.MARKET_TICK, envelope -> {});

        var sources = bridge.getSourceInstruments();
        assertEquals(2, sources.size());
        assertTrue(sources.contains(eth));
        assertTrue(sources.contains(sol));
    }

    @Test
    void testSubscriptionCount() {
        String btc = "binance:BTC-USDT";
        String sol = "binance:SOL-USDT";
        String eth = "binance:ETH-USDT";

        bridge.subscribe(btc, eth, EventType.MARKET_TICK, envelope -> {});
        bridge.subscribe(btc, eth, EventType.BOOK_DELTA, envelope -> {});
        bridge.subscribe(sol, eth, EventType.MARKET_TICK, envelope -> {});

        assertEquals(3, bridge.getSubscriptionCount(eth));
    }

    @Test
    void testConsumerException() throws InterruptedException {
        String btc = "binance:BTC-USDT";
        String eth = "binance:ETH-USDT";

        CountDownLatch latch = new CountDownLatch(2);

        // First consumer throws exception
        bridge.subscribe(btc, eth, EventType.MARKET_TICK, envelope -> {
            latch.countDown();
            throw new RuntimeException("Test exception");
        });

        // Second consumer should still receive event
        bridge.subscribe(btc, eth, EventType.MARKET_TICK, envelope -> latch.countDown());

        EventEnvelope envelope = new EventEnvelope(
                EventType.MARKET_TICK,
                "{}".getBytes(),
                PayloadCodec.JSON,
                "ETH-USDT",
                System.nanoTime(),
                1L);

        bridge.dispatch(eth, envelope);

        assertTrue(latch.await(1, TimeUnit.SECONDS), "Both consumers should be called despite exception");
    }
}
