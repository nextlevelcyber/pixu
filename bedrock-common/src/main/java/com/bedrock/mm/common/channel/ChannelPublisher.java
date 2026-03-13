package com.bedrock.mm.common.channel;

import org.agrona.DirectBuffer;

/**
 * Channel publisher interface for sending messages
 * Supports both in-process (Ringbuffer) and Aeron implementations
 */
public interface ChannelPublisher<T> extends AutoCloseable {
    
    /**
     * Publish a message
     * @param message the message to publish
     * @return true if published successfully, false if back-pressured
     */
    boolean publish(T message);
    
    /**
     * Publish a message using direct buffer (zero-copy)
     * @param buffer the buffer containing the message
     * @param offset offset in the buffer
     * @param length length of the message
     * @return true if published successfully, false if back-pressured
     */
    boolean publish(DirectBuffer buffer, int offset, int length);
    
    /**
     * Get the stream ID for this publisher
     */
    int getStreamId();
    
    /**
     * Check if the publisher is connected
     */
    boolean isConnected();
    
    /**
     * Get metrics about this publisher
     */
    PublisherMetrics getMetrics();
}