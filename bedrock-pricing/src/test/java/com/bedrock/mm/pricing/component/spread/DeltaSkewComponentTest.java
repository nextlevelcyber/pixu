package com.bedrock.mm.pricing.component.spread;

import com.bedrock.mm.pricing.InstrumentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeltaSkewComponentTest {

    private InstrumentContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new InstrumentContext();
    }

    @Test
    void zeroPositionReturnsZero() {
        long maxPosition = 10_00000000L; // 10 BTC
        long maxSkew = 50_000_000L;      // $0.50
        DeltaSkewComponent bidComponent = new DeltaSkewComponent(maxPosition, maxSkew, true);
        DeltaSkewComponent askComponent = new DeltaSkewComponent(maxPosition, maxSkew, false);

        ctx.netPosition = 0;

        assertEquals(0, bidComponent.compute(ctx, 0));
        assertEquals(0, askComponent.compute(ctx, 0));
    }

    @Test
    void longPositionAppliesNegativeSkew() {
        long maxPosition = 10_00000000L; // 10 BTC
        long maxSkew = 50_000_000L;      // $0.50
        DeltaSkewComponent bidComponent = new DeltaSkewComponent(maxPosition, maxSkew, true);
        DeltaSkewComponent askComponent = new DeltaSkewComponent(maxPosition, maxSkew, false);

        // Half max long position
        ctx.netPosition = 5_00000000L; // 5 BTC

        // skewRatio = 5 / 10 = 0.5
        // skewOffset = 0.5 * 50_000_000 = 25_000_000
        // return -skewOffset = -25_000_000
        long expectedOffset = -25_000_000L;

        assertEquals(expectedOffset, bidComponent.compute(ctx, 0));
        assertEquals(expectedOffset, askComponent.compute(ctx, 0));
    }

    @Test
    void shortPositionAppliesPositiveSkew() {
        long maxPosition = 10_00000000L; // 10 BTC
        long maxSkew = 50_000_000L;      // $0.50
        DeltaSkewComponent bidComponent = new DeltaSkewComponent(maxPosition, maxSkew, true);
        DeltaSkewComponent askComponent = new DeltaSkewComponent(maxPosition, maxSkew, false);

        // Half max short position
        ctx.netPosition = -5_00000000L; // -5 BTC

        // skewRatio = -5 / 10 = -0.5
        // skewOffset = -0.5 * 50_000_000 = -25_000_000
        // return -skewOffset = 25_000_000
        long expectedOffset = 25_000_000L;

        assertEquals(expectedOffset, bidComponent.compute(ctx, 0));
        assertEquals(expectedOffset, askComponent.compute(ctx, 0));
    }

    @Test
    void positionExceedingMaxIsClamped() {
        long maxPosition = 10_00000000L; // 10 BTC
        long maxSkew = 50_000_000L;      // $0.50
        DeltaSkewComponent component = new DeltaSkewComponent(maxPosition, maxSkew, true);

        // Position exceeds max
        ctx.netPosition = 15_00000000L; // 15 BTC

        // skewRatio should be clamped to 1.0
        // skewOffset = 1.0 * 50_000_000 = 50_000_000
        // return -skewOffset = -50_000_000
        long expectedOffset = -50_000_000L;

        assertEquals(expectedOffset, component.compute(ctx, 0));
    }

    @Test
    void negativePositionExceedingMaxIsClamped() {
        long maxPosition = 10_00000000L; // 10 BTC
        long maxSkew = 50_000_000L;      // $0.50
        DeltaSkewComponent component = new DeltaSkewComponent(maxPosition, maxSkew, true);

        // Position exceeds max (short)
        ctx.netPosition = -15_00000000L; // -15 BTC

        // skewRatio should be clamped to -1.0
        // skewOffset = -1.0 * 50_000_000 = -50_000_000
        // return -skewOffset = 50_000_000
        long expectedOffset = 50_000_000L;

        assertEquals(expectedOffset, component.compute(ctx, 0));
    }

    @Test
    void zeroMaxPositionReturnsZero() {
        long maxSkew = 50_000_000L;
        DeltaSkewComponent component = new DeltaSkewComponent(0, maxSkew, true);

        ctx.netPosition = 5_00000000L;

        assertEquals(0, component.compute(ctx, 0));
    }

    @Test
    void setMaxPositionUpdatesCorrectly() {
        long initialMax = 10_00000000L;
        long newMax = 20_00000000L;
        long maxSkew = 50_000_000L;
        DeltaSkewComponent component = new DeltaSkewComponent(initialMax, maxSkew, true);

        ctx.netPosition = 10_00000000L; // 10 BTC

        // With initialMax=10, ratio=1.0, offset=-50_000_000
        assertEquals(-50_000_000L, component.compute(ctx, 0));

        component.setMaxPosition(newMax);

        // With newMax=20, ratio=0.5, offset=-25_000_000
        assertEquals(-25_000_000L, component.compute(ctx, 0));
    }

    @Test
    void setMaxSkewUpdatesCorrectly() {
        long maxPosition = 10_00000000L;
        long initialSkew = 50_000_000L;
        long newSkew = 100_000_000L;
        DeltaSkewComponent component = new DeltaSkewComponent(maxPosition, initialSkew, true);

        ctx.netPosition = 5_00000000L; // 5 BTC, ratio=0.5

        // With initialSkew=50_000_000, offset=-25_000_000
        assertEquals(-25_000_000L, component.compute(ctx, 0));

        component.setMaxSkew(newSkew);

        // With newSkew=100_000_000, offset=-50_000_000
        assertEquals(-50_000_000L, component.compute(ctx, 0));
    }
}
