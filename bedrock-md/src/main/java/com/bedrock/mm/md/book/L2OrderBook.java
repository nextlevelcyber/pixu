package com.bedrock.mm.md.book;

/**
 * Zero-allocation L2 order book implementation using primitive long arrays.
 *
 * Maintains sorted price levels with depth=20:
 * - Bid side: descending price order (highest bid first)
 * - Ask side: ascending price order (lowest ask first)
 *
 * All prices and sizes are fixed-point long values (scale 1e-8).
 * No heap allocation on hot path - uses primitive arrays only.
 */
public final class L2OrderBook {
    private static final int MAX_DEPTH = 20;
    private static final long EMPTY_PRICE = Long.MIN_VALUE;

    // Bid side: descending order (bidPrices[0] is highest bid)
    private final long[] bidPrices;
    private final long[] bidSizes;

    // Ask side: ascending order (askPrices[0] is lowest ask)
    private final long[] askPrices;
    private final long[] askSizes;

    private long lastSeqId;
    private long lastUpdateNanos;

    public L2OrderBook() {
        this.bidPrices = new long[MAX_DEPTH];
        this.bidSizes = new long[MAX_DEPTH];
        this.askPrices = new long[MAX_DEPTH];
        this.askSizes = new long[MAX_DEPTH];
        clear();
    }

    /**
     * Clears the book to empty state.
     */
    public void clear() {
        for (int i = 0; i < MAX_DEPTH; i++) {
            bidPrices[i] = EMPTY_PRICE;
            bidSizes[i] = 0;
            askPrices[i] = EMPTY_PRICE;
            askSizes[i] = 0;
        }
        lastSeqId = 0;
        lastUpdateNanos = 0;
    }

    /**
     * Applies a snapshot to the book, replacing all levels.
     *
     * @param bidP bid prices (descending order), length <= MAX_DEPTH
     * @param bidS bid sizes, same length as bidP
     * @param askP ask prices (ascending order), length <= MAX_DEPTH
     * @param askS ask sizes, same length as askP
     * @param seqId sequence identifier
     * @param updateNanos update timestamp in nanoseconds
     */
    public void applySnapshot(long[] bidP, long[] bidS, long[] askP, long[] askS,
                              long seqId, long updateNanos) {
        clear();

        int bidLen = Math.min(bidP.length, MAX_DEPTH);
        for (int i = 0; i < bidLen; i++) {
            bidPrices[i] = bidP[i];
            bidSizes[i] = bidS[i];
        }

        int askLen = Math.min(askP.length, MAX_DEPTH);
        for (int i = 0; i < askLen; i++) {
            askPrices[i] = askP[i];
            askSizes[i] = askS[i];
        }

        this.lastSeqId = seqId;
        this.lastUpdateNanos = updateNanos;
    }

    /**
     * Rebuilds the book from partial snapshot data.
     * Used when sequence ID gap is detected and requires re-synchronization.
     *
     * @param bidP bid prices (descending order)
     * @param bidS bid sizes, same length as bidP
     * @param askP ask prices (ascending order)
     * @param askS ask sizes, same length as askP
     * @param levels number of valid levels in the arrays
     * @param snapshotSeqId sequence identifier of the snapshot
     */
    public void rebuild(long[] bidP, long[] bidS, long[] askP, long[] askS,
                        int levels, long snapshotSeqId) {
        clear();

        int bidLen = Math.min(Math.min(bidP.length, levels), MAX_DEPTH);
        for (int i = 0; i < bidLen; i++) {
            bidPrices[i] = bidP[i];
            bidSizes[i] = bidS[i];
        }

        int askLen = Math.min(Math.min(askP.length, levels), MAX_DEPTH);
        for (int i = 0; i < askLen; i++) {
            askPrices[i] = askP[i];
            askSizes[i] = askS[i];
        }

        this.lastSeqId = snapshotSeqId;
        this.lastUpdateNanos = System.nanoTime();
    }

    /**
     * Applies an incremental delta update to the book.
     * Maintains sorted order: bid descending, ask ascending.
     *
     * @param price price level (fixed-point 1e-8)
     * @param size new size at this level (0 = delete)
     * @param isBid true for bid side, false for ask side
     * @param seqId sequence identifier
     * @param updateNanos update timestamp in nanoseconds
     */
    public void applyDelta(long price, long size, boolean isBid, long seqId, long updateNanos) {
        if (isBid) {
            applyBidDelta(price, size);
        } else {
            applyAskDelta(price, size);
        }
        this.lastSeqId = seqId;
        this.lastUpdateNanos = updateNanos;
    }

    private void applyBidDelta(long price, long size) {
        // Find existing level or insertion point
        int idx = findBidIndex(price);

        if (idx >= 0 && bidPrices[idx] == price) {
            // Update existing level
            if (size == 0) {
                // Delete: shift all levels up
                for (int i = idx; i < MAX_DEPTH - 1; i++) {
                    bidPrices[i] = bidPrices[i + 1];
                    bidSizes[i] = bidSizes[i + 1];
                }
                bidPrices[MAX_DEPTH - 1] = EMPTY_PRICE;
                bidSizes[MAX_DEPTH - 1] = 0;
            } else {
                // Update size
                bidSizes[idx] = size;
            }
        } else if (size > 0) {
            // Insert new level (idx is insertion point)
            int insertIdx = idx >= 0 ? idx : -(idx + 1);
            if (insertIdx < MAX_DEPTH) {
                // Shift levels down to make room
                for (int i = MAX_DEPTH - 1; i > insertIdx; i--) {
                    bidPrices[i] = bidPrices[i - 1];
                    bidSizes[i] = bidSizes[i - 1];
                }
                bidPrices[insertIdx] = price;
                bidSizes[insertIdx] = size;
            }
        }
    }

