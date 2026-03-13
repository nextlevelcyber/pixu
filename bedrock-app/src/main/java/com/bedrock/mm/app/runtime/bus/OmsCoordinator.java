package com.bedrock.mm.app.runtime.bus;

import com.bedrock.mm.aeron.bus.EventBus;
import com.bedrock.mm.aeron.bus.instrument.InstrumentEventBus;
import com.bedrock.mm.app.config.BedrockConfig;
import com.bedrock.mm.app.runtime.bus.instrument.InstrumentEventBusCoordinator;
import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventSerdeRegistry;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.PositionPayload;
import com.bedrock.mm.monitor.MetricRegistry;
import com.bedrock.mm.monitor.MonitorService;
import com.bedrock.mm.oms.exec.ExecGateway;
import com.bedrock.mm.oms.exec.binance.BinanceExecGateway;
import com.bedrock.mm.oms.model.ExecEvent;
import com.bedrock.mm.oms.model.ExecEventType;
import com.bedrock.mm.oms.model.Order;
import com.bedrock.mm.oms.model.OrderState;
import com.bedrock.mm.oms.model.PositionEvent;
import com.bedrock.mm.oms.position.PositionTracker;
import com.bedrock.mm.oms.region.RegionManager;
import com.bedrock.mm.oms.risk.RiskGuard;
import com.bedrock.mm.oms.statemachine.OrderStateMachine;
import com.bedrock.mm.oms.store.OrderStore;
import com.bedrock.mm.oms.store.PriceRegion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OMS lifecycle coordinator.
 * <p>
 * Creates per-instrument OMS instances (OrderStore, PositionTracker, RegionManager,
 * RiskGuard, ExecGateway) and registers OmsBusConsumer on the appropriate bus.
 * <p>
 * ExecEvent callbacks from the gateway update order state and position, then publish
 * POSITION_UPDATE events back onto the instrument bus for the PricingBusConsumer.
 * <p>
 * Enabled via bedrock.oms.enabled=true.
 */
@Component
@ConditionalOnProperty(name = "bedrock.oms.enabled", havingValue = "true")
public class OmsCoordinator {
    private static final Logger log = LoggerFactory.getLogger(OmsCoordinator.class);

    /** Number of bid/ask price regions per instrument. */
    private static final int NUM_REGIONS = 1;

    /** Default tick size (BTC/ETH on Binance: $0.01). */
    private static final double DEFAULT_TICK_SIZE = 0.01;

    /** Bid region ratios: orders placed 0.05%-0.10% below fairMid. */
    private static final double BID_MIN_RATIO = 0.9990;
    private static final double BID_MAX_RATIO = 0.9995;

    /** Ask region ratios: orders placed 0.05%-0.10% above fairMid. */
    private static final double ASK_MIN_RATIO = 1.0005;
    private static final double ASK_MAX_RATIO = 1.0010;

    private final EventSerdeRegistry serdeRegistry;
    private final EventSerde eventSerde;
    private final DeadLetterChannel deadLetterChannel;
    private final InstrumentEventBusCoordinator instrBusCoordinator;
    private final EventBus eventBus;
    private final MonitorService monitorService;

    private final Map<String, InstrumentOmsState> states = new ConcurrentHashMap<>();
    private final AtomicInteger instrumentIdCounter = new AtomicInteger(0);

    @Autowired
    public OmsCoordinator(
            EventSerdeRegistry serdeRegistry,
            EventSerde eventSerde,
            DeadLetterChannel deadLetterChannel,
            @Autowired(required = false) InstrumentEventBusCoordinator instrBusCoordinator,
            @Autowired(required = false) EventBus eventBus,
            @Autowired(required = false) MonitorService monitorService) {
        this.serdeRegistry = serdeRegistry;
        this.eventSerde = eventSerde;
        this.deadLetterChannel = deadLetterChannel;
        this.instrBusCoordinator = instrBusCoordinator;
        this.eventBus = eventBus;
        this.monitorService = monitorService;
    }

    OmsCoordinator(
            EventSerdeRegistry serdeRegistry,
            EventSerde eventSerde,
            DeadLetterChannel deadLetterChannel,
            InstrumentEventBusCoordinator instrBusCoordinator,
            EventBus eventBus) {
        this(serdeRegistry, eventSerde, deadLetterChannel, instrBusCoordinator, eventBus, null);
    }

