package com.bedrock.mm.app.runtime.bus.instrument;

import com.bedrock.mm.adapter.TradingAdapter;
import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventSerdeRegistry;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.OrderCommand;
import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.app.runtime.bus.DeadLetterChannel;
import com.bedrock.mm.app.runtime.bus.DeadLetterReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Per-instrument adapter consumer: handles ORDER_COMMAND for a single adapter.
 * <p>
 * Constructed with the TradingAdapter for this instrument. No symbol resolution
 * or adapter selection is performed on the hot path.
 * <p>
 * Reuses order command processing logic from AdapterBusConsumer.
 */
public class InstrumentAdapterBusConsumer implements Consumer<EventEnvelope> {
    private static final Logger log = LoggerFactory.getLogger(InstrumentAdapterBusConsumer.class);

    private final String instrumentId;
    private final TradingAdapter adapter;
    private final EventSerdeRegistry serdeRegistry;
    private final DeadLetterChannel deadLetterChannel;

    /**
     * @param instrumentId     instrument identifier
     * @param adapter          trading adapter for this instrument
     * @param serdeRegistry    event serde registry
     * @param deadLetterChannel dead letter channel
     */
    public InstrumentAdapterBusConsumer(
            String instrumentId,
            TradingAdapter adapter,
            EventSerdeRegistry serdeRegistry,
            DeadLetterChannel deadLetterChannel) {
        this.instrumentId = instrumentId;
        this.adapter = adapter;
        this.serdeRegistry = serdeRegistry;
        this.deadLetterChannel = deadLetterChannel;
    }

    @Override
    public void accept(EventEnvelope env) {
        if (env.getType() != EventType.ORDER_COMMAND) {
            return; // ignore non-order events
        }

        try {
            EventSerde serde = serdeRegistry.require(env.getPayloadCodec());
            OrderCommand cmd = serde.deserialize(env.getPayload(), OrderCommand.class);

            if (cmd == null || cmd.getSymbol() == null) {
                log.warn("InstrumentAdapterBusConsumer[{}]: invalid ORDER_COMMAND payload", instrumentId);
                return;
            }

            Symbol symbol = Symbol.of(cmd.getSymbol());
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
                    cmd.getStrategyId());

            if (!adapter.isConnected()) {
                log.warn("InstrumentAdapterBusConsumer[{}]: adapter {} not connected, dropping order {}",
                        instrumentId, adapter.getName(), cmd.getClientOrderId());
                return;
            }

            adapter.submitOrder(request).thenAccept(resp -> {
                log.info("InstrumentAdapterBusConsumer[{}]: order submitted: {} {} @ {} qty {} => {}",
                        instrumentId, symbol.getName(), side, cmd.getPrice(), cmd.getQuantity(), resp.orderId());
            }).exceptionally(ex -> {
                log.warn("InstrumentAdapterBusConsumer[{}]: submit failed for {}: {}",
                        instrumentId, cmd.getClientOrderId(), ex.toString());
                return null;
            });

        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.HANDLER_FAILED,
                    "InstrumentAdapterBusConsumer[" + instrumentId + "].accept", env, e);
            log.warn("InstrumentAdapterBusConsumer[{}]: failed to handle ORDER_COMMAND: {}",
                    instrumentId, e.toString());
        }
    }

    private TradingAdapter.OrderType toOrderType(String type) {
        if (type == null) {
            return TradingAdapter.OrderType.LIMIT;
        }
        switch (type.toUpperCase()) {
            case "MARKET":
                return TradingAdapter.OrderType.MARKET;
            case "STOP":
                return TradingAdapter.OrderType.STOP;
            case "STOP_LIMIT":
                return TradingAdapter.OrderType.STOP_LIMIT;
            default:
                return TradingAdapter.OrderType.LIMIT;
        }
    }

    private TradingAdapter.TimeInForce toTimeInForce(String tif) {
        if (tif == null) {
            return TradingAdapter.TimeInForce.GTC;
        }
        switch (tif.toUpperCase()) {
            case "IOC":
                return TradingAdapter.TimeInForce.IOC;
            case "FOK":
                return TradingAdapter.TimeInForce.FOK;
            default:
                return TradingAdapter.TimeInForce.GTC;
        }
    }
}
