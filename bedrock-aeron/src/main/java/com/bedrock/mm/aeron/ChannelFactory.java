package com.bedrock.mm.aeron;

import com.bedrock.mm.aeron.inproc.InProcChannelProvider;
import com.bedrock.mm.common.channel.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Factory for creating channel publishers and subscribers
 */
@Slf4j
public class ChannelFactory {
    
    private static final ConcurrentMap<ChannelMode, ChannelProvider> providers = new ConcurrentHashMap<>();
    
    static {
        // Register built-in providers
        registerProvider(new InProcChannelProvider());
        registerProvider(new AeronChannelProvider());
        
        // Load providers via SPI
        ServiceLoader<ChannelProvider> loader = ServiceLoader.load(ChannelProvider.class);
        for (ChannelProvider provider : loader) {
            registerProvider(provider);
        }
    }
    
    public static void registerProvider(ChannelProvider provider) {
        for (ChannelMode mode : ChannelMode.values()) {
            if (provider.supports(mode)) {
                providers.put(mode, provider);
                log.info("Registered channel provider {} for mode {}", provider.getName(), mode);
            }
        }
    }
    
    public static <T> ChannelPublisher<T> createPublisher(ChannelConfig config) {
        ChannelProvider provider = providers.get(config.getMode());
        if (provider == null) {
            throw new IllegalArgumentException("No provider found for mode: " + config.getMode());
        }
        return provider.createPublisher(config);
    }
    
    public static <T> ChannelSubscriber<T> createSubscriber(ChannelConfig config) {
        ChannelProvider provider = providers.get(config.getMode());
        if (provider == null) {
            throw new IllegalArgumentException("No provider found for mode: " + config.getMode());
        }
        return provider.createSubscriber(config);
    }
    
    public static void initializeProvider(ChannelMode mode, ChannelProviderConfig config) {
        ChannelProvider provider = providers.get(mode);
        if (provider != null) {
            provider.initialize(config);
        }
    }
    
    public static void shutdownProvider(ChannelMode mode) {
        ChannelProvider provider = providers.get(mode);
        if (provider != null) {
            provider.shutdown();
        }
    }
    
    public static void shutdownAll() {
        providers.values().forEach(ChannelProvider::shutdown);
    }
}