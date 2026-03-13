package com.bedrock.mm.pricing.component.spread;

import com.bedrock.mm.pricing.InstrumentContext;
import com.bedrock.mm.pricing.component.PricingComponent;

public class PositionBoundComponent implements PricingComponent {
    private static final long LARGE_OFFSET = Long.MIN_VALUE / 2;
    private volatile long softLimit; // 1e-8 (typically 80% of hard limit)
    private final boolean isBid;

    public PositionBoundComponent(long softLimit, boolean isBid) {
        this.softLimit = softLimit;
        this.isBid = isBid;
    }

    @Override
    public long compute(InstrumentContext ctx, long currentEstimate) {
        if (softLimit == 0) {
            return 0;
        }
        if (isBid && ctx.netPosition >= softLimit) {
            ctx.quoteFlags |= 0x01; // signal bid suppression
            return LARGE_OFFSET;
        }
        if (!isBid && ctx.netPosition <= -softLimit) {
            ctx.quoteFlags |= 0x02; // signal ask suppression
            return LARGE_OFFSET;
        }
        return 0;
    }

    public void setSoftLimit(long softLimit) {
        this.softLimit = softLimit;
    }
}
