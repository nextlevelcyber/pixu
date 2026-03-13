package com.bedrock.mm.adapter;

import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Simulation trading adapter for paper trading
 */
public class SimulationTradingAdapter implements TradingAdapter {
    private static final Logger log = LoggerFactory.getLogger(SimulationTradingAdapter.class);
    
    private static final String ADAPTER_NAME = "Simulation";
    private static final String ADAPTER_VERSION = "1.0.0";
    
    private AdapterConfig config;
    private final AdapterStats stats = new AdapterStats();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong orderIdSequence = new AtomicLong(0);
    private final AtomicLong tradeIdSequence = new AtomicLong(0);
    
    // Simulation state
    private final Map<String, Order> orders = new ConcurrentHashMap<>();
    private final Map<String, Balance> balances = new ConcurrentHashMap<>();
    private final Map<Symbol, Position> positions = new ConcurrentHashMap<>();
    private final List<Trade> tradeHistory = new CopyOnWriteArrayList<>();
    
    // Event handlers
    private OrderUpdateHandler orderUpdateHandler;
    private TradeUpdateHandler tradeUpdateHandler;
    private BalanceUpdateHandler balanceUpdateHandler;
    private PositionUpdateHandler positionUpdateHandler;
    
    // Simulation parameters
    private final Random random = new Random();
    private ScheduledExecutorService scheduler;
    
    // Market simulation
    private final Map<Symbol, BigDecimal> currentPrices = new ConcurrentHashMap<>();
    private final Map<Symbol, BigDecimal> volatilities = new ConcurrentHashMap<>();
    
    @Override
    public String getName() {
        return ADAPTER_NAME;
    }
    
    @Override
    public String getVersion() {
        return ADAPTER_VERSION;
    }
    
