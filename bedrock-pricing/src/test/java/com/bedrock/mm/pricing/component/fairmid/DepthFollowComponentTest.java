package com.bedrock.mm.pricing.component.fairmid;

import com.bedrock.mm.pricing.InstrumentContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DepthFollowComponentTest {

    private static final double SCALE = 1e8;

    @Test
    void testEmptyBook_returnsZero() {
        DepthFollowComponent component = new DepthFollowComponent(5);
        InstrumentContext ctx = new InstrumentContext();
        ctx.bookLevels = 0;

        long offset = component.compute(ctx, 100_000_000L);

        assertEquals(0, offset);
    }

    @Test
    void testSingleLevel_returnsWeightedMid() {
        DepthFollowComponent component = new DepthFollowComponent(5);
        InstrumentContext ctx = new InstrumentContext();
        ctx.bookLevels = 1;
        ctx.bidPrices[0] = (long)(100.0 * SCALE);
        ctx.bidSizes[0]  = (long)(10.0  * SCALE);
        ctx.askPrices[0] = (long)(102.0 * SCALE);
        ctx.askSizes[0]  = (long)(10.0  * SCALE);

        long currentEstimate = (long)(101.0 * SCALE);
        long offset = component.compute(ctx, currentEstimate);

        // weightedBid = 100, weightedAsk = 102, depthMid = 101
        double actualMid = (currentEstimate + offset) / SCALE;
        assertEquals(101.0, actualMid, 0.01);
    }

    @Test
    void testMultipleLevels_computesWeightedAverage() {
        DepthFollowComponent component = new DepthFollowComponent(3);
        InstrumentContext ctx = new InstrumentContext();
        ctx.bookLevels = 3;

        // Bid side: 100*10 + 99*5 + 98*2 = 1691; total = 17; weightedBid ≈ 99.47
        ctx.bidPrices[0] = (long)(100.0 * SCALE);
        ctx.bidSizes[0]  = (long)(10.0  * SCALE);
        ctx.bidPrices[1] = (long)(99.0  * SCALE);
        ctx.bidSizes[1]  = (long)(5.0   * SCALE);
        ctx.bidPrices[2] = (long)(98.0  * SCALE);
        ctx.bidSizes[2]  = (long)(2.0   * SCALE);

        // Ask side: 102*10 + 103*5 + 104*2 = 1943; total = 17; weightedAsk ≈ 114.29
        ctx.askPrices[0] = (long)(102.0 * SCALE);
        ctx.askSizes[0]  = (long)(10.0  * SCALE);
        ctx.askPrices[1] = (long)(103.0 * SCALE);
        ctx.askSizes[1]  = (long)(5.0   * SCALE);
        ctx.askPrices[2] = (long)(104.0 * SCALE);
        ctx.askSizes[2]  = (long)(2.0   * SCALE);

        long currentEstimate = (long)(101.0 * SCALE);
        long offset = component.compute(ctx, currentEstimate);

        // weightedBid ≈ 99.47, weightedAsk ≈ 114.29 → depthMid skewed high
        double actualMid = (currentEstimate + offset) / SCALE;
        assertTrue(actualMid > 100.0, "Depth mid should be skewed toward higher prices");
    }

    @Test
    void testDepthLimit_respectsMaxLevels() {
        DepthFollowComponent component = new DepthFollowComponent(2);
        InstrumentContext ctx = new InstrumentContext();
        ctx.bookLevels = 5;

        ctx.bidPrices[0] = (long)(100.0 * SCALE);
        ctx.bidSizes[0]  = (long)(10.0  * SCALE);
        ctx.bidPrices[1] = (long)(99.0  * SCALE);
        ctx.bidSizes[1]  = (long)(10.0  * SCALE);
        ctx.bidPrices[2] = (long)(50.0  * SCALE); // Should be ignored
        ctx.bidSizes[2]  = (long)(100.0 * SCALE);

        ctx.askPrices[0] = (long)(102.0 * SCALE);
        ctx.askSizes[0]  = (long)(10.0  * SCALE);
        ctx.askPrices[1] = (long)(103.0 * SCALE);
        ctx.askSizes[1]  = (long)(10.0  * SCALE);
        ctx.askPrices[2] = (long)(200.0 * SCALE); // Should be ignored
        ctx.askSizes[2]  = (long)(100.0 * SCALE);

        long currentEstimate = (long)(101.0 * SCALE);
        long offset = component.compute(ctx, currentEstimate);

        // Only first 2 levels: weightedBid = (100*10 + 99*10) / 20 = 99.5
        //                       weightedAsk = (102*10 + 103*10) / 20 = 102.5
        //                       depthMid = 101.0
        double actualMid = (currentEstimate + offset) / SCALE;
        assertEquals(101.0, actualMid, 0.1);
    }
}
