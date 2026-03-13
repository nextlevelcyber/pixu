package com.bedrock.mm.app.strategy;

import com.bedrock.mm.adapter.AdapterService;
import com.bedrock.mm.aeron.bus.EventBus;
import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.OrderCommand;
import com.bedrock.mm.adapter.TradingAdapter;
import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.strategy.OrderManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * OrderManager implementation backed by AdapterService and TradingAdapters.
 */
@Service
@ConditionalOnProperty(name = "bedrock.adapter.enabled", havingValue = "true", matchIfMissing = false)
public class AdapterOrderManager implements OrderManager {
    private static final Logger log = LoggerFactory.getLogger(AdapterOrderManager.class);

    private final AdapterService adapterService;
    private final EventBus eventBus;
    private final EventSerde eventSerde;

    @Autowired
    public AdapterOrderManager(AdapterService adapterService, EventBus eventBus, EventSerde eventSerde) {
        this.adapterService = adapterService;
        this.eventBus = eventBus;
        this.eventSerde = eventSerde;
    }

    @Override
    public String submitOrder(String symbolName, String side, double price, double quantity) {
        try {
            // Build OrderCommand payload and publish via unified event bus
            OrderCommand cmd = new OrderCommand();
            cmd.setSymbol(symbolName);
            cmd.setSide(side);
            cmd.setType("LIMIT");
            // Convert double to long fixed-point (scale 1e-8)
            cmd.setPrice((long) (price * 100_000_000));
            cmd.setQuantity((long) (quantity * 100_000_000));
            cmd.setTimeInForce("GTC");
            cmd.setStrategyId("Strategy");
            String clientOrderId = String.format("%s_%d", symbolName, System.currentTimeMillis());
            cmd.setClientOrderId(clientOrderId);

            byte[] payload = eventSerde.serialize(cmd);
            long tsNs = System.nanoTime();
            long seq = tsNs; // simple monotonic surrogate; real seq from publisher if needed
            EventEnvelope env = new EventEnvelope(EventType.ORDER_COMMAND, payload, eventSerde.codec(), symbolName, tsNs, seq);
            boolean ok = eventBus != null && eventBus.publish(env);
            if (!ok) {
                log.warn("AdapterOrderManager: event bus publish failed for {} {} @ {} qty {}",
                        symbolName, side, price, quantity);
            } else {
                log.info("AdapterOrderManager: ORDER_COMMAND published for {} {} @ {} qty {} (cid={})",
                        symbolName, side, price, quantity, clientOrderId);
            }
            // Return client order id as a handle; actual order id will arrive via adapter updates
            return clientOrderId;
        } catch (Exception e) {
            log.warn("AdapterOrderManager: submitOrder failed for {} {} @ {} qty {}. {}",
                    symbolName, side, price, quantity, e.toString());
            return null;
        }
    }

    @Override
    public boolean cancelOrder(String orderId) {
        try {
            boolean anySuccess = false;
            for (TradingAdapter adapter : adapterService.getConnectedAdapters()) {
                try {
                    TradingAdapter.CancelResponse resp = adapter.cancelOrder(orderId).join();
                    anySuccess = anySuccess || resp.success();
                } catch (Exception ex) {
                    // ignore and try next adapter
                }
            }
            log.info("AdapterOrderManager: cancel order {} => {}", orderId, anySuccess);
            return anySuccess;
        } catch (Exception e) {
            log.warn("AdapterOrderManager: cancelOrder failed for {}. {}", orderId, e.toString());
            return false;
        }
    }

    @Override
    public boolean isOrderActive(String orderId) {
        try {
            for (TradingAdapter adapter : adapterService.getConnectedAdapters()) {
                try {
                    TradingAdapter.OrderStatus status = adapter.getOrderStatus(orderId).join();
                    TradingAdapter.OrderStatus.Status s = status.status();
                    if (s == TradingAdapter.OrderStatus.Status.NEW ||
                        s == TradingAdapter.OrderStatus.Status.PARTIALLY_FILLED) {
                        return true;
                    }
                } catch (Exception ex) {
                    // ignore and try next adapter
                }
            }
            return false;
        } catch (Exception e) {
            log.warn("AdapterOrderManager: isOrderActive failed for {}. {}", orderId, e.toString());
            return false;
        }
    }

    private Optional<Symbol> resolveSymbol(String symbolName) {
        return adapterService.getAllAdapters().stream()
                .flatMap(a -> a.getSupportedSymbols().stream())
                .filter(s -> s.getName().equalsIgnoreCase(symbolName))
                .findFirst();
    }
}
