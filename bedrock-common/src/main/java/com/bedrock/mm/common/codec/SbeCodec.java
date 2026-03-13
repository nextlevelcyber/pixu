package com.bedrock.mm.common.codec;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;

/**
 * SBE codec utilities for zero-copy encoding/decoding
 */
public class SbeCodec {
    
    private static final int MESSAGE_HEADER_LENGTH = 8;
    
    /**
     * Encode a message to a direct buffer
     * @param encoder the SBE encoder
     * @param buffer the target buffer
     * @param offset the offset in the buffer
     * @return the number of bytes written
     */
    public static <T> int encode(T encoder, MutableDirectBuffer buffer, int offset) {
        // This will be implemented with generated SBE encoders
        // For now, return a placeholder
        return MESSAGE_HEADER_LENGTH;
    }
    
    /**
     * Decode a message from a direct buffer
     * @param decoder the SBE decoder
     * @param buffer the source buffer
     * @param offset the offset in the buffer
     * @param length the length of the message
     * @return the decoded message
     */
    public static <T> T decode(T decoder, DirectBuffer buffer, int offset, int length) {
        // This will be implemented with generated SBE decoders
        // For now, return the decoder itself
        return decoder;
    }
    
    /**
     * Get the template ID from a message buffer
     */
    public static int getTemplateId(DirectBuffer buffer, int offset) {
        return buffer.getShort(offset + 2, java.nio.ByteOrder.LITTLE_ENDIAN);
    }
    
    /**
     * Get the message length from a message buffer
     */
    public static int getMessageLength(DirectBuffer buffer, int offset) {
        return buffer.getShort(offset, java.nio.ByteOrder.LITTLE_ENDIAN);
    }
    
    /**
     * Create a new unsafe buffer
     */
    public static MutableDirectBuffer allocateBuffer(int capacity) {
        return new UnsafeBuffer(new byte[capacity]);
    }
}