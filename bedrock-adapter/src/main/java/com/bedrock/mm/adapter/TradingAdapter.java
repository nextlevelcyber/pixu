package com.bedrock.mm.adapter;

import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Trading adapter interface for connecting to exchanges and brokers
 */
public interface TradingAdapter {
    
    /**
     * Get adapter name
     */
    String getName();
    
    /**
     * Get adapter version
     */
    String getVersion();
    
    /**
     * Initialize the adapter
     */
    void initialize(AdapterConfig config);
    
    /**
     * Connect to the exchange/broker
     */
    CompletableFuture<Void> connect();
    
    /**
     * Disconnect from the exchange/broker
     */
    CompletableFuture<Void> disconnect();
    
    /**
     * Check if adapter is connected
     */
    boolean isConnected();
    
    /**
     * Submit a new order
     */
    CompletableFuture<OrderResponse> submitOrder(OrderRequest request);
    
    /**
     * Cancel an existing order
     */
    CompletableFuture<CancelResponse> cancelOrder(String orderId);
    
    /**
     * Cancel all orders for a symbol
     */
    CompletableFuture<CancelResponse> cancelAllOrders(Symbol symbol);
    
    /**
     * Get order status
     */
    CompletableFuture<OrderStatus> getOrderStatus(String orderId);
    
    /**
     * Get account balance
     */
    CompletableFuture<List<Balance>> getBalances();
    
    /**
     * Get positions
     */
    CompletableFuture<List<Position>> getPositions();
    
    /**
     * Get open orders
     */
    CompletableFuture<List<Order>> getOpenOrders(Symbol symbol);
    
    /**
     * Get trade history
     */
    CompletableFuture<List<Trade>> getTradeHistory(Symbol symbol, long fromTime, long toTime);
    
    /**
     * Subscribe to order updates
     */
    void subscribeOrderUpdates(OrderUpdateHandler handler);
    
    /**
     * Subscribe to trade updates
     */
    void subscribeTradeUpdates(TradeUpdateHandler handler);
    
    /**
     * Subscribe to balance updates
     */
    void subscribeBalanceUpdates(BalanceUpdateHandler handler);
    
    /**
     * Subscribe to position updates
     */
    void subscribePositionUpdates(PositionUpdateHandler handler);
    
    /**
     * Get supported symbols
     */
    List<Symbol> getSupportedSymbols();
    
    /**
     * Get adapter statistics
     */
    AdapterStats getStats();
    
    // Event handlers
    
    @FunctionalInterface
    interface OrderUpdateHandler {
        void onOrderUpdate(OrderUpdate update);
    }
    
    @FunctionalInterface
    interface TradeUpdateHandler {
        void onTradeUpdate(TradeUpdate update);
    }
    
    @FunctionalInterface
    interface BalanceUpdateHandler {
        void onBalanceUpdate(BalanceUpdate update);
    }
    
    @FunctionalInterface
    interface PositionUpdateHandler {
        void onPositionUpdate(PositionUpdate update);
    }
    
    // Data classes
    
    class OrderRequest {
        private final String clientOrderId;
        private final Symbol symbol;
        private final Side side;
        private final OrderType type;
        private final long price;      // fixed-point, scale 1e-8
        private final long quantity;   // fixed-point, scale 1e-8
        private final TimeInForce timeInForce;
        private final String strategyId;

        public OrderRequest(String clientOrderId, Symbol symbol, Side side, OrderType type,
                           long price, long quantity, TimeInForce timeInForce, String strategyId) {
            this.clientOrderId = clientOrderId;
            this.symbol = symbol;
            this.side = side;
            this.type = type;
            this.price = price;
            this.quantity = quantity;
            this.timeInForce = timeInForce;
            this.strategyId = strategyId;
        }

        public String clientOrderId() { return clientOrderId; }
        public Symbol symbol() { return symbol; }
        public Side side() { return side; }
        public OrderType type() { return type; }
        public long price() { return price; }
        public long quantity() { return quantity; }
        public TimeInForce timeInForce() { return timeInForce; }
        public String strategyId() { return strategyId; }
    }
    
    class OrderResponse {
        private final String orderId;
        private final String clientOrderId;
        private final OrderStatus.Status status;
        private final String message;
        private final long timestamp;
        
