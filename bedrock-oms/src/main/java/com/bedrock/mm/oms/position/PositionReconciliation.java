package com.bedrock.mm.oms.position;

import java.util.List;

/**
 * Position reconciliation utility.
 *
 * Used during OMS startup to reconcile internal position state with
 * exchange position snapshots (fetched via REST).
 *
 * Design principles:
 * - Simple POJO for REST response parsing
 * - Applies exchange snapshot to PositionTracker
 * - Called once on startup, not on hot path
 *
 * Usage:
 * 1. OMS starts up
 * 2. Fetch positions from exchange REST API
 * 3. Parse into List<RestPosition>
 * 4. Call applySnapshot() to initialize PositionTracker
 */
public class PositionReconciliation {

    /**
     * Simple POJO for REST position snapshot parsing.
     *
     * Exchange-specific implementations will map their REST response
     * to this common format.
     */
    public static class RestPosition {
        public int instrumentId;

        /**
         * Net position from exchange (fixed-point 1e-8).
         * Positive = long, Negative = short.
         */
        public long netPosition;

        /**
         * Average entry price (fixed-point 1e-8).
         * May be zero if exchange doesn't provide it.
         */
        public long avgEntryPrice;

        /**
         * Unrealized PnL (fixed-point 1e-8).
         * May be zero if exchange doesn't provide it.
         */
        public long unrealizedPnl;

        public RestPosition(int instrumentId, long netPosition) {
            this.instrumentId = instrumentId;
            this.netPosition = netPosition;
            this.avgEntryPrice = 0;
            this.unrealizedPnl = 0;
        }

        public RestPosition(int instrumentId, long netPosition, long avgEntryPrice, long unrealizedPnl) {
            this.instrumentId = instrumentId;
            this.netPosition = netPosition;
            this.avgEntryPrice = avgEntryPrice;
            this.unrealizedPnl = unrealizedPnl;
        }
    }

    /**
     * Applies REST position snapshot to PositionTracker.
     *
     * Called once during OMS startup to initialize position state
     * from exchange ground truth.
     *
     * Note: This sets netPosition directly but doesn't reconstruct
     * fillValueSum or totalFillSize. Average entry price tracking
     * starts fresh from this point. For more accurate entry price,
     * the exchange's avgEntryPrice can be stored separately.
     *
     * @param positions list of positions from exchange REST API
     * @param tracker position tracker to initialize
     */
    public void applySnapshot(List<RestPosition> positions, PositionTracker tracker) {
        if (positions == null || positions.isEmpty()) {
            return;
        }

        for (RestPosition pos : positions) {
            if (pos.netPosition == 0) {
                tracker.reset(pos.instrumentId);
                continue;
            }
            tracker.reconcile(pos.instrumentId, pos.netPosition, pos.avgEntryPrice);
        }
    }

    /**
     * Compares current OMS position with exchange position.
     *
     * Returns difference that needs reconciliation.
     *
     * @param instrumentId instrument to check
     * @param exchangePosition position from exchange REST
     * @param tracker current OMS position tracker
     * @return position difference (fixed-point 1e-8), positive = OMS has more than exchange
     */
    public long calculateDrift(int instrumentId, long exchangePosition, PositionTracker tracker) {
        long omsPosition = tracker.getNetPosition(instrumentId);
        return omsPosition - exchangePosition;
    }
}
