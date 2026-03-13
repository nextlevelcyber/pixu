package com.bedrock.mm.oms.model;

/**
 * Order model for OMS
 *
 * Zero-allocation hot path design - all fields are primitive types.
 * Uses fixed-point integer arithmetic (scale 1e-8) for price and quantity.
 */
public class Order {
    public long orderId;
    public long exchOrderId;      // 0 until ACK received
    public int instrumentId;
    public long price;            // fixed-point 1e-8
    public long origSize;
    public long filledSize;
    public long fillValueSum;     // sum of (fillPrice * fillSize) for avg price calc
    public OrderState state;
    public int regionIndex;
    public boolean isBid;
    public long createNanos;      // System.nanoTime() at creation
    public long quotePublishNanos; // Pricing quote publish timestamp used to place this order
    public long quoteSeqId;       // Pricing quote sequence used to place this order

    /**
     * Calculate average fill price.
     *
     * @return average fill price in fixed-point format, or 0 if no fills
     */
    public long fillAvgPrice() {
        return filledSize == 0 ? 0 : fillValueSum / filledSize;
    }
}
