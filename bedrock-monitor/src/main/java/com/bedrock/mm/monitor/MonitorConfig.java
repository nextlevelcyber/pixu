package com.bedrock.mm.monitor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration for the monitoring framework
 */
@Data
@Component
@ConfigurationProperties(prefix = "bedrock.monitor")
public class MonitorConfig {
    
    /**
     * Directory for storing metric files
     */
    private String metricsDirectory = "metrics";
    
    /**
     * Whether to enable monitoring
     */
    private boolean enabled = true;
    
    /**
     * Flush interval for persisting metrics
     */
    private Duration flushInterval = Duration.ofSeconds(30);
    
    /**
     * Maximum number of metrics to store
     */
    private int maxMetrics = 10000;
    
    /**
     * Buffer size per metric in bytes
     */
    private int metricBufferSize = 1024;
    
    /**
     * Whether to enable JVM metrics
     */
    private boolean enableJvmMetrics = true;
    
    /**
     * Whether to enable system metrics
     */
    private boolean enableSystemMetrics = true;
    
    /**
     * Metric name prefix
     */
    private String metricPrefix = "bedrock";
    
    /**
     * Whether to export metrics to external systems
     */
    private boolean enableExport = false;
    
    /**
     * Export configuration
     */
    private ExportConfig export = new ExportConfig();
    
    @Data
    public static class ExportConfig {
        /**
         * Export interval
         */
        private Duration interval = Duration.ofMinutes(1);
        
        /**
         * Export format (json, csv, prometheus)
         */
        private String format = "json";
        
        /**
         * Export destination
         */
        private String destination = "console";
        
        /**
         * Whether to include JVM metrics in export
         */
        private boolean includeJvmMetrics = true;
        
        /**
         * Whether to include system metrics in export
         */
        private boolean includeSystemMetrics = true;
    }
}