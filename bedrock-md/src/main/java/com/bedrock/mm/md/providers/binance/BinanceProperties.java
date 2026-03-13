package com.bedrock.mm.md.providers.binance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "bedrock.md.binance")
public class BinanceProperties {
    private boolean enabled = false;
    /** Spot public WS endpoint */
    private String endpointSpot = "wss://stream.binance.com:9443/ws";
    /** USDT-margined futures public WS endpoint */
    private String endpointFutures = "wss://fstream.binance.com/ws";
    /** Market type: SPOT or FUTURES */
    private String marketType = "SPOT";
    /** Depth stream suffix, e.g. depth@100ms */
    private String depthStream = "depth20@100ms";
    /** Symbols to subscribe; case-insensitive (e.g., btcusdt, BTCUSDT) */
    private List<String> symbols = List.of("BTCUSDT", "ETHUSDT");
    private int reconnectIntervalSeconds = 5;
    private int maxReconnectAttempts = 10;
}