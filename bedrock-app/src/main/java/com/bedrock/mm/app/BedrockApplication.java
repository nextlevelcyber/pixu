package com.bedrock.mm.app;

import com.bedrock.mm.app.config.BedrockConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Arrays;

/**
 * Main application class for Bedrock Market Making System
 */
@SpringBootApplication(scanBasePackages = "com.bedrock.mm")
@EnableConfigurationProperties(BedrockConfig.class)
public class BedrockApplication {
    private static final Logger log = LoggerFactory.getLogger(BedrockApplication.class);
    
    public static void main(String[] args) {
        try {
            // Print startup banner
            printBanner();
            
            // Configure system properties
            configureSystemProperties();
            
            // Start Spring Boot application
            ConfigurableApplicationContext context = SpringApplication.run(BedrockApplication.class, args);
            
            // Get configuration
            BedrockConfig config = context.getBean(BedrockConfig.class);
            
            // Print startup information
            printStartupInfo(config, context);
            
            // Add shutdown hook
            addShutdownHook(context);
            
        } catch (Exception e) {
            log.error("Failed to start Bedrock application", e);
            System.exit(1);
        }
    }
    
    private static void printBanner() {
        String banner = "\n" +
                "██████╗ ███████╗██████╗ ██████╗  ██████╗  ██████╗██╗  ██╗\n" +
                "██╔══██╗██╔════╝██╔══██╗██╔══██╗██╔═══██╗██╔════╝██║ ██╔╝\n" +
                "██████╔╝█████╗  ██║  ██║██████╔╝██║   ██║██║     █████╔╝ \n" +
                "██╔══██╗██╔══╝  ██║  ██║██╔══██╗██║   ██║██║     ██╔═██╗ \n" +
                "██████╔╝███████╗██████╔╝██║  ██║╚██████╔╝╚██████╗██║  ██╗\n" +
                "╚═════╝ ╚══════╝╚═════╝ ╚═╝  ╚═╝ ╚═════╝  ╚═════╝╚═╝  ╚═╝\n" +
                "\n" +
                "Market Making System v1.0.0\n";
        
        System.out.println(banner);
    }
    
    private static void configureSystemProperties() {
        // Set default system properties if not already set
        
        // JVM optimization for low latency
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        
        // Disable DNS caching for better failover
        if (System.getProperty("networkaddress.cache.ttl") == null) {
            System.setProperty("networkaddress.cache.ttl", "60");
        }
        
        // Enable JVM optimizations
        if (System.getProperty("java.net.preferIPv4Stack") == null) {
            System.setProperty("java.net.preferIPv4Stack", "true");
        }
        
        // Set default timezone to UTC
        if (System.getProperty("user.timezone") == null) {
            System.setProperty("user.timezone", "UTC");
        }
        
        log.info("System properties configured");
    }
    
    private static void printStartupInfo(BedrockConfig config, ConfigurableApplicationContext context) {
        log.info("=".repeat(80));
        log.info("Application: {}", config.getDisplayName());
        log.info("Mode: {}", config.getMode());
        log.info("Environment: {}", config.getEnvironment());
        log.info("Server Port: {}", config.getServer().getPort());
        log.info("Context Path: {}", config.getServer().getContextPath());
        log.info("Active Profiles: {}", Arrays.toString(context.getEnvironment().getActiveProfiles()));
        log.info("=".repeat(80));
        
        // Print service status
        log.info("Service Status:");
        log.info("  Monitor Service: ENABLED");
        log.info("  Market Data Service: {}", config.isMarketDataEnabled() ? "ENABLED" : "DISABLED");
        log.info("  Strategy Service: {}", config.isStrategyEnabled() ? "ENABLED" : "DISABLED");
        log.info("  Adapter Service: {}", config.isAdapterEnabled() ? "ENABLED" : "DISABLED");
        log.info("=".repeat(80));
        
        // Print API endpoints
        String baseUrl = String.format("http://localhost:%d%s", 
            config.getServer().getPort(), config.getServer().getContextPath());
        log.info("API Endpoints:");
        log.info("  Status: {}/v1/status", baseUrl);
        log.info("  Health: {}/v1/health", baseUrl);
        log.info("  Strategies: {}/v1/strategies", baseUrl);
        log.info("  Adapters: {}/v1/adapters", baseUrl);
        log.info("  Actuator: http://localhost:{}/actuator", config.getServer().getManagementPort());
        log.info("=".repeat(80));
        
        log.info("Bedrock Market Making System started successfully!");
    }
    
    private static void addShutdownHook(ConfigurableApplicationContext context) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown signal received, gracefully shutting down...");
            try {
                context.close();
                log.info("Application shutdown complete");
            } catch (Exception e) {
                log.error("Error during shutdown", e);
            }
        }, "shutdown-hook"));
    }
}