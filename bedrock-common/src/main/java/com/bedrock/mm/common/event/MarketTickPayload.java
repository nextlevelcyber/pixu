package com.bedrock.mm.common.event;

/**
 * Unified payload for MARKET_TICK events.
 */
public class MarketTickPayload {
    public String symbol;
    public long timestamp;
    public long price;
    public long quantity;
    public boolean buy;
    public long sequenceNumber;

    public MarketTickPayload() {
    }

    public MarketTickPayload(String symbol, long timestamp, long price, long quantity, boolean buy, long sequenceNumber) {
        this.symbol = symbol;
        this.timestamp = timestamp;
        this.price = price;
        this.quantity = quantity;
        this.buy = buy;
        this.sequenceNumber = sequenceNumber;
    }
}
