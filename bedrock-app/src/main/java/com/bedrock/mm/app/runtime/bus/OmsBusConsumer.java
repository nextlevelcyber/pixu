package com.bedrock.mm.app.runtime.bus;

import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventSerdeRegistry;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.oms.exec.ExecGateway;
import com.bedrock.mm.oms.model.ExecEvent;
import com.bedrock.mm.oms.position.PositionTracker;
import com.bedrock.mm.oms.region.OrderAction;
import com.bedrock.mm.oms.region.RegionManager;
import com.bedrock.mm.oms.risk.RiskGuard;
import com.bedrock.mm.oms.store.OrderStore;
import com.bedrock.mm.oms.store.PriceRegion;
import com.bedrock.mm.pricing.model.QuoteTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Per-instrument OMS event consumer.
 * <p>
 * Listens for QUOTE_TARGET events on the per-instrument bus, runs RegionManager.diff()
 * to compute required OrderActions, applies RiskGuard pre-checks, then delegates
 * order placement and cancellation to ExecGateway.
 * <p>
 * Single-threaded: runs on the per-instrument Disruptor consumer thread.
 */
public class OmsBusConsumer implements Consumer<EventEnvelope> {
    private static final Logger log = LoggerFactory.getLogger(OmsBusConsumer.class);

    private final String symbol;
    private final int instrumentId;
    private final RegionManager regionManager;
    private final PriceRegion[] bidRegions;
    private final PriceRegion[] askRegions;
    private final OrderStore orderStore;
    private final PositionTracker positionTracker;
    private final ExecGateway execGateway;
    private final RiskGuard riskGuard;
    private final double tickSize;
    private final Consumer<ExecEvent> execEventHandler;
    private final EventSerdeRegistry serdeRegistry;
    private final DeadLetterChannel deadLetterChannel;
    private final BooleanSupplier quoteTargetEnabled;
    private final Runnable onQuoteTargetBlocked;

    public OmsBusConsumer(
            String symbol,
            int instrumentId,
            RegionManager regionManager,
            PriceRegion[] bidRegions,
            PriceRegion[] askRegions,
            OrderStore orderStore,
            PositionTracker positionTracker,
            ExecGateway execGateway,
            RiskGuard riskGuard,
            double tickSize,
            Consumer<ExecEvent> execEventHandler,
            EventSerdeRegistry serdeRegistry,
            DeadLetterChannel deadLetterChannel) {
        this(symbol, instrumentId, regionManager, bidRegions, askRegions, orderStore, positionTracker,
                execGateway, riskGuard, tickSize, execEventHandler, serdeRegistry, deadLetterChannel,
                () -> true, () -> {});
    }

    public OmsBusConsumer(
            String symbol,
            int instrumentId,
            RegionManager regionManager,
            PriceRegion[] bidRegions,
            PriceRegion[] askRegions,
            OrderStore orderStore,
            PositionTracker positionTracker,
            ExecGateway execGateway,
            RiskGuard riskGuard,
            double tickSize,
            Consumer<ExecEvent> execEventHandler,
            EventSerdeRegistry serdeRegistry,
            DeadLetterChannel deadLetterChannel,
            BooleanSupplier quoteTargetEnabled,
            Runnable onQuoteTargetBlocked) {
        this.symbol = symbol;
        this.instrumentId = instrumentId;
        this.regionManager = regionManager;
        this.bidRegions = bidRegions;
        this.askRegions = askRegions;
        this.orderStore = orderStore;
        this.positionTracker = positionTracker;
        this.execGateway = execGateway;
        this.riskGuard = riskGuard;
        this.tickSize = tickSize;
        this.execEventHandler = execEventHandler;
        this.serdeRegistry = serdeRegistry;
        this.deadLetterChannel = deadLetterChannel;
        this.quoteTargetEnabled = quoteTargetEnabled;
        this.onQuoteTargetBlocked = onQuoteTargetBlocked;
    }

    @Override
    public void accept(EventEnvelope env) {
        if (env == null) {
            return;
        }
        if (!symbol.equals(env.getSymbol())) {
            return;
        }
        try {
            EventSerde serde = serdeRegistry.require(env.getPayloadCodec());
            if (env.getType() == EventType.QUOTE_TARGET) {
                handleQuoteTarget(env, serde);
                return;
            }
            if (env.getType() == EventType.EXEC_EVENT) {
                handleExecEvent(env, serde);
            }
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.HANDLER_FAILED,
                    "OmsBusConsumer[" + symbol + "].accept", env, e);
            log.warn("OmsBusConsumer[{}]: failed to handle {}: {}",
                    symbol, env.getType(), e.toString());
        }
    }

    private void handleQuoteTarget(EventEnvelope env, EventSerde serde) {
        try {
            QuoteTarget target = serde.deserialize(env.getPayload(), QuoteTarget.class);
            if (target == null) {
                return;
            }
            if (!quoteTargetEnabled.getAsBoolean()) {
                onQuoteTargetBlocked.run();
                log.warn("OmsBusConsumer[{}]: startup recovery not ready, drop QUOTE_TARGET seq={}",
                        symbol, target.seqId);
                return;
            }

            synchronized (orderStore) {
                List<OrderAction> actions = regionManager.diff(
                        instrumentId,
                        target.fairMid,
                        target.bidPrice, target.askPrice,
                        target.bidSize, target.askSize,
                        target.regionIndex,
                        bidRegions, askRegions,
                        orderStore, tickSize);

                long netPosition = positionTracker.getNetPosition(instrumentId);
                int openOrders = orderStore.getOpenOrderCount(instrumentId);

                for (OrderAction action : actions) {
                    if (action instanceof OrderAction.NewOrder n) {
                        if (riskGuard.preCheck(instrumentId, n.price(), target.fairMid,
                                openOrders, netPosition)) {
                            execGateway.placeOrder(n.instrumentId(), n.price(), n.size(),
                                    n.isBid(), n.regionIndex(), target.publishNanos, target.seqId);
                            openOrders++;
                        } else {
                            log.debug("OmsBusConsumer[{}]: RiskGuard rejected order price={} fairMid={}",
                                    symbol, n.price(), target.fairMid);
                        }
                    } else if (action instanceof OrderAction.CancelOrder c) {
                        execGateway.cancelOrder(instrumentId, c.orderId());
                    }
                }
            }
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.DESERIALIZE_FAILED,
                    "OmsBusConsumer[" + symbol + "].handleQuoteTarget", env, e);
            log.warn("OmsBusConsumer[{}]: failed to process QUOTE_TARGET: {}", symbol, e.toString());
        }
    }

    private void handleExecEvent(EventEnvelope env, EventSerde serde) {
        try {
            ExecEvent execEvent = serde.deserialize(env.getPayload(), ExecEvent.class);
            if (execEvent == null) {
                return;
            }
            execEventHandler.accept(execEvent);
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.DESERIALIZE_FAILED,
                    "OmsBusConsumer[" + symbol + "].handleExecEvent", env, e);
            log.warn("OmsBusConsumer[{}]: failed to process EXEC_EVENT: {}", symbol, e.toString());
        }
    }
}
