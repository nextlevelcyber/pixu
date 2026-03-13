package com.bedrock.mm.oms.store;

import com.bedrock.mm.oms.model.Order;
import com.bedrock.mm.oms.model.OrderState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OrderStoreTest {

    private OrderStore store;

    @BeforeEach
    void setUp() {
        store = new OrderStore(16);
    }

    @Test
    void testAddAndGetOrder() {
        Order order = createOrder(1000L, 0L, 1, 10000L);
        store.addOrder(order);

        Order retrieved = store.getOrder(1000L);
        assertNotNull(retrieved);
        assertEquals(1000L, retrieved.orderId);
        assertEquals(1, retrieved.instrumentId);
    }

    @Test
    void testGetNonExistentOrder() {
        Order retrieved = store.getOrder(9999L);
        assertNull(retrieved);
    }

    @Test
    void testUpdateExchOrderId() {
        Order order = createOrder(1000L, 0L, 1, 10000L);
        store.addOrder(order);

        store.updateExchOrderId(1000L, 55555L);

        Order retrieved = store.getOrder(1000L);
        assertEquals(55555L, retrieved.exchOrderId);

        Order byExchId = store.getByExchOrderId(55555L);
        assertNotNull(byExchId);
        assertEquals(1000L, byExchId.orderId);
    }

    @Test
    void testGetByExchOrderId() {
        Order order = createOrder(1000L, 55555L, 1, 10000L);
        store.addOrder(order);

        Order retrieved = store.getByExchOrderId(55555L);
        assertNotNull(retrieved);
        assertEquals(1000L, retrieved.orderId);
    }

    @Test
    void testGetByExchOrderIdNotFound() {
        Order retrieved = store.getByExchOrderId(99999L);
        assertNull(retrieved);
    }

    @Test
    void testRemoveOrder() {
        Order order = createOrder(1000L, 55555L, 1, 10000L);
        store.addOrder(order);

        assertEquals(1, store.size());

        store.removeOrder(1000L);

        assertEquals(0, store.size());
        assertNull(store.getOrder(1000L));
        assertNull(store.getByExchOrderId(55555L));
    }

    @Test
    void testGetOpenOrderIds() {
        Order order1 = createOrder(1000L, 0L, 1, 10000L);
        order1.state = OrderState.OPEN;
        store.addOrder(order1);

        Order order2 = createOrder(2000L, 0L, 1, 20000L);
        order2.state = OrderState.PARTIAL_FILL;
        store.addOrder(order2);

        Order order3 = createOrder(3000L, 0L, 1, 30000L);
        order3.state = OrderState.FILLED;
        store.addOrder(order3);

        Order order4 = createOrder(4000L, 0L, 2, 40000L);
        order4.state = OrderState.OPEN;
        store.addOrder(order4);

        List<Long> openOrders = store.getOpenOrderIds(1);
        assertEquals(2, openOrders.size());
        assertTrue(openOrders.contains(1000L));
        assertTrue(openOrders.contains(2000L));
    }

    @Test
    void testGetOpenOrderCount() {
        Order order1 = createOrder(1000L, 0L, 1, 10000L);
        order1.state = OrderState.OPEN;
        store.addOrder(order1);

        Order order2 = createOrder(2000L, 0L, 1, 20000L);
        order2.state = OrderState.PARTIAL_FILL;
        store.addOrder(order2);

        Order order3 = createOrder(3000L, 0L, 1, 30000L);
        order3.state = OrderState.FILLED;
        store.addOrder(order3);

        int count = store.getOpenOrderCount(1);
        assertEquals(2, count);
    }

    @Test
    void testGetActiveOrdersSnapshotAcrossInstruments() {
        Order order1 = createOrder(1000L, 0L, 1, 10000L);
        order1.state = OrderState.OPEN;
        store.addOrder(order1);

        Order order2 = createOrder(2000L, 0L, 2, 20000L);
        order2.state = OrderState.PENDING_CANCEL;
        store.addOrder(order2);

        Order order3 = createOrder(3000L, 0L, 3, 30000L);
        order3.state = OrderState.CANCELLED;
        store.addOrder(order3);

        List<Order> active = store.getActiveOrdersSnapshot();
        assertEquals(2, active.size());
        assertTrue(active.stream().anyMatch(o -> o.orderId == 1000L));
        assertTrue(active.stream().anyMatch(o -> o.orderId == 2000L));
    }

    @Test
    void testClear() {
        Order order1 = createOrder(1000L, 0L, 1, 10000L);
        Order order2 = createOrder(2000L, 0L, 1, 20000L);
        store.addOrder(order1);
        store.addOrder(order2);

        assertEquals(2, store.size());

        store.clear();

        assertEquals(0, store.size());
        assertNull(store.getOrder(1000L));
        assertNull(store.getOrder(2000L));
    }

    @Test
    void testSize() {
        assertEquals(0, store.size());

        store.addOrder(createOrder(1000L, 0L, 1, 10000L));
        assertEquals(1, store.size());

        store.addOrder(createOrder(2000L, 0L, 1, 20000L));
        assertEquals(2, store.size());

        store.removeOrder(1000L);
        assertEquals(1, store.size());
    }

    private Order createOrder(long orderId, long exchOrderId, int instrumentId, long price) {
        Order order = new Order();
        order.orderId = orderId;
        order.exchOrderId = exchOrderId;
        order.instrumentId = instrumentId;
        order.price = price;
        order.origSize = 100000000L;  // 1.0 in fixed-point
        order.filledSize = 0L;
        order.fillValueSum = 0L;
        order.state = OrderState.PENDING_NEW;
        order.regionIndex = 0;
        order.isBid = true;
        order.createNanos = System.nanoTime();
        return order;
    }
}
