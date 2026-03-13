package com.bedrock.mm.pricing.component.fairmid;

import com.bedrock.mm.pricing.InstrumentContext;
import com.bedrock.mm.pricing.component.PricingComponent;

public class TradeFlowComponent implements PricingComponent {
    private volatile double alpha;
    private volatile long tickSizeLong;
    private double ewma;

    public TradeFlowComponent(double alpha, long tickSizeLong) {
        this.alpha = alpha;
        this.tickSizeLong = tickSizeLong;
        this.ewma = 0.0;
    }

    public TradeFlowComponent() {
        this(0.95, 100L);
    }

    public void setAlpha(double alpha) {
        this.alpha = alpha;
    }

    public void setTickSizeLong(long tickSizeLong) {
        this.tickSizeLong = tickSizeLong;
    }

    @Override
    public long compute(InstrumentContext ctx, long currentEstimate) {
        if (ctx.lastTradeSize <= 0) {
            return (long) ewma;
        }

        double currentAlpha = this.alpha;
        long currentTickSize = this.tickSizeLong;

        long signal = ctx.lastTradeBuy ? currentTickSize : -currentTickSize;
        ewma = currentAlpha * ewma + (1.0 - currentAlpha) * signal;

        return (long) ewma;
    }
}
