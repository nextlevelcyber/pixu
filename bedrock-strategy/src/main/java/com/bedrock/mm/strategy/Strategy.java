package com.bedrock.mm.strategy;

import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.md.BookDelta;
import com.bedrock.mm.md.MarketTick;

/**
 * Base interface for trading strategies
 */
public interface Strategy {
    
    /**
     * Get strategy name
     */
    String getName();
    
    /**
     * Get strategy version
     */
    String getVersion();
    
    /**
     * Initialize the strategy
     */
    void initialize(StrategyContext context);
    
    /**
     * Start the strategy
     */
    void start();
    
    /**
     * Stop the strategy
     */
    void stop();
    
    /**
     * Check if strategy is running
     */
    boolean isRunning();
    
    /**
     * Handle market tick data
     */
    void onMarketTick(MarketTick tick);
    
    /**
     * Handle order book delta
     */
    void onBookDelta(BookDelta delta);
    
    /**
     * Handle order acknowledgment
     */
    void onOrderAck(OrderAck ack);
    
    /**
     * Handle trade fill
     */
    void onFill(Fill fill);
    
    /**
     * Get symbols this strategy is interested in
     */
    Symbol[] getSymbols();
    
    /**
     * Get strategy parameters
     */
    StrategyParameters getParameters();
    
    /**
     * Update strategy parameters
     */
    void updateParameters(StrategyParameters parameters);
    
    /**
     * Get strategy statistics
     */
    StrategyStats getStats();
    
    /**
     * Reset strategy state
     */
    void reset();
}