package com.bedrock.mm.strategy;

import com.bedrock.mm.md.MarketDataService;

/**
 * Strategy execution context
 */
public interface StrategyContext {
    
    /**
     * Get market data service
     */
    MarketDataService getMarketDataService();
    
    /**
     * Get monitoring service (may be null; type-agnostic)
     */
    Object getMonitorService();
    
    /**
     * Get order manager
     */
    OrderManager getOrderManager();
    
    /**
     * Get position manager
     */
    PositionManager getPositionManager();
    
    /**
     * Get risk manager
     */
    RiskManager getRiskManager();
    
    /**
     * Get strategy configuration
     */
    StrategyConfig getConfig();
    
    /**
     * Log a message
     */
    void log(String level, String message, Object... args);
    
    /**
     * Log info message
     */
    default void logInfo(String message, Object... args) {
        log("INFO", message, args);
    }
    
    /**
     * Log warning message
     */
    default void logWarn(String message, Object... args) {
        log("WARN", message, args);
    }
    
    /**
     * Log error message
     */
    default void logError(String message, Object... args) {
        log("ERROR", message, args);
    }
}