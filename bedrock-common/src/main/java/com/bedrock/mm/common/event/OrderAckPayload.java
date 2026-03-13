package com.bedrock.mm.common.event;

/**
 * Payload for ORDER_ACK events published on the unified event bus.
 * Kept simple to avoid cross-module coupling.
 */
public class OrderAckPayload {
    public String symbol;           // e.g., BTCUSDT
    public String orderId;          // exchange order id
    public String clientOrderId;    // client order id if present
    public String side;             // BUY/SELL
    public String orderType;        // LIMIT/MARKET/...
    public String status;           // NEW/PARTIALLY_FILLED/FILLED/CANCELED/...
    public String price;            // order price as string to preserve precision
    public String quantity;         // order quantity as string
    public long eventTimeMs;        // exchange event time in milliseconds

    public OrderAckPayload() {}

    public OrderAckPayload(String symbol, String orderId, String clientOrderId,
                           String side, String orderType, String status,
                           String price, String quantity, long eventTimeMs) {
        this.symbol = symbol;
        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.side = side;
        this.orderType = orderType;
        this.status = status;
        this.price = price;
        this.quantity = quantity;
        this.eventTimeMs = eventTimeMs;
    }
}