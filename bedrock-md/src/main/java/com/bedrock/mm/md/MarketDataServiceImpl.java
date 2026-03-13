package com.bedrock.mm.md;
import com.bedrock.mm.aeron.ChannelFactory;
import com.bedrock.mm.aeron.bus.EventBus;
import com.bedrock.mm.common.channel.ChannelConfig;
import com.bedrock.mm.common.channel.ChannelMode;
import com.bedrock.mm.common.channel.ChannelPublisher;
import com.bedrock.mm.common.channel.ChannelProviderConfig;
import com.bedrock.mm.common.event.BboPayload;
import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.BookDeltaPayload;
import com.bedrock.mm.common.event.MarketTickPayload;
import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.monitor.MonitorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.Optional;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Market data service implementation
 */
@Service
@ConditionalOnProperty(name = "bedrock.md.enabled", havingValue = "true", matchIfMissing = true)
public class MarketDataServiceImpl implements MarketDataService {
    private static final Logger log = LoggerFactory.getLogger(MarketDataServiceImpl.class);

    private final MonitorService monitorService;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong sequenceNumber = new AtomicLong(0);
    private final MarketDataConfig mdConfig;
    private final EventBus eventBus;
    private final EventSerde eventSerde;

    // Publishers for different data types
    private ChannelPublisher<MarketTick> marketTickPublisher;
    private ChannelPublisher<BookDelta> bookDeltaPublisher;

    // Subscription handlers (keyed by symbol name to avoid Symbol instance equality issues)
    private final ConcurrentMap<String, MarketTickHandler> tickHandlers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, BookDeltaHandler> bookHandlers = new ConcurrentHashMap<>();

    // Metrics
    private com.bedrock.mm.monitor.MetricRegistry.Counter ticksPublished;
    private com.bedrock.mm.monitor.MetricRegistry.Counter deltasPublished;
    private com.bedrock.mm.monitor.MetricRegistry.Timer publishLatency;

    public MarketDataServiceImpl(@Autowired(required = false) MonitorService monitorService,
            MarketDataConfig mdConfig,
            @Autowired(required = false) EventBus eventBus,
            EventSerde eventSerde) {
        this.monitorService = monitorService;
        this.scheduler = Executors.newScheduledThreadPool(2);
        this.mdConfig = mdConfig;
        this.eventBus = eventBus;
        this.eventSerde = eventSerde;
    }

    public void initialize() {
        // Initialize metrics
        if (monitorService != null) {
            ticksPublished = monitorService.counter("md.ticks.published");
            deltasPublished = monitorService.counter("md.deltas.published");
            publishLatency = monitorService.timer("md.publish.latency");
        }

        // Initialize publishers from config
        MarketDataConfig.ChannelConfig ch = mdConfig.getChannels();
        ChannelMode mode;
        try {
            mode = ChannelMode.valueOf(Optional.ofNullable(ch.getMode()).orElse("IN_PROC"));
        } catch (IllegalArgumentException iae) {
            log.warn("Unknown channel mode '{}', defaulting to IN_PROC", ch.getMode());
            mode = ChannelMode.IN_PROC;
        }

        if (mode == ChannelMode.AERON_IPC || mode == ChannelMode.AERON_UDP) {
            // Initialize Aeron provider with sensible defaults for local dev
            ChannelProviderConfig providerConfig = ChannelProviderConfig.builder()
                    .mode(mode)
                    .aeronDir(Optional.ofNullable(System.getProperty("aeron.dir")).orElse("/tmp/aeron"))
                    .embeddedMediaDriver(true)
                    .build();
            ChannelFactory.initializeProvider(mode, providerConfig);
        }

        ChannelConfig tickConfig;
        ChannelConfig deltaConfig;
        switch (mode) {
            case IN_PROC:
                tickConfig = ChannelConfig.inProc(ch.getMarketTickStreamId(), MarketTick.class);
                deltaConfig = ChannelConfig.inProc(ch.getBookDeltaStreamId(), BookDelta.class);
                break;
            case AERON_IPC:
                tickConfig = ChannelConfig.aeronIpc(ch.getMarketTickStreamId(), MarketTick.class);
                deltaConfig = ChannelConfig.aeronIpc(ch.getBookDeltaStreamId(), BookDelta.class);
                break;
            case AERON_UDP:
                String tickEndpoint = Optional.ofNullable(ch.getMarketTickChannel())
                        .orElse("aeron:udp?endpoint=localhost:40123");
                String deltaEndpoint = Optional.ofNullable(ch.getBookDeltaChannel())
                        .orElse("aeron:udp?endpoint=localhost:40124");
                tickConfig = ChannelConfig.aeronUdp(ch.getMarketTickStreamId(), tickEndpoint, MarketTick.class);
                deltaConfig = ChannelConfig.aeronUdp(ch.getBookDeltaStreamId(), deltaEndpoint, BookDelta.class);
                break;
            default:
                throw new IllegalStateException("Unsupported channel mode: " + mode);
        }

        marketTickPublisher = ChannelFactory.createPublisher(tickConfig);
        bookDeltaPublisher = ChannelFactory.createPublisher(deltaConfig);

        log.info("Market data service initialized with mode={} tickStream={} deltaStream={}",
                mode, tickConfig.getStreamId(), deltaConfig.getStreamId());
    }

