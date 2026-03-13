package com.bedrock.mm.pricing.component.spread;

import com.bedrock.mm.pricing.InstrumentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VolatilitySpreadComponentTest {

    private InstrumentContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new InstrumentContext();
    }

    @Test
    void noTradeReturnsZero() {
        VolatilitySpreadComponent component = new VolatilitySpreadComponent(2.0, true);
        ctx.lastTradeSize = 0;
        ctx.fairMid = 100_00000000L; // $100

        long result = component.compute(ctx, 0);

        assertEquals(0, result);
    }

    @Test
    void tradeAtFairMidReturnsZero() {
        VolatilitySpreadComponent bidComponent = new VolatilitySpreadComponent(2.0, true);
        VolatilitySpreadComponent askComponent = new VolatilitySpreadComponent(2.0, false);
        ctx.lastTradeSize = 100_000_000L; // 1 BTC
        ctx.fairMid = 100_00000000L;      // $100
        ctx.lastTradePrice = 100_00000000L; // $100 (same as fairMid)

        assertEquals(0, bidComponent.compute(ctx, 0));
        assertEquals(0, askComponent.compute(ctx, 0));
    }

    @Test
    void tradeAboveFairMidAppliesSpread() {
        double volMultiplier = 2.0;
        VolatilitySpreadComponent bidComponent = new VolatilitySpreadComponent(volMultiplier, true);
        VolatilitySpreadComponent askComponent = new VolatilitySpreadComponent(volMultiplier, false);

        ctx.lastTradeSize = 100_000_000L;  // 1 BTC
        ctx.fairMid = 100_00000000L;       // $100
        ctx.lastTradePrice = 100_10000000L; // $100.10

        long rawVol = Math.abs(ctx.lastTradePrice - ctx.fairMid); // 10_000_000L = $0.10
        long expectedSpread = (long)(rawVol * volMultiplier); // 20_000_000L = $0.20

        long bidResult = bidComponent.compute(ctx, 0);
        long askResult = askComponent.compute(ctx, 0);

        assertEquals(-expectedSpread, bidResult);
        assertEquals(expectedSpread, askResult);
    }

    @Test
    void tradeBelowFairMidAppliesSpread() {
        double volMultiplier = 1.5;
        VolatilitySpreadComponent bidComponent = new VolatilitySpreadComponent(volMultiplier, true);
        VolatilitySpreadComponent askComponent = new VolatilitySpreadComponent(volMultiplier, false);

        ctx.lastTradeSize = 100_000_000L;  // 1 BTC
        ctx.fairMid = 100_00000000L;       // $100
        ctx.lastTradePrice = 99_90000000L; // $99.90

        long rawVol = Math.abs(ctx.lastTradePrice - ctx.fairMid); // 10_000_000L = $0.10
        long expectedSpread = (long)(rawVol * volMultiplier); // 15_000_000L = $0.15

        long bidResult = bidComponent.compute(ctx, 0);
        long askResult = askComponent.compute(ctx, 0);

        assertEquals(-expectedSpread, bidResult);
        assertEquals(expectedSpread, askResult);
    }

    @Test
    void setVolMultiplierUpdatesCorrectly() {
        VolatilitySpreadComponent component = new VolatilitySpreadComponent(2.0, true);
        ctx.lastTradeSize = 100_000_000L;
        ctx.fairMid = 100_00000000L;
        ctx.lastTradePrice = 100_10000000L;

        long rawVol = 10_000_000L;
        assertEquals(-20_000_000L, component.compute(ctx, 0)); // 2.0 multiplier

        component.setVolMultiplier(3.0);
        assertEquals(-30_000_000L, component.compute(ctx, 0)); // 3.0 multiplier
    }

    @Test
    void zeroFairMidReturnsZero() {
        VolatilitySpreadComponent component = new VolatilitySpreadComponent(2.0, true);
        ctx.lastTradeSize = 100_000_000L;
        ctx.fairMid = 0;
        ctx.lastTradePrice = 100_00000000L;

        assertEquals(0, component.compute(ctx, 0));
    }
}
