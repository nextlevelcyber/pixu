package com.bedrock.mm.md.providers.binance;

import com.bedrock.mm.md.ws.AbstractWebSocketClient;

import java.util.function.Consumer;

/**
 * Binance Spot Private WS client for user data stream (listenKey).
 * No subscription message is required once connected to wsBase/listenKey.
 */
public class BinancePrivateWebSocketClient extends AbstractWebSocketClient {

    public BinancePrivateWebSocketClient(String endpoint,
                                         Consumer<String> onMessage,
                                         int reconnectIntervalSeconds,
                                         int maxReconnectAttempts) {
        super(endpoint, onMessage, reconnectIntervalSeconds, maxReconnectAttempts);
    }

    @Override
    protected void onConnectedAndResubscribe() {
        // User data stream does not require a SUBSCRIBE message; events start flowing after connect.
    }
}