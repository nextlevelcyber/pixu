package com.bedrock.mm.md;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Market data service configuration
 */
@Data
@Component
@ConfigurationProperties(prefix = "bedrock.md")
public class MarketDataConfig {
    
    /**
     * Whether to enable market data service
     */
    private boolean enabled = true;
    
    /**
     * Market data sources configuration
     */
    private List<DataSourceConfig> dataSources = List.of(
        new DataSourceConfig("simulation", "SIMULATION", true)
    );
    
    /**
     * Channel configuration
     */
    private ChannelConfig channels = new ChannelConfig();
    
    /**
     * Simulation configuration
     */
    private SimulationConfig simulation = new SimulationConfig();
    
    /**
     * Buffer configuration
     */
    private BufferConfig buffer = new BufferConfig();
    
    @Data
    public static class DataSourceConfig {
        private String name;
        private String type;
        private boolean enabled;
        private String endpoint;
        private String apiKey;
        private String secretKey;
        private Duration reconnectInterval = Duration.ofSeconds(5);
        private int maxReconnectAttempts = 10;
        
        public DataSourceConfig() {}
        
        public DataSourceConfig(String name, String type, boolean enabled) {
            this.name = name;
            this.type = type;
            this.enabled = enabled;
        }
    }
    
    @Data
    public static class ChannelConfig {
        private int marketTickStreamId = 1001;
        private int bookDeltaStreamId = 1002;
        private String marketTickChannel = "market-ticks";
        private String bookDeltaChannel = "book-deltas";
        private String mode = "IN_PROC";
    }
    
    @Data
    public static class SimulationConfig {
        private boolean enabled = true;
        private Duration tickInterval = Duration.ofMillis(100);
        private Duration deltaInterval = Duration.ofMillis(50);
        private double priceVolatility = 0.02; // 2% volatility
        private double volumeRange = 10.0;
        private List<String> symbols = List.of("BTCUSDT", "ETHUSDT");
    }
    
    @Data
    public static class BufferConfig {
        private int ringBufferSize = 1024 * 1024; // 1MB
        private int batchSize = 100;
        private Duration flushInterval = Duration.ofMillis(10);
        private boolean enableBackpressure = true;
    }
}