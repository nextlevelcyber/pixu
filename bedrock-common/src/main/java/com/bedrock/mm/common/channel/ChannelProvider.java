package com.bedrock.mm.common.channel;

/**
 * SPI interface for channel providers
 * Implementations: InProcChannelProvider, AeronChannelProvider
 */
public interface ChannelProvider {
    
    /**
     * Create a publisher for the given stream
     * @param config channel configuration
     * @return channel publisher
     */
    <T> ChannelPublisher<T> createPublisher(ChannelConfig config);
    
    /**
     * Create a subscriber for the given stream
     * @param config channel configuration
     * @return channel subscriber
     */
    <T> ChannelSubscriber<T> createSubscriber(ChannelConfig config);
    
    /**
     * Get the provider name
     */
    String getName();
    
    /**
     * Check if this provider supports the given mode
     */
    boolean supports(ChannelMode mode);
    
    /**
     * Initialize the provider
     */
    void initialize(ChannelProviderConfig config);
    
    /**
     * Shutdown the provider
     */
    void shutdown();
}