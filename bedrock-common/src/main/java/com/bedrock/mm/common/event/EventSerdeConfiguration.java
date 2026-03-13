package com.bedrock.mm.common.event;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(EventSerdeProperties.class)
public class EventSerdeConfiguration {

    @Bean
    @Primary
    public EventSerde activeEventSerde(
            EventSerdeProperties properties,
            @Qualifier("jsonEventSerde") EventSerde jsonEventSerde,
            @Qualifier("sbeEventSerde") EventSerde sbeEventSerde) {
        return properties.getPayloadCodec() == PayloadCodec.SBE ? sbeEventSerde : jsonEventSerde;
    }
}
