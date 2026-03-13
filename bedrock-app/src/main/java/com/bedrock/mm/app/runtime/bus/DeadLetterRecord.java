package com.bedrock.mm.app.runtime.bus;

import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.PayloadCodec;

public class DeadLetterRecord {
    public final long timestampMs;
    public final DeadLetterReason reason;
    public final String source;
    public final EventType eventType;
    public final PayloadCodec payloadCodec;
    public final String symbol;
    public final long seq;
    public final String error;

    public DeadLetterRecord(long timestampMs, DeadLetterReason reason, String source, EventType eventType,
                            PayloadCodec payloadCodec, String symbol, long seq, String error) {
        this.timestampMs = timestampMs;
        this.reason = reason;
        this.source = source;
        this.eventType = eventType;
        this.payloadCodec = payloadCodec;
        this.symbol = symbol;
        this.seq = seq;
        this.error = error;
    }
}
