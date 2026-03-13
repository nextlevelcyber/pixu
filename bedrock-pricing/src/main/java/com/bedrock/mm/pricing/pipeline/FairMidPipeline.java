package com.bedrock.mm.pricing.pipeline;

import com.bedrock.mm.pricing.InstrumentContext;
import com.bedrock.mm.pricing.component.PricingComponent;

public class FairMidPipeline {
    private final PricingComponent[] components;

    public FairMidPipeline(PricingComponent... components) {
        this.components = components;
    }

    public long compute(InstrumentContext ctx) {
        long estimate = (ctx.bidPrice + ctx.askPrice) >>> 1;
        for (PricingComponent component : components) {
            estimate += component.compute(ctx, estimate);
        }
        ctx.fairMid = estimate;
        return estimate;
    }
}
