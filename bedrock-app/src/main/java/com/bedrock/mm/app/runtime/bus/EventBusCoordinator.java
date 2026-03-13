package com.bedrock.mm.app.runtime.bus;

import com.bedrock.mm.adapter.AdapterService;
import com.bedrock.mm.app.config.BedrockConfig;
import com.bedrock.mm.aeron.bus.EventBus;
import com.bedrock.mm.aeron.bus.EventDispatcher;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventSerdeRegistry;
import com.bedrock.mm.md.MarketDataService;
import com.bedrock.mm.strategy.StrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns unified event-bus wiring and loop lifecycle.
 */
@Component
public class EventBusCoordinator {
    private static final Logger log = LoggerFactory.getLogger(EventBusCoordinator.class);

    private final EventBus eventBus;
    private final EventDispatcher eventDispatcher;
    private final MarketDataService marketDataService;
    private final StrategyService strategyService;
    private final AdapterService adapterService;
    private final EventSerde eventSerde;
    private final EventSerdeRegistry eventSerdeRegistry;
    private final DeadLetterChannel deadLetterChannel;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean wired = new AtomicBoolean(false);
    private volatile ExecutorService loopExecutor;
    private volatile Future<?> loopFuture;

    @Autowired
    public EventBusCoordinator(
            @Autowired(required = false) EventBus eventBus,
            @Autowired(required = false) EventDispatcher eventDispatcher,
            @Autowired(required = false) MarketDataService marketDataService,
            @Autowired(required = false) StrategyService strategyService,
            @Autowired(required = false) AdapterService adapterService,
            EventSerde eventSerde,
            EventSerdeRegistry eventSerdeRegistry,
            DeadLetterChannel deadLetterChannel) {
        this.eventBus = eventBus;
        this.eventDispatcher = eventDispatcher;
        this.marketDataService = marketDataService;
        this.strategyService = strategyService;
        this.adapterService = adapterService;
        this.eventSerde = eventSerde;
        this.eventSerdeRegistry = eventSerdeRegistry;
        this.deadLetterChannel = deadLetterChannel;
    }

    public void start(BedrockConfig config) {
        if (eventBus == null || eventDispatcher == null) {
            log.info("Unified event bus not available; services will run without bus.");
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }

        wireConsumers(config);

        loopExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bedrock-event-bus-loop");
            t.setDaemon(true);
            return t;
        });

        loopFuture = loopExecutor.submit(() -> {
            try {
                log.info("Starting unified event bus loop...");
                eventBus.runLoop();
            } catch (Exception e) {
                log.error("Event bus loop terminated with error", e);
            }
        });
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        try {
            eventBus.stop();
        } catch (Exception e) {
            log.warn("Error stopping event bus", e);
        }

        Future<?> currentLoopFuture = loopFuture;
        if (currentLoopFuture != null) {
            try {
                currentLoopFuture.get(3, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                log.warn("Timed out waiting for event bus loop to stop");
                currentLoopFuture.cancel(true);
            } catch (Exception e) {
                log.warn("Error waiting for event bus loop shutdown", e);
            }
        }

        ExecutorService currentExecutor = loopExecutor;
        if (currentExecutor != null) {
            currentExecutor.shutdownNow();
            loopExecutor = null;
            loopFuture = null;
        }

        try {
            eventBus.close();
        } catch (Exception e) {
            log.warn("Error closing event bus", e);
        }
    }

    private void wireConsumers(BedrockConfig config) {
        if (!wired.compareAndSet(false, true)) {
            return;
        }

        eventBus.registerConsumer(eventDispatcher);

        if (marketDataService != null && strategyService != null && config.isStrategyEnabled()) {
            eventDispatcher.registerStrategyConsumer(
                    new StrategyBusConsumer(marketDataService, strategyService, eventSerdeRegistry, deadLetterChannel));
        }

        if (adapterService != null && config.isAdapterEnabled()) {
            eventDispatcher.registerAdapterConsumer(new AdapterBusConsumer(adapterService, eventSerdeRegistry, deadLetterChannel));
            new AdapterUpdateBridge(adapterService, eventBus, eventSerde, deadLetterChannel).attach();
        }
    }
}
