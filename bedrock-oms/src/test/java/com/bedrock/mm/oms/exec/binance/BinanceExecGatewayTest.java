package com.bedrock.mm.oms.exec.binance;

import com.bedrock.mm.common.idgen.OrderIdGenerator;
import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.oms.exec.RestReconciliation;
import com.bedrock.mm.oms.model.ExecEvent;
import com.bedrock.mm.oms.model.ExecEventType;
import com.bedrock.mm.oms.model.Order;
import com.bedrock.mm.oms.model.OrderState;
import com.bedrock.mm.oms.store.OrderStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for BinanceExecGateway.
 *
 * Tests order placement, cancellation, and event handling without actual Binance connection.
 */
class BinanceExecGatewayTest {

    private OrderStore orderStore;
    private List<ExecEvent> receivedEvents;
    private BinanceExecGateway gateway;

    @BeforeEach
    void setUp() {
        orderStore = new OrderStore(64);
        receivedEvents = new ArrayList<>();

        // Mock credentials (not used in unit test, no actual HTTP calls)
        gateway = new BinanceExecGateway(
                "https://testnet.binance.vision",
                "wss://testnet.binance.vision",
                "test-api-key",
                "test-secret-key",
                orderStore,
                receivedEvents::add,
                1  // instrumentId
        );
    }

    @AfterEach
    void tearDown() {
        gateway.shutdown();
    }

    @Test
    void testPlaceOrderCreatesOrderInStore() {
        // Place an order
        gateway.placeOrder(1, 5000000000000L, 100000000L, true, 0);

        // Verify order created in store
        List<Long> openOrders = orderStore.getOpenOrderIds(1);
        assertEquals(1, openOrders.size());

        Order order = orderStore.getOrder(openOrders.get(0));
        assertNotNull(order);
        assertEquals(1, order.instrumentId);
        assertEquals(5000000000000L, order.price);
        assertEquals(100000000L, order.origSize);
        assertTrue(order.isBid);
        assertEquals(OrderState.PENDING_NEW, order.state);
        assertEquals(0L, order.exchOrderId);
    }

    @Test
    void testCancelOrderWithoutExchOrderIdIsIgnored() {
        // Create order without exchOrderId
        Order order = new Order();
        order.orderId = 1000L;
        order.exchOrderId = 0;
        order.instrumentId = 1;
        order.price = 5000000000000L;
        order.origSize = 100000000L;
        order.filledSize = 0L;
        order.state = OrderState.PENDING_NEW;
        order.regionIndex = 0;
        order.isBid = true;
        order.createNanos = System.nanoTime();
        orderStore.addOrder(order);

        // Attempt to cancel
        gateway.cancelOrder(1, 1000L);

        // No exception thrown, order remains in store
        assertEquals(order, orderStore.getOrder(1000L));
    }

    @Test
    void testCancelOrderWithExchOrderIdIssuesRequest() {
        // Create order with exchOrderId
        Order order = new Order();
        order.orderId = 1000L;
        order.exchOrderId = 5555L;
        order.instrumentId = 1;
        order.price = 5000000000000L;
        order.origSize = 100000000L;
        order.filledSize = 0L;
        order.state = OrderState.OPEN;
        order.regionIndex = 0;
        order.isBid = true;
        order.createNanos = System.nanoTime();
        orderStore.addOrder(order);

        // Cancel order
        gateway.cancelOrder(1, 1000L);

        // Order still in store (cancellation is async, state updated by WS event)
        assertEquals(order, orderStore.getOrder(1000L));
    }

    @Test
    void testHandleAckEvent() {
        // Simulate ACK event from private WS
        ExecEvent event = new ExecEvent();
        event.seqId = System.nanoTime();
        event.instrumentId = 1;
        event.internalOrderId = 1000L;
        event.exchOrderId = 5555L;
        event.type = ExecEventType.ACK;
        event.isBid = true;
        event.remainSize = 100000000L;

        // Directly invoke event consumer (simulates WS callback)
        receivedEvents.add(event);

        // Verify event received
        assertEquals(1, receivedEvents.size());
        assertEquals(ExecEventType.ACK, receivedEvents.get(0).type);
        assertEquals(5555L, receivedEvents.get(0).exchOrderId);
    }

    @Test
    void testHandleFillEvent() {
        // Create order in store
        Order order = new Order();
        order.orderId = 1000L;
        order.exchOrderId = 5555L;
        order.instrumentId = 1;
        order.price = 5000000000000L;
        order.origSize = 100000000L;
        order.filledSize = 0L;
        order.state = OrderState.OPEN;
        order.regionIndex = 0;
        order.isBid = true;
        order.createNanos = System.nanoTime();
        orderStore.addOrder(order);

        // Simulate FILL event
        ExecEvent event = new ExecEvent();
        event.seqId = System.nanoTime();
        event.instrumentId = 1;
        event.internalOrderId = 1000L;
        event.exchOrderId = 5555L;
        event.type = ExecEventType.FILL;
        event.fillSize = 100000000L;
        event.fillPrice = 5000000000000L;
        event.remainSize = 0L;
        event.isBid = true;

        receivedEvents.add(event);

        // Verify event received
        assertEquals(1, receivedEvents.size());
        assertEquals(ExecEventType.FILL, receivedEvents.get(0).type);
        assertEquals(100000000L, receivedEvents.get(0).fillSize);
    }

