package com.bedrock.mm.aeron.bus;

import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.JsonEventSerde;
import com.bedrock.mm.common.event.OrderCommand;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcEventBusIntegrationTest {

    @Test
    void shouldPublishAndConsumeOrderCommand() throws Exception {
        InProcEventBus bus = new InProcEventBus(1024 * 64);
        JsonEventSerde serde = new JsonEventSerde(new ObjectMapper());
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<EventEnvelope> received = new AtomicReference<>();

        bus.registerConsumer(env -> {
            received.set(env);
            latch.countDown();
        });

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(bus::runLoop);

        OrderCommand cmd = new OrderCommand();
        cmd.setClientOrderId("cid-2");
        cmd.setSymbol("ETHUSDT");
        cmd.setSide("SELL");
        cmd.setType("LIMIT");
        cmd.setPrice((long)(3000.0 * 1e8));
        cmd.setQuantity((long)(1.2 * 1e8));
        cmd.setTimeInForce("GTC");
        cmd.setStrategyId("s2");

        EventEnvelope env = new EventEnvelope(
                EventType.ORDER_COMMAND,
                serde.serialize(cmd),
                serde.codec(),
                "ETHUSDT",
                1000L,
                99L);
        assertTrue(bus.publish(env));
        assertTrue(latch.await(2, TimeUnit.SECONDS));

        bus.stop();
        executor.shutdownNow();

        EventEnvelope result = received.get();
        assertNotNull(result);
        assertEquals(EventType.ORDER_COMMAND, result.getType());
        OrderCommand decoded = serde.deserialize(result.getPayload(), OrderCommand.class);
        assertEquals("cid-2", decoded.getClientOrderId());
        assertEquals("ETHUSDT", decoded.getSymbol());
    }
}
