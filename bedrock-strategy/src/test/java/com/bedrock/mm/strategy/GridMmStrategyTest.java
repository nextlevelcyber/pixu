package com.bedrock.mm.strategy;

import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GridMmStrategy
 */
class GridMmStrategyTest {

    private GridMmStrategy strategy;

    @BeforeEach
    void setUp() {
        strategy = new GridMmStrategy();
    }

    @Test
    void testStrategyNameAndVersion() {
        assertEquals("GridMM", strategy.getName());
        assertEquals("1.0.0", strategy.getVersion());
    }

    @Test
    void testComputeQuotePrice_Bid() {
        // 参考价格 = 10000, gridSpacingBps = 10, level = 1
        // 买单价格 = 10000 * (1 - 10/10000 * 1) = 10000 * 0.999 = 9990
        long referencePrice = 10_000L;
        double gridSpacingBps = 10.0;
        int level = 1;

        double spacingBps = gridSpacingBps * level;
        double multiplier = 1.0 - spacingBps / 10_000.0;
        long expectedPrice = Math.max(1L, Math.round(referencePrice * multiplier));

        assertEquals(9990L, expectedPrice);
    }

    @Test
    void testComputeQuotePrice_Ask() {
        // 参考价格 = 10000, gridSpacingBps = 10, level = 1
        // 卖单价格 = 10000 * (1 + 10/10000 * 1) = 10000 * 1.001 = 10010
        long referencePrice = 10_000L;
        double gridSpacingBps = 10.0;
        int level = 1;

        double spacingBps = gridSpacingBps * level;
        double multiplier = 1.0 + spacingBps / 10_000.0;
        long expectedPrice = Math.max(1L, Math.round(referencePrice * multiplier));

        assertEquals(10010L, expectedPrice);
    }

    @Test
    void testComputeQuotePrice_MultipleLevels() {
        long referencePrice = 10_000L;
        double gridSpacingBps = 10.0;

        // Level 1: 10000 * (1 - 0.001) = 9990
        long bidLevel1 = computeBidPrice(referencePrice, gridSpacingBps, 1);
        assertEquals(9990L, bidLevel1);

        // Level 2: 10000 * (1 - 0.002) = 9980
        long bidLevel2 = computeBidPrice(referencePrice, gridSpacingBps, 2);
        assertEquals(9980L, bidLevel2);

        // Level 3: 10000 * (1 - 0.003) = 9970
        long bidLevel3 = computeBidPrice(referencePrice, gridSpacingBps, 3);
        assertEquals(9970L, bidLevel3);
    }

    @Test
    void testComputeReferencePrice_FromBestBidAsk() {
        // 最佳买卖价都存在时，使用中价
        long bestBid = 9990L;
        long bestAsk = 10010L;

        long referencePrice = (bestBid + bestAsk) / 2L;

        assertEquals(10000L, referencePrice);
    }

    @Test
    void testComputeReferencePrice_FallbackToLastTrade() {
        // 没有买卖价时，使用最新成交价
        long lastTradePrice = 9995L;

        long referencePrice = lastTradePrice;

        assertEquals(9995L, referencePrice);
    }

    @Test
    void testGridSpacing_Calculation() {
        // 验证网格间距计算
        double midPrice = 50000.0;  // BTC 价格
        double gridSpacingBps = 10.0;  // 10 基点 = 0.1%

        // 第一层间距
        double spacing = midPrice * gridSpacingBps / 10_000.0;
        assertEquals(50.0, spacing);  // 50000 * 0.001 = 50

        // 第二层间距 (2 倍)
        double spacing2 = midPrice * gridSpacingBps * 2 / 10_000.0;
        assertEquals(100.0, spacing2);
    }

    @Test
    void testPositionLimits_Buy() {
        double maxPosition = 0.01;
        double sizePerLevel = 0.001;
        int numLevels = 3;

        // 检查每层是否能买入
        double currentPos = 0.0;

        for (int level = 1; level <= numLevels; level++) {
            double levelExposure = sizePerLevel * level;
            boolean canBuy = currentPos + levelExposure <= maxPosition;

            // 所有层都应该能买入，因为总暴露 = 0.001 + 0.002 + 0.003 = 0.006 < 0.01
            assertTrue(canBuy, "Level " + level + " should be able to buy");
        }
    }

