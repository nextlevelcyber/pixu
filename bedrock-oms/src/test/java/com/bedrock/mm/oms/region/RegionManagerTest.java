package com.bedrock.mm.oms.region;

import com.bedrock.mm.oms.model.Order;
import com.bedrock.mm.oms.model.OrderState;
import com.bedrock.mm.oms.store.OrderStore;
import com.bedrock.mm.oms.store.PriceRegion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RegionManager.
 *
 * Validates:
 * - OrderAction generation for new orders
 * - OrderAction generation for cancels (stale orders)
 * - Boundary updates
 * - Single-sided quoting
 */
class RegionManagerTest {
    private static final int INSTRUMENT_ID = 1;
    private static final long SCALE = 100_000_000L; // 1e8
    private static final double TICK_SIZE = 0.01;

    private RegionManager manager;
    private OrderStore store;

    @BeforeEach
    void setUp() {
        manager = new RegionManager();
        store = new OrderStore(64);
    }

    @Test
    void testDiffGeneratesNewOrderWhenRegionBelowMinCount() {
        // Given: Empty order store, region with minCount=1
        PriceRegion[] bidRegions = new PriceRegion[1];
        bidRegions[0] = new PriceRegion(0, true, -0.01, -0.005);

        PriceRegion[] askRegions = new PriceRegion[0];

        long fairMid = toFixed(50000.0);
        long bidPrice = toFixed(49750.0);
        long bidSize = toFixed(1.0);

        // When: Diff with empty store
        List<OrderAction> actions = manager.diff(
            INSTRUMENT_ID, fairMid, bidPrice, 0L, bidSize, 0L,
            0, bidRegions, askRegions, store, TICK_SIZE
        );

        // Then: Should generate NewOrder for bid
        assertEquals(1, actions.size());
        assertTrue(actions.get(0) instanceof OrderAction.NewOrder);

        OrderAction.NewOrder newOrder = (OrderAction.NewOrder) actions.get(0);
        assertEquals(INSTRUMENT_ID, newOrder.instrumentId());
        assertEquals(bidPrice, newOrder.price());
        assertEquals(bidSize, newOrder.size());
        assertTrue(newOrder.isBid());
        assertEquals(0, newOrder.regionIndex());
    }

    @Test
    void testDiffGeneratesNoActionsWhenRegionAtMinCount() {
        // Given: Order store with 1 order in region (meets minCount)
        PriceRegion[] bidRegions = new PriceRegion[1];
        bidRegions[0] = new PriceRegion(0, true, -0.01, -0.005);

        // Add order to region
        Order order = createOrder(1L, toFixed(49750.0), toFixed(1.0), true);
        store.addOrder(order);
        bidRegions[0].addOrder(order.orderId);

        PriceRegion[] askRegions = new PriceRegion[0];

        long fairMid = toFixed(50000.0);

        // When: Diff with order already in region
        List<OrderAction> actions = manager.diff(
            INSTRUMENT_ID, fairMid, toFixed(49750.0), 0L, toFixed(1.0), 0L,
            0, bidRegions, askRegions, store, TICK_SIZE
        );

        // Then: No actions needed
        assertEquals(0, actions.size());
    }

    @Test
    void testDiffGeneratesCancelForStaleOrder() {
        // Given: Order with price outside updated region boundaries
        PriceRegion[] bidRegions = new PriceRegion[1];
        bidRegions[0] = new PriceRegion(0, true, -0.01, -0.005);

        // Add order at price that will be stale after fairMid changes
        Order order = createOrder(1L, toFixed(49000.0), toFixed(1.0), true);
        store.addOrder(order);
        bidRegions[0].addOrder(order.orderId);

        PriceRegion[] askRegions = new PriceRegion[0];

        // FairMid changes, making old order stale
        long newFairMid = toFixed(51000.0); // New boundaries: [50490, 50745]

        // When: Diff detects stale order
        List<OrderAction> actions = manager.diff(
            INSTRUMENT_ID, newFairMid, toFixed(50490.0), 0L, toFixed(1.0), 0L,
            0, bidRegions, askRegions, store, TICK_SIZE
        );

        // Then: Should generate CancelOrder for stale order + NewOrder
        assertTrue(actions.size() >= 1);
        boolean foundCancel = false;
        for (OrderAction action : actions) {
            if (action instanceof OrderAction.CancelOrder cancel) {
                if (cancel.orderId() == 1L) {
                    foundCancel = true;
                }
            }
        }
        assertTrue(foundCancel, "Should cancel stale order");
    }