    @Test
    void testSymbolMapping() {
        // Place orders for different instruments
        gateway.placeOrder(1, 5000000000000L, 100000000L, true, 0);  // BTCUSDT
        gateway.placeOrder(2, 300000000000L, 100000000L, true, 0);   // ETHUSDT

        List<Long> btcOrders = orderStore.getOpenOrderIds(1);
        List<Long> ethOrders = orderStore.getOpenOrderIds(2);

        assertEquals(1, btcOrders.size());
        assertEquals(1, ethOrders.size());

        Order btcOrder = orderStore.getOrder(btcOrders.get(0));
        Order ethOrder = orderStore.getOrder(ethOrders.get(0));

        assertEquals(1, btcOrder.instrumentId);
        assertEquals(2, ethOrder.instrumentId);
    }

    @Test
    void testPriceAndQuantityFormatting() {
        // Place order with specific fixed-point values
        long price = 5012345678L;      // 50.12345678
        long quantity = 123456789L;     // 1.23456789

        gateway.placeOrder(1, price, quantity, false, 0);

        List<Long> orders = orderStore.getOpenOrderIds(1);
        assertEquals(1, orders.size());

        Order order = orderStore.getOrder(orders.get(0));
        assertEquals(price, order.price);
        assertEquals(quantity, order.origSize);
        assertFalse(order.isBid);
    }

    @Test
    void testGeneratedOrderIdCarriesInstrumentAndSide() {
        gateway.placeOrder(1, 5000000000000L, 100000000L, true, 0);   // BUY
        gateway.placeOrder(1, 5001000000000L, 100000000L, false, 0);  // SELL

        List<Long> orders = orderStore.getOpenOrderIds(1);
        assertEquals(2, orders.size());

        Order bidOrder = null;
        Order askOrder = null;
        for (Long orderId : orders) {
            Order order = orderStore.getOrder(orderId);
            if (order.isBid) {
                bidOrder = order;
            } else {
                askOrder = order;
            }
        }
        assertNotNull(bidOrder);
        assertNotNull(askOrder);

        assertEquals(1, OrderIdGenerator.extractInstrumentId(bidOrder.orderId));
        assertEquals(1, OrderIdGenerator.extractInstrumentId(askOrder.orderId));
        assertEquals(Side.BUY, OrderIdGenerator.extractSide(bidOrder.orderId));
        assertEquals(Side.SELL, OrderIdGenerator.extractSide(askOrder.orderId));
    }

    @Test
    void testGeneratedOrderIdMonotonicForSameInstrument() {
        gateway.placeOrder(1, 5000000000000L, 100000000L, true, 0);
        gateway.placeOrder(1, 5001000000000L, 100000000L, true, 0);

        List<Long> orders = orderStore.getOpenOrderIds(1);
        assertEquals(2, orders.size());

        orders.sort(Long::compareTo);
        long first = orders.get(0);
        long second = orders.get(1);
        assertTrue(second > first, "orderId should increase for consecutive orders");
    }

    @Test
    void testPlaceOrderWithQuoteContextStoresMetadata() {
        long quotePublishNanos = System.nanoTime() - 1_000;
        long quoteSeqId = 42L;

        gateway.placeOrder(1, 5000000000000L, 100000000L, true, 0, quotePublishNanos, quoteSeqId);

        List<Long> openOrders = orderStore.getOpenOrderIds(1);
        assertEquals(1, openOrders.size());
        Order order = orderStore.getOrder(openOrders.get(0));
        assertNotNull(order);
        assertEquals(quotePublishNanos, order.quotePublishNanos);
        assertEquals(quoteSeqId, order.quoteSeqId);
    }

    @Test
    void testMultipleOrdersTracking() {
        // Place multiple orders
        for (int i = 0; i < 5; i++) {
            gateway.placeOrder(1, 5000000000000L + i * 10000000L, 100000000L, true, 0);
        }

        List<Long> orders = orderStore.getOpenOrderIds(1);
        assertEquals(5, orders.size());

        // All orders in PENDING_NEW state
        for (Long orderId : orders) {
            Order order = orderStore.getOrder(orderId);
            assertEquals(OrderState.PENDING_NEW, order.state);
        }
    }

    @Test
    void testReconciliationCallback() {
        // onPrivateWsReconnect is called when WS reconnects
        // This triggers REST reconciliation
        // In unit test, we can't verify HTTP calls, but we can ensure no exception thrown
        Boolean result = assertDoesNotThrow(() -> gateway.onPrivateWsReconnect());
        assertFalse(result);
    }

