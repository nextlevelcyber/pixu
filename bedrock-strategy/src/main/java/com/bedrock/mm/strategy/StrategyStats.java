package com.bedrock.mm.strategy;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Strategy statistics
 */
@Data
public class StrategyStats {
    
    // Counters
    private final AtomicLong ticksProcessed = new AtomicLong(0);
    private final AtomicLong deltasProcessed = new AtomicLong(0);
    private final AtomicLong ordersPlaced = new AtomicLong(0);
    private final AtomicLong ordersRejected = new AtomicLong(0);
    private final AtomicLong ordersCancelled = new AtomicLong(0);
    private final AtomicLong fills = new AtomicLong(0);
    
    // Financial metrics
    private final DoubleAdder totalVolume = new DoubleAdder();
    private final DoubleAdder totalPnl = new DoubleAdder();
    private final DoubleAdder realizedPnl = new DoubleAdder();
    private final DoubleAdder unrealizedPnl = new DoubleAdder();
    private final DoubleAdder commission = new DoubleAdder();
    
    // Performance metrics
    private volatile double maxDrawdown = 0.0;
    private volatile double maxProfit = 0.0;
    private volatile double currentPosition = 0.0;
    private volatile double averageSpread = 0.0;
    private volatile double fillRate = 0.0;
    
    // Timing
    private volatile LocalDateTime startTime;
    private volatile LocalDateTime lastUpdateTime;
    private volatile long totalRunTimeMs = 0;
    
    // Risk metrics
    private volatile double sharpeRatio = 0.0;
    private volatile double volatility = 0.0;
    private volatile double var95 = 0.0; // Value at Risk 95%
    
    public StrategyStats() {
        this.startTime = LocalDateTime.now();
        this.lastUpdateTime = LocalDateTime.now();
    }
    
