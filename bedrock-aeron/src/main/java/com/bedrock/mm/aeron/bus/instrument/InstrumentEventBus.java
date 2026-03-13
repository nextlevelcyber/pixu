package com.bedrock.mm.aeron.bus.instrument;

import com.bedrock.mm.common.event.EventEnvelope;
import com.lmax.disruptor.*;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Per-instrument event bus using LMAX Disruptor.
 * <p>
 * Each instrument gets a dedicated Disruptor ring buffer with:
 * - Fixed buffer size (1024 slots)
 * - Single consumer thread (zero coordination overhead)
 * - Strategy consumers dispatched first, then adapter consumers
 * <p>
 * Zero-allocation on hot path: reuses EventEnvelope instances via object pool.
 */
public class InstrumentEventBus {
    private static final Logger log = LoggerFactory.getLogger(InstrumentEventBus.class);
    private static final int BUFFER_SIZE = 1024;

    private final String instrumentId;
    private final Disruptor<EventHolder> disruptor;
    private final RingBuffer<EventHolder> ringBuffer;
    private final List<Consumer<EventEnvelope>> strategyConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<EventEnvelope>> adapterConsumers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong publishAttempts = new AtomicLong(0);
    private final AtomicLong publishSuccess = new AtomicLong(0);
    private final AtomicLong publishDropped = new AtomicLong(0);

    /**
     * Holder for EventEnvelope in ring buffer (reused, zero-allocation).
     */
    static class EventHolder {
        EventEnvelope envelope;
    }

    public InstrumentEventBus(String instrumentId) {
        this.instrumentId = instrumentId;

        ThreadFactory threadFactory = r -> {
            Thread t = new Thread(r, "instrument-bus-" + instrumentId);
            t.setDaemon(true);
            return t;
        };

        this.disruptor = new Disruptor<>(
                EventHolder::new,
                BUFFER_SIZE,
                threadFactory,
                ProducerType.MULTI,
                new BlockingWaitStrategy());

        this.disruptor.handleEventsWith(this::onEvent);
        this.ringBuffer = disruptor.getRingBuffer();
    }

    /**
     * Publish event to this instrument's ring buffer.
     *
     * @param envelope event envelope
     * @return true if published successfully, false if ring buffer full
     */
    public boolean publish(EventEnvelope envelope) {
        publishAttempts.incrementAndGet();
        try {
            long sequence = ringBuffer.tryNext();
            try {
                EventHolder holder = ringBuffer.get(sequence);
                holder.envelope = envelope;
            } finally {
                ringBuffer.publish(sequence);
            }
            publishSuccess.incrementAndGet();
            return true;
        } catch (InsufficientCapacityException e) {
            publishDropped.incrementAndGet();
            if (publishDropped.get() % 1000 == 0) {
                log.warn("InstrumentEventBus[{}]: ring buffer full, dropped {} events",
                        instrumentId, publishDropped.get());
            }
            return false;
        }
    }

    /**
     * Register a strategy consumer (receives events before adapters).
     *
     * @param consumer event consumer
     */
    public void registerStrategyConsumer(Consumer<EventEnvelope> consumer) {
        strategyConsumers.add(consumer);
        log.debug("InstrumentEventBus[{}]: registered strategy consumer", instrumentId);
    }

    /**
     * Register an adapter consumer (receives events after strategies).
     *
     * @param consumer event consumer
     */
    public void registerAdapterConsumer(Consumer<EventEnvelope> consumer) {
        adapterConsumers.add(consumer);
        log.debug("InstrumentEventBus[{}]: registered adapter consumer", instrumentId);
    }

    /**
     * Start the Disruptor event loop.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            disruptor.start();
            log.info("InstrumentEventBus[{}]: started", instrumentId);
        }
    }

    /**
     * Stop the Disruptor event loop.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            try {
                disruptor.shutdown();
                log.info("InstrumentEventBus[{}]: stopped", instrumentId);
            } catch (Exception e) {
                log.warn("InstrumentEventBus[{}]: error during shutdown: {}", instrumentId, e.toString());
            }
        }
    }

    /**
     * Get instrument ID.
     *
     * @return instrument identifier
     */
    public String getInstrumentId() {
        return instrumentId;
    }

    /**
     * Check if event bus is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Get metrics snapshot.
     *
     * @return metrics
     */
    public Metrics getMetrics() {
        return new Metrics(
                publishAttempts.get(),
                publishSuccess.get(),
                publishDropped.get());
    }

    /**
     * Disruptor event handler: dispatch to strategy consumers first, then adapter consumers.
     */
    private void onEvent(EventHolder holder, long sequence, boolean endOfBatch) {
        EventEnvelope envelope = holder.envelope;
        if (envelope == null) {
            return;
        }

        // Strategy consumers first (preserves existing EventDispatcher ordering)
        for (Consumer<EventEnvelope> consumer : strategyConsumers) {
            try {
                consumer.accept(envelope);
            } catch (Exception e) {
                log.warn("InstrumentEventBus[{}]: strategy consumer failed: {}", instrumentId, e.toString());
            }
        }

        // Adapter consumers second
        for (Consumer<EventEnvelope> consumer : adapterConsumers) {
            try {
                consumer.accept(envelope);
            } catch (Exception e) {
                log.warn("InstrumentEventBus[{}]: adapter consumer failed: {}", instrumentId, e.toString());
            }
        }

        // Clear reference for GC (allow envelope to be reclaimed)
        holder.envelope = null;
    }

    /**
     * Metrics snapshot.
     */
    public record Metrics(
            long publishAttempts,
            long publishSuccess,
            long publishDropped) {
    }
}
