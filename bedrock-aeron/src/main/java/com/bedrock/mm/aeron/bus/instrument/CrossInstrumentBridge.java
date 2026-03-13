package com.bedrock.mm.aeron.bus.instrument;

import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Bridge for cross-instrument event subscription.
 * <p>
 * Allows strategies to subscribe to events from other instruments.
 * Example: BTC strategy subscribing to ETH market data for correlation signals.
 * <p>
 * Usage:
 * <pre>
 * // BTC strategy wants to listen to ETH BBO
 * bridge.subscribe("binance:BTC-USDT", "binance:ETH-USDT",
 *                  EventType.MARKET_TICK, envelope -> {
 *     // Handle ETH tick in BTC strategy context
 * });
 * </pre>
 */
public class CrossInstrumentBridge {
    private static final Logger log = LoggerFactory.getLogger(CrossInstrumentBridge.class);

    // Map: source instrument → event type → list of subscriptions
    private final Map<String, Map<EventType, List<Subscription>>> subscriptions = new ConcurrentHashMap<>();

    /**
     * Subscription record.
     */
    private record Subscription(String targetInstrument, Consumer<EventEnvelope> consumer) {
    }

    /**
     * Subscribe to events from another instrument.
     *
     * @param targetInstrument instrument that will receive events (subscriber)
     * @param sourceInstrument instrument producing events (publisher)
     * @param eventType        event type to subscribe to (e.g., MARKET_TICK)
     * @param consumer         callback for event handling
     */
    public void subscribe(String targetInstrument, String sourceInstrument,
                          EventType eventType, Consumer<EventEnvelope> consumer) {
        subscriptions
                .computeIfAbsent(sourceInstrument, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(new Subscription(targetInstrument, consumer));

        log.info("CrossInstrumentBridge: {} subscribed to {} events from {}",
                targetInstrument, eventType, sourceInstrument);
    }

    /**
     * Unsubscribe from events.
     *
     * @param targetInstrument instrument to unsubscribe
     * @param sourceInstrument source instrument
     * @param eventType        event type
     */
    public void unsubscribe(String targetInstrument, String sourceInstrument, EventType eventType) {
        Map<EventType, List<Subscription>> typeMap = subscriptions.get(sourceInstrument);
        if (typeMap == null) {
            return;
        }
        List<Subscription> subs = typeMap.get(eventType);
        if (subs == null) {
            return;
        }
        subs.removeIf(sub -> sub.targetInstrument.equals(targetInstrument));
        log.info("CrossInstrumentBridge: {} unsubscribed from {} events from {}",
                targetInstrument, eventType, sourceInstrument);
    }

    /**
     * Dispatch event to cross-instrument subscribers.
     * <p>
     * Called by InstrumentEventBus after local consumers.
     *
     * @param sourceInstrument instrument producing the event
     * @param envelope         event envelope
     */
    public void dispatch(String sourceInstrument, EventEnvelope envelope) {
        Map<EventType, List<Subscription>> typeMap = subscriptions.get(sourceInstrument);
        if (typeMap == null) {
            return;
        }
        List<Subscription> subs = typeMap.get(envelope.getType());
        if (subs == null || subs.isEmpty()) {
            return;
        }

        for (Subscription sub : subs) {
            try {
                sub.consumer.accept(envelope);
            } catch (Exception e) {
                log.warn("CrossInstrumentBridge: failed to dispatch {} event from {} to {}: {}",
                        envelope.getType(), sourceInstrument, sub.targetInstrument, e.toString());
            }
        }
    }

    /**
     * Get all source instruments that have cross-instrument subscriptions.
     *
     * @return set of source instrument IDs
     */
    public Set<String> getSourceInstruments() {
        return subscriptions.keySet();
    }

    /**
     * Check if there are any subscriptions for a source instrument.
     *
     * @param sourceInstrument source instrument ID
     * @return true if there are subscriptions
     */
    public boolean hasSubscriptions(String sourceInstrument) {
        Map<EventType, List<Subscription>> typeMap = subscriptions.get(sourceInstrument);
        if (typeMap == null) {
            return false;
        }
        return typeMap.values().stream().anyMatch(list -> !list.isEmpty());
    }

    /**
     * Get subscription count for a source instrument.
     *
     * @param sourceInstrument source instrument ID
     * @return number of subscriptions
     */
    public int getSubscriptionCount(String sourceInstrument) {
        Map<EventType, List<Subscription>> typeMap = subscriptions.get(sourceInstrument);
        if (typeMap == null) {
            return 0;
        }
        return typeMap.values().stream().mapToInt(List::size).sum();
    }
}
