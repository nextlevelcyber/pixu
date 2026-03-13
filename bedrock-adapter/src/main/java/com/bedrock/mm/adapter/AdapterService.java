package com.bedrock.mm.adapter;

import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.monitor.MonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.Optional;

/**
 * Service for managing trading adapters
 */
@Service
@ConditionalOnProperty(name = "bedrock.adapter.enabled", havingValue = "true", matchIfMissing = false)
public class AdapterService {
    
    private static final Logger log = LoggerFactory.getLogger(AdapterService.class);

    private final AdapterConfig config;
    
    @Autowired(required = false)
    private MonitorService monitorService;
    
    private final Map<String, TradingAdapter> adapters = new ConcurrentHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    private ScheduledExecutorService scheduler;
    private ExecutorService executorService;

    @Autowired
    public AdapterService(AdapterConfig config) {
        this.config = config;
    }
    
    public void initialize() {
        if (!config.isEnabled()) {
            log.info("Adapter service is disabled");
            return;
        }
        
        if (initialized.compareAndSet(false, true)) {
            try {
                // Create thread pools
                scheduler = Executors.newScheduledThreadPool(2, r -> {
                    Thread t = new Thread(r, "adapter-scheduler");
                    t.setDaemon(true);
                    return t;
                });
                
                executorService = Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "adapter-worker");
                    t.setDaemon(true);
                    return t;
                });
                
                // Initialize adapters
                initializeAdapters();
                
                // Start monitoring
                startMonitoring();
                