    @Test
    void testDiffBothSides() {
        // Given: Empty store, regions for both bid and ask
        PriceRegion[] bidRegions = new PriceRegion[1];
        bidRegions[0] = new PriceRegion(0, true, -0.01, -0.005);

        PriceRegion[] askRegions = new PriceRegion[1];
        askRegions[0] = new PriceRegion(0, false, 0.005, 0.01);

        long fairMid = toFixed(50000.0);
        long bidPrice = toFixed(49750.0);
        long askPrice = toFixed(50250.0);
        long bidSize = toFixed(1.0);
        long askSize = toFixed(0.5);

        // When: Diff for both sides
        List<OrderAction> actions = manager.diff(
            INSTRUMENT_ID, fairMid, bidPrice, askPrice, bidSize, askSize,
            0, bidRegions, askRegions, store, TICK_SIZE
        );

        // Then: Should generate NewOrder for both bid and ask
        assertEquals(2, actions.size());
        
        int bidOrders = 0;
        int askOrders = 0;
        for (OrderAction action : actions) {
            if (action instanceof OrderAction.NewOrder newOrder) {
                if (newOrder.isBid()) bidOrders++;
                else askOrders++;
            }
        }

        assertEquals(1, bidOrders);
        assertEquals(1, askOrders);
    }

    @Test
    void testDiffSingleSide() {
        // Given: Single bid region
        PriceRegion[] bidRegions = new PriceRegion[1];
        bidRegions[0] = new PriceRegion(0, true, -0.01, -0.005);

        long fairMid = toFixed(50000.0);
        long bidPrice = toFixed(49750.0);
        long bidSize = toFixed(1.0);

        // When: Diff single side
        List<OrderAction> actions = manager.diffSingleSide(
            INSTRUMENT_ID, fairMid, bidPrice, bidSize, true,
            0, bidRegions, store, TICK_SIZE
        );

        // Then: Should generate NewOrder for bid only
        assertEquals(1, actions.size());
        assertTrue(actions.get(0) instanceof OrderAction.NewOrder);

        OrderAction.NewOrder newOrder = (OrderAction.NewOrder) actions.get(0);
        assertTrue(newOrder.isBid());
    }

    @Test
    void testDiffMultipleRegions() {
        // Given: Multiple bid regions
        PriceRegion[] bidRegions = new PriceRegion[3];
        bidRegions[0] = new PriceRegion(0, true, -0.02, -0.015);
        bidRegions[1] = new PriceRegion(1, true, -0.015, -0.01);
        bidRegions[2] = new PriceRegion(2, true, -0.01, -0.005);

        PriceRegion[] askRegions = new PriceRegion[0];

        long fairMid = toFixed(50000.0);
        long bidPrice = toFixed(49750.0);
        long bidSize = toFixed(1.0);

        // When: Diff targeting region 2
        List<OrderAction> actions = manager.diff(
            INSTRUMENT_ID, fairMid, bidPrice, 0L, bidSize, 0L,
            2, bidRegions, askRegions, store, TICK_SIZE
        );

        // Then: Should generate NewOrder for region 2
        assertEquals(1, actions.size());

        OrderAction.NewOrder newOrder = (OrderAction.NewOrder) actions.get(0);
        assertEquals(2, newOrder.regionIndex());
    }

    // Helper methods

    private Order createOrder(long orderId, long price, long size, boolean isBid) {
        Order order = new Order();
        order.orderId = orderId;
        order.exchOrderId = 0;
        order.instrumentId = INSTRUMENT_ID;
        order.price = price;
        order.origSize = size;
        order.filledSize = 0;
        order.fillValueSum = 0;
        order.state = OrderState.OPEN;
        order.regionIndex = 0;
        order.isBid = isBid;
        order.createNanos = System.nanoTime();
        return order;
    }

    private long toFixed(double value) {
        return (long) (value * SCALE);
    }
}
