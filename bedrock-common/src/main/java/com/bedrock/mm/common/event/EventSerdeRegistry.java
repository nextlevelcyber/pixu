package com.bedrock.mm.common.event;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Lookup for payload serdes by codec type.
 */
@Component
public class EventSerdeRegistry {
    private final Map<PayloadCodec, EventSerde> serdes = new EnumMap<>(PayloadCodec.class);

    public EventSerdeRegistry(List<EventSerde> implementations) {
        for (EventSerde serde : implementations) {
            serdes.put(serde.codec(), serde);
        }
    }

    public EventSerde require(PayloadCodec codec) {
        EventSerde serde = serdes.get(codec);
        if (serde == null) {
            throw new IllegalArgumentException("No EventSerde registered for codec " + codec);
        }
        return serde;
    }
}
