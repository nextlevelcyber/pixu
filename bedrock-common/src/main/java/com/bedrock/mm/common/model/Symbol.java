package com.bedrock.mm.common.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/**
 * Trading symbol representation
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Symbol {
    private final int symbolId;
    private final String name;
    private final String baseAsset;
    private final String quoteAsset;
    private final int priceScale;  // Number of decimal places for price
    private final int qtyScale;    // Number of decimal places for quantity

    @JsonCreator
    public Symbol(
            @JsonProperty("symbolId") int symbolId,
            @JsonProperty("name") String name,
            @JsonProperty("baseAsset") String baseAsset,
            @JsonProperty("quoteAsset") String quoteAsset,
            @JsonProperty("priceScale") int priceScale,
            @JsonProperty("qtyScale") int qtyScale) {
        this.symbolId = symbolId;
        this.name = name;
        this.baseAsset = baseAsset;
        this.quoteAsset = quoteAsset;
        this.priceScale = priceScale;
        this.qtyScale = qtyScale;
    }

    public int getSymbolId() { return symbolId; }
    public String getName() { return name; }
    public String getBaseAsset() { return baseAsset; }
    public String getQuoteAsset() { return quoteAsset; }
    public int getPriceScale() { return priceScale; }
    public int getQtyScale() { return qtyScale; }

    /**
     * Convert price from decimal to fixed-point representation
     */
    public long priceToFixed(double price) {
        return Math.round(price * Math.pow(10, 8)); // Always use 1e-8 precision
    }
    
    /**
     * Convert price from fixed-point to decimal representation
     */
    public double priceFromFixed(long fixedPrice) {
        return fixedPrice / 1e8;
    }
    
    /**
     * Convert quantity from decimal to fixed-point representation
     */
    public long qtyToFixed(double qty) {
        return Math.round(qty * Math.pow(10, 8)); // Always use 1e-8 precision
    }
    
    /**
     * Convert quantity from fixed-point to decimal representation
     */
    public double qtyFromFixed(long fixedQty) {
        return fixedQty / 1e8;
    }
    
    /**
     * Convert decimal price to fixed-point representation (alias for priceToFixed)
     */
    public long decimalToPrice(double price) {
        return priceToFixed(price);
    }
    
    /**
     * Convert decimal quantity to fixed-point representation (alias for qtyToFixed)
     */
    public long decimalToQty(double qty) {
        return qtyToFixed(qty);
    }
    
    /**
     * Convert fixed-point price to decimal representation (alias for priceFromFixed)
     */
    public double priceToDecimal(long fixedPrice) {
        return priceFromFixed(fixedPrice);
    }
    
    /**
     * Convert fixed-point quantity to decimal representation (alias for qtyFromFixed)
     */
    public double qtyToDecimal(long fixedQty) {
        return qtyFromFixed(fixedQty);
    }
    
    public static Symbol btcUsdt() {
        return new Symbol(1, "BTCUSDT", "BTC", "USDT", 2, 6);
    }
    
    public static Symbol ethUsdt() {
        return new Symbol(2, "ETHUSDT", "ETH", "USDT", 2, 5);
    }
    
    /**
     * Create a symbol by name with default settings
     */
    public static Symbol of(String name) {
        // Parse symbol name to extract base and quote assets
        String baseAsset, quoteAsset;
        if (name.endsWith("USDT")) {
            baseAsset = name.substring(0, name.length() - 4);
            quoteAsset = "USDT";
        } else if (name.endsWith("BTC")) {
            baseAsset = name.substring(0, name.length() - 3);
            quoteAsset = "BTC";
        } else if (name.endsWith("ETH")) {
            baseAsset = name.substring(0, name.length() - 3);
            quoteAsset = "ETH";
        } else {
            // Default fallback
            baseAsset = name.substring(0, 3);
            quoteAsset = name.substring(3);
        }
        
        // Generate a simple hash-based ID
        int symbolId = Math.abs(name.hashCode()) % 10000;
        
        // Default scales
        return new Symbol(symbolId, name, baseAsset, quoteAsset, 2, 6);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Symbol symbol = (Symbol) o;
        return symbolId == symbol.symbolId &&
                priceScale == symbol.priceScale &&
                qtyScale == symbol.qtyScale &&
                Objects.equals(name, symbol.name) &&
                Objects.equals(baseAsset, symbol.baseAsset) &&
                Objects.equals(quoteAsset, symbol.quoteAsset);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbolId, name, baseAsset, quoteAsset, priceScale, qtyScale);
    }

    @Override
    public String toString() {
        return "Symbol{" +
                "symbolId=" + symbolId +
                ", name='" + name + '\'' +
                ", baseAsset='" + baseAsset + '\'' +
                ", quoteAsset='" + quoteAsset + '\'' +
                ", priceScale=" + priceScale +
                ", qtyScale=" + qtyScale +
                '}';
    }
}