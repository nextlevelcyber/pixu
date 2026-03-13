package com.bedrock.mm.oms.region;

import com.bedrock.mm.oms.model.Order;
import com.bedrock.mm.oms.store.OrderStore;
import com.bedrock.mm.oms.store.PriceRegion;

import java.util.ArrayList;
import java.util.List;

/**
 * RegionManager - diffs QuoteTarget against current OrderStore state.
 *
 * Core responsibility:
 * - Update all price region boundaries based on current fairMid
 * - Identify stale orders (price outside updated boundaries)
 * - Determine missing orders (region below minCount)
 * - Generate OrderActions (NewOrder/CancelOrder) to reconcile
 *
 * Design principles:
 * - Pure diff logic, no side effects
 * - Called after Pricing Engine publishes QuoteTarget
 * - Output OrderActions are passed to ExecGateway for execution
 * - Not on critical hot path (< 10μs acceptable)
 *
 * Usage:
 * 1. Pricing Engine computes QuoteTarget (fairMid, bidPrice, askPrice, bidSize, askSize)
 * 2. RegionManager.diff() compares target vs current OrderStore
 * 3. OMS executes returned OrderActions via ExecGateway
 */
public class RegionManager {
    private static final long SCALE = 100_000_000L; // 1e8 for fixed-point arithmetic
    private final RegionBoundaryCalculator boundaryCalc = new RegionBoundaryCalculator();

    /**
     * Diffs QuoteTarget against current OrderStore state.
     *
     * Algorithm:
     * 1. Update all region boundaries based on current fairMid
     * 2. Find stale orders (price outside new boundaries) → CancelOrder
     * 3. Check if target regions need more orders → NewOrder
     *
     * @param instrumentId instrument to trade
     * @param fairMid current fair mid price (fixed-point 1e-8)
     * @param bidPrice target bid quote price (fixed-point 1e-8)
     * @param askPrice target ask quote price (fixed-point 1e-8)
     * @param bidSize target bid quote size (fixed-point 1e-8)
     * @param askSize target ask quote size (fixed-point 1e-8)
     * @param regionIndex target region index (which region to quote in)
     * @param bidRegions bid price regions array
     * @param askRegions ask price regions array
     * @param store order store with current order state
     * @param tickSize minimum price increment for this instrument
     * @return list of OrderActions to execute
     */
    public List<OrderAction> diff(int instrumentId, long fairMid,
                                   long bidPrice, long askPrice,
                                   long bidSize, long askSize,
                                   int regionIndex,
                                   PriceRegion[] bidRegions,
                                   PriceRegion[] askRegions,
                                   OrderStore store,
                                   double tickSize) {
        List<OrderAction> actions = new ArrayList<>();

        // Temporary arrays for stale order detection
        long[] staleOrders = new long[32];
        long[] orderPrices = new long[32];

        // 1. Update all bid region boundaries and find stale orders
        for (PriceRegion region : bidRegions) {
            boundaryCalc.updateBoundaries(region, fairMid, tickSize);

            // Get order IDs and their prices from OrderStore
            long[] orderIds = new long[32];
            int orderCount = region.getOrderIds(orderIds);

            // Build price array for stale detection
            for (int i = 0; i < orderCount; i++) {
                Order order = store.getOrder(orderIds[i]);
                orderPrices[i] = (order != null) ? order.price : 0;
            }

            // Find orders with price outside [minPrice, maxPrice]
            int staleCount = region.getStaleOrders(staleOrders, orderPrices);
            for (int i = 0; i < staleCount; i++) {
                actions.add(new OrderAction.CancelOrder(staleOrders[i]));
            }
        }

        // 2. Update all ask region boundaries and find stale orders
        for (PriceRegion region : askRegions) {
            boundaryCalc.updateBoundaries(region, fairMid, tickSize);

            long[] orderIds = new long[32];
            int orderCount = region.getOrderIds(orderIds);

            for (int i = 0; i < orderCount; i++) {
                Order order = store.getOrder(orderIds[i]);
                orderPrices[i] = (order != null) ? order.price : 0;
            }

            int staleCount = region.getStaleOrders(staleOrders, orderPrices);
            for (int i = 0; i < staleCount; i++) {
                actions.add(new OrderAction.CancelOrder(staleOrders[i]));
            }
        }

        // 3. Check if target bid region needs new orders
        // Note: minCount is fixed at 1 in current design (one order per region)
        if (regionIndex >= 0 && regionIndex < bidRegions.length) {
            PriceRegion bidRegion = bidRegions[regionIndex];
            if (bidRegion.size() < 1) {
                // Need more bid orders - use target bidPrice from QuoteTarget
                actions.add(new OrderAction.NewOrder(
                    instrumentId,
                    bidPrice, // Use QuoteTarget price (already tick-aligned)
                    bidSize,
                    true, // isBid
                    regionIndex
                ));
            }
        }

        // 4. Check if target ask region needs new orders
        if (regionIndex >= 0 && regionIndex < askRegions.length) {
            PriceRegion askRegion = askRegions[regionIndex];
            if (askRegion.size() < 1) {
                // Need more ask orders - use target askPrice from QuoteTarget
                actions.add(new OrderAction.NewOrder(
                    instrumentId,
                    askPrice, // Use QuoteTarget price (already tick-aligned)
                    askSize,
                    false, // isBid (this is ask/sell)
                    regionIndex
                ));
            }
        }

        return actions;
    }

    /**
     * Simplified diff for single-sided quoting.
     *
     * Used when strategy only wants to quote bid OR ask, not both.
     *
     * @param instrumentId instrument to trade
     * @param fairMid current fair mid price (fixed-point 1e-8)
     * @param targetPrice target quote price (fixed-point 1e-8)
     * @param targetSize target quote size (fixed-point 1e-8)
     * @param isBid true for bid, false for ask
     * @param regionIndex target region index
     * @param regions price regions array (either bid or ask)
     * @param store order store
     * @param tickSize tick size
     * @return list of OrderActions
     */
    public List<OrderAction> diffSingleSide(int instrumentId, long fairMid,
                                             long targetPrice, long targetSize,
                                             boolean isBid, int regionIndex,
                                             PriceRegion[] regions,
                                             OrderStore store,
                                             double tickSize) {
        List<OrderAction> actions = new ArrayList<>();

        // Temporary arrays for stale order detection
        long[] staleOrders = new long[32];
        long[] orderPrices = new long[32];

        // Update all region boundaries
        for (PriceRegion region : regions) {
            boundaryCalc.updateBoundaries(region, fairMid, tickSize);

            // Get order IDs and their prices from OrderStore
            long[] orderIds = new long[32];
            int orderCount = region.getOrderIds(orderIds);

            // Build price array for stale detection
            for (int i = 0; i < orderCount; i++) {
                Order order = store.getOrder(orderIds[i]);
                orderPrices[i] = (order != null) ? order.price : 0;
            }

            int staleCount = region.getStaleOrders(staleOrders, orderPrices);
            for (int i = 0; i < staleCount; i++) {
                actions.add(new OrderAction.CancelOrder(staleOrders[i]));
            }
        }

        // Check target region
        if (regionIndex >= 0 && regionIndex < regions.length) {
            PriceRegion region = regions[regionIndex];
            if (region.size() < 1) {
                actions.add(new OrderAction.NewOrder(
                    instrumentId,
                    targetPrice,
                    targetSize,
                    isBid,
                    regionIndex
                ));
            }
        }

        return actions;
    }
}