    /**
     * Start the OMS: create per-instrument state and register consumers.
     */
    public void start(BedrockConfig config) {
        java.util.List<String> symbols = config.getOms().getSymbols();
        if (symbols == null || symbols.isEmpty()) {
            log.info("OmsCoordinator: no symbols configured, skipping start");
            return;
        }

        String exchange = config.getOms().getExchange();
        String apiKey = System.getenv("BINANCE_API_KEY");
        String secretKey = System.getenv("BINANCE_SECRET_KEY");

        for (String symbol : symbols) {
            int instrId = instrumentIdCounter.incrementAndGet();
            final String sym = symbol;

            InstrumentOmsState state = createState(instrId, sym, exchange, apiKey, secretKey);
            states.put(symbol, state);

            OmsBusConsumer consumer = new OmsBusConsumer(
                    sym, instrId,
                    state.regionManager,
                    state.bidRegions, state.askRegions,
                    state.orderStore,
                    state.positionTracker,
                    state.execGateway,
                    state.riskGuard,
                    DEFAULT_TICK_SIZE,
                    event -> handleExecEvent(sym, state, event),
                    serdeRegistry, deadLetterChannel,
                    () -> state.quoteTargetEnabled,
                    () -> {
                        state.quoteTargetsBlocked.incrementAndGet();
                        state.metrics.quoteTargetsBlocked.increment();
                    });

            if (instrBusCoordinator != null) {
                InstrumentEventBus bus = instrBusCoordinator.getOrCreateBus(symbol);
                bus.registerStrategyConsumer(consumer);
                log.info("OmsCoordinator: registered consumer for {} on per-instrument bus (instrId={})",
                        symbol, instrId);
            } else if (eventBus != null) {
                eventBus.registerConsumer(consumer);
                log.info("OmsCoordinator: registered consumer for {} on unified bus (instrId={})",
                        symbol, instrId);
            } else {
                log.warn("OmsCoordinator: no event bus available for {}", symbol);
            }

            // Startup recovery must happen after consumer registration so reconstructed
            // reconciliation events are consumed by OMS in-process.
            runStartupRecovery(sym, state);
        }

        log.info("OmsCoordinator: started {} instruments", states.size());
    }

    /**
     * Stop the OMS: shut down all gateways.
     */
    public void stop() {
        for (InstrumentOmsState state : states.values()) {
            try {
                state.execGateway.shutdown();
            } catch (Exception e) {
                log.warn("OmsCoordinator: error shutting down gateway for instrId={}", state.instrumentId, e);
            }
        }
        states.clear();
        log.info("OmsCoordinator: stopped");
    }

    /**
     * Get OrderStore for a symbol (for inspection/testing).
     */
    public OrderStore getOrderStore(String symbol) {
        InstrumentOmsState state = states.get(symbol);
        return state != null ? state.orderStore : null;
    }

    /**
     * Get PositionTracker for a symbol (for inspection/testing).
     */
    public PositionTracker getPositionTracker(String symbol) {
        InstrumentOmsState state = states.get(symbol);
        return state != null ? state.positionTracker : null;
    }

    /**
     * Get all registered symbol states (unmodifiable view).
     */
    public Map<String, InstrumentOmsState> getAllStates() {
        return Collections.unmodifiableMap(states);
    }

    /**
     * Get latency snapshot for one symbol.
     */
    public OmsLatencySnapshot getLatencySnapshot(String symbol) {
        InstrumentOmsState state = states.get(symbol);
        if (state == null || state.metrics == null) {
            return null;
        }
        return OmsLatencySnapshot.from(state.metrics);
    }

    /**
     * Get latency snapshots for all active OMS symbols.
     */
    public Map<String, OmsLatencySnapshot> getAllLatencySnapshots() {
        Map<String, OmsLatencySnapshot> snapshots = new java.util.HashMap<>();
        for (Map.Entry<String, InstrumentOmsState> entry : states.entrySet()) {
            InstrumentOmsState state = entry.getValue();
            if (state != null && state.metrics != null) {
                snapshots.put(entry.getKey(), OmsLatencySnapshot.from(state.metrics));
            }
        }
        return snapshots;
    }

    /**
     * Get startup recovery snapshot for one symbol.
     */
    public OmsRecoverySnapshot getRecoverySnapshot(String symbol) {
        InstrumentOmsState state = states.get(symbol);
        if (state == null || state.metrics == null) {
            return null;
        }
        return OmsRecoverySnapshot.from(symbol, state);
    }

