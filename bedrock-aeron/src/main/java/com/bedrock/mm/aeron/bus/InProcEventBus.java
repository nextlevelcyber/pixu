package com.bedrock.mm.aeron.bus;

import com.bedrock.mm.common.event.EventEnvelope;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.concurrent.ringbuffer.ManyToOneRingBuffer;
import org.agrona.concurrent.ringbuffer.RingBufferDescriptor;
import org.agrona.concurrent.MessageHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-ringbuffer event bus for inproc mode, enforcing single-thread,
 * lock-free dispatch.
 */
public class InProcEventBus implements EventBus {
    private static final Logger log = LoggerFactory.getLogger(InProcEventBus.class);
    private final ManyToOneRingBuffer ringBuffer;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<java.util.function.Consumer<EventEnvelope>> consumers = new CopyOnWriteArrayList<>();
    private final AtomicLong publishAttempts = new AtomicLong(0);
    private final AtomicLong publishSuccess = new AtomicLong(0);
    private final AtomicLong publishEncodeFailures = new AtomicLong(0);
    private final AtomicLong publishBackpressureDrops = new AtomicLong(0);
    private final AtomicLong decodeFailures = new AtomicLong(0);
    private final AtomicLong consumerDispatchFailures = new AtomicLong(0);

    public InProcEventBus(int capacity) {
        int payloadCapacity = nextPowerOfTwo(capacity);
        int totalCapacity = payloadCapacity + RingBufferDescriptor.TRAILER_LENGTH;
        UnsafeBuffer buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(totalCapacity));
        this.ringBuffer = new ManyToOneRingBuffer(buffer);
    }

    @Override
    public boolean publish(EventEnvelope envelope) {
        publishAttempts.incrementAndGet();
        byte[] bytes;
        try {
            bytes = EventEnvelopeWireCodec.encode(envelope);
        } catch (Exception e) {
            publishEncodeFailures.incrementAndGet();
            log.warn("Failed to encode event envelope: {}", e.toString());
            return false;
        }
        int index = ringBuffer.tryClaim(1, bytes.length);
        if (index > 0) {
            ringBuffer.buffer().putBytes(index, bytes);
            ringBuffer.commit(index);
            publishSuccess.incrementAndGet();
            return true;
        }
        publishBackpressureDrops.incrementAndGet();
        return false;
    }

    @Override
    public void registerConsumer(java.util.function.Consumer<EventEnvelope> consumer) {
        consumers.add(consumer);
    }

    @Override
    public void runLoop() {
        running.set(true);
        MessageHandler handler = (msgTypeId, buffer, index, length) -> {
            EventEnvelope env;
            try {
                env = EventEnvelopeWireCodec.decode(buffer, index, length);
            } catch (Exception e) {
                decodeFailures.incrementAndGet();
                log.warn("Failed to decode event envelope: {}", e.toString());
                return;
            }
            for (java.util.function.Consumer<EventEnvelope> c : consumers) {
                try {
                    c.accept(env);
                } catch (Exception e) {
                    consumerDispatchFailures.incrementAndGet();
                    log.warn("Consumer dispatch failure: {}", e.toString());
                }
            }
        };
        while (running.get()) {
            ringBuffer.read(handler, Integer.MAX_VALUE);
        }
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public void close() {
        stop();
    }

    @Override
    public EventBusMetricsSnapshot metrics() {
        return new EventBusMetricsSnapshot(
                publishAttempts.get(),
                publishSuccess.get(),
                publishEncodeFailures.get(),
                publishBackpressureDrops.get(),
                decodeFailures.get(),
                consumerDispatchFailures.get());
    }

    private static int nextPowerOfTwo(int value) {
        if (value <= 0)
            return 1024;
        int highest = Integer.highestOneBit(value);
        return (value == highest) ? value : highest << 1;
    }
}
