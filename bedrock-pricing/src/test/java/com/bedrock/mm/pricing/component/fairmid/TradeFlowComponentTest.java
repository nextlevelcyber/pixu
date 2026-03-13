package com.bedrock.mm.pricing.component.fairmid;

import com.bedrock.mm.pricing.InstrumentContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TradeFlowComponentTest {

    @Test
    void testNoTrade_returnsCurrentEwma() {
        TradeFlowComponent component = new TradeFlowComponent(0.95, 100L);
        InstrumentContext ctx = new InstrumentContext();
        ctx.lastTradeSize = 0;

        long offset = component.compute(ctx, 100_000_000L);

        assertEquals(0, offset); // Initial ewma is 0
    }

    @Test
    void testBuyTrade_incrementsEwma() {
        TradeFlowComponent component = new TradeFlowComponent(0.95, 100L);
        InstrumentContext ctx = new InstrumentContext();
        ctx.lastTradeSize = 1000L;
        ctx.lastTradeBuy = true;

        long offset1 = component.compute(ctx, 100_000_000L);
        // ewma = 0.95 * 0 + 0.05 * 100 = 5
        assertEquals(5L, offset1);

        long offset2 = component.compute(ctx, 100_000_000L);
        // ewma = 0.95 * 5 + 0.05 * 100 = 9.75
        assertTrue(offset2 > offset1);
    }

    @Test
    void testSellTrade_decrementsEwma() {
        TradeFlowComponent component = new TradeFlowComponent(0.95, 100L);
        InstrumentContext ctx = new InstrumentContext();
        ctx.lastTradeSize = 1000L;
        ctx.lastTradeBuy = false;

        long offset1 = component.compute(ctx, 100_000_000L);
        // ewma = 0.95 * 0 + 0.05 * (-100) = -5
        assertEquals(-5L, offset1);

        long offset2 = component.compute(ctx, 100_000_000L);
        // ewma = 0.95 * (-5) + 0.05 * (-100) = -9.75
        assertTrue(offset2 < offset1);
    }

    @Test
    void testAlternatingTrades_ewmaDecays() {
        TradeFlowComponent component = new TradeFlowComponent(0.9, 100L);
        InstrumentContext ctx = new InstrumentContext();

        // First buy
        ctx.lastTradeSize = 1000L;
        ctx.lastTradeBuy = true;
        long offset1 = component.compute(ctx, 100_000_000L);
        // ewma = 0.9 * 0 + 0.1 * 100 = 10
        // But since we cast to long, we get 10 (integer truncation)
        assertTrue(offset1 >= 9L && offset1 <= 10L, "Expected ~10, got " + offset1);

        // Then sell
        ctx.lastTradeBuy = false;
        long offset2 = component.compute(ctx, 100_000_000L);
        // ewma = 0.9 * 10 + 0.1 * (-100) = 9 - 10 = -1
        assertEquals(-1L, offset2);
    }

    @Test
    void testAlphaUpdate_affectsDecay() {
        TradeFlowComponent component = new TradeFlowComponent(0.9, 100L);
        component.setAlpha(0.5); // Faster decay

        InstrumentContext ctx = new InstrumentContext();
        ctx.lastTradeSize = 1000L;
        ctx.lastTradeBuy = true;

        long offset = component.compute(ctx, 100_000_000L);
        // ewma = 0.5 * 0 + 0.5 * 100 = 50
        assertEquals(50L, offset);
    }

    @Test
    void testTickSizeUpdate_affectsSignalMagnitude() {
        TradeFlowComponent component = new TradeFlowComponent(0.95, 100L);
        component.setTickSizeLong(200L);

        InstrumentContext ctx = new InstrumentContext();
        ctx.lastTradeSize = 1000L;
        ctx.lastTradeBuy = true;

        long offset = component.compute(ctx, 100_000_000L);
        // ewma = 0.95 * 0 + 0.05 * 200 = 10
        assertEquals(10L, offset);
    }
}