    /**
     * Get startup recovery snapshots for all active OMS symbols.
     */
    public Map<String, OmsRecoverySnapshot> getAllRecoverySnapshots() {
        Map<String, OmsRecoverySnapshot> snapshots = new java.util.HashMap<>();
        for (Map.Entry<String, InstrumentOmsState> entry : states.entrySet()) {
            InstrumentOmsState state = entry.getValue();
            if (state != null && state.metrics != null) {
                snapshots.put(entry.getKey(), OmsRecoverySnapshot.from(entry.getKey(), state));
            }
        }
        return snapshots;
    }

    /**
     * Returns true when all active symbols are ready for quote processing.
     */
    public boolean isStartupRecoveryReady() {
        if (states.isEmpty()) {
            return true;
        }
        for (InstrumentOmsState state : states.values()) {
            if (state != null && !state.startupRecoveryReady) {
                return false;
            }
        }
        return true;
    }

    /**
     * Manually retry startup recovery for one symbol.
     *
     * @return true when recovery succeeds
     */
    public boolean retryRecovery(String symbol) {
        InstrumentOmsState state = states.get(symbol);
        if (state == null) {
            return false;
        }
        return runStartupRecovery(symbol, state);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private boolean runStartupRecovery(String symbol, InstrumentOmsState state) {
        state.startupRecoveryInProgress = true;
        state.startupRecoveryReady = false;
        state.quoteTargetEnabled = false;
        state.lastRecoveryStartEpochMs = System.currentTimeMillis();
        state.recoveryAttempts.incrementAndGet();
        state.metrics.recoveryAttempts.increment();
        try {
            boolean ok = state.execGateway.onPrivateWsReconnect();
            long now = System.currentTimeMillis();
            state.lastRecoveryEndEpochMs = now;
            if (ok) {
                state.startupRecoveryReady = true;
                state.quoteTargetEnabled = true;
                state.lastRecoverySuccessEpochMs = now;
                state.lastRecoveryError = "";
                state.recoverySuccess.incrementAndGet();
                state.metrics.recoverySuccess.increment();
                log.info("OmsCoordinator[{}]: startup recovery completed", symbol);
                return true;
            }
            state.lastRecoveryError = "gateway reconciliation returned false";
            state.recoveryFailure.incrementAndGet();
            state.metrics.recoveryFailure.increment();
            log.error("OmsCoordinator[{}]: startup recovery failed", symbol);
            return false;
        } catch (Exception e) {
            state.lastRecoveryEndEpochMs = System.currentTimeMillis();
            state.lastRecoveryError = e.toString();
            state.recoveryFailure.incrementAndGet();
            state.metrics.recoveryFailure.increment();
            log.error("OmsCoordinator[{}]: startup recovery exception: {}", symbol, e.toString(), e);
            return false;
        } finally {
            state.startupRecoveryInProgress = false;
        }
    }

    private InstrumentOmsState createState(int instrId, String symbol,
                                            String exchange, String apiKey, String secretKey) {
        InstrumentOmsState state = new InstrumentOmsState();
        state.instrumentId = instrId;
        state.orderStore = new OrderStore(64);
        state.positionTracker = new PositionTracker();
        state.regionManager = new RegionManager();
        state.riskGuard = new RiskGuard();
        state.metrics = createMetrics(symbol);

        // Create 1 bid region and 1 ask region
        state.bidRegions = new PriceRegion[NUM_REGIONS];
        state.askRegions = new PriceRegion[NUM_REGIONS];
        state.bidRegions[0] = new PriceRegion(0, true, BID_MIN_RATIO, BID_MAX_RATIO);
        state.askRegions[0] = new PriceRegion(0, false, ASK_MIN_RATIO, ASK_MAX_RATIO);

        // Build ExecGateway: real Binance gateway if credentials present, no-op otherwise
        if ("binance".equalsIgnoreCase(exchange) && apiKey != null && !apiKey.isEmpty()
                && secretKey != null && !secretKey.isEmpty()) {
            BinanceExecGateway gateway = new BinanceExecGateway(
                    "https://api.binance.com",
                    "wss://stream.binance.com:9443/ws",
                    apiKey, secretKey,
                    state.orderStore,
                    event -> publishExecEvent(symbol, state, event),
                    instrId,
                    symbol);
            gateway.start();
            state.execGateway = gateway;
            log.info("OmsCoordinator: BinanceExecGateway started for {} (instrId={})", symbol, instrId);
        } else {
            state.execGateway = noOpGateway(symbol);
            log.info("OmsCoordinator: no-op ExecGateway for {} (instrId={}, exchange={}, credsPresent={})",
                    symbol, instrId, exchange, apiKey != null && !apiKey.isEmpty());
        }

        return state;
    }

    private void publishExecEvent(String symbol, InstrumentOmsState state, ExecEvent event) {
        state.metrics.execEventsEnqueued.increment();
        if (event.recvNanos == 0L) {
            event.recvNanos = System.nanoTime();
        }
        if (event.instrumentId == 0) {
            event.instrumentId = state.instrumentId;
        }
        try {
            byte[] payloadBytes = eventSerde.serialize(event);
            EventEnvelope env = new EventEnvelope(
                    EventType.EXEC_EVENT,
                    payloadBytes,
                    eventSerde.codec(),
                    symbol,
                    event.recvNanos,
                    event.seqId);

            boolean published;
            if (instrBusCoordinator != null) {
                published = instrBusCoordinator.publish(env);
            } else if (eventBus != null) {
                published = eventBus.publish(env);
            } else {
                published = false;
            }

            if (!published) {
                deadLetterChannel.publish(
                        DeadLetterReason.PUBLISH_FAILED,
                        "OmsCoordinator[" + symbol + "].publishExecEvent",
                        env,
                        null);
                log.warn("OmsCoordinator[{}]: failed to publish EXEC_EVENT seq={}", symbol, event.seqId);
            }
        } catch (Exception e) {
            deadLetterChannel.publish(
                    DeadLetterReason.HANDLER_FAILED,
                    "OmsCoordinator[" + symbol + "].publishExecEvent",
                    EventType.EXEC_EVENT,
                    eventSerde.codec(),
                    symbol,
                    event.seqId,
                    e.toString());
            log.warn("OmsCoordinator[{}]: error publishing EXEC_EVENT: {}", symbol, e.toString());
        }
    }

    /**
     * Handle an ExecEvent from the gateway: update order state, position, publish POSITION_UPDATE.
     * This method runs on the OMS bus consumer thread.
     */
    private void handleExecEvent(String symbol, InstrumentOmsState state, ExecEvent event) {
        long startNs = System.nanoTime();
        if (event.recvNanos > 0) {
            state.metrics.execQueueDelayNs.update(Math.max(0L, startNs - event.recvNanos));
        }

        try {
            synchronized (state.orderStore) {
                Order order = state.orderStore.getOrder(event.internalOrderId);
                if (order == null && event.exchOrderId != 0L) {
                    order = state.orderStore.getByExchOrderId(event.exchOrderId);
                    if (order != null) {
                        event.internalOrderId = order.orderId;
                    }
                }

                if (order == null) {
                    log.warn("OmsCoordinator[{}]: ExecEvent for unknown order {}", symbol, event.internalOrderId);
                    return;
                }
                if (event.instrumentId == 0) {
                    event.instrumentId = order.instrumentId;
                }

                // Transition state machine
                try {
                    OrderState next = OrderStateMachine.transition(order.state, event.type);
                    order.state = next;
                } catch (IllegalStateException e) {
                    log.warn("OmsCoordinator[{}]: invalid state transition for order {}: {}",
                            symbol, event.internalOrderId, e.getMessage());
                    return;
                }

                // On ACK: map exchOrderId
                if (event.type == ExecEventType.ACK && event.exchOrderId != 0) {
                    state.orderStore.updateExchOrderId(event.internalOrderId, event.exchOrderId);
                }

                // On terminal fill or cancel: remove from store
                if (order.state == OrderState.FILLED || order.state == OrderState.CANCELLED
                        || order.state == OrderState.REJECTED) {
                    state.orderStore.removeOrder(event.internalOrderId);
                }

                // On fill: update position and publish POSITION_UPDATE
                if (event.type == ExecEventType.FILL || event.type == ExecEventType.PARTIAL_FILL) {
                    state.positionTracker.onFill(event);
                    publishPositionUpdate(symbol, state);
                }

                long endNs = System.nanoTime();
                if (event.recvNanos > 0) {
                    state.metrics.execToStateNs.update(Math.max(0L, endNs - event.recvNanos));
                }
                if (event.type == ExecEventType.ACK) {
                    if (event.recvNanos > 0) {
                        state.metrics.ackToStateNs.update(Math.max(0L, endNs - event.recvNanos));
                    }
                    if (order.quotePublishNanos > 0) {
                        state.metrics.quoteToAckNs.update(Math.max(0L, endNs - order.quotePublishNanos));
                    }
                }
                state.metrics.execEventsProcessed.increment();
            }

        } catch (Exception e) {
            log.error("OmsCoordinator[{}]: error handling ExecEvent: {}", symbol, e.toString(), e);
        }
    }

    private InstrumentOmsMetrics createMetrics(String symbol) {
        if (monitorService == null) {
            return InstrumentOmsMetrics.noop();
        }
        String metricSymbol = symbol.toLowerCase(Locale.ROOT);
        return new InstrumentOmsMetrics(
                monitorService.counter("oms." + metricSymbol + ".exec.events.enqueued"),
                monitorService.counter("oms." + metricSymbol + ".exec.events.processed"),
                monitorService.histogram("oms." + metricSymbol + ".latency.exec.queue.delay.ns"),
                monitorService.histogram("oms." + metricSymbol + ".latency.exec.to.state.ns"),
                monitorService.histogram("oms." + metricSymbol + ".latency.ack.to.state.ns"),
                monitorService.histogram("oms." + metricSymbol + ".latency.quote.to.ack.ns"),
                monitorService.counter("oms." + metricSymbol + ".recovery.attempts"),
                monitorService.counter("oms." + metricSymbol + ".recovery.success"),
                monitorService.counter("oms." + metricSymbol + ".recovery.failure"),
                monitorService.counter("oms." + metricSymbol + ".quote.blocked.before.recovery"));
    }

    private void publishPositionUpdate(String symbol, InstrumentOmsState state) {
        try {
            PositionEvent posEvent = state.positionTracker.buildPositionEvent(
                    state.instrumentId, state.reusePositionEvent);

            PositionPayload payload = new PositionPayload(
                    symbol, state.instrumentId,
                    posEvent.netPosition, posEvent.delta,
                    posEvent.unrealizedPnl, posEvent.publishNanos, posEvent.seqId);

            byte[] payloadBytes = eventSerde.serialize(payload);
            EventEnvelope env = new EventEnvelope(
                    EventType.POSITION_UPDATE, payloadBytes,
                    eventSerde.codec(), symbol, posEvent.publishNanos, posEvent.seqId);

            if (instrBusCoordinator != null) {
                instrBusCoordinator.publish(env);
            } else if (eventBus != null) {
                eventBus.publish(env);
            }
        } catch (Exception e) {
            log.warn("OmsCoordinator[{}]: failed to publish POSITION_UPDATE: {}", symbol, e.toString());
        }
    }

    private static ExecGateway noOpGateway(String symbol) {
        return new ExecGateway() {
            @Override public void placeOrder(int id, long price, long size, boolean isBid, int regionIdx) {
                LoggerFactory.getLogger(OmsCoordinator.class)
                        .debug("NoOpGateway[{}]: placeOrder price={} size={} isBid={}", symbol, price, size, isBid);
            }
            @Override public void cancelOrder(int id, long orderId) {
                LoggerFactory.getLogger(OmsCoordinator.class)
                        .debug("NoOpGateway[{}]: cancelOrder orderId={}", symbol, orderId);
            }
            @Override public boolean onPrivateWsReconnect() { return true; }
            @Override public void shutdown() {}
        };
    }

    // -------------------------------------------------------------------------
    // Per-instrument state holder
    // -------------------------------------------------------------------------

    public static class InstrumentOmsState {
        public int instrumentId;
        public OrderStore orderStore;
        public PositionTracker positionTracker;
        public RegionManager regionManager;
        public RiskGuard riskGuard;
        public ExecGateway execGateway;
        public InstrumentOmsMetrics metrics;
        public PriceRegion[] bidRegions;
        public PriceRegion[] askRegions;
        public volatile boolean startupRecoveryInProgress;
        public volatile boolean startupRecoveryReady;
        public volatile boolean quoteTargetEnabled;
        public volatile long lastRecoveryStartEpochMs;
        public volatile long lastRecoveryEndEpochMs;
        public volatile long lastRecoverySuccessEpochMs;
        public volatile String lastRecoveryError = "";
        public final AtomicLong recoveryAttempts = new AtomicLong(0L);
        public final AtomicLong recoverySuccess = new AtomicLong(0L);
        public final AtomicLong recoveryFailure = new AtomicLong(0L);
        public final AtomicLong quoteTargetsBlocked = new AtomicLong(0L);
        /** Pre-allocated PositionEvent for zero-GC buildPositionEvent calls. */
        public final PositionEvent reusePositionEvent = new PositionEvent();
    }

    public record InstrumentOmsMetrics(
            MetricRegistry.Counter execEventsEnqueued,
            MetricRegistry.Counter execEventsProcessed,
            MetricRegistry.Histogram execQueueDelayNs,
            MetricRegistry.Histogram execToStateNs,
            MetricRegistry.Histogram ackToStateNs,
            MetricRegistry.Histogram quoteToAckNs,
            MetricRegistry.Counter recoveryAttempts,
            MetricRegistry.Counter recoverySuccess,
            MetricRegistry.Counter recoveryFailure,
            MetricRegistry.Counter quoteTargetsBlocked) {

        static InstrumentOmsMetrics noop() {
            return new InstrumentOmsMetrics(
                    new NoOpCounter(), new NoOpCounter(),
                    new NoOpHistogram(), new NoOpHistogram(),
                    new NoOpHistogram(), new NoOpHistogram(),
                    new NoOpCounter(), new NoOpCounter(),
                    new NoOpCounter(), new NoOpCounter());
        }
    }

    private static final class NoOpCounter implements MetricRegistry.Counter {
        @Override public void increment() {}
        @Override public void increment(long delta) {}
        @Override public long getCount() { return 0; }
        @Override public String getName() { return "noop"; }
        @Override public MetricRegistry.MetricType getType() { return MetricRegistry.MetricType.COUNTER; }
        @Override public long getLastUpdated() { return 0; }
    }

    private static final class NoOpHistogram implements MetricRegistry.Histogram {
        @Override public void update(long value) {}
        @Override public void update(double value) {}
        @Override public long getCount() { return 0; }
        @Override public double getMin() { return 0; }
        @Override public double getMax() { return 0; }
        @Override public double getMean() { return 0; }
        @Override public double getStdDev() { return 0; }
        @Override public double getPercentile(double percentile) { return 0; }
        @Override public String getName() { return "noop"; }
        @Override public MetricRegistry.MetricType getType() { return MetricRegistry.MetricType.HISTOGRAM; }
        @Override public long getLastUpdated() { return 0; }
    }

    public record OmsLatencySnapshot(
            long execEventsEnqueued,
            long execEventsProcessed,
            long execQueueDelayCount,
            double execQueueDelayP99Ns,
            double execQueueDelayP999Ns,
            long execToStateCount,
            double execToStateP99Ns,
            double execToStateP999Ns,
            long ackToStateCount,
            double ackToStateP99Ns,
            double ackToStateP999Ns,
            long quoteToAckCount,
            double quoteToAckP99Ns,
            double quoteToAckP999Ns) {

        static OmsLatencySnapshot from(InstrumentOmsMetrics metrics) {
            return new OmsLatencySnapshot(
                    metrics.execEventsEnqueued.getCount(),
                    metrics.execEventsProcessed.getCount(),
                    metrics.execQueueDelayNs.getCount(),
                    metrics.execQueueDelayNs.getPercentile(99.0),
                    metrics.execQueueDelayNs.getPercentile(99.9),
                    metrics.execToStateNs.getCount(),
                    metrics.execToStateNs.getPercentile(99.0),
                    metrics.execToStateNs.getPercentile(99.9),
                    metrics.ackToStateNs.getCount(),
                    metrics.ackToStateNs.getPercentile(99.0),
                    metrics.ackToStateNs.getPercentile(99.9),
                    metrics.quoteToAckNs.getCount(),
                    metrics.quoteToAckNs.getPercentile(99.0),
                    metrics.quoteToAckNs.getPercentile(99.9));
        }
    }

    public record OmsRecoverySnapshot(
            String symbol,
            int instrumentId,
            boolean recoveryReady,
            boolean recoveryInProgress,
            long lastRecoveryStartEpochMs,
            long lastRecoveryEndEpochMs,
            long lastRecoverySuccessEpochMs,
            String lastRecoveryError,
            long recoveryAttempts,
            long recoverySuccess,
            long recoveryFailure,
            long quoteTargetsBlocked) {

        static OmsRecoverySnapshot from(String symbol, InstrumentOmsState state) {
            return new OmsRecoverySnapshot(
                    symbol,
                    state.instrumentId,
                    state.startupRecoveryReady,
                    state.startupRecoveryInProgress,
                    state.lastRecoveryStartEpochMs,
                    state.lastRecoveryEndEpochMs,
                    state.lastRecoverySuccessEpochMs,
                    state.lastRecoveryError,
                    state.recoveryAttempts.get(),
                    state.recoverySuccess.get(),
                    state.recoveryFailure.get(),
                    state.quoteTargetsBlocked.get());
        }
    }
}
