package com.bedrock.mm.md;

import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Order book delta update
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookDelta {
    
    private final Symbol symbol;
    private final long timestamp;
    private final Side side;
    private final long price;
    private final long quantity;
    private final Action action;
    private final long sequenceNumber;
    
    @JsonCreator
    public BookDelta(
            @JsonProperty("symbol") Symbol symbol,
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("side") Side side,
            @JsonProperty("price") long price,
            @JsonProperty("quantity") long quantity,
            @JsonProperty("action") Action action,
            @JsonProperty("sequenceNumber") long sequenceNumber) {
        this.symbol = symbol;
        this.timestamp = timestamp;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.action = action;
        this.sequenceNumber = sequenceNumber;
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
     * Book delta actions
     */
    public enum Action {
        ADD,    // Add new level
        UPDATE, // Update existing level
        DELETE  // Remove level
    }
    
    /**
     * Create an add delta
     */
    public static BookDelta add(Symbol symbol, long timestamp, Side side, 
                               long price, long quantity, long sequenceNumber) {
        return new BookDelta(symbol, timestamp, side, price, quantity, Action.ADD, sequenceNumber);
    }
    
    /**
     * Create an update delta
     */
    public static BookDelta update(Symbol symbol, long timestamp, Side side, 
                                  long price, long quantity, long sequenceNumber) {
        return new BookDelta(symbol, timestamp, side, price, quantity, Action.UPDATE, sequenceNumber);
    }
    
    /**
     * Create a delete delta
     */
    public static BookDelta delete(Symbol symbol, long timestamp, Side side, 
                                  long price, long sequenceNumber) {
        return new BookDelta(symbol, timestamp, side, price, 0, Action.DELETE, sequenceNumber);
    }
    
    @Override
    public String toString() {
        return String.format("BookDelta{symbol=%s, timestamp=%d, side=%s, price=%.8f, qty=%.8f, action=%s, seq=%d}",
                symbol.getName(), timestamp, side, getPriceAsDecimal(), getQuantityAsDecimal(),
                action, sequenceNumber);
    }
}