package com.bedrock.mm.aeron;

import com.bedrock.mm.common.channel.ChannelConfig;
import com.bedrock.mm.common.channel.ChannelPublisher;
import com.bedrock.mm.common.channel.PublisherMetrics;
import io.aeron.Aeron;
import io.aeron.Publication;
import lombok.extern.slf4j.Slf4j;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * Aeron-based channel publisher
 */
@Slf4j
public class AeronChannelPublisher<T> implements ChannelPublisher<T> {
    // private static final Logger log = LoggerFactory.getLogger(AeronChannelPublisher.class);
    
    private final Publication publication;
    private final ChannelConfig config;
    private final PublisherMetrics metrics;
    private volatile boolean closed = false;
    
    public AeronChannelPublisher(Aeron aeron, ChannelConfig config) {
        this.config = config;
        this.metrics = new PublisherMetrics();
        
        String channel = config.getEndpoint();
        this.publication = aeron.addPublication(channel, config.getStreamId());
        
        log.info("Created Aeron publisher for stream {} on channel {}", 
                config.getStreamId(), channel);
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
            long result = publication.offer(buffer, offset, length);
            
            if (result > 0) {
                long latency = System.nanoTime() - startTime;
                metrics.recordPublish(length, latency);
                return true;
            } else if (result == Publication.BACK_PRESSURED) {
                metrics.recordOfferFailure();
                return false;
            } else {
                log.warn("Publication offer failed with result: {}", result);
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
        return !closed && publication.isConnected();
    }
    
    @Override
    public PublisherMetrics getMetrics() {
        return metrics;
    }
    
    @Override
    public void close() {
        if (!closed) {
            log.info("Closing Aeron publisher for stream {}", config.getStreamId());
            publication.close();
            closed = true;
        }
    }
}