package com.bedrock.mm.app.config;

import com.bedrock.mm.aeron.bus.AeronEventBus;
import com.bedrock.mm.aeron.bus.EventBus;
import com.bedrock.mm.aeron.bus.EventDispatcher;
import com.bedrock.mm.aeron.bus.InProcEventBus;
import com.bedrock.mm.common.channel.ChannelMode;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EventBusConfiguration {

    @Bean
    public EventBus eventBus(EventBusProperties props) {
        ChannelMode mode = props.getMode();
        if (mode == ChannelMode.IN_PROC) {
            return new InProcEventBus(props.getRingBufferSize());
        } else {
            return new AeronEventBus(
                    mode,
                    props.getStreamId(),
                    props.getEndpoint(),
                    props.getAeronDir(),
                    props.isEmbeddedMediaDriver(),
                    props.isDeleteAeronDirOnStart());
        }
    }

    @Bean
    public EventDispatcher eventDispatcher() {
        return new EventDispatcher();
    }
}
