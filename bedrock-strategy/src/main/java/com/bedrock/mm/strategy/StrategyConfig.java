package com.bedrock.mm.strategy;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Strategy configuration
 */
@Data
public class StrategyConfig {
    
    /**
     * Whether strategy service is enabled
     */
    private boolean enabled = true;
    
    /**
     * Strategy execution mode
     */
    private ExecutionMode executionMode = ExecutionMode.SIMULATION;
    
    /**
     * List of strategies to load and run
     */
    private List<StrategyDefinition> strategies = new ArrayList<>();
    
    /**
     * Global strategy parameters
     */
    private GlobalParameters global = new GlobalParameters();
    
    /**
     * Risk management settings
     */
    private RiskSettings risk = new RiskSettings();
    
    /**
     * Performance settings
     */
    private PerformanceSettings performance = new PerformanceSettings();
    
    /**
     * Monitoring settings
     */
    private MonitoringSettings monitoring = new MonitoringSettings();
    
    @Data
    public static class StrategyDefinition {
        private String name;
        private String className;
        private boolean enabled = true;
        private Map<String, Object> parameters = new HashMap<>();
        private List<String> symbols = new ArrayList<>();
        private int priority = 0;
        private String description;
    }
    
    @Data
    public static class GlobalParameters {
        private double defaultSpread = 0.001;
        private double defaultQuantity = 1.0;
        private double maxPosition = 10.0;
        private double riskLimit = 1000.0;
        private long orderTimeoutMs = 5000;
        private long quoteRefreshMs = 1000;
        private boolean enableRiskChecks = true;
        private boolean enablePositionLimits = true;
    }
    
    @Data
    public static class RiskSettings {
        private boolean enabled = true;
        private double maxDrawdown = 0.1; // 10%
        private double stopLoss = 0.05; // 5%
        private double positionLimit = 100.0;
        private double dailyLossLimit = 1000.0;
        private double maxOrderSize = 10.0;
        private long riskCheckIntervalMs = 100;
        private boolean autoStopOnLimit = true;
    }
    
    @Data
    public static class PerformanceSettings {
        private int workerThreads = 4;
        private int ringBufferSize = 1024;
        private int batchSize = 10;
        private long flushIntervalMs = 1000;
        private boolean enableBatching = true;
        private boolean enableAsyncProcessing = true;
        private int maxQueueSize = 10000;
    }
    
    @Data
    public static class MonitoringSettings {
        private boolean enabled = true;
        private long statsIntervalMs = 5000;
        private boolean enableMetrics = true;
        private boolean enableLogging = true;
        private String logLevel = "INFO";
        private boolean enablePerformanceTracking = true;
        private boolean enableRiskMonitoring = true;
    }
    
    public enum ExecutionMode {
        SIMULATION,  // Paper trading
        LIVE,        // Live trading
        BACKTEST     // Historical data testing
    }
    
    /**
     * Get strategy definition by name
     */
    public StrategyDefinition getStrategy(String name) {
        return strategies.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
    
    /**
     * Add strategy definition
     */
    public void addStrategy(StrategyDefinition strategy) {
        strategies.add(strategy);
    }
    
    /**
     * Remove strategy definition
     */
    public boolean removeStrategy(String name) {
        return strategies.removeIf(s -> s.getName().equals(name));
    }
    
    /**
     * Get enabled strategies
     */
    public List<StrategyDefinition> getEnabledStrategies() {
        return strategies.stream()
                .filter(StrategyDefinition::isEnabled)
                .collect(Collectors.toList());
    }
    
    /**
     * Validate configuration
     */
    public boolean isValid() {
        if (!enabled) return true;
        
        // Check global parameters
        if (global.getDefaultSpread() <= 0 || 
            global.getDefaultQuantity() <= 0 ||
            global.getMaxPosition() <= 0 ||
            global.getRiskLimit() <= 0) {
            return false;
        }
        
        // Check risk settings
        if (risk.isEnabled()) {
            if (risk.getMaxDrawdown() <= 0 ||
                risk.getStopLoss() <= 0 ||
                risk.getPositionLimit() <= 0 ||
                risk.getDailyLossLimit() <= 0 ||
                risk.getMaxOrderSize() <= 0) {
                return false;
            }
        }
        
        // Check performance settings
        if (performance.getWorkerThreads() <= 0 ||
            performance.getRingBufferSize() <= 0 ||
            performance.getBatchSize() <= 0 ||
            performance.getMaxQueueSize() <= 0) {
            return false;
        }
        
        // Check strategies
        for (StrategyDefinition strategy : strategies) {
            if (strategy.getName() == null || strategy.getName().trim().isEmpty()) {
                return false;
            }
            if (strategy.getClassName() == null || strategy.getClassName().trim().isEmpty()) {
                return false;
            }
        }
        
        return true;
    }
}