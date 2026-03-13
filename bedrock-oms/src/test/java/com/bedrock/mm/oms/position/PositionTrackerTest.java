package com.bedrock.mm.oms.position;

import com.bedrock.mm.oms.model.ExecEvent;
import com.bedrock.mm.oms.model.ExecEventType;
import com.bedrock.mm.oms.model.PositionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PositionTracker.
 *
 * Validates:
 * - Single fill updates netPosition correctly
 * - Multiple fills accumulate correctly
 * - Average entry price calculation accuracy
 * - PnL calculation accuracy
 * - Reset clears all state
 */
class PositionTrackerTest {
    private static final int INSTRUMENT_ID = 1;
    private static final long SCALE = 100_000_000L; // 1e8

    private PositionTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new PositionTracker();
    }

    @Test
    void testSingleBidFillIncreasesPosition() {
        // Given: Empty position
        assertEquals(0, tracker.getNetPosition(INSTRUMENT_ID));

        // When: Bid fill for 1.0 BTC at 50000.0 USD
        ExecEvent event = createFillEvent(
            INSTRUMENT_ID,
            true, // isBid
            toFixed(50000.0), // fillPrice
            toFixed(1.0) // fillSize
        );
        tracker.onFill(event);

        // Then: Position increases by 1.0 BTC
        assertEquals(toFixed(1.0), tracker.getNetPosition(INSTRUMENT_ID));
    }

    @Test
    void testSingleAskFillDecreasesPosition() {
        // Given: Empty position
        assertEquals(0, tracker.getNetPosition(INSTRUMENT_ID));

        // When: Ask fill for 0.5 BTC at 51000.0 USD
        ExecEvent event = createFillEvent(
            INSTRUMENT_ID,
            false, // isBid (this is an ask)
            toFixed(51000.0),
            toFixed(0.5)
        );
        tracker.onFill(event);

        // Then: Position decreases by 0.5 BTC (goes negative)
        assertEquals(toFixed(-0.5), tracker.getNetPosition(INSTRUMENT_ID));
    }

    @Test
    void testMultipleFillsAccumulateCorrectly() {
        // Given: Empty position

        // When: Multiple fills
        // Fill 1: Buy 1.0 BTC at 50000
        tracker.onFill(createFillEvent(INSTRUMENT_ID, true, toFixed(50000.0), toFixed(1.0)));

        // Fill 2: Buy 0.5 BTC at 51000
        tracker.onFill(createFillEvent(INSTRUMENT_ID, true, toFixed(51000.0), toFixed(0.5)));

        // Fill 3: Sell 0.3 BTC at 52000
        tracker.onFill(createFillEvent(INSTRUMENT_ID, false, toFixed(52000.0), toFixed(0.3)));

        // Then: Net position = 1.0 + 0.5 - 0.3 = 1.2 BTC
        assertEquals(toFixed(1.2), tracker.getNetPosition(INSTRUMENT_ID));
    }

    @Test
    void testAverageEntryPriceCalculation() {
        // Given: Multiple fills at different prices
        // Fill 1: Buy 1.0 BTC at 50000
        tracker.onFill(createFillEvent(INSTRUMENT_ID, true, toFixed(50000.0), toFixed(1.0)));

        // Fill 2: Buy 1.0 BTC at 52000
        tracker.onFill(createFillEvent(INSTRUMENT_ID, true, toFixed(52000.0), toFixed(1.0)));

        // When: Get average entry price
        long avgEntry = tracker.getAvgEntryPrice(INSTRUMENT_ID);

        // Then: Average = (50000 * 1.0 + 52000 * 1.0) / 2.0 = 51000
        assertEquals(toFixed(51000.0), avgEntry);
    }

    @Test
    void testPnLCalculationWithMarkPrice() {
        // Given: Buy 1.0 BTC at 50000
        tracker.onFill(createFillEvent(INSTRUMENT_ID, true, toFixed(50000.0), toFixed(1.0)));

        // When: Mark price updates to 55000
        tracker.updateMarkPrice(INSTRUMENT_ID, toFixed(55000.0));

        // Then: Unrealized PnL = (55000 - 50000) * 1.0 = 5000
        long expectedPnl = toFixed(5000.0);
        long actualPnl = tracker.getUnrealizedPnl(INSTRUMENT_ID);

        // Allow 0.01% tolerance due to fixed-point rounding
        assertAlmostEqual(expectedPnl, actualPnl, 0.0001);
    }

    @Test
    void testPnLCalculationForShortPosition() {
        // Given: Sell 1.0 BTC at 50000 (short position)
        tracker.onFill(createFillEvent(INSTRUMENT_ID, false, toFixed(50000.0), toFixed(1.0)));

        // When: Mark price increases to 52000 (loss for short)
        tracker.updateMarkPrice(INSTRUMENT_ID, toFixed(52000.0));

        // Then: Unrealized PnL = (52000 - 50000) * (-1.0) = -2000
        long expectedPnl = toFixed(-2000.0);
        long actualPnl = tracker.getUnrealizedPnl(INSTRUMENT_ID);

        assertAlmostEqual(expectedPnl, actualPnl, 0.0001);
    }

    @Test
    void testPnLIsZeroWhenNoPosition() {
        // Given: No position
        assertEquals(0, tracker.getNetPosition(INSTRUMENT_ID));

        // When: Update mark price
        tracker.updateMarkPrice(INSTRUMENT_ID, toFixed(50000.0));

        // Then: PnL is zero
        assertEquals(0, tracker.getUnrealizedPnl(INSTRUMENT_ID));
    }

    @Test
    void testResetClearsAllState() {
        // Given: Active position with fills and mark price
        tracker.onFill(createFillEvent(INSTRUMENT_ID, true, toFixed(50000.0), toFixed(1.0)));
        tracker.updateMarkPrice(INSTRUMENT_ID, toFixed(55000.0));

        // Verify state exists
        assertNotEquals(0, tracker.getNetPosition(INSTRUMENT_ID));
        assertNotEquals(0, tracker.getUnrealizedPnl(INSTRUMENT_ID));

        // When: Reset
        tracker.reset(INSTRUMENT_ID);

        // Then: All state cleared
        assertEquals(0, tracker.getNetPosition(INSTRUMENT_ID));
        assertEquals(0, tracker.getUnrealizedPnl(INSTRUMENT_ID));
        assertEquals(0, tracker.getAvgEntryPrice(INSTRUMENT_ID));
    }

    @Test
    void testBuildPositionEvent() {
        // Given: Position with data
        tracker.onFill(createFillEvent(INSTRUMENT_ID, true, toFixed(50000.0), toFixed(1.0)));
        tracker.updateMarkPrice(INSTRUMENT_ID, toFixed(55000.0));

        // When: Build position event
        PositionEvent event = new PositionEvent();
        tracker.buildPositionEvent(INSTRUMENT_ID, event);

        // Then: Event populated correctly
        assertEquals(INSTRUMENT_ID, event.instrumentId);
        assertEquals(toFixed(1.0), event.netPosition);
        assertEquals(toFixed(1.0), event.delta); // For spot: delta = netPosition
        assertNotEquals(0, event.unrealizedPnl);
        assertNotEquals(0, event.seqId);
        assertNotEquals(0, event.publishNanos);
    }

    @Test
    void testMultipleInstrumentsIndependent() {
        // Given: Two different instruments
        int instrument1 = 1;
        int instrument2 = 2;

        // When: Fill for instrument 1
        tracker.onFill(createFillEvent(instrument1, true, toFixed(50000.0), toFixed(1.0)));

        // Then: Only instrument 1 affected
        assertEquals(toFixed(1.0), tracker.getNetPosition(instrument1));
        assertEquals(0, tracker.getNetPosition(instrument2));
    }

    @Test
    void testZeroFillDoesNotChangePosition() {
        // Given: Empty position

        // When: Fill with zero size
        tracker.onFill(createFillEvent(INSTRUMENT_ID, true, toFixed(50000.0), 0));

        // Then: Position unchanged
        assertEquals(0, tracker.getNetPosition(INSTRUMENT_ID));
    }

    @Test
    void testReconcileRestoresLongPositionAndAvgEntry() {
        tracker.reconcile(INSTRUMENT_ID, toFixed(1.5), toFixed(50500.0));

        assertEquals(toFixed(1.5), tracker.getNetPosition(INSTRUMENT_ID));
        assertEquals(toFixed(50500.0), tracker.getAvgEntryPrice(INSTRUMENT_ID));
    }

    @Test
    void testReconcileRestoresShortPositionAndPnlAfterMarkUpdate() {
        tracker.reconcile(INSTRUMENT_ID, toFixed(-2.0), toFixed(50000.0));
        tracker.updateMarkPrice(INSTRUMENT_ID, toFixed(49000.0));

        assertEquals(toFixed(-2.0), tracker.getNetPosition(INSTRUMENT_ID));
        assertEquals(toFixed(50000.0), tracker.getAvgEntryPrice(INSTRUMENT_ID));
        assertAlmostEqual(toFixed(2000.0), tracker.getUnrealizedPnl(INSTRUMENT_ID), 0.0001);
    }

    // Helper methods

    private ExecEvent createFillEvent(int instrumentId, boolean isBid, long fillPrice, long fillSize) {
        ExecEvent event = new ExecEvent();
        event.seqId = 1;
        event.instrumentId = instrumentId;
        event.internalOrderId = 12345L;
        event.exchOrderId = 67890L;
        event.type = ExecEventType.FILL;
        event.fillPrice = fillPrice;
        event.fillSize = fillSize;
        event.remainSize = 0;
        event.isBid = isBid;
        return event;
    }

    private long toFixed(double value) {
        return (long) (value * SCALE);
    }

    private double fromFixed(long value) {
        return value / (double) SCALE;
    }

    private void assertAlmostEqual(long expected, long actual, double tolerance) {
        double expectedDouble = fromFixed(expected);
        double actualDouble = fromFixed(actual);
        double diff = Math.abs(expectedDouble - actualDouble);
        double maxDiff = Math.abs(expectedDouble * tolerance);
        assertTrue(diff <= maxDiff,
            String.format("Expected %f, got %f, diff %f exceeds tolerance %f",
                expectedDouble, actualDouble, diff, maxDiff));
    }
}
