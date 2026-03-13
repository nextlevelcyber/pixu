package com.bedrock.mm.pricing.component.fairmid;

import com.bedrock.mm.pricing.InstrumentContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IndexFollowComponentTest {

    @Test
    void testNoIndexPrice_returnsZero() {
        IndexFollowComponent component = new IndexFollowComponent(0.3);
        InstrumentContext ctx = new InstrumentContext();
        ctx.indexPrice = 0;

        long offset = component.compute(ctx, 100_000_000L);

        assertEquals(0, offset);
    }

    @Test
    void testIndexPriceAboveEstimate_returnsPositiveOffset() {
        IndexFollowComponent component = new IndexFollowComponent(0.3);
        InstrumentContext ctx = new InstrumentContext();
        ctx.indexPrice = 110_000_000L; // 1.1
        long currentEstimate = 100_000_000L; // 1.0

        long offset = component.compute(ctx, currentEstimate);

        // offset = (110 - 100) * 0.3 = 3
        assertEquals(3_000_000L, offset);
    }

    @Test
    void testIndexPriceBelowEstimate_returnsNegativeOffset() {
        IndexFollowComponent component = new IndexFollowComponent(0.3);
        InstrumentContext ctx = new InstrumentContext();
        ctx.indexPrice = 90_000_000L; // 0.9
        long currentEstimate = 100_000_000L; // 1.0

        long offset = component.compute(ctx, currentEstimate);

        // offset = (90 - 100) * 0.3 = -3
        assertEquals(-3_000_000L, offset);
    }

    @Test
    void testWeightUpdate_usesNewWeight() {
        IndexFollowComponent component = new IndexFollowComponent(0.2);
        component.setWeight(0.5);

        InstrumentContext ctx = new InstrumentContext();
        ctx.indexPrice = 120_000_000L; // 1.2
        long currentEstimate = 100_000_000L; // 1.0

        long offset = component.compute(ctx, currentEstimate);

        // offset = (120 - 100) * 0.5 = 10
        assertEquals(10_000_000L, offset);
    }

    @Test
    void testZeroWeight_returnsZero() {
        IndexFollowComponent component = new IndexFollowComponent(0.0);
        InstrumentContext ctx = new InstrumentContext();
        ctx.indexPrice = 120_000_000L;
        long currentEstimate = 100_000_000L;

        long offset = component.compute(ctx, currentEstimate);

        assertEquals(0, offset);
    }
}
