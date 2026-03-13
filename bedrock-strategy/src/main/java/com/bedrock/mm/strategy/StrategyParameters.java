package com.bedrock.mm.strategy;

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Strategy parameters
 */
@Data
public class StrategyParameters {
    
    // Common parameters
    private double spread = 0.001; // 0.1%
    private double quantity = 1.0;
    private double maxPosition = 10.0;
    private double riskLimit = 1000.0;
    private boolean enabled = true;
    
    // Market making specific
    private double skewFactor = 0.0;
    private double inventoryTarget = 0.0;
    private int maxOrders = 2;
    private double minSpread = 0.0001; // 0.01%
    private double maxSpread = 0.01; // 1%
    
    // Risk management
    private double stopLoss = 0.05; // 5%
    private double takeProfit = 0.02; // 2%
    private double maxDrawdown = 0.1; // 10%
    private double positionLimit = 100.0;
    
    // Timing parameters
    private long orderTimeoutMs = 5000; // 5 seconds
    private long quoteRefreshMs = 1000; // 1 second
    private long riskCheckMs = 100; // 100ms
    
    // Custom parameters
    private Map<String, Object> customParameters = new HashMap<>();
    
    /**
     * Get custom parameter
     */
    @SuppressWarnings("unchecked")
    public <T> T getCustomParameter(String key, T defaultValue) {
        return (T) customParameters.getOrDefault(key, defaultValue);
    }
    
    /**
     * Set custom parameter
     */
    public void setCustomParameter(String key, Object value) {
        customParameters.put(key, value);
    }
    
    /**
     * Validate parameters
     */
    public boolean isValid() {
        return spread > 0 && 
               quantity > 0 && 
               maxPosition > 0 && 
               riskLimit > 0 &&
               minSpread <= maxSpread &&
               stopLoss > 0 &&
               takeProfit > 0 &&
               maxDrawdown > 0 &&
               orderTimeoutMs > 0 &&
               quoteRefreshMs > 0 &&
               riskCheckMs > 0;
    }
    
    /**
     * Create a copy of parameters
     */
    public StrategyParameters copy() {
        StrategyParameters copy = new StrategyParameters();
        copy.spread = this.spread;
        copy.quantity = this.quantity;
        copy.maxPosition = this.maxPosition;
        copy.riskLimit = this.riskLimit;
        copy.enabled = this.enabled;
        copy.skewFactor = this.skewFactor;
        copy.inventoryTarget = this.inventoryTarget;
        copy.maxOrders = this.maxOrders;
        copy.minSpread = this.minSpread;
        copy.maxSpread = this.maxSpread;
        copy.stopLoss = this.stopLoss;
        copy.takeProfit = this.takeProfit;
        copy.maxDrawdown = this.maxDrawdown;
        copy.positionLimit = this.positionLimit;
        copy.orderTimeoutMs = this.orderTimeoutMs;
        copy.quoteRefreshMs = this.quoteRefreshMs;
        copy.riskCheckMs = this.riskCheckMs;
        copy.customParameters = new HashMap<>(this.customParameters);
        return copy;
    }
}