package com.bedrock.mm.common.channel;

/**
 * Channel communication modes
 */
public enum ChannelMode {
    /**
     * In-process communication using Agrona RingBuffer
     */
    IN_PROC,
    
    /**
     * Aeron IPC (Inter-Process Communication)
     */
    AERON_IPC,
    
    /**
     * Aeron UDP (network communication)
     */
    AERON_UDP
}