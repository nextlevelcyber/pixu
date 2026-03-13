package com.bedrock.mm.app.runtime.bus.instrument;

import com.bedrock.mm.adapter.AdapterService;
import com.bedrock.mm.adapter.TradingAdapter;
import com.bedrock.mm.aeron.bus.instrument.InstrumentEventBus;
import com.bedrock.mm.app.config.BedrockConfig;
import com.bedrock.mm.app.runtime.bus.AdapterUpdateBridge;
import com.bedrock.mm.app.runtime.bus.DeadLetterChannel;
import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventSerdeRegistry;
import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.md.MarketDataService;
import com.bedrock.mm.strategy.Strategy;
import com.bedrock.mm.strategy.StrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Per-instrument event bus coordinator.
 * <p>
 * Creates and manages InstrumentEventBus instances on-demand per instrument.
 * Registers strategies and adapters to their respective instrument buses.
 * <p>
 * Feature flag: bedrock.bus.perInstrumentEnabled
 * - true: uses InstrumentEventBus (dedicated Disruptor per instrument)
 * - false: falls back to EventBusCoordinator (unified bus)
 */
@Component
@ConditionalOnProperty(name = "bedrock.bus.perInstrumentEnabled", havingValue = "true")
public class InstrumentEventBusCoordinator {
    private static final Logger log = LoggerFactory.getLogger(InstrumentEventBusCoordinator.class);

    private final MarketDataService marketDataService;
    private final StrategyService strategyService;
    private final AdapterService adapterService;
    private final EventSerdeRegistry eventSerdeRegistry;
    private final DeadLetterChannel deadLetterChannel;
    private final EventSerde eventSerde;

    private final Map<String, InstrumentEventBus> busByInstrument = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean wired = new AtomicBoolean(false);

    @Autowired
    public InstrumentEventBusCoordinator(
            @Autowired(required = false) MarketDataService marketDataService,
            @Autowired(required = false) StrategyService strategyService,
            @Autowired(required = false) AdapterService adapterService,
            EventSerdeRegistry eventSerdeRegistry,
            DeadLetterChannel deadLetterChannel,
            EventSerde eventSerde) {
        this.marketDataService = marketDataService;
        this.strategyService = strategyService;
        this.adapterService = adapterService;
        this.eventSerdeRegistry = eventSerdeRegistry;
        this.deadLetterChannel = deadLetterChannel;
        this.eventSerde = eventSerde;
    }

    /**
     * Start all registered instrument buses.
     *
     * @param config Bedrock configuration
     */
    public void start(BedrockConfig config) {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        wireConsumers(config);

        for (Map.Entry<String, InstrumentEventBus> entry : busByInstrument.entrySet()) {
            try {
                entry.getValue().start();
                log.info("InstrumentEventBusCoordinator: started bus for instrument {}", entry.getKey());
            } catch (Exception e) {
                log.error("InstrumentEventBusCoordinator: failed to start bus for {}: {}",
                        entry.getKey(), e.toString());
            }
        }

        log.info("InstrumentEventBusCoordinator: started {} instrument buses", busByInstrument.size());
    }

