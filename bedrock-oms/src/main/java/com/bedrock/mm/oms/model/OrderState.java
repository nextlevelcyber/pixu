package com.bedrock.mm.oms.model;

/**
 * Order state enumeration for OMS state machine
 */
public enum OrderState {
    PENDING_NEW,
    OPEN,
    PARTIAL_FILL,
    FILLED,
    CANCELLED,
    REJECTED,
    PENDING_CANCEL
}