    @Override
    public void subscribeMarketTick(Symbol symbol, MarketTickHandler handler) {
        if (symbol == null || handler == null) return;
        tickHandlers.put(symbol.getName(), handler);
        log.info("Subscribed to market ticks for symbol: {}", symbol.getName());
    }

    @Override
    public void subscribeBookDelta(Symbol symbol, BookDeltaHandler handler) {
        if (symbol == null || handler == null) return;
        bookHandlers.put(symbol.getName(), handler);
        log.info("Subscribed to book deltas for symbol: {}", symbol.getName());
    }

    @Override
    public void unsubscribeMarketTick(Symbol symbol) {
        if (symbol == null) return;
        tickHandlers.remove(symbol.getName());
        log.info("Unsubscribed from market ticks for symbol: {}", symbol.getName());
    }

    @Override
    public void unsubscribeBookDelta(Symbol symbol) {
        if (symbol == null) return;
        bookHandlers.remove(symbol.getName());
        log.info("Unsubscribed from book deltas for symbol: {}", symbol.getName());
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting market data service");

            // Start market data simulation only when enabled via configuration
            try {
                MarketDataConfig.SimulationConfig sim = mdConfig.getSimulation();
                boolean simEnabled = (sim != null) && sim.isEnabled();
                if (simEnabled) {
                    log.info("Market data simulation is enabled; starting simulation tasks.");
                    startMarketDataSimulation();
                } else {
                    log.info("Market data simulation is disabled; skipping simulation tasks.");
                }
            } catch (Exception e) {
                log.warn("Failed to evaluate simulation configuration; skipping simulation start", e);
            }

            log.info("Market data service started");
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping market data service");

            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }

