package com.bedrock.mm.app.runtime.bus;

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
import com.bedrock.mm.strategy.StrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Strategy-side consumer: delivers MARKET_TICK/BOOK_DELTA to MD local
 * subscribers,
 * and consumes ORDER_ACK/FILL to notify strategies.
 * Enforces ordered dispatch via EventDispatcher: strategy first.
 */
public class StrategyBusConsumer implements Consumer<EventEnvelope> {
    private static final Logger log = LoggerFactory.getLogger(StrategyBusConsumer.class);
    private final MarketDataService mdService;
    private final StrategyService strategyService;
    private final EventSerdeRegistry serdeRegistry;
    private final DeadLetterChannel deadLetterChannel;
    private final ConcurrentMap<String, AtomicLong> lastSeqByStream = new ConcurrentHashMap<>();

    public StrategyBusConsumer(MarketDataService mdService, StrategyService strategyService,
                               EventSerdeRegistry serdeRegistry, DeadLetterChannel deadLetterChannel) {
        this.mdService = mdService;
        this.strategyService = strategyService;
        this.serdeRegistry = serdeRegistry;
        this.deadLetterChannel = deadLetterChannel;
    }

    @Override
    public void accept(EventEnvelope env) {
        try {
            if (!acceptIfInOrder(env)) {
                return;
            }
            EventSerde serde = serdeRegistry.require(env.getPayloadCodec());
            if (env.getType() == EventType.MARKET_TICK) {
                MarketTickPayload payload = serde.deserialize(env.getPayload(), MarketTickPayload.class);
                MarketTick tick = toMarketTick(payload);
                mdService.handleMarketTick(tick);
                return;
            }
            if (env.getType() == EventType.BOOK_DELTA) {
                BookDeltaPayload payload = serde.deserialize(env.getPayload(), BookDeltaPayload.class);
                BookDelta delta = toBookDelta(payload);
                mdService.handleBookDelta(delta);
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
            deadLetterChannel.publish(DeadLetterReason.HANDLER_FAILED, "StrategyBusConsumer.accept", env, e);
            log.warn("StrategyBusConsumer: failed to handle {}: {}", env.getType(), e.toString());
        }
    }

    private void handleOrderAck(EventEnvelope env, EventSerde serde) {
        if (strategyService == null)
            return;
        try {
            OrderAckPayload payload = serde.deserialize(env.getPayload(), OrderAckPayload.class);
            if (payload == null || payload.symbol == null)
                return;
            String symbolName = payload.symbol;

            for (Map.Entry<String, Strategy> entry : strategyService.getAllStrategies().entrySet()) {
                Strategy strategy = entry.getValue();
                Symbol symbol = matchSymbol(strategy.getSymbols(), symbolName);
                if (symbol == null)
                    continue;

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
                    // Unknown statuses: log and treat as info only
                    log.debug("StrategyBusConsumer: ignoring ORDER_ACK status={} for {}", payload.status, symbolName);
                    continue;
                }
                try {
                    strategy.onOrderAck(ack);
                } catch (Exception ex) {
                    deadLetterChannel.publish(DeadLetterReason.HANDLER_FAILED,
                            "StrategyBusConsumer.onOrderAck." + strategy.getName(), env, ex);
                    log.warn("StrategyBusConsumer: strategy {} failed onOrderAck: {}", strategy.getName(),
                            ex.toString());
                }
            }
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.DESERIALIZE_FAILED, "StrategyBusConsumer.handleOrderAck", env, e);
            log.warn("StrategyBusConsumer: failed to parse ORDER_ACK payload: {}", e.toString());
        }
    }

    private void handleFill(EventEnvelope env, EventSerde serde) {
        if (strategyService == null)
            return;
        try {
            FillPayload payload = serde.deserialize(env.getPayload(), FillPayload.class);
            if (payload == null || payload.symbol == null)
                return;
            String symbolName = payload.symbol;

            for (Map.Entry<String, Strategy> entry : strategyService.getAllStrategies().entrySet()) {
                Strategy strategy = entry.getValue();
                Symbol symbol = matchSymbol(strategy.getSymbols(), symbolName);
                if (symbol == null)
                    continue;

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
                try {
                    strategy.onFill(fill);
                } catch (Exception ex) {
                    deadLetterChannel.publish(DeadLetterReason.HANDLER_FAILED,
                            "StrategyBusConsumer.onFill." + strategy.getName(), env, ex);
                    log.warn("StrategyBusConsumer: strategy {} failed onFill: {}", strategy.getName(), ex.toString());
                }
            }
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.DESERIALIZE_FAILED, "StrategyBusConsumer.handleFill", env, e);
            log.warn("StrategyBusConsumer: failed to parse FILL payload: {}", e.toString());
        }
    }

    private static Symbol matchSymbol(Symbol[] symbols, String name) {
        if (symbols == null || name == null)
            return null;
        return Arrays.stream(symbols)
                .filter(s -> s.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    private static Side toSide(String s) {
        return "SELL".equalsIgnoreCase(s) ? Side.SELL : Side.BUY;
    }

    private boolean acceptIfInOrder(EventEnvelope env) {
        String symbol = env.getSymbol() == null ? "" : env.getSymbol();
        // Use computeIfAbsent with lambda to avoid pre-computing key
        // The key is only constructed once per unique type+symbol combination
        AtomicLong last = lastSeqByStream.computeIfAbsent(
            env.getType().name() + "|" + symbol,
            k -> new AtomicLong(Long.MIN_VALUE)
        );
        long seq = env.getSeq();
        while (true) {
            long current = last.get();
            if (seq <= current) {
                deadLetterChannel.publish(DeadLetterReason.OUT_OF_ORDER, "StrategyBusConsumer.ordering", env, null);
                log.warn("StrategyBusConsumer: drop out-of-order event type={} symbol={} seq={} last={}",
                        env.getType(), symbol, seq, current);
                return false;
            }
            if (last.compareAndSet(current, seq)) {
                return true;
            }
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
            if (decimal == null)
                return 0L;
            return symbol.decimalToPrice(Double.parseDouble(decimal));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long safeDecimalToQty(Symbol symbol, String decimal) {
        try {
            if (decimal == null)
                return 0L;
            return symbol.decimalToQty(Double.parseDouble(decimal));
        } catch (Exception e) {
            return 0L;
        }
    }
}
