package com.bedrock.mm.aeron.bus;

import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.PayloadCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventEnvelopeWireCodecTest {

    @Test
    void shouldEncodeDecodeJsonPayloadEnvelope() {
        byte[] payload = "{\"symbol\":\"BTCUSDT\"}".getBytes(StandardCharsets.UTF_8);
        EventEnvelope input = new EventEnvelope(
                EventType.MARKET_TICK, payload, PayloadCodec.JSON, "BTCUSDT", 123L, 1L);

        byte[] wire = EventEnvelopeWireCodec.encode(input);
        EventEnvelope output = EventEnvelopeWireCodec.decode(wire);

        assertEquals(input.getType(), output.getType());
        assertEquals(input.getPayloadCodec(), output.getPayloadCodec());
        assertArrayEquals(input.getPayload(), output.getPayload());
        assertEquals(input.getSymbol(), output.getSymbol());
        assertEquals(input.getTsNs(), output.getTsNs());
        assertEquals(input.getSeq(), output.getSeq());
    }

    @Test
    void shouldEncodeDecodeSbePayloadEnvelope() {
        byte[] payload = new byte[] {1, 2, 3, 4, 5};
        EventEnvelope input = new EventEnvelope(
                EventType.ORDER_COMMAND, payload, PayloadCodec.SBE, "BTCUSDT", 456L, 2L);

        byte[] wire = EventEnvelopeWireCodec.encode(input);
        EventEnvelope output = EventEnvelopeWireCodec.decode(wire);

        assertEquals(PayloadCodec.SBE, output.getPayloadCodec());
        assertArrayEquals(payload, output.getPayload());
    }

    @Test
    void shouldRejectInvalidMagic() {
        byte[] payload = new byte[] {1, 2};
        EventEnvelope input = new EventEnvelope(
                EventType.FILL, payload, PayloadCodec.SBE, "BTCUSDT", 1L, 2L);

        byte[] wire = EventEnvelopeWireCodec.encode(input);
        wire[0] = 0;
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> EventEnvelopeWireCodec.decode(wire));
        assertTrue(ex.getMessage().contains("magic"));
    }
}