    /**
     * Stop all instrument buses.
     */
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }

        for (Map.Entry<String, InstrumentEventBus> entry : busByInstrument.entrySet()) {
            try {
                entry.getValue().stop();
                log.info("InstrumentEventBusCoordinator: stopped bus for instrument {}", entry.getKey());
            } catch (Exception e) {
                log.warn("InstrumentEventBusCoordinator: error stopping bus for {}: {}",
                        entry.getKey(), e.toString());
            }
        }

        busByInstrument.clear();
        log.info("InstrumentEventBusCoordinator: stopped all instrument buses");
    }

    /**
     * Get or create an InstrumentEventBus for the given instrument.
     *
     * @param instrumentId instrument identifier (symbol name)
     * @return instrument event bus
     */
    public InstrumentEventBus getOrCreateBus(String instrumentId) {
        return busByInstrument.computeIfAbsent(instrumentId, id -> {
            log.info("InstrumentEventBusCoordinator: creating bus for instrument {}", id);
            return new InstrumentEventBus(id);
        });
    }

    /**
     * Register a strategy to the specified instruments.
     * <p>
     * Creates per-instrument consumers that deliver events to this strategy.
     *
     * @param strategy      strategy to register
     * @param instrumentIds instrument identifiers (symbol names)
     */
    public void registerStrategy(Strategy strategy, String[] instrumentIds) {
        if (instrumentIds == null || instrumentIds.length == 0) {
            log.warn("InstrumentEventBusCoordinator: no instruments specified for strategy {}", strategy.getName());
            return;
        }

        for (String instrumentId : instrumentIds) {
            InstrumentEventBus bus = getOrCreateBus(instrumentId);
            InstrumentStrategyBusConsumer consumer = new InstrumentStrategyBusConsumer(
                    instrumentId,
                    marketDataService,
                    Collections.singletonList(strategy),
                    eventSerdeRegistry,
                    deadLetterChannel);
            bus.registerStrategyConsumer(consumer);
            log.info("InstrumentEventBusCoordinator: registered strategy {} to instrument {}",
                    strategy.getName(), instrumentId);
        }
    }

    /**
     * Register an adapter to the specified instruments.
     * <p>
     * Creates per-instrument consumers that deliver order commands to this adapter.
     *
     * @param adapter       adapter to register
     * @param instrumentIds instrument identifiers (symbol names)
     */
    public void registerAdapter(TradingAdapter adapter, String[] instrumentIds) {
        if (instrumentIds == null || instrumentIds.length == 0) {
            log.warn("InstrumentEventBusCoordinator: no instruments specified for adapter {}", adapter.getName());
            return;
        }

        for (String instrumentId : instrumentIds) {
            InstrumentEventBus bus = getOrCreateBus(instrumentId);
            InstrumentAdapterBusConsumer consumer = new InstrumentAdapterBusConsumer(
                    instrumentId,
                    adapter,
                    eventSerdeRegistry,
                    deadLetterChannel);
            bus.registerAdapterConsumer(consumer);
            log.info("InstrumentEventBusCoordinator: registered adapter {} to instrument {}",
                    adapter.getName(), instrumentId);
        }
    }

    /**
     * Publish event to the appropriate instrument bus.
     *
     * @param envelope event envelope
     * @return true if published successfully
     */
    public boolean publish(EventEnvelope envelope) {
        String symbol = envelope.getSymbol();
        if (symbol == null || symbol.isEmpty()) {
            log.warn("InstrumentEventBusCoordinator: envelope missing symbol, cannot route: {}", envelope.getType());
            return false;
        }

        InstrumentEventBus bus = busByInstrument.get(symbol);
        if (bus == null) {
            log.warn("InstrumentEventBusCoordinator: no bus found for instrument {}", symbol);
            return false;
        }

        return bus.publish(envelope);
    }

    /**
     * Get all registered instrument buses.
     *
     * @return map of instrument ID to event bus
     */
    public Map<String, InstrumentEventBus> getAllBuses() {
        return Collections.unmodifiableMap(busByInstrument);
    }

    private void wireConsumers(BedrockConfig config) {
        if (!wired.compareAndSet(false, true)) {
            return;
        }

        // Wire strategies to their instruments
        if (marketDataService != null && strategyService != null && config.isStrategyEnabled()) {
            for (Map.Entry<String, Strategy> entry : strategyService.getAllStrategies().entrySet()) {
                Strategy strategy = entry.getValue();
                Symbol[] symbols = strategy.getSymbols();
                if (symbols != null && symbols.length > 0) {
                    String[] instrumentIds = Arrays.stream(symbols)
                            .map(Symbol::getName)
                            .toArray(String[]::new);
                    registerStrategy(strategy, instrumentIds);
                }
            }
        }

        // Wire adapters to their instruments
        if (adapterService != null && config.isAdapterEnabled()) {
            for (TradingAdapter adapter : adapterService.getAllAdapters()) {
                List<Symbol> symbols = adapter.getSupportedSymbols();
                if (symbols != null && !symbols.isEmpty()) {
                    String[] instrumentIds = symbols.stream()
                            .map(Symbol::getName)
                            .toArray(String[]::new);
                    registerAdapter(adapter, instrumentIds);
                }
            }

            // Attach adapter update bridge (order acks/fills back to bus)
            new AdapterUpdateBridge(adapterService, new InstrumentEventBusPublisher(), eventSerde, deadLetterChannel)
                    .attach();
        }
    }

    /**
     * Adapter for publishing events from AdapterUpdateBridge to per-instrument buses.
     */
    private class InstrumentEventBusPublisher implements com.bedrock.mm.aeron.bus.EventBus {
        @Override
        public boolean publish(EventEnvelope envelope) {
            InstrumentEventBusCoordinator.this.publish(envelope);
            return true;
        }

        @Override
        public void registerConsumer(java.util.function.Consumer<EventEnvelope> consumer) {
            // Not used in per-instrument mode
        }

        @Override
        public void runLoop() {
            // Not used in per-instrument mode (each InstrumentEventBus runs its own loop)
        }

        @Override
        public void stop() {
            // Delegated to InstrumentEventBusCoordinator.stop()
        }

        @Override
        public void close() {
            // Delegated to InstrumentEventBusCoordinator.stop()
        }
    }
}
