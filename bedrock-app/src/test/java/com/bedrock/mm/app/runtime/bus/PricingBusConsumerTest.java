package com.bedrock.mm.app.runtime.bus;

import com.bedrock.mm.common.event.BboPayload;
import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventSerdeRegistry;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.MarketTickPayload;
import com.bedrock.mm.common.event.PayloadCodec;
import com.bedrock.mm.pricing.PricingOrchestrator;
import com.bedrock.mm.pricing.PricingOrchestratorFactory;
import com.bedrock.mm.pricing.model.QuoteTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PricingBusConsumer.
 */
public class PricingBusConsumerTest {
    private static final String SYMBOL = "BTCUSDT";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private List<QuoteTarget> publishedTargets;
    private PricingOrchestrator orchestrator;
    private PricingBusConsumer consumer;
    private DeadLetterChannel deadLetterChannel;

    // Minimal JSON-based EventSerde for tests
    private final EventSerde jsonSerde = new EventSerde() {
        @Override public PayloadCodec codec() { return PayloadCodec.JSON; }
        @Override public <T> byte[] serialize(T payload) {
            try { return MAPPER.writeValueAsBytes(payload); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public <T> T deserialize(byte[] payload, Class<T> type) {
            try { return MAPPER.readValue(payload, type); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    };

    @BeforeEach
    void setUp() {
        publishedTargets = new ArrayList<>();
        orchestrator = PricingOrchestratorFactory.createDefault(1, target -> publishedTargets.add(target.copy()));
        deadLetterChannel = new DeadLetterChannel();

        EventSerdeRegistry serdeRegistry = new EventSerdeRegistry(List.of(jsonSerde));
        consumer = new PricingBusConsumer(SYMBOL, orchestrator, serdeRegistry, deadLetterChannel);
    }

    @Test
    void testBboEventTriggersRecompute() throws Exception {
        assertEquals(0L, orchestrator.getSeqId());
        assertTrue(publishedTargets.isEmpty());

        BboPayload bbo = new BboPayload(SYMBOL, 5000_0000_0000L, 100_000_000L,
                5001_0000_0000L, 80_000_000L, System.nanoTime(), 42L);
        EventEnvelope env = bboEnvelope(bbo);

        consumer.accept(env);

        // BBO triggers recompute → seqId increments and publisher is called
        assertEquals(1L, orchestrator.getSeqId());
        assertEquals(1, publishedTargets.size());
        assertEquals(1, publishedTargets.get(0).instrumentId);
        assertEquals(1L, publishedTargets.get(0).seqId);
    }

    @Test
    void testBboSeqIdIncrementsAcrossMultipleEvents() throws Exception {
        for (int i = 1; i <= 3; i++) {
            BboPayload bbo = new BboPayload(SYMBOL, 5000_0000_0000L + i, 100_000_000L,
                    5001_0000_0000L + i, 80_000_000L, System.nanoTime(), (long) i);
            consumer.accept(bboEnvelope(bbo));
        }

        assertEquals(3L, orchestrator.getSeqId());
        assertEquals(3, publishedTargets.size());
        // Each target's seqId increments
        for (int i = 0; i < 3; i++) {
            assertEquals(i + 1L, publishedTargets.get(i).seqId);
        }
    }

    @Test
    void testMarketTickUpdatesTradeStateNoRecompute() throws Exception {
        MarketTickPayload tick = new MarketTickPayload(SYMBOL, System.currentTimeMillis(),
                5000_0000_0000L, 100_000_000L, true, 43L);
        EventEnvelope env = marketTickEnvelope(tick);

        consumer.accept(env);

        // MARKET_TICK should NOT trigger recompute
        assertEquals(0L, orchestrator.getSeqId());
        assertTrue(publishedTargets.isEmpty());
        // But orchestrator context should have lastTradePrice updated
        assertEquals(5000_0000_0000L, orchestrator.getContext().lastTradePrice);
        assertTrue(orchestrator.getContext().lastTradeBuy);
    }

    @Test
    void testDeadLetterOnMalformedBboPayload() {
        // Malformed payload (random bytes)
        byte[] garbage = new byte[]{0x01, 0x02, 0x03};
        EventEnvelope env = new EventEnvelope(EventType.BBO, garbage,
                PayloadCodec.JSON, SYMBOL, System.nanoTime(), 99L);

        consumer.accept(env);

        // Orchestrator should not be called
        assertEquals(0L, orchestrator.getSeqId());
        assertTrue(publishedTargets.isEmpty());
        // Dead letter should have recorded the failure
        assertEquals(1, deadLetterChannel.currentSize());
    }

    @Test
    void testNullEnvelopeIsHandledGracefully() {
        // Should not throw
        assertDoesNotThrow(() -> consumer.accept(null));
        assertEquals(0L, orchestrator.getSeqId());
        assertTrue(publishedTargets.isEmpty());
    }

    @Test
    void testUnrelatedEventTypeIsIgnored() throws Exception {
        // BOOK_DELTA event: should be silently ignored
        BboPayload unused = new BboPayload(SYMBOL, 1L, 1L, 2L, 1L, 0L, 1L);
        byte[] bytes = MAPPER.writeValueAsBytes(unused);
        EventEnvelope env = new EventEnvelope(EventType.BOOK_DELTA, bytes,
                PayloadCodec.JSON, SYMBOL, System.nanoTime(), 1L);

        consumer.accept(env);

        assertEquals(0L, orchestrator.getSeqId());
        assertTrue(publishedTargets.isEmpty());
        assertEquals(0, deadLetterChannel.currentSize());
    }

    // Helpers

    private EventEnvelope bboEnvelope(BboPayload bbo) throws Exception {
        byte[] bytes = MAPPER.writeValueAsBytes(bbo);
        return new EventEnvelope(EventType.BBO, bytes,
                PayloadCodec.JSON, SYMBOL, bbo.recvNanos, bbo.sequenceNumber);
    }

    private EventEnvelope marketTickEnvelope(MarketTickPayload tick) throws Exception {
        byte[] bytes = MAPPER.writeValueAsBytes(tick);
        return new EventEnvelope(EventType.MARKET_TICK, bytes,
                PayloadCodec.JSON, SYMBOL, System.nanoTime(), tick.sequenceNumber);
    }
}
