package com.bedrock.mm.md.providers.bitget;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "bedrock.md.bitget")
public class BitgetProperties {
    private boolean enabled = false;
    private String endpoint = "wss://ws.bitget.com/v2/ws/public";
    private String instType = "USDT-FUTURES";
    private String depthChannel = "books5"; // books, books5, books15
    private List<String> symbols = List.of("BTCUSDT", "ETHUSDT");
    private int reconnectIntervalSeconds = 5;
    private int maxReconnectAttempts = 10;
}