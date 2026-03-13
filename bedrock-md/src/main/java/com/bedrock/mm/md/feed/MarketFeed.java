package com.bedrock.mm.md.feed;

import com.bedrock.mm.md.BookDelta;
import com.bedrock.mm.md.MarketTick;
import com.bedrock.mm.common.model.Symbol;

/**
 * Generic market feed interface for external exchange integrations.
 */
public interface MarketFeed extends AutoCloseable {

    /** Connect to the upstream WS/stream. */
    void connect();

    /** Subscribe to ticker channel for a symbol. */
    void subscribeTicker(Symbol symbol);

    /** Subscribe to depth channel for a symbol. */
    void subscribeDepth(Symbol symbol, String depthChannel);

    /** Callback when a parsed market tick is available. */
    interface TickListener { void onTick(MarketTick tick); }

    /** Callback when a parsed book delta is available. */
    interface BookListener { void onBook(BookDelta delta); }

    /** Register listeners. */
    void setTickListener(TickListener listener);
    void setBookListener(BookListener listener);

    /** Close connection. */
    @Override
    void close();
}