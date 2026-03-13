package com.bedrock.mm.oms.model;

/**
 * Position event published from OMS to Pricing Engine.
 *
 * Communicated via Aeron channel 'oms.position' to inform the Pricing Engine
 * of current position state for delta skew calculations.
 *
 * Design constraints:
 * - All numeric fields are long (fixed-point 1e-8 scale)
 * - Published after every fill that changes position
 * - Non-blocking async publish (not on hot path)
 */
public class PositionEvent {
    public long seqId;
    public int instrumentId;

    /**
     * Net position (fixed-point 1e-8 scale).
     * Positive = long position, Negative = short position.
     */
    public long netPosition;

    /**
     * Current delta exposure (fixed-point 1e-8 scale).
     * For spot: delta = netPosition
     * For futures: delta may differ based on contract multiplier
     */
    public long delta;

    /**
     * Unrealized PnL (fixed-point 1e-8 scale).
     */
    public long unrealizedPnl;

    /**
     * System.nanoTime() when this event was published.
     */
    public long publishNanos;

    /**
     * Resets all fields for object pool reuse.
     */
    public void reset() {
        seqId = 0;
        instrumentId = 0;
        netPosition = 0;
        delta = 0;
        unrealizedPnl = 0;
        publishNanos = 0;
    }
}
