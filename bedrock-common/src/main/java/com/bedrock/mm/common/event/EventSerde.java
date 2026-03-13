package com.bedrock.mm.common.event;

/**
 * Payload codec abstraction for unified event bus.
 * Current implementation can be JSON; future implementations can be SBE/binary.
 */
public interface EventSerde {
    PayloadCodec codec();

    <T> byte[] serialize(T payload);

    <T> T deserialize(byte[] payload, Class<T> type);
}
