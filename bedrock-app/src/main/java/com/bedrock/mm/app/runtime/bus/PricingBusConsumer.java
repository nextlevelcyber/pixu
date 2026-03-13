package com.bedrock.mm.app.runtime.bus;

import com.bedrock.mm.common.event.BboPayload;
import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventSerdeRegistry;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.MarketTickPayload;
import com.bedrock.mm.common.event.PositionPayload;
import com.bedrock.mm.pricing.PricingOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Per-instrument pricing engine consumer.
 * <p>
 * Routes events from the per-instrument bus to the PricingOrchestrator:
 * - BBO → orchestrator.onBbo() (triggers full recompute + publish)
 * - MARKET_TICK → orchestrator.onTrade() (EWMA trade flow update, no recompute)
 * <p>
 * Exceptions are routed to DeadLetterChannel (same pattern as InstrumentStrategyBusConsumer).
 */
public class PricingBusConsumer implements Consumer<EventEnvelope> {
    private static final Logger log = LoggerFactory.getLogger(PricingBusConsumer.class);

    private final String instrumentId;
    private final PricingOrchestrator orchestrator;
    private final EventSerdeRegistry serdeRegistry;
    private final DeadLetterChannel deadLetterChannel;

    public PricingBusConsumer(
            String instrumentId,
            PricingOrchestrator orchestrator,
            EventSerdeRegistry serdeRegistry,
            DeadLetterChannel deadLetterChannel) {
        this.instrumentId = instrumentId;
        this.orchestrator = orchestrator;
        this.serdeRegistry = serdeRegistry;
        this.deadLetterChannel = deadLetterChannel;
    }

    @Override
    public void accept(EventEnvelope env) {
        if (env == null) {
            return;
        }
        try {
            EventSerde serde = serdeRegistry.require(env.getPayloadCodec());

            if (env.getType() == EventType.BBO) {
                handleBbo(env, serde);
                return;
            }
            if (env.getType() == EventType.MARKET_TICK) {
                handleMarketTick(env, serde);
                return;
            }
            if (env.getType() == EventType.POSITION_UPDATE) {
                handlePositionUpdate(env, serde);
            }
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.HANDLER_FAILED,
                    "PricingBusConsumer[" + instrumentId + "].accept", env, e);
            log.warn("PricingBusConsumer[{}]: failed to handle {}: {}",
                    instrumentId, env.getType(), e.toString());
        }
    }

    private void handleBbo(EventEnvelope env, EventSerde serde) {
        try {
            BboPayload bbo = serde.deserialize(env.getPayload(), BboPayload.class);
            if (bbo == null) {
                return;
            }
            orchestrator.onBbo(bbo.bidPrice, bbo.askPrice, bbo.bidSize, bbo.askSize, bbo.recvNanos);
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.DESERIALIZE_FAILED,
                    "PricingBusConsumer[" + instrumentId + "].handleBbo", env, e);
            log.warn("PricingBusConsumer[{}]: failed to parse BBO: {}", instrumentId, e.toString());
        }
    }

    private void handleMarketTick(EventEnvelope env, EventSerde serde) {
        try {
            MarketTickPayload tick = serde.deserialize(env.getPayload(), MarketTickPayload.class);
            if (tick == null) {
                return;
            }
            orchestrator.onTrade(tick.price, tick.quantity, tick.buy);
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.DESERIALIZE_FAILED,
                    "PricingBusConsumer[" + instrumentId + "].handleMarketTick", env, e);
            log.warn("PricingBusConsumer[{}]: failed to parse MARKET_TICK: {}", instrumentId, e.toString());
        }
    }

    private void handlePositionUpdate(EventEnvelope env, EventSerde serde) {
        try {
            PositionPayload pos = serde.deserialize(env.getPayload(), PositionPayload.class);
            if (pos == null) {
                return;
            }
            orchestrator.onPosition(pos.netPosition);
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.DESERIALIZE_FAILED,
                    "PricingBusConsumer[" + instrumentId + "].handlePositionUpdate", env, e);
            log.warn("PricingBusConsumer[{}]: failed to parse POSITION_UPDATE: {}", instrumentId, e.toString());
        }
    }
}
