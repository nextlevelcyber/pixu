package com.bedrock.mm.oms.risk;

/**
 * RiskGuard - pre-execution risk checks for OMS.
 *
 * Design principles:
 * - Lightweight checks only (< 100ns per check)
 * - Called before every order placement
 * - Fast-fail on any violation (returns false)
 * - Hot-updatable parameters (volatile fields)
 * - Thread-safe for single-threaded OMS (no locks needed)
 *
 * Risk checks:
 * 1. Price deviation: order price must be within X% of fairMid
 * 2. Open order limit: total open orders per instrument
 * 3. Position limit: absolute net position per instrument
 * 4. Rate limit: max orders per second per instrument
 *
 * Usage:
 * 1. OMS calls preCheck() before submitting order to ExecGateway
 * 2. If false, reject order immediately (no exchange interaction)
 * 3. If true, proceed with order placement
 * 4. Management API can update limits via setXxx() methods
 */
public class RiskGuard {
    private static final long SCALE = 100_000_000L; // 1e8 for fixed-point arithmetic
    private static final long NS_PER_SECOND = 1_000_000_000L;

    // Risk limits (hot-updatable via volatile)
    private volatile long maxPriceDeviationBits = (long)(0.05 * SCALE); // 5% max deviation
    private volatile int maxOpenOrders = 100;
    private volatile long maxPositionBits = (long)(10.0 * SCALE); // 10 BTC absolute
    private volatile int maxOrdersPerSecond = 50;

    // Rate limiter state (single-threaded, no sync needed)
    private long orderCountThisSecond = 0;
    private long lastSecondNanos = System.nanoTime();

    /**
     * Immutable snapshot of risk limits for hot-update rollback.
     */
    public record RiskLimits(
            long maxPriceDeviationBits,
            int maxOpenOrders,
            long maxPositionBits,
            int maxOrdersPerSecond) {}

    /**
     * Pre-execution risk check.
     *
     * Called before every order placement. Fast-fail on any violation.
     *
     * Performance: < 100ns (no heap allocation, pure arithmetic)
     *
     * @param instrumentId instrument to trade (currently unused, for future per-instrument limits)
     * @param orderPrice order price (fixed-point 1e-8)
     * @param fairMid current fair mid price (fixed-point 1e-8)
     * @param currentOpenOrders number of open orders for this instrument
     * @param netPosition current net position (fixed-point 1e-8), positive = long, negative = short
     * @return true if order passes all checks, false otherwise
     */
    public boolean preCheck(int instrumentId, long orderPrice, long fairMid,
                             int currentOpenOrders, long netPosition) {
        // 1. Price deviation check
        // Ensure order price is within maxPriceDeviation of fairMid
        // Calculate percentage deviation: |orderPrice - fairMid| / fairMid
        long absDiff = Math.abs(orderPrice - fairMid);
        // Convert maxPriceDeviationBits (ratio in fixed-point) to absolute price threshold
        // maxAllowedDiff = fairMid * (maxPriceDeviationBits / SCALE)
        // To avoid overflow, reorder as: (fairMid / SCALE) * maxPriceDeviationBits
        // Even better: compare ratios to avoid division precision loss
        // absDiff / fairMid > maxPriceDeviationBits / SCALE
        // Multiply both sides by fairMid * SCALE (but this overflows)
        // Instead: check if absDiff * SCALE > fairMid * maxPriceDeviationBits
        // But that overflows too, so use division on the larger side:
        // absDiff > (fairMid * maxPriceDeviationBits) / SCALE
        // Reorder multiplication to prevent overflow:
        long maxAllowedDiff = (fairMid / SCALE) * maxPriceDeviationBits;
        if (absDiff > maxAllowedDiff) {
            return false;
        }

        // 2. Open order count check
        if (currentOpenOrders >= maxOpenOrders) {
            return false;
        }

        // 3. Position limit check
        // Check absolute position (long or short)
        if (Math.abs(netPosition) >= maxPositionBits) {
            return false;
        }

        // 4. Rate limit check
        // Reset counter every second
        long now = System.nanoTime();
        if (now - lastSecondNanos > NS_PER_SECOND) {
            orderCountThisSecond = 0;
            lastSecondNanos = now;
        }

        if (orderCountThisSecond >= maxOrdersPerSecond) {
            return false;
        }

        // Increment rate limiter (only if all checks pass)
        orderCountThisSecond++;

        return true;
    }

