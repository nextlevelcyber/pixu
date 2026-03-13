package com.bedrock.mm.common.model;

/**
 * Order side enumeration
 */
public enum Side {
    BUY(1),
    SELL(2);
    
    private final int value;
    
    Side(int value) {
        this.value = value;
    }
    
    public int getValue() {
        return value;
    }
    
    public static Side fromValue(int value) {
        switch (value) {
            case 1:
                return BUY;
            case 2:
                return SELL;
            default:
                throw new IllegalArgumentException("Invalid side value: " + value);
        }
    }
    
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}