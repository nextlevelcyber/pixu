package com.bedrock.mm.oms.statemachine;

import com.bedrock.mm.oms.model.ExecEventType;
import com.bedrock.mm.oms.model.OrderState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OrderStateMachineTest {

    @Test
    void testPendingNewToOpen() {
        OrderState result = OrderStateMachine.transition(OrderState.PENDING_NEW, ExecEventType.ACK);
        assertEquals(OrderState.OPEN, result);
    }

    @Test
    void testPendingNewToRejected() {
        OrderState result = OrderStateMachine.transition(OrderState.PENDING_NEW, ExecEventType.REJECTED);
        assertEquals(OrderState.REJECTED, result);
    }

    @Test
    void testPendingNewToFilledImmediately() {
        OrderState result = OrderStateMachine.transition(OrderState.PENDING_NEW, ExecEventType.FILL);
        assertEquals(OrderState.FILLED, result);
    }

    @Test
    void testOpenToPartialFill() {
        OrderState result = OrderStateMachine.transition(OrderState.OPEN, ExecEventType.PARTIAL_FILL);
        assertEquals(OrderState.PARTIAL_FILL, result);
    }

    @Test
    void testOpenToFilled() {
        OrderState result = OrderStateMachine.transition(OrderState.OPEN, ExecEventType.FILL);
        assertEquals(OrderState.FILLED, result);
    }

    @Test
    void testOpenToCancelled() {
        OrderState result = OrderStateMachine.transition(OrderState.OPEN, ExecEventType.CANCELLED);
        assertEquals(OrderState.CANCELLED, result);
    }

    @Test
    void testPartialFillToFilled() {
        OrderState result = OrderStateMachine.transition(OrderState.PARTIAL_FILL, ExecEventType.FILL);
        assertEquals(OrderState.FILLED, result);
    }

    @Test
    void testPartialFillRemainsPartialFill() {
        OrderState result = OrderStateMachine.transition(OrderState.PARTIAL_FILL, ExecEventType.PARTIAL_FILL);
        assertEquals(OrderState.PARTIAL_FILL, result);
    }

    @Test
    void testPendingCancelToCancelled() {
        OrderState result = OrderStateMachine.transition(OrderState.PENDING_CANCEL, ExecEventType.CANCELLED);
        assertEquals(OrderState.CANCELLED, result);
    }

    @Test
    void testPendingCancelToFilledRace() {
        OrderState result = OrderStateMachine.transition(OrderState.PENDING_CANCEL, ExecEventType.FILL);
        assertEquals(OrderState.FILLED, result);
    }

    @Test
    void testInvalidTransitionFromPendingNew() {
        assertThrows(IllegalStateException.class, () ->
            OrderStateMachine.transition(OrderState.PENDING_NEW, ExecEventType.PARTIAL_FILL)
        );
    }

    @Test
    void testInvalidTransitionFromOpen() {
        assertThrows(IllegalStateException.class, () ->
            OrderStateMachine.transition(OrderState.OPEN, ExecEventType.ACK)
        );
    }

    @Test
    void testInvalidTransitionFromPartialFill() {
        assertThrows(IllegalStateException.class, () ->
            OrderStateMachine.transition(OrderState.PARTIAL_FILL, ExecEventType.ACK)
        );
    }

    @Test
    void testInvalidTransitionFromPendingCancel() {
        assertThrows(IllegalStateException.class, () ->
            OrderStateMachine.transition(OrderState.PENDING_CANCEL, ExecEventType.ACK)
        );
    }

    @Test
    void testTerminalStateFilled() {
        assertThrows(IllegalStateException.class, () ->
            OrderStateMachine.transition(OrderState.FILLED, ExecEventType.ACK)
        );
    }

    @Test
    void testTerminalStateCancelled() {
        assertThrows(IllegalStateException.class, () ->
            OrderStateMachine.transition(OrderState.CANCELLED, ExecEventType.ACK)
        );
    }

    @Test
    void testTerminalStateRejected() {
        assertThrows(IllegalStateException.class, () ->
            OrderStateMachine.transition(OrderState.REJECTED, ExecEventType.ACK)
        );
    }

    @Test
    void testFullLifecycleOpen() {
        OrderState state = OrderState.PENDING_NEW;
        state = OrderStateMachine.transition(state, ExecEventType.ACK);
        assertEquals(OrderState.OPEN, state);
        state = OrderStateMachine.transition(state, ExecEventType.PARTIAL_FILL);
        assertEquals(OrderState.PARTIAL_FILL, state);
        state = OrderStateMachine.transition(state, ExecEventType.FILL);
        assertEquals(OrderState.FILLED, state);
    }

    @Test
    void testFullLifecycleRejected() {
        OrderState state = OrderState.PENDING_NEW;
        state = OrderStateMachine.transition(state, ExecEventType.REJECTED);
        assertEquals(OrderState.REJECTED, state);
    }
}
