package com.bedrock.mm.common.event;

/**
 * Unified order command payload for event bus.
 * Uses fixed-point long values (scale 1e-8) for price/quantity to avoid allocation.
 */
public class OrderCommand {
    private String symbol;       // e.g. BTCUSDT
    private String side;         // BUY or SELL
    private String type = "LIMIT"; // MARKET/LIMIT/STOP/STOP_LIMIT
    private long price;          // fixed-point, scale 1e-8
    private long quantity;       // fixed-point, scale 1e-8
    private String timeInForce = "GTC"; // GTC/IOC/FOK
    private String strategyId = "Strategy";
    private String clientOrderId; // optional client id

    public OrderCommand() {}

    public OrderCommand(String symbol, String side, String type,
                        long price, long quantity,
                        String timeInForce, String strategyId,
                        String clientOrderId) {
        this.symbol = symbol;
        this.side = side;
        this.type = type;
        this.price = price;
        this.quantity = quantity;
        this.timeInForce = timeInForce;
        this.strategyId = strategyId;
        this.clientOrderId = clientOrderId;
    }

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public long getPrice() { return price; }
    public void setPrice(long price) { this.price = price; }
    public long getQuantity() { return quantity; }
    public void setQuantity(long quantity) { this.quantity = quantity; }
    public String getTimeInForce() { return timeInForce; }
    public void setTimeInForce(String timeInForce) { this.timeInForce = timeInForce; }
    public String getStrategyId() { return strategyId; }
    public void setStrategyId(String strategyId) { this.strategyId = strategyId; }
    public String getClientOrderId() { return clientOrderId; }
    public void setClientOrderId(String clientOrderId) { this.clientOrderId = clientOrderId; }
}