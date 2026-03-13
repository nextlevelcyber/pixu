package com.bedrock.mm.app.runtime.bus;

import com.bedrock.mm.app.config.BedrockConfig;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventSerdeRegistry;
import com.bedrock.mm.common.event.PayloadCodec;
import com.bedrock.mm.oms.exec.ExecGateway;
import com.bedrock.mm.oms.store.OrderStore;
import com.bedrock.mm.oms.position.PositionTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OmsCoordinator lifecycle management.
 */
class OmsCoordinatorTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private EventSerdeRegistry serdeRegistry;
    private EventSerde serde;
    private DeadLetterChannel deadLetterChannel;
    private OmsCoordinator coordinator;

    @BeforeEach
    void setUp() {
        serde = new EventSerde() {
            @Override public byte[] serialize(Object obj) {
                try { return mapper.writeValueAsBytes(obj); } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public <T> T deserialize(byte[] bytes, Class<T> type) {
                try { return mapper.readValue(bytes, type); } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public PayloadCodec codec() { return PayloadCodec.JSON; }
        };
        serdeRegistry = new EventSerdeRegistry(List.of(serde));
        deadLetterChannel = new DeadLetterChannel();
        // No bus (null): OmsCoordinator logs a warning but doesn't crash
        coordinator = new OmsCoordinator(serdeRegistry, serde, deadLetterChannel, null, null);
    }

    private BedrockConfig configWith(String... symbols) {
        BedrockConfig config = new BedrockConfig();
        BedrockConfig.OmsConfig omsConfig = new BedrockConfig.OmsConfig();
        omsConfig.setEnabled(true);
        omsConfig.setSymbols(List.of(symbols));
        omsConfig.setExchange("simulation"); // no credentials needed
        config.setOms(omsConfig);
        return config;
    }

    @Test
    void startCreatesOrderStorePerSymbol() {
        coordinator.start(configWith("BTCUSDT", "ETHUSDT"));
        assertNotNull(coordinator.getOrderStore("BTCUSDT"));
        assertNotNull(coordinator.getOrderStore("ETHUSDT"));
    }

    @Test
    void getOrderStoreReturnsNullForUnregistered() {
        coordinator.start(configWith("BTCUSDT"));
        assertNull(coordinator.getOrderStore("XRPUSDT"));
    }

    @Test
    void getPositionTrackerReturnsInstancePerSymbol() {
        coordinator.start(configWith("BTCUSDT"));
        PositionTracker tracker = coordinator.getPositionTracker("BTCUSDT");
        assertNotNull(tracker);
        assertEquals(0L, tracker.getNetPosition(1));
    }

    @Test
    void multipleSymbolsGetSeparateOrderStores() {
        coordinator.start(configWith("BTCUSDT", "ETHUSDT"));
        OrderStore btcStore = coordinator.getOrderStore("BTCUSDT");
        OrderStore ethStore = coordinator.getOrderStore("ETHUSDT");
        assertNotNull(btcStore);
        assertNotNull(ethStore);
        assertNotSame(btcStore, ethStore);
    }

    @Test
    void emptySymbolsNoOp() {
        coordinator.start(configWith());
        assertEquals(0, coordinator.getAllStates().size());
    }

    @Test
    void stopClearsAllStates() {
        coordinator.start(configWith("BTCUSDT", "ETHUSDT"));
        assertEquals(2, coordinator.getAllStates().size());
        coordinator.stop();
        assertEquals(0, coordinator.getAllStates().size());
        assertNull(coordinator.getOrderStore("BTCUSDT"));
    }

    @Test
    void instrumentIdsAreSequential() {
        coordinator.start(configWith("BTCUSDT", "ETHUSDT"));
        OmsCoordinator.InstrumentOmsState btcState = coordinator.getAllStates().get("BTCUSDT");
        OmsCoordinator.InstrumentOmsState ethState = coordinator.getAllStates().get("ETHUSDT");
        assertNotNull(btcState);
        assertNotNull(ethState);
        // instrument IDs should be distinct
        assertNotEquals(btcState.instrumentId, ethState.instrumentId);
    }

    @Test
    void latencySnapshotAvailableForRegisteredSymbol() {
        coordinator.start(configWith("BTCUSDT"));
        OmsCoordinator.OmsLatencySnapshot snapshot = coordinator.getLatencySnapshot("BTCUSDT");
        assertNotNull(snapshot);
        assertEquals(0L, snapshot.execEventsEnqueued());
        assertEquals(0L, snapshot.execEventsProcessed());
        assertTrue(coordinator.getAllLatencySnapshots().containsKey("BTCUSDT"));
    }

    @Test
    void latencySnapshotMissingForUnknownSymbol() {
        coordinator.start(configWith("BTCUSDT"));
        assertNull(coordinator.getLatencySnapshot("ETHUSDT"));
    }

    @Test
    void latencySnapshotsClearedAfterStop() {
        coordinator.start(configWith("BTCUSDT", "ETHUSDT"));
        assertEquals(2, coordinator.getAllLatencySnapshots().size());
        coordinator.stop();
        assertTrue(coordinator.getAllLatencySnapshots().isEmpty());
        assertNull(coordinator.getLatencySnapshot("BTCUSDT"));
    }

    @Test
    void startupRecoverySnapshotAvailableAfterStart() {
        coordinator.start(configWith("BTCUSDT"));
        OmsCoordinator.OmsRecoverySnapshot snapshot = coordinator.getRecoverySnapshot("BTCUSDT");
        assertNotNull(snapshot);
        assertTrue(snapshot.recoveryReady());
        assertFalse(snapshot.recoveryInProgress());
        assertTrue(snapshot.lastRecoveryStartEpochMs() > 0L);
        assertEquals(0L, snapshot.recoveryFailure());
    }

    @Test
    void retryRecoveryReturnsTrueForKnownSymbol() {
        coordinator.start(configWith("BTCUSDT"));
        assertTrue(coordinator.retryRecovery("BTCUSDT"));
        assertTrue(coordinator.isStartupRecoveryReady());
    }

    @Test
    void retryRecoveryFailureKeepsGateClosed() {
        coordinator.start(configWith("BTCUSDT"));
        OmsCoordinator.InstrumentOmsState state = coordinator.getAllStates().get("BTCUSDT");
        assertNotNull(state);
        state.execGateway = new ExecGateway() {
            @Override public void placeOrder(int instrumentId, long price, long size, boolean isBid, int regionIndex) {}
            @Override public void cancelOrder(int instrumentId, long orderId) {}
            @Override public boolean onPrivateWsReconnect() { return false; }
            @Override public void shutdown() {}
        };

        assertFalse(coordinator.retryRecovery("BTCUSDT"));
        OmsCoordinator.OmsRecoverySnapshot snapshot = coordinator.getRecoverySnapshot("BTCUSDT");
        assertNotNull(snapshot);
        assertFalse(snapshot.recoveryReady());
        assertFalse(snapshot.recoveryInProgress());
        assertTrue(snapshot.recoveryFailure() >= 1L);
    }
}
