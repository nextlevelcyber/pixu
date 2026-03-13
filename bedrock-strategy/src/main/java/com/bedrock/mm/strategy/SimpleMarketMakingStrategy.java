package com.bedrock.mm.strategy;

import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.md.BookDelta;
import com.bedrock.mm.md.MarketTick;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple market making strategy
 */
@Slf4j
public class SimpleMarketMakingStrategy implements Strategy {
    
    private static final String STRATEGY_NAME = "SimpleMarketMaking";
    private static final String STRATEGY_VERSION = "1.0.0";
    
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong orderSequence = new AtomicLong(0);
    
    private StrategyContext context;
    private StrategyParameters parameters;
    private StrategyStats stats;
    
    // Market state
    private volatile long lastBidPrice = 0;
    private volatile long lastAskPrice = 0;
    private volatile long lastTradePrice = 0;
    
    // Active orders
    private String activeBuyOrderId;
    private String activeSellOrderId;
    
    @Override
    public String getName() {
        return STRATEGY_NAME;
    }
    
    @Override
    public String getVersion() {
        return STRATEGY_VERSION;
    }
    
    @Override
    public void initialize(StrategyContext context) {
        this.context = context;
        this.parameters = createDefaultParameters();
        this.stats = new StrategyStats();
        
        context.logInfo("Initialized {} strategy v{}", getName(), getVersion());
    }
    
    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            context.logInfo("Starting {} strategy", getName());
            
            // Subscribe to market data for our symbols
            for (Symbol symbol : getSymbols()) {
                context.getMarketDataService().subscribeMarketTick(symbol, this::onMarketTick);
                context.getMarketDataService().subscribeBookDelta(symbol, this::onBookDelta);
            }
            
