package com.bedrock.mm.common.event;

/**
 * Payload for FILL events published on the unified event bus.
 * Minimal and decoupled from adapter/strategy domain models.
 */
public class FillPayload {
    public String symbol;           // e.g., BTCUSDT
    public String orderId;          // related order id
    public String clientOrderId;    // client id if present
    public String tradeId;          // exchange trade id if present
    public String side;             // BUY/SELL
    public String price;            // fill price as string
    public String quantity;         // fill quantity as string
    public String liquidity;        // MAKER/TAKER
    public String commission;       // commission amount as string
    public String commissionAsset;  // asset of commission
    public long eventTimeMs;        // exchange event time in milliseconds

    public FillPayload() {}

    public FillPayload(String symbol, String orderId, String clientOrderId,
                       String tradeId, String side, String price, String quantity,
                       String liquidity, String commission, String commissionAsset,
                       long eventTimeMs) {
        this.symbol = symbol;
        this.orderId = orderId;
        this.clientOrderId = clientOrderId;
        this.tradeId = tradeId;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.liquidity = liquidity;
        this.commission = commission;
        this.commissionAsset = commissionAsset;
        this.eventTimeMs = eventTimeMs;
    }
}