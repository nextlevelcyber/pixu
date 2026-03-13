package com.bedrock.mm.oms.exec;

/**
 * ExecGateway - Execution gateway interface for order placement and cancellation.
 *
 * Implementations handle exchange connectivity (REST + private WebSocket),
 * order lifecycle management, and execution event generation.
 */
public interface ExecGateway {
    /**
     * Place a new limit order.
     *
     * @param instrumentId instrument identifier
     * @param price order price (fixed-point 1e-8)
     * @param size order size (fixed-point 1e-8)
     * @param isBid true for buy, false for sell
     * @param regionIndex price region index (for tracking)
     */
    void placeOrder(int instrumentId, long price, long size, boolean isBid, int regionIndex);

    /**
     * Place a new limit order with quote context metadata.
     * <p>
     * Default implementation keeps backward compatibility for gateways that
     * do not consume quote metadata yet.
     *
     * @param instrumentId instrument identifier
     * @param price order price (fixed-point 1e-8)
     * @param size order size (fixed-point 1e-8)
     * @param isBid true for buy, false for sell
     * @param regionIndex price region index (for tracking)
     * @param quotePublishNanos quote publication timestamp from Pricing
     * @param quoteSeqId quote sequence identifier from Pricing
     */
    default void placeOrder(int instrumentId, long price, long size, boolean isBid, int regionIndex,
                            long quotePublishNanos, long quoteSeqId) {
        placeOrder(instrumentId, price, size, isBid, regionIndex);
    }

    /**
     * Cancel an existing order.
     *
     * @param instrumentId instrument identifier
     * @param orderId internal order ID
     */
    void cancelOrder(int instrumentId, long orderId);

    /**
     * Handle private WebSocket reconnection.
     * Triggers REST reconciliation to detect missed execution events.
     *
     * @return true if reconciliation succeeded, false otherwise
     */
    boolean onPrivateWsReconnect();

    /**
     * Shutdown the gateway gracefully.
     */
    void shutdown();
}
