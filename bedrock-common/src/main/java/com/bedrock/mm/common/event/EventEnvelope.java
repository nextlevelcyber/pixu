package com.bedrock.mm.common.event;

import java.util.Objects;

/**
 * Unified event envelope carrying type and encoded payload.
 */
public class EventEnvelope {
    private final EventType type;
    private final byte[] payload;
    private final PayloadCodec payloadCodec;
    private final String symbol;
    private final long tsNs;
    private final long seq;

    public EventEnvelope(EventType type, byte[] payload, PayloadCodec payloadCodec, String symbol, long tsNs, long seq) {
        this.type = Objects.requireNonNull(type);
        this.payload = Objects.requireNonNull(payload);
        this.payloadCodec = Objects.requireNonNull(payloadCodec);
        this.symbol = symbol;
        this.tsNs = tsNs;
        this.seq = seq;
    }

    /**
     * Backward-compatible JSON constructor.
     */
    public EventEnvelope(EventType type, String payloadJson, String symbol, long tsNs, long seq) {
        this(type, payloadJson == null ? new byte[0] : payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                PayloadCodec.JSON, symbol, tsNs, seq);
    }

    public EventType getType() { return type; }
    public byte[] getPayload() { return payload; }
    public PayloadCodec getPayloadCodec() { return payloadCodec; }

    /**
     * Backward-compatible accessor for JSON payload only.
     */
    public String getPayloadJson() {
        if (payloadCodec != PayloadCodec.JSON) {
            throw new IllegalStateException("Payload codec is not JSON: " + payloadCodec);
        }
        return new String(payload, java.nio.charset.StandardCharsets.UTF_8);
    }
    public String getSymbol() { return symbol; }
    public long getTsNs() { return tsNs; }
    public long getSeq() { return seq; }
}
