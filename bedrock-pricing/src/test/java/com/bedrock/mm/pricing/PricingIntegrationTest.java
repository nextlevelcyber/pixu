package com.bedrock.mm.pricing;

import com.bedrock.mm.pricing.model.QuoteTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PricingIntegrationTest - end-to-end integration test for pricing engine.
 *
 * Tests realistic market data flow: depth -> bbo -> trade -> position updates.
 */
class PricingIntegrationTest {

    private List<QuoteTarget> published;
    private PricingOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        published = new ArrayList<>();
        orchestrator = PricingOrchestratorFactory.createDefault(1, qt -> published.add(qt.copy()));
    }

    @Test
    void testRealisticMarketDataFlow() {
        // Setup: 5-level depth book
        long[] bidPrices = new long[10];
        long[] bidSizes = new long[10];
        long[] askPrices = new long[10];
        long[] askSizes = new long[10];
        for (int i = 0; i < 5; i++) {
            bidPrices[i] = Double.doubleToRawLongBits(100.0 - i * 0.1);
            bidSizes[i] = Double.doubleToRawLongBits(10.0 + i);
            askPrices[i] = Double.doubleToRawLongBits(100.5 + i * 0.1);
            askSizes[i] = Double.doubleToRawLongBits(10.0 + i);
        }
        orchestrator.onDepth(bidPrices, bidSizes, askPrices, askSizes, 5);

        // External index price
        orchestrator.onIndexPrice(Double.doubleToRawLongBits(100.3));

        // Trade flow
        orchestrator.onTrade(Double.doubleToRawLongBits(100.2), Double.doubleToRawLongBits(1.0), true);

        // Position (fixed-point 1e-8)
        orchestrator.onPosition(250_000_000L); // 2.5 BTC

        // BBO update triggers recompute
        long bidPrice = Double.doubleToRawLongBits(100.0);
        long askPrice = Double.doubleToRawLongBits(100.5);
        long bidSize = Double.doubleToRawLongBits(10.0);
        long askSize = Double.doubleToRawLongBits(10.0);
        orchestrator.onBbo(bidPrice, askPrice, bidSize, askSize, System.nanoTime());

        assertEquals(1, published.size());
        QuoteTarget target = published.get(0);

        // Verify all inputs were used
        double fairMid = Double.longBitsToDouble(target.fairMid);
        assertTrue(fairMid > 0);

        double targetBid = Double.longBitsToDouble(target.bidPrice);
        double targetAsk = Double.longBitsToDouble(target.askPrice);

        // Sanity checks
        assertTrue(targetAsk > targetBid, "Ask should be higher than bid");
        assertTrue(targetBid < fairMid, "Bid should be below fair mid");
        assertTrue(targetAsk > fairMid, "Ask should be above fair mid");

        // Spread should be reasonable (not collapsed)
        double spread = targetAsk - targetBid;
        assertTrue(spread > 0.1, "Spread should be at least $0.10 (base half spread)");
    }

    @Test
    void testDepthFollowComponentInfluence() {
        // Setup asymmetric depth (heavy bid side)
        long[] bidPrices = new long[10];
        long[] bidSizes = new long[10];
        long[] askPrices = new long[10];
        long[] askSizes = new long[10];
        for (int i = 0; i < 5; i++) {
            bidPrices[i] = Double.doubleToRawLongBits(99.9 - i * 0.1);
            bidSizes[i] = Double.doubleToRawLongBits(100.0); // heavy bid
            askPrices[i] = Double.doubleToRawLongBits(100.1 + i * 0.1);
            askSizes[i] = Double.doubleToRawLongBits(10.0); // light ask
        }
        orchestrator.onDepth(bidPrices, bidSizes, askPrices, askSizes, 5);

        // BBO at 100.0 / 100.1
        long bidPrice = Double.doubleToRawLongBits(100.0);
        long askPrice = Double.doubleToRawLongBits(100.1);
        long bidSize = Double.doubleToRawLongBits(10.0);
        long askSize = Double.doubleToRawLongBits(10.0);
        orchestrator.onBbo(bidPrice, askPrice, bidSize, askSize, System.nanoTime());

        QuoteTarget target = published.get(0);
        double fairMid = Double.longBitsToDouble(target.fairMid);

        // Fair mid should be pulled down by heavy bid side
        assertTrue(fairMid < 100.05, "Fair mid should be pulled toward bid side");
    }

    @Test
    void testPositionSkewInfluence() {
        // Neutral position (fixed-point 1e-8)
        orchestrator.onPosition(0L);
        orchestrator.onBbo(
            Double.doubleToRawLongBits(100.0),
            Double.doubleToRawLongBits(100.5),
            Double.doubleToRawLongBits(10.0),
            Double.doubleToRawLongBits(10.0),
            System.nanoTime()
        );
        double neutralBid = Double.longBitsToDouble(published.get(0).bidPrice);
        double neutralAsk = Double.longBitsToDouble(published.get(0).askPrice);

        // Long position (should lower quotes to encourage selling)
        published.clear();
        orchestrator.onPosition(500_000_000L); // long 5 BTC in fixed-point 1e-8
        orchestrator.onBbo(
            Double.doubleToRawLongBits(100.0),
            Double.doubleToRawLongBits(100.5),
            Double.doubleToRawLongBits(10.0),
            Double.doubleToRawLongBits(10.0),
            System.nanoTime()
        );
        double longBid = Double.longBitsToDouble(published.get(0).bidPrice);
        double longAsk = Double.longBitsToDouble(published.get(0).askPrice);

        // Both bid and ask should be lower when long
        assertTrue(longBid < neutralBid, "Bid should be lower when long");
        assertTrue(longAsk < neutralAsk, "Ask should be lower when long");
    }

    @Test
    void testPositionBoundSuppression() {
        // Large long position (exceeds soft limit of 8 BTC) in fixed-point 1e-8
        orchestrator.onPosition(1_000_000_000L); // 10 BTC
        orchestrator.onBbo(
            Double.doubleToRawLongBits(100.0),
            Double.doubleToRawLongBits(100.5),
            Double.doubleToRawLongBits(10.0),
            Double.doubleToRawLongBits(10.0),
            System.nanoTime()
        );

        QuoteTarget target = published.get(0);
        // Bid suppression flag should be set
        assertTrue((target.flags & 0x01) != 0, "Bid suppression flag should be set");
    }

    @Test
    void testVolatilitySpreadExpansion() {
        // Recent trade far from fair mid (high volatility)
        long fairMid = Double.doubleToRawLongBits(100.0);
        long tradePriceFar = Double.doubleToRawLongBits(105.0); // $5 away
        orchestrator.onTrade(tradePriceFar, Double.doubleToRawLongBits(1.0), true);

        orchestrator.onBbo(
            Double.doubleToRawLongBits(100.0),
            Double.doubleToRawLongBits(100.5),
            Double.doubleToRawLongBits(10.0),
            Double.doubleToRawLongBits(10.0),
            System.nanoTime()
        );
        double highVolBid = Double.longBitsToDouble(published.get(0).bidPrice);
        double highVolAsk = Double.longBitsToDouble(published.get(0).askPrice);
        double highVolSpread = highVolAsk - highVolBid;

        // Recent trade close to fair mid (low volatility)
        published.clear();
        long tradeClosePrice = Double.doubleToRawLongBits(100.2); // $0.2 away
        orchestrator.onTrade(tradeClosePrice, Double.doubleToRawLongBits(1.0), true);

        orchestrator.onBbo(
            Double.doubleToRawLongBits(100.0),
            Double.doubleToRawLongBits(100.5),
            Double.doubleToRawLongBits(10.0),
            Double.doubleToRawLongBits(10.0),
            System.nanoTime()
        );
        double lowVolBid = Double.longBitsToDouble(published.get(0).bidPrice);
        double lowVolAsk = Double.longBitsToDouble(published.get(0).askPrice);
        double lowVolSpread = lowVolAsk - lowVolBid;

        // High volatility should produce wider spread
        assertTrue(highVolSpread > lowVolSpread, "High volatility should widen spread");
    }

    @Test
    void testIndexFollowComponentInfluence() {
        // Index price higher than BBO mid
        orchestrator.onIndexPrice(Double.doubleToRawLongBits(101.0));
        orchestrator.onBbo(
            Double.doubleToRawLongBits(100.0),
            Double.doubleToRawLongBits(100.5),
            Double.doubleToRawLongBits(10.0),
            Double.doubleToRawLongBits(10.0),
            System.nanoTime()
        );
        double fairMidHigh = Double.longBitsToDouble(published.get(0).fairMid);

        // Index price lower than BBO mid
        published.clear();
        orchestrator.onIndexPrice(Double.doubleToRawLongBits(99.5));
        orchestrator.onBbo(
            Double.doubleToRawLongBits(100.0),
            Double.doubleToRawLongBits(100.5),
            Double.doubleToRawLongBits(10.0),
            Double.doubleToRawLongBits(10.0),
            System.nanoTime()
        );
        double fairMidLow = Double.longBitsToDouble(published.get(0).fairMid);

        // Fair mid should follow index direction
        assertTrue(fairMidHigh > fairMidLow, "Fair mid should follow index price");
    }

    @Test
    void testTradeFlowComponentAccumulation() {
        // Series of buy trades
        for (int i = 0; i < 10; i++) {
            orchestrator.onTrade(Double.doubleToRawLongBits(100.0), Double.doubleToRawLongBits(1.0), true);
        }
        orchestrator.onBbo(
            Double.doubleToRawLongBits(100.0),
            Double.doubleToRawLongBits(100.5),
            Double.doubleToRawLongBits(10.0),
            Double.doubleToRawLongBits(10.0),
            System.nanoTime()
        );
        double fairMidAfterBuys = Double.longBitsToDouble(published.get(0).fairMid);

        // Series of sell trades
        published.clear();
        for (int i = 0; i < 10; i++) {
            orchestrator.onTrade(Double.doubleToRawLongBits(100.0), Double.doubleToRawLongBits(1.0), false);
        }
        orchestrator.onBbo(
            Double.doubleToRawLongBits(100.0),
            Double.doubleToRawLongBits(100.5),
            Double.doubleToRawLongBits(10.0),
            Double.doubleToRawLongBits(10.0),
            System.nanoTime()
        );
        double fairMidAfterSells = Double.longBitsToDouble(published.get(0).fairMid);

        // Fair mid should be pushed up by buy flow, down by sell flow
        assertTrue(fairMidAfterBuys > fairMidAfterSells, "Buy flow should push fair mid up");
    }
}
