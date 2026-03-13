package com.bedrock.mm.md;

import com.bedrock.mm.common.model.Symbol;

/**
 * Market data service interface
 */
public interface MarketDataService {
    
    /**
     * Subscribe to market tick data for a symbol
     */
    void subscribeMarketTick(Symbol symbol, MarketTickHandler handler);
    
    /**
     * Subscribe to order book updates for a symbol
     */
    void subscribeBookDelta(Symbol symbol, BookDeltaHandler handler);
    
    /**
     * Unsubscribe from market tick data
     */
    void unsubscribeMarketTick(Symbol symbol);
    
    /**
     * Unsubscribe from order book updates
     */
    void unsubscribeBookDelta(Symbol symbol);
    
    /**
     * Initialize the market data service
     */
    void initialize();
    
    /**
     * Start the market data service
     */
    void start();
    
    /**
     * Stop the market data service
     */
    void stop();
    
    /**
     * Check if the service is running
     */
    boolean isRunning();

    /**
     * Consume MARKET_TICK from unified event bus.
     * Default no-op to keep backward compatibility for alternate implementations.
     */
    default void handleMarketTick(MarketTick tick) {
        // no-op
    }

    /**
     * Consume BOOK_DELTA from unified event bus.
     * Default no-op to keep backward compatibility for alternate implementations.
     */
    default void handleBookDelta(BookDelta delta) {
        // no-op
    }

    /**
     * Publish a BBO event derived from L2 order book top-of-book.
     * Called by feed implementations after applying deltas.
     * Default no-op to keep backward compatibility.
     */
    default void publishBbo(String symbol, long bidPrice, long bidSize, long askPrice, long askSize,
                            long recvNanos, long sequenceNumber) {
        // no-op
    }
    
    /**
     * Handler for market tick data
     */
    @FunctionalInterface
    interface MarketTickHandler {
        void onMarketTick(MarketTick tick);
    }
    
    /**
     * Handler for order book delta updates
     */
    @FunctionalInterface
    interface BookDeltaHandler {
        void onBookDelta(BookDelta delta);
    }
}