    @Override
    public void initialize(AdapterConfig config) {
        this.config = config;
        
        // Initialize default balances
        initializeBalances();
        
        // Initialize market prices
        initializeMarketPrices();
        
        // Create scheduler for simulation
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "simulation-adapter");
            t.setDaemon(true);
            return t;
        });
        
        log.info("Initialized {} adapter v{}", getName(), getVersion());
    }
    
    @Override
    public CompletableFuture<Void> connect() {
        return CompletableFuture.runAsync(() -> {
            stats.recordConnectAttempt();
            
            try {
                // Simulate connection delay
                Thread.sleep(100 + random.nextInt(200));
                
                if (random.nextDouble() > 0.95) { // 5% failure rate
                    stats.recordConnectFailure();
                    throw new RuntimeException("Simulated connection failure");
                }
                
                connected.set(true);
                stats.recordConnectSuccess();
                
                // Start market simulation
                startMarketSimulation();
                
                log.info("Connected to simulation adapter");
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stats.recordConnectFailure();
                throw new RuntimeException("Connection interrupted", e);
            } catch (Exception e) {
                stats.recordConnectFailure();
                throw new RuntimeException("Connection failed", e);
            }
        });
    }
    
    @Override
    public CompletableFuture<Void> disconnect() {
        return CompletableFuture.runAsync(() -> {
            connected.set(false);
            stats.recordDisconnect();
            
            // Stop market simulation
            if (scheduler != null) {
                scheduler.shutdown();
            }
            
            log.info("Disconnected from simulation adapter");
        });
    }
    
    @Override
    public boolean isConnected() {
        return connected.get();
    }
    
    @Override
    public CompletableFuture<OrderResponse> submitOrder(OrderRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            stats.recordRequest();
            stats.recordOrderSubmitted();
            
            if (!isConnected()) {
                stats.recordFailedRequest();
                stats.recordOrderRejected();
                return new OrderResponse(
                    null, request.clientOrderId(), 
                    OrderStatus.Status.REJECTED, 
                    "Not connected", System.currentTimeMillis()
                );
            }
            
            // Simulate processing delay
            try {
                Thread.sleep(10 + random.nextInt(50));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            // Validate order
            String validationError = validateOrder(request);
            if (validationError != null) {
                stats.recordFailedRequest();
                stats.recordOrderRejected();
                return new OrderResponse(
                    null, request.clientOrderId(),
                    OrderStatus.Status.REJECTED,
                    validationError, System.currentTimeMillis()
                );
            }
            
            // Create order
            String orderId = generateOrderId();
            long priceScaled = request.price();
            long qtyScaled = request.quantity();
            BigDecimal priceDec = BigDecimal.valueOf(priceScaled).divide(BigDecimal.valueOf(100_000_000));
            BigDecimal qtyDec = BigDecimal.valueOf(qtyScaled).divide(BigDecimal.valueOf(100_000_000));
            Order order = new Order(
                orderId, request.clientOrderId(), request.symbol(),
                request.side(), request.type(), priceDec, qtyDec,
                BigDecimal.ZERO, OrderStatus.Status.NEW,
                System.currentTimeMillis(), System.currentTimeMillis()
            );
            
            orders.put(orderId, order);
            stats.recordSuccessfulRequest();
            stats.recordOrderAccepted();
            
            // Send order update
            if (orderUpdateHandler != null) {
                OrderUpdate update = new OrderUpdate(
                    orderId, request.clientOrderId(), request.symbol(),
                    request.side(), request.type(), priceDec, qtyDec,
                    BigDecimal.ZERO, BigDecimal.ZERO, OrderStatus.Status.NEW,
                    null, System.currentTimeMillis()
                );
                orderUpdateHandler.onOrderUpdate(update);
            }
            
            // Schedule order processing
            scheduleOrderProcessing(order);
            
            return new OrderResponse(
                orderId, request.clientOrderId(),
                OrderStatus.Status.NEW, "Order accepted",
                System.currentTimeMillis()
            );
        });
    }
    
    @Override
    public CompletableFuture<CancelResponse> cancelOrder(String orderId) {
        return CompletableFuture.supplyAsync(() -> {
            stats.recordRequest();
            
            Order order = orders.get(orderId);
            if (order == null) {
                stats.recordFailedRequest();
                return new CancelResponse(orderId, false, "Order not found", System.currentTimeMillis());
            }
            
            if (order.status() == OrderStatus.Status.FILLED || 
                order.status() == OrderStatus.Status.CANCELED) {
                stats.recordFailedRequest();
                return new CancelResponse(orderId, false, "Order cannot be cancelled", System.currentTimeMillis());
            }
            
            // Update order status
            Order cancelledOrder = new Order(
                order.orderId(), order.clientOrderId(), order.symbol(),
                order.side(), order.type(), order.price(), order.quantity(),
                order.filledQuantity(), OrderStatus.Status.CANCELED,
                order.createTime(), System.currentTimeMillis()
            );
            
            orders.put(orderId, cancelledOrder);
            stats.recordSuccessfulRequest();
            stats.recordOrderCancelled();
            
            // Send order update
            if (orderUpdateHandler != null) {
                OrderUpdate update = new OrderUpdate(
                    orderId, order.clientOrderId(), order.symbol(),
                    order.side(), order.type(), order.price(), order.quantity(),
                    order.filledQuantity(), BigDecimal.ZERO, OrderStatus.Status.CANCELED,
                    null, System.currentTimeMillis()
                );
                orderUpdateHandler.onOrderUpdate(update);
            }
            
            return new CancelResponse(orderId, true, "Order cancelled", System.currentTimeMillis());
        });
    }
    
    @Override
    public CompletableFuture<CancelResponse> cancelAllOrders(Symbol symbol) {
        return CompletableFuture.supplyAsync(() -> {
            List<String> cancelledOrders = new ArrayList<>();
            
            for (Order order : orders.values()) {
                if (order.symbol().equals(symbol) && 
                    order.status() == OrderStatus.Status.NEW || 
                    order.status() == OrderStatus.Status.PARTIALLY_FILLED) {
                    
                    cancelOrder(order.orderId());
                    cancelledOrders.add(order.orderId());
                }
            }
            
            return new CancelResponse(
                null, true, 
                "Cancelled " + cancelledOrders.size() + " orders", 
                System.currentTimeMillis()
            );
        });
    }
    
    @Override
    public CompletableFuture<OrderStatus> getOrderStatus(String orderId) {
        return CompletableFuture.supplyAsync(() -> {
            stats.recordRequest();
            
            Order order = orders.get(orderId);
            if (order == null) {
                stats.recordFailedRequest();
                return null;
            }
            
            stats.recordSuccessfulRequest();
            
            return new OrderStatus(
                order.orderId(), order.clientOrderId(), order.symbol(),
                order.side(), order.type(), order.price(), order.quantity(),
                order.filledQuantity(), calculateAvgFillPrice(order),
                order.status(), order.createTime(), order.updateTime()
            );
        });
    }
    
    @Override
    public CompletableFuture<List<Balance>> getBalances() {
        return CompletableFuture.supplyAsync(() -> {
            stats.recordRequest();
            stats.recordSuccessfulRequest();
            return new ArrayList<>(balances.values());
        });
    }
    
    @Override
    public CompletableFuture<List<Position>> getPositions() {
        return CompletableFuture.supplyAsync(() -> {
            stats.recordRequest();
            stats.recordSuccessfulRequest();
            return new ArrayList<>(positions.values());
        });
    }
    
    @Override
    public CompletableFuture<List<Order>> getOpenOrders(Symbol symbol) {
        return CompletableFuture.supplyAsync(() -> {
            stats.recordRequest();
            stats.recordSuccessfulRequest();
            
            return orders.values().stream()
                .filter(order -> order.symbol().equals(symbol))
                .filter(order -> order.status() == OrderStatus.Status.NEW || 
                               order.status() == OrderStatus.Status.PARTIALLY_FILLED)
                .collect(Collectors.toList());
        });
    }
    
    @Override
    public CompletableFuture<List<Trade>> getTradeHistory(Symbol symbol, long fromTime, long toTime) {
        return CompletableFuture.supplyAsync(() -> {
            stats.recordRequest();
            stats.recordSuccessfulRequest();
            
            return tradeHistory.stream()
                .filter(trade -> trade.symbol().equals(symbol))
                .filter(trade -> trade.timestamp() >= fromTime && trade.timestamp() <= toTime)
                .collect(Collectors.toList());
        });
    }
    
    @Override
    public void subscribeOrderUpdates(OrderUpdateHandler handler) {
        this.orderUpdateHandler = handler;
    }
    
    @Override
    public void subscribeTradeUpdates(TradeUpdateHandler handler) {
        this.tradeUpdateHandler = handler;
    }
    
    @Override
    public void subscribeBalanceUpdates(BalanceUpdateHandler handler) {
        this.balanceUpdateHandler = handler;
    }
    
    @Override
    public void subscribePositionUpdates(PositionUpdateHandler handler) {
        this.positionUpdateHandler = handler;
    }
    
    @Override
    public List<Symbol> getSupportedSymbols() {
        return Arrays.asList(
            Symbol.btcUsdt(),
            Symbol.ethUsdt(),
            Symbol.of("ADAUSDT"),
            Symbol.of("DOTUSDT"),
            Symbol.of("LINKUSDT")
        );
    }
    
    @Override
    public AdapterStats getStats() {
        return stats;
    }
    
    private void initializeBalances() {
        // Initialize with some default balances
        balances.put("USDT", new Balance("USDT", 
            new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("100000"), 
            System.currentTimeMillis()));
        balances.put("BTC", new Balance("BTC", 
            new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("10"), 
            System.currentTimeMillis()));
        balances.put("ETH", new Balance("ETH", 
            new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"), 
            System.currentTimeMillis()));
    }
    
    private void initializeMarketPrices() {
        currentPrices.put(Symbol.btcUsdt(), new BigDecimal("50000"));
        currentPrices.put(Symbol.ethUsdt(), new BigDecimal("3000"));
        currentPrices.put(Symbol.of("ADAUSDT"), new BigDecimal("1.5"));
        currentPrices.put(Symbol.of("DOTUSDT"), new BigDecimal("25"));
        currentPrices.put(Symbol.of("LINKUSDT"), new BigDecimal("20"));
        
        volatilities.put(Symbol.btcUsdt(), new BigDecimal("0.02"));
        volatilities.put(Symbol.ethUsdt(), new BigDecimal("0.03"));
        volatilities.put(Symbol.of("ADAUSDT"), new BigDecimal("0.05"));
        volatilities.put(Symbol.of("DOTUSDT"), new BigDecimal("0.04"));
        volatilities.put(Symbol.of("LINKUSDT"), new BigDecimal("0.04"));
    }
    
    private void startMarketSimulation() {
        // Update market prices periodically
        scheduler.scheduleAtFixedRate(() -> {
            for (Map.Entry<Symbol, BigDecimal> entry : currentPrices.entrySet()) {
                Symbol symbol = entry.getKey();
                BigDecimal currentPrice = entry.getValue();
                BigDecimal volatility = volatilities.get(symbol);
                
                // Random walk with volatility
                double change = (random.nextGaussian() * volatility.doubleValue());
                BigDecimal newPrice = currentPrice.multiply(
                    BigDecimal.ONE.add(BigDecimal.valueOf(change))
                ).setScale(symbol.getPriceScale(), RoundingMode.HALF_UP);
                
                currentPrices.put(symbol, newPrice);
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }
    
    private void scheduleOrderProcessing(Order order) {
        // Schedule order fill simulation
        scheduler.schedule(() -> {
            processOrder(order);
        }, 100 + random.nextInt(500), TimeUnit.MILLISECONDS);
    }
    
    private void processOrder(Order order) {
        if (!orders.containsKey(order.orderId()) || 
            order.status() != OrderStatus.Status.NEW) {
            return;
        }
        
        BigDecimal currentPrice = getCurrentPrice(order.symbol());
        if (currentPrice == null) return;
        
        // Determine if order should fill
        boolean shouldFill = shouldOrderFill(order, currentPrice);
        if (!shouldFill) {
            // Reschedule
            scheduler.schedule(() -> processOrder(order), 
                100 + random.nextInt(200), TimeUnit.MILLISECONDS);
            return;
        }
        
        // Determine fill quantity (partial or full)
        BigDecimal fillQty = determineFillQuantity(order);
        BigDecimal fillPrice = determineFillPrice(order, currentPrice);
        
        // Execute fill
        executeFill(order, fillQty, fillPrice);
    }
    
    private boolean shouldOrderFill(Order order, BigDecimal currentPrice) {
        if (order.type() == OrderType.MARKET) {
            return true; // Market orders fill immediately
        }
        
        if (order.type() == OrderType.LIMIT) {
            if (order.side() == Side.BUY) {
                return currentPrice.compareTo(order.price()) <= 0;
            } else {
                return currentPrice.compareTo(order.price()) >= 0;
            }
        }
        
        return false;
    }
    
    private BigDecimal determineFillQuantity(Order order) {
        BigDecimal remainingQty = order.quantity().subtract(order.filledQuantity());
        
        // 80% chance of full fill, 20% chance of partial fill
        if (random.nextDouble() < 0.8) {
            return remainingQty;
        } else {
            // Partial fill: 10% to 90% of remaining quantity
            double fillRatio = 0.1 + random.nextDouble() * 0.8;
            return remainingQty.multiply(BigDecimal.valueOf(fillRatio))
                .setScale(order.symbol().getQtyScale(), RoundingMode.DOWN);
        }
    }
    
    private BigDecimal determineFillPrice(Order order, BigDecimal currentPrice) {
        if (order.type() == OrderType.MARKET) {
            // Add some slippage for market orders
            double slippage = (random.nextDouble() - 0.5) * 0.001; // ±0.05%
            return currentPrice.multiply(BigDecimal.ONE.add(BigDecimal.valueOf(slippage)))
                .setScale(order.symbol().getPriceScale(), RoundingMode.HALF_UP);
        } else {
            return order.price();
        }
    }
    
    private void executeFill(Order order, BigDecimal fillQty, BigDecimal fillPrice) {
        if (fillQty.compareTo(BigDecimal.ZERO) <= 0) return;
        
        // Update order
        BigDecimal newFilledQty = order.filledQuantity().add(fillQty);
        OrderStatus.Status newStatus = newFilledQty.compareTo(order.quantity()) >= 0 ? 
            OrderStatus.Status.FILLED : OrderStatus.Status.PARTIALLY_FILLED;
        
        Order updatedOrder = new Order(
            order.orderId(), order.clientOrderId(), order.symbol(),
            order.side(), order.type(), order.price(), order.quantity(),
            newFilledQty, newStatus, order.createTime(), System.currentTimeMillis()
        );
        
        orders.put(order.orderId(), updatedOrder);
        
        // Create trade
        String tradeId = generateTradeId();
        Trade trade = new Trade(
            tradeId, order.orderId(), order.symbol(), order.side(),
            fillPrice, fillQty, calculateCommission(fillQty, fillPrice),
            "USDT", random.nextBoolean(), System.currentTimeMillis()
        );
        
        tradeHistory.add(trade);
        stats.recordTrade(fillQty.doubleValue(), 
            fillQty.multiply(fillPrice).doubleValue(),
            trade.commission().doubleValue());
        
        if (newStatus == OrderStatus.Status.FILLED) {
            stats.recordOrderFilled();
        } else {
            stats.recordOrderPartiallyFilled();
        }
        
        // Update balances and positions
        updateBalancesAndPositions(trade);
        
        // Send updates
        sendOrderUpdate(updatedOrder);
        sendTradeUpdate(trade);
    }
    
    private void updateBalancesAndPositions(Trade trade) {
        // Update balances (simplified)
        String baseAsset = trade.symbol().getBaseAsset();
        String quoteAsset = trade.symbol().getQuoteAsset();
        
        BigDecimal notional = trade.price().multiply(trade.quantity());
        
        if (trade.side() == Side.BUY) {
            // Increase base asset, decrease quote asset
            updateBalance(baseAsset, trade.quantity(), BigDecimal.ZERO);
            updateBalance(quoteAsset, notional.negate(), BigDecimal.ZERO);
        } else {
            // Decrease base asset, increase quote asset
            updateBalance(baseAsset, trade.quantity().negate(), BigDecimal.ZERO);
            updateBalance(quoteAsset, notional, BigDecimal.ZERO);
        }
        
        // Update position
        updatePosition(trade);
    }
    
    private void updateBalance(String asset, BigDecimal deltaFree, BigDecimal deltaLocked) {
        Balance current = balances.get(asset);
        if (current == null) {
            current = new Balance(asset, BigDecimal.ZERO, BigDecimal.ZERO, 
                BigDecimal.ZERO, System.currentTimeMillis());
        }
        
        BigDecimal newFree = current.free().add(deltaFree);
        BigDecimal newLocked = current.locked().add(deltaLocked);
        BigDecimal newTotal = newFree.add(newLocked);
        
        Balance updated = new Balance(asset, newFree, newLocked, newTotal, 
            System.currentTimeMillis());
        balances.put(asset, updated);
        
        if (balanceUpdateHandler != null) {
            BalanceUpdate update = new BalanceUpdate(asset, newFree, newLocked, 
                newTotal, System.currentTimeMillis());
            balanceUpdateHandler.onBalanceUpdate(update);
        }
    }
    
    private void updatePosition(Trade trade) {
        Position current = positions.get(trade.symbol());
        if (current == null) {
            current = new Position(trade.symbol(), BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 
                System.currentTimeMillis());
        }
        
        BigDecimal deltaSize = trade.side() == Side.BUY ? 
            trade.quantity() : trade.quantity().negate();
        BigDecimal newSize = current.size().add(deltaSize);
        
        // Simplified position calculation
        BigDecimal newNotional = newSize.multiply(trade.price());
        BigDecimal newAvgPrice = newSize.compareTo(BigDecimal.ZERO) != 0 ? 
            newNotional.divide(newSize, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        
        Position updated = new Position(trade.symbol(), newSize, newNotional,
            newAvgPrice, BigDecimal.ZERO, current.realizedPnl(), 
            System.currentTimeMillis());
        positions.put(trade.symbol(), updated);
        
        if (positionUpdateHandler != null) {
            PositionUpdate update = new PositionUpdate(trade.symbol(), newSize,
                newNotional, newAvgPrice, BigDecimal.ZERO, current.realizedPnl(),
                System.currentTimeMillis());
            positionUpdateHandler.onPositionUpdate(update);
        }
    }
    
    private void sendOrderUpdate(Order order) {
        if (orderUpdateHandler != null) {
            OrderUpdate update = new OrderUpdate(
                order.orderId(), order.clientOrderId(), order.symbol(),
                order.side(), order.type(), order.price(), order.quantity(),
                order.filledQuantity(), calculateAvgFillPrice(order), order.status(),
                null, order.updateTime()
            );
            orderUpdateHandler.onOrderUpdate(update);
        }
    }
    
    private void sendTradeUpdate(Trade trade) {
        if (tradeUpdateHandler != null) {
            TradeUpdate update = new TradeUpdate(
                trade.tradeId(), trade.orderId(), null, trade.symbol(),
                trade.side(), trade.price(), trade.quantity(),
                trade.commission(), trade.commissionAsset(), trade.isMaker(),
                trade.timestamp()
            );
            tradeUpdateHandler.onTradeUpdate(update);
        }
    }
    
    private String validateOrder(OrderRequest request) {
        long qtyScaled = request.quantity();
        BigDecimal qtyDec = BigDecimal.valueOf(qtyScaled).divide(BigDecimal.valueOf(100_000_000));
        if (qtyDec.compareTo(BigDecimal.ZERO) <= 0) {
            return "Invalid quantity";
        }

        long priceScaled = request.price();
        BigDecimal priceDec = BigDecimal.valueOf(priceScaled).divide(BigDecimal.valueOf(100_000_000));
        if (request.type() == OrderType.LIMIT &&
            priceDec.compareTo(BigDecimal.ZERO) <= 0) {
            return "Invalid price";
        }

        // Check balance for buy orders
        if (request.side() == Side.BUY) {
            String quoteAsset = request.symbol().getQuoteAsset();
            Balance balance = balances.get(quoteAsset);
            if (balance == null) {
                return "Insufficient balance";
            }

            BigDecimal required = qtyDec.multiply(
                request.type() == OrderType.MARKET ?
                getCurrentPrice(request.symbol()) : priceDec
            );

            if (balance.free().compareTo(required) < 0) {
                return "Insufficient balance";
            }
        }

        return null; // Valid
    }
    
    private BigDecimal getCurrentPrice(Symbol symbol) {
        return currentPrices.get(symbol);
    }
    
    private BigDecimal calculateAvgFillPrice(Order order) {
        // Simplified calculation - in real implementation would track actual fills
        return order.price();
    }
    
    private BigDecimal calculateCommission(BigDecimal quantity, BigDecimal price) {
        // 0.1% commission
        return quantity.multiply(price).multiply(new BigDecimal("0.001"));
    }
    
    private String generateOrderId() {
        return "SIM_ORDER_" + orderIdSequence.incrementAndGet();
    }
    
    private String generateTradeId() {
        return "SIM_TRADE_" + tradeIdSequence.incrementAndGet();
    }
}