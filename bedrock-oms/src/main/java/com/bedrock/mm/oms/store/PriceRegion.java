package com.bedrock.mm.oms.store;

/**
 * PriceRegion - Manages orders within a specific price band relative to fair mid.
 *
 * Zero-allocation hot path design:
 * - Fixed-size array for order IDs
 * - Uses -1 as empty slot marker
 * - minRatio/maxRatio use double for boundary calculation (acceptable per architecture doc)
 */
public class PriceRegion {
    private static final int CAPACITY = 32;
    private static final long EMPTY_SLOT = -1;

    public final int regionIndex;
    public final boolean isBid;
    public double minRatio;
    public double maxRatio;

    private final long[] orderIds;
    private long minPrice;
    private long maxPrice;

    public PriceRegion(int regionIndex, boolean isBid, double minRatio, double maxRatio) {
        this.regionIndex = regionIndex;
        this.isBid = isBid;
        this.minRatio = minRatio;
        this.maxRatio = maxRatio;
        this.orderIds = new long[CAPACITY];

        // Initialize all slots as empty
        for (int i = 0; i < CAPACITY; i++) {
            orderIds[i] = EMPTY_SLOT;
        }
    }

    /**
     * Update price boundaries based on new fair mid.
     *
     * @param fairMidBits fair mid as raw long bits from Double.doubleToRawLongBits
     */
    public void updateBoundaries(long fairMidBits) {
        double fairMid = Double.longBitsToDouble(fairMidBits);
        this.minPrice = (long) (fairMid * minRatio);
        this.maxPrice = (long) (fairMid * maxRatio);
    }

    /**
     * Add an order to this region.
     *
     * @param orderId order ID to add
     * @return true if added successfully, false if region is full
     */
    public boolean addOrder(long orderId) {
        for (int i = 0; i < CAPACITY; i++) {
            if (orderIds[i] == EMPTY_SLOT) {
                orderIds[i] = orderId;
                return true;
            }
        }
        return false;
    }

    /**
     * Remove an order from this region.
     *
     * @param orderId order ID to remove
     * @return true if found and removed, false if not found
     */
    public boolean removeOrder(long orderId) {
        for (int i = 0; i < CAPACITY; i++) {
            if (orderIds[i] == orderId) {
                orderIds[i] = EMPTY_SLOT;
                return true;
            }
        }
        return false;
    }

    /**
     * Get all order IDs in this region.
     *
     * @param result output array to fill with order IDs
     * @return number of orders copied
     */
    public int getOrderIds(long[] result) {
        int count = 0;
        for (int i = 0; i < CAPACITY && count < result.length; i++) {
            if (orderIds[i] != EMPTY_SLOT) {
                result[count++] = orderIds[i];
            }
        }
        return count;
    }

    /**
     * Find orders that are outside current price boundaries (stale).
     *
     * @param result output array to fill with stale order IDs
     * @param orderPrices array mapping order IDs to prices (must be indexed by slot position)
     * @return number of stale orders found
     */
    public int getStaleOrders(long[] result, long[] orderPrices) {
        int count = 0;
        for (int i = 0; i < CAPACITY && count < result.length; i++) {
            long orderId = orderIds[i];
            if (orderId != EMPTY_SLOT) {
                long price = orderPrices[i];
                if (price < minPrice || price > maxPrice) {
                    result[count++] = orderId;
                }
            }
        }
        return count;
    }

    /**
     * Get current order count.
     *
     * @return number of active orders in this region
     */
    public int size() {
        int count = 0;
        for (int i = 0; i < CAPACITY; i++) {
            if (orderIds[i] != EMPTY_SLOT) {
                count++;
            }
        }
        return count;
    }

    /**
     * Check if region is empty.
     */
    public boolean isEmpty() {
        for (int i = 0; i < CAPACITY; i++) {
            if (orderIds[i] != EMPTY_SLOT) {
                return false;
            }
        }
        return true;
    }

    /**
     * Get min price boundary (fixed-point 1e-8).
     */
    public long getMinPrice() {
        return minPrice;
    }

    /**
     * Get max price boundary (fixed-point 1e-8).
     */
    public long getMaxPrice() {
        return maxPrice;
    }

    /**
     * Set min price boundary (for external boundary calculator).
     *
     * @param minPrice minimum price (fixed-point 1e-8)
     */
    public void setMinPrice(long minPrice) {
        this.minPrice = minPrice;
    }

    /**
     * Set max price boundary (for external boundary calculator).
     *
     * @param maxPrice maximum price (fixed-point 1e-8)
     */
    public void setMaxPrice(long maxPrice) {
        this.maxPrice = maxPrice;
    }
}
