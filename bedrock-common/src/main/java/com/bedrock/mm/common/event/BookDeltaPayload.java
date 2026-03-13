package com.bedrock.mm.common.event;

/**
 * Unified payload for BOOK_DELTA events.
 */
public class BookDeltaPayload {
    public String symbol;
    public long timestamp;
    public String side;
    public long price;
    public long quantity;
    public String action;
    public long sequenceNumber;

    public BookDeltaPayload() {
    }

    public BookDeltaPayload(String symbol, long timestamp, String side, long price, long quantity, String action, long sequenceNumber) {
        this.symbol = symbol;
        this.timestamp = timestamp;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.action = action;
        this.sequenceNumber = sequenceNumber;
    }
}
