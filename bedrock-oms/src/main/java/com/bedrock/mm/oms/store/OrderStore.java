package com.bedrock.mm.oms.store;

import com.bedrock.mm.oms.model.Order;
import com.bedrock.mm.oms.model.OrderState;
import org.agrona.collections.Long2LongHashMap;
import org.agrona.collections.Long2ObjectHashMap;

import java.util.ArrayList;
import java.util.List;

/**
 * OrderStore - High-performance order storage with dual indexing.
 *
 * Uses Agrona collections for zero-allocation hot path:
 * - Long2ObjectHashMap for internal order ID -> Order mapping
 * - Long2LongHashMap for exchange order ID -> internal order ID reverse index
 *
 * Thread safety: Single-threaded only. No concurrent access allowed.
 */
public class OrderStore {
    private final Long2ObjectHashMap<Order> orders;
    private final Long2LongHashMap exchToInternal;

    /**
     * Create order store with specified initial capacity.
     *
     * @param initialCapacity initial capacity for internal maps
     */
    public OrderStore(int initialCapacity) {
        this.orders = new Long2ObjectHashMap<>(initialCapacity, 0.7f);
        this.exchToInternal = new Long2LongHashMap(initialCapacity, 0.7f, -1L);
    }

    /**
     * Add a new order to the store.
     *
     * @param order order to add
     */
    public void addOrder(Order order) {
        orders.put(order.orderId, order);
        if (order.exchOrderId != 0) {
            exchToInternal.put(order.exchOrderId, order.orderId);
        }
    }

    /**
     * Get order by internal order ID.
     *
     * @param orderId internal order ID
     * @return order or null if not found
     */
    public Order getOrder(long orderId) {
        return orders.get(orderId);
    }

    /**
     * Get order by exchange order ID.
     *
     * @param exchOrderId exchange order ID
     * @return order or null if not found
     */
    public Order getByExchOrderId(long exchOrderId) {
        long orderId = exchToInternal.get(exchOrderId);
        if (orderId == -1L) {
            return null;
        }
        return orders.get(orderId);
    }

    /**
     * Update exchange order ID for an existing order (called when ACK received).
     *
     * @param orderId internal order ID
     * @param exchOrderId exchange order ID
     */
    public void updateExchOrderId(long orderId, long exchOrderId) {
        Order order = orders.get(orderId);
        if (order != null) {
            order.exchOrderId = exchOrderId;
            exchToInternal.put(exchOrderId, orderId);
        }
    }

    /**
     * Remove order from store (called after FILLED/CANCELLED).
     *
     * @param orderId internal order ID
     */
    public void removeOrder(long orderId) {
        Order order = orders.remove(orderId);
        if (order != null && order.exchOrderId != 0) {
            exchToInternal.remove(order.exchOrderId);
        }
    }

    /**
     * Get all open order IDs for a specific instrument.
     *
     * @param instrumentId instrument ID
     * @return list of order IDs in any active (non-terminal) state: PENDING_NEW, OPEN, PARTIAL_FILL, PENDING_CANCEL
     */
    public List<Long> getOpenOrderIds(int instrumentId) {
        List<Long> result = new ArrayList<>();
        orders.forEach((orderId, order) -> {
            if (order.instrumentId == instrumentId && isActiveState(order.state)) {
                result.add(orderId);
            }
        });
        return result;
    }

    /**
     * Get count of active orders for a specific instrument.
     *
     * @param instrumentId instrument ID
     * @return count of orders in any active (non-terminal) state
     */
    public int getOpenOrderCount(int instrumentId) {
        int count = 0;
        for (Order order : orders.values()) {
            if (order.instrumentId == instrumentId && isActiveState(order.state)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Snapshot all active orders across instruments.
     * <p>
     * Intended for cold-path reconciliation flows where scanning all active orders
     * is required.
     */
    public List<Order> getActiveOrdersSnapshot() {
        List<Order> result = new ArrayList<>(orders.size());
        for (Order order : orders.values()) {
            if (isActiveState(order.state)) {
                result.add(order);
            }
        }
        return result;
    }

    /** Returns true for non-terminal states that represent live orders on the exchange. */
    private static boolean isActiveState(OrderState state) {
        return state == OrderState.PENDING_NEW
            || state == OrderState.OPEN
            || state == OrderState.PARTIAL_FILL
            || state == OrderState.PENDING_CANCEL;
    }

    /**
     * Get total number of orders in store.
     *
     * @return total order count
     */
    public int size() {
        return orders.size();
    }

    /**
     * Clear all orders (useful for testing).
     */
    public void clear() {
        orders.clear();
        exchToInternal.clear();
    }
}
