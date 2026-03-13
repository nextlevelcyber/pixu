package com.bedrock.mm.pricing.component.spread;

import com.bedrock.mm.pricing.InstrumentContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BaseSpreadComponentTest {

    private InstrumentContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new InstrumentContext();
    }

    @Test
    void bidReturnsNegativeHalfSpread() {
        long halfSpread = 10_000_000L; // $0.10
        BaseSpreadComponent component = new BaseSpreadComponent(halfSpread, true);

        long result = component.compute(ctx, 0);

        assertEquals(-halfSpread, result);
    }

    @Test
    void askReturnsPositiveHalfSpread() {
        long halfSpread = 10_000_000L; // $0.10
        BaseSpreadComponent component = new BaseSpreadComponent(halfSpread, false);

        long result = component.compute(ctx, 0);

        assertEquals(halfSpread, result);
    }

    @Test
    void setHalfSpreadUpdatesCorrectly() {
        long initialSpread = 10_000_000L; // $0.10
        long newSpread = 20_000_000L;     // $0.20
        BaseSpreadComponent component = new BaseSpreadComponent(initialSpread, true);

        assertEquals(-initialSpread, component.compute(ctx, 0));

        component.setHalfSpread(newSpread);
        assertEquals(-newSpread, component.compute(ctx, 0));
    }

    @Test
    void zeroSpreadReturnZero() {
        BaseSpreadComponent bidComponent = new BaseSpreadComponent(0, true);
        BaseSpreadComponent askComponent = new BaseSpreadComponent(0, false);

        assertEquals(0, bidComponent.compute(ctx, 0));
        assertEquals(0, askComponent.compute(ctx, 0));
    }
}
