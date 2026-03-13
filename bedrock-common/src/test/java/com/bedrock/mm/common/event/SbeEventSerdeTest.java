package com.bedrock.mm.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SbeEventSerdeTest {

    @Test
    void shouldRoundTripOrderCommand() {
        SbeEventSerde serde = new SbeEventSerde(new ObjectMapper());
        OrderCommand cmd = new OrderCommand();
        cmd.setClientOrderId("cid-sbe-1");
        cmd.setSymbol("BTCUSDT");
        cmd.setSide("BUY");
        cmd.setType("LIMIT");
        cmd.setPrice((long)(51000.0 * 100_000_000L));
        cmd.setQuantity((long)(0.25 * 100_000_000L));
        cmd.setTimeInForce("GTC");
        cmd.setStrategyId("sbe-strategy");

        byte[] bytes = serde.serialize(cmd);
        OrderCommand decoded = serde.deserialize(bytes, OrderCommand.class);

        assertEquals(PayloadCodec.SBE, serde.codec());
        assertEquals("cid-sbe-1", decoded.getClientOrderId());
        assertEquals("BTCUSDT", decoded.getSymbol());
        assertEquals("BUY", decoded.getSide());
    }

    @Test
    void shouldRejectWrongTargetTypeWhenTemplateIdDiffers() {
        SbeEventSerde serde = new SbeEventSerde(new ObjectMapper());
        OrderCommand cmd = new OrderCommand();
        cmd.setClientOrderId("cid-sbe-2");
        cmd.setSymbol("ETHUSDT");
        cmd.setSide("SELL");

        byte[] bytes = serde.serialize(cmd);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> serde.deserialize(bytes, FillPayload.class));
        assertTrue(ex.getMessage().contains("Failed to SBE-deserialize"));
        assertTrue(ex.getMessage().contains("Template mismatch"));
    }

    @Test
    void shouldRoundTripOrderAckPayload() {
        SbeEventSerde serde = new SbeEventSerde(new ObjectMapper());
        OrderAckPayload ack = new OrderAckPayload(
                "BTCUSDT",
                "oid-1",
                "cid-1",
                "BUY",
                "LIMIT",
                "NEW",
                "51000.12",
                "0.10",
                1700000000123L);

        byte[] bytes = serde.serialize(ack);
        OrderAckPayload decoded = serde.deserialize(bytes, OrderAckPayload.class);

        assertEquals("BTCUSDT", decoded.symbol);
        assertEquals("oid-1", decoded.orderId);
        assertEquals("cid-1", decoded.clientOrderId);
        assertEquals("NEW", decoded.status);
        assertEquals("51000.12", decoded.price);
        assertEquals(1700000000123L, decoded.eventTimeMs);
    }

    @Test
    void shouldRoundTripFillPayload() {
        SbeEventSerde serde = new SbeEventSerde(new ObjectMapper());
        FillPayload fill = new FillPayload(
                "ETHUSDT",
                "oid-2",
                "cid-2",
                "tid-1",
                "SELL",
                "2500.50",
                "0.40",
                "TAKER",
                "0.001",
                "USDT",
                1700000000222L);

        byte[] bytes = serde.serialize(fill);
        FillPayload decoded = serde.deserialize(bytes, FillPayload.class);

        assertEquals("ETHUSDT", decoded.symbol);
        assertEquals("oid-2", decoded.orderId);
        assertEquals("tid-1", decoded.tradeId);
        assertEquals("TAKER", decoded.liquidity);
        assertEquals("0.001", decoded.commission);
        assertEquals(1700000000222L, decoded.eventTimeMs);
    }

    @Test
    void shouldRoundTripUnknownTypeViaFallbackJson() {
        SbeEventSerde serde = new SbeEventSerde(new ObjectMapper());
        UnknownPayload payload = new UnknownPayload();
        payload.symbol = "BNBUSDT";
        payload.sequence = 42L;

        byte[] bytes = serde.serialize(payload);
        UnknownPayload decoded = serde.deserialize(bytes, UnknownPayload.class);

        assertEquals("BNBUSDT", decoded.symbol);
        assertEquals(42L, decoded.sequence);
    }

    @Test
    void shouldRoundTripMarketTickPayload() {
        SbeEventSerde serde = new SbeEventSerde(new ObjectMapper());
        MarketTickPayload payload = new MarketTickPayload(
                "BTCUSDT",
                1700000000333L,
                5100012345L,
                25000000L,
                true,
                99L);

        byte[] bytes = serde.serialize(payload);
        MarketTickPayload decoded = serde.deserialize(bytes, MarketTickPayload.class);

        assertEquals("BTCUSDT", decoded.symbol);
        assertEquals(1700000000333L, decoded.timestamp);
        assertEquals(5100012345L, decoded.price);
        assertEquals(25000000L, decoded.quantity);
        assertTrue(decoded.buy);
        assertEquals(99L, decoded.sequenceNumber);
    }

    @Test
    void shouldRoundTripBookDeltaPayload() {
        SbeEventSerde serde = new SbeEventSerde(new ObjectMapper());
        BookDeltaPayload payload = new BookDeltaPayload(
                "ETHUSDT",
                1700000000444L,
                "SELL",
                3000123456L,
                9000000L,
                "UPDATE",
                101L);

        byte[] bytes = serde.serialize(payload);
        BookDeltaPayload decoded = serde.deserialize(bytes, BookDeltaPayload.class);

        assertEquals("ETHUSDT", decoded.symbol);
        assertEquals(1700000000444L, decoded.timestamp);
        assertEquals("SELL", decoded.side);
        assertEquals(3000123456L, decoded.price);
        assertEquals(9000000L, decoded.quantity);
        assertEquals("UPDATE", decoded.action);
        assertEquals(101L, decoded.sequenceNumber);
    }

    static class UnknownPayload {
        public String symbol;
        public long sequence;
    }
}