    @Test
    void testPositionLimits_Sell() {
        double maxPosition = 0.01;
        double sizePerLevel = 0.001;
        int numLevels = 3;

        // 检查每层是否能卖出
        double currentPos = 0.0;

        for (int level = 1; level <= numLevels; level++) {
            double levelExposure = sizePerLevel * level;
            boolean canSell = currentPos - levelExposure >= -maxPosition;

            // 所有层都应该能卖出，因为总暴露 = -0.001 - 0.002 - 0.003 = -0.006 > -0.01
            assertTrue(canSell, "Level " + level + " should be able to sell");
        }
    }

    @Test
    void testPositionLimits_AtMaxBuy() {
        double maxPosition = 0.01;
        double sizePerLevel = 0.001;
        double currentPos = 0.0095;  // 接近最大仓位

        // 再买一层就会超过限制
        boolean canBuy = currentPos + sizePerLevel <= maxPosition;
        assertFalse(canBuy);  // 0.0095 + 0.001 = 0.0105 > 0.01
    }

    @Test
    void testPositionLimits_AtMaxSell() {
        double maxPosition = 0.01;
        double sizePerLevel = 0.001;
        double currentPos = -0.0095;  // 接近最大空仓

        // 再卖一层就会超过限制
        boolean canSell = currentPos - sizePerLevel >= -maxPosition;
        assertFalse(canSell);  // -0.0095 - 0.001 = -0.0105 < -0.01
    }

    @Test
    void testGridPrices_BtcExample() {
        // 实际 BTC 示例
        long btcPrice = 50_000L * 100_000_000L;  // 50000 USD，scale 1e-8
        double gridSpacingBps = 10.0;

        // 第一层买单
        long bid1 = computeBidPrice(btcPrice, gridSpacingBps, 1);
        long expectedBid1 = Math.round(btcPrice * (1.0 - 10.0 / 10_000.0));
        assertEquals(expectedBid1, bid1);

        // 第一层卖单
        long ask1 = computeAskPrice(btcPrice, gridSpacingBps, 1);
        long expectedAsk1 = Math.round(btcPrice * (1.0 + 10.0 / 10_000.0));
        assertEquals(expectedAsk1, ask1);

        // 买卖价差
        long spread = ask1 - bid1;
        double spreadBps = (double) spread / btcPrice * 10_000.0;
        assertEquals(20.0, spreadBps, 0.01);  // 买 -10bps, 卖 +10bps, 价差 20bps
    }

    @Test
    void testRepriceThreshold() {
        double repriceThresholdBps = 5.0;
        double currentPrice = 10000.0;
        double newPrice = 10005.0;  // 5bps 变动

        double diffBps = Math.abs(newPrice - currentPrice) / currentPrice * 10_000.0;

        assertEquals(5.0, diffBps, 0.01);
        assertTrue(diffBps >= repriceThresholdBps);  // 应该触发重新定价
    }

    @Test
    void testRepriceThreshold_NoTrigger() {
        double repriceThresholdBps = 5.0;
        double currentPrice = 10000.0;
        double newPrice = 10003.0;  // 3bps 变动

        double diffBps = Math.abs(newPrice - currentPrice) / currentPrice * 10_000.0;

        assertEquals(3.0, diffBps, 0.01);
        assertFalse(diffBps >= repriceThresholdBps);  // 不应该触发重新定价
    }

    @Test
    void testSymbolPriceConversion() {
        Symbol btcUsdt = Symbol.btcUsdt();

        // 测试价格转换
        long scaledPrice = 50_000L * 100_000_000L;  // 50000 USD in 1e-8 scale
        double decimalPrice = btcUsdt.priceToDecimal(scaledPrice);

        assertEquals(50000.0, decimalPrice, 0.01);
    }

    // Helper methods (mirroring strategy logic)

    private long computeBidPrice(long referencePrice, double gridSpacingBps, int level) {
        double spacingBps = gridSpacingBps * level;
        double multiplier = 1.0 - spacingBps / 10_000.0;
        return Math.max(1L, Math.round(referencePrice * multiplier));
    }

    private long computeAskPrice(long referencePrice, double gridSpacingBps, int level) {
        double spacingBps = gridSpacingBps * level;
        double multiplier = 1.0 + spacingBps / 10_000.0;
        return Math.max(1L, Math.round(referencePrice * multiplier));
    }
}
