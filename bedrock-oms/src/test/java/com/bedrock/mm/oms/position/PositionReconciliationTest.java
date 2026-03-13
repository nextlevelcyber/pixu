package com.bedrock.mm.oms.position;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionReconciliationTest {
    private static final long SCALE = 100_000_000L;

    private final PositionReconciliation reconciliation = new PositionReconciliation();

    @Test
    void testApplySnapshotRestoresPositionsAndAvgEntry() {
        PositionTracker tracker = new PositionTracker();
        List<PositionReconciliation.RestPosition> snapshot = List.of(
                new PositionReconciliation.RestPosition(1, toFixed(1.0), toFixed(50000.0), 0),
                new PositionReconciliation.RestPosition(2, toFixed(-0.5), toFixed(2500.0), 0)
        );

        reconciliation.applySnapshot(snapshot, tracker);

        assertEquals(toFixed(1.0), tracker.getNetPosition(1));
        assertEquals(toFixed(50000.0), tracker.getAvgEntryPrice(1));
        assertEquals(toFixed(-0.5), tracker.getNetPosition(2));
        assertEquals(toFixed(2500.0), tracker.getAvgEntryPrice(2));
    }

    @Test
    void testApplySnapshotZeroPositionResetsState() {
        PositionTracker tracker = new PositionTracker();
        tracker.reconcile(1, toFixed(2.0), toFixed(48000.0));

        reconciliation.applySnapshot(
                List.of(new PositionReconciliation.RestPosition(1, 0L, 0L, 0L)),
                tracker
        );

        assertEquals(0L, tracker.getNetPosition(1));
        assertEquals(0L, tracker.getAvgEntryPrice(1));
    }

    @Test
    void testCalculateDrift() {
        PositionTracker tracker = new PositionTracker();
        tracker.reconcile(3, toFixed(1.2), toFixed(3000.0));

        long drift = reconciliation.calculateDrift(3, toFixed(1.0), tracker);
        assertEquals(toFixed(0.2), drift);
    }

    private long toFixed(double value) {
        return (long) (value * SCALE);
    }
}
