package com.bedrock.mm.app.runtime.bus;

import com.bedrock.mm.app.config.BedrockConfig;
import com.bedrock.mm.common.event.EventSerde;
import com.bedrock.mm.common.event.EventSerdeRegistry;
import com.bedrock.mm.common.event.PayloadCodec;
import com.bedrock.mm.pricing.PricingOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for PricingEngineCoordinator.
 */
public class PricingEngineCoordinatorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PricingEngineCoordinator coordinator;
    private DeadLetterChannel deadLetterChannel;

    private final EventSerde jsonSerde = new EventSerde() {
        @Override public PayloadCodec codec() { return PayloadCodec.JSON; }
        @Override public <T> byte[] serialize(T payload) {
            try { return MAPPER.writeValueAsBytes(payload); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
        @Override public <T> T deserialize(byte[] payload, Class<T> type) {
            try { return MAPPER.readValue(payload, type); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    };

    @BeforeEach
    void setUp() {
        deadLetterChannel = new DeadLetterChannel();
        EventSerdeRegistry serdeRegistry = new EventSerdeRegistry(List.of(jsonSerde));
        coordinator = new PricingEngineCoordinator(serdeRegistry, jsonSerde, deadLetterChannel,
                null, null); // no bus in unit tests
    }

    private BedrockConfig configWithSymbols(String... symbols) {
        BedrockConfig config = new BedrockConfig();
        config.getPricing().setEnabled(true);
        config.getPricing().setSymbols(List.of(symbols));
        return config;
    }

    @Test
    void testStartCreatesOrchestratorForEachSymbol() {
        BedrockConfig config = configWithSymbols("BTCUSDT", "ETHUSDT");

        coordinator.start(config);

        assertNotNull(coordinator.getOrchestrator("BTCUSDT"));
        assertNotNull(coordinator.getOrchestrator("ETHUSDT"));
    }

    @Test
    void testGetOrchestratorReturnsPreCreatedInstance() {
        BedrockConfig config = configWithSymbols("BTCUSDT");

        coordinator.start(config);

        PricingOrchestrator orchestrator = coordinator.getOrchestrator("BTCUSDT");
        assertNotNull(orchestrator);
        // Same instance on repeated calls
        assertSame(orchestrator, coordinator.getOrchestrator("BTCUSDT"));
    }

    @Test
    void testMultipleInstrumentsGetSeparateOrchestrators() {
        BedrockConfig config = configWithSymbols("BTCUSDT", "ETHUSDT", "SOLUSDT");

        coordinator.start(config);

        PricingOrchestrator btc = coordinator.getOrchestrator("BTCUSDT");
        PricingOrchestrator eth = coordinator.getOrchestrator("ETHUSDT");
        PricingOrchestrator sol = coordinator.getOrchestrator("SOLUSDT");

        assertNotNull(btc);
        assertNotNull(eth);
        assertNotNull(sol);
        // Different instances
        assertNotSame(btc, eth);
        assertNotSame(eth, sol);
        // Different instrument IDs
        assertNotEquals(btc.getInstrumentId(), eth.getInstrumentId());
        assertNotEquals(eth.getInstrumentId(), sol.getInstrumentId());
    }

    @Test
    void testGetOrchestratorReturnsNullForUnregisteredSymbol() {
        coordinator.start(configWithSymbols("BTCUSDT"));

        assertNull(coordinator.getOrchestrator("UNKNOWN"));
        assertNull(coordinator.getOrchestrator("ETHUSDT"));
    }

    @Test
    void testStartWithNoSymbolsDoesNothing() {
        coordinator.start(configWithSymbols());

        assertTrue(coordinator.getAllOrchestrators().isEmpty());
    }

    @Test
    void testStopClearsOrchestrators() {
        coordinator.start(configWithSymbols("BTCUSDT", "ETHUSDT"));

        assertFalse(coordinator.getAllOrchestrators().isEmpty());

        coordinator.stop();

        assertTrue(coordinator.getAllOrchestrators().isEmpty());
        assertNull(coordinator.getOrchestrator("BTCUSDT"));
    }

    @Test
    void testInstrumentIdsAreSequential() {
        BedrockConfig config = configWithSymbols("BTCUSDT", "ETHUSDT");

        coordinator.start(config);

        int btcId = coordinator.getOrchestrator("BTCUSDT").getInstrumentId();
        int ethId = coordinator.getOrchestrator("ETHUSDT").getInstrumentId();

        // Both assigned, different IDs, sequential order
        assertTrue(btcId > 0);
        assertTrue(ethId > 0);
        assertNotEquals(btcId, ethId);
        assertEquals(btcId + 1, ethId);
    }
}
