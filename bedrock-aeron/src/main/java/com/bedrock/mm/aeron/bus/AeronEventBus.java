package com.bedrock.mm.aeron.bus;

import com.bedrock.mm.aeron.ChannelFactory;
import com.bedrock.mm.common.channel.ChannelConfig;
import com.bedrock.mm.common.channel.ChannelMode;
import com.bedrock.mm.common.channel.ChannelProviderConfig;
import com.bedrock.mm.common.channel.ChannelPublisher;
import com.bedrock.mm.common.channel.ChannelSubscriber;
import com.bedrock.mm.common.event.EventEnvelope;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Aeron-backed event bus using a single stream.
 */
public class AeronEventBus implements EventBus {
    private static final Logger log = LoggerFactory.getLogger(AeronEventBus.class);
    private final ChannelPublisher<byte[]> publisher;
    private final ChannelSubscriber<byte[]> subscriber;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<java.util.function.Consumer<EventEnvelope>> consumers = new CopyOnWriteArrayList<>();
    private final AtomicLong publishAttempts = new AtomicLong(0);
    private final AtomicLong publishSuccess = new AtomicLong(0);
    private final AtomicLong publishEncodeFailures = new AtomicLong(0);
    private final AtomicLong publishBackpressureDrops = new AtomicLong(0);
    private final AtomicLong decodeFailures = new AtomicLong(0);
    private final AtomicLong consumerDispatchFailures = new AtomicLong(0);

    public AeronEventBus(ChannelMode mode, int streamId, String endpoint) {
        this(mode, streamId, endpoint, "/tmp/aeron", true, false);
    }

    public AeronEventBus(ChannelMode mode, int streamId, String endpoint,
                         String aeronDir, boolean embeddedMediaDriver, boolean deleteAeronDirOnStart) {
        // Initialize provider for Aeron modes if needed
        if (mode == ChannelMode.AERON_IPC || mode == ChannelMode.AERON_UDP) {
            ChannelProviderConfig providerConfig = ChannelProviderConfig.builder()
                    .mode(mode)
                    .aeronDir(aeronDir)
                    .embeddedMediaDriver(embeddedMediaDriver)
                    .deleteAeronDirectoryOnStart(deleteAeronDirOnStart)
                    .build();
            ChannelFactory.initializeProvider(mode, providerConfig);
        }

        ChannelConfig config;
        switch (mode) {
            case IN_PROC:
                config = ChannelConfig.inProc(streamId, byte[].class);
                break;
            case AERON_IPC:
                config = ChannelConfig.aeronIpc(streamId, byte[].class);
                break;
            case AERON_UDP:
                config = ChannelConfig.aeronUdp(streamId, endpoint, byte[].class);
                break;
            default:
                throw new IllegalArgumentException("Unsupported mode: " + mode);
        }

        this.publisher = ChannelFactory.createPublisher(config);
        this.subscriber = ChannelFactory.createSubscriber(config);
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
        DirectBuffer buf = new UnsafeBuffer(bytes);
        boolean ok = publisher.publish(buf, 0, bytes.length);
        if (ok) {
            publishSuccess.incrementAndGet();
        } else {
            publishBackpressureDrops.incrementAndGet();
        }
        return ok;
    }

    @Override
    public void registerConsumer(java.util.function.Consumer<EventEnvelope> consumer) {
        consumers.add(consumer);
    }

    @Override
    public void runLoop() {
        running.set(true);
        subscriber.subscribe((buffer, offset, length, ts) -> {
            EventEnvelope env;
            try {
                env = EventEnvelopeWireCodec.decode(buffer, offset, length);
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
        });
        while (running.get()) {
            subscriber.poll(Integer.MAX_VALUE);
        }
    }

    @Override
    public void stop() {
        running.set(false);
    }

    @Override
    public void close() {
        stop();
        try {
            publisher.close();
        } catch (Exception ignored) {
        }
        try {
            subscriber.close();
        } catch (Exception ignored) {
        }
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
}
