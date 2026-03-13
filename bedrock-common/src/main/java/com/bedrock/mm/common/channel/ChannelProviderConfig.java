package com.bedrock.mm.common.channel;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Channel provider configuration
 */
@Data
@Builder
public class ChannelProviderConfig {
    private final ChannelMode mode;
    private final String aeronDir;
    private final boolean embeddedMediaDriver;
    private final Map<String, String> properties;
    
    @Builder.Default
    private final int mediaDriverThreads = 1;
    
    @Builder.Default
    private final boolean deleteAeronDirectoryOnStart = false;
    
    @Builder.Default
    private final long mediaDriverTimeoutMs = 10000;
}