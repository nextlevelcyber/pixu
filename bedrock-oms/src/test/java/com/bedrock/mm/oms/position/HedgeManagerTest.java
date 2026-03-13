package com.bedrock.mm.oms.position;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for HedgeManager.
 *
 * Validates:
 * - needsHedge() returns false below threshold
 * - needsHedge() returns true above threshold
 * - Rate limiting (min interval between hedges)
 * - calcHedgeSize() correct direction (long position → sell hedge)
 */
class HedgeManagerTest {
    private static final int INSTRUMENT_ID = 1;
    private static final int HEDGE_INSTRUMENT_ID = 2;
    private static final long SCALE = 100_000_000L; // 1e8

    private PositionTracker positionTracker;
    private HedgeManager hedgeManager;

    @BeforeEach
    void setUp() {
        positionTracker = new PositionTracker();
    }

    @Test
    void testNeedsHedgeReturnsFalseBelowThreshold() {
        // Given: Hedge threshold of 0.5 BTC
        hedgeManager = new HedgeManager(toFixed(0.5), HEDGE_INSTRUMENT_ID, 1.0);

        // When: Position is 0.3 BTC (below threshold)
        setPosition(INSTRUMENT_ID, 0.3);

        // Then: No hedge needed
        assertFalse(hedgeManager.needsHedge(positionTracker, INSTRUMENT_ID));
    }

    @Test
    void testNeedsHedgeReturnsTrueAboveThreshold() {
        // Given: Hedge threshold of 0.5 BTC
        hedgeManager = new HedgeManager(toFixed(0.5), HEDGE_INSTRUMENT_ID, 1.0);

        // When: Position is 1.0 BTC (above threshold)
        setPosition(INSTRUMENT_ID, 1.0);

        // Then: Hedge needed
        assertTrue(hedgeManager.needsHedge(positionTracker, INSTRUMENT_ID));
    }

    @Test
    void testNeedsHedgeReturnsTrueForNegativePosition() {
        // Given: Hedge threshold of 0.5 BTC
        hedgeManager = new HedgeManager(toFixed(0.5), HEDGE_INSTRUMENT_ID, 1.0);

        // When: Position is -1.0 BTC (short position, abs > threshold)
        setPosition(INSTRUMENT_ID, -1.0);

        // Then: Hedge needed (threshold checks absolute value)
        assertTrue(hedgeManager.needsHedge(positionTracker, INSTRUMENT_ID));
    }

    @Test
    void testRateLimitingPreventsImmediateSecondHedge() throws InterruptedException {
        // Given: Hedge threshold of 0.5 BTC
        hedgeManager = new HedgeManager(toFixed(0.5), HEDGE_INSTRUMENT_ID, 1.0);
        setPosition(INSTRUMENT_ID, 1.0);

        // When: First hedge check (should be true)
        assertTrue(hedgeManager.needsHedge(positionTracker, INSTRUMENT_ID));

        // And: Mark hedge as sent
        hedgeManager.onHedgeSent();

        // Then: Immediate second check should be false (rate limited)
        assertFalse(hedgeManager.needsHedge(positionTracker, INSTRUMENT_ID));

        // Wait for rate limit to expire (> 1ms)
        Thread.sleep(2);

        // Then: After rate limit expires, hedge needed again
        assertTrue(hedgeManager.needsHedge(positionTracker, INSTRUMENT_ID));
    }

    @Test
    void testCalcHedgeSizeForLongPosition() {
        // Given: Full hedge (ratio = 1.0)
        hedgeManager = new HedgeManager(toFixed(0.5), HEDGE_INSTRUMENT_ID, 1.0);

        // When: Long 1.0 BTC position
        setPosition(INSTRUMENT_ID, 1.0);

        // Then: Hedge size = -1.0 (sell to offset)
        long hedgeSize = hedgeManager.calcHedgeSize(positionTracker, INSTRUMENT_ID);
        assertEquals(toFixed(-1.0), hedgeSize);
    }

    @Test
    void testCalcHedgeSizeForShortPosition() {
        // Given: Full hedge (ratio = 1.0)
        hedgeManager = new HedgeManager(toFixed(0.5), HEDGE_INSTRUMENT_ID, 1.0);

        // When: Short 1.0 BTC position
        setPosition(INSTRUMENT_ID, -1.0);

        // Then: Hedge size = +1.0 (buy to offset)
        long hedgeSize = hedgeManager.calcHedgeSize(positionTracker, INSTRUMENT_ID);
        assertEquals(toFixed(1.0), hedgeSize);
    }

