package com.bedrock.mm.app.runtime.lifecycle;

import com.bedrock.mm.adapter.AdapterService;
import com.bedrock.mm.app.runtime.bus.EventBusCoordinator;
import com.bedrock.mm.app.runtime.bus.OmsCoordinator;
import com.bedrock.mm.app.runtime.bus.PricingEngineCoordinator;
import com.bedrock.mm.app.config.BedrockConfig;
import com.bedrock.mm.md.MarketDataService;
import com.bedrock.mm.md.FeedManager;
import com.bedrock.mm.monitor.MonitorService;
import com.bedrock.mm.strategy.StrategyService;
import com.bedrock.mm.md.providers.binance.BinanceFeed;
import com.bedrock.mm.md.providers.bitget.BitgetV3Feed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main application service for managing all Bedrock services
 */
@Service
public class ApplicationService {
    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);
    
    private final BedrockConfig config;
    private final MonitorService monitorService;
    private final MarketDataService marketDataService;
    private final StrategyService strategyService;
    private final AdapterService adapterService;
    private final BinanceFeed binanceFeed;
    private final BitgetV3Feed bitgetV3Feed;
    private final FeedManager feedManager;
    private final EventBusCoordinator eventBusCoordinator;
    private final PricingEngineCoordinator pricingEngineCoordinator;
    private final OmsCoordinator omsCoordinator;
    
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    
    @Autowired
    public ApplicationService(BedrockConfig config,
                            @Autowired(required = false) MonitorService monitorService,
                            @Autowired(required = false) MarketDataService marketDataService,
                            @Autowired(required = false) StrategyService strategyService,
                            @Autowired(required = false) AdapterService adapterService,
                            @Autowired(required = false) BinanceFeed binanceFeed,
                            @Autowired(required = false) BitgetV3Feed bitgetV3Feed,
                            @Autowired(required = false) FeedManager feedManager,
                            @Autowired(required = false) EventBusCoordinator eventBusCoordinator,
                            @Autowired(required = false) PricingEngineCoordinator pricingEngineCoordinator,
                            @Autowired(required = false) OmsCoordinator omsCoordinator) {
        this.config = config;
        this.monitorService = monitorService;
        this.marketDataService = marketDataService;
        this.strategyService = strategyService;
        this.adapterService = adapterService;
        this.binanceFeed = binanceFeed;
        this.bitgetV3Feed = bitgetV3Feed;
        this.feedManager = feedManager;
        this.eventBusCoordinator = eventBusCoordinator;
        this.pricingEngineCoordinator = pricingEngineCoordinator;
        this.omsCoordinator = omsCoordinator;
    }
    
    /**
     * Start all services when application is ready
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (started.compareAndSet(false, true)) {
            log.info("Starting {} in {} mode...", config.getDisplayName(), config.getMode());
            
            try {
                startServices().get(30, TimeUnit.SECONDS);
                log.info("Application started successfully");
                
                // Record startup metrics
                if (monitorService != null) {
                    monitorService.counter("application.startup.success").increment();
                    monitorService.gauge("application.status").setValue(1.0);
                }
                
            } catch (Exception e) {
                log.error("Failed to start application", e);
                if (monitorService != null) {
                    monitorService.counter("application.startup.failure").increment();
                    monitorService.gauge("application.status").setValue(0.0);
                }
                throw new RuntimeException("Application startup failed", e);
            }
        }
    }
    
    /**
     * Stop all services when application is shutting down
     */
    @EventListener(ContextClosedEvent.class)
    public void onApplicationShutdown() {
        if (stopping.compareAndSet(false, true)) {
            log.info("Shutting down {}...", config.getDisplayName());
            
            try {
                stopServices().get(30, TimeUnit.SECONDS);
                log.info("Application shutdown complete");
                
                if (monitorService != null) {
                    monitorService.counter("application.shutdown.success").increment();
                    monitorService.gauge("application.status").setValue(0.0);
                }
                
            } catch (Exception e) {
                log.error("Error during application shutdown", e);
                if (monitorService != null) {
                    monitorService.counter("application.shutdown.failure").increment();
                }
            }
        }
    }
    
    /**
     * Start all enabled services
     */
    public CompletableFuture<Void> startServices() {
        return CompletableFuture.runAsync(() -> {
            try {
                // Start services in order
                
                // 1. Monitor service (always first)
                if (monitorService != null) {
                    log.info("Starting monitor service...");
                    monitorService.start();
                }

                // 1.5 Unified Event Bus
                if (eventBusCoordinator != null) {
                    eventBusCoordinator.start(config);
                }

                // 1.7 Pricing engine (after bus, before MDS so consumers are ready)
                if (pricingEngineCoordinator != null) {
                    log.info("Starting pricing engine coordinator...");
                    pricingEngineCoordinator.start(config);
                }

                // 1.8 OMS coordinator (after pricing, before MDS so consumers are registered)
                if (omsCoordinator != null) {
                    log.info("Starting OMS coordinator...");
                    omsCoordinator.start(config);
                }

                // 2. Market data service
                if (marketDataService != null && config.isMarketDataEnabled()) {
                    log.info("Initializing market data service...");
                    marketDataService.initialize();
                    
                    log.info("Starting market feeds via FeedManager...");
                    if (feedManager != null) {
                        feedManager.startAll();
                    } else {
                        // fallback for legacy direct feeds
                        if (binanceFeed != null) {
                            log.info("Starting Binance feed (legacy)...");
                            binanceFeed.start();
                        }
                        if (bitgetV3Feed != null) {
                            log.info("Starting Bitget V3 feed (legacy)...");
                            bitgetV3Feed.start();
                        }
                    }
                    
                    log.info("Starting market data service...");
                    marketDataService.start();
                }
                
                // 3. Adapter service
                if (adapterService != null && config.isAdapterEnabled()) {
                    log.info("Initializing adapter service...");
                    adapterService.initialize();
                    
                    log.info("Starting adapter service...");
                    adapterService.startAll().join();

                }
                
                // 4. Strategy service (last, depends on others)
                if (strategyService != null && config.isStrategyEnabled()) {
                    log.info("Initializing strategy service...");
                    strategyService.initialize();
                    
                    log.info("Starting strategy service...");
                    strategyService.startAllStrategies();
                }
                
                log.info("All services started successfully");
                
            } catch (Exception e) {
                log.error("Failed to start services", e);
                throw new RuntimeException("Service startup failed", e);
            }
        });
    }
    
    /**
     * Stop all services
     */
    public CompletableFuture<Void> stopServices() {
        return CompletableFuture.runAsync(() -> {
            try {
                // Stop services in reverse order
                
                // 1. Strategy service (first to stop)
                if (strategyService != null) {
                    log.info("Stopping strategy service...");
                    try {
                        strategyService.shutdown();
                    } catch (Exception e) {
                        log.warn("Error stopping strategy service", e);
                    }
                }
                
                // 2. Adapter service
                if (adapterService != null) {
                    log.info("Stopping adapter service...");
                    try {
                        adapterService.stopAll().join();
                        adapterService.shutdown();
                    } catch (Exception e) {
                        log.warn("Error stopping adapter service", e);
                    }
                }
                
                // 2.5. Market feeds
                if (feedManager != null) {
                    log.info("Stopping market feeds via FeedManager...");
                    feedManager.stopAll();
                } else {
                    if (binanceFeed != null) {
                        log.info("Stopping Binance feed...");
                        try {
                            binanceFeed.close();
                        } catch (Exception e) {
                            log.warn("Error stopping Binance feed", e);
                        }
                    }
                    if (bitgetV3Feed != null) {
                        log.info("Stopping Bitget V3 feed...");
                        try {
                            bitgetV3Feed.close();
                        } catch (Exception e) {
                            log.warn("Error stopping Bitget V3 feed", e);
                        }
                    }
                }
                
                // 2.8 OMS coordinator
                if (omsCoordinator != null) {
                    log.info("Stopping OMS coordinator...");
                    omsCoordinator.stop();
                }

                // 2.9 Pricing engine
                if (pricingEngineCoordinator != null) {
                    log.info("Stopping pricing engine coordinator...");
                    pricingEngineCoordinator.stop();
                }

                // 3. Market data service
                if (marketDataService != null) {
                    log.info("Stopping market data service...");
                    try {
                        marketDataService.stop();
                    } catch (Exception e) {
                        log.warn("Error stopping market data service", e);
                    }
                }

                // 3.5. Unified Event Bus
                if (eventBusCoordinator != null) {
                    log.info("Stopping unified event bus loop...");
                    eventBusCoordinator.stop();
                }

                // 4. Monitor service (last)
                if (monitorService != null) {
                    log.info("Stopping monitor service...");
                    try {
                        monitorService.stop();
                    } catch (Exception e) {
                        log.warn("Error stopping monitor service", e);
                    }
                }
                
                log.info("All services stopped");
                
            } catch (Exception e) {
                log.error("Error during service shutdown", e);
                throw new RuntimeException("Service shutdown failed", e);
            }
        });
    }
    
    /**
     * Restart all services
     */
    public CompletableFuture<Void> restartServices() {
        return stopServices().thenCompose(v -> startServices());
    }
    
    /**
     * Get application status
     */
    public ApplicationStatus getStatus() {
        return ApplicationStatus.builder()
            .name(config.getName())
            .version(config.getVersion())
            .environment(config.getEnvironment())
            .mode(config.getMode())
            .started(started.get())
            .stopping(stopping.get())
            .monitorEnabled(config.isMonitorEnabled())
            .marketDataEnabled(config.isMarketDataEnabled())
            .strategyEnabled(config.isStrategyEnabled())
            .adapterEnabled(config.isAdapterEnabled())
            .build();
    }
    
    /**
     * Check if application is healthy
     */
    public boolean isHealthy() {
        if (!started.get() || stopping.get()) {
            return false;
        }
        
        // Check individual service health
        try {
            if (marketDataService != null && config.isMarketDataEnabled()) {
                if (!marketDataService.isRunning()) {
                    return false;
                }
            }
            
            if (adapterService != null && config.isAdapterEnabled()) {
                if (!adapterService.isAnyAdapterConnected()) {
                    return false;
                }
            }
            
            if (strategyService != null && config.isStrategyEnabled()) {
                long runningCount = strategyService.getAllStrategies().values().stream()
                    .filter(strategy -> strategy.isRunning())
                    .count();
                if (runningCount == 0) {
                    return false;
                }
            }
            
            return true;
            
        } catch (Exception e) {
            log.warn("Health check failed", e);
            return false;
        }
    }
    
    /**
     * Application status data class
     */
    @lombok.Builder
    @lombok.Data
    public static class ApplicationStatus {
        private String name;
        private String version;
        private String environment;
        private BedrockConfig.Mode mode;
        private boolean started;
        private boolean stopping;
        private boolean monitorEnabled;
        private boolean marketDataEnabled;
        private boolean strategyEnabled;
        private boolean adapterEnabled;
    }
}
