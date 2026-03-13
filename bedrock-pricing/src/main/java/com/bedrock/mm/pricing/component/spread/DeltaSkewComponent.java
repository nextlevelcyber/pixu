package com.bedrock.mm.pricing.component.spread;

import com.bedrock.mm.pricing.InstrumentContext;
import com.bedrock.mm.pricing.component.PricingComponent;

public class DeltaSkewComponent implements PricingComponent {
    private static final long SCALE = 100_000_000L;
    private volatile long maxPosition; // 1e-8 (e.g. 10 BTC = 1_000_000_000L)
    private volatile long maxSkew;     // 1e-8 (e.g. $0.50 = 50_000_000L)
    private final boolean isBid;

    public DeltaSkewComponent(long maxPosition, long maxSkew, boolean isBid) {
        this.maxPosition = maxPosition;
        this.maxSkew = maxSkew;
        this.isBid = isBid;
    }

    @Override
    public long compute(InstrumentContext ctx, long currentEstimate) {
        if (maxPosition == 0 || ctx.netPosition == 0) {
            return 0;
        }
        // skewRatio = netPosition / maxPosition (clamped to [-1, 1])
        double skewRatio = Math.max(-1.0, Math.min(1.0,
            (double) ctx.netPosition / maxPosition));
        // Long pos: lower bid (discourage buying), lower ask (encourage selling)
        // Short pos: raise bid (encourage buying), raise ask (discourage selling)
        // Both bid and ask move in the SAME direction opposite to position
        long skewOffset = (long)(skewRatio * maxSkew);
        return -skewOffset; // negative skew offset for both bid and ask
    }

    public void setMaxPosition(long maxPosition) {
        this.maxPosition = maxPosition;
    }

    public void setMaxSkew(long maxSkew) {
        this.maxSkew = maxSkew;
    }
}
