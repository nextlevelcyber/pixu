package com.bedrock.mm.oms.risk;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RiskGuard.
 *
 * Validates:
 * - Price deviation check
 * - Open order limit check
 * - Position limit check
 * - Rate limiting
 * - Hot parameter updates
 */
class RiskGuardTest {
    private static final int INSTRUMENT_ID = 1;
    private static final long SCALE = 100_000_000L; // 1e8

    private RiskGuard guard;

    @BeforeEach
    void setUp() {
        guard = new RiskGuard();
    }

    @Test
    void testPreCheckPassesWhenAllLimitsOk() {
        // Given: Order within all limits
        long orderPrice = toFixed(50000.0);
        long fairMid = toFixed(50000.0);
        int openOrders = 10;
        long netPosition = toFixed(1.0);

        // When: Pre-check
        boolean result = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, openOrders, netPosition);

        // Then: Passes
        assertTrue(result);
    }

    @Test
    void testPreCheckFailsWhenPriceDeviationExceeded() {
        // Given: Order price too far from fairMid (default 5% max)
        long orderPrice = toFixed(55000.0); // 10% above fairMid
        long fairMid = toFixed(50000.0);
        int openOrders = 10;
        long netPosition = toFixed(1.0);

        // When: Pre-check
        boolean result = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, openOrders, netPosition);

        // Then: Fails
        assertFalse(result);
    }

    @Test
    void testPreCheckPassesWhenPriceDeviationWithinLimit() {
        // Given: Order price within 5% of fairMid
        long orderPrice = toFixed(51000.0); // 2% above fairMid
        long fairMid = toFixed(50000.0);
        int openOrders = 10;
        long netPosition = toFixed(1.0);

        // When: Pre-check
        boolean result = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, openOrders, netPosition);

        // Then: Passes
        assertTrue(result);
    }

    @Test
    void testPreCheckFailsWhenOpenOrderLimitReached() {
        // Given: Already at max open orders (default 100)
        long orderPrice = toFixed(50000.0);
        long fairMid = toFixed(50000.0);
        int openOrders = 100;
        long netPosition = toFixed(1.0);

        // When: Pre-check
        boolean result = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, openOrders, netPosition);

        // Then: Fails
        assertFalse(result);
    }

    @Test
    void testPreCheckFailsWhenPositionLimitExceeded() {
        // Given: Position at limit (default 10 BTC)
        long orderPrice = toFixed(50000.0);
        long fairMid = toFixed(50000.0);
        int openOrders = 10;
        long netPosition = toFixed(10.0); // At limit

        // When: Pre-check
        boolean result = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, openOrders, netPosition);

        // Then: Fails
        assertFalse(result);
    }

    @Test
    void testPreCheckFailsForNegativePositionAtLimit() {
        // Given: Short position at limit
        long orderPrice = toFixed(50000.0);
        long fairMid = toFixed(50000.0);
        int openOrders = 10;
        long netPosition = toFixed(-10.0); // Short at limit

        // When: Pre-check
        boolean result = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, openOrders, netPosition);

        // Then: Fails (checks absolute value)
        assertFalse(result);
    }

    @Test
    void testRateLimitingBlocks51stOrderInSameSecond() throws InterruptedException {
        // Given: Rate limit of 50 orders/second (default)
        long orderPrice = toFixed(50000.0);
        long fairMid = toFixed(50000.0);
        int openOrders = 10;
        long netPosition = toFixed(1.0);

        // When: Submit 50 orders in same second
        for (int i = 0; i < 50; i++) {
            boolean result = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, openOrders, netPosition);
            assertTrue(result, "Order " + i + " should pass");
        }

        // Then: 51st order fails
        boolean result51 = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, openOrders, netPosition);
        assertFalse(result51);

        // And: After 1 second, rate limit resets
        Thread.sleep(1100); // Wait > 1 second
        boolean resultAfterSleep = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, openOrders, netPosition);
        assertTrue(resultAfterSleep);
    }

    @Test
    void testSetMaxPriceDeviation() {
        // Given: Custom price deviation limit
        guard.setMaxPriceDeviation(0.10); // 10% max

        long orderPrice = toFixed(55000.0); // 10% above fairMid
        long fairMid = toFixed(50000.0);

        // When: Pre-check with 10% deviation
        boolean result = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, 10, toFixed(1.0));

        // Then: Passes (exactly at limit)
        assertTrue(result);
    }

    @Test
    void testSetMaxOpenOrders() {
        // Given: Custom open order limit
        guard.setMaxOpenOrders(50);

        long orderPrice = toFixed(50000.0);
        long fairMid = toFixed(50000.0);

        // When: Pre-check with 50 open orders
        boolean result = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, 50, toFixed(1.0));

        // Then: Fails (at limit)
        assertFalse(result);

        // And: With 49 open orders
        boolean result49 = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, 49, toFixed(1.0));
        assertTrue(result49);
    }

    @Test
    void testSetMaxPosition() {
        // Given: Custom position limit
        guard.setMaxPosition(5.0); // 5 BTC max

        long orderPrice = toFixed(50000.0);
        long fairMid = toFixed(50000.0);

        // When: Pre-check with 5 BTC position
        boolean result = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, 10, toFixed(5.0));

        // Then: Fails (at limit)
        assertFalse(result);

        // And: With 4.9 BTC position
        boolean result49 = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, 10, toFixed(4.9));
        assertTrue(result49);
    }

    @Test
    void testSetMaxOrdersPerSecond() {
        // Given: Custom rate limit
        guard.setMaxOrdersPerSecond(10);

        long orderPrice = toFixed(50000.0);
        long fairMid = toFixed(50000.0);

        // When: Submit 10 orders
        for (int i = 0; i < 10; i++) {
            boolean result = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, 10, toFixed(1.0));
            assertTrue(result, "Order " + i + " should pass");
        }

        // Then: 11th order fails
        boolean result11 = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, 10, toFixed(1.0));
        assertFalse(result11);
    }

    @Test
    void testResetRateLimiter() {
        // Given: Rate limit reached
        long orderPrice = toFixed(50000.0);
        long fairMid = toFixed(50000.0);

        guard.setMaxOrdersPerSecond(5);
        for (int i = 0; i < 5; i++) {
            guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, 10, toFixed(1.0));
        }

        // When: Reset rate limiter
        guard.resetRateLimiter();

        // Then: Can submit orders again immediately
        boolean result = guard.preCheck(INSTRUMENT_ID, orderPrice, fairMid, 10, toFixed(1.0));
        assertTrue(result);
    }

    @Test
    void testInvalidParameterThrowsException() {
        // Then: Invalid parameters throw IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> guard.setMaxPriceDeviation(-0.1));
        assertThrows(IllegalArgumentException.class, () -> guard.setMaxPriceDeviation(1.5));
        assertThrows(IllegalArgumentException.class, () -> guard.setMaxOpenOrders(-1));
        assertThrows(IllegalArgumentException.class, () -> guard.setMaxPosition(-1.0));
        assertThrows(IllegalArgumentException.class, () -> guard.setMaxOrdersPerSecond(-1));
        assertThrows(IllegalArgumentException.class, () -> guard.applyLimits(null));
    }

    @Test
    void testGettersReturnCorrectValues() {
        // Given: Custom parameters
        guard.setMaxPriceDeviation(0.03);
        guard.setMaxOpenOrders(75);
        guard.setMaxPosition(8.5);
        guard.setMaxOrdersPerSecond(40);

        // Then: Getters return correct values
        assertEquals(toFixed(0.03), guard.getMaxPriceDeviation());
        assertEquals(75, guard.getMaxOpenOrders());
        assertEquals(toFixed(8.5), guard.getMaxPosition());
        assertEquals(40, guard.getMaxOrdersPerSecond());
    }

    @Test
    void testSnapshotAndRollbackLimits() {
        RiskGuard.RiskLimits baseline = guard.snapshotLimits();

        guard.setMaxPriceDeviation(0.01);
        guard.setMaxOpenOrders(5);
        guard.setMaxPosition(1.0);
        guard.setMaxOrdersPerSecond(2);

        assertEquals(toFixed(0.01), guard.getMaxPriceDeviation());
        assertEquals(5, guard.getMaxOpenOrders());
        assertEquals(toFixed(1.0), guard.getMaxPosition());
        assertEquals(2, guard.getMaxOrdersPerSecond());

        guard.applyLimits(baseline);
        assertEquals(baseline.maxPriceDeviationBits(), guard.getMaxPriceDeviation());
        assertEquals(baseline.maxOpenOrders(), guard.getMaxOpenOrders());
        assertEquals(baseline.maxPositionBits(), guard.getMaxPosition());
        assertEquals(baseline.maxOrdersPerSecond(), guard.getMaxOrdersPerSecond());
    }

    @Test
    void testApplyLimitsRejectsInvalidSnapshot() {
        RiskGuard.RiskLimits invalid = new RiskGuard.RiskLimits(-1L, 1, 1L, 1);
        assertThrows(IllegalArgumentException.class, () -> guard.applyLimits(invalid));
    }

    // Helper methods

    private long toFixed(double value) {
        return (long) (value * SCALE);
    }
}
