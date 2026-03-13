package com.bedrock.mm.app.controller;

import com.bedrock.mm.adapter.AdapterService;
import com.bedrock.mm.adapter.AdapterStats;
import com.bedrock.mm.adapter.TradingAdapter;
import com.bedrock.mm.aeron.bus.EventBus;
import com.bedrock.mm.app.runtime.bus.DeadLetterChannel;
import com.bedrock.mm.app.runtime.bus.OmsCoordinator;
import com.bedrock.mm.app.runtime.lifecycle.ApplicationService;
import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.md.MarketDataService;
import com.bedrock.mm.strategy.StrategyService;
import com.bedrock.mm.strategy.StrategyStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * REST API controller for application management
 */
@RestController
@RequestMapping("/api/v1")
public class ApplicationController {
    private static final Logger log = LoggerFactory.getLogger(ApplicationController.class);
    
    private final ApplicationService applicationService;
    private final MarketDataService marketDataService;
    private final StrategyService strategyService;
    private final AdapterService adapterService;
    private final DeadLetterChannel deadLetterChannel;
    private final EventBus eventBus;
    private final OmsCoordinator omsCoordinator;
    
    public ApplicationController(ApplicationService applicationService,
                               @Autowired(required = false) MarketDataService marketDataService,
                               @Autowired(required = false) StrategyService strategyService,
                               @Autowired(required = false) AdapterService adapterService,
                               @Autowired(required = false) DeadLetterChannel deadLetterChannel,
                               @Autowired(required = false) EventBus eventBus,
                               @Autowired(required = false) OmsCoordinator omsCoordinator) {
        this.applicationService = applicationService;
        this.marketDataService = marketDataService;
        this.strategyService = strategyService;
        this.adapterService = adapterService;
        this.deadLetterChannel = deadLetterChannel;
        this.eventBus = eventBus;
        this.omsCoordinator = omsCoordinator;
    }
    
    /**
     * Get application status
     */
    @GetMapping("/status")
    public ResponseEntity<ApplicationService.ApplicationStatus> getStatus() {
        return ResponseEntity.ok(applicationService.getStatus());
    }
    
    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", applicationService.isHealthy() ? "UP" : "DOWN");
        health.put("timestamp", System.currentTimeMillis());
        
        // Add service-specific health info
        Map<String, Object> services = new HashMap<>();
        
        if (marketDataService != null) {
            services.put("marketData", Map.of(
                "status", marketDataService.isRunning() ? "UP" : "DOWN"
            ));
        }
        
        if (strategyService != null) {
            int totalStrategies = strategyService.getAllStrategies().size();
            int runningStrategies = (int) strategyService.getAllStrategies().entrySet().stream()
                .filter(entry -> entry.getValue().isRunning())
                .count();
            
            services.put("strategy", Map.of(
                "status", "UP",
                "runningStrategies", runningStrategies,
                "totalStrategies", totalStrategies
            ));
        }
        
        if (adapterService != null) {
            services.put("adapter", Map.of(
                "status", adapterService.isAnyAdapterConnected() ? "UP" : "DOWN",
                "connectedAdapters", adapterService.getConnectedAdapters().size(),
                "totalAdapters", adapterService.getAllAdapters().size()
            ));
        }

        if (omsCoordinator != null) {
            Map<String, OmsCoordinator.OmsRecoverySnapshot> snapshots = omsCoordinator.getAllRecoverySnapshots();
            int total = snapshots.size();
            long ready = snapshots.values().stream()
                    .filter(OmsCoordinator.OmsRecoverySnapshot::recoveryReady)
                    .count();
            services.put("oms", Map.of(
                    "status", omsCoordinator.isStartupRecoveryReady() ? "UP" : "DEGRADED",
                    "recoveryReadySymbols", ready,
                    "totalSymbols", total
            ));
        }
        
        health.put("services", services);
        
