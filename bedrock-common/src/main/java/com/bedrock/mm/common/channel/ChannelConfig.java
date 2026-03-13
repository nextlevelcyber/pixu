package com.bedrock.mm.common.channel;

import lombok.Builder;
import lombok.Data;

/**
 * Channel configuration
 */
@Data
@Builder
public class ChannelConfig {
    
    private final ChannelMode mode;
    private final int streamId;
    private final String endpoint;
    private final int mtu;
    private final int termBufferLength;
    private final Class<?> messageType;
    private final String name;
    
    @Builder.Default
    private final int ringBufferSize = 2048; // 1MB for in-proc mode
    
    @Builder.Default
    private final boolean enableBackpressure = true;
    
    public static ChannelConfig inProc(int streamId, Class<?> messageType) {
        return ChannelConfig.builder()
                .mode(ChannelMode.IN_PROC)
                .streamId(streamId)
                .messageType(messageType)
                .name("inproc-" + streamId)
                .build();
    }
    
    public static ChannelConfig aeronIpc(int streamId, Class<?> messageType) {
        return ChannelConfig.builder()
                .mode(ChannelMode.AERON_IPC)
                .streamId(streamId)
                .messageType(messageType)
                .endpoint("aeron:ipc")
                .name("aeron-ipc-" + streamId)
                .build();
    }
    
    public static ChannelConfig aeronUdp(int streamId, String endpoint, Class<?> messageType) {
        return ChannelConfig.builder()
                .mode(ChannelMode.AERON_UDP)
                .streamId(streamId)
                .endpoint(endpoint)
                .messageType(messageType)
                .mtu(1408)
                .termBufferLength(64 * 1024 * 1024)
                .name("aeron-udp-" + streamId)
                .build();
    }
}