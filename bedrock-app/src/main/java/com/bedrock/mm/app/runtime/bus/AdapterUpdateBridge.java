package com.bedrock.mm.app.runtime.bus;

import com.bedrock.mm.adapter.AdapterService;
import com.bedrock.mm.adapter.TradingAdapter;
import com.bedrock.mm.aeron.bus.EventBus;
import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.FillPayload;
import com.bedrock.mm.common.event.OrderAckPayload;
import com.bedrock.mm.common.event.PayloadCodec;
import com.bedrock.mm.common.model.Symbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges adapter-level OrderUpdate/TradeUpdate into unified EventBus events
 * (ORDER_ACK and FILL), so strategies receive fills/acks in simulation and live modes.
 */
public class AdapterUpdateBridge {
    private static final Logger log = LoggerFactory.getLogger(AdapterUpdateBridge.class);

    private final AdapterService adapterService;
    private final EventBus eventBus;
    private final EventSerde serde;
    private final DeadLetterChannel deadLetterChannel;
    private final AtomicLong seq = new AtomicLong(0);
    private final AtomicBoolean attached = new AtomicBoolean(false);

    public AdapterUpdateBridge(AdapterService adapterService, EventBus eventBus, EventSerde serde,
                               DeadLetterChannel deadLetterChannel) {
        this.adapterService = adapterService;
        this.eventBus = eventBus;
        this.serde = serde;
        this.deadLetterChannel = deadLetterChannel;
    }

    /** Attach handlers to all adapters. Safe to call multiple times; attaches only once. */
    public void attach() {
        if (eventBus == null) {
            log.debug("AdapterUpdateBridge: EventBus unavailable; skipping attach");
            return;
        }
        if (!attached.compareAndSet(false, true)) {
            return; // already attached
        }

        for (TradingAdapter adapter : adapterService.getAllAdapters()) {
            try {
                adapter.subscribeOrderUpdates(this::onOrderUpdate);
                adapter.subscribeTradeUpdates(this::onTradeUpdate);
                log.info("AdapterUpdateBridge: attached to adapter {}", adapter.getName());
            } catch (Exception e) {
                log.warn("AdapterUpdateBridge: failed attaching to {}: {}", adapter.getName(), e.getMessage());
            }
        }
    }

    private void onOrderUpdate(TradingAdapter.OrderUpdate u) {
        try {
            Symbol symbol = u.symbol();
            long eventTimeMs = u.timestamp();
            long tsNs = eventTimeMs * 1_000_000L;
            long sequence = seq.incrementAndGet();

            OrderAckPayload payload = new OrderAckPayload(
                    symbol.getName(),
                    u.orderId(),
                    u.clientOrderId(),
                    u.side().name(),
                    u.type().name(),
                    u.status().name(),
                    u.price() != null ? u.price().toPlainString() : null,
                    u.quantity() != null ? u.quantity().toPlainString() : null,
                    eventTimeMs
            );
            byte[] payloadBytes = serde.serialize(payload);

            publish(EventType.ORDER_ACK, payloadBytes, serde.codec(), symbol.getName(), tsNs, sequence);
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.BRIDGE_FAILED, "AdapterUpdateBridge.onOrderUpdate",
                    EventType.ORDER_ACK, serde.codec(), null, -1L, e.toString());
            log.warn("AdapterUpdateBridge: failed to publish ORDER_ACK: {}", e.toString());
        }
    }

    private void onTradeUpdate(TradingAdapter.TradeUpdate t) {
        try {
            Symbol symbol = t.symbol();
            long eventTimeMs = t.timestamp();
            long tsNs = eventTimeMs * 1_000_000L;
            long sequence = seq.incrementAndGet();

            FillPayload payload = new FillPayload(
                    symbol.getName(),
                    t.orderId(),
                    t.clientOrderId(),
                    t.tradeId(),
                    t.side().name(),
                    t.price() != null ? t.price().toPlainString() : null,
                    t.quantity() != null ? t.quantity().toPlainString() : null,
                    t.isMaker() ? "MAKER" : "TAKER",
                    t.commission() != null ? t.commission().toPlainString() : null,
                    t.commissionAsset(),
                    eventTimeMs
            );
            byte[] payloadBytes = serde.serialize(payload);

            publish(EventType.FILL, payloadBytes, serde.codec(), symbol.getName(), tsNs, sequence);
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.BRIDGE_FAILED, "AdapterUpdateBridge.onTradeUpdate",
                    EventType.FILL, serde.codec(), null, -1L, e.toString());
            log.warn("AdapterUpdateBridge: failed to publish FILL: {}", e.toString());
        }
    }

    private void publish(EventType type, byte[] payload, PayloadCodec payloadCodec, String symbol, long tsNs, long seqNum) {
        try {
            EventEnvelope env = new EventEnvelope(type, payload, payloadCodec, symbol, tsNs, seqNum);
            eventBus.publish(env);
        } catch (Exception e) {
            deadLetterChannel.publish(DeadLetterReason.PUBLISH_FAILED, "AdapterUpdateBridge.publish",
                    type, payloadCodec, symbol, seqNum, e.toString());
            log.warn("AdapterUpdateBridge: error publishing {} for {}: {}", type, symbol, e.toString());
        }
    }
}
