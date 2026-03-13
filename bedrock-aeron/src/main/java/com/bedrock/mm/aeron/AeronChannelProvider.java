package com.bedrock.mm.aeron;

import com.bedrock.mm.common.channel.*;
import io.aeron.Aeron;
import io.aeron.driver.MediaDriver;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Aeron-based channel provider
 */
@Slf4j
public class AeronChannelProvider implements ChannelProvider {
    
    private MediaDriver mediaDriver;
    private Aeron aeron;
    private final ConcurrentMap<String, Object> channels = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    private ChannelProviderConfig config;
    
    @Override
    public <T> ChannelPublisher<T> createPublisher(ChannelConfig config) {
        if (!supports(config.getMode())) {
            throw new IllegalArgumentException("Unsupported mode: " + config.getMode());
        }
        
        String key = "pub-" + config.getStreamId();
        return (ChannelPublisher<T>) channels.computeIfAbsent(key, k -> 
            new AeronChannelPublisher<>(aeron, config));
    }
    
    @Override
    public <T> ChannelSubscriber<T> createSubscriber(ChannelConfig config) {
        if (!supports(config.getMode())) {
            throw new IllegalArgumentException("Unsupported mode: " + config.getMode());
        }
        
        String key = "sub-" + config.getStreamId();
        return (ChannelSubscriber<T>) channels.computeIfAbsent(key, k -> 
            new AeronChannelSubscriber<>(aeron, config));
    }
    
    @Override
    public String getName() {
        return "Aeron";
    }
    
    @Override
    public boolean supports(ChannelMode mode) {
        return mode == ChannelMode.AERON_IPC || mode == ChannelMode.AERON_UDP;
    }
    
    @Override
    public void initialize(ChannelProviderConfig config) {
        if (initialized) {
            return;
        }
        
        this.config = config;
        
        log.info("Initializing Aeron channel provider");
        
        if (config.isEmbeddedMediaDriver()) {
            MediaDriver.Context mediaDriverContext = new MediaDriver.Context()
                    .aeronDirectoryName(config.getAeronDir())
                    .dirDeleteOnStart(config.isDeleteAeronDirectoryOnStart())
                    .threadingMode(io.aeron.driver.ThreadingMode.SHARED);
            
            mediaDriver = MediaDriver.launchEmbedded(mediaDriverContext);
            log.info("Started embedded media driver at {}", config.getAeronDir());
        }
        
        Aeron.Context aeronContext = new Aeron.Context()
                .aeronDirectoryName(config.getAeronDir());
        
        aeron = Aeron.connect(aeronContext);
        log.info("Connected to Aeron at {}", config.getAeronDir());
        
        initialized = true;
    }
    
    @Override
    public void shutdown() {
        if (!initialized) {
            return;
        }
        
        log.info("Shutting down Aeron channel provider");
        
        // Close all channels
        channels.values().forEach(channel -> {
            try {
                if (channel instanceof AutoCloseable) {
                    ((AutoCloseable) channel).close();
                }
            } catch (Exception e) {
                log.warn("Error closing channel", e);
            }
        });
        channels.clear();
        
        // Close Aeron
        if (aeron != null) {
            aeron.close();
            aeron = null;
        }
        
        // Close media driver if embedded
        if (mediaDriver != null) {
            mediaDriver.close();
            mediaDriver = null;
        }
        
        initialized = false;
    }
}