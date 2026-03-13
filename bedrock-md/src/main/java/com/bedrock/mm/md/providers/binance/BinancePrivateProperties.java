package com.bedrock.mm.md.providers.binance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "bedrock.md.binance.private")
public class BinancePrivateProperties {
    private boolean enabled = true;
    /** Use new WebSocket API (userDataStream.start/ping/stop) */
    private boolean useWsApi = true;
    /** WebSocket API base URL (JSON-RPC) */
    private String wsApiUrl = "wss://ws-api.binance.com:443/ws-api/v3";
    /** Spot REST base for legacy userDataStream listenKey management */
    private String restBase = "https://api.binance.com";
    /** Spot private WS base for legacy listenKey (final URL is wsBase + "/" + listenKey) */
    private String wsBase = "wss://stream.binance.com:9443/ws";
    /** API key used for listenKey create/keepalive */
    private String apiKey = "MxrG462GNeKuo7p7i8em7RV5ujnswXzotNMnnA2jYjpiZW4e7znGQ7URe9IqbXIr";
    /** Secret key not required for userDataStream, kept for future signed endpoints */
    private String secretKey = "24zMzRH1TBkMTKT0Kk98Xx2AJEHlQaLtWK4eW20jfh4Kdy3buKulWmjFY9Cvudkg";
    /** Keepalive interval in minutes (Binance requires < 60 min); we use 30 by default */
    private int keepAliveMinutes = 30;
    /** WS reconnect interval seconds */
    private int reconnectIntervalSeconds = 5;
    /** Max reconnect attempts per WS client */
    private int maxReconnectAttempts = 10;
    /** WS-API ping interval seconds */
    private int pingIntervalSeconds = 30;
}