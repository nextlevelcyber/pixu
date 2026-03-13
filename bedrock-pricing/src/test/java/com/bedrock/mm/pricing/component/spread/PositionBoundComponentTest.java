package com.bedrock.mm.pricing.component.spread;

import com.bedrock.mm.pricing.InstrumentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PositionBoundComponentTest {

    private static final long LARGE_OFFSET = Long.MIN_VALUE / 2;
    private InstrumentContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new InstrumentContext();
        ctx.quoteFlags = 0;
    }

    @Test
    void belowSoftLimitReturnsZero() {
        long softLimit = 8_00000000L; // 8 BTC
        PositionBoundComponent bidComponent = new PositionBoundComponent(softLimit, true);
        PositionBoundComponent askComponent = new PositionBoundComponent(softLimit, false);

        ctx.netPosition = 5_00000000L; // 5 BTC (below limit)

        assertEquals(0, bidComponent.compute(ctx, 0));
        assertEquals(0, askComponent.compute(ctx, 0));
        assertEquals(0, ctx.quoteFlags);
    }

    @Test
    void atSoftLimitBidSideReturnsLargeOffset() {
        long softLimit = 8_00000000L; // 8 BTC
        PositionBoundComponent bidComponent = new PositionBoundComponent(softLimit, true);

        ctx.netPosition = 8_00000000L; // 8 BTC (at limit)

        long result = bidComponent.compute(ctx, 0);

        assertEquals(LARGE_OFFSET, result);
        assertEquals(0x01, ctx.quoteFlags & 0x01); // bit 0 set
    }

    @Test
    void atSoftLimitAskSideReturnsLargeOffset() {
        long softLimit = 8_00000000L; // 8 BTC
        PositionBoundComponent askComponent = new PositionBoundComponent(softLimit, false);

        ctx.netPosition = -8_00000000L; // -8 BTC (at negative limit)

        long result = askComponent.compute(ctx, 0);

        assertEquals(LARGE_OFFSET, result);
        assertEquals(0x02, ctx.quoteFlags & 0x02); // bit 1 set
    }

    @Test
    void exceedingSoftLimitBidSideReturnsLargeOffset() {
        long softLimit = 8_00000000L; // 8 BTC
        PositionBoundComponent bidComponent = new PositionBoundComponent(softLimit, true);

        ctx.netPosition = 10_00000000L; // 10 BTC (exceeds limit)

        long result = bidComponent.compute(ctx, 0);

        assertEquals(LARGE_OFFSET, result);
        assertEquals(0x01, ctx.quoteFlags & 0x01);
    }

    @Test
    void exceedingSoftLimitAskSideReturnsLargeOffset() {
        long softLimit = 8_00000000L; // 8 BTC
        PositionBoundComponent askComponent = new PositionBoundComponent(softLimit, false);

        ctx.netPosition = -10_00000000L; // -10 BTC (exceeds negative limit)

        long result = askComponent.compute(ctx, 0);

        assertEquals(LARGE_OFFSET, result);
        assertEquals(0x02, ctx.quoteFlags & 0x02);
    }

    @Test
    void wrongSideDoesNotTrigger() {
        long softLimit = 8_00000000L;
        PositionBoundComponent bidComponent = new PositionBoundComponent(softLimit, true);
        PositionBoundComponent askComponent = new PositionBoundComponent(softLimit, false);

        // Bid component with short position (should not trigger)
        ctx.netPosition = -10_00000000L;
        assertEquals(0, bidComponent.compute(ctx, 0));
        assertEquals(0, ctx.quoteFlags);

        // Reset flags
        ctx.quoteFlags = 0;

        // Ask component with long position (should not trigger)
        ctx.netPosition = 10_00000000L;
        assertEquals(0, askComponent.compute(ctx, 0));
        assertEquals(0, ctx.quoteFlags);
    }

    @Test
    void zeroSoftLimitReturnsZero() {
        PositionBoundComponent bidComponent = new PositionBoundComponent(0, true);
        PositionBoundComponent askComponent = new PositionBoundComponent(0, false);

        ctx.netPosition = 10_00000000L;

        assertEquals(0, bidComponent.compute(ctx, 0));
        assertEquals(0, askComponent.compute(ctx, 0));
        assertEquals(0, ctx.quoteFlags);
    }

    @Test
    void setSoftLimitUpdatesCorrectly() {
        long initialLimit = 8_00000000L;
        long newLimit = 12_00000000L;
        PositionBoundComponent component = new PositionBoundComponent(initialLimit, true);

        ctx.netPosition = 10_00000000L; // 10 BTC

        // With initial limit=8, position exceeds, should trigger
        assertEquals(LARGE_OFFSET, component.compute(ctx, 0));
        ctx.quoteFlags = 0; // reset

        component.setSoftLimit(newLimit);

        // With new limit=12, position below limit, should not trigger
        assertEquals(0, component.compute(ctx, 0));
        assertEquals(0, ctx.quoteFlags);
    }

    @Test
    void bothFlagsCanBeSetIndependently() {
        long softLimit = 8_00000000L;
        PositionBoundComponent bidComponent = new PositionBoundComponent(softLimit, true);
        PositionBoundComponent askComponent = new PositionBoundComponent(softLimit, false);

        ctx.netPosition = 10_00000000L; // Long position

        bidComponent.compute(ctx, 0);
        assertEquals(0x01, ctx.quoteFlags);

        // Now simulate a different scenario where ask also triggers
        ctx.netPosition = -10_00000000L;
        askComponent.compute(ctx, 0);
        assertEquals(0x03, ctx.quoteFlags); // Both bits set
    }
}
