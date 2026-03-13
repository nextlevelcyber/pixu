package com.bedrock.mm.aeron.inproc;

import com.bedrock.mm.common.channel.ChannelConfig;
import com.bedrock.mm.common.channel.ChannelSubscriber;
import com.bedrock.mm.common.channel.SubscriberMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.MessageHandler;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;

/**
 * In-process channel subscriber using RingBuffer
 */
public class InProcChannelSubscriber<T> implements ChannelSubscriber<T> {
    private static final Logger log = LoggerFactory.getLogger(InProcChannelSubscriber.class);
    
    private final OneToOneRingBuffer ringBuffer;
    private final ChannelConfig config;
    private final SubscriberMetrics metrics;
    private volatile ChannelSubscriber.MessageHandler<T> messageHandler;
    private volatile ChannelSubscriber.RawMessageHandler rawMessageHandler;
    private volatile boolean closed = false;
    
    public InProcChannelSubscriber(OneToOneRingBuffer ringBuffer, ChannelConfig config) {
        this.ringBuffer = ringBuffer;
        this.config = config;
        this.metrics = new SubscriberMetrics();
        
        log.debug("Created InProc subscriber for stream {}", config.getStreamId());
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
        
        org.agrona.concurrent.MessageHandler handler = (msgTypeId, buffer, index, length) -> {
            long startTime = System.nanoTime();
            
            try {
                if (rawMessageHandler != null) {
                    rawMessageHandler.onMessage(buffer, index, length, System.nanoTime());
                } else if (messageHandler != null) {
                    // For now, convert buffer to string as placeholder
                    // In real implementation, this would use SBE decoding
                    byte[] bytes = new byte[length];
                    buffer.getBytes(index, bytes);
                    String message = new String(bytes);
                    @SuppressWarnings("unchecked")
                    T typedMessage = (T) message;
                    messageHandler.onMessage(typedMessage, System.nanoTime());
                }
                
                long processingTime = System.nanoTime() - startTime;
                metrics.recordReceive(length, processingTime, 0); // lag calculation would be more complex
                
            } catch (Exception e) {
                log.warn("Error processing message", e);
            }
        };
        
        return ringBuffer.read(handler, messageCountLimit);
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
    public SubscriberMetrics getMetrics() {
        return metrics;
    }
    
    @Override
    public void close() {
        if (!closed) {
            log.debug("Closing InProc subscriber for stream {}", config.getStreamId());
            closed = true;
        }
    }
}