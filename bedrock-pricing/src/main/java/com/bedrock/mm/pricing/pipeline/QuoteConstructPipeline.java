package com.bedrock.mm.pricing.pipeline;

import com.bedrock.mm.pricing.InstrumentContext;
import com.bedrock.mm.pricing.component.PricingComponent;
import com.bedrock.mm.pricing.model.QuoteTarget;

public class QuoteConstructPipeline {
    private final PricingComponent[] bidComponents;
    private final PricingComponent[] askComponents;

    public QuoteConstructPipeline(PricingComponent[] bidComponents, PricingComponent[] askComponents) {
        this.bidComponents = bidComponents;
        this.askComponents = askComponents;
    }

    public void compute(InstrumentContext ctx, long fairMid, QuoteTarget target) {
        long bidEstimate = fairMid;
        for (PricingComponent c : bidComponents) {
            bidEstimate += c.compute(ctx, bidEstimate);
        }

        long askEstimate = fairMid;
        for (PricingComponent c : askComponents) {
            askEstimate += c.compute(ctx, askEstimate);
        }

        target.bidPrice = bidEstimate;
        target.askPrice = askEstimate;
    }
}
