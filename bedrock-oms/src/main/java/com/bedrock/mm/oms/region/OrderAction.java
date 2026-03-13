package com.bedrock.mm.oms.region;

/**
 * Order action for RegionManager diff output.
 *
 * Sealed interface with two permitted subtypes:
 * - NewOrder: place a new limit order at specified price/size
 * - CancelOrder: cancel an existing order by orderId
 *
 * Used by RegionManager.diff() to compute the difference between
 * current OrderStore state and target QuoteTarget, generating
 * minimal actions to reconcile the two.
 */
public sealed interface OrderAction permits OrderAction.NewOrder, OrderAction.CancelOrder {

    /**
     * Action to place a new limit order.
     *
     * @param instrumentId instrument to trade
     * @param price order price (fixed-point 1e-8)
     * @param size order size (fixed-point 1e-8)
     * @param isBid true for bid (buy), false for ask (sell)
     * @param regionIndex price region index (for tracking which region this order belongs to)
     */
    record NewOrder(
        int instrumentId,
        long price,
        long size,
        boolean isBid,
        int regionIndex
    ) implements OrderAction {}

    /**
     * Action to cancel an existing order.
     *
     * @param orderId internal order ID to cancel
     */
    record CancelOrder(
        long orderId
    ) implements OrderAction {}
}