    /**
     * Sets maximum price deviation from fairMid.
     *
     * Example: setMaxPriceDeviation(0.05) = 5% max deviation
     *
     * @param ratio deviation ratio (e.g., 0.05 for 5%)
     */
    public void setMaxPriceDeviation(double ratio) {
        if (ratio < 0.0 || ratio > 1.0) {
            throw new IllegalArgumentException("ratio must be in [0.0, 1.0]: " + ratio);
        }
        maxPriceDeviationBits = (long)(ratio * SCALE);
    }

    /**
     * Sets maximum open orders per instrument.
     *
     * @param max maximum open orders
     */
    public void setMaxOpenOrders(int max) {
        if (max < 0) {
            throw new IllegalArgumentException("max must be >= 0: " + max);
        }
        maxOpenOrders = max;
    }

    /**
     * Sets maximum absolute position size.
     *
     * Example: setMaxPosition(10.0) = max 10 BTC long OR short
     *
     * @param qty maximum position size (absolute value)
     */
    public void setMaxPosition(double qty) {
        if (qty < 0.0) {
            throw new IllegalArgumentException("qty must be >= 0: " + qty);
        }
        maxPositionBits = (long)(qty * SCALE);
    }

    /**
     * Sets maximum orders per second rate limit.
     *
     * @param rate maximum orders per second
     */
    public void setMaxOrdersPerSecond(int rate) {
        if (rate < 0) {
            throw new IllegalArgumentException("rate must be >= 0: " + rate);
        }
        maxOrdersPerSecond = rate;
    }

    /**
     * Snapshot current risk limits, useful before applying runtime config changes.
     */
    public RiskLimits snapshotLimits() {
        return new RiskLimits(
                maxPriceDeviationBits,
                maxOpenOrders,
                maxPositionBits,
                maxOrdersPerSecond);
    }

    /**
     * Apply all limits atomically from a snapshot (or rollback snapshot).
     */
    public void applyLimits(RiskLimits limits) {
        if (limits == null) {
            throw new IllegalArgumentException("limits must not be null");
        }
        if (limits.maxPriceDeviationBits() < 0 || limits.maxPriceDeviationBits() > SCALE) {
            throw new IllegalArgumentException("maxPriceDeviationBits must be in [0, " + SCALE + "]");
        }
        if (limits.maxOpenOrders() < 0) {
            throw new IllegalArgumentException("maxOpenOrders must be >= 0");
        }
        if (limits.maxPositionBits() < 0) {
            throw new IllegalArgumentException("maxPositionBits must be >= 0");
        }
        if (limits.maxOrdersPerSecond() < 0) {
            throw new IllegalArgumentException("maxOrdersPerSecond must be >= 0");
        }
        maxPriceDeviationBits = limits.maxPriceDeviationBits();
        maxOpenOrders = limits.maxOpenOrders();
        maxPositionBits = limits.maxPositionBits();
        maxOrdersPerSecond = limits.maxOrdersPerSecond();
    }

    /**
     * Resets rate limiter state.
     *
     * Used for testing or manual intervention.
     */
    public void resetRateLimiter() {
        orderCountThisSecond = 0;
        lastSecondNanos = System.nanoTime();
    }

    // Getters for testing

    public long getMaxPriceDeviation() {
        return maxPriceDeviationBits;
    }

    public int getMaxOpenOrders() {
        return maxOpenOrders;
    }

    public long getMaxPosition() {
        return maxPositionBits;
    }

    public int getMaxOrdersPerSecond() {
        return maxOrdersPerSecond;
    }

    public long getOrderCountThisSecond() {
        return orderCountThisSecond;
    }
}
