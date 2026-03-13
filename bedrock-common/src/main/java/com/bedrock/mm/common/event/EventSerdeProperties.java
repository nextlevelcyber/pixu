package com.bedrock.mm.common.event;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "bedrock.bus")
public class EventSerdeProperties {
    private PayloadCodec payloadCodec = PayloadCodec.JSON;

    public PayloadCodec getPayloadCodec() {
        return payloadCodec;
    }

    public void setPayloadCodec(PayloadCodec payloadCodec) {
        this.payloadCodec = payloadCodec == null ? PayloadCodec.JSON : payloadCodec;
    }
}
