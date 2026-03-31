package com.bedrock.mm.strategy;

import com.bedrock.mm.md.MarketDataService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Strategy service for managing trading strategies
 */
@Service
@ConditionalOnProperty(name = "bedrock.strategy.enabled", havingValue = "true", matchIfMissing = false)
public class StrategyService {
    private static final Logger log = LoggerFactory.getLogger(StrategyService.class);
    
    private final StrategyConfig strategyConfig;
    private final Optional<MarketDataService> marketDataService;
    private final Optional<OrderManager> orderManager;
    
    private final Map<String, Strategy> strategies = new ConcurrentHashMap<>();
    private final Map<String, StrategyContext> contexts = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    private ScheduledExecutorService scheduler;

    public StrategyService(StrategyConfig strategyConfig,
                           Optional<MarketDataService> marketDataService,
                           Optional<OrderManager> orderManager) {
        this.strategyConfig = strategyConfig;
        this.marketDataService = marketDataService != null ? marketDataService : Optional.empty();
        this.orderManager = orderManager != null ? orderManager : Optional.empty();
    }
    
    public void initialize() {
        if (!strategyConfig.isEnabled()) {
            log.info("Strategy service is disabled");
            return;
        }
        
        log.info("Initializing strategy service");
        
        // Create scheduler
        scheduler = Executors.newScheduledThreadPool(
            strategyConfig.getPerformance().getWorkerThreads(),
            r -> {
                Thread t = new Thread(r, "strategy-worker");
                t.setDaemon(true);
                return t;
            }
        );
        
        // Load and initialize strategies
        loadStrategies();
        
        // Start monitoring
        startMonitoring();
        
        log.info("Strategy service initialized with {} strategies", strategies.size());
    }
    
    @PreDestroy
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            log.info("Shutting down strategy service");
            
            // Stop all strategies
            stopAllStrategies();
            
