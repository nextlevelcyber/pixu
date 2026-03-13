package com.bedrock.mm.pricing.component.spread;

import com.bedrock.mm.pricing.InstrumentContext;
import com.bedrock.mm.pricing.component.PricingComponent;

public class VolatilitySpreadComponent implements PricingComponent {
    private volatile double volMultiplier; // default 2.0
    private final boolean isBid;

    public VolatilitySpreadComponent(double volMultiplier, boolean isBid) {
        this.volMultiplier = volMultiplier;
        this.isBid = isBid;
    }

    @Override
    public long compute(InstrumentContext ctx, long currentEstimate) {
        // Vol proxy: |lastTradePrice - fairMid|
        if (ctx.lastTradeSize == 0 || ctx.fairMid == 0) {
            return 0;
        }
        long rawVol = Math.abs(ctx.lastTradePrice - ctx.fairMid);
        long extraSpread = (long)(rawVol * volMultiplier);
        return isBid ? -extraSpread : extraSpread;
    }

    public void setVolMultiplier(double v) {
        this.volMultiplier = v;
    }
}
