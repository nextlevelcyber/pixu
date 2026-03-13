package com.bedrock.mm.pricing.component;

import com.bedrock.mm.pricing.InstrumentContext;

@FunctionalInterface
public interface PricingComponent {
    long compute(InstrumentContext ctx, long currentEstimate);
}
