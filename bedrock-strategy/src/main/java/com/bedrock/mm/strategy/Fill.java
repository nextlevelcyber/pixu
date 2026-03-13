package com.bedrock.mm.strategy;

import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import lombok.Data;

/**
 * Trade fill
 */
@Data
public class Fill {
    
    private final String fillId;
    private final String orderId;
    private final String clientOrderId;
    private final Symbol symbol;
    private final Side side;
    private final long fillPrice;
    private final long fillQuantity;
    private final long timestamp;
    private final String liquidity;
    private final long commission;
    private final String commissionAsset;
    
    public Fill(String fillId, String orderId, String clientOrderId, Symbol symbol,
               Side side, long fillPrice, long fillQuantity, long timestamp,
               String liquidity, long commission, String commissionAsset) {
        this.fillId = fillId;
        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.symbol = symbol;
        this.side = side;
        this.fillPrice = fillPrice;
        this.fillQuantity = fillQuantity;
        this.timestamp = timestamp;
        this.liquidity = liquidity;
        this.commission = commission;
        this.commissionAsset = commissionAsset;
    }
    
    /**
     * Get fill price as decimal
     */
    public double getFillPriceAsDecimal() {
        return symbol.priceToDecimal(fillPrice);
    }
    
    /**
     * Get fill quantity as decimal
     */
    public double getFillQuantityAsDecimal() {
        return symbol.qtyToDecimal(fillQuantity);
    }
    
    /**
     * Get commission as decimal
     */
    public double getCommissionAsDecimal() {
        // Assuming commission is in the same scale as quantity for simplicity
        return symbol.qtyToDecimal(commission);
    }
    
    /**
     * Calculate notional value
     */
    public double getNotionalValue() {
        return getFillPriceAsDecimal() * getFillQuantityAsDecimal();
    }
    
    /**
     * Check if this is a maker fill
     */
    public boolean isMaker() {
        return "MAKER".equals(liquidity);
    }
    
    /**
     * Check if this is a taker fill
     */
    public boolean isTaker() {
        return "TAKER".equals(liquidity);
    }
    
    @Override
    public String toString() {
        return String.format("Fill{fillId=%s, orderId=%s, clientOrderId=%s, symbol=%s, side=%s, " +
                           "fillPrice=%.8f, fillQty=%.8f, timestamp=%d, liquidity=%s, " +
                           "commission=%.8f, commissionAsset=%s}",
                           fillId, orderId, clientOrderId, symbol.getName(), side,
                           getFillPriceAsDecimal(), getFillQuantityAsDecimal(), timestamp,
                           liquidity, getCommissionAsDecimal(), commissionAsset);
    }
}