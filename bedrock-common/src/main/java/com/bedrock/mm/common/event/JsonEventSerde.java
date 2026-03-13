package com.bedrock.mm.common.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Default JSON serde for bus payloads.
 */
@Component("jsonEventSerde")
public class JsonEventSerde implements EventSerde {
    private final ObjectMapper mapper;

    public JsonEventSerde(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public PayloadCodec codec() {
        return PayloadCodec.JSON;
    }

    @Override
    public <T> byte[] serialize(T payload) {
        try {
            return mapper.writeValueAsBytes(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize payload", e);
        }
    }

    @Override
    public <T> T deserialize(byte[] payload, Class<T> type) {
        try {
            return mapper.readValue(payload, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to deserialize payload to " + type.getSimpleName(), e);
        }
    }
}
