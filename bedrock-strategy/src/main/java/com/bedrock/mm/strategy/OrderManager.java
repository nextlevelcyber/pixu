package com.bedrock.mm.strategy;

/**
 * Order management interface
 */
public interface OrderManager {
    
    /**
     * Submit a new order
     * @param symbol trading symbol
     * @param side order side (BUY/SELL)
     * @param price order price
     * @param quantity order quantity
     * @return order ID
     */
    String submitOrder(String symbol, String side, double price, double quantity);
    
    /**
     * Cancel an existing order
     * @param orderId order ID to cancel
     * @return true if cancellation was successful
     */
    boolean cancelOrder(String orderId);
    
    /**
     * Check if an order is still active
     * @param orderId order ID to check
     * @return true if order is active
     */
    boolean isOrderActive(String orderId);
}