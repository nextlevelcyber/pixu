package com.bedrock.mm.md.providers.binance;

import com.bedrock.mm.md.ws.AbstractWebSocketClient;

/**
 * Binance WebSocket API client for User Data Stream.
 * Sends JSON-RPC methods: userDataStream.start / ping / stop.
 */
public class BinanceUserDataWebSocketApiClient extends AbstractWebSocketClient {

    private final int startRequestId;
    private final int pingRequestId;
    private final int stopRequestId;
    private final int pingIntervalSeconds;

    public BinanceUserDataWebSocketApiClient(String endpoint,
                                             java.util.function.Consumer<String> onMessage,
                                             int reconnectIntervalSeconds,
                                             int maxReconnectAttempts,
                                             int pingIntervalSeconds) {
        super(endpoint, onMessage, reconnectIntervalSeconds, maxReconnectAttempts);
        this.startRequestId = 1;
        this.pingRequestId = 2;
        this.stopRequestId = 3;
        this.pingIntervalSeconds = Math.max(5, pingIntervalSeconds);
    }

    @Override
    protected void onConnectedAndResubscribe() {
        // Start user data stream
        sendText('{'
            + "\"id\":" + startRequestId + ','
            + "\"method\":\"userDataStream.start\"" + '}');
        // Heartbeat ping
        startHeartbeat('{'
            + "\"id\":" + pingRequestId + ','
            + "\"method\":\"userDataStream.ping\"" + '}', 20, pingIntervalSeconds);
    }

    /**
     * Stop user data stream gracefully.
     */
    public void stopStream() {
        sendText('{'
            + "\"id\":" + stopRequestId + ','
            + "\"method\":\"userDataStream.stop\"" + '}');
    }
}