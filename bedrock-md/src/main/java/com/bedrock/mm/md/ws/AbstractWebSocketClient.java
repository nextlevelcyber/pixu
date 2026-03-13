package com.bedrock.mm.md.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 抽象WebSocket客户端：统一连接管理、心跳、重连与消息分发。
 * 具体平台可继承此类并提供订阅消息的发送逻辑。
 */
public abstract class AbstractWebSocketClient implements AutoCloseable {
    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected final String endpoint;
    protected final Consumer<String> onMessage;
    protected WebSocket ws;
    protected volatile boolean opened = false;

    private Runnable onOpenCallback;
    private Runnable onCloseCallback;
    private Consumer<Throwable> onErrorCallback;

    private final Queue<String> pendingMessages = new ConcurrentLinkedQueue<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> heartbeatTask;

    private int reconnectAttempts = 0;
    private final int maxReconnectAttempts;
    private final int reconnectIntervalSeconds;

    protected AbstractWebSocketClient(String endpoint, Consumer<String> onMessage,
                                      int reconnectIntervalSeconds, int maxReconnectAttempts) {
        this.endpoint = endpoint;
        this.onMessage = onMessage;
        this.reconnectIntervalSeconds = reconnectIntervalSeconds;
        this.maxReconnectAttempts = maxReconnectAttempts;
    }

    /**
     * 建立连接
     */
    public void connect() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        CompletableFuture<WebSocket> future = client.newWebSocketBuilder()
                .buildAsync(URI.create(endpoint), new Listener());
        this.ws = future.join();
    }

    /**
     * 发送文本消息（订阅或控制指令）
     */
    protected void sendText(String msg) {
        WebSocket current = this.ws;
        if (current != null && opened) {
            current.sendText(msg, true);
        } else {
            pendingMessages.add(msg);
        }
    }

    /**
     * 设置心跳（可选）
     */
    protected void startHeartbeat(String payload, long initialDelaySec, long periodSec) {
        cancelHeartbeat();
        heartbeatTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                if (ws != null) {
                    ws.sendText(payload, true);
                }
            } catch (Exception e) {
                log.warn("Heartbeat send failed", e);
            }
        }, initialDelaySec, periodSec, TimeUnit.SECONDS);
    }

    protected void cancelHeartbeat() {
        if (heartbeatTask != null) {
            try { heartbeatTask.cancel(true); } catch (Exception ignored) {}
            heartbeatTask = null;
        }
    }

    public void setOnOpen(Runnable onOpen) { this.onOpenCallback = onOpen; }
    public void setOnClose(Runnable onClose) { this.onCloseCallback = onClose; }
    public void setOnError(Consumer<Throwable> onError) { this.onErrorCallback = onError; }

    /**
     * 重连调度
     */
    protected void scheduleReconnect() {
        if (reconnectAttempts >= maxReconnectAttempts) {
            log.warn("Reached max reconnect attempts: {}", reconnectAttempts);
            return;
        }
        reconnectAttempts++;
        int delay = reconnectIntervalSeconds;
        log.info("Scheduling reconnect attempt {} in {}s", reconnectAttempts, delay);
        scheduler.schedule(this::reconnect, delay, TimeUnit.SECONDS);
    }

    private void reconnect() {
        try {
            connect();
        } catch (Exception e) {
            log.warn("Reconnect failed", e);
            scheduleReconnect();
        }
    }

    @Override
    public void close() {
        cancelHeartbeat();
        try {
            if (ws != null) {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
                ws.abort();
            }
        } catch (Exception e) {
            log.warn("Error closing WS", e);
        } finally {
            scheduler.shutdownNow();
        }
    }

    /**
     * 当连接打开时，发送具体平台订阅消息（由子类实现）
     */
    protected abstract void onConnectedAndResubscribe();

    private class Listener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder(2048);
        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("WS connected: {}", endpoint);
            ws = webSocket;
            opened = true;
            // 刷新重连计数
            reconnectAttempts = 0;
            // 刷新队列中的待发消息
            String pending;
            while ((pending = pendingMessages.poll()) != null) {
                webSocket.sendText(pending, true);
            }
            if (onOpenCallback != null) {
                try { onOpenCallback.run(); } catch (Exception e) { log.warn("onOpen callback error", e); }
            }
            try { onConnectedAndResubscribe(); } catch (Exception e) { log.warn("Resubscribe error", e); }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            try {
                // Accumulate fragmented frames until last=true
                textBuffer.append(data);
                if (last) {
                    String msg = textBuffer.toString();
                    textBuffer.setLength(0);
                    onMessage.accept(msg);
                }
            } catch (Exception e) {
                log.warn("onMessage handler error", e);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            webSocket.sendPong(message);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.info("WS closed: {} {}", statusCode, reason);
            opened = false;
            if (onCloseCallback != null) {
                try { onCloseCallback.run(); } catch (Exception e) { log.warn("onClose callback error", e); }
            }
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("WS error", error);
            if (onErrorCallback != null) {
                try { onErrorCallback.accept(error); } catch (Exception e) { log.warn("onError callback error", e); }
            }
            scheduleReconnect();
        }
    }
}