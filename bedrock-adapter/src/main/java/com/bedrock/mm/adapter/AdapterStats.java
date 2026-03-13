package com.bedrock.mm.adapter;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Trading adapter statistics
 */
@Data
public class AdapterStats {
    
    // Connection stats
    private final AtomicLong connectAttempts = new AtomicLong(0);
    private final AtomicLong connectSuccesses = new AtomicLong(0);
    private final AtomicLong connectFailures = new AtomicLong(0);
    private final AtomicLong disconnects = new AtomicLong(0);
    private final AtomicLong reconnects = new AtomicLong(0);
    
    // Request stats
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong timeoutRequests = new AtomicLong(0);
    private final AtomicLong rateLimitedRequests = new AtomicLong(0);
    
    // Order stats
    private final AtomicLong ordersSubmitted = new AtomicLong(0);
    private final AtomicLong ordersAccepted = new AtomicLong(0);
    private final AtomicLong ordersRejected = new AtomicLong(0);
    private final AtomicLong ordersCancelled = new AtomicLong(0);
    private final AtomicLong ordersFilled = new AtomicLong(0);
    private final AtomicLong ordersPartiallyFilled = new AtomicLong(0);
    
    // Trade stats
    private final AtomicLong tradesReceived = new AtomicLong(0);
    private final DoubleAdder totalVolume = new DoubleAdder();
    private final DoubleAdder totalNotional = new DoubleAdder();
    private final DoubleAdder totalCommission = new DoubleAdder();
    
    // WebSocket stats
    private final AtomicLong wsMessagesReceived = new AtomicLong(0);
    private final AtomicLong wsMessagesSent = new AtomicLong(0);
    private final AtomicLong wsErrors = new AtomicLong(0);
    private final AtomicLong wsReconnects = new AtomicLong(0);
    
    // Latency stats
    private final DoubleAdder totalLatency = new DoubleAdder();
    private final AtomicLong latencyMeasurements = new AtomicLong(0);
    private volatile double minLatency = Double.MAX_VALUE;
    private volatile double maxLatency = 0.0;
    
    // Error stats
    private final AtomicLong networkErrors = new AtomicLong(0);
    private final AtomicLong authErrors = new AtomicLong(0);
    private final AtomicLong serverErrors = new AtomicLong(0);
    private final AtomicLong clientErrors = new AtomicLong(0);
    
    // Timing
    private volatile LocalDateTime startTime;
    private volatile LocalDateTime lastUpdateTime;
    private volatile LocalDateTime lastConnectTime;
    private volatile LocalDateTime lastDisconnectTime;
    
    // Status
    private volatile boolean connected = false;
    private volatile String lastError;
    private volatile long uptimeMs = 0;
    
    public AdapterStats() {
        this.startTime = LocalDateTime.now();
        this.lastUpdateTime = LocalDateTime.now();
    }
    
