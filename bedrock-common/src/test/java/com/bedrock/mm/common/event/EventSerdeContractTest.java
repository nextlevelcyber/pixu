package com.bedrock.mm.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventSerdeContractTest {

    @Test
    void shouldRoundTripAllCorePayloadsAcrossJsonAndSbe() {
        EventSerde[] serdes = new EventSerde[] {
                new JsonEventSerde(new ObjectMapper()),
                new SbeEventSerde(new ObjectMapper())
        };
        for (EventSerde serde : serdes) {
            assertOrderCommand(serde);
            assertOrderAck(serde);
            assertFill(serde);
            assertMarketTick(serde);
            assertBookDelta(serde);
        }
    }

    private void assertOrderCommand(EventSerde serde) {
        OrderCommand payload = new OrderCommand();
        payload.setClientOrderId("cid-contract-1");
        payload.setSymbol("BTCUSDT");
        payload.setSide("BUY");
        payload.setType("LIMIT");
        payload.setPrice((long)(50123.45 * 100_000_000L));
        payload.setQuantity((long)(0.75 * 100_000_000L));
        payload.setTimeInForce("GTC");
        payload.setStrategyId("strat-a");

        byte[] bytes = serde.serialize(payload);
        OrderCommand decoded = serde.deserialize(bytes, OrderCommand.class);
        assertEquals(payload.getClientOrderId(), decoded.getClientOrderId());
        assertEquals(payload.getSymbol(), decoded.getSymbol());
        assertEquals(payload.getSide(), decoded.getSide());
        assertEquals(payload.getType(), decoded.getType());
        assertEquals(payload.getPrice(), decoded.getPrice());
        assertEquals(payload.getQuantity(), decoded.getQuantity());
        assertEquals(payload.getTimeInForce(), decoded.getTimeInForce());
        assertEquals(payload.getStrategyId(), decoded.getStrategyId());
    }

    private void assertOrderAck(EventSerde serde) {
        OrderAckPayload payload = new OrderAckPayload(
                "BTCUSDT", "oid-1", "cid-1", "BUY", "LIMIT", "NEW",
                "50123.45", "0.75", 1700000000001L);
        byte[] bytes = serde.serialize(payload);
        OrderAckPayload decoded = serde.deserialize(bytes, OrderAckPayload.class);
        assertEquals(payload.symbol, decoded.symbol);
        assertEquals(payload.orderId, decoded.orderId);
        assertEquals(payload.clientOrderId, decoded.clientOrderId);
        assertEquals(payload.side, decoded.side);
        assertEquals(payload.orderType, decoded.orderType);
        assertEquals(payload.status, decoded.status);
        assertEquals(payload.price, decoded.price);
        assertEquals(payload.quantity, decoded.quantity);
        assertEquals(payload.eventTimeMs, decoded.eventTimeMs);
    }

    private void assertFill(EventSerde serde) {
        FillPayload payload = new FillPayload(
                "ETHUSDT", "oid-2", "cid-2", "tid-1", "SELL",
                "2500.10", "1.20", "TAKER", "0.003", "USDT", 1700000000002L);
        byte[] bytes = serde.serialize(payload);
        FillPayload decoded = serde.deserialize(bytes, FillPayload.class);
        assertEquals(payload.symbol, decoded.symbol);
        assertEquals(payload.orderId, decoded.orderId);
        assertEquals(payload.clientOrderId, decoded.clientOrderId);
        assertEquals(payload.tradeId, decoded.tradeId);
        assertEquals(payload.side, decoded.side);
        assertEquals(payload.price, decoded.price);
        assertEquals(payload.quantity, decoded.quantity);
        assertEquals(payload.liquidity, decoded.liquidity);
        assertEquals(payload.commission, decoded.commission);
        assertEquals(payload.commissionAsset, decoded.commissionAsset);
        assertEquals(payload.eventTimeMs, decoded.eventTimeMs);
    }

    private void assertMarketTick(EventSerde serde) {
        MarketTickPayload payload = new MarketTickPayload("BTCUSDT", 1700000000003L, 51000123L, 1200000L, true, 111L);
        byte[] bytes = serde.serialize(payload);
        MarketTickPayload decoded = serde.deserialize(bytes, MarketTickPayload.class);
        assertEquals(payload.symbol, decoded.symbol);
        assertEquals(payload.timestamp, decoded.timestamp);
        assertEquals(payload.price, decoded.price);
        assertEquals(payload.quantity, decoded.quantity);
        assertEquals(payload.sequenceNumber, decoded.sequenceNumber);
        assertTrue(decoded.buy);
    }

    private void assertBookDelta(EventSerde serde) {
        BookDeltaPayload payload = new BookDeltaPayload("BTCUSDT", 1700000000004L, "SELL", 51000100L, 900000L, "UPDATE", 112L);
        byte[] bytes = serde.serialize(payload);
        BookDeltaPayload decoded = serde.deserialize(bytes, BookDeltaPayload.class);
        assertEquals(payload.symbol, decoded.symbol);
        assertEquals(payload.timestamp, decoded.timestamp);
        assertEquals(payload.side, decoded.side);
        assertEquals(payload.price, decoded.price);
        assertEquals(payload.quantity, decoded.quantity);
        assertEquals(payload.action, decoded.action);
        assertEquals(payload.sequenceNumber, decoded.sequenceNumber);
    }
}
