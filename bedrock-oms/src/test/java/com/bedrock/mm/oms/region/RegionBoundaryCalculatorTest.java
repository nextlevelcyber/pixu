package com.bedrock.mm.oms.region;

import com.bedrock.mm.oms.store.PriceRegion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RegionBoundaryCalculator.
 *
 * Validates:
 * - Boundary calculation from fairMid + ratios
 * - Tick size rounding
 * - Positive and negative ratio handling
 */
class RegionBoundaryCalculatorTest {
    private static final long SCALE = 100_000_000L; // 1e8
    private static final double EPSILON = 0.0001; // Tolerance for double comparison

    private RegionBoundaryCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new RegionBoundaryCalculator();
    }

    @Test
    void testBidRegionBoundaryCalculation() {
        // Given: Bid region with -1% to -0.5% ratio
        PriceRegion region = new PriceRegion(0, true, -0.01, -0.005);
        long fairMid = toFixed(50000.0); // $50,000
        double tickSize = 0.01;

        // When: Update boundaries
        calculator.updateBoundaries(region, fairMid, tickSize);

        // Then: minPrice = 50000 * 0.99 = 49500, maxPrice = 50000 * 0.995 = 49750
        assertEquals(toFixed(49500.0), region.getMinPrice());
        assertEquals(toFixed(49750.0), region.getMaxPrice());
    }

    @Test
    void testAskRegionBoundaryCalculation() {
        // Given: Ask region with +0.5% to +1% ratio
        PriceRegion region = new PriceRegion(0, false, 0.005, 0.01);
        long fairMid = toFixed(50000.0);
        double tickSize = 0.01;

        // When: Update boundaries
        calculator.updateBoundaries(region, fairMid, tickSize);

        // Then: minPrice = 50000 * 1.005 = 50250, maxPrice = 50000 * 1.01 = 50500
        assertEquals(toFixed(50250.0), region.getMinPrice());
        assertEquals(toFixed(50500.0), region.getMaxPrice());
    }

    @Test
    void testTickSizeRounding() {
        // Given: Region with ratio that produces non-tick-aligned price
        PriceRegion region = new PriceRegion(0, true, -0.01, -0.005);
        long fairMid = toFixed(50000.12); // Non-round fairMid
        double tickSize = 0.1; // Larger tick size

        // When: Update boundaries
        calculator.updateBoundaries(region, fairMid, tickSize);

        // Then: Prices should be rounded to nearest 0.1
        // 50000.12 * 0.99 = 49500.1188 → rounds to 49500.1
        // 50000.12 * 0.995 = 49750.1194 → rounds to 49750.1
        assertEquals(toFixed(49500.1), region.getMinPrice());
        assertEquals(toFixed(49750.1), region.getMaxPrice());
    }

    @Test
    void testZeroRatio() {
        // Given: Region with zero ratios (boundary = fairMid)
        PriceRegion region = new PriceRegion(0, true, 0.0, 0.0);
        long fairMid = toFixed(50000.0);
        double tickSize = 0.01;

        // When: Update boundaries
        calculator.updateBoundaries(region, fairMid, tickSize);

        // Then: Both boundaries equal fairMid
        assertEquals(toFixed(50000.0), region.getMinPrice());
        assertEquals(toFixed(50000.0), region.getMaxPrice());
    }

    @Test
    void testLargeFairMid() {
        // Given: High-priced instrument
        PriceRegion region = new PriceRegion(0, true, -0.02, -0.01);
        long fairMid = toFixed(100000.0); // $100,000
        double tickSize = 1.0; // $1 tick

        // When: Update boundaries
        calculator.updateBoundaries(region, fairMid, tickSize);

        // Then: minPrice = 100000 * 0.98 = 98000, maxPrice = 100000 * 0.99 = 99000
        assertEquals(toFixed(98000.0), region.getMinPrice());
        assertEquals(toFixed(99000.0), region.getMaxPrice());
    }

    @Test
    void testSmallTickSize() {
        // Given: High-precision instrument
        PriceRegion region = new PriceRegion(0, true, -0.005, -0.001);
        long fairMid = toFixed(0.5); // $0.50 (altcoin)
        double tickSize = 0.0001; // 0.01 cent precision

        // When: Update boundaries
        calculator.updateBoundaries(region, fairMid, tickSize);

        // Then: minPrice = 0.5 * 0.995 = 0.4975, maxPrice = 0.5 * 0.999 = 0.4995
        assertEquals(toFixed(0.4975), region.getMinPrice());
        assertEquals(toFixed(0.4995), region.getMaxPrice());
    }

    @Test
    void testMultipleUpdates() {
        // Given: Region that gets updated multiple times
        PriceRegion region = new PriceRegion(0, true, -0.01, -0.005);
        double tickSize = 0.01;

        // When: First update at $50,000
        calculator.updateBoundaries(region, toFixed(50000.0), tickSize);
        long firstMin = region.getMinPrice();
        long firstMax = region.getMaxPrice();

        // And: Second update at $51,000
        calculator.updateBoundaries(region, toFixed(51000.0), tickSize);

        // Then: Boundaries updated correctly
        assertEquals(toFixed(49500.0), firstMin);
        assertEquals(toFixed(49750.0), firstMax);
        assertEquals(toFixed(50490.0), region.getMinPrice()); // 51000 * 0.99
        assertEquals(toFixed(50745.0), region.getMaxPrice()); // 51000 * 0.995
    }

    // Helper methods

    private long toFixed(double value) {
        return (long) (value * SCALE);
    }
}
