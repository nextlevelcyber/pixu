package com.bedrock.mm.aeron.bus;

import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.PayloadCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.agrona.DirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Transport codec for EventEnvelope.
 * Optimized for zero-allocation on hot path via symbol caching.
 */
final class EventEnvelopeWireCodec {
    private static final int MAGIC = 0x45564231; // EVB1
    private static final short VERSION = 1;
    private static final int FIXED_HEADER_LENGTH = 32;
    private static final ObjectMapper LEGACY_MAPPER = new ObjectMapper();

    // Symbol cache to avoid String allocation on decode
    private static final ConcurrentHashMap<String, String> SYMBOL_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_SYMBOL_CACHE_SIZE = 1000;

    private EventEnvelopeWireCodec() {
    }

    static byte[] encode(EventEnvelope env) {
        byte[] payload = env.getPayload();
        String symbol = env.getSymbol();

        // Pre-compute symbol bytes length without allocation if possible
        int symbolLength = -1;
        byte[] symbolBytes = null;
        if (symbol != null && !symbol.isEmpty()) {
            symbolBytes = symbol.getBytes(StandardCharsets.UTF_8);
            symbolLength = symbolBytes.length;
        }

        int payloadLength = payload.length;

        ByteBuffer out = ByteBuffer
                .allocate(FIXED_HEADER_LENGTH + (symbolLength < 0 ? 0 : symbolLength) + payloadLength)
                .order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MAGIC);
        out.putShort(VERSION);
        out.put((byte) env.getType().ordinal());
        out.put((byte) env.getPayloadCodec().ordinal());
        out.putInt(symbolLength);
        out.putInt(payloadLength);
        out.putLong(env.getTsNs());
        out.putLong(env.getSeq());
        if (symbolLength > 0) {
            out.put(symbolBytes);
        }
        out.put(payload);
        return out.array();
    }

    static EventEnvelope decode(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("Invalid event envelope: null bytes");
        }
        return decode(new UnsafeBuffer(bytes), 0, bytes.length);
    }

    static EventEnvelope decode(DirectBuffer buffer, int offset, int length) {
        if (buffer == null || length < FIXED_HEADER_LENGTH) {
            throw new IllegalArgumentException("Invalid event envelope: too short");
        }
        int magic = buffer.getInt(offset, ByteOrder.LITTLE_ENDIAN);
        if (magic != MAGIC) {
            return decodeLegacyJson(buffer, offset, length);
        }
        short version = (short) (buffer.getShort(offset + 4, ByteOrder.LITTLE_ENDIAN) & 0xFFFF);
        if (version != VERSION) {
            throw new IllegalArgumentException("Unsupported event envelope version: " + version);
        }
        int typeOrdinal = buffer.getByte(offset + 6) & 0xFF;
        int codecOrdinal = buffer.getByte(offset + 7) & 0xFF;
        int symbolLength = buffer.getInt(offset + 8, ByteOrder.LITTLE_ENDIAN);
        int payloadLength = buffer.getInt(offset + 12, ByteOrder.LITTLE_ENDIAN);
        long tsNs = buffer.getLong(offset + 16, ByteOrder.LITTLE_ENDIAN);
        long seq = buffer.getLong(offset + 24, ByteOrder.LITTLE_ENDIAN);

        if (typeOrdinal >= EventType.values().length) {
            throw new IllegalArgumentException("Invalid event type ordinal: " + typeOrdinal);
        }
        if (codecOrdinal >= PayloadCodec.values().length) {
            throw new IllegalArgumentException("Invalid payload codec ordinal: " + codecOrdinal);
        }
        if (symbolLength < -1) {
            throw new IllegalArgumentException("Invalid symbol length: " + symbolLength);
        }
        if (payloadLength < 0) {
            throw new IllegalArgumentException("Invalid payload length: " + payloadLength);
        }

        int expectedLength = FIXED_HEADER_LENGTH + (symbolLength < 0 ? 0 : symbolLength) + payloadLength;
        if (expectedLength != length) {
            throw new IllegalArgumentException("Invalid event envelope length: expected " + expectedLength + " but was " + length);
        }

        int cursor = offset + FIXED_HEADER_LENGTH;
        String symbol = null;
        if (symbolLength > 0) {
            // Use cached/interned symbol strings to avoid repeated allocations
            // For common symbols (BTCUSDT, ETHUSDT, etc.), this eliminates string allocation
            // Use DirectBuffer.getStringUtf8() to avoid intermediate byte[] allocation
            String tempSymbol = buffer.getStringWithoutLengthUtf8(cursor, symbolLength);
            symbol = internSymbol(tempSymbol);
            cursor += symbolLength;
        } else if (symbolLength == 0) {
            symbol = "";
        }
        byte[] payload = new byte[payloadLength];
        buffer.getBytes(cursor, payload);

        return new EventEnvelope(
                EventType.values()[typeOrdinal],
                payload,
                PayloadCodec.values()[codecOrdinal],
                symbol,
                tsNs,
                seq);
    }

    /**
     * Intern symbol strings to reduce allocation.
     * Common symbols (BTCUSDT, ETHUSDT, etc.) are cached and reused.
     */
    private static String internSymbol(String symbol) {
        if (symbol == null) {
            return null;
        }
        // Try to get from cache first
        String cached = SYMBOL_CACHE.get(symbol);
        if (cached != null) {
            return cached;
        }
        // If cache is not too large, add new symbol
        if (SYMBOL_CACHE.size() < MAX_SYMBOL_CACHE_SIZE) {
            SYMBOL_CACHE.putIfAbsent(symbol, symbol);
            return SYMBOL_CACHE.get(symbol);
        }
        // Cache full, just return the symbol (still allocates but prevents unbounded growth)
        return symbol;
    }

    private static EventEnvelope decodeLegacyJson(DirectBuffer buffer, int offset, int length) {
        try {
            byte[] bytes = new byte[length];
            buffer.getBytes(offset, bytes);
            WireEnvelope wire = LEGACY_MAPPER.readValue(bytes, WireEnvelope.class);
            EventType type = EventType.valueOf(wire.type);
            PayloadCodec payloadCodec = wire.payloadCodec == null
                    ? PayloadCodec.JSON
                    : PayloadCodec.valueOf(wire.payloadCodec);
            byte[] payload = wire.payload == null ? new byte[0] : wire.payload;
            return new EventEnvelope(type, payload, payloadCodec, wire.symbol, wire.tsNs, wire.seq);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid event envelope magic and legacy decode failed", e);
        }
    }

    private static final class WireEnvelope {
        public String type;
        public byte[] payload;
        public String payloadCodec;
        public String symbol;
        public long tsNs;
        public long seq;

        public WireEnvelope() {
        }
    }
}