            context.logInfo("Started {} strategy", getName());
        }
    }
    
    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            context.logInfo("Stopping {} strategy", getName());
            
            // Cancel all active orders
            cancelAllOrders();
            
            // Unsubscribe from market data
            for (Symbol symbol : getSymbols()) {
                context.getMarketDataService().unsubscribeMarketTick(symbol);
                context.getMarketDataService().unsubscribeBookDelta(symbol);
            }
            
            context.logInfo("Stopped {} strategy", getName());
        }
    }
    
    @Override
    public boolean isRunning() {
        return running.get();
    }
    
    @Override
    public void onMarketTick(MarketTick tick) {
        if (!running.get()) return;
        
        try {
            lastTradePrice = tick.getPrice();
            stats.incrementTicksProcessed();
            
            // Update quotes based on new trade
            updateQuotes(tick.getSymbol());
            
        } catch (Exception e) {
            context.logError("Error processing market tick: {}", tick, e);
        }
    }
    
    @Override
    public void onBookDelta(BookDelta delta) {
        if (!running.get()) return;
        
        try {
            // Update best bid/ask based on book changes
            if (delta.getSide() == Side.BUY) {
                lastBidPrice = delta.getPrice();
            } else {
                lastAskPrice = delta.getPrice();
            }
            
            stats.incrementDeltasProcessed();
            
            // Update quotes based on new book state
            updateQuotes(delta.getSymbol());
            
        } catch (Exception e) {
            context.logError("Error processing book delta: {}", delta, e);
        }
    }
    
    @Override
    public void onOrderAck(OrderAck ack) {
        if (!running.get()) return;
        
        try {
            context.logInfo("Received order ack: {}", ack);
            
            if (ack.getStatus() == OrderAck.OrderStatus.NEW) {
                stats.incrementOrdersPlaced();
            } else if (ack.getStatus() == OrderAck.OrderStatus.REJECTED) {
                stats.incrementOrdersRejected();
                // Retry placing order after rejection
                retryOrder(ack);
            }
            
        } catch (Exception e) {
            context.logError("Error processing order ack: {}", ack, e);
        }
    }
    
    @Override
    public void onFill(Fill fill) {
        if (!running.get()) return;

        try {
            context.logInfo("Received fill: {}", fill);

            stats.incrementFills();
            stats.addVolume(fill.getFillQuantityAsDecimal());
            stats.addPnl(calculatePnl(fill));

            // Clear the filled order ID
            if (fill.getOrderId().equals(activeBuyOrderId)) {
                activeBuyOrderId = null;
            } else if (fill.getOrderId().equals(activeSellOrderId)) {
                activeSellOrderId = null;
            }

            // Place new quotes after fill
            updateQuotes(fill.getSymbol());

        } catch (Exception e) {
            context.logError("Error processing fill: {}", fill, e);
        }
    }
    
    @Override
    public Symbol[] getSymbols() {
        return new Symbol[] { Symbol.btcUsdt() };
    }
    
    @Override
    public StrategyParameters getParameters() {
        return parameters;
    }
    
    @Override
    public void updateParameters(StrategyParameters parameters) {
        this.parameters = parameters;
        context.logInfo("Updated strategy parameters: {}", parameters);
    }
    
    @Override
    public StrategyStats getStats() {
        return stats;
    }
    
    @Override
    public void reset() {
        stats = new StrategyStats();
        activeBuyOrderId = null;
        activeSellOrderId = null;
        lastBidPrice = 0;
        lastAskPrice = 0;
        lastTradePrice = 0;
        context.logInfo("Reset strategy state");
    }
    
    private void updateQuotes(Symbol symbol) {
        if (lastTradePrice == 0) return;

        double spread = parameters.getSpread();
        double quantity = parameters.getQuantity();

        // Fixed-point arithmetic (scale 1e-8) for all price calculations
        // Convert spread percentage to fixed-point: spread 0.001 = 0.1% = 100000 in scale 1e-8
        // Example: spread=0.001 -> spreadScaled = 100000 (represents 0.00100000 in 1e-8 scale)
        long spreadScaled = (long) (spread * 100_000_000L);
        long halfSpreadScaled = spreadScaled / 2;

        // Bid = lastTradePrice * (1 - spread/2) = lastTradePrice - lastTradePrice * halfSpread
        // Ask = lastTradePrice * (1 + spread/2) = lastTradePrice + lastTradePrice * halfSpread
        // All calculations use fixed-point long (no double multiplication)
        long bidPrice = lastTradePrice - (lastTradePrice * halfSpreadScaled / 100_000_000L);
        long askPrice = lastTradePrice + (lastTradePrice * halfSpreadScaled / 100_000_000L);

        double bidPriceDecimal = symbol.priceToDecimal(bidPrice);
        double askPriceDecimal = symbol.priceToDecimal(askPrice);
        double orderQtyDecimal = quantity;
        
        // Place buy order if not active
        if (activeBuyOrderId == null) {
            String orderId = context.getOrderManager()
                    .submitOrder(symbol.getName(), "BUY", bidPriceDecimal, orderQtyDecimal);
            if (orderId != null) {
                activeBuyOrderId = orderId;
                context.logInfo("Placed BUY order: {} @ {} qty {} => {}",
                        symbol.getName(), bidPriceDecimal, orderQtyDecimal, orderId);
                stats.incrementOrdersPlaced();
            } else {
                context.logWarn("Failed to place BUY order: {} @ {} qty {}",
                        symbol.getName(), bidPriceDecimal, orderQtyDecimal);
            }
        }
        
        // Place sell order if not active
        if (activeSellOrderId == null) {
            String orderId = context.getOrderManager()
                    .submitOrder(symbol.getName(), "SELL", askPriceDecimal, orderQtyDecimal);
            if (orderId != null) {
                activeSellOrderId = orderId;
                context.logInfo("Placed SELL order: {} @ {} qty {} => {}",
                        symbol.getName(), askPriceDecimal, orderQtyDecimal, orderId);
                stats.incrementOrdersPlaced();
            } else {
                context.logWarn("Failed to place SELL order: {} @ {} qty {}",
                        symbol.getName(), askPriceDecimal, orderQtyDecimal);
            }
        }
    }
    
    private void cancelAllOrders() {
        if (activeBuyOrderId != null) {
            boolean success = context.getOrderManager().cancelOrder(activeBuyOrderId);
            context.logInfo("Cancel BUY order {} => {}", activeBuyOrderId, success);
            activeBuyOrderId = null;
        }
        if (activeSellOrderId != null) {
            boolean success = context.getOrderManager().cancelOrder(activeSellOrderId);
            context.logInfo("Cancel SELL order {} => {}", activeSellOrderId, success);
            activeSellOrderId = null;
        }
    }
    
    private void retryOrder(OrderAck ack) {
        // Simple retry logic - in real implementation would be more sophisticated
        context.logWarn("Retrying order after rejection: {}", ack.getRejectReason());
    }
    
    private double calculatePnl(Fill fill) {
        // Fixed-point PnL calculation: price * quantity
        // Price and quantity are already fixed-point (scale 1e-8)
        // Result is in quote currency (e.g., USDT)
        long notional = (fill.getFillPrice() * fill.getFillQuantity()) / 100_000_000L;
        double notionalDecimal = notional / 100_000_000.0;
        return fill.getSide() == Side.BUY ? -notionalDecimal : notionalDecimal;
    }
    
    private String generateClientOrderId() {
        // Zero-allocation: concatenate without String.format
        long ts = System.currentTimeMillis();
        long seq = orderSequence.incrementAndGet();
        return STRATEGY_NAME + "_" + ts + "_" + seq;
    }
    
    private StrategyParameters createDefaultParameters() {
        StrategyParameters params = new StrategyParameters();
        params.setSpread(0.001); // 0.1% spread
        params.setQuantity(1.0); // 1 unit quantity
        params.setMaxPosition(10.0); // Max 10 units position
        params.setRiskLimit(1000.0); // $1000 risk limit
        return params;
    }
}