            // Shutdown scheduler
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduler.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduler.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            
            log.info("Strategy service shut down");
        }
    }

    /**
     * Start all enabled strategies
     */
    public void startAllStrategies() {
        if (!strategyConfig.isEnabled()) {
            log.warn("Strategy service is disabled");
            return;
        }
        
        log.info("Starting all strategies");
        
        for (Strategy strategy : strategies.values()) {
            try {
                if (!strategy.isRunning()) {
                    strategy.start();
                    log.info("Started strategy: {}", strategy.getName());
                }
            } catch (Exception e) {
                log.error("Failed to start strategy: {}", strategy.getName(), e);
            }
        }
        
        running.set(true);
        log.info("All strategies started");
    }

    /**
     * Stop all strategies
     */
    public void stopAllStrategies() {
        log.info("Stopping all strategies");
        
        for (Strategy strategy : strategies.values()) {
            try {
                if (strategy.isRunning()) {
                    strategy.stop();
                    log.info("Stopped strategy: {}", strategy.getName());
                }
            } catch (Exception e) {
                log.error("Failed to stop strategy: {}", strategy.getName(), e);
            }
        }
        
        running.set(false);
        log.info("All strategies stopped");
    }

    /**
     * Start specific strategy
     */
    public boolean startStrategy(String name) {
        Strategy strategy = strategies.get(name);
        if (strategy == null) {
            log.warn("Strategy not found: {}", name);
            return false;
        }
        
        try {
            if (!strategy.isRunning()) {
                strategy.start();
                log.info("Started strategy: {}", name);
                return true;
            } else {
                log.warn("Strategy already running: {}", name);
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to start strategy: {}", name, e);
            return false;
        }
    }

    /**
     * Stop specific strategy
     */
    public boolean stopStrategy(String name) {
        Strategy strategy = strategies.get(name);
        if (strategy == null) {
            log.warn("Strategy not found: {}", name);
            return false;
        }
        
        try {
            if (strategy.isRunning()) {
                strategy.stop();
                log.info("Stopped strategy: {}", name);
                return true;
            } else {
                log.warn("Strategy not running: {}", name);
                return false;
            }
        } catch (Exception e) {
            log.error("Failed to stop strategy: {}", name, e);
            return false;
        }
    }

    /**
     * Get strategy by name
     */
    public Strategy getStrategy(String name) {
        return strategies.get(name);
    }
    
    /**
     * Get all strategies
     */
    public Map<String, Strategy> getAllStrategies() {
        return new ConcurrentHashMap<>(strategies);
    }
    
    /**
     * Get strategy statistics
     */
    public StrategyStats getStrategyStats(String name) {
        Strategy strategy = strategies.get(name);
        return strategy != null ? strategy.getStats() : null;
    }
    
    /**
     * Update strategy parameters
     */
    public boolean updateStrategyParameters(String name, StrategyParameters parameters) {
        Strategy strategy = strategies.get(name);
        if (strategy == null) {
            log.warn("Strategy not found: {}", name);
            return false;
        }
        
        try {
            strategy.updateParameters(parameters);
            log.info("Updated parameters for strategy: {}", name);
            return true;
        } catch (Exception e) {
            log.error("Failed to update parameters for strategy: {}", name, e);
            return false;
        }
    }
    
    /**
     * Reset strategy
     */
    public boolean resetStrategy(String name) {
        Strategy strategy = strategies.get(name);
        if (strategy == null) {
            log.warn("Strategy not found: {}", name);
            return false;
        }
        
        try {
            boolean wasRunning = strategy.isRunning();
            if (wasRunning) {
                strategy.stop();
            }
            
            strategy.reset();
            
            if (wasRunning) {
                strategy.start();
            }
            
            log.info("Reset strategy: {}", name);
            return true;
        } catch (Exception e) {
            log.error("Failed to reset strategy: {}", name, e);
            return false;
        }
    }
    
    /**
     * Check if service is running
     */
    public boolean isRunning() {
        return running.get();
    }
    
    private void loadStrategies() {
        for (StrategyConfig.StrategyDefinition def : strategyConfig.getEnabledStrategies()) {
            try {
                loadStrategy(def);
            } catch (Exception e) {
                log.error("Failed to load strategy: {}", def.getName(), e);
            }
        }
    }
    
    private void loadStrategy(StrategyConfig.StrategyDefinition def) throws Exception {
        log.info("Loading strategy: {} ({})", def.getName(), def.getClassName());
        
        // Create strategy instance
        Class<?> strategyClass = Class.forName(def.getClassName());
        Strategy strategy = (Strategy) strategyClass.getDeclaredConstructor().newInstance();
        
        // Create context
        StrategyContext context = new StrategyContextImpl(
            marketDataService.orElse(null),
            strategyConfig,
            orderManager.orElse(null));
        
        // Initialize strategy
        strategy.initialize(context);
        
        StrategyParameters params = strategy.getParameters().copy();
        params.setCustomParameter("strategyName", def.getName());
        params.setCustomParameter("symbols", def.getSymbols());
        if (!def.getParameters().isEmpty()) {
            def.getParameters().forEach(params::setCustomParameter);
        }
        strategy.updateParameters(params);
        
        // Store strategy and context
        strategies.put(def.getName(), strategy);
        contexts.put(def.getName(), context);
        
        log.info("Loaded strategy: {} v{}", strategy.getName(), strategy.getVersion());
    }
    
    private void startMonitoring() {
        if (!strategyConfig.getMonitoring().isEnabled()) {
            return;
        }
        
        long intervalMs = strategyConfig.getMonitoring().getStatsIntervalMs();
        
        scheduler.scheduleAtFixedRate(() -> {
            try {
                collectAndReportStats();
            } catch (Exception e) {
                log.error("Error collecting strategy stats", e);
            }
        }, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
        
        log.info("Started strategy monitoring with interval: {}ms", intervalMs);
    }
    
    private void collectAndReportStats() {
        for (Map.Entry<String, Strategy> entry : strategies.entrySet()) {
            String name = entry.getKey();
            Strategy strategy = entry.getValue();
            StrategyStats stats = strategy.getStats();
            
            if (log.isDebugEnabled()) {
                log.debug("Strategy {} stats: ticks={}, orders={}, pnl={}, position={}", 
                    name, stats.getTicksProcessed(), stats.getOrdersPlaced(), 
                    stats.getTotalPnl(), stats.getCurrentPosition());
            }
        }
    }
}
