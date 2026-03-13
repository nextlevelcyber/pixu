package com.bedrock.mm.oms.region;

import com.bedrock.mm.oms.store.PriceRegion;

/**
 * Calculates price region absolute boundaries from fairMid + ratios.
 *
 * Design:
 * - PriceRegion stores minRatio/maxRatio (e.g., -0.01 to -0.005 for bid region)
 * - This calculator converts fairMid * (1 + ratio) to absolute prices
 * - Rounds to tick size for exchange compatibility
 * - Updates PriceRegion.minPrice and maxPrice fields
 *
 * Performance:
 * - Not on critical hot path (called when fairMid updates, ~1-10Hz)
 * - Uses double arithmetic for price calculation (acceptable here)
 * - < 1μs per region update
 */
public class RegionBoundaryCalculator {
    private static final long SCALE = 100_000_000L; // 1e8 for fixed-point arithmetic

    /**
     * Updates PriceRegion absolute price bounds from fairMid + ratios.
     *
     * Formula:
     * - minPrice = round(fairMid * (1 + minRatio) / tickSize) * tickSize
     * - maxPrice = round(fairMid * (1 + maxRatio) / tickSize) * tickSize
     *
     * @param region price region to update
     * @param fairMid fair mid price (fixed-point 1e-8)
     * @param tickSize minimum price increment (e.g., 0.01 for BTC/USDT)
     */
    public void updateBoundaries(PriceRegion region, long fairMid, double tickSize) {
        // Convert fairMid from fixed-point 1e-8 to double
        double mid = fairMid / (double) SCALE;

        // Calculate absolute prices from ratios
        double minPriceDouble = mid * (1.0 + region.minRatio);
        double maxPriceDouble = mid * (1.0 + region.maxRatio);

        // Round to tick size and convert back to fixed-point
        long minPriceFixed = (long) (roundToTick(minPriceDouble, tickSize) * SCALE);
        long maxPriceFixed = (long) (roundToTick(maxPriceDouble, tickSize) * SCALE);

        // Update region boundaries
        region.setMinPrice(minPriceFixed);
        region.setMaxPrice(maxPriceFixed);
    }

    /**
     * Rounds price to nearest tick size.
     *
     * Example: roundToTick(50000.12, 0.01) = 50000.12
     *          roundToTick(50000.126, 0.01) = 50000.13
     *
     * @param price price to round
     * @param tickSize tick size
     * @return rounded price
     */
    private double roundToTick(double price, double tickSize) {
        return Math.round(price / tickSize) * tickSize;
    }
}
