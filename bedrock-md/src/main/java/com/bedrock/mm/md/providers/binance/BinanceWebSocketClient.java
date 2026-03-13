package com.bedrock.mm.md.providers.binance;

import com.bedrock.mm.md.ws.AbstractWebSocketClient;

import java.util.List;

public class BinanceWebSocketClient extends AbstractWebSocketClient {
    private List<String> subscribeParams;
    private int subscribeId = 1;

    public BinanceWebSocketClient(String endpoint,
                                  java.util.function.Consumer<String> onMessage,
                                  int reconnectIntervalSeconds,
                                  int maxReconnectAttempts) {
        super(endpoint, onMessage, reconnectIntervalSeconds, maxReconnectAttempts);
    }

    public void setSubscribe(List<String> params, int id) {
        this.subscribeParams = params;
        this.subscribeId = id;
    }

    /**
     * 发送Binance批量订阅消息
     */
    private void sendSubscribe() {
        if (subscribeParams == null || subscribeParams.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        sb.append('{')
          .append("\"method\":\"SUBSCRIBE\",")
          .append("\"params\":[");
        for (int i = 0; i < subscribeParams.size(); i++) {
            sb.append('"').append(subscribeParams.get(i)).append('"');
            if (i < subscribeParams.size() - 1) sb.append(',');
        }
        sb.append("],\"id\":").append(subscribeId).append('}');
        sendText(sb.toString());
    }

    @Override
    protected void onConnectedAndResubscribe() {
        sendSubscribe();
    }
}