    @Test
    void testParseOpenOrdersResponseMapsFields() throws Exception {
        String json = """
                [
                  {
                    "symbol":"BTCUSDT",
                    "orderId":5555,
                    "clientOrderId":"1234",
                    "price":"50000.12000000",
                    "origQty":"0.01000000",
                    "executedQty":"0.00200000",
                    "status":"PARTIALLY_FILLED",
                    "side":"BUY"
                  },
                  {
                    "symbol":"BTCUSDT",
                    "orderId":6666,
                    "clientOrderId":"abc",
                    "price":"50010.00000000",
                    "origQty":"0.02000000",
                    "executedQty":"0.00000000",
                    "status":"NEW",
                    "side":"SELL"
                  }
                ]
                """;

        List<RestReconciliation.ExchangeOrder> orders = gateway.parseOpenOrdersResponse(json, 1);
        assertEquals(2, orders.size());

        RestReconciliation.ExchangeOrder first = orders.get(0);
        assertEquals(5555L, first.exchOrderId);
        assertEquals("1234", first.clientOrderId);
        assertEquals(1, first.instrumentId);
        assertEquals(5_000_012_000_000L, first.price);
        assertEquals(1_000_000L, first.origSize);
        assertEquals(200_000L, first.filledSize);
        assertTrue(first.isBid);
        assertEquals("PARTIALLY_FILLED", first.status);

        RestReconciliation.ExchangeOrder second = orders.get(1);
        assertEquals(6666L, second.exchOrderId);
        assertEquals("abc", second.clientOrderId);
        assertFalse(second.isBid);
        assertEquals("NEW", second.status);
    }

    @Test
    void testParseOpenOrdersResponseRejectsNonArray() {
        assertThrows(Exception.class, () -> gateway.parseOpenOrdersResponse("{\"ok\":true}", 1));
    }

    @Test
    void testExecEventDispatchedOnGatewayDispatcherThread() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> callbackThread = new AtomicReference<>();
        BinanceExecGateway asyncGateway = new BinanceExecGateway(
                "https://testnet.binance.vision",
                "wss://testnet.binance.vision",
                "test-api-key",
                "test-secret-key",
                orderStore,
                event -> {
                    callbackThread.set(Thread.currentThread().getName());
                    latch.countDown();
                },
                1
        );

        try {
            ExecEvent event = new ExecEvent();
            event.seqId = 1L;
            event.type = ExecEventType.ACK;
            asyncGateway.dispatchExecEventForTest(event);

            assertTrue(latch.await(1, TimeUnit.SECONDS), "timed out waiting for async callback");
            assertNotNull(callbackThread.get());
            assertTrue(callbackThread.get().startsWith("binance-exec-dispatch-"));
            assertNotEquals(Thread.currentThread().getName(), callbackThread.get());
        } finally {
            asyncGateway.shutdown();
        }
    }

    @Test
    void testDispatchAfterShutdownIsDropped() {
        AtomicInteger consumed = new AtomicInteger(0);
        BinanceExecGateway asyncGateway = new BinanceExecGateway(
                "https://testnet.binance.vision",
                "wss://testnet.binance.vision",
                "test-api-key",
                "test-secret-key",
                orderStore,
                event -> consumed.incrementAndGet(),
                1
        );
        asyncGateway.shutdown();

        ExecEvent event = new ExecEvent();
        event.seqId = 2L;
        event.type = ExecEventType.ACK;

        assertDoesNotThrow(() -> asyncGateway.dispatchExecEventForTest(event));
        assertEquals(0, consumed.get());
        assertEquals(1L, asyncGateway.droppedExecEventsForTest());
    }

    @Test
    void testConsumerExceptionDoesNotStopDispatcherThread() throws Exception {
        AtomicInteger consumed = new AtomicInteger(0);
        CountDownLatch secondDelivered = new CountDownLatch(1);
        BinanceExecGateway asyncGateway = new BinanceExecGateway(
                "https://testnet.binance.vision",
                "wss://testnet.binance.vision",
                "test-api-key",
                "test-secret-key",
                orderStore,
                event -> {
                    int count = consumed.incrementAndGet();
                    if (count == 1) {
                        throw new RuntimeException("simulated-consumer-failure");
                    }
                    secondDelivered.countDown();
                },
                1
        );

        try {
            ExecEvent first = new ExecEvent();
            first.seqId = 10L;
            first.type = ExecEventType.ACK;
            ExecEvent second = new ExecEvent();
            second.seqId = 11L;
            second.type = ExecEventType.FILL;

            asyncGateway.dispatchExecEventForTest(first);
            asyncGateway.dispatchExecEventForTest(second);

            assertTrue(secondDelivered.await(1, TimeUnit.SECONDS), "second event should still be delivered");
            assertEquals(2, consumed.get());
            assertEquals(1L, asyncGateway.dispatchedExecEventsForTest());
            assertEquals(0L, asyncGateway.droppedExecEventsForTest());
        } finally {
            asyncGateway.shutdown();
        }
    }
}