    // Connection methods
    public void recordConnectAttempt() {
        connectAttempts.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordConnectSuccess() {
        connectSuccesses.incrementAndGet();
        connected = true;
        lastConnectTime = LocalDateTime.now();
        updateLastUpdateTime();
    }
    
    public void recordConnectFailure() {
        connectFailures.incrementAndGet();
        connected = false;
        updateLastUpdateTime();
    }
    
    public void recordDisconnect() {
        disconnects.incrementAndGet();
        connected = false;
        lastDisconnectTime = LocalDateTime.now();
        updateLastUpdateTime();
    }
    
    public void recordReconnect() {
        reconnects.incrementAndGet();
        updateLastUpdateTime();
    }
    
    // Request methods
    public void recordRequest() {
        totalRequests.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordSuccessfulRequest() {
        successfulRequests.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordFailedRequest() {
        failedRequests.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordTimeoutRequest() {
        timeoutRequests.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordRateLimitedRequest() {
        rateLimitedRequests.incrementAndGet();
        updateLastUpdateTime();
    }
    
    // Order methods
    public void recordOrderSubmitted() {
        ordersSubmitted.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordOrderAccepted() {
        ordersAccepted.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordOrderRejected() {
        ordersRejected.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordOrderCancelled() {
        ordersCancelled.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordOrderFilled() {
        ordersFilled.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordOrderPartiallyFilled() {
        ordersPartiallyFilled.incrementAndGet();
        updateLastUpdateTime();
    }
    
    // Trade methods
    public void recordTrade(double volume, double notional, double commission) {
        tradesReceived.incrementAndGet();
        totalVolume.add(volume);
        totalNotional.add(notional);
        totalCommission.add(commission);
        updateLastUpdateTime();
    }
    
    // WebSocket methods
    public void recordWsMessageReceived() {
        wsMessagesReceived.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordWsMessageSent() {
        wsMessagesSent.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordWsError() {
        wsErrors.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordWsReconnect() {
        wsReconnects.incrementAndGet();
        updateLastUpdateTime();
    }
    
    // Latency methods
    public void recordLatency(double latencyMs) {
        totalLatency.add(latencyMs);
        latencyMeasurements.incrementAndGet();
        
        if (latencyMs < minLatency) {
            minLatency = latencyMs;
        }
        if (latencyMs > maxLatency) {
            maxLatency = latencyMs;
        }
        
        updateLastUpdateTime();
    }
    
    // Error methods
    public void recordNetworkError() {
        networkErrors.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordAuthError() {
        authErrors.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordServerError() {
        serverErrors.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void recordClientError() {
        clientErrors.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void setLastError(String error) {
        this.lastError = error;
        updateLastUpdateTime();
    }
    
    // Getters for atomic values
    public long getConnectAttempts() { return connectAttempts.get(); }
    public long getConnectSuccesses() { return connectSuccesses.get(); }
    public long getConnectFailures() { return connectFailures.get(); }
    public long getDisconnects() { return disconnects.get(); }
    public long getReconnects() { return reconnects.get(); }
    
    public long getTotalRequests() { return totalRequests.get(); }
    public long getSuccessfulRequests() { return successfulRequests.get(); }
    public long getFailedRequests() { return failedRequests.get(); }
    public long getTimeoutRequests() { return timeoutRequests.get(); }
    public long getRateLimitedRequests() { return rateLimitedRequests.get(); }
    
    public long getOrdersSubmitted() { return ordersSubmitted.get(); }
    public long getOrdersAccepted() { return ordersAccepted.get(); }
    public long getOrdersRejected() { return ordersRejected.get(); }
    public long getOrdersCancelled() { return ordersCancelled.get(); }
    public long getOrdersFilled() { return ordersFilled.get(); }
    public long getOrdersPartiallyFilled() { return ordersPartiallyFilled.get(); }
    
    public long getTradesReceived() { return tradesReceived.get(); }
    public double getTotalVolume() { return totalVolume.sum(); }
    public double getTotalNotional() { return totalNotional.sum(); }
    public double getTotalCommission() { return totalCommission.sum(); }
    
    public long getWsMessagesReceived() { return wsMessagesReceived.get(); }
    public long getWsMessagesSent() { return wsMessagesSent.get(); }
    public long getWsErrors() { return wsErrors.get(); }
    public long getWsReconnects() { return wsReconnects.get(); }
    
    public long getNetworkErrors() { return networkErrors.get(); }
    public long getAuthErrors() { return authErrors.get(); }
    public long getServerErrors() { return serverErrors.get(); }
    public long getClientErrors() { return clientErrors.get(); }
    
    // Calculated metrics
    public double getConnectionSuccessRate() {
        long attempts = getConnectAttempts();
        return attempts > 0 ? (double) getConnectSuccesses() / attempts : 0.0;
    }
    
    public double getRequestSuccessRate() {
        long total = getTotalRequests();
        return total > 0 ? (double) getSuccessfulRequests() / total : 0.0;
    }
    
    public double getOrderSuccessRate() {
        long submitted = getOrdersSubmitted();
        return submitted > 0 ? (double) getOrdersAccepted() / submitted : 0.0;
    }
    
    public double getAverageLatency() {
        long measurements = latencyMeasurements.get();
        return measurements > 0 ? totalLatency.sum() / measurements : 0.0;
    }
    
    public double getAverageTradeSize() {
        long trades = getTradesReceived();
        return trades > 0 ? getTotalVolume() / trades : 0.0;
    }
    
    public long getCurrentUptimeMs() {
        if (startTime == null) return 0;
        return java.time.Duration.between(startTime, LocalDateTime.now()).toMillis();
    }
    
    private void updateLastUpdateTime() {
        lastUpdateTime = LocalDateTime.now();
    }
    
    /**
     * Reset all statistics
     */
    public void reset() {
        connectAttempts.set(0);
        connectSuccesses.set(0);
        connectFailures.set(0);
        disconnects.set(0);
        reconnects.set(0);
        
        totalRequests.set(0);
        successfulRequests.set(0);
        failedRequests.set(0);
        timeoutRequests.set(0);
        rateLimitedRequests.set(0);
        
        ordersSubmitted.set(0);
        ordersAccepted.set(0);
        ordersRejected.set(0);
        ordersCancelled.set(0);
        ordersFilled.set(0);
        ordersPartiallyFilled.set(0);
        
        tradesReceived.set(0);
        totalVolume.reset();
        totalNotional.reset();
        totalCommission.reset();
        
        wsMessagesReceived.set(0);
        wsMessagesSent.set(0);
        wsErrors.set(0);
        wsReconnects.set(0);
        
        totalLatency.reset();
        latencyMeasurements.set(0);
        minLatency = Double.MAX_VALUE;
        maxLatency = 0.0;
        
        networkErrors.set(0);
        authErrors.set(0);
        serverErrors.set(0);
        clientErrors.set(0);
        
        startTime = LocalDateTime.now();
        lastUpdateTime = LocalDateTime.now();
        lastConnectTime = null;
        lastDisconnectTime = null;
        lastError = null;
        uptimeMs = 0;
    }
}