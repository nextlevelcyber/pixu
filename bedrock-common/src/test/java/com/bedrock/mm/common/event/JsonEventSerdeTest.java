package com.bedrock.mm.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonEventSerdeTest {

    @Test
    void shouldRoundTripOrderCommandPayload() {
        JsonEventSerde serde = new JsonEventSerde(new ObjectMapper());
        OrderCommand cmd = new OrderCommand();
        cmd.setClientOrderId("cid-1");
        cmd.setSymbol("BTCUSDT");
        cmd.setSide("BUY");
        cmd.setType("LIMIT");
        cmd.setPrice((long)(50000.5 * 100_000_000L));
        cmd.setQuantity((long)(0.1 * 100_000_000L));
        cmd.setTimeInForce("GTC");
        cmd.setStrategyId("s1");

        byte[] bytes = serde.serialize(cmd);
        OrderCommand decoded = serde.deserialize(bytes, OrderCommand.class);

        assertEquals(PayloadCodec.JSON, serde.codec());
        assertEquals(cmd.getClientOrderId(), decoded.getClientOrderId());
        assertEquals(cmd.getSymbol(), decoded.getSymbol());
        assertEquals(cmd.getSide(), decoded.getSide());
        assertEquals(cmd.getType(), decoded.getType());
        assertEquals(cmd.getPrice(), decoded.getPrice());
        assertEquals(cmd.getQuantity(), decoded.getQuantity());
        assertEquals(cmd.getTimeInForce(), decoded.getTimeInForce());
        assertEquals(cmd.getStrategyId(), decoded.getStrategyId());
    }
}
