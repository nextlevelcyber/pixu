package com.bedrock.mm.aeron.bus;

import com.bedrock.mm.common.event.EventEnvelope;

/**
 * Unified event bus abstraction backed by InProc ringbuffer or Aeron IPC/UDP.
 */
public interface EventBus extends AutoCloseable {
    /** Publish one envelope to the bus */
    boolean publish(EventEnvelope envelope);

    /** Register a low-level raw consumer to receive decoded envelopes */
    void registerConsumer(java.util.function.Consumer<EventEnvelope> consumer);

    /** Start polling/dispatching in current thread (blocking) */
    void runLoop();

    /** Signal to stop the loop */
    void stop();

    /** Runtime counters for backpressure/drop/codec failures. */
    default EventBusMetricsSnapshot metrics() {
        return new EventBusMetricsSnapshot(0, 0, 0, 0, 0, 0);
    }
}
