package com.bedrock.mm.app.runtime.bus.instrument;

import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventSerdeRegistry;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.OrderAckPayload;
import com.bedrock.mm.common.event.FillPayload;
import com.bedrock.mm.common.event.MarketTickPayload;
import com.bedrock.mm.common.event.BookDeltaPayload;
import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.md.BookDelta;
import com.bedrock.mm.md.MarketDataService;
import com.bedrock.mm.md.MarketTick;
import com.bedrock.mm.strategy.Fill;
import com.bedrock.mm.strategy.OrderAck;
import com.bedrock.mm.strategy.Strategy;
import com.bedrock.mm.app.runtime.bus.DeadLetterChannel;
import com.bedrock.mm.app.runtime.bus.DeadLetterReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Per-instrument strategy consumer: delivers events to pre-filtered strategy list.
 * <p>
 * Constructed with strategies that have already been filtered to those subscribing
 * to this instrument. No symbol matching is performed on the hot path.
 * <p>
 * Reuses EventEnvelope deserialization logic from StrategyBusConsumer.
 */
public class InstrumentStrategyBusConsumer implements Consumer<EventEnvelope> {
    private static final Logger log = LoggerFactory.getLogger(InstrumentStrategyBusConsumer.class);

    private final String instrumentId;
    private final MarketDataService mdService;
    private final List<Strategy> strategies;
    private final EventSerdeRegistry serdeRegistry;
    private final DeadLetterChannel deadLetterChannel;

    /**
     * @param instrumentId     instrument identifier
     * @param mdService        market data service
     * @param strategies       pre-filtered strategies subscribing to this instrument
     * @param serdeRegistry    event serde registry
     * @param deadLetterChannel dead letter channel
     */
    public InstrumentStrategyBusConsumer(
            String instrumentId,
            MarketDataService mdService,
            List<Strategy> strategies,
            EventSerdeRegistry serdeRegistry,
            DeadLetterChannel deadLetterChannel) {
        this.instrumentId = instrumentId;
        this.mdService = mdService;
        this.strategies = strategies;
        this.serdeRegistry = serdeRegistry;
        this.deadLetterChannel = deadLetterChannel;
    }

