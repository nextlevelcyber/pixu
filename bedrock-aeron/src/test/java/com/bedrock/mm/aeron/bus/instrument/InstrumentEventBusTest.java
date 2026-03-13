package com.bedrock.mm.aeron.bus.instrument;

import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.PayloadCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentEventBusTest {

    private InstrumentEventBus eventBus;

    @BeforeEach
    void setUp() {
        eventBus = new InstrumentEventBus("binance:BTC-USDT");
    }

    @AfterEach
    void tearDown() {
        if (eventBus != null) {
            eventBus.stop();
        }
    }

    @Test
    void testPublishAndConsume() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicLong receivedSeq = new AtomicLong(-1);

        eventBus.registerStrategyConsumer(env -> {
            receivedSeq.set(env.getSeq());
            latch.countDown();
        });

        eventBus.start();

        EventEnvelope envelope = new EventEnvelope(
                EventType.MARKET_TICK,
                "{}".getBytes(),
                PayloadCodec.JSON,
                "BTC-USDT",
                System.nanoTime(),
                100L);

        assertTrue(eventBus.publish(envelope));
        assertTrue(latch.await(1, TimeUnit.SECONDS));
        assertEquals(100L, receivedSeq.get());
    }

    @Test
    void testStrategyConsumersBeforeAdapters() throws InterruptedException {
        CountDownLatch strategyLatch = new CountDownLatch(1);
        CountDownLatch adapterLatch = new CountDownLatch(1);
        AtomicInteger order = new AtomicInteger(0);
        AtomicInteger strategyOrder = new AtomicInteger(-1);
        AtomicInteger adapterOrder = new AtomicInteger(-1);

        eventBus.registerStrategyConsumer(env -> {
            strategyOrder.set(order.getAndIncrement());
            strategyLatch.countDown();
        });

        eventBus.registerAdapterConsumer(env -> {
            adapterOrder.set(order.getAndIncrement());
            adapterLatch.countDown();
        });

        eventBus.start();

        EventEnvelope envelope = new EventEnvelope(
                EventType.MARKET_TICK,
                "{}".getBytes(),
                PayloadCodec.JSON,
                "BTC-USDT",
                System.nanoTime(),
                1L);

        assertTrue(eventBus.publish(envelope));
        assertTrue(strategyLatch.await(1, TimeUnit.SECONDS));
        assertTrue(adapterLatch.await(1, TimeUnit.SECONDS));

        assertTrue(strategyOrder.get() < adapterOrder.get(),
                "Strategy consumer should run before adapter consumer");
    }

    @Test
    void testMultipleConsumers() throws InterruptedException {
        int consumerCount = 5;
        CountDownLatch latch = new CountDownLatch(consumerCount);

        for (int i = 0; i < consumerCount; i++) {
            eventBus.registerStrategyConsumer(env -> latch.countDown());
        }

        eventBus.start();

        EventEnvelope envelope = new EventEnvelope(
                EventType.MARKET_TICK,
                "{}".getBytes(),
                PayloadCodec.JSON,
                "BTC-USDT",
                System.nanoTime(),
                1L);

        assertTrue(eventBus.publish(envelope));
        assertTrue(latch.await(1, TimeUnit.SECONDS));
    }

    @Test
    void testBackpressure() throws InterruptedException {
        // Register slow consumer to create backpressure
        CountDownLatch blockLatch = new CountDownLatch(1);
        eventBus.registerStrategyConsumer(env -> {
            try {
                blockLatch.await(); // Block until test completes
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        eventBus.start();

        int successCount = 0;
        int dropCount = 0;

        // Try to overflow ring buffer (1024 slots)
        for (int i = 0; i < 2000; i++) {
            EventEnvelope envelope = new EventEnvelope(
                    EventType.MARKET_TICK,
                    "{}".getBytes(),
                    PayloadCodec.JSON,
                    "BTC-USDT",
                    System.nanoTime(),
                    i);

            if (eventBus.publish(envelope)) {
                successCount++;
            } else {
                dropCount++;
            }
        }

        assertTrue(successCount > 0, "Should publish some events");
        assertTrue(dropCount > 0, "Should drop some events due to backpressure");

        InstrumentEventBus.Metrics metrics = eventBus.getMetrics();
        assertEquals(2000, metrics.publishAttempts());
        assertTrue(metrics.publishDropped() > 0);

        // Unblock consumer
        blockLatch.countDown();
    }

    @Test
    void testMetrics() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(10);
        eventBus.registerStrategyConsumer(env -> latch.countDown());
        eventBus.start();

        for (int i = 0; i < 10; i++) {
            EventEnvelope envelope = new EventEnvelope(
                    EventType.MARKET_TICK,
                    "{}".getBytes(),
                    PayloadCodec.JSON,
                    "BTC-USDT",
                    System.nanoTime(),
                    i);
            eventBus.publish(envelope);
        }

        assertTrue(latch.await(1, TimeUnit.SECONDS));

        InstrumentEventBus.Metrics metrics = eventBus.getMetrics();
        assertEquals(10, metrics.publishAttempts());
        assertEquals(10, metrics.publishSuccess());
        assertEquals(0, metrics.publishDropped());
    }

    @Test
    void testStartStop() {
        assertFalse(eventBus.isRunning());

        eventBus.start();
        assertTrue(eventBus.isRunning());

        eventBus.stop();
        assertFalse(eventBus.isRunning());

        // Double stop should be safe
        eventBus.stop();
        assertFalse(eventBus.isRunning());
    }

    @Test
    void testInstrumentId() {
        assertEquals("binance:BTC-USDT", eventBus.getInstrumentId());
    }
}
