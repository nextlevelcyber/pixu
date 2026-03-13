package com.bedrock.mm.common.channel;

import org.agrona.DirectBuffer;

/**
 * Channel subscriber interface for receiving messages
 * Supports both in-process (Ringbuffer) and Aeron implementations
 */
public interface ChannelSubscriber<T> extends AutoCloseable {
    
    /**
     * Message handler interface
     */
    @FunctionalInterface
    interface MessageHandler<T> {
        void onMessage(T message, long timestamp);
    }
    
    /**
     * Raw message handler interface for zero-copy processing
     */
    @FunctionalInterface
    interface RawMessageHandler {
        void onMessage(DirectBuffer buffer, int offset, int length, long timestamp);
    }
    
    /**
     * Subscribe with a message handler
     * @param handler the message handler
     */
    void subscribe(MessageHandler<T> handler);
    
    /**
     * Subscribe with a raw message handler (zero-copy)
     * @param handler the raw message handler
     */
    void subscribe(RawMessageHandler handler);
    
    /**
     * Poll for messages (non-blocking)
     * @return number of messages processed
     */
    int poll();
    
    /**
     * Poll for messages with limit
     * @param messageCountLimit maximum number of messages to process
     * @return number of messages processed
     */
    int poll(int messageCountLimit);
    
    /**
     * Get the stream ID for this subscriber
     */
    int getStreamId();
    
    /**
     * Check if the subscriber is connected
     */
    boolean isConnected();
    
    /**
     * Get metrics about this subscriber
     */
    SubscriberMetrics getMetrics();
}