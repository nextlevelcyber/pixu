package com.bedrock.mm.pricing.pipeline;

import com.bedrock.mm.pricing.InstrumentContext;
import com.bedrock.mm.pricing.component.PricingComponent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FairMidPipelineTest {

    @Test
    void testNoComponents_returnsSimpleMid() {
        FairMidPipeline pipeline = new FairMidPipeline();
        InstrumentContext ctx = new InstrumentContext();
        ctx.bidPrice = 100_000_000L; // 1.0
        ctx.askPrice = 102_000_000L; // 1.02

        long fairMid = pipeline.compute(ctx);

        assertEquals(101_000_000L, fairMid); // (100 + 102) / 2 = 101
        assertEquals(101_000_000L, ctx.fairMid);
    }

    @Test
    void testSingleOffsetComponent() {
        PricingComponent offsetComponent = (ctx, estimate) -> 1_000_000L; // +0.01 offset
        FairMidPipeline pipeline = new FairMidPipeline(offsetComponent);
        InstrumentContext ctx = new InstrumentContext();
        ctx.bidPrice = 100_000_000L;
        ctx.askPrice = 102_000_000L;

        long fairMid = pipeline.compute(ctx);

        assertEquals(102_000_000L, fairMid); // (100 + 102) / 2 + 1 = 102
    }

    @Test
    void testMultipleComponents_sumOfOffsets() {
        PricingComponent offset1 = (ctx, estimate) -> 1_000_000L;  // +0.01
        PricingComponent offset2 = (ctx, estimate) -> -500_000L;    // -0.005
        FairMidPipeline pipeline = new FairMidPipeline(offset1, offset2);
        InstrumentContext ctx = new InstrumentContext();
        ctx.bidPrice = 100_000_000L;
        ctx.askPrice = 102_000_000L;

        long fairMid = pipeline.compute(ctx);

        assertEquals(101_500_000L, fairMid); // (100 + 102) / 2 + 1 - 0.5 = 101.5
    }
}
