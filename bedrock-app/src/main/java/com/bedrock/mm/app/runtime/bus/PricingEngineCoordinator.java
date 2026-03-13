package com.bedrock.mm.app.runtime.bus;

import com.bedrock.mm.aeron.bus.EventBus;
import com.bedrock.mm.aeron.bus.instrument.InstrumentEventBus;
import com.bedrock.mm.app.config.BedrockConfig;
import com.bedrock.mm.app.runtime.bus.instrument.InstrumentEventBusCoordinator;
import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventSerdeRegistry;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.pricing.PricingOrchestrator;
import com.bedrock.mm.pricing.PricingOrchestratorFactory;
import com.bedrock.mm.pricing.model.QuoteTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pricing engine lifecycle coordinator.
 * <p>
 * Creates per-instrument PricingOrchestrator instances and registers
 * PricingBusConsumer on the appropriate bus (per-instrument or unified).
 * <p>
 * Enabled via bedrock.pricing.enabled=true.
 */
@Component
@ConditionalOnProperty(name = "bedrock.pricing.enabled", havingValue = "true")
public class PricingEngineCoordinator {
    private static final Logger log = LoggerFactory.getLogger(PricingEngineCoordinator.class);

    private final EventSerdeRegistry serdeRegistry;
    private final EventSerde eventSerde;
    private final DeadLetterChannel deadLetterChannel;
    private final InstrumentEventBusCoordinator instrBusCoordinator;
    private final EventBus eventBus;

    private final Map<String, PricingOrchestrator> orchestrators = new ConcurrentHashMap<>();
    private final AtomicInteger instrumentIdCounter = new AtomicInteger(0);

    @Autowired
    public PricingEngineCoordinator(
            EventSerdeRegistry serdeRegistry,
            EventSerde eventSerde,
            DeadLetterChannel deadLetterChannel,
            @Autowired(required = false) InstrumentEventBusCoordinator instrBusCoordinator,
            @Autowired(required = false) EventBus eventBus) {
        this.serdeRegistry = serdeRegistry;
        this.eventSerde = eventSerde;
        this.deadLetterChannel = deadLetterChannel;
        this.instrBusCoordinator = instrBusCoordinator;
        this.eventBus = eventBus;
    }

    /**
     * Start pricing engine: create orchestrators for configured symbols and register consumers.
     *
     * @param config Bedrock configuration
     */
    public void start(BedrockConfig config) {
        List<String> symbols = config.getPricing().getSymbols();
        if (symbols == null || symbols.isEmpty()) {
            log.info("PricingEngineCoordinator: no symbols configured, skipping start");
            return;
        }

        for (String symbol : symbols) {
            int instrId = instrumentIdCounter.incrementAndGet();
            final String sym = symbol;

            PricingOrchestrator orchestrator = PricingOrchestratorFactory.createDefault(
                    instrId, target -> publishQuoteTarget(sym, target));
            orchestrators.put(symbol, orchestrator);

            PricingBusConsumer consumer = new PricingBusConsumer(symbol, orchestrator,
                    serdeRegistry, deadLetterChannel);

            if (instrBusCoordinator != null) {
                InstrumentEventBus bus = instrBusCoordinator.getOrCreateBus(symbol);
                bus.registerStrategyConsumer(consumer);
                log.info("PricingEngineCoordinator: registered consumer for {} on per-instrument bus (instrId={})",
                        symbol, instrId);
            } else if (eventBus != null) {
                eventBus.registerConsumer(consumer);
                log.info("PricingEngineCoordinator: registered consumer for {} on unified bus (instrId={})",
                        symbol, instrId);
            } else {
                log.warn("PricingEngineCoordinator: no event bus available for {}", symbol);
            }
        }

        log.info("PricingEngineCoordinator: started {} orchestrators", orchestrators.size());
    }

    /**
     * Stop the pricing engine. Disruptor shutdown is delegated to InstrumentEventBusCoordinator.
     */
    public void stop() {
        orchestrators.clear();
        log.info("PricingEngineCoordinator: stopped");
    }

    /**
     * Get the orchestrator for a given symbol (for inspection/testing).
     *
     * @param symbol instrument symbol
     * @return PricingOrchestrator or null if not registered
     */
    public PricingOrchestrator getOrchestrator(String symbol) {
        return orchestrators.get(symbol);
    }

    /**
     * Get all registered orchestrators (unmodifiable view).
     */
    public Map<String, PricingOrchestrator> getAllOrchestrators() {
        return Collections.unmodifiableMap(orchestrators);
    }

    private void publishQuoteTarget(String symbol, QuoteTarget target) {
        try {
            QuoteTarget copy = target.copy();
            byte[] payloadBytes = eventSerde.serialize(copy);
            EventEnvelope env = new EventEnvelope(EventType.QUOTE_TARGET, payloadBytes,
                    eventSerde.codec(), symbol, target.publishNanos, target.seqId);
            if (instrBusCoordinator != null) {
                instrBusCoordinator.publish(env);
            } else if (eventBus != null) {
                eventBus.publish(env);
            }
        } catch (Exception e) {
            log.warn("PricingEngineCoordinator: error publishing QuoteTarget for {}: {}",
                    symbol, e.toString());
        }
    }
}
