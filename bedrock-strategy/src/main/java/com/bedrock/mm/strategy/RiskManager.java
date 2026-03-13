package com.bedrock.mm.strategy;

/**
 * Risk management interface
 */
public interface RiskManager {
    
    /**
     * Check if an order passes risk checks
     * @param symbol trading symbol
     * @param side order side (BUY/SELL)
     * @param price order price
     * @param quantity order quantity
     * @return true if order passes risk checks
     */
    boolean checkRisk(String symbol, String side, double price, double quantity);
    
    /**
     * Get maximum allowed order size for a symbol
     * @param symbol trading symbol
     * @return maximum order size
     */
    double getMaxOrderSize(String symbol);
    
    /**
     * Check if a position is within risk limits
     * @param symbol trading symbol
     * @param position current position
     * @return true if position is within limits
     */
    boolean isWithinRiskLimits(String symbol, double position);
}