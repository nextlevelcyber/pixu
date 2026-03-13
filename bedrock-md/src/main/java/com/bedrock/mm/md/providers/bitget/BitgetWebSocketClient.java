package com.bedrock.mm.md.providers.bitget;

import com.bedrock.mm.md.ws.AbstractWebSocketClient;

import java.util.ArrayList;
import java.util.List;

public class BitgetWebSocketClient extends AbstractWebSocketClient {
    private static class Arg {
        final String instType; final String channel; final String instId;
        Arg(String instType, String channel, String instId) { this.instType = instType; this.channel = channel; this.instId = instId; }
    }
    private final List<Arg> subscriptions = new ArrayList<>();

    public BitgetWebSocketClient(String endpoint,
                                 java.util.function.Consumer<String> onMessage,
                                 int reconnectIntervalSeconds,
                                 int maxReconnectAttempts) {
        super(endpoint, onMessage, reconnectIntervalSeconds, maxReconnectAttempts);
        // Bitget需要心跳ping
        startHeartbeat("ping", 20, 20);
    }

    public void addSubscription(String instType, String channel, String instId) {
        subscriptions.add(new Arg(instType, channel, instId));
    }

    public void clearSubscriptions() { subscriptions.clear(); }

    private void sendSubscribe(String instType, String channel, String instId) {
        String msg = String.format("{\"op\":\"subscribe\",\"args\":[{\"instType\":\"%s\",\"channel\":\"%s\",\"instId\":\"%s\"}]}",
                instType, channel, instId);
        sendText(msg);
    }

    @Override
    protected void onConnectedAndResubscribe() {
        for (Arg a : subscriptions) {
            sendSubscribe(a.instType, a.channel, a.instId);
        }
    }
}