    private void applyAskDelta(long price, long size) {
        // Find existing level or insertion point
        int idx = findAskIndex(price);

        if (idx >= 0 && askPrices[idx] == price) {
            // Update existing level
            if (size == 0) {
                // Delete: shift all levels up
                for (int i = idx; i < MAX_DEPTH - 1; i++) {
                    askPrices[i] = askPrices[i + 1];
                    askSizes[i] = askSizes[i + 1];
                }
                askPrices[MAX_DEPTH - 1] = EMPTY_PRICE;
                askSizes[MAX_DEPTH - 1] = 0;
            } else {
                // Update size
                askSizes[idx] = size;
            }
        } else if (size > 0) {
            // Insert new level (idx is insertion point)
            int insertIdx = idx >= 0 ? idx : -(idx + 1);
            if (insertIdx < MAX_DEPTH) {
                // Shift levels down to make room
                for (int i = MAX_DEPTH - 1; i > insertIdx; i--) {
                    askPrices[i] = askPrices[i - 1];
                    askSizes[i] = askSizes[i - 1];
                }
                askPrices[insertIdx] = price;
                askSizes[insertIdx] = size;
            }
        }
    }

    /**
     * Binary search for bid price (descending order).
     * Returns index if found, or -(insertion_point + 1) if not found.
     */
    private int findBidIndex(long price) {
        int left = 0;
        int right = MAX_DEPTH - 1;

        // Find first non-empty level
        while (right >= 0 && bidPrices[right] == EMPTY_PRICE) {
            right--;
        }

        while (left <= right) {
            int mid = (left + right) >>> 1;
            long midPrice = bidPrices[mid];

            if (midPrice == price) {
                return mid;
            } else if (midPrice > price) {
                // Descending order: higher price comes first
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        return -(left + 1);
    }

    /**
     * Binary search for ask price (ascending order).
     * Returns index if found, or -(insertion_point + 1) if not found.
     */
    private int findAskIndex(long price) {
        int left = 0;
        int right = MAX_DEPTH - 1;

        // Find first non-empty level
        while (right >= 0 && askPrices[right] == EMPTY_PRICE) {
            right--;
        }

        while (left <= right) {
            int mid = (left + right) >>> 1;
            long midPrice = askPrices[mid];

            if (midPrice == price) {
                return mid;
            } else if (midPrice < price) {
                // Ascending order: lower price comes first
                left = mid + 1;
            } else {
                right = mid - 1;
            }
        }
        return -(left + 1);
    }

    // Getters

    public long getBidPrice(int level) {
        return level < MAX_DEPTH ? bidPrices[level] : EMPTY_PRICE;
    }

    public long getBidSize(int level) {
        return level < MAX_DEPTH ? bidSizes[level] : 0;
    }

    public long getAskPrice(int level) {
        return level < MAX_DEPTH ? askPrices[level] : EMPTY_PRICE;
    }

    public long getAskSize(int level) {
        return level < MAX_DEPTH ? askSizes[level] : 0;
    }

    public long getLastSeqId() {
        return lastSeqId;
    }

    public long getLastUpdateNanos() {
        return lastUpdateNanos;
    }

    public int getMaxDepth() {
        return MAX_DEPTH;
    }

    /**
     * Returns the number of valid bid levels (non-empty).
     */
    public int getBidDepth() {
        for (int i = 0; i < MAX_DEPTH; i++) {
            if (bidPrices[i] == EMPTY_PRICE) {
                return i;
            }
        }
        return MAX_DEPTH;
    }

    /**
     * Returns the number of valid ask levels (non-empty).
     */
    public int getAskDepth() {
        for (int i = 0; i < MAX_DEPTH; i++) {
            if (askPrices[i] == EMPTY_PRICE) {
                return i;
            }
        }
        return MAX_DEPTH;
    }

    /**
     * Returns the number of levels where both bid and ask are non-empty.
     */
    public int getValidLevels() {
        return Math.min(getBidDepth(), getAskDepth());
    }

    /**
     * Returns best bid price (level 0), or EMPTY_PRICE if no bids.
     */
    public long getBestBid() {
        return bidPrices[0];
    }

    /**
     * Returns best ask price (level 0), or EMPTY_PRICE if no asks.
     */
    public long getBestAsk() {
        return askPrices[0];
    }

    /**
     * Returns mid price as average of best bid and best ask.
     * Returns EMPTY_PRICE if either side is empty.
     */
    public long getMidPrice() {
        long bestBid = getBestBid();
        long bestAsk = getBestAsk();
        if (bestBid == EMPTY_PRICE || bestAsk == EMPTY_PRICE) {
            return EMPTY_PRICE;
        }
        return (bestBid + bestAsk) / 2;
    }
}
