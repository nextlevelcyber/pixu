package com.bedrock.mm.md.book;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class L2OrderBookTest {
    private static final long EMPTY_PRICE = Long.MIN_VALUE;
    private L2OrderBook book;

    @BeforeEach
    public void setUp() {
        book = new L2OrderBook();
    }

    @Test
    public void testInitialState() {
        assertEquals(EMPTY_PRICE, book.getBestBid());
        assertEquals(EMPTY_PRICE, book.getBestAsk());
        assertEquals(0, book.getBidDepth());
        assertEquals(0, book.getAskDepth());
        assertEquals(0, book.getLastSeqId());
        assertEquals(0, book.getLastUpdateNanos());
    }

    @Test
    public void testApplySnapshot() {
        long[] bidPrices = {5010000000000L, 5009000000000L, 5008000000000L};
        long[] bidSizes = {100000000L, 200000000L, 300000000L};
        long[] askPrices = {5011000000000L, 5012000000000L, 5013000000000L};
        long[] askSizes = {150000000L, 250000000L, 350000000L};

        book.applySnapshot(bidPrices, bidSizes, askPrices, askSizes, 100L, 1000000000L);

        assertEquals(5010000000000L, book.getBestBid());
        assertEquals(5011000000000L, book.getBestAsk());
        assertEquals(3, book.getBidDepth());
        assertEquals(3, book.getAskDepth());
        assertEquals(100L, book.getLastSeqId());
        assertEquals(1000000000L, book.getLastUpdateNanos());

        // Verify bid levels (descending)
        assertEquals(5010000000000L, book.getBidPrice(0));
        assertEquals(100000000L, book.getBidSize(0));
        assertEquals(5009000000000L, book.getBidPrice(1));
        assertEquals(200000000L, book.getBidSize(1));
        assertEquals(5008000000000L, book.getBidPrice(2));
        assertEquals(300000000L, book.getBidSize(2));

        // Verify ask levels (ascending)
        assertEquals(5011000000000L, book.getAskPrice(0));
        assertEquals(150000000L, book.getAskSize(0));
        assertEquals(5012000000000L, book.getAskPrice(1));
        assertEquals(250000000L, book.getAskSize(1));
        assertEquals(5013000000000L, book.getAskPrice(2));
        assertEquals(350000000L, book.getAskSize(2));
    }

    @Test
    public void testBidDeltaInsertNewLevel() {
        // Start with one bid level
        book.applyDelta(5000000000000L, 100000000L, true, 1L, 1000000000L);

        assertEquals(5000000000000L, book.getBestBid());
        assertEquals(100000000L, book.getBidSize(0));
        assertEquals(1, book.getBidDepth());

        // Insert higher bid (should become level 0)
        book.applyDelta(5001000000000L, 200000000L, true, 2L, 2000000000L);

        assertEquals(5001000000000L, book.getBestBid());
        assertEquals(200000000L, book.getBidSize(0));
        assertEquals(5000000000000L, book.getBidPrice(1));
        assertEquals(100000000L, book.getBidSize(1));
        assertEquals(2, book.getBidDepth());

        // Insert lower bid (should become level 2)
        book.applyDelta(4999000000000L, 150000000L, true, 3L, 3000000000L);

        assertEquals(3, book.getBidDepth());
        assertEquals(5001000000000L, book.getBidPrice(0));
        assertEquals(5000000000000L, book.getBidPrice(1));
        assertEquals(4999000000000L, book.getBidPrice(2));
    }

    @Test
    public void testBidDeltaUpdateExistingLevel() {
        book.applyDelta(5000000000000L, 100000000L, true, 1L, 1000000000L);
        assertEquals(100000000L, book.getBidSize(0));

        // Update size
        book.applyDelta(5000000000000L, 250000000L, true, 2L, 2000000000L);
        assertEquals(250000000L, book.getBidSize(0));
        assertEquals(1, book.getBidDepth());
    }

    @Test
    public void testBidDeltaDeleteLevel() {
        // Setup book with 3 levels
        book.applyDelta(5002000000000L, 100000000L, true, 1L, 1000000000L);
        book.applyDelta(5001000000000L, 200000000L, true, 2L, 2000000000L);
        book.applyDelta(5000000000000L, 300000000L, true, 3L, 3000000000L);
        assertEquals(3, book.getBidDepth());

        // Delete middle level
        book.applyDelta(5001000000000L, 0L, true, 4L, 4000000000L);

        assertEquals(2, book.getBidDepth());
        assertEquals(5002000000000L, book.getBidPrice(0));
        assertEquals(100000000L, book.getBidSize(0));
        assertEquals(5000000000000L, book.getBidPrice(1));
        assertEquals(300000000L, book.getBidSize(1));
        assertEquals(EMPTY_PRICE, book.getBidPrice(2));
    }

    @Test
    public void testAskDeltaInsertNewLevel() {
        // Start with one ask level
        book.applyDelta(5010000000000L, 100000000L, false, 1L, 1000000000L);

        assertEquals(5010000000000L, book.getBestAsk());
        assertEquals(100000000L, book.getAskSize(0));
        assertEquals(1, book.getAskDepth());

        // Insert lower ask (should become level 0)
        book.applyDelta(5009000000000L, 200000000L, false, 2L, 2000000000L);

        assertEquals(5009000000000L, book.getBestAsk());
        assertEquals(200000000L, book.getAskSize(0));
        assertEquals(5010000000000L, book.getAskPrice(1));
        assertEquals(100000000L, book.getAskSize(1));
        assertEquals(2, book.getAskDepth());

        // Insert higher ask (should become level 2)
        book.applyDelta(5011000000000L, 150000000L, false, 3L, 3000000000L);

        assertEquals(3, book.getAskDepth());
        assertEquals(5009000000000L, book.getAskPrice(0));
        assertEquals(5010000000000L, book.getAskPrice(1));
        assertEquals(5011000000000L, book.getAskPrice(2));
    }

    @Test
    public void testAskDeltaUpdateExistingLevel() {
        book.applyDelta(5010000000000L, 100000000L, false, 1L, 1000000000L);
        assertEquals(100000000L, book.getAskSize(0));

        // Update size
        book.applyDelta(5010000000000L, 250000000L, false, 2L, 2000000000L);
        assertEquals(250000000L, book.getAskSize(0));
        assertEquals(1, book.getAskDepth());
    }

    @Test
    public void testAskDeltaDeleteLevel() {
        // Setup book with 3 levels
        book.applyDelta(5010000000000L, 100000000L, false, 1L, 1000000000L);
        book.applyDelta(5011000000000L, 200000000L, false, 2L, 2000000000L);
        book.applyDelta(5012000000000L, 300000000L, false, 3L, 3000000000L);
        assertEquals(3, book.getAskDepth());

        // Delete middle level
        book.applyDelta(5011000000000L, 0L, false, 4L, 4000000000L);

        assertEquals(2, book.getAskDepth());
        assertEquals(5010000000000L, book.getAskPrice(0));
        assertEquals(100000000L, book.getAskSize(0));
        assertEquals(5012000000000L, book.getAskPrice(1));
        assertEquals(300000000L, book.getAskSize(1));
        assertEquals(EMPTY_PRICE, book.getAskPrice(2));
    }

    @Test
    public void testMidPrice() {
        book.applyDelta(5000000000000L, 100000000L, true, 1L, 1000000000L);
        book.applyDelta(5010000000000L, 100000000L, false, 2L, 2000000000L);

        long expectedMid = (5000000000000L + 5010000000000L) / 2;
        assertEquals(expectedMid, book.getMidPrice());
    }

    @Test
    public void testMidPriceWithEmptyBook() {
        assertEquals(EMPTY_PRICE, book.getMidPrice());

        book.applyDelta(5000000000000L, 100000000L, true, 1L, 1000000000L);
        assertEquals(EMPTY_PRICE, book.getMidPrice()); // Still empty on ask side
    }

    @Test
    public void testClear() {
        book.applyDelta(5000000000000L, 100000000L, true, 1L, 1000000000L);
        book.applyDelta(5010000000000L, 100000000L, false, 2L, 2000000000L);

        book.clear();

        assertEquals(EMPTY_PRICE, book.getBestBid());
        assertEquals(EMPTY_PRICE, book.getBestAsk());
        assertEquals(0, book.getBidDepth());
        assertEquals(0, book.getAskDepth());
        assertEquals(0, book.getLastSeqId());
        assertEquals(0, book.getLastUpdateNanos());
    }

    @Test
    public void testMaxDepthLimit() {
        // Insert 25 bid levels (only 20 should be kept)
        for (int i = 0; i < 25; i++) {
            long price = 5000000000000L - (i * 1000000000L);
            book.applyDelta(price, 100000000L, true, i + 1L, (i + 1) * 1000000000L);
        }

        assertEquals(20, book.getBidDepth());
        assertEquals(5000000000000L, book.getBestBid());
        assertEquals(4981000000000L, book.getBidPrice(19)); // 20th level
        assertEquals(EMPTY_PRICE, book.getBidPrice(20)); // Beyond max depth
    }

    @Test
    public void testBidSortingOrder() {
        // Insert in random order
        book.applyDelta(5000000000000L, 100000000L, true, 1L, 1000000000L);
        book.applyDelta(5002000000000L, 200000000L, true, 2L, 2000000000L);
        book.applyDelta(5001000000000L, 150000000L, true, 3L, 3000000000L);
        book.applyDelta(5004000000000L, 250000000L, true, 4L, 4000000000L);
        book.applyDelta(4999000000000L, 50000000L, true, 5L, 5000000000L);

        // Verify descending order
        assertEquals(5004000000000L, book.getBidPrice(0));
        assertEquals(5002000000000L, book.getBidPrice(1));
        assertEquals(5001000000000L, book.getBidPrice(2));
        assertEquals(5000000000000L, book.getBidPrice(3));
        assertEquals(4999000000000L, book.getBidPrice(4));
    }

    @Test
    public void testAskSortingOrder() {
        // Insert in random order
        book.applyDelta(5010000000000L, 100000000L, false, 1L, 1000000000L);
        book.applyDelta(5008000000000L, 200000000L, false, 2L, 2000000000L);
        book.applyDelta(5009000000000L, 150000000L, false, 3L, 3000000000L);
        book.applyDelta(5007000000000L, 250000000L, false, 4L, 4000000000L);
        book.applyDelta(5011000000000L, 50000000L, false, 5L, 5000000000L);

        // Verify ascending order
        assertEquals(5007000000000L, book.getAskPrice(0));
        assertEquals(5008000000000L, book.getAskPrice(1));
        assertEquals(5009000000000L, book.getAskPrice(2));
        assertEquals(5010000000000L, book.getAskPrice(3));
        assertEquals(5011000000000L, book.getAskPrice(4));
    }

    @Test
    public void testSequenceIdTracking() {
        book.applyDelta(5000000000000L, 100000000L, true, 1L, 1000000000L);
        assertEquals(1L, book.getLastSeqId());

        book.applyDelta(5001000000000L, 200000000L, true, 2L, 2000000000L);
        assertEquals(2L, book.getLastSeqId());

        book.applySnapshot(new long[]{5000000000000L}, new long[]{100000000L},
                new long[]{5010000000000L}, new long[]{100000000L},
                100L, 3000000000L);
        assertEquals(100L, book.getLastSeqId());
        assertEquals(3000000000L, book.getLastUpdateNanos());
    }

    @Test
    public void testSnapshotReplacesExistingData() {
        // Setup initial state
        book.applyDelta(5000000000000L, 100000000L, true, 1L, 1000000000L);
        book.applyDelta(5001000000000L, 200000000L, true, 2L, 2000000000L);
        book.applyDelta(5010000000000L, 150000000L, false, 3L, 3000000000L);
        assertEquals(2, book.getBidDepth());
        assertEquals(1, book.getAskDepth());

        // Apply snapshot with different levels
        long[] bidPrices = {5100000000000L};
        long[] bidSizes = {500000000L};
        long[] askPrices = {5110000000000L, 5120000000000L};
        long[] askSizes = {600000000L, 700000000L};

        book.applySnapshot(bidPrices, bidSizes, askPrices, askSizes, 10L, 10000000000L);

        // Verify old data is gone
        assertEquals(1, book.getBidDepth());
        assertEquals(2, book.getAskDepth());
        assertEquals(5100000000000L, book.getBestBid());
        assertEquals(5110000000000L, book.getBestAsk());
        assertEquals(EMPTY_PRICE, book.getBidPrice(1));
    }

    @Test
    public void testRebuild() {
        // Setup initial state with some data
        book.applyDelta(5000000000000L, 100000000L, true, 1L, 1000000000L);
        book.applyDelta(5010000000000L, 150000000L, false, 2L, 2000000000L);
        assertEquals(1, book.getBidDepth());
        assertEquals(1, book.getAskDepth());

        // Rebuild with partial snapshot (levels parameter limits data)
        long[] bidPrices = {5200000000000L, 5199000000000L, 5198000000000L};
        long[] bidSizes = {100000000L, 200000000L, 300000000L};
        long[] askPrices = {5201000000000L, 5202000000000L, 5203000000000L};
        long[] askSizes = {150000000L, 250000000L, 350000000L};

        book.rebuild(bidPrices, bidSizes, askPrices, askSizes, 2, 100L);

        // Verify only first 2 levels are loaded
        assertEquals(2, book.getBidDepth());
        assertEquals(2, book.getAskDepth());
        assertEquals(5200000000000L, book.getBestBid());
        assertEquals(5201000000000L, book.getBestAsk());
        assertEquals(5199000000000L, book.getBidPrice(1));
        assertEquals(5202000000000L, book.getAskPrice(1));
        assertEquals(EMPTY_PRICE, book.getBidPrice(2)); // 3rd level not loaded
        assertEquals(EMPTY_PRICE, book.getAskPrice(2));
        assertEquals(100L, book.getLastSeqId());
    }

    @Test
    public void testRebuildWithMaxDepth() {
        // Rebuild with more levels than MAX_DEPTH
        long[] bidPrices = new long[25];
        long[] bidSizes = new long[25];
        long[] askPrices = new long[25];
        long[] askSizes = new long[25];

        for (int i = 0; i < 25; i++) {
            bidPrices[i] = 5000000000000L - (i * 1000000000L);
            bidSizes[i] = 100000000L * (i + 1);
            askPrices[i] = 5100000000000L + (i * 1000000000L);
            askSizes[i] = 150000000L * (i + 1);
        }

        book.rebuild(bidPrices, bidSizes, askPrices, askSizes, 25, 200L);

        // Verify only MAX_DEPTH (20) levels are kept
        assertEquals(20, book.getBidDepth());
        assertEquals(20, book.getAskDepth());
        assertEquals(5000000000000L, book.getBestBid());
        assertEquals(5100000000000L, book.getBestAsk());
        assertEquals(4981000000000L, book.getBidPrice(19));
        assertEquals(5119000000000L, book.getAskPrice(19));
        assertEquals(EMPTY_PRICE, book.getBidPrice(20));
        assertEquals(EMPTY_PRICE, book.getAskPrice(20));
        assertEquals(200L, book.getLastSeqId());
    }
}
