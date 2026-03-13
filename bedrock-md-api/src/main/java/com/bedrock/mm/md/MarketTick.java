package com.bedrock.mm.md;

import com.bedrock.mm.common.model.Symbol;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Market tick data
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MarketTick {
    
    private final Symbol symbol;
    private final long timestamp;
    private final long price;
    private final long quantity;
    private final boolean isBuy;
    private final long sequenceNumber;
    
    @JsonCreator
    public MarketTick(
            @JsonProperty("symbol") Symbol symbol,
            @JsonProperty("timestamp") long timestamp,
            @JsonProperty("price") long price,
            @JsonProperty("quantity") long quantity,
            @JsonProperty("buy") boolean isBuy,
            @JsonProperty("sequenceNumber") long sequenceNumber) {
        this.symbol = symbol;
        this.timestamp = timestamp;
        this.price = price;
        this.quantity = quantity;
        this.isBuy = isBuy;
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
     * Create a buy tick
     */
    public static MarketTick buy(Symbol symbol, long timestamp, long price, 
                                long quantity, long sequenceNumber) {
        return new MarketTick(symbol, timestamp, price, quantity, true, sequenceNumber);
    }
    
    /**
     * Create a sell tick
     */
    public static MarketTick sell(Symbol symbol, long timestamp, long price, 
                                 long quantity, long sequenceNumber) {
        return new MarketTick(symbol, timestamp, price, quantity, false, sequenceNumber);
    }
    
    @Override
    public String toString() {
        return String.format("MarketTick{symbol=%s, timestamp=%d, price=%.8f, qty=%.8f, side=%s, seq=%d}",
                symbol.getName(), timestamp, getPriceAsDecimal(), getQuantityAsDecimal(),
                isBuy ? "BUY" : "SELL", sequenceNumber);
    }
}