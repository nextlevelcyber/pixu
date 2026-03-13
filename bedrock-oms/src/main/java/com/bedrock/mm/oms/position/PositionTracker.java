package com.bedrock.mm.oms.position;

import com.bedrock.mm.oms.model.ExecEvent;
import com.bedrock.mm.oms.model.PositionEvent;

/**
 * High-performance position tracker for OMS.
 *
 * Design principles:
 * - Pure primitive arrays indexed by instrumentId (max 64 instruments)
 * - Hot path onFill() must complete in < 500ns with ZERO heap allocation
 * - Single-threaded only (called from Disruptor consumer thread)
 * - Position calculated from fills, not from order states
 *
 * Position calculation:
 * - Bid fill: netPosition increases by fillSize
 * - Ask fill: netPosition decreases by fillSize
 * - Avg entry price: sum(fillPrice * fillSize) / sum(fillSize)
 * - Unrealized PnL: (markPrice - avgEntryPrice) * netPosition
 */
public class PositionTracker {
    private static final int MAX_INSTRUMENTS = 64;
    private static final long SCALE = 100_000_000L; // 1e8 for fixed-point arithmetic

    /**
     * Net position per instrument (fixed-point 1e-8).
     * Positive = long, Negative = short.
     */
    private final long[] netPosition = new long[MAX_INSTRUMENTS];

    /**
     * Weighted price accumulator for avg entry price calculation.
     * Stores: sum(fillPrice_int * fillSize_1e8), where fillPrice_int = fillPrice / SCALE.
     * Avoids long overflow: direct fillPrice * fillSize would be 1e16-scale and overflow for
     * typical crypto prices (e.g. $50000 * 1 BTC = 5e12 * 1e8 = 5e20 > Long.MAX_VALUE).
     * Retrieval: (fillValueSum / totalFillSize) * SCALE → avgEntryPrice in 1e-8 scale.
     */
    private final long[] fillValueSum = new long[MAX_INSTRUMENTS];

    /**
     * Total fill size (absolute value, always positive).
     * Used with fillValueSum to compute avgEntryPrice.
     */
    private final long[] totalFillSize = new long[MAX_INSTRUMENTS];

    /**
     * Unrealized PnL per instrument (fixed-point 1e-8).
     */
    private final long[] unrealizedPnl = new long[MAX_INSTRUMENTS];

    /**
     * Last mark price used for PnL calculation (fixed-point 1e-8).
     */
    private final long[] lastMarkPrice = new long[MAX_INSTRUMENTS];

    /**
     * Sequence counter for PositionEvent.seqId.
     */
    private long seqCounter = 0;

    /**
     * Hot path: called for each fill event.
     *
     * Performance constraint: < 500ns, NO heap allocation.
     *
     * @param event fill event (PARTIAL_FILL or FILL)
     */
    public void onFill(ExecEvent event) {
        int id = event.instrumentId;

        // Calculate position change: bid fill increases, ask fill decreases
        long positionDelta = event.isBid ? event.fillSize : -event.fillSize;

        netPosition[id] += positionDelta;

        // Accumulate: (fillPrice / SCALE) * fillSize to avoid long overflow.
        // fillPrice / SCALE = integer price (e.g. 50000 for $50000).
        // Stored units: price_int * size_1e8. Retrieval multiplies by SCALE to restore 1e-8 scale.
        fillValueSum[id] += (event.fillPrice / SCALE) * event.fillSize;
        totalFillSize[id] += event.fillSize;

        // Recalculate unrealized PnL if we have a mark price
        if (lastMarkPrice[id] != 0) {
            recalculatePnl(id);
        }
    }

    /**
     * Updates mark price for an instrument and recalculates unrealized PnL.
     *
     * Called periodically when new market data arrives (BBO updates).
     * Not on critical hot path (< 10μs acceptable).
     *
     * @param instrumentId instrument identifier
     * @param markPrice current mark price (fixed-point 1e-8)
     */
    public void updateMarkPrice(int instrumentId, long markPrice) {
        lastMarkPrice[instrumentId] = markPrice;
        recalculatePnl(instrumentId);
    }

