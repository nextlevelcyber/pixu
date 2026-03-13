package com.bedrock.mm.oms.store;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PriceRegionTest {

    @Test
    void testCreateRegion() {
        PriceRegion region = new PriceRegion(0, true, 0.995, 0.998);
        assertEquals(0, region.regionIndex);
        assertTrue(region.isBid);
        assertEquals(0.995, region.minRatio, 0.0001);
        assertEquals(0.998, region.maxRatio, 0.0001);
        assertTrue(region.isEmpty());
    }

    @Test
    void testUpdateBoundaries() {
        PriceRegion region = new PriceRegion(0, true, 0.995, 0.998);

        double fairMid = 100.0;
        long fairMidBits = Double.doubleToRawLongBits(fairMid);
        region.updateBoundaries(fairMidBits);

        // Expected: min = 100 * 0.995 = 99.5, max = 100 * 0.998 = 99.8
        assertEquals(99L, region.getMinPrice());
        assertEquals(99L, region.getMaxPrice());
    }

    @Test
    void testAddOrder() {
        PriceRegion region = new PriceRegion(0, true, 0.995, 0.998);

        assertTrue(region.addOrder(1000L));
        assertEquals(1, region.size());
        assertFalse(region.isEmpty());

        assertTrue(region.addOrder(2000L));
        assertEquals(2, region.size());
    }

    @Test
    void testAddOrderUntilFull() {
        PriceRegion region = new PriceRegion(0, true, 0.995, 0.998);

        // Fill up to capacity (32)
        for (int i = 0; i < 32; i++) {
            assertTrue(region.addOrder(i));
        }
        assertEquals(32, region.size());

        // Try to add one more - should fail
        assertFalse(region.addOrder(99L));
        assertEquals(32, region.size());
    }

    @Test
    void testRemoveOrder() {
        PriceRegion region = new PriceRegion(0, true, 0.995, 0.998);

        region.addOrder(1000L);
        region.addOrder(2000L);
        region.addOrder(3000L);
        assertEquals(3, region.size());

        assertTrue(region.removeOrder(2000L));
        assertEquals(2, region.size());

        assertFalse(region.removeOrder(9999L));
        assertEquals(2, region.size());
    }

    @Test
    void testGetOrderIds() {
        PriceRegion region = new PriceRegion(0, true, 0.995, 0.998);

        region.addOrder(1000L);
        region.addOrder(2000L);
        region.addOrder(3000L);

        long[] result = new long[10];
        int count = region.getOrderIds(result);

        assertEquals(3, count);
        assertTrue(contains(result, count, 1000L));
        assertTrue(contains(result, count, 2000L));
        assertTrue(contains(result, count, 3000L));
    }

    @Test
    void testGetStaleOrders() {
        PriceRegion region = new PriceRegion(0, true, 0.995, 0.998);

        // Update boundaries: fair mid = 10000 (1e-8 scale)
        // min = 10000 * 0.995 = 9950
        // max = 10000 * 0.998 = 9980
        long fairMidBits = Double.doubleToRawLongBits(10000.0);
        region.updateBoundaries(fairMidBits);

        region.addOrder(1000L);
        region.addOrder(2000L);
        region.addOrder(3000L);

        // Prices: 9900 (stale, too low), 9960 (ok), 10000 (stale, too high)
        long[] orderPrices = new long[32];
        orderPrices[0] = 9900L;
        orderPrices[1] = 9960L;
        orderPrices[2] = 10000L;

        long[] staleResult = new long[10];
        int staleCount = region.getStaleOrders(staleResult, orderPrices);

        assertEquals(2, staleCount);
        assertTrue(contains(staleResult, staleCount, 1000L));
        assertTrue(contains(staleResult, staleCount, 3000L));
    }

    @Test
    void testSizeAndIsEmpty() {
        PriceRegion region = new PriceRegion(0, true, 0.995, 0.998);

        assertTrue(region.isEmpty());
        assertEquals(0, region.size());

        region.addOrder(1000L);
        assertFalse(region.isEmpty());
        assertEquals(1, region.size());

        region.removeOrder(1000L);
        assertTrue(region.isEmpty());
        assertEquals(0, region.size());
    }

    private boolean contains(long[] array, int count, long value) {
        for (int i = 0; i < count; i++) {
            if (array[i] == value) {
                return true;
            }
        }
        return false;
    }
}
