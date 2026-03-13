package com.bedrock.mm.aeron.inproc;

import com.bedrock.mm.common.channel.ChannelConfig;
import com.bedrock.mm.common.channel.ChannelPublisher;
import com.bedrock.mm.common.channel.PublisherMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * In-process channel publisher using RingBuffer
 */
public class InProcChannelPublisher<T> implements ChannelPublisher<T> {
    private static final Logger log = LoggerFactory.getLogger(InProcChannelPublisher.class);
    
    private final OneToOneRingBuffer ringBuffer;
    private final ChannelConfig config;
    private final PublisherMetrics metrics;
    private final UnsafeBuffer tempBuffer;
    private volatile boolean closed = false;
    
    public InProcChannelPublisher(OneToOneRingBuffer ringBuffer, ChannelConfig config) {
        this.ringBuffer = ringBuffer;
        this.config = config;
        this.metrics = new PublisherMetrics();
        this.tempBuffer = new UnsafeBuffer(new byte[8192]); // 8KB temp buffer
        
        log.debug("Created InProc publisher for stream {}", config.getStreamId());
    }
    
    @Override
    public boolean publish(T message) {
        if (closed) {
            return false;
        }
        
        // For now, serialize the message as string bytes
        // In real implementation, this would use SBE encoding
        byte[] messageBytes = message.toString().getBytes();
        return publish(new UnsafeBuffer(messageBytes), 0, messageBytes.length);
    }
    
    @Override
    public boolean publish(DirectBuffer buffer, int offset, int length) {
        if (closed) {
            return false;
        }
        
        long startTime = System.nanoTime();
        
        try {
            int claimIndex = ringBuffer.tryClaim(1, length);
            if (claimIndex > 0) {
                ringBuffer.buffer().putBytes(claimIndex, buffer, offset, length);
                ringBuffer.commit(claimIndex);
                
                long latency = System.nanoTime() - startTime;
                metrics.recordPublish(length, latency);
                return true;
            } else {
                metrics.recordOfferFailure();
                return false;
            }
        } catch (Exception e) {
            log.warn("Failed to publish message", e);
            metrics.recordOfferFailure();
            return false;
        }
    }
    
    @Override
    public int getStreamId() {
        return config.getStreamId();
    }
    
    @Override
    public boolean isConnected() {
        return !closed;
    }
    
    @Override
    public PublisherMetrics getMetrics() {
        return metrics;
    }
    
    @Override
    public void close() {
        if (!closed) {
            log.debug("Closing InProc publisher for stream {}", config.getStreamId());
            closed = true;
        }
    }
}