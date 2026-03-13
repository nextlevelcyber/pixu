package com.bedrock.mm.common.channel;

import lombok.Data;

/**
 * Publisher metrics
 */
@Data
public class PublisherMetrics {
    private long messagesSent;
    private long bytesPublished;
    private long offerFailures;
    private long backPressureEvents;
    private double avgLatencyNs;
    private long lastPublishTimestamp;
    
    public void recordPublish(int messageSize, long latencyNs) {
        messagesSent++;
        bytesPublished += messageSize;
        avgLatencyNs = (avgLatencyNs * (messagesSent - 1) + latencyNs) / messagesSent;
        lastPublishTimestamp = System.nanoTime();
    }
    
    public void recordOfferFailure() {
        offerFailures++;
        backPressureEvents++;
    }
    
    public double getPublishRate() {
        long elapsed = System.nanoTime() - lastPublishTimestamp;
        if (elapsed <= 0) return 0.0;
        return messagesSent * 1_000_000_000.0 / elapsed;
    }
}