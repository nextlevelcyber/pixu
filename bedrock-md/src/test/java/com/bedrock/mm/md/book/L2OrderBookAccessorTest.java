package com.bedrock.mm.md.book;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for L2OrderBook level accessors and getValidLevels().
 */
public class L2OrderBookAccessorTest {
    private L2OrderBook book;

    @BeforeEach
    void setUp() {
        book = new L2OrderBook();
    }

    @Test
    void testGetValidLevelsEmpty() {
        assertEquals(0, book.getValidLevels());
    }

    @Test
    void testGetValidLevelsAfterSnapshot() {
        long[] bidPrices = {5010_0000_0000L, 5009_0000_0000L, 5008_0000_0000L};
        long[] bidSizes  = {100_000_000L, 200_000_000L, 300_000_000L};
        long[] askPrices = {5011_0000_0000L, 5012_0000_0000L};
        long[] askSizes  = {150_000_000L, 250_000_000L};

        book.applySnapshot(bidPrices, bidSizes, askPrices, askSizes, 1L, System.nanoTime());

        // valid levels = min(bidDepth=3, askDepth=2) = 2
        assertEquals(2, book.getValidLevels());
    }

    @Test
    void testGetValidLevelsSymmetric() {
        long[] bidPrices = {5010_0000_0000L, 5009_0000_0000L};
        long[] bidSizes  = {100_000_000L, 200_000_000L};
        long[] askPrices = {5011_0000_0000L, 5012_0000_0000L};
        long[] askSizes  = {150_000_000L, 250_000_000L};

        book.applySnapshot(bidPrices, bidSizes, askPrices, askSizes, 1L, System.nanoTime());

        assertEquals(2, book.getValidLevels());
    }

    @Test
    void testBidPrice0AfterApplyDelta() {
        book.applyDelta(5010_0000_0000L, 100_000_000L, true, 1L, System.nanoTime());
        book.applyDelta(5011_0000_0000L, 80_000_000L, false, 1L, System.nanoTime());

        assertEquals(5010_0000_0000L, book.getBidPrice(0));
        assertEquals(100_000_000L, book.getBidSize(0));
        assertEquals(5011_0000_0000L, book.getAskPrice(0));
        assertEquals(80_000_000L, book.getAskSize(0));
    }

    @Test
    void testBidAskNonZeroAfterDelta() {
        book.applyDelta(5000_0000_0000L, 50_000_000L, true, 1L, System.nanoTime());
        book.applyDelta(5001_0000_0000L, 60_000_000L, false, 1L, System.nanoTime());

        long bid0 = book.getBidPrice(0);
        long ask0 = book.getAskPrice(0);

        // Both sides populated: should satisfy the BBO condition
        assertTrue(bid0 > 0);
        assertTrue(ask0 > 0);
        assertEquals(1, book.getValidLevels());
    }

    @Test
    void testGetValidLevelsOnlyBidSide() {
        book.applyDelta(5010_0000_0000L, 100_000_000L, true, 1L, System.nanoTime());

        // Ask side empty → validLevels = 0
        assertEquals(0, book.getValidLevels());
    }

    @Test
    void testLevelAccessorsOutOfRange() {
        // Accessing beyond valid levels returns Long.MIN_VALUE / 0
        assertTrue(book.getBidPrice(0) < 0); // EMPTY_PRICE = Long.MIN_VALUE
        assertEquals(0, book.getBidSize(0));
        assertTrue(book.getAskPrice(0) < 0);
        assertEquals(0, book.getAskSize(0));
    }

    @Test
    void testGetValidLevelsAfterDeleteReducesDepth() {
        book.applyDelta(5010_0000_0000L, 100_000_000L, true, 1L, System.nanoTime());
        book.applyDelta(5009_0000_0000L, 200_000_000L, true, 2L, System.nanoTime());
        book.applyDelta(5011_0000_0000L, 80_000_000L, false, 3L, System.nanoTime());
        book.applyDelta(5012_0000_0000L, 90_000_000L, false, 4L, System.nanoTime());

        assertEquals(2, book.getValidLevels());

        // Delete one bid level
        book.applyDelta(5009_0000_0000L, 0L, true, 5L, System.nanoTime());

        // bid depth = 1, ask depth = 2 → validLevels = 1
        assertEquals(1, book.getValidLevels());
    }
}
