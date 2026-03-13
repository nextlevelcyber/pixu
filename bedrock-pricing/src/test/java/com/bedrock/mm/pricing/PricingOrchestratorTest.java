package com.bedrock.mm.pricing;

import com.bedrock.mm.pricing.model.QuoteTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PricingOrchestratorTest {

    private List<QuoteTarget> published;
    private PricingOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        published = new ArrayList<>();
        orchestrator = PricingOrchestratorFactory.createDefault(1, qt -> published.add(qt.copy()));
    }

    @Test
    void testOnBboTriggersRecompute() {
        long bidPrice = Double.doubleToRawLongBits(100.0);
        long askPrice = Double.doubleToRawLongBits(101.0);
        long bidSize = Double.doubleToRawLongBits(10.0);
        long askSize = Double.doubleToRawLongBits(10.0);

        orchestrator.onBbo(bidPrice, askPrice, bidSize, askSize, System.nanoTime());

        assertEquals(1, published.size());
        QuoteTarget target = published.get(0);
        assertEquals(1, target.instrumentId);
        assertEquals(1, target.seqId);
        assertTrue(target.publishNanos > 0);
        assertTrue(target.fairMid != 0);
        assertTrue(target.bidPrice != 0);
        assertTrue(target.askPrice != 0);
    }

    @Test
    void testOnDepthNoRecompute() {
        long[] bidPrices = new long[10];
        long[] bidSizes = new long[10];
        long[] askPrices = new long[10];
        long[] askSizes = new long[10];
        for (int i = 0; i < 5; i++) {
            bidPrices[i] = Double.doubleToRawLongBits(100.0 - i);
            bidSizes[i] = Double.doubleToRawLongBits(10.0);
            askPrices[i] = Double.doubleToRawLongBits(101.0 + i);
            askSizes[i] = Double.doubleToRawLongBits(10.0);
        }

        orchestrator.onDepth(bidPrices, bidSizes, askPrices, askSizes, 5);

        assertEquals(0, published.size());
        assertEquals(5, orchestrator.getContext().bookLevels);
    }

    @Test
    void testOnPositionNoRecompute() {
        long netPosition = 500_000_000L; // 5.0 BTC in fixed-point 1e-8

        orchestrator.onPosition(netPosition);

        assertEquals(0, published.size());
        assertEquals(netPosition, orchestrator.getContext().netPosition);
    }

    @Test
    void testOnTradeNoRecompute() {
        long price = Double.doubleToRawLongBits(100.5);
        long size = Double.doubleToRawLongBits(1.0);

        orchestrator.onTrade(price, size, true);

        assertEquals(0, published.size());
        assertEquals(price, orchestrator.getContext().lastTradePrice);
        assertEquals(size, orchestrator.getContext().lastTradeSize);
        assertTrue(orchestrator.getContext().lastTradeBuy);
    }

    @Test
    void testOnIndexPriceNoRecompute() {
        long indexPrice = Double.doubleToRawLongBits(100.25);

        orchestrator.onIndexPrice(indexPrice);

        assertEquals(0, published.size());
        assertEquals(indexPrice, orchestrator.getContext().indexPrice);
    }

    @Test
    void testMultipleBboIncrementsSeqId() {
        long bidPrice = Double.doubleToRawLongBits(100.0);
        long askPrice = Double.doubleToRawLongBits(101.0);
        long bidSize = Double.doubleToRawLongBits(10.0);
        long askSize = Double.doubleToRawLongBits(10.0);

        orchestrator.onBbo(bidPrice, askPrice, bidSize, askSize, System.nanoTime());
        orchestrator.onBbo(bidPrice, askPrice, bidSize, askSize, System.nanoTime());
        orchestrator.onBbo(bidPrice, askPrice, bidSize, askSize, System.nanoTime());

        assertEquals(3, published.size());
        assertEquals(1, published.get(0).seqId);
        assertEquals(2, published.get(1).seqId);
        assertEquals(3, published.get(2).seqId);
    }

    @Test
    void testQuoteFlagsResetOnRecompute() {
        long bidPrice = Double.doubleToRawLongBits(100.0);
        long askPrice = Double.doubleToRawLongBits(101.0);
        long bidSize = Double.doubleToRawLongBits(10.0);
        long askSize = Double.doubleToRawLongBits(10.0);

        // First quote
        orchestrator.onBbo(bidPrice, askPrice, bidSize, askSize, System.nanoTime());
        int firstFlags = published.get(0).flags;

        // Trigger position bound (set large position in fixed-point 1e-8)
        orchestrator.onPosition(10_000_000_000L); // 100 BTC, exceeds 8 BTC soft limit
        orchestrator.onBbo(bidPrice, askPrice, bidSize, askSize, System.nanoTime());
        int secondFlags = published.get(1).flags;

        // Flags should change when position bound triggers
        assertTrue((secondFlags & 0x01) != 0); // bid suppression flag set
    }

    @Test
    void testFairMidCalculation() {
        long bidPrice = Double.doubleToRawLongBits(100.0);
        long askPrice = Double.doubleToRawLongBits(102.0);
        long bidSize = Double.doubleToRawLongBits(10.0);
        long askSize = Double.doubleToRawLongBits(10.0);

        orchestrator.onBbo(bidPrice, askPrice, bidSize, askSize, System.nanoTime());

        QuoteTarget target = published.get(0);
        double fairMid = Double.longBitsToDouble(target.fairMid);

        // Fair mid should be near 101.0 (simple mid without depth/index/trade)
        assertTrue(fairMid >= 100.0 && fairMid <= 102.0);
    }

    @Test
    void testSpreadApplied() {
        long bidPrice = Double.doubleToRawLongBits(100.0);
        long askPrice = Double.doubleToRawLongBits(100.0);
        long bidSize = Double.doubleToRawLongBits(10.0);
        long askSize = Double.doubleToRawLongBits(10.0);

        orchestrator.onBbo(bidPrice, askPrice, bidSize, askSize, System.nanoTime());

        QuoteTarget target = published.get(0);
        double targetBid = Double.longBitsToDouble(target.bidPrice);
        double targetAsk = Double.longBitsToDouble(target.askPrice);

        // Ask should be higher than bid due to spread
        assertTrue(targetAsk > targetBid);
    }
}
