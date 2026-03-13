package com.bedrock.mm.oms.model;

/**
 * Execution event type enumeration.
 *
 * Represents the different types of order execution events that can occur.
 */
public enum ExecEventType {
    /**
     * Order acknowledged by exchange (NEW state).
     */
    ACK,

    /**
     * Partial fill received.
     */
    PARTIAL_FILL,

    /**
     * Order fully filled.
     */
    FILL,

    /**
     * Order cancelled.
     */
    CANCELLED,

    /**
     * Order rejected by exchange.
     */
    REJECTED
}
