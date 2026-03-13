package com.bedrock.mm.pricing;

import com.bedrock.mm.pricing.component.PricingComponent;
import com.bedrock.mm.pricing.component.fairmid.DepthFollowComponent;
import com.bedrock.mm.pricing.component.fairmid.IndexFollowComponent;
import com.bedrock.mm.pricing.component.fairmid.TradeFlowComponent;
import com.bedrock.mm.pricing.component.spread.BaseSpreadComponent;
import com.bedrock.mm.pricing.component.spread.DeltaSkewComponent;
import com.bedrock.mm.pricing.component.spread.PositionBoundComponent;
import com.bedrock.mm.pricing.component.spread.VolatilitySpreadComponent;
import com.bedrock.mm.pricing.model.QuoteTarget;
import com.bedrock.mm.pricing.pipeline.FairMidPipeline;
import com.bedrock.mm.pricing.pipeline.QuoteConstructPipeline;

import java.util.function.Consumer;

/**
 * PricingOrchestratorFactory - factory for creating PricingOrchestrator instances.
 *
 * Provides default configuration with standard components:
 * - FairMid: DepthFollow(5 levels) + IndexFollow(0.3 weight) + TradeFlow(0.95 alpha, 100L tick)
 * - QuoteConstruct:
 *   - BaseSpread(10_000_000L = $0.10 half spread)
 *   - VolatilitySpread(2.0 multiplier)
 *   - DeltaSkew(1_000_000_000L = 10 BTC max, 50_000_000L = $0.50 max skew)
 *   - PositionBound(800_000_000L = 8 BTC soft limit)
 */
public class PricingOrchestratorFactory {

    /**
     * Create PricingOrchestrator with default component configuration.
     *
     * @param instrumentId instrument ID
     * @param publisher QuoteTarget publisher callback
     * @return configured PricingOrchestrator
     */
    public static PricingOrchestrator createDefault(int instrumentId, Consumer<QuoteTarget> publisher) {
        // FairMid pipeline
        PricingComponent[] fairMidComponents = {
            new DepthFollowComponent(5),
            new IndexFollowComponent(0.3),
            new TradeFlowComponent(0.95, 100L)
        };
        FairMidPipeline fairMidPipeline = new FairMidPipeline(fairMidComponents);

        // QuoteConstruct pipeline (separate bid and ask)
        long halfSpread = 10_000_000L; // $0.10
        double volMultiplier = 2.0;
        long maxPosition = 1_000_000_000L; // 10 BTC
        long maxSkew = 50_000_000L; // $0.50
        long softLimit = 800_000_000L; // 8 BTC

        PricingComponent[] bidComponents = {
            new BaseSpreadComponent(halfSpread, true),
            new VolatilitySpreadComponent(volMultiplier, true),
            new DeltaSkewComponent(maxPosition, maxSkew, true),
            new PositionBoundComponent(softLimit, true)
        };

        PricingComponent[] askComponents = {
            new BaseSpreadComponent(halfSpread, false),
            new VolatilitySpreadComponent(volMultiplier, false),
            new DeltaSkewComponent(maxPosition, maxSkew, false),
            new PositionBoundComponent(softLimit, false)
        };

        QuoteConstructPipeline quoteConstructPipeline = new QuoteConstructPipeline(bidComponents, askComponents);

        return new PricingOrchestrator(instrumentId, fairMidPipeline, quoteConstructPipeline, publisher);
    }

    /**
     * Create PricingOrchestrator with custom pipelines.
     *
     * @param instrumentId instrument ID
     * @param fairMidPipeline custom FairMid pipeline
     * @param quoteConstructPipeline custom QuoteConstruct pipeline
     * @param publisher QuoteTarget publisher callback
     * @return configured PricingOrchestrator
     */
    public static PricingOrchestrator create(
        int instrumentId,
        FairMidPipeline fairMidPipeline,
        QuoteConstructPipeline quoteConstructPipeline,
        Consumer<QuoteTarget> publisher
    ) {
        return new PricingOrchestrator(instrumentId, fairMidPipeline, quoteConstructPipeline, publisher);
    }
}