    // Counter methods
    public void incrementTicksProcessed() {
        ticksProcessed.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void incrementDeltasProcessed() {
        deltasProcessed.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void incrementOrdersPlaced() {
        ordersPlaced.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void incrementOrdersRejected() {
        ordersRejected.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void incrementOrdersCancelled() {
        ordersCancelled.incrementAndGet();
        updateLastUpdateTime();
    }
    
    public void incrementFills() {
        fills.incrementAndGet();
        updateFillRate();
        updateLastUpdateTime();
    }
    
    // Financial methods
    public void addVolume(BigDecimal volume) {
        totalVolume.add(volume.doubleValue());
        updateLastUpdateTime();
    }

    // Optimized method for hot path - avoids BigDecimal boxing
    public void addVolume(double volume) {
        totalVolume.add(volume);
        updateLastUpdateTime();
    }
    
    public void addPnl(double pnl) {
        totalPnl.add(pnl);
        updateMaxDrawdownAndProfit();
        updateLastUpdateTime();
    }
    
    public void addRealizedPnl(double pnl) {
        realizedPnl.add(pnl);
        updateLastUpdateTime();
    }
    
    public void setUnrealizedPnl(double pnl) {
        unrealizedPnl.reset();
        unrealizedPnl.add(pnl);
        updateLastUpdateTime();
    }
    
    public void addCommission(double commission) {
        this.commission.add(commission);
        updateLastUpdateTime();
    }
    
    // Getters for atomic values
    public long getTicksProcessed() {
        return ticksProcessed.get();
    }
    
    public long getDeltasProcessed() {
        return deltasProcessed.get();
    }
    
    public long getOrdersPlaced() {
        return ordersPlaced.get();
    }
    
    public long getOrdersRejected() {
        return ordersRejected.get();
    }
    
    public long getOrdersCancelled() {
        return ordersCancelled.get();
    }
    
    public long getFills() {
        return fills.get();
    }
    
    public double getTotalVolume() {
        return totalVolume.sum();
    }
    
    public double getTotalPnl() {
        return totalPnl.sum();
    }
    
    public double getRealizedPnl() {
        return realizedPnl.sum();
    }
    
    public double getUnrealizedPnl() {
        return unrealizedPnl.sum();
    }
    
    public double getCommission() {
        return commission.sum();
    }
    
    public double getNetPnl() {
        return getTotalPnl() - getCommission();
    }
    
    // Calculated metrics
    public double getOrderSuccessRate() {
        long placed = getOrdersPlaced();
        long rejected = getOrdersRejected();
        return placed > 0 ? (double) (placed - rejected) / placed : 0.0;
    }
    
    public double getAverageTradeSize() {
        long fillCount = getFills();
        return fillCount > 0 ? getTotalVolume() / fillCount : 0.0;
    }
    
    public long getUptimeMs() {
        return totalRunTimeMs + (startTime != null ? 
            java.time.Duration.between(startTime, LocalDateTime.now()).toMillis() : 0);
    }
    
    // Update methods
    // HOT PATH: Avoid LocalDateTime.now() allocation - just track timing without creating objects
    private void updateLastUpdateTime() {
        // Skip LocalDateTime allocation on hot path - only update for display/snapshot operations
        // lastUpdateTime = LocalDateTime.now(); // REMOVED: allocates on every tick
    }
    
    private void updateFillRate() {
        long placed = getOrdersPlaced();
        long filled = getFills();
        fillRate = placed > 0 ? (double) filled / placed : 0.0;
    }
    
    private void updateMaxDrawdownAndProfit() {
        double currentPnl = getTotalPnl();
        if (currentPnl > maxProfit) {
            maxProfit = currentPnl;
        }
        double drawdown = maxProfit - currentPnl;
        if (drawdown > maxDrawdown) {
            maxDrawdown = drawdown;
        }
    }
    
    /**
     * Reset all statistics
     */
    public void reset() {
        ticksProcessed.set(0);
        deltasProcessed.set(0);
        ordersPlaced.set(0);
        ordersRejected.set(0);
        ordersCancelled.set(0);
        fills.set(0);
        
        totalVolume.reset();
        totalPnl.reset();
        realizedPnl.reset();
        unrealizedPnl.reset();
        commission.reset();
        
        maxDrawdown = 0.0;
        maxProfit = 0.0;
        currentPosition = 0.0;
        averageSpread = 0.0;
        fillRate = 0.0;
        
        sharpeRatio = 0.0;
        volatility = 0.0;
        var95 = 0.0;
        
        startTime = LocalDateTime.now();
        lastUpdateTime = LocalDateTime.now();
        totalRunTimeMs = 0;
    }
    
    /**
     * Create a snapshot of current statistics
     */
    public StrategyStats snapshot() {
        StrategyStats snapshot = new StrategyStats();
        
        snapshot.ticksProcessed.set(this.getTicksProcessed());
        snapshot.deltasProcessed.set(this.getDeltasProcessed());
        snapshot.ordersPlaced.set(this.getOrdersPlaced());
        snapshot.ordersRejected.set(this.getOrdersRejected());
        snapshot.ordersCancelled.set(this.getOrdersCancelled());
        snapshot.fills.set(this.getFills());
        
        snapshot.totalVolume.add(this.getTotalVolume());
        snapshot.totalPnl.add(this.getTotalPnl());
        snapshot.realizedPnl.add(this.getRealizedPnl());
        snapshot.unrealizedPnl.add(this.getUnrealizedPnl());
        snapshot.commission.add(this.getCommission());
        
        snapshot.maxDrawdown = this.maxDrawdown;
        snapshot.maxProfit = this.maxProfit;
        snapshot.currentPosition = this.currentPosition;
        snapshot.averageSpread = this.averageSpread;
        snapshot.fillRate = this.fillRate;
        
        snapshot.sharpeRatio = this.sharpeRatio;
        snapshot.volatility = this.volatility;
        snapshot.var95 = this.var95;
        
        snapshot.startTime = this.startTime;
        snapshot.lastUpdateTime = this.lastUpdateTime;
        snapshot.totalRunTimeMs = this.getUptimeMs();
        
        return snapshot;
    }
}