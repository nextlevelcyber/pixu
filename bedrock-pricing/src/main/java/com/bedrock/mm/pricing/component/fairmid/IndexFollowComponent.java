package com.bedrock.mm.pricing.component.fairmid;

import com.bedrock.mm.pricing.InstrumentContext;
import com.bedrock.mm.pricing.component.PricingComponent;

public class IndexFollowComponent implements PricingComponent {
    private volatile double weight;

    public IndexFollowComponent(double weight) {
        this.weight = weight;
    }

    public IndexFollowComponent() {
        this(0.3);
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    @Override
    public long compute(InstrumentContext ctx, long currentEstimate) {
        if (ctx.indexPrice == 0) {
            return 0;
        }
        double currentWeight = this.weight;
        return (long)((ctx.indexPrice - currentEstimate) * currentWeight);
    }
}
