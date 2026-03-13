package com.bedrock.mm.app.config;

import com.bedrock.mm.adapter.AdapterConfig;
import com.bedrock.mm.md.MarketDataConfig;
import com.bedrock.mm.monitor.MonitorConfig;
import com.bedrock.mm.strategy.StrategyConfig;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.ArrayList;
import java.util.List;

/**
 * Main configuration class for Bedrock application
 */
@Data
@ConfigurationProperties(prefix = "bedrock")
public class BedrockConfig {
    
    /**
     * Application information
     */
    private String name = "Bedrock Market Making System";
    private String version = "1.0.0";
    private String environment = "development";
    
    /**
     * Application mode
     */
    private Mode mode = Mode.FULL;
    
    /**
     * Server configuration
     */
    @NestedConfigurationProperty
    private ServerConfig server = new ServerConfig();
    
    /**
     * Market data configuration
     */
    @NestedConfigurationProperty
    private MarketDataConfig marketData = new MarketDataConfig();
    
    /**
     * Strategy configuration
     */
    @NestedConfigurationProperty
    private StrategyConfig strategy = new StrategyConfig();
    
    /**
     * Adapter configuration
     */
    @NestedConfigurationProperty
    private AdapterConfig adapter = new AdapterConfig();
    
    /**
     * Monitor configuration
     */
    @NestedConfigurationProperty
    private MonitorConfig monitor = new MonitorConfig();

    /**
     * Pricing engine configuration
     */
    @NestedConfigurationProperty
    private PricingConfig pricing = new PricingConfig();

    /**
     * OMS configuration
     */
    @NestedConfigurationProperty
    private OmsConfig oms = new OmsConfig();
    
    /**
     * Application mode enum
     */
    public enum Mode {
        /**
         * Full mode - all services enabled
         */
        FULL,
        
        /**
         * Market data only
         */
        MARKET_DATA_ONLY,
        
        /**
         * Strategy only
         */
        STRATEGY_ONLY,
        
        /**
         * Adapter only
         */
        ADAPTER_ONLY,
        
        /**
         * Simulation mode
         */
        SIMULATION
    }
    
    /**
     * Server configuration
     */
    @Data
    public static class ServerConfig {
        private int port = 8080;
        private String contextPath = "/api";
        private boolean enableSwagger = true;
        private boolean enableActuator = true;
        private String managementPort = "8081";
        private Cors cors = new Cors();
        
        @Data
        public static class Cors {
            private boolean enabled = true;
            private String[] allowedOrigins = {"*"};
            private String[] allowedMethods = {"GET", "POST", "PUT", "DELETE", "OPTIONS"};
            private String[] allowedHeaders = {"*"};
            private boolean allowCredentials = false;
            private long maxAge = 3600;
        }
    }
    
    /**
     * Validate configuration
     */
    public void validate() {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Application name cannot be empty");
        }
        
        if (version == null || version.trim().isEmpty()) {
            throw new IllegalArgumentException("Application version cannot be empty");
        }
        
        if (mode == null) {
            throw new IllegalArgumentException("Application mode cannot be null");
        }
        
        // Validate nested configurations based on mode
        switch (mode) {
            case FULL:
                if (!strategy.isValid()) {
                    throw new IllegalArgumentException("Invalid strategy configuration");
                }
                if (!adapter.isValid()) {
                    throw new IllegalArgumentException("Invalid adapter configuration");
                }
                break;
            case MARKET_DATA_ONLY:
                // Market data and monitor configs don't have validation methods yet
                break;
            case STRATEGY_ONLY:
                if (!strategy.isValid()) {
                    throw new IllegalArgumentException("Invalid strategy configuration");
                }
                break;
            case ADAPTER_ONLY:
                if (!adapter.isValid()) {
                    throw new IllegalArgumentException("Invalid adapter configuration");
                }
                break;
            case SIMULATION:
                // All configs should be valid for simulation
                if (!strategy.isValid()) {
                    throw new IllegalArgumentException("Invalid strategy configuration");
                }
                if (!adapter.isValid()) {
                    throw new IllegalArgumentException("Invalid adapter configuration");
                }
                break;
        }
    }
    
    /**
     * Check if market data service should be enabled
     */
    public boolean isMarketDataEnabled() {
        return mode == Mode.FULL
                || mode == Mode.MARKET_DATA_ONLY
                || mode == Mode.SIMULATION;
    }
    
    /**
     * Check if strategy service should be enabled
     */
    public boolean isStrategyEnabled() {
        return mode == Mode.FULL || mode == Mode.STRATEGY_ONLY || mode == Mode.SIMULATION;
    }
    
    /**
     * Check if adapter service should be enabled
     */
    public boolean isAdapterEnabled() {
        return mode == Mode.FULL || mode == Mode.ADAPTER_ONLY || mode == Mode.SIMULATION;
    }
    
    /**
     * Check if monitor service should be enabled
     */
    public boolean isMonitorEnabled() {
        return monitor.isEnabled();
    }

    /**
     * Check if pricing engine should be enabled
     */
    public boolean isPricingEnabled() {
        return pricing.isEnabled();
    }

    /**
     * Check if OMS should be enabled
     */
    public boolean isOmsEnabled() {
        return oms.isEnabled();
    }

    /**
     * Pricing engine configuration
     */
    @Data
    public static class PricingConfig {
        private boolean enabled = false;
        private List<String> symbols = new ArrayList<>();
    }

    /**
     * OMS configuration
     */
    @Data
    public static class OmsConfig {
        private boolean enabled = false;
        private List<String> symbols = new ArrayList<>();
        /** Exchange connector to use: binance (default) or simulation. */
        private String exchange = "binance";
    }
    
    /**
     * Get application display name
     */
    public String getDisplayName() {
        return name + " v" + version + " (" + environment + ")";
    }
}
