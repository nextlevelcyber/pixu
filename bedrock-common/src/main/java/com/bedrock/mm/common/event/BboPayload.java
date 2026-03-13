package com.bedrock.mm.common.event;

/**
 * Payload for BBO (Best Bid and Offer) events.
 * All prices and sizes are fixed-point long (scale 1e-8).
 */
public class BboPayload {
    public String symbol;
    public long bidPrice;
    public long bidSize;
    public long askPrice;
    public long askSize;
    public long recvNanos;
    public long sequenceNumber;

    public BboPayload() {}

    public BboPayload(String symbol, long bidPrice, long bidSize, long askPrice, long askSize,
                      long recvNanos, long sequenceNumber) {
        this.symbol = symbol;
        this.bidPrice = bidPrice;
        this.bidSize = bidSize;
        this.askPrice = askPrice;
        this.askSize = askSize;
        this.recvNanos = recvNanos;
        this.sequenceNumber = sequenceNumber;
    }
}
