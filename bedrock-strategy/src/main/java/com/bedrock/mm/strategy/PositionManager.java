package com.bedrock.mm.strategy;

/**
 * Position management interface
 */
public interface PositionManager {
    
    /**
     * Get current position for a symbol
     * @param symbol trading symbol
     * @return current position (positive for long, negative for short)
     */
    double getPosition(String symbol);
    
    /**
     * Get available balance for an asset
     * @param asset asset name (e.g., "USDT", "BTC")
     * @return available balance
     */
    double getAvailableBalance(String asset);
    
    /**
     * Get unrealized PnL for a symbol
     * @param symbol trading symbol
     * @return unrealized profit/loss
     */
    double getUnrealizedPnl(String symbol);
}