package com.bedrock.mm.pricing.pipeline;

import com.bedrock.mm.pricing.InstrumentContext;
import com.bedrock.mm.pricing.component.PricingComponent;
import com.bedrock.mm.pricing.model.QuoteTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuoteConstructPipelineTest {

    @Test
    void testBidSide_appliesNegativeOffsetFromFairMid() {
        PricingComponent bidOffset = (ctx, estimate) -> -2_000_000L; // -0.02
        QuoteConstructPipeline pipeline = new QuoteConstructPipeline(
            new PricingComponent[]{bidOffset},
            new PricingComponent[]{}
        );
        InstrumentContext ctx = new InstrumentContext();
        long fairMid = 100_000_000L; // 1.0
        QuoteTarget target = new QuoteTarget();

        pipeline.compute(ctx, fairMid, target);

        assertEquals(98_000_000L, target.bidPrice); // 100 - 2 = 98
        assertEquals(100_000_000L, target.askPrice); // fairMid, no ask components
    }

    @Test
    void testAskSide_appliesPositiveOffsetFromFairMid() {
        PricingComponent askOffset = (ctx, estimate) -> 2_000_000L; // +0.02
        QuoteConstructPipeline pipeline = new QuoteConstructPipeline(
            new PricingComponent[]{},
            new PricingComponent[]{askOffset}
        );
        InstrumentContext ctx = new InstrumentContext();
        long fairMid = 100_000_000L;
        QuoteTarget target = new QuoteTarget();

        pipeline.compute(ctx, fairMid, target);

        assertEquals(100_000_000L, target.bidPrice); // fairMid, no bid components
        assertEquals(102_000_000L, target.askPrice); // 100 + 2 = 102
    }

    @Test
    void testBothSides_applySymmetricSpread() {
        PricingComponent bidOffset = (ctx, estimate) -> -1_000_000L; // -0.01
        PricingComponent askOffset = (ctx, estimate) -> 1_000_000L;  // +0.01
        QuoteConstructPipeline pipeline = new QuoteConstructPipeline(
            new PricingComponent[]{bidOffset},
            new PricingComponent[]{askOffset}
        );
        InstrumentContext ctx = new InstrumentContext();
        long fairMid = 100_000_000L;
        QuoteTarget target = new QuoteTarget();

        pipeline.compute(ctx, fairMid, target);

        assertEquals(99_000_000L, target.bidPrice);   // 100 - 1 = 99
        assertEquals(101_000_000L, target.askPrice);  // 100 + 1 = 101
    }
}
