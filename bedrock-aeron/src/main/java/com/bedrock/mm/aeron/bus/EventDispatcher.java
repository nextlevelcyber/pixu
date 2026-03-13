package com.bedrock.mm.aeron.bus;

import com.bedrock.mm.common.event.EventEnvelope;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Ordered dispatcher: strategy consumers first, then adapter consumers.
 */
public class EventDispatcher implements Consumer<EventEnvelope> {
    private final List<Consumer<EventEnvelope>> strategyConsumers = new CopyOnWriteArrayList<>();
    private final List<Consumer<EventEnvelope>> adapterConsumers = new CopyOnWriteArrayList<>();

    public void registerStrategyConsumer(Consumer<EventEnvelope> consumer) {
        strategyConsumers.add(consumer);
    }

    public void registerAdapterConsumer(Consumer<EventEnvelope> consumer) {
        adapterConsumers.add(consumer);
    }

    @Override
    public void accept(EventEnvelope env) {
        // strategy first
        for (Consumer<EventEnvelope> c : strategyConsumers) {
            c.accept(env);
        }
        // adapter second
        for (Consumer<EventEnvelope> c : adapterConsumers) {
            c.accept(env);
        }
    }
}
