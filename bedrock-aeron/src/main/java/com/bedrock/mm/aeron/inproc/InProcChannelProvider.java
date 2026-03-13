package com.bedrock.mm.aeron.inproc;

import com.bedrock.mm.common.channel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.agrona.concurrent.ringbuffer.OneToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;
import org.agrona.concurrent.UnsafeBuffer;
import java.nio.ByteBuffer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * In-process channel provider using Agrona RingBuffer
 */
public class InProcChannelProvider implements ChannelProvider {
    private static final Logger log = LoggerFactory.getLogger(InProcChannelProvider.class);
    
    private final ConcurrentMap<Integer, OneToOneRingBuffer> ringBuffers = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    
    @Override
    public <T> ChannelPublisher<T> createPublisher(ChannelConfig config) {
        if (!supports(config.getMode())) {
            throw new IllegalArgumentException("Unsupported mode: " + config.getMode());
        }
        
        OneToOneRingBuffer ringBuffer = getOrCreateRingBuffer(config);
        return new InProcChannelPublisher<>(ringBuffer, config);
    }
    
    @Override
    public <T> ChannelSubscriber<T> createSubscriber(ChannelConfig config) {
        if (!supports(config.getMode())) {
            throw new IllegalArgumentException("Unsupported mode: " + config.getMode());
        }
        
        OneToOneRingBuffer ringBuffer = getOrCreateRingBuffer(config);
        return new InProcChannelSubscriber<>(ringBuffer, config);
    }
    
    @Override
    public String getName() {
        return "InProc";
    }
    
    @Override
    public boolean supports(ChannelMode mode) {
        return mode == ChannelMode.IN_PROC;
    }
    
    @Override
    public void initialize(ChannelProviderConfig config) {
        if (initialized) {
            return;
        }
        
        log.info("Initializing InProc channel provider");
        initialized = true;
    }
    
    @Override
    public void shutdown() {
        if (!initialized) {
            return;
        }
        
        log.info("Shutting down InProc channel provider");
        ringBuffers.clear();
        initialized = false;
    }
    
    private OneToOneRingBuffer getOrCreateRingBuffer(ChannelConfig config) {
        return ringBuffers.computeIfAbsent(config.getStreamId(), streamId -> {
            int desiredPayloadCapacity = config.getRingBufferSize();
            int payloadCapacity = nextPowerOfTwo(desiredPayloadCapacity);
            int totalCapacity = payloadCapacity + RingBufferDescriptor.TRAILER_LENGTH;

            UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(totalCapacity));
            log.info("Created ring buffer for stream {} with payload capacity {} and total {} (incl trailer)",
                    streamId, payloadCapacity, totalCapacity);
            return new OneToOneRingBuffer(buffer);
        });
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 0) {
            return 1024; // sensible default payload capacity
        }
        int highest = Integer.highestOneBit(value);
        return (value == highest) ? value : highest << 1;
    }
}