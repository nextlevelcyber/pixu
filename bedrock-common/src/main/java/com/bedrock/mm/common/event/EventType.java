package com.bedrock.mm.common.event;

/**
 * Unified event types for the system-wide event bus.
 */
public enum EventType {
    MARKET_TICK,
    BOOK_DELTA,
    ORDER_COMMAND,
    ORDER_ACK,
    FILL,
    BBO,
    QUOTE_TARGET,
    POSITION_UPDATE,
    EXEC_EVENT
}
