package com.bedrock.mm.oms.exec;

import com.bedrock.mm.oms.model.ExecEvent;
import com.bedrock.mm.oms.model.ExecEventType;
import com.bedrock.mm.oms.model.Order;
import com.bedrock.mm.oms.model.OrderState;
import com.bedrock.mm.oms.store.OrderStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RestReconciliationTest {

    private RestReconciliation reconciliation;
    private OrderStore store;

    @BeforeEach
    void setUp() {
        reconciliation = new RestReconciliation();
        store = new OrderStore(64);
    }

    @Test
    void testNoDiscrepancies() {
        // OMS and REST in sync
        Order order = createOrder(1000L, 5555L, 1, 10000L, 0L, OrderState.OPEN);
        store.addOrder(order);

        List<RestReconciliation.ExchangeOrder> restOrders = new ArrayList<>();
        RestReconciliation.ExchangeOrder restOrder = new RestReconciliation.ExchangeOrder();
        restOrder.exchOrderId = 5555L;
        restOrder.instrumentId = 1;
        restOrder.price = 10000L;
        restOrder.origSize = 100000000L;
        restOrder.filledSize = 0L;
        restOrder.isBid = true;
        restOrder.status = "NEW";
        restOrders.add(restOrder);

        List<ExecEvent> events = reconciliation.reconcile(restOrders, store);
        assertTrue(events.isEmpty(), "No events should be generated when in sync");
    }

    @Test
    void testMissingOrderFilledOnExchange() {
        // Order in OMS but not in REST → assume filled
        Order order = createOrder(1000L, 5555L, 1, 10000L, 0L, OrderState.OPEN);
        store.addOrder(order);

        List<RestReconciliation.ExchangeOrder> restOrders = new ArrayList<>();  // Empty

        List<ExecEvent> events = reconciliation.reconcile(restOrders, store);
        assertEquals(1, events.size());

        ExecEvent event = events.get(0);
        assertEquals(ExecEventType.CANCELLED, event.type);
        assertEquals(1000L, event.internalOrderId);
        assertEquals(5555L, event.exchOrderId);
    }

    @Test
    void testPartialFillDetected() {
        // OMS shows 0 filled, REST shows partial fill
        Order order = createOrder(1000L, 5555L, 1, 10000L, 0L, OrderState.OPEN);
        store.addOrder(order);

        List<RestReconciliation.ExchangeOrder> restOrders = new ArrayList<>();
        RestReconciliation.ExchangeOrder restOrder = new RestReconciliation.ExchangeOrder();
        restOrder.exchOrderId = 5555L;
        restOrder.instrumentId = 1;
        restOrder.price = 10000L;
        restOrder.origSize = 100000000L;
        restOrder.filledSize = 50000000L;  // 50% filled
        restOrder.isBid = true;
        restOrder.status = "PARTIALLY_FILLED";
        restOrders.add(restOrder);

        List<ExecEvent> events = reconciliation.reconcile(restOrders, store);
        assertEquals(1, events.size());

        ExecEvent event = events.get(0);
        assertEquals(ExecEventType.PARTIAL_FILL, event.type);
        assertEquals(1000L, event.internalOrderId);
        assertEquals(50000000L, event.fillSize);
    }

    @Test
    void testFullFillDetected() {
        // OMS shows partial fill, REST shows fully filled
        Order order = createOrder(1000L, 5555L, 1, 10000L, 50000000L, OrderState.PARTIAL_FILL);
        store.addOrder(order);

        List<RestReconciliation.ExchangeOrder> restOrders = new ArrayList<>();
        RestReconciliation.ExchangeOrder restOrder = new RestReconciliation.ExchangeOrder();
        restOrder.exchOrderId = 5555L;
        restOrder.instrumentId = 1;
        restOrder.price = 10000L;
        restOrder.origSize = 100000000L;
        restOrder.filledSize = 100000000L;  // Fully filled
        restOrder.isBid = true;
        restOrder.status = "FILLED";
        restOrders.add(restOrder);

        List<ExecEvent> events = reconciliation.reconcile(restOrders, store);
        assertEquals(1, events.size());

        ExecEvent event = events.get(0);
        assertEquals(ExecEventType.FILL, event.type);
        assertEquals(1000L, event.internalOrderId);
        assertEquals(50000000L, event.fillSize);  // Delta from 50% to 100%
    }

    @Test
    void testMissedAck() {
        // PENDING_NEW order in OMS can be recovered by clientOrderId mapping
        Order pending = createOrder(9999L, 0L, 1, 10000L, 0L, OrderState.PENDING_NEW);
        store.addOrder(pending);

        List<RestReconciliation.ExchangeOrder> restOrders = new ArrayList<>();
        RestReconciliation.ExchangeOrder restOrder = new RestReconciliation.ExchangeOrder();
        restOrder.exchOrderId = 9999L;
        restOrder.clientOrderId = "9999";
        restOrder.instrumentId = 1;
        restOrder.price = 10000L;
        restOrder.origSize = 100000000L;
        restOrder.filledSize = 0L;
        restOrder.isBid = true;
        restOrder.status = "NEW";
        restOrders.add(restOrder);

        List<ExecEvent> events = reconciliation.reconcile(restOrders, store);
        assertEquals(1, events.size());

        ExecEvent event = events.get(0);
        assertEquals(ExecEventType.ACK, event.type);
        assertEquals(9999L, event.exchOrderId);
        assertEquals(9999L, event.internalOrderId);
    }

    @Test
    void testUnmatchedExchangeOrderIsSkipped() {
        List<RestReconciliation.ExchangeOrder> restOrders = new ArrayList<>();
        RestReconciliation.ExchangeOrder restOrder = new RestReconciliation.ExchangeOrder();
        restOrder.exchOrderId = 12345L;
        restOrder.clientOrderId = "external-order";
        restOrder.instrumentId = 1;
        restOrder.price = 10000L;
        restOrder.origSize = 100000000L;
        restOrder.filledSize = 0L;
        restOrder.isBid = true;
        restOrder.status = "NEW";
        restOrders.add(restOrder);

        List<ExecEvent> events = reconciliation.reconcile(restOrders, store);
        assertTrue(events.isEmpty(), "Unmapped exchange orders should be skipped");
        assertEquals(1, reconciliation.countUnmappedOpenOrders(restOrders, store));
    }

    @Test
    void testHighInstrumentIdStillReconciled() {
        Order order = createOrder(1000L, 5555L, 5000, 10000L, 0L, OrderState.OPEN);
        store.addOrder(order);

        List<ExecEvent> events = reconciliation.reconcile(new ArrayList<>(), store);
        assertEquals(1, events.size());
        assertEquals(ExecEventType.CANCELLED, events.get(0).type);
        assertEquals(5000, events.get(0).instrumentId);
    }

    @Test
    void testPendingNewOrderIgnored() {
        // Order in PENDING_NEW state with no exchOrderId
        Order order = createOrder(1000L, 0L, 1, 10000L, 0L, OrderState.PENDING_NEW);
        store.addOrder(order);

        List<RestReconciliation.ExchangeOrder> restOrders = new ArrayList<>();

        List<ExecEvent> events = reconciliation.reconcile(restOrders, store);
        assertTrue(events.isEmpty(), "PENDING_NEW orders should be skipped");
    }

    @Test
    void testBootstrapOpenOrdersReconstructsMissingOrders() {
        List<RestReconciliation.ExchangeOrder> restOrders = new ArrayList<>();
        RestReconciliation.ExchangeOrder restOrder = new RestReconciliation.ExchangeOrder();
        restOrder.exchOrderId = 8888L;
        restOrder.clientOrderId = "2001";
        restOrder.instrumentId = 2;
        restOrder.price = 500_120_000_0000L;
        restOrder.origSize = 10_000_000L;
        restOrder.filledSize = 2_000_000L;
        restOrder.isBid = false;
        restOrder.status = "PARTIALLY_FILLED";
        restOrders.add(restOrder);

        int reconstructed = reconciliation.bootstrapOpenOrders(restOrders, store);
        assertEquals(1, reconstructed);

        Order recovered = store.getOrder(2001L);
        assertNotNull(recovered);
        assertEquals(8888L, recovered.exchOrderId);
        assertEquals(2, recovered.instrumentId);
        assertEquals(500_120_000_0000L, recovered.price);
        assertEquals(10_000_000L, recovered.origSize);
        assertEquals(2_000_000L, recovered.filledSize);
        assertEquals(OrderState.PARTIAL_FILL, recovered.state);
        assertEquals(-1, recovered.regionIndex);
        assertFalse(recovered.isBid);
    }

    @Test
    void testBootstrapSkipsOrderWithoutNumericClientOrderId() {
        List<RestReconciliation.ExchangeOrder> restOrders = new ArrayList<>();
        RestReconciliation.ExchangeOrder restOrder = new RestReconciliation.ExchangeOrder();
        restOrder.exchOrderId = 7777L;
        restOrder.clientOrderId = "external-order";
        restOrder.instrumentId = 1;
        restOrder.price = 10000L;
        restOrder.origSize = 100000000L;
        restOrder.filledSize = 0L;
        restOrder.isBid = true;
        restOrder.status = "NEW";
        restOrders.add(restOrder);

        int reconstructed = reconciliation.bootstrapOpenOrders(restOrders, store);
        assertEquals(0, reconstructed);
        assertEquals(0, store.size());
    }

    @Test
    void testBootstrapThenReconcileProducesNoSyntheticEventsForRecoveredOrder() {
        List<RestReconciliation.ExchangeOrder> restOrders = new ArrayList<>();
        RestReconciliation.ExchangeOrder restOrder = new RestReconciliation.ExchangeOrder();
        restOrder.exchOrderId = 9999L;
        restOrder.clientOrderId = "9999";
        restOrder.instrumentId = 1;
        restOrder.price = 10000L;
        restOrder.origSize = 100000000L;
        restOrder.filledSize = 0L;
        restOrder.isBid = true;
        restOrder.status = "NEW";
        restOrders.add(restOrder);

        int reconstructed = reconciliation.bootstrapOpenOrders(restOrders, store);
        assertEquals(1, reconstructed);

        List<ExecEvent> events = reconciliation.reconcile(restOrders, store);
        assertTrue(events.isEmpty());
        assertEquals(0, reconciliation.countUnmappedOpenOrders(restOrders, store));
    }

    private Order createOrder(long orderId, long exchOrderId, int instrumentId, long price, long filledSize, OrderState state) {
        Order order = new Order();
        order.orderId = orderId;
        order.exchOrderId = exchOrderId;
        order.instrumentId = instrumentId;
        order.price = price;
        order.origSize = 100000000L;  // 1.0 in fixed-point
        order.filledSize = filledSize;
        order.fillValueSum = filledSize > 0 ? filledSize * price : 0;
        order.state = state;
        order.regionIndex = 0;
        order.isBid = true;
        order.createNanos = System.nanoTime();
        return order;
    }
}
