package com.bedrock.mm.oms.position;

/**
 * Delta hedging manager for OMS.
 *
 * Determines when to trigger hedge orders based on net position delta.
 *
 * Design principles:
 * - Lightweight decision logic only (no order execution)
 * - Rate limiting to prevent hedge order spam
 * - Configurable delta threshold per instrument
 * - Hedge ratio allows partial hedging (e.g., hedge 50% of delta)
 *
 * Usage flow:
 * 1. After each fill, call needsHedge() to check if hedging is required
 * 2. If true, call calcHedgeSize() to compute hedge order size
 * 3. Submit hedge order via OMS
 * 4. Call onHedgeSent() to update rate limiter
 */
public class HedgeManager {
    private static final long SCALE = 100_000_000L; // 1e8 for fixed-point arithmetic

    /**
     * Absolute delta threshold (fixed-point 1e-8).
     * Hedge triggered when abs(delta) > threshold.
     */
    private final long deltaThresholdBits;

    /**
     * Instrument to use for hedging (e.g., spot hedges perpetual).
     */
    private final int hedgeInstrumentId;

    /**
     * Hedge ratio (0.0 to 1.0).
     * 1.0 = full hedge, 0.5 = hedge 50% of delta, etc.
     */
    private final double hedgeRatio;

    /**
     * Timestamp (nanos) of last hedge order sent.
     */
    private long lastHedgeNanos = 0;

    /**
     * Minimum interval between hedge orders (nanoseconds).
     * Prevents hedge order spam during rapid position changes.
     */
    private static final long MIN_HEDGE_INTERVAL_NS = 1_000_000L; // 1ms

    /**
     * Creates a HedgeManager.
     *
     * @param deltaThreshold absolute delta threshold (fixed-point 1e-8)
     * @param hedgeInstrumentId instrument ID to use for hedge orders
     * @param hedgeRatio hedge ratio (0.0 to 1.0)
     */
    public HedgeManager(long deltaThreshold, int hedgeInstrumentId, double hedgeRatio) {
        if (hedgeRatio < 0.0 || hedgeRatio > 1.0) {
            throw new IllegalArgumentException("hedgeRatio must be in [0.0, 1.0]: " + hedgeRatio);
        }
        this.deltaThresholdBits = Math.abs(deltaThreshold);
        this.hedgeInstrumentId = hedgeInstrumentId;
        this.hedgeRatio = hedgeRatio;
    }

    /**
     * Checks if hedging is needed for an instrument.
     *
     * Hedge triggered when:
     * 1. abs(delta) > threshold
     * 2. Enough time has elapsed since last hedge (rate limiting)
     *
     * @param positions position tracker to query
     * @param instrumentId instrument to check
     * @return true if hedge should be sent
     */
    public boolean needsHedge(PositionTracker positions, int instrumentId) {
        long delta = Math.abs(positions.getNetPosition(instrumentId));

        if (delta <= deltaThresholdBits) {
            return false;
        }

        long currentNanos = System.nanoTime();
        long elapsedNanos = currentNanos - lastHedgeNanos;

        return elapsedNanos > MIN_HEDGE_INTERVAL_NS;
    }

    /**
     * Calculates hedge order size.
     *
     * Hedge size is computed to offset the current delta:
     * - If long position (positive delta): sell hedge (negative size)
     * - If short position (negative delta): buy hedge (positive size)
     *
     * Formula: hedgeSize = -netPosition * hedgeRatio
     *
     * @param positions position tracker to query
     * @param instrumentId instrument to hedge
     * @return hedge size (fixed-point 1e-8), positive = buy, negative = sell
     */
    public long calcHedgeSize(PositionTracker positions, int instrumentId) {
        long netPos = positions.getNetPosition(instrumentId);

        // Negate to offset position, then apply ratio
        // Example: long 1.0 BTC → hedge = -1.0 * 1.0 = -1.0 (sell 1.0)
        double hedgeDouble = -(netPos / (double) SCALE) * hedgeRatio;

        return (long) (hedgeDouble * SCALE);
    }

    /**
     * Records that a hedge order was sent.
     *
     * Updates the rate limiter timestamp.
     * Call this after successfully submitting a hedge order.
     */
    public void onHedgeSent() {
        lastHedgeNanos = System.nanoTime();
    }

    /**
     * Gets the hedge instrument ID.
     *
     * @return instrument ID used for hedge orders
     */
    public int getHedgeInstrumentId() {
        return hedgeInstrumentId;
    }

    /**
     * Gets the delta threshold.
     *
     * @return delta threshold (fixed-point 1e-8)
     */
    public long getDeltaThreshold() {
        return deltaThresholdBits;
    }

    /**
     * Gets the hedge ratio.
     *
     * @return hedge ratio (0.0 to 1.0)
     */
    public double getHedgeRatio() {
        return hedgeRatio;
    }

    /**
     * Resets the rate limiter (for testing).
     */
    public void resetRateLimiter() {
        lastHedgeNanos = 0;
    }
}