    @Override
    public void accept(EventEnvelope env) {
        try {
            EventSerde serde = serdeRegistry.require(env.getPayloadCodec());

            if (env.getType() == EventType.MARKET_TICK) {
                handleMarketTick(env, serde);
                return;
            }
            if (env.getType() == EventType.BOOK_DELTA) {
                handleBookDelta(env, serde);
                return;
            }
            if (env.getType() == EventType.ORDER_ACK) {
                handleOrderAck(env, serde);
                return;
            }
            if (env.getType() == EventType.FILL) {
                handleFill(env, serde);
                return;
            }
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.HANDLER_FAILED,
                    "InstrumentStrategyBusConsumer[" + instrumentId + "].accept", env, e);
            log.warn("InstrumentStrategyBusConsumer[{}]: failed to handle {}: {}",
                    instrumentId, env.getType(), e.toString());
        }
    }

    private void handleMarketTick(EventEnvelope env, EventSerde serde) {
        try {
            MarketTickPayload payload = serde.deserialize(env.getPayload(), MarketTickPayload.class);
            MarketTick tick = toMarketTick(payload);
            if (tick == null) {
                return;
            }
            mdService.handleMarketTick(tick);
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.DESERIALIZE_FAILED,
                    "InstrumentStrategyBusConsumer[" + instrumentId + "].handleMarketTick", env, e);
            log.warn("InstrumentStrategyBusConsumer[{}]: failed to parse MARKET_TICK: {}",
                    instrumentId, e.toString());
        }
    }

    private void handleBookDelta(EventEnvelope env, EventSerde serde) {
        try {
            BookDeltaPayload payload = serde.deserialize(env.getPayload(), BookDeltaPayload.class);
            BookDelta delta = toBookDelta(payload);
            if (delta == null) {
                return;
            }
            mdService.handleBookDelta(delta);
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.DESERIALIZE_FAILED,
                    "InstrumentStrategyBusConsumer[" + instrumentId + "].handleBookDelta", env, e);
            log.warn("InstrumentStrategyBusConsumer[{}]: failed to parse BOOK_DELTA: {}",
                    instrumentId, e.toString());
        }
    }

    private void handleOrderAck(EventEnvelope env, EventSerde serde) {
        try {
            OrderAckPayload payload = serde.deserialize(env.getPayload(), OrderAckPayload.class);
            if (payload == null || payload.symbol == null) {
                return;
            }

            Symbol symbol = Symbol.of(payload.symbol);
            Side side = toSide(payload.side);
            long price = safeDecimalToPrice(symbol, payload.price);
            long qty = safeDecimalToQty(symbol, payload.quantity);
            long ts = env.getTsNs();

            OrderAck ack;
            if ("NEW".equalsIgnoreCase(payload.status)) {
                ack = OrderAck.accepted(payload.orderId, payload.clientOrderId, symbol, side, price, qty, ts);
            } else if ("REJECTED".equalsIgnoreCase(payload.status)
                    || "CANCELED".equalsIgnoreCase(payload.status)
                    || "EXPIRED".equalsIgnoreCase(payload.status)) {
                ack = OrderAck.rejected(payload.clientOrderId, symbol, side, price, qty, ts, payload.status);
            } else {
                log.debug("InstrumentStrategyBusConsumer[{}]: ignoring ORDER_ACK status={}",
                        instrumentId, payload.status);
                return;
            }

            for (Strategy strategy : strategies) {
                try {
                    strategy.onOrderAck(ack);
                } catch (Exception ex) {
                    deadLetterChannel.publish(DeadLetterReason.HANDLER_FAILED,
                            "InstrumentStrategyBusConsumer[" + instrumentId + "].onOrderAck." + strategy.getName(),
                            env, ex);
                    log.warn("InstrumentStrategyBusConsumer[{}]: strategy {} failed onOrderAck: {}",
                            instrumentId, strategy.getName(), ex.toString());
                }
            }
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.DESERIALIZE_FAILED,
                    "InstrumentStrategyBusConsumer[" + instrumentId + "].handleOrderAck", env, e);
            log.warn("InstrumentStrategyBusConsumer[{}]: failed to parse ORDER_ACK: {}",
                    instrumentId, e.toString());
        }
    }

    private void handleFill(EventEnvelope env, EventSerde serde) {
        try {
            FillPayload payload = serde.deserialize(env.getPayload(), FillPayload.class);
            if (payload == null || payload.symbol == null) {
                return;
            }

            Symbol symbol = Symbol.of(payload.symbol);
            Side side = toSide(payload.side);
            long price = safeDecimalToPrice(symbol, payload.price);
            long qty = safeDecimalToQty(symbol, payload.quantity);
            long commission = safeDecimalToQty(symbol, payload.commission);
            long ts = env.getTsNs();

            Fill fill = new Fill(
                    payload.tradeId,
                    payload.orderId,
                    payload.clientOrderId,
                    symbol,
                    side,
                    price,
                    qty,
                    ts,
                    payload.liquidity,
                    commission,
                    payload.commissionAsset);

            for (Strategy strategy : strategies) {
                try {
                    strategy.onFill(fill);
                } catch (Exception ex) {
                    deadLetterChannel.publish(DeadLetterReason.HANDLER_FAILED,
                            "InstrumentStrategyBusConsumer[" + instrumentId + "].onFill." + strategy.getName(),
                            env, ex);
                    log.warn("InstrumentStrategyBusConsumer[{}]: strategy {} failed onFill: {}",
                            instrumentId, strategy.getName(), ex.toString());
                }
            }
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.DESERIALIZE_FAILED,
                    "InstrumentStrategyBusConsumer[" + instrumentId + "].handleFill", env, e);
            log.warn("InstrumentStrategyBusConsumer[{}]: failed to parse FILL: {}",
                    instrumentId, e.toString());
        }
    }

    private static MarketTick toMarketTick(MarketTickPayload payload) {
        if (payload == null || payload.symbol == null) {
            return null;
        }
        return new MarketTick(
                Symbol.of(payload.symbol),
                payload.timestamp,
                payload.price,
                payload.quantity,
                payload.buy,
                payload.sequenceNumber);
    }

    private static BookDelta toBookDelta(BookDeltaPayload payload) {
        if (payload == null || payload.symbol == null) {
            return null;
        }
        Side side = toSide(payload.side);
        BookDelta.Action action = toAction(payload.action);
        return new BookDelta(
                Symbol.of(payload.symbol),
                payload.timestamp,
                side,
                payload.price,
                payload.quantity,
                action,
                payload.sequenceNumber);
    }

    private static Side toSide(String s) {
        return "SELL".equalsIgnoreCase(s) ? Side.SELL : Side.BUY;
    }

    private static BookDelta.Action toAction(String action) {
        if ("DELETE".equalsIgnoreCase(action)) {
            return BookDelta.Action.DELETE;
        }
        if ("UPDATE".equalsIgnoreCase(action)) {
            return BookDelta.Action.UPDATE;
        }
        return BookDelta.Action.ADD;
    }

    private static long safeDecimalToPrice(Symbol symbol, String decimal) {
        try {
            if (decimal == null) {
                return 0L;
            }
            return symbol.decimalToPrice(Double.parseDouble(decimal));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long safeDecimalToQty(Symbol symbol, String decimal) {
        try {
            if (decimal == null) {
                return 0L;
            }
            return symbol.decimalToQty(Double.parseDouble(decimal));
        } catch (Exception e) {
            return 0L;
        }
    }
}
