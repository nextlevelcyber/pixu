package com.bedrock.mm.app.runtime.bus;

import com.bedrock.mm.common.event.EventEnvelope;
import com.bedrock.mm.common.event.EventType;
import com.bedrock.mm.common.event.PayloadCodec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DeadLetterChannel {
    private static final int DEFAULT_CAPACITY = 1024;

    private final int capacity;
    private final AtomicLong totalCount = new AtomicLong(0);
    private final LinkedList<DeadLetterRecord> records = new LinkedList<>();

    public DeadLetterChannel() {
        this(DEFAULT_CAPACITY);
    }

    public DeadLetterChannel(int capacity) {
        this.capacity = Math.max(64, capacity);
    }

    public void publish(DeadLetterReason reason, String source, EventEnvelope env, Throwable error) {
        publish(reason, source,
                env == null ? null : env.getType(),
                env == null ? null : env.getPayloadCodec(),
                env == null ? null : env.getSymbol(),
                env == null ? -1L : env.getSeq(),
                error == null ? null : error.toString());
    }

    public void publish(DeadLetterReason reason, String source, EventType eventType, PayloadCodec payloadCodec,
                        String symbol, long seq, String error) {
        DeadLetterRecord record = new DeadLetterRecord(
                System.currentTimeMillis(),
                reason,
                source,
                eventType,
                payloadCodec,
                symbol,
                seq,
                error);
        synchronized (records) {
            records.addLast(record);
            while (records.size() > capacity) {
                records.removeFirst();
            }
        }
        totalCount.incrementAndGet();
    }

    public long totalCount() {
        return totalCount.get();
    }

    public int currentSize() {
        synchronized (records) {
            return records.size();
        }
    }

    public List<DeadLetterRecord> latest(int limit) {
        int bounded = Math.max(1, Math.min(5000, limit));
        synchronized (records) {
            int size = records.size();
            int start = Math.max(0, size - bounded);
            return new ArrayList<>(records.subList(start, size));
        }
    }
}
