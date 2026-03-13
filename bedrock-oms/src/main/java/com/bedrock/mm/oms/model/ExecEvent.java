package com.bedrock.mm.oms.model;

/**
 * OMS internal execution event model.
 *
 * This is NOT the SBE-generated ExecEvent. This is the internal OMS representation
 * used for order state transitions and position tracking.
 *
 * Design constraints:
 * - All prices and quantities are long (fixed-point 1e-8 scale)
 * - Object pooling friendly (reset() method for reuse)
 * - No heap allocation on hot path
 */
public class ExecEvent {
    public long seqId;
    /**
     * System.nanoTime() when the event was received/generated in gateway threads.
     * Used for queue-delay and exec-to-state latency metrics.
     */
    public long recvNanos;
    public int instrumentId;
    public long internalOrderId;
    public long exchOrderId;
    public ExecEventType type;

    /**
     * Fill price (fixed-point 1e-8 scale).
     * Zero if not a fill event.
     */
    public long fillPrice;

    /**
     * Fill size (fixed-point 1e-8 scale).
     * Zero if not a fill event.
     */
    public long fillSize;

    /**
     * Remaining size after this event.
     * For FILL events, this should be zero.
     */
    public long remainSize;

    /**
     * Whether this order is a bid (buy) order.
     * Used to determine position direction (bid fill = increase, ask fill = decrease).
     */
    public boolean isBid;

    /**
     * Reject reason (only populated for REJECTED events, null otherwise).
     */
    public String rejectReason;

    /**
     * Resets all fields for object pool reuse.
     * Critical for hot-path zero-allocation design.
     */
    public void reset() {
        seqId = 0;
        recvNanos = 0;
        instrumentId = 0;
        internalOrderId = 0;
        exchOrderId = 0;
        type = null;
        fillPrice = 0;
        fillSize = 0;
        remainSize = 0;
        isBid = false;
        rejectReason = null;
    }
}
