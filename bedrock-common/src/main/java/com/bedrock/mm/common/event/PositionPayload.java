package com.bedrock.mm.common.event;

/**
 * Position update payload for bus publication.
 * Published by OmsCoordinator after each fill; consumed by PricingBusConsumer
 * to update delta skew in the PricingOrchestrator.
 *
 * All monetary values are fixed-point long (scale 1e-8).
 */
public class PositionPayload {
    public String symbol;
    public int instrumentId;
    /** Net position (1e-8 scale). Positive = long, negative = short. */
    public long netPosition;
    /** Position delta from this fill (1e-8 scale). */
    public long delta;
    /** Unrealized PnL (1e-8 scale). */
    public long unrealizedPnl;
    public long publishNanos;
    public long seqId;

    public PositionPayload() {}

    public PositionPayload(String symbol, int instrumentId, long netPosition, long delta,
                           long unrealizedPnl, long publishNanos, long seqId) {
        this.symbol = symbol;
        this.instrumentId = instrumentId;
        this.netPosition = netPosition;
        this.delta = delta;
        this.unrealizedPnl = unrealizedPnl;
        this.publishNanos = publishNanos;
        this.seqId = seqId;
    }
}
