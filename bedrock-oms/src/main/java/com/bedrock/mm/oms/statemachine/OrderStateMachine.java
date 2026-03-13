package com.bedrock.mm.oms.statemachine;

import com.bedrock.mm.oms.model.ExecEventType;
import com.bedrock.mm.oms.model.OrderState;

/**
 * OrderStateMachine - Pure state transition logic with zero allocation.
 *
 * Valid state transitions:
 * - PENDING_NEW + ACK → OPEN
 * - PENDING_NEW + REJECT → REJECTED
 * - PENDING_NEW + FILL → FILLED (rare: immediate fill)
 * - OPEN + PARTIAL_FILL → PARTIAL_FILL
 * - OPEN + FILL → FILLED
 * - OPEN + CANCEL → PENDING_CANCEL (we issued cancel)
 * - OPEN + CANCELLED → CANCELLED (exchange cancelled)
 * - PARTIAL_FILL + FILL → FILLED
 * - PARTIAL_FILL + CANCEL → PENDING_CANCEL
 * - PENDING_CANCEL + CANCELLED → CANCELLED
 * - PENDING_CANCEL + FILL → FILLED (fill racing with cancel)
 *
 * Thread safety: Stateless, safe for concurrent use.
 */
public class OrderStateMachine {

    /**
     * Compute the next state given current state and execution event.
     *
     * @param current current order state
     * @param eventType execution event type
     * @return next order state
     * @throws IllegalStateException if transition is invalid
     */
    public static OrderState transition(OrderState current, ExecEventType eventType) {
        switch (current) {
            case PENDING_NEW:
                switch (eventType) {
                    case ACK:
                        return OrderState.OPEN;
                    case REJECTED:
                        return OrderState.REJECTED;
                    case FILL:
                        return OrderState.FILLED;
                    default:
                        throwInvalidTransition(current, eventType);
                }
                break;

            case OPEN:
                switch (eventType) {
                    case PARTIAL_FILL:
                        return OrderState.PARTIAL_FILL;
                    case FILL:
                        return OrderState.FILLED;
                    case CANCELLED:
                        return OrderState.CANCELLED;
                    default:
                        throwInvalidTransition(current, eventType);
                }
                break;

            case PARTIAL_FILL:
                switch (eventType) {
                    case PARTIAL_FILL:
                        return OrderState.PARTIAL_FILL;
                    case FILL:
                        return OrderState.FILLED;
                    default:
                        throwInvalidTransition(current, eventType);
                }
                break;

            case PENDING_CANCEL:
                switch (eventType) {
                    case CANCELLED:
                        return OrderState.CANCELLED;
                    case FILL:
                        return OrderState.FILLED;
                    default:
                        throwInvalidTransition(current, eventType);
                }
                break;

            case FILLED:
            case CANCELLED:
            case REJECTED:
                // Terminal states - no transitions allowed
                throwInvalidTransition(current, eventType);
                break;

            default:
                throw new IllegalStateException("Unknown state: " + current);
        }

        // Should never reach here
        throw new IllegalStateException("Unexpected transition failure: " + current + " + " + eventType);
    }

    private static void throwInvalidTransition(OrderState current, ExecEventType eventType) {
        throw new IllegalStateException(
            "Invalid state transition: " + current + " + " + eventType
        );
    }
}
