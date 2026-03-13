package com.bedrock.mm.common.channel;

import lombok.Data;

/**
 * Subscriber metrics
 */
@Data
public class SubscriberMetrics {
    private long messagesReceived;
    private long bytesReceived;
    private long pollCount;
    private long lagNs;
    private double avgProcessingTimeNs;
    private long lastReceiveTimestamp;
    
    public void recordReceive(int messageSize, long processingTimeNs, long lagNs) {
        messagesReceived++;
        bytesReceived += messageSize;
        this.lagNs = lagNs;
        avgProcessingTimeNs = (avgProcessingTimeNs * (messagesReceived - 1) + processingTimeNs) / messagesReceived;
        lastReceiveTimestamp = System.nanoTime();
    }
    
    public void recordPoll() {
        pollCount++;
    }
    
    public double getReceiveRate() {
        long elapsed = System.nanoTime() - lastReceiveTimestamp;
        if (elapsed <= 0) return 0.0;
        return messagesReceived * 1_000_000_000.0 / elapsed;
    }
}