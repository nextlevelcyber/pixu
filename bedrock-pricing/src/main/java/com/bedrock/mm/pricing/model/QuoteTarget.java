package com.bedrock.mm.pricing.model;

public class QuoteTarget {
    public long seqId;
    public long publishNanos;
    public int instrumentId;
    public int regionIndex = -1;
    public long bidPrice;
    public long askPrice;
    public long bidSize;
    public long askSize;
    public long fairMid;
    public int flags;

    public QuoteTarget copy() {
        QuoteTarget c = new QuoteTarget();
        c.seqId = this.seqId;
        c.publishNanos = this.publishNanos;
        c.instrumentId = this.instrumentId;
        c.regionIndex = this.regionIndex;
        c.bidPrice = this.bidPrice;
        c.askPrice = this.askPrice;
        c.bidSize = this.bidSize;
        c.askSize = this.askSize;
        c.fairMid = this.fairMid;
        c.flags = this.flags;
        return c;
    }

    public void reset() {
        seqId = 0;
        publishNanos = 0;
        instrumentId = 0;
        regionIndex = -1;
        bidPrice = 0;
        askPrice = 0;
        bidSize = 0;
        askSize = 0;
        fairMid = 0;
        flags = 0;
    }
}
