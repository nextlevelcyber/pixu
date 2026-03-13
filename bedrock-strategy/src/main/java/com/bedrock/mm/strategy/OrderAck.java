package com.bedrock.mm.strategy;

import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import lombok.Data;

/**
 * Order acknowledgment
 */
@Data
public class OrderAck {
    
    private final String orderId;
    private final String clientOrderId;
    private final Symbol symbol;
    private final Side side;
    private final long price;
    private final long quantity;
    private final OrderStatus status;
    private final long timestamp;
    private final String rejectReason;
    
    public OrderAck(String orderId, String clientOrderId, Symbol symbol, Side side,
                   long price, long quantity, OrderStatus status, long timestamp, String rejectReason) {
        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.symbol = symbol;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.status = status;
        this.timestamp = timestamp;
        this.rejectReason = rejectReason;
    }
    
    /**
     * Get price as decimal
     */
    public double getPriceAsDecimal() {
        return symbol.priceToDecimal(price);
    }
    
    /**
     * Get quantity as decimal
     */
    public double getQuantityAsDecimal() {
        return symbol.qtyToDecimal(quantity);
    }
    
    /**
     * Order status enum
     */
    public enum OrderStatus {
        NEW,
        PARTIALLY_FILLED,
        FILLED,
        CANCELED,
        REJECTED,
        EXPIRED
    }
    
    /**
     * Create accepted order ack
     */
    public static OrderAck accepted(String orderId, String clientOrderId, Symbol symbol,
                                   Side side, long price, long quantity, long timestamp) {
        return new OrderAck(orderId, clientOrderId, symbol, side, price, quantity,
                           OrderStatus.NEW, timestamp, null);
    }
    
    /**
     * Create rejected order ack
     */
    public static OrderAck rejected(String clientOrderId, Symbol symbol, Side side,
                                   long price, long quantity, long timestamp, String reason) {
        return new OrderAck(null, clientOrderId, symbol, side, price, quantity,
                           OrderStatus.REJECTED, timestamp, reason);
    }
    
    @Override
    public String toString() {
        return String.format("OrderAck{orderId=%s, clientOrderId=%s, symbol=%s, side=%s, " +
                           "price=%.8f, qty=%.8f, status=%s, timestamp=%d, reason=%s}",
                           orderId, clientOrderId, symbol.getName(), side,
                           getPriceAsDecimal(), getQuantityAsDecimal(), status, timestamp, rejectReason);
    }
}