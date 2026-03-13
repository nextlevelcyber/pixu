package com.bedrock.mm.oms.exec;

import com.bedrock.mm.oms.model.ExecEvent;
import com.bedrock.mm.oms.model.ExecEventType;
import com.bedrock.mm.oms.model.Order;
import com.bedrock.mm.oms.model.OrderState;
import com.bedrock.mm.oms.store.OrderStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RestReconciliation - Compares REST /openOrders snapshot vs OMS OrderStore.
 *
 * Generates synthetic ExecEvents for missed order updates (acks, fills, cancels)
 * detected during private WebSocket reconnection.
 *
 * Algorithm:
 * 1. Build set of exchange order IDs from REST response
 * 2. For each OMS order in PENDING_NEW/OPEN/PARTIAL_FILL:
 *    - If NOT in REST set → order was filled or cancelled → generate FILLED or CANCELLED event
 *    - If in REST set → check filled quantity → generate PARTIAL_FILL if needed
 * 3. For each REST order NOT in OMS → generate synthetic ACK event (missed acknowledgment)
 */
public class RestReconciliation {
    private static final Logger log = LoggerFactory.getLogger(RestReconciliation.class);

    /**
     * Exchange order representation from REST API.
     */
    public static class ExchangeOrder {
        public long exchOrderId;
        public String clientOrderId;
        public int instrumentId;
        public long price;
        public long origSize;
        public long filledSize;
        public boolean isBid;
        public String status;  // "NEW", "PARTIALLY_FILLED", "FILLED", etc.
    }

    /**
     * Reconcile OMS state against exchange REST snapshot.
     *
     * @param restOrders list of open orders from exchange REST API
     * @param store OMS order store
     * @return list of synthetic ExecEvents to apply
     */
    public List<ExecEvent> reconcile(List<ExchangeOrder> restOrders, OrderStore store) {
        List<ExecEvent> events = new ArrayList<>();

        if (restOrders == null) {
            restOrders = List.of();
        }

        Map<Long, ExchangeOrder> restByExchId = new HashMap<>();
        Map<Long, ExchangeOrder> restByClientOrderId = new HashMap<>();
        for (ExchangeOrder restOrder : restOrders) {
            if (restOrder == null) {
                continue;
            }
            if (restOrder.exchOrderId != 0L) {
                restByExchId.put(restOrder.exchOrderId, restOrder);
            }
            long clientOrderId = parseClientOrderId(restOrder.clientOrderId);
            if (clientOrderId != 0L) {
                restByClientOrderId.put(clientOrderId, restOrder);
            }
        }

        List<Order> activeOrders = store.getActiveOrdersSnapshot();
        for (Order omsOrder : activeOrders) {
            if (omsOrder.exchOrderId == 0L) {
                ExchangeOrder matched = restByClientOrderId.remove(omsOrder.orderId);
                if (matched == null || matched.exchOrderId == 0L) {
                    log.warn("Reconcile: order {} still PENDING_NEW, no exchOrderId", omsOrder.orderId);
                    continue;
                }

                restByExchId.remove(matched.exchOrderId);
                events.add(buildAckEvent(omsOrder, matched));
                log.info("Reconcile: recovered ACK for internalOrderId={} exchOrderId={}",
                        omsOrder.orderId, matched.exchOrderId);

                if (matched.filledSize > omsOrder.filledSize) {
                    events.add(buildFillDeltaEvent(omsOrder, matched));
                    log.info("Reconcile: recovered fill delta for internalOrderId={} delta={}",
                            omsOrder.orderId, matched.filledSize - omsOrder.filledSize);
                }
                continue;
            }

            ExchangeOrder restOrder = restByExchId.remove(omsOrder.exchOrderId);
            if (restOrder == null) {
                // Missing from openOrders -> likely terminal on exchange.
                events.add(buildMissingFromRestTerminalEvent(omsOrder));
                continue;
            }

            long clientOrderId = parseClientOrderId(restOrder.clientOrderId);
            if (clientOrderId != 0L) {
                restByClientOrderId.remove(clientOrderId);
            }

            if (restOrder.filledSize > omsOrder.filledSize) {
                // Partial fill detected
                events.add(buildFillDeltaEvent(omsOrder, restOrder));
                log.info("Reconcile: order {} partial fill detected (delta {})",
                        omsOrder.orderId, restOrder.filledSize - omsOrder.filledSize);
            }
        }

        // Remaining orders from REST cannot be mapped to OMS state.
        for (ExchangeOrder restOrder : restByExchId.values()) {
            log.warn("Reconcile: unmatched exchange order skipped exchOrderId={} clientOrderId={} status={}",
                    restOrder.exchOrderId, restOrder.clientOrderId, restOrder.status);
        }

        log.info("Reconciliation generated {} synthetic events", events.size());
        return events;
    }

