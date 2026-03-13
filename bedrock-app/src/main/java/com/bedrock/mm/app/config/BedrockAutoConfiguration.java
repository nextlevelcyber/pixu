package com.bedrock.mm.app.config;

import com.bedrock.mm.adapter.AdapterConfig;
import com.bedrock.mm.adapter.AdapterService;
import com.bedrock.mm.md.MarketDataConfig;
import com.bedrock.mm.md.MarketDataService;
import com.bedrock.mm.monitor.MonitorConfig;
import com.bedrock.mm.monitor.MonitorService;

import com.bedrock.mm.strategy.StrategyConfig;
import com.bedrock.mm.strategy.StrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Auto-configuration for Bedrock services
 */
@Configuration
@EnableConfigurationProperties(BedrockConfig.class)
public class BedrockAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(BedrockAutoConfiguration.class);
    
    /**
     * Monitor service - always enabled
     */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "bedrock.monitor.enabled", havingValue = "true", matchIfMissing = false)
    public MonitorService monitorService(BedrockConfig bedrockConfig) {
        MonitorConfig config = bedrockConfig.getMonitor();
        config.setEnabled(bedrockConfig.isMonitorEnabled());
        
        MonitorService service = new MonitorService(config);
        log.info("Created MonitorService with config: enabled={}", config.isEnabled());
        return service;
    }
    

    

    

    
    /**
     * Configuration validation
     */
    @Bean
    public BedrockConfigValidator configValidator(BedrockConfig bedrockConfig) {
        return new BedrockConfigValidator(bedrockConfig);
    }

    /**
     * Expose StrategyConfig bean derived from nested BedrockConfig.strategy to avoid
     * duplicate property binding on 'bedrock.strategy' prefix.
     */
    @Bean
    public StrategyConfig strategyConfig(BedrockConfig bedrockConfig) {
        return bedrockConfig.getStrategy();
    }
    
    /**
     * Configuration validator
     */
    public static class BedrockConfigValidator {
        private final BedrockConfig config;
        
        public BedrockConfigValidator(BedrockConfig config) {
            this.config = config;
            validateConfiguration();
        }
        
        private void validateConfiguration() {
            try {
                config.validate();
                log.info("Configuration validation passed for {}", config.getDisplayName());
            } catch (Exception e) {
                log.error("Configuration validation failed: {}", e.getMessage());
                throw new IllegalStateException("Invalid configuration", e);
            }
        }
    }
}