        public OrderResponse(String orderId, String clientOrderId, OrderStatus.Status status, String message, long timestamp) {
            this.orderId = orderId;
            this.clientOrderId = clientOrderId;
            this.status = status;
            this.message = message;
            this.timestamp = timestamp;
        }
        
        public String orderId() { return orderId; }
        public String clientOrderId() { return clientOrderId; }
        public OrderStatus.Status status() { return status; }
        public String message() { return message; }
        public long timestamp() { return timestamp; }
    }
    
    class CancelResponse {
        private final String orderId;
        private final boolean success;
        private final String message;
        private final long timestamp;
        
        public CancelResponse(String orderId, boolean success, String message, long timestamp) {
            this.orderId = orderId;
            this.success = success;
            this.message = message;
            this.timestamp = timestamp;
        }
        
        public String orderId() { return orderId; }
        public boolean success() { return success; }
        public String message() { return message; }
        public long timestamp() { return timestamp; }
    }
    
    class OrderStatus {
        private final String orderId;
        private final String clientOrderId;
        private final Symbol symbol;
        private final Side side;
        private final OrderType type;
        private final BigDecimal price;
        private final BigDecimal quantity;
        private final BigDecimal filledQuantity;
        private final BigDecimal avgFillPrice;
        private final Status status;
        private final long createTime;
        private final long updateTime;
        
        public OrderStatus(String orderId, String clientOrderId, Symbol symbol, Side side, OrderType type,
                          BigDecimal price, BigDecimal quantity, BigDecimal filledQuantity, BigDecimal avgFillPrice,
                          Status status, long createTime, long updateTime) {
            this.orderId = orderId;
            this.clientOrderId = clientOrderId;
            this.symbol = symbol;
            this.side = side;
            this.type = type;
            this.price = price;
            this.quantity = quantity;
            this.filledQuantity = filledQuantity;
            this.avgFillPrice = avgFillPrice;
            this.status = status;
            this.createTime = createTime;
            this.updateTime = updateTime;
        }
        
        public String orderId() { return orderId; }
        public String clientOrderId() { return clientOrderId; }
        public Symbol symbol() { return symbol; }
        public Side side() { return side; }
        public OrderType type() { return type; }
        public BigDecimal price() { return price; }
        public BigDecimal quantity() { return quantity; }
        public BigDecimal filledQuantity() { return filledQuantity; }
        public BigDecimal avgFillPrice() { return avgFillPrice; }
        public Status status() { return status; }
        public long createTime() { return createTime; }
        public long updateTime() { return updateTime; }
        
        public enum Status {
            NEW, PARTIALLY_FILLED, FILLED, CANCELED, REJECTED, EXPIRED
        }
    }
    
    class Balance {
        private final String asset;
        private final BigDecimal free;
        private final BigDecimal locked;
        private final BigDecimal total;
        private final long timestamp;
        
        public Balance(String asset, BigDecimal free, BigDecimal locked, BigDecimal total, long timestamp) {
            this.asset = asset;
            this.free = free;
            this.locked = locked;
            this.total = total;
            this.timestamp = timestamp;
        }
        
        @JsonProperty("asset")
        public String asset() { return asset; }
        @JsonProperty("free")
        public BigDecimal free() { return free; }
        @JsonProperty("locked")
        public BigDecimal locked() { return locked; }
        @JsonProperty("total")
        public BigDecimal total() { return total; }
        @JsonProperty("timestamp")
        public long timestamp() { return timestamp; }
    }
    
    class Position {
        private final Symbol symbol;
        private final BigDecimal size;
        private final BigDecimal notional;
        private final BigDecimal avgPrice;
        private final BigDecimal unrealizedPnl;
        private final BigDecimal realizedPnl;
        private final long timestamp;
        
        public Position(Symbol symbol, BigDecimal size, BigDecimal notional, BigDecimal avgPrice,
                       BigDecimal unrealizedPnl, BigDecimal realizedPnl, long timestamp) {
            this.symbol = symbol;
            this.size = size;
            this.notional = notional;
            this.avgPrice = avgPrice;
            this.unrealizedPnl = unrealizedPnl;
            this.realizedPnl = realizedPnl;
            this.timestamp = timestamp;
        }
        
