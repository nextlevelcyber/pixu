package com.bedrock.mm.aeron.bus;

public class EventBusMetricsSnapshot {
    public final long publishAttempts;
    public final long publishSuccess;
    public final long publishEncodeFailures;
    public final long publishBackpressureDrops;
    public final long decodeFailures;
    public final long consumerDispatchFailures;

    public EventBusMetricsSnapshot(long publishAttempts, long publishSuccess, long publishEncodeFailures,
                                   long publishBackpressureDrops, long decodeFailures, long consumerDispatchFailures) {
        this.publishAttempts = publishAttempts;
        this.publishSuccess = publishSuccess;
        this.publishEncodeFailures = publishEncodeFailures;
        this.publishBackpressureDrops = publishBackpressureDrops;
        this.decodeFailures = decodeFailures;
        this.consumerDispatchFailures = consumerDispatchFailures;
    }
}