            log.info("Market data service stopped");
        }
    }

    @PreDestroy
    public void destroy() {
        stop();

        if (marketTickPublisher != null) {
            try {
                marketTickPublisher.close();
            } catch (Exception e) {
                log.warn("Error closing market tick publisher", e);
            }
        }
        if (bookDeltaPublisher != null) {
            try {
                bookDeltaPublisher.close();
            } catch (Exception e) {
                log.warn("Error closing book delta publisher", e);
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Publish a market tick
     */
    public void publishMarketTick(MarketTick tick) {
        try {
            var context = publishLatency != null ? publishLatency.time() : null;
            try {
                // Publish to unified event bus if available
                if (eventBus != null) {
                    MarketTickPayload payload = new MarketTickPayload(
                            tick.getSymbol().getName(),
                            tick.getTimestamp(),
                            tick.getPrice(),
                            tick.getQuantity(),
                            tick.isBuy(),
                            tick.getSequenceNumber());
                    byte[] payloadBytes = eventSerde.serialize(payload);
                    EventEnvelope env = new EventEnvelope(EventType.MARKET_TICK, payloadBytes,
                            eventSerde.codec(), tick.getSymbol().getName(), tick.getTimestamp(), tick.getSequenceNumber());
                    eventBus.publish(env);
                } else {
                    // Fallback to legacy channel publish and direct handlers
                    MarketTickHandler handler = tickHandlers.get(tick.getSymbol().getName());
                    if (handler != null) {
                        handler.onMarketTick(tick);
                    }
                    marketTickPublisher.publish(tick);
                }
                if (ticksPublished != null) ticksPublished.increment();
            } finally {
                if (context != null) {
                    context.close();
                }
            }
        } catch (Exception e) {
            log.warn("Error publishing market tick: {}", tick, e);
        }
    }

    /**
     * Publish a book delta
     */
    public void publishBookDelta(BookDelta delta) {
        try {
            var context = publishLatency != null ? publishLatency.time() : null;
            try {
                // Publish to unified event bus if available
                if (eventBus != null) {
                    BookDeltaPayload payload = new BookDeltaPayload(
                            delta.getSymbol().getName(),
                            delta.getTimestamp(),
                            delta.getSide() == null ? null : delta.getSide().name(),
                            delta.getPrice(),
                            delta.getQuantity(),
                            delta.getAction() == null ? null : delta.getAction().name(),
                            delta.getSequenceNumber());
                    byte[] payloadBytes = eventSerde.serialize(payload);
                    EventEnvelope env = new EventEnvelope(EventType.BOOK_DELTA, payloadBytes,
                            eventSerde.codec(), delta.getSymbol().getName(), delta.getTimestamp(), delta.getSequenceNumber());
                    eventBus.publish(env);
                } else {
                    // Fallback to legacy channel publish and direct handlers
                    BookDeltaHandler handler = bookHandlers.get(delta.getSymbol().getName());
                    if (handler != null) {
                        handler.onBookDelta(delta);
                    }
                    bookDeltaPublisher.publish(delta);
                }
                if (deltasPublished != null) deltasPublished.increment();
            } finally {
                if (context != null) {
                    context.close();
                }
            }
        } catch (Exception e) {
            log.warn("Error publishing book delta: {}", delta, e);
        }
    }

    /**
     * Consume MARKET_TICK event by notifying subscribed handlers.
     */
    @Override
    public void handleMarketTick(MarketTick tick) {
        try {
            if (tick == null || tick.getSymbol() == null) return;
            MarketTickHandler handler = tickHandlers.get(tick.getSymbol().getName());
            if (handler != null) {
                handler.onMarketTick(tick);
            }
        } catch (Exception e) {
            log.warn("Error handling market tick: {}", tick, e);
        }
    }

    /**
     * Publish a BBO event derived from L2 order book top-of-book.
     * Serializes to EventEnvelope(BBO) and publishes to EventBus.
     */
    @Override
    public void publishBbo(String symbolName, long bidPrice, long bidSize, long askPrice, long askSize,
                           long recvNanos, long sequenceNumber) {
        try {
            if (eventBus != null) {
                BboPayload payload = new BboPayload(symbolName, bidPrice, bidSize, askPrice, askSize,
                        recvNanos, sequenceNumber);
                byte[] payloadBytes = eventSerde.serialize(payload);
                EventEnvelope env = new EventEnvelope(EventType.BBO, payloadBytes,
                        eventSerde.codec(), symbolName, recvNanos, sequenceNumber);
                eventBus.publish(env);
            }
        } catch (Exception e) {
            log.warn("Error publishing BBO for {}: {}", symbolName, e.toString());
        }
    }

    /**
     * Consume BOOK_DELTA event by notifying subscribed handlers.
     */
    @Override
    public void handleBookDelta(BookDelta delta) {
        try {
            if (delta == null || delta.getSymbol() == null) return;
            BookDeltaHandler handler = bookHandlers.get(delta.getSymbol().getName());
            if (handler != null) {
                handler.onBookDelta(delta);
            }
        } catch (Exception e) {
            log.warn("Error handling book delta: {}", delta, e);
        }
    }

    /**
     * Start market data simulation for testing
     */
    private void startMarketDataSimulation() {
        Symbol btcUsdt = Symbol.btcUsdt();
        Symbol ethUsdt = Symbol.ethUsdt();

        // Simulate market ticks every 100ms
        scheduler.scheduleAtFixedRate(() -> {
            try {
                long timestamp = System.currentTimeMillis();
                long seq = sequenceNumber.incrementAndGet();

                // Generate random BTC tick
                long btcPrice = btcUsdt.decimalToPrice(50000 + Math.random() * 1000);
                long btcQty = btcUsdt.decimalToQty(Math.random() * 10);
                boolean isBuy = Math.random() > 0.5;

                MarketTick btcTick = new MarketTick(btcUsdt, timestamp, btcPrice, btcQty, isBuy, seq);
                publishMarketTick(btcTick);

                // Generate random ETH tick
                long ethPrice = ethUsdt.decimalToPrice(3000 + Math.random() * 100);
                long ethQty = ethUsdt.decimalToQty(Math.random() * 50);

                MarketTick ethTick = new MarketTick(ethUsdt, timestamp, ethPrice, ethQty, !isBuy, seq + 1);
                publishMarketTick(ethTick);

            } catch (Exception e) {
                log.warn("Error in market data simulation", e);
            }
        }, 0, 100, TimeUnit.MILLISECONDS);

        // Simulate book deltas every 50ms
        scheduler.scheduleAtFixedRate(() -> {
            try {
                long timestamp = System.currentTimeMillis();
                long seq = sequenceNumber.incrementAndGet();

                // Generate random book delta
                Symbol symbol = Math.random() > 0.5 ? btcUsdt : ethUsdt;
                long basePrice = symbol == btcUsdt ? btcUsdt.decimalToPrice(50000) : ethUsdt.decimalToPrice(3000);
                long price = basePrice + (long) (Math.random() * 1000 - 500);
                long qty = symbol.decimalToQty(Math.random() * 20);

                BookDelta.Action action = BookDelta.Action.values()[(int) (Math.random() * 3)];
                com.bedrock.mm.common.model.Side side = Math.random() > 0.5 ? com.bedrock.mm.common.model.Side.BUY
                        : com.bedrock.mm.common.model.Side.SELL;

                BookDelta delta = new BookDelta(symbol, timestamp, side, price, qty, action, seq);
                publishBookDelta(delta);

            } catch (Exception e) {
                log.warn("Error in book delta simulation", e);
            }
        }, 0, 50, TimeUnit.MILLISECONDS);
    }
}