                log.info("Adapter service initialized with {} adapters", adapters.size());
                
            } catch (Exception e) {
                log.error("Failed to initialize adapter service", e);
                throw new RuntimeException("Adapter service initialization failed", e);
            }
        }
    }
    
    @PreDestroy
    public void shutdown() {
        if (running.compareAndSet(true, false)) {
            log.info("Shutting down adapter service...");
            
            // Disconnect all adapters
            CompletableFuture<?>[] disconnectFutures = adapters.values().stream()
                .map(adapter -> adapter.disconnect().exceptionally(ex -> {
                    log.warn("Failed to disconnect adapter {}: {}", 
                        adapter.getName(), ex.getMessage());
                    return null;
                }))
                .toArray(CompletableFuture[]::new);
            
            try {
                CompletableFuture.allOf(disconnectFutures)
                    .get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("Timeout waiting for adapters to disconnect", e);
            }
            
            // Shutdown thread pools
            shutdownExecutor(scheduler, "scheduler");
            shutdownExecutor(executorService, "executor");
            
            adapters.clear();
            log.info("Adapter service shutdown complete");
        }
    }
    
    /**
     * Start all adapters
     */
    public CompletableFuture<Void> startAll() {
        if (!initialized.get()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Service not initialized"));
        }
        
        if (running.compareAndSet(false, true)) {
            log.info("Starting all adapters...");
            
            CompletableFuture<?>[] connectFutures = adapters.values().stream()
                .map(adapter -> adapter.connect().exceptionally(ex -> {
                    log.error("Failed to connect adapter {}: {}", 
                        adapter.getName(), ex.getMessage());
                    return null;
                }))
                .toArray(CompletableFuture[]::new);
            
            return CompletableFuture.allOf(connectFutures)
                .thenRun(() -> log.info("All adapters started"));
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Stop all adapters
     */
    public CompletableFuture<Void> stopAll() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping all adapters...");
            
            CompletableFuture<?>[] disconnectFutures = adapters.values().stream()
                .map(adapter -> adapter.disconnect().exceptionally(ex -> {
                    log.warn("Failed to disconnect adapter {}: {}", 
                        adapter.getName(), ex.getMessage());
                    return null;
                }))
                .toArray(CompletableFuture[]::new);
            
            return CompletableFuture.allOf(disconnectFutures)
                .thenRun(() -> log.info("All adapters stopped"));
        }
        
        return CompletableFuture.completedFuture(null);
    }
    
    /**
     * Get adapter by name
     */
    public Optional<TradingAdapter> getAdapter(String name) {
        return Optional.ofNullable(adapters.get(name));
    }
    
    /**
     * Get all adapters
     */
    public Collection<TradingAdapter> getAllAdapters() {
        return new ArrayList<>(adapters.values());
    }
    
    /**
     * Get adapter names
     */
    public Set<String> getAdapterNames() {
        return new HashSet<>(adapters.keySet());
    }
    
    /**
     * Check if any adapter is connected
     */
    public boolean isAnyAdapterConnected() {
        return adapters.values().stream().anyMatch(TradingAdapter::isConnected);
    }
    
    /**
     * Get connected adapters
     */
    public List<TradingAdapter> getConnectedAdapters() {
        return adapters.values().stream()
            .filter(TradingAdapter::isConnected)
            .collect(Collectors.toList());
    }
    
    /**
     * Get adapter statistics
     */
    public Map<String, AdapterStats> getAdapterStats() {
        Map<String, AdapterStats> stats = new HashMap<>();
        adapters.forEach((name, adapter) -> stats.put(name, adapter.getStats()));
        return stats;
    }
    
    /**
     * Get supported symbols across all adapters
     */
    public Set<Symbol> getAllSupportedSymbols() {
        Set<Symbol> symbols = new HashSet<>();
        adapters.values().forEach(adapter -> symbols.addAll(adapter.getSupportedSymbols()));
        return symbols;
    }
    
    /**
     * Get adapters supporting a specific symbol
     */
    public List<TradingAdapter> getAdaptersForSymbol(Symbol symbol) {
        return adapters.values().stream()
            .filter(adapter -> adapter.getSupportedSymbols().contains(symbol))
            .collect(Collectors.toList());
    }
    
    /**
     * Submit order to best available adapter
     */
    public CompletableFuture<TradingAdapter.OrderResponse> submitOrder(
            TradingAdapter.OrderRequest request) {
        
        List<TradingAdapter> availableAdapters = getAdaptersForSymbol(request.symbol())
            .stream()
            .filter(TradingAdapter::isConnected)
            .collect(Collectors.toList());
        
        if (availableAdapters.isEmpty()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("No connected adapter available for symbol: " + 
                    request.symbol()));
        }
        
        // Use first available adapter (could implement load balancing here)
        TradingAdapter adapter = availableAdapters.get(0);
        return adapter.submitOrder(request);
    }
    
    /**
     * Cancel order on appropriate adapter
     */
    public CompletableFuture<TradingAdapter.CancelResponse> cancelOrder(
            String adapterId, String orderId) {
        
        TradingAdapter adapter = adapters.get(adapterId);
        if (adapter == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Adapter not found: " + adapterId));
        }
        
        if (!adapter.isConnected()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("Adapter not connected: " + adapterId));
        }
        
        return adapter.cancelOrder(orderId);
    }
    
    /**
     * Get order status from appropriate adapter
     */
    public CompletableFuture<TradingAdapter.OrderStatus> getOrderStatus(
            String adapterId, String orderId) {
        
        TradingAdapter adapter = adapters.get(adapterId);
        if (adapter == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("Adapter not found: " + adapterId));
        }
        
        return adapter.getOrderStatus(orderId);
    }
    
    /**
     * Get balances from all connected adapters
     */
    public CompletableFuture<Map<String, List<TradingAdapter.Balance>>> getAllBalances() {
        Map<String, CompletableFuture<List<TradingAdapter.Balance>>> futures = new HashMap<>();
        
        adapters.forEach((name, adapter) -> {
            if (adapter.isConnected()) {
                futures.put(name, adapter.getBalances());
            }
        });
        
        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                Map<String, List<TradingAdapter.Balance>> results = new HashMap<>();
                futures.forEach((name, future) -> {
                    try {
                        results.put(name, future.get());
                    } catch (Exception e) {
                        log.warn("Failed to get balances from adapter {}: {}", 
                            name, e.getMessage());
                        results.put(name, Collections.emptyList());
                    }
                });
                return results;
            });
    }
    
    /**
     * Get positions from all connected adapters
     */
    public CompletableFuture<Map<String, List<TradingAdapter.Position>>> getAllPositions() {
        Map<String, CompletableFuture<List<TradingAdapter.Position>>> futures = new HashMap<>();
        
        adapters.forEach((name, adapter) -> {
            if (adapter.isConnected()) {
                futures.put(name, adapter.getPositions());
            }
        });
        
        return CompletableFuture.allOf(futures.values().toArray(new CompletableFuture[0]))
            .thenApply(v -> {
                Map<String, List<TradingAdapter.Position>> results = new HashMap<>();
                futures.forEach((name, future) -> {
                    try {
                        results.put(name, future.get());
                    } catch (Exception e) {
                        log.warn("Failed to get positions from adapter {}: {}", 
                            name, e.getMessage());
                        results.put(name, Collections.emptyList());
                    }
                });
                return results;
            });
    }
    
    private void initializeAdapters() {
        // Create adapters based on configuration type.
        // Supports comma-separated types, e.g., "binance,bitget".
        String type = Optional.ofNullable(config.getType()).orElse("simulation");
        String[] types = Arrays.stream(type.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);

        for (String t : types) {
            TradingAdapter adapter = null;
            String key = t.toLowerCase();
            switch (key) {
                case "simulation":
                    adapter = new SimulationTradingAdapter();
                    break;
                case "binance":
                    adapter = new BinanceTradingAdapter();
                    break;
                case "bitget":
                    adapter = new BitgetTradingAdapter();
                    break;
                default:
                    log.warn("Unknown adapter type: {} — skipping", t);
            }
            if (adapter != null) {
                adapter.initialize(config);
                adapters.put(key, adapter);
            }
        }

        log.info("Initialized {} adapters: {}", adapters.size(), adapters.keySet());
    }
    
    private void startMonitoring() {
        // Monitor adapter health
        scheduler.scheduleAtFixedRate(() -> {
            try {
                monitorAdapterHealth();
            } catch (Exception e) {
                log.warn("Error in adapter health monitoring", e);
            }
        }, 10, 30, TimeUnit.SECONDS);
        
        // Report adapter statistics
        scheduler.scheduleAtFixedRate(() -> {
            try {
                reportAdapterStats();
            } catch (Exception e) {
                log.warn("Error in adapter stats reporting", e);
            }
        }, 30, 60, TimeUnit.SECONDS);
    }
    
    private void monitorAdapterHealth() {
        for (Map.Entry<String, TradingAdapter> entry : adapters.entrySet()) {
            String name = entry.getKey();
            TradingAdapter adapter = entry.getValue();
            
            boolean connected = adapter.isConnected();
            AdapterStats stats = adapter.getStats();
            
            // Record metrics if monitoring service is available
            if (monitorService != null) {
                monitorService.gauge("adapter.connected." + name).setValue(connected ? 1.0 : 0.0);
                monitorService.gauge("adapter.requests.total." + name).setValue(stats.getTotalRequests());
                monitorService.gauge("adapter.requests.success_rate." + name).setValue(stats.getRequestSuccessRate());
                monitorService.gauge("adapter.orders.submitted." + name).setValue(stats.getOrdersSubmitted());
                monitorService.gauge("adapter.orders.filled." + name).setValue(stats.getOrdersFilled());
                monitorService.gauge("adapter.latency.avg." + name).setValue(stats.getAverageLatency());
            }
            
            if (!connected && running.get()) {
                log.warn("Adapter {} is not connected", name);
                
                // Attempt reconnection
                adapter.connect().exceptionally(ex -> {
                    log.error("Failed to reconnect adapter {}: {}", name, ex.getMessage());
                    return null;
                });
            }
        }
    }
    
    private void reportAdapterStats() {
        for (Map.Entry<String, TradingAdapter> entry : adapters.entrySet()) {
            String name = entry.getKey();
            TradingAdapter adapter = entry.getValue();
            AdapterStats stats = adapter.getStats();
            
            log.info("Adapter {} stats: connected={}, requests={}, success_rate={:.2f}%, " +
                    "orders_submitted={}, orders_filled={}, avg_latency={:.2f}ms",
                name, adapter.isConnected(), stats.getTotalRequests(),
                stats.getRequestSuccessRate() * 100, stats.getOrdersSubmitted(),
                stats.getOrdersFilled(), stats.getAverageLatency());
        }
    }
    
    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Forcing shutdown of {} executor", name);
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                executor.shutdownNow();
            }
        }
    }
}