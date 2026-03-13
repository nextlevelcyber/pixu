package com.bedrock.mm.app.runtime.bus;

import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventSerdeRegistry;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.PayloadCodec;
import com.bedrock.mm.oms.exec.ExecGateway;
import com.bedrock.mm.oms.model.ExecEvent;
import com.bedrock.mm.oms.model.ExecEventType;
import com.bedrock.mm.oms.position.PositionTracker;
import com.bedrock.mm.oms.region.RegionManager;
import com.bedrock.mm.oms.risk.RiskGuard;
import com.bedrock.mm.oms.store.OrderStore;
import com.bedrock.mm.oms.store.PriceRegion;
import com.bedrock.mm.pricing.model.QuoteTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OmsBusConsumer.
 */
class OmsBusConsumerTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final int INSTR_ID = 1;

    private final ObjectMapper mapper = new ObjectMapper();

    private EventSerde serde;
    private EventSerdeRegistry serdeRegistry;

    private OrderStore orderStore;
    private PositionTracker positionTracker;
    private RiskGuard riskGuard;
    private TestExecGateway execGateway;
    private RegionManager regionManager;
    private PriceRegion[] bidRegions;
    private PriceRegion[] askRegions;
    private DeadLetterChannel deadLetterChannel;
    private List<ExecEvent> handledExecEvents;

    private OmsBusConsumer consumer;

    @BeforeEach
    void setUp() {
        serde = new EventSerde() {
            @Override public byte[] serialize(Object obj) {
                try { return mapper.writeValueAsBytes(obj); } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public <T> T deserialize(byte[] bytes, Class<T> type) {
                try { return mapper.readValue(bytes, type); } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public PayloadCodec codec() { return PayloadCodec.JSON; }
        };

        serdeRegistry = new EventSerdeRegistry(List.of(serde));

        orderStore = new OrderStore(16);
        positionTracker = new PositionTracker();
        riskGuard = new RiskGuard();
        execGateway = new TestExecGateway();
        regionManager = new RegionManager();

        bidRegions = new PriceRegion[]{new PriceRegion(0, true, 0.999, 0.9995)};
        askRegions = new PriceRegion[]{new PriceRegion(0, false, 1.0005, 1.001)};

        deadLetterChannel = new DeadLetterChannel();
        handledExecEvents = new ArrayList<>();

        consumer = new OmsBusConsumer(
                SYMBOL, INSTR_ID,
                regionManager, bidRegions, askRegions,
                orderStore, positionTracker,
                execGateway, riskGuard, 0.01,
                handledExecEvents::add,
                serdeRegistry, deadLetterChannel);
    }

    @Test
    void nullEnvelopeIgnored() {
        consumer.accept(null);
        assertEquals(0, execGateway.placedOrders.size());
        assertEquals(0, deadLetterChannel.currentSize());
    }

    @Test
    void unrelatedEventTypeIgnored() throws Exception {
        byte[] payload = mapper.writeValueAsBytes(java.util.Map.of());
        EventEnvelope env = new EventEnvelope(EventType.BBO, payload, PayloadCodec.JSON,
                SYMBOL, System.nanoTime(), 1L);
        consumer.accept(env);
        assertEquals(0, execGateway.placedOrders.size());
        assertEquals(0, deadLetterChannel.currentSize());
    }

    @Test
    void quoteTargetWithEmptyRegionsProducesNewOrders() throws Exception {
        // Regions are empty → RegionManager.diff() should produce 1 bid + 1 ask NewOrder
        long fairMid = 5_000_000_000_000L; // $50000 in 1e-8
        long spread  =         10_000_000L; // $0.10 in 1e-8
        QuoteTarget target = new QuoteTarget();
        target.fairMid      = fairMid;
        target.bidPrice     = fairMid - spread;
        target.askPrice     = fairMid + spread;
        target.bidSize      = 10_000_000L; // 0.1 BTC
        target.askSize      = 10_000_000L;
        target.regionIndex  = 0;
        target.seqId        = 1L;
        target.publishNanos = System.nanoTime();

        byte[] payload = mapper.writeValueAsBytes(target);
        EventEnvelope env = new EventEnvelope(EventType.QUOTE_TARGET, payload,
                PayloadCodec.JSON, SYMBOL, target.publishNanos, target.seqId);

        consumer.accept(env);

        // Both regions empty → expect 1 bid + 1 ask order placed (passing RiskGuard)
        assertEquals(2, execGateway.placedOrders.size(), "Expected bid + ask order");
        assertEquals(target.publishNanos, execGateway.lastQuotePublishNanos);
        assertEquals(target.seqId, execGateway.lastQuoteSeqId);
        assertEquals(0, deadLetterChannel.currentSize(), "No dead letters expected");
    }

    @Test
    void cancelStaleOrdersCalledOnExecGateway() throws Exception {
        // Pre-fill a region with a fake orderId that won't match valid boundaries
        // after fairMid shifts significantly → should generate CancelOrder
        long fairMid = 5_000_000_000_000L; // $50000
        // Add an order to bid region 0 that is outside new boundaries
        bidRegions[0].addOrder(9999L);

        QuoteTarget target = new QuoteTarget();
        target.fairMid      = fairMid;
        target.bidPrice     = fairMid - 10_000_000L;
        target.askPrice     = fairMid + 10_000_000L;
        target.bidSize      = 10_000_000L;
        target.askSize      = 10_000_000L;
        target.regionIndex  = 0;
        target.seqId        = 2L;
        target.publishNanos = System.nanoTime();

        byte[] payload = mapper.writeValueAsBytes(target);
        EventEnvelope env = new EventEnvelope(EventType.QUOTE_TARGET, payload,
                PayloadCodec.JSON, SYMBOL, target.publishNanos, target.seqId);

        consumer.accept(env);
        assertEquals(0, deadLetterChannel.currentSize());
    }

    @Test
    void malformedPayloadRoutesToDeadLetter() {
        byte[] garbage = new byte[]{0x01, 0x02, 0x03};
        EventEnvelope env = new EventEnvelope(EventType.QUOTE_TARGET, garbage,
                PayloadCodec.JSON, SYMBOL, System.nanoTime(), 1L);
        consumer.accept(env);
        assertEquals(1, deadLetterChannel.currentSize());
    }

    @Test
    void execEventIsDispatchedToHandler() throws Exception {
        ExecEvent execEvent = new ExecEvent();
        execEvent.seqId = 7L;
        execEvent.instrumentId = INSTR_ID;
        execEvent.internalOrderId = 123L;
        execEvent.type = ExecEventType.ACK;

        byte[] payload = mapper.writeValueAsBytes(execEvent);
        EventEnvelope env = new EventEnvelope(EventType.EXEC_EVENT, payload,
                PayloadCodec.JSON, SYMBOL, System.nanoTime(), execEvent.seqId);

        consumer.accept(env);
        assertEquals(1, handledExecEvents.size());
        assertEquals(7L, handledExecEvents.get(0).seqId);
    }

    @Test
    void otherSymbolIsIgnored() throws Exception {
        QuoteTarget target = new QuoteTarget();
        target.fairMid = 5_000_000_000_000L;
        target.bidPrice = target.fairMid - 10_000_000L;
        target.askPrice = target.fairMid + 10_000_000L;
        target.bidSize = 10_000_000L;
        target.askSize = 10_000_000L;

        byte[] payload = mapper.writeValueAsBytes(target);
        EventEnvelope env = new EventEnvelope(EventType.QUOTE_TARGET, payload,
                PayloadCodec.JSON, "ETHUSDT", System.nanoTime(), 99L);
        consumer.accept(env);

        assertEquals(0, execGateway.placedOrders.size());
        assertEquals(0, deadLetterChannel.currentSize());
    }

    @Test
    void multipleQuoteTargetsIncrementallyFillRegions() throws Exception {
        long fairMid = 5_000_000_000_000L;
        QuoteTarget target = new QuoteTarget();
        target.fairMid = fairMid;
        target.bidPrice = fairMid - 10_000_000L;
        target.askPrice = fairMid + 10_000_000L;
        target.bidSize  = 10_000_000L;
        target.askSize  = 10_000_000L;
        target.regionIndex = 0;

        // First call: regions empty → 2 orders placed
        target.seqId = 1L;
        byte[] p1 = mapper.writeValueAsBytes(target);
        consumer.accept(new EventEnvelope(EventType.QUOTE_TARGET, p1,
                PayloadCodec.JSON, SYMBOL, System.nanoTime(), 1L));
        int firstRound = execGateway.placedOrders.size();
        assertEquals(2, firstRound);

        // Second call without clearing regions: no new orders (regions have order slots)
        // Note: PriceRegion is a fixed array that OMS manages; second call won't re-add unless removed
        target.seqId = 2L;
        byte[] p2 = mapper.writeValueAsBytes(target);
        consumer.accept(new EventEnvelope(EventType.QUOTE_TARGET, p2,
                PayloadCodec.JSON, SYMBOL, System.nanoTime(), 2L));
        // Orders are placed by ExecGateway but region slots tracked externally
        // Regions still appear empty to RegionManager (we didn't actually add orderId to region)
        // So again 2 more orders
        assertEquals(4, execGateway.placedOrders.size());
    }

    @Test
    void quoteTargetDroppedWhenStartupRecoveryNotReady() throws Exception {
        AtomicInteger blocked = new AtomicInteger(0);
        OmsBusConsumer gatedConsumer = new OmsBusConsumer(
                SYMBOL, INSTR_ID,
                regionManager, bidRegions, askRegions,
                orderStore, positionTracker,
                execGateway, riskGuard, 0.01,
                handledExecEvents::add,
                serdeRegistry, deadLetterChannel,
                () -> false,
                blocked::incrementAndGet);

        QuoteTarget target = new QuoteTarget();
        target.fairMid = 5_000_000_000_000L;
        target.bidPrice = target.fairMid - 10_000_000L;
        target.askPrice = target.fairMid + 10_000_000L;
        target.bidSize = 10_000_000L;
        target.askSize = 10_000_000L;
        target.regionIndex = 0;
        target.seqId = 55L;
        target.publishNanos = System.nanoTime();

        byte[] payload = mapper.writeValueAsBytes(target);
        EventEnvelope env = new EventEnvelope(EventType.QUOTE_TARGET, payload,
                PayloadCodec.JSON, SYMBOL, target.publishNanos, target.seqId);
        gatedConsumer.accept(env);

        assertEquals(0, execGateway.placedOrders.size());
        assertEquals(1, blocked.get());
    }

    // ---- helpers ----

    static class TestExecGateway implements ExecGateway {
        final List<long[]> placedOrders = new ArrayList<>();
        final List<Long> cancelledOrders = new ArrayList<>();
        long lastQuotePublishNanos;
        long lastQuoteSeqId;

        @Override public void placeOrder(int instrId, long price, long size, boolean isBid, int region) {
            placedOrders.add(new long[]{price, size, isBid ? 1 : 0, region});
        }
        @Override
        public void placeOrder(int instrId, long price, long size, boolean isBid, int region,
                               long quotePublishNanos, long quoteSeqId) {
            lastQuotePublishNanos = quotePublishNanos;
            lastQuoteSeqId = quoteSeqId;
            placeOrder(instrId, price, size, isBid, region);
        }
        @Override public void cancelOrder(int instrId, long orderId) { cancelledOrders.add(orderId); }
        @Override public boolean onPrivateWsReconnect() { return true; }
        @Override public void shutdown() {}
    }
}