        return ResponseEntity.ok(health);
    }

    /**
     * Get OMS latency snapshots for all active symbols.
     */
    @GetMapping("/oms/latency")
    public ResponseEntity<Map<String, OmsCoordinator.OmsLatencySnapshot>> getOmsLatencies() {
        if (omsCoordinator == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(omsCoordinator.getAllLatencySnapshots());
    }

    /**
     * Get OMS latency snapshot for one symbol.
     */
    @GetMapping("/oms/latency/{symbol}")
    public ResponseEntity<OmsCoordinator.OmsLatencySnapshot> getOmsLatencyBySymbol(
            @PathVariable String symbol) {
        if (omsCoordinator == null) {
            return ResponseEntity.notFound().build();
        }
        OmsCoordinator.OmsLatencySnapshot snapshot = omsCoordinator.getLatencySnapshot(symbol);
        if (snapshot == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(snapshot);
    }

    /**
     * Get OMS startup recovery snapshots for all active symbols.
     */
    @GetMapping("/oms/recovery")
    public ResponseEntity<Map<String, OmsCoordinator.OmsRecoverySnapshot>> getOmsRecoveries() {
        if (omsCoordinator == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(omsCoordinator.getAllRecoverySnapshots());
    }

    /**
     * Get OMS startup recovery snapshot for one symbol.
     */
    @GetMapping("/oms/recovery/{symbol}")
    public ResponseEntity<OmsCoordinator.OmsRecoverySnapshot> getOmsRecoveryBySymbol(
            @PathVariable String symbol) {
        if (omsCoordinator == null) {
            return ResponseEntity.notFound().build();
        }
        OmsCoordinator.OmsRecoverySnapshot snapshot = omsCoordinator.getRecoverySnapshot(symbol);
        if (snapshot == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(snapshot);
    }

    /**
     * Retry OMS startup recovery for one symbol.
     */
    @PostMapping("/oms/recovery/{symbol}/retry")
    public ResponseEntity<Map<String, Object>> retryOmsRecoveryBySymbol(
            @PathVariable String symbol) {
        if (omsCoordinator == null) {
            return ResponseEntity.notFound().build();
        }
        boolean ok = omsCoordinator.retryRecovery(symbol);
        OmsCoordinator.OmsRecoverySnapshot snapshot = omsCoordinator.getRecoverySnapshot(symbol);
        if (snapshot == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "symbol", symbol,
                "success", ok,
                "snapshot", snapshot
        ));
    }
    
    /**
     * Restart application services
     */
    @PostMapping("/restart")
    public CompletableFuture<ResponseEntity<Map<String, String>>> restart() {
        return applicationService.restartServices()
            .thenApply(v -> ResponseEntity.ok(Map.of("message", "Services restarted successfully")))
            .exceptionally(ex -> {
                log.error("Failed to restart services", ex);
                return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to restart services: " + ex.getMessage()));
            });
    }
    
    // Market Data endpoints
    
    /**
     * Get market data service status
     */
    @GetMapping("/md/status")
    public ResponseEntity<Map<String, Object>> getMarketDataStatus() {
        if (marketDataService == null) {
            return ResponseEntity.notFound().build();
        }
        
        Map<String, Object> status = new HashMap<>();
        status.put("running", marketDataService.isRunning());
        
        return ResponseEntity.ok(status);
    }
    
    /**
     * Start market data service
     */
    @PostMapping("/md/start")
    public ResponseEntity<Map<String, String>> startMarketData() {
        if (marketDataService == null) {
            return ResponseEntity.notFound().build();
        }
        
        try {
            marketDataService.start();
            return ResponseEntity.ok(Map.of("message", "Market data service started"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Failed to start market data service: " + e.getMessage()));
        }
    }
    
    /**
     * Stop market data service
     */
    @PostMapping("/md/stop")
    public ResponseEntity<Map<String, String>> stopMarketData() {
        if (marketDataService == null) {
            return ResponseEntity.notFound().build();
        }
        
        try {
            marketDataService.stop();
            return ResponseEntity.ok(Map.of("message", "Market data service stopped"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Failed to stop market data service: " + e.getMessage()));
        }
    }
    
    // Strategy endpoints
    
    /**
     * Get all strategies
     */
    @GetMapping("/strategies")
    public ResponseEntity<Set<String>> getStrategies() {
        if (strategyService == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(strategyService.getAllStrategies().keySet());
    }
    
    /**
     * Get running strategies
     */
    @GetMapping("/strategies/running")
    public ResponseEntity<Set<String>> getRunningStrategies() {
        if (strategyService == null) {
            return ResponseEntity.notFound().build();
        }
        
        Set<String> runningStrategies = strategyService.getAllStrategies().entrySet().stream()
            .filter(entry -> entry.getValue().isRunning())
            .map(Map.Entry::getKey)
            .collect(java.util.stream.Collectors.toSet());
        
        return ResponseEntity.ok(runningStrategies);
    }
    
    /**
     * Start strategy
     */
    @PostMapping("/strategies/{strategyName}/start")
    public ResponseEntity<Map<String, String>> startStrategy(@PathVariable String strategyName) {
        if (strategyService == null) {
            return ResponseEntity.notFound().build();
        }
        
        try {
            strategyService.startStrategy(strategyName);
            return ResponseEntity.ok(Map.of("message", "Strategy started: " + strategyName));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Failed to start strategy: " + e.getMessage()));
        }
    }
    
    /**
     * Stop strategy
     */
    @PostMapping("/strategies/{strategyName}/stop")
    public ResponseEntity<Map<String, String>> stopStrategy(@PathVariable String strategyName) {
        if (strategyService == null) {
            return ResponseEntity.notFound().build();
        }
        
        try {
            strategyService.stopStrategy(strategyName);
            return ResponseEntity.ok(Map.of("message", "Strategy stopped: " + strategyName));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Failed to stop strategy: " + e.getMessage()));
        }
    }
    
    /**
     * Get strategy statistics
     */
    @GetMapping("/strategies/{strategyName}/stats")
    public ResponseEntity<StrategyStats> getStrategyStats(@PathVariable String strategyName) {
        if (strategyService == null) {
            return ResponseEntity.notFound().build();
        }
        
        StrategyStats stats = strategyService.getStrategyStats(strategyName);
        if (stats == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(stats);
    }
    
    /**
     * Reset strategy
     */
    @PostMapping("/strategies/{strategyName}/reset")
    public ResponseEntity<Map<String, String>> resetStrategy(@PathVariable String strategyName) {
        if (strategyService == null) {
            return ResponseEntity.notFound().build();
        }
        
        try {
            strategyService.resetStrategy(strategyName);
            return ResponseEntity.ok(Map.of("message", "Strategy reset: " + strategyName));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                .body(Map.of("error", "Failed to reset strategy: " + e.getMessage()));
        }
    }
    
    // Adapter endpoints
    
    /**
     * Get all adapters
     */
    @GetMapping("/adapters")
    public ResponseEntity<Set<String>> getAdapters() {
        if (adapterService == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(adapterService.getAdapterNames());
    }
    
    /**
     * Get adapter statistics
     */
    @GetMapping("/adapters/stats")
    public ResponseEntity<Map<String, AdapterStats>> getAdapterStats() {
        if (adapterService == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(adapterService.getAdapterStats());
    }
    
    /**
     * Get supported symbols
     */
    @GetMapping("/adapters/symbols")
    public ResponseEntity<Set<Symbol>> getSupportedSymbols() {
        if (adapterService == null) {
            return ResponseEntity.notFound().build();
        }
        
        return ResponseEntity.ok(adapterService.getAllSupportedSymbols());
    }
    
    /**
     * Get balances from all adapters
     */
    @GetMapping("/adapters/balances")
    public CompletableFuture<ResponseEntity<Map<String, List<TradingAdapter.Balance>>>> getBalances() {
        if (adapterService == null) {
            return CompletableFuture.completedFuture(ResponseEntity.notFound().build());
        }
        
        return adapterService.getAllBalances()
            .thenApply(balances -> ResponseEntity.ok(balances))
            .exceptionally(ex -> {
                log.error("Failed to get balances", ex);
                return ResponseEntity.internalServerError().build();
            });
    }
    
    /**
     * Get positions from all adapters
     */
    @GetMapping("/adapters/positions")
    public CompletableFuture<ResponseEntity<Map<String, List<TradingAdapter.Position>>>> getPositions() {
        if (adapterService == null) {
            return CompletableFuture.completedFuture(ResponseEntity.notFound().build());
        }
        
        return adapterService.getAllPositions()
            .thenApply(positions -> ResponseEntity.ok(positions))
            .exceptionally(ex -> {
                log.error("Failed to get positions", ex);
                return ResponseEntity.internalServerError().build();
            });
    }

    @GetMapping("/bus/deadletters/summary")
    public ResponseEntity<Map<String, Object>> deadLetterSummary() {
        if (deadLetterChannel == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> out = new HashMap<>();
        out.put("total", deadLetterChannel.totalCount());
        out.put("buffered", deadLetterChannel.currentSize());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/bus/deadletters")
    public ResponseEntity<List<?>> deadLetters(@RequestParam(defaultValue = "100") int limit) {
        if (deadLetterChannel == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(deadLetterChannel.latest(limit));
    }

    @GetMapping("/bus/metrics")
    public ResponseEntity<?> busMetrics() {
        if (eventBus == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(eventBus.metrics());
    }
}
