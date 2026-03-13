package com.bedrock.mm.aeron;

import com.bedrock.mm.common.channel.ChannelConfig;
import com.bedrock.mm.common.channel.ChannelSubscriber;
import com.bedrock.mm.common.channel.SubscriberMetrics;
import io.aeron.Aeron;
import io.aeron.Subscription;
import io.aeron.logbuffer.FragmentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.agrona.DirectBuffer;

/**
 * Aeron-based channel subscriber
 */
public class AeronChannelSubscriber<T> implements ChannelSubscriber<T> {
    private static final Logger log = LoggerFactory.getLogger(AeronChannelSubscriber.class);
    
    private final Subscription subscription;
    private final ChannelConfig config;
    private final SubscriberMetrics metrics;
    private volatile ChannelSubscriber.MessageHandler<T> messageHandler;
    private volatile ChannelSubscriber.RawMessageHandler rawMessageHandler;
    private volatile boolean closed = false;
    
    public AeronChannelSubscriber(Aeron aeron, ChannelConfig config) {
        this.config = config;
        this.metrics = new SubscriberMetrics();
        
        String channel = config.getEndpoint();
        this.subscription = aeron.addSubscription(channel, config.getStreamId());
        
        log.info("Created Aeron subscriber for stream {} on channel {}", 
                config.getStreamId(), channel);
    }
    
    @Override
    public void subscribe(ChannelSubscriber.MessageHandler<T> handler) {
        this.messageHandler = handler;
        log.debug("Subscribed message handler for stream {}", config.getStreamId());
    }
    
    @Override
    public void subscribe(ChannelSubscriber.RawMessageHandler handler) {
        this.rawMessageHandler = handler;
        log.debug("Subscribed raw message handler for stream {}", config.getStreamId());
    }
    
    @Override
    public int poll() {
        return poll(Integer.MAX_VALUE);
    }
    
    @Override
    public int poll(int messageCountLimit) {
        if (closed) {
            return 0;
        }
        
        metrics.recordPoll();
        
        FragmentHandler handler = (buffer, offset, length, header) -> {
            long startTime = System.nanoTime();
            
            try {
                if (rawMessageHandler != null) {
                    rawMessageHandler.onMessage(buffer, offset, length, System.nanoTime());
                } else if (messageHandler != null) {
                    // For now, convert buffer to string as placeholder
                    // In real implementation, this would use SBE decoding
                    byte[] bytes = new byte[length];
                    buffer.getBytes(offset, bytes);
                    String message = new String(bytes);
                    @SuppressWarnings("unchecked")
                    T typedMessage = (T) message;
                    messageHandler.onMessage(typedMessage, System.nanoTime());
                }
                
                long processingTime = System.nanoTime() - startTime;
                // Calculate lag based on header timestamp if available
                long lag = 0; // timestamp() method not available in this Aeron version
                metrics.recordReceive(length, processingTime, lag);
                
            } catch (Exception e) {
                log.warn("Error processing message", e);
            }
        };
        
        return subscription.poll(handler, messageCountLimit);
    }
    
    @Override
    public int getStreamId() {
        return config.getStreamId();
    }
    
    @Override
    public boolean isConnected() {
        return !closed && subscription.isConnected();
    }
    
    @Override
    public SubscriberMetrics getMetrics() {
        return metrics;
    }
    
    @Override
    public void close() {
        if (!closed) {
            log.info("Closing Aeron subscriber for stream {}", config.getStreamId());
            subscription.close();
            closed = true;
        }
    }
}