    @Test
    void testCalcHedgeSizeWithPartialHedgeRatio() {
        // Given: 50% hedge ratio
        hedgeManager = new HedgeManager(toFixed(0.5), HEDGE_INSTRUMENT_ID, 0.5);

        // When: Long 2.0 BTC position
        setPosition(INSTRUMENT_ID, 2.0);

        // Then: Hedge size = -1.0 (only hedge 50%)
        long hedgeSize = hedgeManager.calcHedgeSize(positionTracker, INSTRUMENT_ID);

        // Allow small rounding tolerance
        assertAlmostEqual(toFixed(-1.0), hedgeSize, 0.01);
    }

    @Test
    void testCalcHedgeSizeWithZeroPosition() {
        // Given: Full hedge
        hedgeManager = new HedgeManager(toFixed(0.5), HEDGE_INSTRUMENT_ID, 1.0);

        // When: No position
        setPosition(INSTRUMENT_ID, 0.0);

        // Then: Hedge size = 0
        long hedgeSize = hedgeManager.calcHedgeSize(positionTracker, INSTRUMENT_ID);
        assertEquals(0, hedgeSize);
    }

    @Test
    void testGetHedgeInstrumentId() {
        // Given: Hedge manager with specific hedge instrument
        hedgeManager = new HedgeManager(toFixed(0.5), HEDGE_INSTRUMENT_ID, 1.0);

        // Then: Returns correct hedge instrument ID
        assertEquals(HEDGE_INSTRUMENT_ID, hedgeManager.getHedgeInstrumentId());
    }

    @Test
    void testInvalidHedgeRatioThrowsException() {
        // Then: Hedge ratio > 1.0 throws exception
        assertThrows(IllegalArgumentException.class, () ->
            new HedgeManager(toFixed(0.5), HEDGE_INSTRUMENT_ID, 1.5));

        // And: Negative hedge ratio throws exception
        assertThrows(IllegalArgumentException.class, () ->
            new HedgeManager(toFixed(0.5), HEDGE_INSTRUMENT_ID, -0.5));
    }

    @Test
    void testResetRateLimiter() {
        // Given: Rate limiter active
        hedgeManager = new HedgeManager(toFixed(0.5), HEDGE_INSTRUMENT_ID, 1.0);
        setPosition(INSTRUMENT_ID, 1.0);

        hedgeManager.onHedgeSent();
        assertFalse(hedgeManager.needsHedge(positionTracker, INSTRUMENT_ID));

        // When: Reset rate limiter
        hedgeManager.resetRateLimiter();

        // Then: Hedge check returns true immediately
        assertTrue(hedgeManager.needsHedge(positionTracker, INSTRUMENT_ID));
    }

    @Test
    void testGettersReturnCorrectValues() {
        // Given: Hedge manager with specific configuration
        long threshold = toFixed(0.75);
        double ratio = 0.8;
        hedgeManager = new HedgeManager(threshold, HEDGE_INSTRUMENT_ID, ratio);

        // Then: Getters return correct values
        assertEquals(threshold, hedgeManager.getDeltaThreshold());
        assertEquals(ratio, hedgeManager.getHedgeRatio());
        assertEquals(HEDGE_INSTRUMENT_ID, hedgeManager.getHedgeInstrumentId());
    }

    // Helper methods

    private void setPosition(int instrumentId, double position) {
        // Simulate a fill that results in the desired position
        // (This is a simplified approach - in reality, fills accumulate)
        long positionFixed = toFixed(position);

        // Create a mock fill event to set position
        // We'll directly manipulate via fills rather than accessing internals
        com.bedrock.mm.oms.model.ExecEvent event = new com.bedrock.mm.oms.model.ExecEvent();
        event.instrumentId = instrumentId;
        event.fillPrice = toFixed(50000.0); // Arbitrary price
        event.fillSize = Math.abs(positionFixed);
        event.isBid = position > 0; // Positive = buy, negative = sell
        event.type = com.bedrock.mm.oms.model.ExecEventType.FILL;

        positionTracker.onFill(event);
    }

    private long toFixed(double value) {
        return (long) (value * SCALE);
    }

    private double fromFixed(long value) {
        return value / (double) SCALE;
    }

    private void assertAlmostEqual(long expected, long actual, double tolerancePercent) {
        double expectedDouble = fromFixed(expected);
        double actualDouble = fromFixed(actual);
        double diff = Math.abs(expectedDouble - actualDouble);
        double maxDiff = Math.abs(expectedDouble * tolerancePercent / 100.0);

        assertTrue(diff <= maxDiff,
            String.format("Expected %f, got %f, diff %f exceeds tolerance %f%%",
                expectedDouble, actualDouble, diff, tolerancePercent));
    }
}