    /**
     * Bootstrap local OrderStore from REST openOrders snapshot.
     * <p>
     * This path is used for stateless OMS restart: when process memory is empty after reboot,
     * open orders can be reconstructed from exchange state via clientOrderId -> internalOrderId.
     *
     * @param restOrders list of open orders from exchange REST API
     * @param store OMS order store
     * @return number of orders reconstructed into OrderStore
     */
    public int bootstrapOpenOrders(List<ExchangeOrder> restOrders, OrderStore store) {
        if (restOrders == null || restOrders.isEmpty()) {
            return 0;
        }

        int reconstructed = 0;
        for (ExchangeOrder restOrder : restOrders) {
            if (restOrder == null || restOrder.exchOrderId == 0L) {
                continue;
            }

            long internalOrderId = parseClientOrderId(restOrder.clientOrderId);
            if (internalOrderId == 0L) {
                log.warn("Bootstrap: skip open order without numeric clientOrderId exchOrderId={} clientOrderId={}",
                        restOrder.exchOrderId, restOrder.clientOrderId);
                continue;
            }

            Order byInternal = store.getOrder(internalOrderId);
            if (byInternal != null) {
                if (byInternal.exchOrderId == 0L && restOrder.exchOrderId != 0L) {
                    store.updateExchOrderId(internalOrderId, restOrder.exchOrderId);
                }
                continue;
            }

            Order byExch = store.getByExchOrderId(restOrder.exchOrderId);
            if (byExch != null) {
                continue;
            }

            Order recovered = new Order();
            recovered.orderId = internalOrderId;
            recovered.exchOrderId = restOrder.exchOrderId;
            recovered.instrumentId = restOrder.instrumentId;
            recovered.price = restOrder.price;
            recovered.origSize = Math.max(0L, restOrder.origSize);
            recovered.filledSize = clampFill(recovered.origSize, restOrder.filledSize);
            recovered.fillValueSum = recovered.filledSize > 0L
                    ? recovered.filledSize * recovered.price
                    : 0L;
            recovered.state = deriveActiveState(restOrder);
            recovered.regionIndex = -1; // restart reconstruction path: region unknown
            recovered.isBid = restOrder.isBid;
            recovered.createNanos = System.nanoTime();
            recovered.quotePublishNanos = 0L;
            recovered.quoteSeqId = 0L;

            store.addOrder(recovered);
            reconstructed++;
        }

        if (reconstructed > 0) {
            log.info("Bootstrap: reconstructed {} open orders into OrderStore", reconstructed);
        }
        return reconstructed;
    }

    /**
     * Count exchange open orders that still cannot be mapped to local OrderStore.
     * <p>
     * Caller should invoke this after bootstrap/reconcile when using stateless restart.
     *
     * @param restOrders list of open orders from exchange REST API
     * @param store OMS order store
     * @return number of unmapped open orders
     */
    public int countUnmappedOpenOrders(List<ExchangeOrder> restOrders, OrderStore store) {
        if (restOrders == null || restOrders.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (ExchangeOrder restOrder : restOrders) {
            if (restOrder == null || restOrder.exchOrderId == 0L) {
                continue;
            }
            Order mapped = store.getByExchOrderId(restOrder.exchOrderId);
            if (mapped == null) {
                count++;
            }
        }
        return count;
    }

    private static ExecEvent buildAckEvent(Order omsOrder, ExchangeOrder restOrder) {
        ExecEvent event = baseEvent(omsOrder);
        event.exchOrderId = restOrder.exchOrderId;
        event.type = ExecEventType.ACK;
        event.remainSize = Math.max(0L, restOrder.origSize - restOrder.filledSize);
        return event;
    }

    private static ExecEvent buildFillDeltaEvent(Order omsOrder, ExchangeOrder restOrder) {
        long fillDelta = restOrder.filledSize - omsOrder.filledSize;
        ExecEvent event = baseEvent(omsOrder);
        event.exchOrderId = restOrder.exchOrderId == 0L ? omsOrder.exchOrderId : restOrder.exchOrderId;
        event.type = (restOrder.filledSize >= restOrder.origSize) ? ExecEventType.FILL : ExecEventType.PARTIAL_FILL;
        event.fillSize = fillDelta;
        event.fillPrice = restOrder.price;
        event.remainSize = Math.max(0L, restOrder.origSize - restOrder.filledSize);
        return event;
    }

    private static ExecEvent buildMissingFromRestTerminalEvent(Order omsOrder) {
        ExecEvent event = baseEvent(omsOrder);
        if (omsOrder.state == OrderState.PARTIAL_FILL && omsOrder.filledSize > 0) {
            event.type = ExecEventType.FILL;
            event.fillSize = Math.max(0L, omsOrder.origSize - omsOrder.filledSize);
            event.fillPrice = omsOrder.price;
            event.remainSize = 0L;
            log.info("Reconcile: order {} synthetically FILLED (missing from REST)", omsOrder.orderId);
        } else {
            event.type = ExecEventType.CANCELLED;
            event.remainSize = Math.max(0L, omsOrder.origSize - omsOrder.filledSize);
            log.info("Reconcile: order {} synthetically CANCELLED (missing from REST)", omsOrder.orderId);
        }
        return event;
    }

    private static ExecEvent baseEvent(Order omsOrder) {
        ExecEvent event = new ExecEvent();
        long now = System.nanoTime();
        event.seqId = now;
        event.recvNanos = now;
        event.instrumentId = omsOrder.instrumentId;
        event.internalOrderId = omsOrder.orderId;
        event.exchOrderId = omsOrder.exchOrderId;
        event.isBid = omsOrder.isBid;
        return event;
    }

    private static long clampFill(long origSize, long filledSize) {
        if (filledSize <= 0L) {
            return 0L;
        }
        if (origSize <= 0L) {
            return filledSize;
        }
        return Math.min(origSize, filledSize);
    }

    private static OrderState deriveActiveState(ExchangeOrder restOrder) {
        if (restOrder == null) {
            return OrderState.OPEN;
        }
        if (restOrder.origSize > 0L && restOrder.filledSize > 0L && restOrder.filledSize < restOrder.origSize) {
            return OrderState.PARTIAL_FILL;
        }
        String status = restOrder.status;
        if (status != null && status.equalsIgnoreCase("PARTIALLY_FILLED")) {
            return OrderState.PARTIAL_FILL;
        }
        return OrderState.OPEN;
    }

    private static long parseClientOrderId(String clientOrderId) {
        if (clientOrderId == null || clientOrderId.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(clientOrderId);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
