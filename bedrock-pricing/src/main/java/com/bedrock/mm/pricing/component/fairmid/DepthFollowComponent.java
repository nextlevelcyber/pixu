package com.bedrock.mm.pricing.component.fairmid;

import com.bedrock.mm.pricing.InstrumentContext;
import com.bedrock.mm.pricing.component.PricingComponent;

public class DepthFollowComponent implements PricingComponent {
    private static final double SCALE = 1e8;
    private final int depth;

    public DepthFollowComponent(int depth) {
        this.depth = depth;
    }

    public DepthFollowComponent() {
        this(5);
    }

    @Override
    public long compute(InstrumentContext ctx, long currentEstimate) {
        int levels = Math.min(depth, ctx.bookLevels);
        if (levels == 0) {
            return 0;
        }

        // Prices/sizes in ctx are 1e-8 fixed-point. Decode to double for weighted avg.
        double bidWeightedSum = 0.0;
        double bidSizeSum = 0.0;
        for (int i = 0; i < levels; i++) {
            long priceBits = ctx.bidPrices[i];
            long sizeBits = ctx.bidSizes[i];
            if (sizeBits == 0) break;
            double price = priceBits / SCALE;
            double size  = sizeBits  / SCALE;
            bidWeightedSum += price * size;
            bidSizeSum += size;
        }

        double askWeightedSum = 0.0;
        double askSizeSum = 0.0;
        for (int i = 0; i < levels; i++) {
            long priceBits = ctx.askPrices[i];
            long sizeBits = ctx.askSizes[i];
            if (sizeBits == 0) break;
            double price = priceBits / SCALE;
            double size  = sizeBits  / SCALE;
            askWeightedSum += price * size;
            askSizeSum += size;
        }

        if (bidSizeSum == 0.0 || askSizeSum == 0.0) {
            return 0;
        }

        double weightedBid = bidWeightedSum / bidSizeSum;
        double weightedAsk = askWeightedSum / askSizeSum;
        double depthMid = (weightedBid + weightedAsk) / 2.0;

        // Re-encode to 1e-8 fixed-point and return offset from current estimate.
        return (long)(depthMid * SCALE) - currentEstimate;
    }
}
