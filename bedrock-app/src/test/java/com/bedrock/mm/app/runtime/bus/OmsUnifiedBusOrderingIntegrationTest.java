package com.bedrock.mm.app.runtime.bus;

import com.bedrock.mm.aeron.bus.InProcEventBus;
import com.bedrock.mm.app.config.BedrockConfig;
import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventSerdeRegistry;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.PayloadCodec;
import com.bedrock.mm.common.event.PositionPayload;
import com.bedrock.mm.oms.model.ExecEvent;
import com.bedrock.mm.oms.model.ExecEventType;
import com.bedrock.mm.oms.model.Order;
import com.bedrock.mm.oms.model.OrderState;
import com.bedrock.mm.pricing.model.QuoteTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for unified bus ordering between quote and exec events.
 */
class OmsUnifiedBusOrderingIntegrationTest {

    private static final String SYMBOL = "BTCUSDT";
    private static final int INSTR_ID = 1;

    @Test
    void quoteThenExecAckAreProcessedInPublishOrder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EventSerde serde = new EventSerde() {
            @Override public byte[] serialize(Object obj) {
                try { return mapper.writeValueAsBytes(obj); } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public <T> T deserialize(byte[] bytes, Class<T> type) {
                try { return mapper.readValue(bytes, type); } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public PayloadCodec codec() { return PayloadCodec.JSON; }
        };
        EventSerdeRegistry serdeRegistry = new EventSerdeRegistry(List.of(serde));
        DeadLetterChannel deadLetterChannel = new DeadLetterChannel();

        InProcEventBus eventBus = new InProcEventBus(1 << 16);
        OmsCoordinator coordinator = new OmsCoordinator(serdeRegistry, serde, deadLetterChannel, null, eventBus);
        coordinator.start(configWithOmsSymbol(SYMBOL));

        OmsCoordinator.InstrumentOmsState state = coordinator.getAllStates().get(SYMBOL);
        assertNotNull(state);

        long internalOrderId = 9_001L;
        addPendingNewOrder(state, internalOrderId);

        CountDownLatch execSeen = new CountDownLatch(1);
        List<EventType> seenTypes = new ArrayList<>();
        eventBus.registerConsumer(env -> {
            if (!SYMBOL.equals(env.getSymbol())) {
                return;
            }
            synchronized (seenTypes) {
                seenTypes.add(env.getType());
            }
            if (env.getType() == EventType.EXEC_EVENT) {
                execSeen.countDown();
            }
        });

        Thread loopThread = new Thread(eventBus::runLoop, "oms-unified-bus-ordering-test");
        loopThread.setDaemon(true);
        loopThread.start();

        try {
            QuoteTarget target = new QuoteTarget();
            target.fairMid = 5_000_000_000_000L;
            target.bidPrice = target.fairMid - 10_000_000L;
            target.askPrice = target.fairMid + 10_000_000L;
            target.bidSize = 10_000_000L;
            target.askSize = 10_000_000L;
            target.regionIndex = 0;
            target.seqId = 100L;
            target.publishNanos = System.nanoTime();

            EventEnvelope quoteEnv = new EventEnvelope(
                    EventType.QUOTE_TARGET,
                    serde.serialize(target),
                    serde.codec(),
                    SYMBOL,
                    target.publishNanos,
                    target.seqId);
            assertTrue(eventBus.publish(quoteEnv));

            ExecEvent ack = new ExecEvent();
            ack.seqId = 101L;
            ack.instrumentId = INSTR_ID;
            ack.internalOrderId = internalOrderId;
            ack.exchOrderId = 123_456L;
            ack.type = ExecEventType.ACK;
            ack.recvNanos = System.nanoTime();

            publishExecEvent(coordinator, state, ack);

            assertTrue(execSeen.await(2, TimeUnit.SECONDS), "Timed out waiting for EXEC_EVENT processing");

            synchronized (seenTypes) {
                assertTrue(seenTypes.size() >= 2, "Expected at least QUOTE_TARGET and EXEC_EVENT");
                assertEquals(EventType.QUOTE_TARGET, seenTypes.get(0));
                assertEquals(EventType.EXEC_EVENT, seenTypes.get(1));
            }

            Order updated = state.orderStore.getOrder(internalOrderId);
            assertNotNull(updated);
            assertEquals(OrderState.OPEN, updated.state);
            assertEquals(123_456L, updated.exchOrderId);
            assertEquals(0, deadLetterChannel.currentSize());
        } finally {
            eventBus.stop();
            loopThread.join(1_000L);
            coordinator.stop();
        }
    }

    @Test
    void ackThenFillPublishesPositionUpdateInOrder() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EventSerde serde = new EventSerde() {
            @Override public byte[] serialize(Object obj) {
                try { return mapper.writeValueAsBytes(obj); } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public <T> T deserialize(byte[] bytes, Class<T> type) {
                try { return mapper.readValue(bytes, type); } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public PayloadCodec codec() { return PayloadCodec.JSON; }
        };
        EventSerdeRegistry serdeRegistry = new EventSerdeRegistry(List.of(serde));
        DeadLetterChannel deadLetterChannel = new DeadLetterChannel();

        InProcEventBus eventBus = new InProcEventBus(1 << 16);
        OmsCoordinator coordinator = new OmsCoordinator(serdeRegistry, serde, deadLetterChannel, null, eventBus);
        coordinator.start(configWithOmsSymbol(SYMBOL));

        OmsCoordinator.InstrumentOmsState state = coordinator.getAllStates().get(SYMBOL);
        assertNotNull(state);

        long internalOrderId = 9_002L;
        addPendingNewOrder(state, internalOrderId);

        CountDownLatch positionSeen = new CountDownLatch(1);
        List<EventType> seenTypes = new ArrayList<>();
        List<PositionPayload> positionUpdates = new ArrayList<>();
        eventBus.registerConsumer(env -> {
            if (!SYMBOL.equals(env.getSymbol())) {
                return;
            }
            synchronized (seenTypes) {
                seenTypes.add(env.getType());
            }
            if (env.getType() == EventType.POSITION_UPDATE) {
                PositionPayload payload = serde.deserialize(env.getPayload(), PositionPayload.class);
                synchronized (positionUpdates) {
                    positionUpdates.add(payload);
                }
                positionSeen.countDown();
            }
        });

        Thread loopThread = new Thread(eventBus::runLoop, "oms-unified-bus-fill-test");
        loopThread.setDaemon(true);
        loopThread.start();

        try {
            QuoteTarget target = new QuoteTarget();
            target.fairMid = 5_000_000_000_000L;
            target.bidPrice = target.fairMid - 10_000_000L;
            target.askPrice = target.fairMid + 10_000_000L;
            target.bidSize = 10_000_000L;
            target.askSize = 10_000_000L;
            target.regionIndex = 0;
            target.seqId = 200L;
            target.publishNanos = System.nanoTime();

            EventEnvelope quoteEnv = new EventEnvelope(
                    EventType.QUOTE_TARGET,
                    serde.serialize(target),
                    serde.codec(),
                    SYMBOL,
                    target.publishNanos,
                    target.seqId);
            assertTrue(eventBus.publish(quoteEnv));

            ExecEvent ack = new ExecEvent();
            ack.seqId = 201L;
            ack.instrumentId = INSTR_ID;
            ack.internalOrderId = internalOrderId;
            ack.exchOrderId = 777_888L;
            ack.type = ExecEventType.ACK;
            ack.recvNanos = System.nanoTime();
            publishExecEvent(coordinator, state, ack);

            ExecEvent fill = new ExecEvent();
            fill.seqId = 202L;
            fill.instrumentId = INSTR_ID;
            fill.internalOrderId = internalOrderId;
            fill.exchOrderId = ack.exchOrderId;
            fill.type = ExecEventType.FILL;
            fill.isBid = true;
            fill.fillSize = 3_000_000L;
            fill.fillPrice = 5_000_100_000_000L;
            fill.recvNanos = System.nanoTime();
            publishExecEvent(coordinator, state, fill);

            assertTrue(positionSeen.await(2, TimeUnit.SECONDS), "Timed out waiting for POSITION_UPDATE");

            synchronized (seenTypes) {
                assertTrue(seenTypes.size() >= 4, "Expected QUOTE_TARGET, ACK, FILL, POSITION_UPDATE");
                assertEquals(EventType.QUOTE_TARGET, seenTypes.get(0));
                assertEquals(EventType.EXEC_EVENT, seenTypes.get(1));
                assertEquals(EventType.EXEC_EVENT, seenTypes.get(2));
                assertEquals(EventType.POSITION_UPDATE, seenTypes.get(3));
            }

            synchronized (positionUpdates) {
                assertEquals(1, positionUpdates.size());
                PositionPayload payload = positionUpdates.get(0);
                assertEquals(SYMBOL, payload.symbol);
                assertEquals(INSTR_ID, payload.instrumentId);
                assertEquals(fill.fillSize, payload.netPosition);
            }

            assertNull(state.orderStore.getOrder(internalOrderId), "Filled order should be removed from store");
            assertEquals(0, deadLetterChannel.currentSize());
        } finally {
            eventBus.stop();
            loopThread.join(1_000L);
            coordinator.stop();
        }
    }

    @Test
    void partialFillsAccumulatePositionAndEmitUpdates() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        EventSerde serde = new EventSerde() {
            @Override public byte[] serialize(Object obj) {
                try { return mapper.writeValueAsBytes(obj); } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public <T> T deserialize(byte[] bytes, Class<T> type) {
                try { return mapper.readValue(bytes, type); } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public PayloadCodec codec() { return PayloadCodec.JSON; }
        };
        EventSerdeRegistry serdeRegistry = new EventSerdeRegistry(List.of(serde));
        DeadLetterChannel deadLetterChannel = new DeadLetterChannel();

        InProcEventBus eventBus = new InProcEventBus(1 << 16);
        OmsCoordinator coordinator = new OmsCoordinator(serdeRegistry, serde, deadLetterChannel, null, eventBus);
        coordinator.start(configWithOmsSymbol(SYMBOL));

        OmsCoordinator.InstrumentOmsState state = coordinator.getAllStates().get(SYMBOL);
        assertNotNull(state);

        long internalOrderId = 9_003L;
        addPendingNewOrder(state, internalOrderId);

        CountDownLatch positionSeen = new CountDownLatch(3);
        List<PositionPayload> positionUpdates = new ArrayList<>();
        eventBus.registerConsumer(env -> {
            if (!SYMBOL.equals(env.getSymbol())) {
                return;
            }
            if (env.getType() == EventType.POSITION_UPDATE) {
                PositionPayload payload = serde.deserialize(env.getPayload(), PositionPayload.class);
                synchronized (positionUpdates) {
                    positionUpdates.add(payload);
                }
                positionSeen.countDown();
            }
        });

        Thread loopThread = new Thread(eventBus::runLoop, "oms-unified-bus-partial-fill-test");
        loopThread.setDaemon(true);
        loopThread.start();

        try {
            QuoteTarget target = new QuoteTarget();
            target.fairMid = 5_000_000_000_000L;
            target.bidPrice = target.fairMid - 10_000_000L;
            target.askPrice = target.fairMid + 10_000_000L;
            target.bidSize = 10_000_000L;
            target.askSize = 10_000_000L;
            target.regionIndex = 0;
            target.seqId = 300L;
            target.publishNanos = System.nanoTime();

            EventEnvelope quoteEnv = new EventEnvelope(
                    EventType.QUOTE_TARGET,
                    serde.serialize(target),
                    serde.codec(),
                    SYMBOL,
                    target.publishNanos,
                    target.seqId);
            assertTrue(eventBus.publish(quoteEnv));

            ExecEvent ack = new ExecEvent();
            ack.seqId = 301L;
            ack.instrumentId = INSTR_ID;
            ack.internalOrderId = internalOrderId;
            ack.exchOrderId = 900_001L;
            ack.type = ExecEventType.ACK;
            ack.recvNanos = System.nanoTime();
            publishExecEvent(coordinator, state, ack);

            ExecEvent partial1 = new ExecEvent();
            partial1.seqId = 302L;
            partial1.instrumentId = INSTR_ID;
            partial1.internalOrderId = internalOrderId;
            partial1.exchOrderId = ack.exchOrderId;
            partial1.type = ExecEventType.PARTIAL_FILL;
            partial1.isBid = true;
            partial1.fillSize = 1_000_000L;
            partial1.fillPrice = 5_000_100_000_000L;
            partial1.recvNanos = System.nanoTime();
            publishExecEvent(coordinator, state, partial1);

            ExecEvent partial2 = new ExecEvent();
            partial2.seqId = 303L;
            partial2.instrumentId = INSTR_ID;
            partial2.internalOrderId = internalOrderId;
            partial2.exchOrderId = ack.exchOrderId;
            partial2.type = ExecEventType.PARTIAL_FILL;
            partial2.isBid = true;
            partial2.fillSize = 2_000_000L;
            partial2.fillPrice = 5_000_110_000_000L;
            partial2.recvNanos = System.nanoTime();
            publishExecEvent(coordinator, state, partial2);

            ExecEvent fill = new ExecEvent();
            fill.seqId = 304L;
            fill.instrumentId = INSTR_ID;
            fill.internalOrderId = internalOrderId;
            fill.exchOrderId = ack.exchOrderId;
            fill.type = ExecEventType.FILL;
            fill.isBid = true;
            fill.fillSize = 4_000_000L;
            fill.fillPrice = 5_000_120_000_000L;
            fill.recvNanos = System.nanoTime();
            publishExecEvent(coordinator, state, fill);

            assertTrue(positionSeen.await(2, TimeUnit.SECONDS), "Timed out waiting for all POSITION_UPDATE events");

            synchronized (positionUpdates) {
                assertEquals(3, positionUpdates.size());
                assertEquals(1_000_000L, positionUpdates.get(0).netPosition);
                assertEquals(3_000_000L, positionUpdates.get(1).netPosition);
                assertEquals(7_000_000L, positionUpdates.get(2).netPosition);
            }

            assertEquals(7_000_000L, state.positionTracker.getNetPosition(INSTR_ID));
            assertNull(state.orderStore.getOrder(internalOrderId), "Order should be removed after terminal fill");
            assertEquals(0, deadLetterChannel.currentSize());
        } finally {
            eventBus.stop();
            loopThread.join(1_000L);
            coordinator.stop();
        }
    }

    private void addPendingNewOrder(OmsCoordinator.InstrumentOmsState state, long orderId) {
        Order order = new Order();
        order.orderId = orderId;
        order.instrumentId = INSTR_ID;
        order.price = 5_000_000_000_000L;
        order.origSize = 10_000_000L;
        order.state = OrderState.PENDING_NEW;
        order.regionIndex = 0;
        order.isBid = true;
        order.createNanos = System.nanoTime();
        order.quotePublishNanos = System.nanoTime() - 1_000_000L;
        order.quoteSeqId = 77L;
        state.orderStore.addOrder(order);
    }

    private void publishExecEvent(OmsCoordinator coordinator,
                                  OmsCoordinator.InstrumentOmsState state,
                                  ExecEvent event) throws Exception {
        Method publishExec = OmsCoordinator.class.getDeclaredMethod(
                "publishExecEvent",
                String.class,
                OmsCoordinator.InstrumentOmsState.class,
                ExecEvent.class);
        publishExec.setAccessible(true);
        publishExec.invoke(coordinator, SYMBOL, state, event);
    }

    private BedrockConfig configWithOmsSymbol(String symbol) {
        BedrockConfig config = new BedrockConfig();
        BedrockConfig.OmsConfig oms = new BedrockConfig.OmsConfig();
        oms.setEnabled(true);
        oms.setSymbols(List.of(symbol));
        oms.setExchange("simulation");
        config.setOms(oms);
        return config;
    }
}
