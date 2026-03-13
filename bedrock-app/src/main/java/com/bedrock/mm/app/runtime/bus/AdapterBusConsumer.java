package com.bedrock.mm.app.runtime.bus;

import com.bedrock.mm.adapter.AdapterService;
import com.bedrock.mm.adapter.TradingAdapter;
import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventSerdeRegistry;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.OrderCommand;
import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Adapter side consumer: handles ORDER_COMMAND and submits to best available adapter.
 */
public class AdapterBusConsumer implements Consumer<EventEnvelope> {
    private static final Logger log = LoggerFactory.getLogger(AdapterBusConsumer.class);
    private final AdapterService adapterService;
    private final EventSerdeRegistry serdeRegistry;
    private final DeadLetterChannel deadLetterChannel;
    private final ConcurrentMap<String, AtomicLong> lastSeqByStream = new ConcurrentHashMap<>();

    public AdapterBusConsumer(AdapterService adapterService, EventSerdeRegistry serdeRegistry,
                              DeadLetterChannel deadLetterChannel) {
        this.adapterService = adapterService;
        this.serdeRegistry = serdeRegistry;
        this.deadLetterChannel = deadLetterChannel;
    }

    @Override
    public void accept(EventEnvelope env) {
        if (env.getType() != EventType.ORDER_COMMAND) {
            return; // ignore non-order events
        }
        if (!acceptIfInOrder(env)) {
            return;
        }
        try {
            EventSerde serde = serdeRegistry.require(env.getPayloadCodec());
            OrderCommand cmd = serde.deserialize(env.getPayload(), OrderCommand.class);
            Optional<Symbol> symbolOpt = resolveSymbol(cmd.getSymbol());
            if (symbolOpt.isEmpty()) {
                log.warn("AdapterBusConsumer: no adapter supports symbol {}", cmd.getSymbol());
                return;
            }
            Symbol symbol = symbolOpt.get();
            Side side = "SELL".equalsIgnoreCase(cmd.getSide()) ? Side.SELL : Side.BUY;
            TradingAdapter.OrderType type = toOrderType(cmd.getType());
            TradingAdapter.TimeInForce tif = toTimeInForce(cmd.getTimeInForce());

            TradingAdapter.OrderRequest request = new TradingAdapter.OrderRequest(
                    cmd.getClientOrderId(),
                    symbol,
                    side,
                    type,
                    cmd.getPrice(),
                    cmd.getQuantity(),
                    tif,
                    cmd.getStrategyId()
            );

            TradingAdapter adapter = selectAdapter(symbol);
            if (adapter == null) {
                log.warn("AdapterBusConsumer: no connected adapter available for {}", symbol.getName());
                return;
            }

            adapter.submitOrder(request).thenAccept(resp -> {
                log.info("AdapterBusConsumer: order submitted: {} {} @ {} qty {} => {}",
                        symbol.getName(), side, cmd.getPrice(), cmd.getQuantity(), resp.orderId());
            }).exceptionally(ex -> {
                log.warn("AdapterBusConsumer: submit failed: {}", ex.toString());
                return null;
            });
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.HANDLER_FAILED, "AdapterBusConsumer.accept", env, e);
            log.warn("AdapterBusConsumer: failed to handle ORDER_COMMAND: {}", e.toString());
        }
    }

    private TradingAdapter.OrderType toOrderType(String type) {
        if (type == null) return TradingAdapter.OrderType.LIMIT;
        switch (type.toUpperCase()) {
            case "MARKET": return TradingAdapter.OrderType.MARKET;
            case "STOP": return TradingAdapter.OrderType.STOP;
            case "STOP_LIMIT": return TradingAdapter.OrderType.STOP_LIMIT;
            default: return TradingAdapter.OrderType.LIMIT;
        }
    }

    private TradingAdapter.TimeInForce toTimeInForce(String tif) {
        if (tif == null) return TradingAdapter.TimeInForce.GTC;
        switch (tif.toUpperCase()) {
            case "IOC": return TradingAdapter.TimeInForce.IOC;
            case "FOK": return TradingAdapter.TimeInForce.FOK;
            default: return TradingAdapter.TimeInForce.GTC;
        }
    }

    private Optional<Symbol> resolveSymbol(String symbolName) {
        return adapterService.getAllAdapters().stream()
                .flatMap(a -> a.getSupportedSymbols().stream())
                .filter(s -> s.getName().equalsIgnoreCase(symbolName))
                .findFirst();
    }

    private TradingAdapter selectAdapter(Symbol symbol) {
        List<TradingAdapter> candidates = adapterService.getAdaptersForSymbol(symbol);
        return candidates.stream().filter(TradingAdapter::isConnected).findFirst().orElse(null);
    }

    private boolean acceptIfInOrder(EventEnvelope env) {
        String symbol = env.getSymbol() == null ? "" : env.getSymbol();
        String key = env.getType().name() + "|" + symbol;
        AtomicLong last = lastSeqByStream.computeIfAbsent(key, k -> new AtomicLong(Long.MIN_VALUE));
        long seq = env.getSeq();
        while (true) {
            long current = last.get();
            if (seq <= current) {
                deadLetterChannel.publish(DeadLetterReason.OUT_OF_ORDER, "AdapterBusConsumer.ordering", env, null);
                log.warn("AdapterBusConsumer: drop out-of-order event type={} symbol={} seq={} last={}",
                        env.getType(), symbol, seq, current);
                return false;
            }
            if (last.compareAndSet(current, seq)) {
                return true;
            }
        }
    }
}