        public Symbol symbol() { return symbol; }
        public BigDecimal size() { return size; }
        public BigDecimal notional() { return notional; }
        public BigDecimal avgPrice() { return avgPrice; }
        public BigDecimal unrealizedPnl() { return unrealizedPnl; }
        public BigDecimal realizedPnl() { return realizedPnl; }
        public long timestamp() { return timestamp; }
    }
    
    class Order {
        private final String orderId;
        private final String clientOrderId;
        private final Symbol symbol;
        private final Side side;
        private final OrderType type;
        private final BigDecimal price;
        private final BigDecimal quantity;
        private final BigDecimal filledQuantity;
        private final OrderStatus.Status status;
        private final long createTime;
        private final long updateTime;
        
        public Order(String orderId, String clientOrderId, Symbol symbol, Side side, OrderType type,
                    BigDecimal price, BigDecimal quantity, BigDecimal filledQuantity, OrderStatus.Status status,
                    long createTime, long updateTime) {
            this.orderId = orderId;
            this.clientOrderId = clientOrderId;
            this.symbol = symbol;
            this.side = side;
            this.type = type;
            this.price = price;
            this.quantity = quantity;
            this.filledQuantity = filledQuantity;
            this.status = status;
            this.createTime = createTime;
            this.updateTime = updateTime;
        }
        
        public String orderId() { return orderId; }
        public String clientOrderId() { return clientOrderId; }
        public Symbol symbol() { return symbol; }
        public Side side() { return side; }
        public OrderType type() { return type; }
        public BigDecimal price() { return price; }
        public BigDecimal quantity() { return quantity; }
        public BigDecimal filledQuantity() { return filledQuantity; }
        public OrderStatus.Status status() { return status; }
        public long createTime() { return createTime; }
        public long updateTime() { return updateTime; }
    }
    
    class Trade {
        private final String tradeId;
        private final String orderId;
        private final Symbol symbol;
        private final Side side;
        private final BigDecimal price;
        private final BigDecimal quantity;
        private final BigDecimal commission;
        private final String commissionAsset;
        private final boolean isMaker;
        private final long timestamp;
        
        public Trade(String tradeId, String orderId, Symbol symbol, Side side, BigDecimal price,
                    BigDecimal quantity, BigDecimal commission, String commissionAsset, boolean isMaker, long timestamp) {
            this.tradeId = tradeId;
            this.orderId = orderId;
            this.symbol = symbol;
            this.side = side;
            this.price = price;
            this.quantity = quantity;
            this.commission = commission;
            this.commissionAsset = commissionAsset;
            this.isMaker = isMaker;
            this.timestamp = timestamp;
        }
        
        public String tradeId() { return tradeId; }
        public String orderId() { return orderId; }
        public Symbol symbol() { return symbol; }
        public Side side() { return side; }
        public BigDecimal price() { return price; }
        public BigDecimal quantity() { return quantity; }
        public BigDecimal commission() { return commission; }
        public String commissionAsset() { return commissionAsset; }
        public boolean isMaker() { return isMaker; }
        public long timestamp() { return timestamp; }
    }
    
    class OrderUpdate {
        private final String orderId;
        private final String clientOrderId;
        private final Symbol symbol;
        private final Side side;
        private final OrderType type;
        private final BigDecimal price;
        private final BigDecimal quantity;
        private final BigDecimal filledQuantity;
        private final BigDecimal avgFillPrice;
        private final OrderStatus.Status status;
        private final String rejectReason;
        private final long timestamp;
        
        public OrderUpdate(String orderId, String clientOrderId, Symbol symbol, Side side, OrderType type,
                          BigDecimal price, BigDecimal quantity, BigDecimal filledQuantity, BigDecimal avgFillPrice,
                          OrderStatus.Status status, String rejectReason, long timestamp) {
            this.orderId = orderId;
            this.clientOrderId = clientOrderId;
            this.symbol = symbol;
            this.side = side;
            this.type = type;
            this.price = price;
            this.quantity = quantity;
            this.filledQuantity = filledQuantity;
            this.avgFillPrice = avgFillPrice;
            this.status = status;
            this.rejectReason = rejectReason;
            this.timestamp = timestamp;
        }
        
