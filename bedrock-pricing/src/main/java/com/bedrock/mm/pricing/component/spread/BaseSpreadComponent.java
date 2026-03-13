package com.bedrock.mm.pricing.component.spread;

import com.bedrock.mm.pricing.InstrumentContext;
import com.bedrock.mm.pricing.component.PricingComponent;

public class BaseSpreadComponent implements PricingComponent {
    private static final long SCALE = 100_000_000L;
    private volatile long halfSpread; // 1e-8 fixed-point (e.g. 10_000_000L = $0.10)
    private final boolean isBid;

    public BaseSpreadComponent(long halfSpread, boolean isBid) {
        this.halfSpread = halfSpread;
        this.isBid = isBid;
    }

    @Override
    public long compute(InstrumentContext ctx, long currentEstimate) {
        return isBid ? -halfSpread : halfSpread;
    }

    public void setHalfSpread(long halfSpread) {
        this.halfSpread = halfSpread;
    }
}
