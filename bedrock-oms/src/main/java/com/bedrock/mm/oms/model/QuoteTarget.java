package com.bedrock.mm.oms.model;

/**
 * QuoteTarget - target quote specification from Pricing Engine.
 *
 * Temporary placeholder until full integration with bedrock-common event model.
 */
public class QuoteTarget {
    public long seqId;
    public long publishNanos;
    public int instrumentId;
    public int regionIndex;      // Target region index, -1 for global update
    public long bidPrice;        // fixed-point 1e-8
    public long askPrice;        // fixed-point 1e-8
    public long bidSize;         // fixed-point 1e-8
    public long askSize;         // fixed-point 1e-8
    public long fairMid;         // For region boundary calculation
    public int flags;            // Bit flags: pause quoting, force refresh, etc.
}