        public String orderId() { return orderId; }
        public String clientOrderId() { return clientOrderId; }
        public Symbol symbol() { return symbol; }
        public Side side() { return side; }
        public OrderType type() { return type; }
        public BigDecimal price() { return price; }
        public BigDecimal quantity() { return quantity; }
        public BigDecimal filledQuantity() { return filledQuantity; }
        public BigDecimal avgFillPrice() { return avgFillPrice; }
        public OrderStatus.Status status() { return status; }
        public String rejectReason() { return rejectReason; }
        public long timestamp() { return timestamp; }
    }
    
    class TradeUpdate {
        private final String tradeId;
        private final String orderId;
        private final String clientOrderId;
        private final Symbol symbol;
        private final Side side;
        private final BigDecimal price;
        private final BigDecimal quantity;
        private final BigDecimal commission;
        private final String commissionAsset;
        private final boolean isMaker;
        private final long timestamp;
        
        public TradeUpdate(String tradeId, String orderId, String clientOrderId, Symbol symbol, Side side,
                          BigDecimal price, BigDecimal quantity, BigDecimal commission, String commissionAsset,
                          boolean isMaker, long timestamp) {
            this.tradeId = tradeId;
            this.orderId = orderId;
            this.clientOrderId = clientOrderId;
            this.symbol = symbol;
            this.side = side;
            this.price = price;
            this.quantity = quantity;
            this.commission = commission;
            this.commissionAsset = commissionAsset;
            this.isMaker = isMaker;
            this.timestamp = timestamp;
        }
        
        public String tradeId() { return tradeId; }
        public String orderId() { return orderId; }
        public String clientOrderId() { return clientOrderId; }
        public Symbol symbol() { return symbol; }
        public Side side() { return side; }
        public BigDecimal price() { return price; }
        public BigDecimal quantity() { return quantity; }
        public BigDecimal commission() { return commission; }
        public String commissionAsset() { return commissionAsset; }
        public boolean isMaker() { return isMaker; }
        public long timestamp() { return timestamp; }
    }
    
    class BalanceUpdate {
        private final String asset;
        private final BigDecimal free;
        private final BigDecimal locked;
        private final BigDecimal total;
        private final long timestamp;
        
        public BalanceUpdate(String asset, BigDecimal free, BigDecimal locked, BigDecimal total, long timestamp) {
            this.asset = asset;
            this.free = free;
            this.locked = locked;
            this.total = total;
            this.timestamp = timestamp;
        }
        
        public String asset() { return asset; }
        public BigDecimal free() { return free; }
        public BigDecimal locked() { return locked; }
        public BigDecimal total() { return total; }
        public long timestamp() { return timestamp; }
    }
    
    class PositionUpdate {
        private final Symbol symbol;
        private final BigDecimal size;
        private final BigDecimal notional;
        private final BigDecimal avgPrice;
        private final BigDecimal unrealizedPnl;
        private final BigDecimal realizedPnl;
        private final long timestamp;
        
        public PositionUpdate(Symbol symbol, BigDecimal size, BigDecimal notional, BigDecimal avgPrice,
                             BigDecimal unrealizedPnl, BigDecimal realizedPnl, long timestamp) {
            this.symbol = symbol;
            this.size = size;
            this.notional = notional;
            this.avgPrice = avgPrice;
            this.unrealizedPnl = unrealizedPnl;
            this.realizedPnl = realizedPnl;
            this.timestamp = timestamp;
        }
        
        public Symbol symbol() { return symbol; }
        public BigDecimal size() { return size; }
        public BigDecimal notional() { return notional; }
        public BigDecimal avgPrice() { return avgPrice; }
        public BigDecimal unrealizedPnl() { return unrealizedPnl; }
        public BigDecimal realizedPnl() { return realizedPnl; }
        public long timestamp() { return timestamp; }
    }
    
    enum OrderType {
        MARKET, LIMIT, STOP, STOP_LIMIT
    }
    
    enum TimeInForce {
        GTC, // Good Till Cancel
        IOC, // Immediate Or Cancel
        FOK  // Fill Or Kill
    }
}