    /**
     * Recalculates unrealized PnL for an instrument.
     *
     * Formula: (markPrice - avgEntryPrice) * netPosition
     *
     * @param instrumentId instrument identifier
     */
    private void recalculatePnl(int instrumentId) {
        long pos = netPosition[instrumentId];
        if (pos == 0) {
            unrealizedPnl[instrumentId] = 0;
            return;
        }

        long totalSize = totalFillSize[instrumentId];
        if (totalSize == 0) {
            unrealizedPnl[instrumentId] = 0;
            return;
        }

        // avgEntry in 1e-8 scale: (fillValueSum / totalFillSize) * SCALE
        // fillValueSum stores price_int * size_1e8; dividing by totalFillSize (1e8 scale) gives price_int.
        // Multiply by SCALE to convert back to 1e-8 fixed-point.
        long avgEntry = (fillValueSum[instrumentId] / totalSize) * SCALE;

        // PnL_1e8 = (priceDiff_1e8 / SCALE) * pos_1e8
        // Must divide priceDiff first to avoid overflow: priceDiff * pos can be ~5e11 * 1e8 = 5e19 > Long.MAX_VALUE.
        long priceDiff = lastMarkPrice[instrumentId] - avgEntry;
        unrealizedPnl[instrumentId] = (priceDiff / SCALE) * pos;
    }

    /**
     * Builds a PositionEvent for publication to Aeron.
     *
     * Reuses provided PositionEvent object to avoid allocation.
     *
     * @param instrumentId instrument identifier
     * @param reuse pre-allocated PositionEvent to populate
     * @return the populated PositionEvent (same instance as reuse param)
     */
    public PositionEvent buildPositionEvent(int instrumentId, PositionEvent reuse) {
        reuse.seqId = ++seqCounter;
        reuse.instrumentId = instrumentId;
        reuse.netPosition = netPosition[instrumentId];
        reuse.delta = netPosition[instrumentId]; // For spot: delta = netPosition
        reuse.unrealizedPnl = unrealizedPnl[instrumentId];
        reuse.publishNanos = System.nanoTime();
        return reuse;
    }

    /**
     * Resets position state for an instrument.
     *
     * Used during reconciliation or when closing out a position.
     *
     * @param instrumentId instrument identifier
     */
    public void reset(int instrumentId) {
        netPosition[instrumentId] = 0;
        fillValueSum[instrumentId] = 0;
        totalFillSize[instrumentId] = 0;
        unrealizedPnl[instrumentId] = 0;
        lastMarkPrice[instrumentId] = 0;
    }

    /**
     * Reconciles position state from exchange snapshot.
     *
     * Cold-path API for OMS startup recovery, not used on the trading hot path.
     * Initializes net position and average entry price directly from REST truth.
     *
     * @param instrumentId instrument identifier
     * @param netPositionValue net position (fixed-point 1e-8)
     * @param avgEntryPrice average entry price (fixed-point 1e-8)
     */
    public void reconcile(int instrumentId, long netPositionValue, long avgEntryPrice) {
        // Always clear previous state first to avoid mixing snapshots with live fills.
        reset(instrumentId);

        if (netPositionValue == 0) {
            return;
        }

        netPosition[instrumentId] = netPositionValue;

        // Restore avg-entry tracking with synthetic accumulators:
        // avgEntry = (fillValueSum / totalFillSize) * SCALE
        // => fillValueSum = (avgEntry / SCALE) * totalFillSize
        long absPosition = Math.abs(netPositionValue);
        totalFillSize[instrumentId] = absPosition;
        fillValueSum[instrumentId] = (avgEntryPrice / SCALE) * absPosition;

        if (lastMarkPrice[instrumentId] != 0) {
            recalculatePnl(instrumentId);
        }
    }

    /**
     * Gets current net position for an instrument.
     *
     * @param instrumentId instrument identifier
     * @return net position (fixed-point 1e-8)
     */
    public long getNetPosition(int instrumentId) {
        return netPosition[instrumentId];
    }

    /**
     * Gets unrealized PnL for an instrument.
     *
     * @param instrumentId instrument identifier
     * @return unrealized PnL (fixed-point 1e-8)
     */
    public long getUnrealizedPnl(int instrumentId) {
        return unrealizedPnl[instrumentId];
    }

    /**
     * Gets average entry price for an instrument.
     *
     * @param instrumentId instrument identifier
     * @return average entry price (fixed-point 1e-8), or 0 if no fills
     */
    public long getAvgEntryPrice(int instrumentId) {
        long totalSize = totalFillSize[instrumentId];
        if (totalSize == 0) {
            return 0;
        }
        // fillValueSum stores price_int * size_1e8; dividing by totalFillSize gives price_int.
        // Multiply by SCALE to return avgEntryPrice in 1e-8 fixed-point scale.
        return (fillValueSum[instrumentId] / totalSize) * SCALE;
    }
}
