package com.bedrock.mm.adapter;

import com.bedrock.mm.common.model.Symbol;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class BinanceTradingAdapter implements TradingAdapter {
    private static final Logger log = LoggerFactory.getLogger(BinanceTradingAdapter.class);

    private static final String ADAPTER_NAME = "Binance";
    private static final String ADAPTER_VERSION = "0.1.0";

    private AdapterConfig config;
    private final AdapterStats stats = new AdapterStats();
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private AdapterConfig.AuthDetails authDetails;
    private Map<String, Object> customSettings;
    private String baseUrl;
    private String wsUrl;
    private long recvWindowMs = 5000;
    private String healthPath = "/api/v3/ping"; // public ping
    private HttpClient httpClient;
    private WebSocket webSocket;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private AdapterConfig.WebSocketConfig wsConfig;
    private final ObjectMapper mapper = new ObjectMapper();

    private OrderUpdateHandler orderUpdateHandler;
    private TradeUpdateHandler tradeUpdateHandler;
    private BalanceUpdateHandler balanceUpdateHandler;
    private PositionUpdateHandler positionUpdateHandler;

    @Override
    public String getName() { return ADAPTER_NAME; }

    @Override
    public String getVersion() { return ADAPTER_VERSION; }

    @Override
    public void initialize(AdapterConfig config) {
        this.config = config;
        String key = getName().toLowerCase();
        this.authDetails = config.getAuth().getAuthFor(key);
        this.customSettings = config.getCustomSettingsFor(key);
        Object bu = customSettings.getOrDefault("base-url", config.getConnection().getBaseUrl());
        Object wu = customSettings.getOrDefault("ws-url", config.getConnection().getWsUrl());
        Object rw = customSettings.getOrDefault("recv-window-ms", recvWindowMs);
        Object hp = customSettings.getOrDefault("health-path", healthPath);
        this.baseUrl = bu != null ? String.valueOf(bu) : null;
        this.wsUrl = wu != null ? String.valueOf(wu) : null;
        this.healthPath = hp != null ? String.valueOf(hp) : healthPath;
        try {
            this.recvWindowMs = Long.parseLong(String.valueOf(rw));
        } catch (NumberFormatException ignored) {}

        this.wsConfig = config.getWebSocket();
        // Build HTTP client with connect timeout
        long ctMs = Math.max(1000, config.getConnection().getConnectTimeoutMs());
        this.connectTimeout = Duration.ofMillis(ctMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();

        log.info("Initialized {} adapter v{} (env={}) baseUrl={} wsUrl={} recvWindowMs={}", getName(), getVersion(), config.getEnvironment(), baseUrl, wsUrl, recvWindowMs);
    }

    @Override
    public CompletableFuture<Void> connect() {
        stats.recordConnectAttempt();
        log.info("{} connecting (env={}) baseUrl={} wsUrl={} apiKeyPresent={}", getName(), config.getEnvironment(), baseUrl, wsUrl, authDetails != null && authDetails.getApiKey() != null && !authDetails.getApiKey().isEmpty());

        // Fire a public REST health check
        performRestHealthCheck();

        // Perform a minimal signed REST call if credentials available
        if (authDetails != null && authDetails.isEnableSignature() && authDetails.getApiKey() != null && !authDetails.getApiKey().isEmpty() && authDetails.getSecretKey() != null && !authDetails.getSecretKey().isEmpty()) {
            performSignedAccountRequest();
        } else {
            log.info("{} signed REST call skipped: missing API key/secret or signature disabled", getName());
        }

        // Connect websocket if configured
        if (wsConfig != null && wsConfig.isEnabled() && wsUrl != null && !wsUrl.isEmpty()) {
            connectWebSocket();
        }

        connected.set(true);
        stats.recordConnectSuccess();
        log.info("{} adapter connected", getName());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> disconnect() {
        if (connected.compareAndSet(true, false)) {
            stats.recordDisconnect();
            log.info("{} adapter disconnected", getName());
            if (webSocket != null) {
                try {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
                } catch (Exception e) {
                    log.warn("{} websocket close failed: {}", getName(), e.getMessage());
                } finally {
                    webSocket = null;
                }
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private void performRestHealthCheck() {
        if (baseUrl == null || healthPath == null) {
            log.info("{} REST health check skipped: baseUrl or healthPath not set", getName());
            return;
        }
        String url = joinUrl(baseUrl, healthPath);
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(connectTimeout)
                    .GET();
            // Optional API key header (not required for public ping)
            if (authDetails != null && authDetails.getApiKey() != null && !authDetails.getApiKey().isEmpty()) {
                builder.header("X-MBX-APIKEY", authDetails.getApiKey());
            }
            HttpRequest request = builder.build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(resp -> log.info("{} REST health {} -> status {}", getName(), url, resp.statusCode()))
                    .exceptionally(ex -> {
                        log.warn("{} REST health failed {} -> {}", getName(), url, ex.toString());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("{} REST health exception {} -> {}", getName(), url, e.toString());
        }
    }

    private void connectWebSocket() {
        try {
            this.webSocket = httpClient.newWebSocketBuilder()
                    .connectTimeout(connectTimeout)
                    .buildAsync(URI.create(wsUrl), new AdapterWebSocketListener())
                    .join();
            // Send ping to verify
            webSocket.sendPing(ByteBuffer.wrap("ping".getBytes()));
            log.info("{} websocket connected to {}", getName(), wsUrl);
            sendBinanceSubscriptions();
        } catch (Exception e) {
            log.warn("{} websocket connect failed: {}", getName(), e.toString());
        }
    }

    private String joinUrl(String base, String path) {
        if (base == null) return path;
        if (path == null) return base;
        boolean bSlash = base.endsWith("/");
        boolean pSlash = path.startsWith("/");
        if (bSlash && pSlash) return base + path.substring(1);
        if (!bSlash && !pSlash) return base + "/" + path;
        return base + path;
    }

    private void performSignedAccountRequest() {
        if (baseUrl == null) {
            log.info("{} account request skipped: baseUrl not set", getName());
            return;
        }
        try {
            long ts = System.currentTimeMillis() + (authDetails.isEnableTimestamp() ? authDetails.getTimestampOffsetMs() : 0);
            String query = "timestamp=" + ts + "&recvWindow=" + recvWindowMs;
            String sig = hmacHex(authDetails.getSecretKey(), query, authDetails.getSignatureMethod());
            String url = joinUrl(baseUrl, "/api/v3/account?" + query + "&signature=" + sig);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(connectTimeout)
                    .header("X-MBX-APIKEY", authDetails.getApiKey())
                    .GET()
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> {
                        log.info("{} signed account status {}", getName(), resp.statusCode());
                        if (resp.statusCode() >= 400) {
                            log.warn("{} account error body: {}", getName(), trimBody(resp.body()));
                        }
                    })
                    .exceptionally(ex -> {
                        log.warn("{} signed account request failed: {}", getName(), ex.toString());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("{} signed account request exception: {}", getName(), e.toString());
        }
    }

    private String hmacHex(String secret, String data, String algo) throws Exception {
        String method = (algo != null && !algo.isEmpty()) ? algo : "HmacSHA256";
        Mac mac = Mac.getInstance(method);
        mac.init(new SecretKeySpec(secret.getBytes(), method));
        byte[] raw = mac.doFinal(data.getBytes());
        StringBuilder sb = new StringBuilder(raw.length * 2);
        for (byte b : raw) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private String trimBody(String body) {
        if (body == null) return "";
        return body.length() > 256 ? body.substring(0, 256) + "..." : body;
    }

    private void sendBinanceSubscriptions() {
        List<String> subs = getBinanceSubscriptions();
        if (subs.isEmpty()) {
            log.info("{} no subscriptions configured; skipping", getName());
            return;
        }
        String params = subs.stream().map(s -> "\"" + s + "\"").collect(Collectors.joining(","));
        String json = "{\"method\":\"SUBSCRIBE\",\"params\":[" + params + "],\"id\":1}";
        try {
            webSocket.sendText(json, true)
                    .thenAccept(ws -> log.info("{} sent WS SUBSCRIBE: {}", getName(), subs))
                    .exceptionally(ex -> {
                        log.warn("{} WS SUBSCRIBE failed: {}", getName(), ex.toString());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("{} WS SUBSCRIBE exception: {}", getName(), e.toString());
        }
    }

    private List<String> getBinanceSubscriptions() {
        Object v = customSettings.get("ws-subscriptions");
        List<String> subs = new ArrayList<>();
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o != null) subs.add(String.valueOf(o));
            }
        }
        if (!subs.isEmpty()) return subs;
        // default subscriptions based on supported symbols
        return getSupportedSymbols().stream()
                .map(Symbol::getName)
                .map(String::toLowerCase)
                .map(s -> s + "@trade")
                .collect(Collectors.toList());
    }

    private class AdapterWebSocketListener implements WebSocket.Listener {
        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            log.info("{} websocket onOpen", getName());
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            log.debug("{} websocket text: {}", getName(), data);
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
            log.info("{} websocket closed: {} {}", getName(), statusCode, reason);
            if (wsConfig != null && wsConfig.isAutoReconnect()) {
                scheduleWsReconnect();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.warn("{} websocket error: {}", getName(), error.toString());
            if (wsConfig != null && wsConfig.isAutoReconnect()) {
                scheduleWsReconnect();
            }
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public CompletableFuture<OrderResponse> submitOrder(OrderRequest request) {
        stats.recordRequest();
        // Skeleton: immediately accept without real execution
        OrderResponse resp = new OrderResponse(
            "BINANCE-" + System.currentTimeMillis(),
            request.clientOrderId(),
            OrderStatus.Status.NEW,
            "ACCEPTED",
            System.currentTimeMillis()
        );
        stats.recordOrderSubmitted();
        stats.recordOrderAccepted();
        if (orderUpdateHandler != null) {
            long priceScaled = request.price();
            long qtyScaled = request.quantity();
            BigDecimal priceDec = BigDecimal.valueOf(priceScaled).divide(BigDecimal.valueOf(100_000_000));
            BigDecimal qtyDec = BigDecimal.valueOf(qtyScaled).divide(BigDecimal.valueOf(100_000_000));
            orderUpdateHandler.onOrderUpdate(new OrderUpdate(
                resp.orderId(), request.clientOrderId(), request.symbol(), request.side(), request.type(),
                priceDec, qtyDec, qtyDec, priceDec, OrderStatus.Status.NEW, null,
                System.currentTimeMillis()
            ));
        }
        return CompletableFuture.completedFuture(resp);
    }

    @Override
    public CompletableFuture<CancelResponse> cancelOrder(String orderId) {
        stats.recordRequest();
        stats.recordOrderCancelled();
        CancelResponse resp = new CancelResponse(orderId, true, "CANCELLED", System.currentTimeMillis());
        if (orderUpdateHandler != null) {
            orderUpdateHandler.onOrderUpdate(new OrderUpdate(
                orderId, null, Symbol.btcUsdt(), null, null, null, null, null, null,
                OrderStatus.Status.CANCELED, null, System.currentTimeMillis()
            ));
        }
        return CompletableFuture.completedFuture(resp);
    }

    @Override
    public CompletableFuture<CancelResponse> cancelAllOrders(Symbol symbol) {
        stats.recordRequest();
        return CompletableFuture.completedFuture(new CancelResponse("ALL", true, "CANCELLED_ALL", System.currentTimeMillis()));
    }

    @Override
    public CompletableFuture<OrderStatus> getOrderStatus(String orderId) {
        stats.recordRequest();
        OrderStatus status = new OrderStatus(orderId, null, Symbol.btcUsdt(), null, null,
            null, null, null, null, OrderStatus.Status.NEW, System.currentTimeMillis(), System.currentTimeMillis());
        return CompletableFuture.completedFuture(status);
    }

    @Override
    public CompletableFuture<List<Balance>> getBalances() {
        stats.recordRequest();
        if (baseUrl == null || authDetails == null || !authDetails.isEnableSignature()) {
            return CompletableFuture.completedFuture(List.of());
        }
        try {
            long ts = System.currentTimeMillis() + (authDetails.isEnableTimestamp() ? authDetails.getTimestampOffsetMs() : 0);
            String query = "timestamp=" + ts + "&recvWindow=" + recvWindowMs + "&omitZeroBalances=true";
            String sig = hmacHex(authDetails.getSecretKey(), query, authDetails.getSignatureMethod());
            String url = joinUrl(baseUrl, "/api/v3/account?" + query + "&signature=" + sig);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(connectTimeout)
                    .header("X-MBX-APIKEY", authDetails.getApiKey())
                    .GET()
                    .build();
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(resp -> {
                        if (resp.statusCode() >= 400) {
                            log.warn("{} getBalances error: {}", getName(), trimBody(resp.body()));
                            return List.<Balance>of();
                        }
                        try {
                            JsonNode root = mapper.readTree(resp.body());
                            JsonNode arr = root.path("balances");
                            List<Balance> list = new java.util.ArrayList<>();
                            long now = System.currentTimeMillis();
                            if (arr.isArray()) {
                                for (JsonNode n : arr) {
                                    String asset = n.path("asset").asText("");
                                    java.math.BigDecimal free = new java.math.BigDecimal(n.path("free").asText("0"));
                                    java.math.BigDecimal locked = new java.math.BigDecimal(n.path("locked").asText("0"));
                                    java.math.BigDecimal total = free.add(locked);
                                    Balance b = new Balance(asset, free, locked, total, now);
                                    list.add(b);
                                    if (balanceUpdateHandler != null) {
                                        balanceUpdateHandler.onBalanceUpdate(new BalanceUpdate(asset, free, locked, total, now));
                                    }
                                }
                            }
                            return list;
                        } catch (Exception e) {
                            log.warn("{} parse balances failed: {}", getName(), e.toString());
                            return List.<Balance>of();
                        }
                    })
                    .exceptionally(ex -> {
                        log.warn("{} getBalances failed: {}", getName(), ex.toString());
                        return List.<Balance>of();
                    });
        } catch (Exception e) {
            log.warn("{} getBalances exception: {}", getName(), e.toString());
            return CompletableFuture.completedFuture(List.of());
        }
    }

    @Override
    public CompletableFuture<List<Position>> getPositions() {
        stats.recordRequest();
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<List<Order>> getOpenOrders(Symbol symbol) {
        stats.recordRequest();
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<List<Trade>> getTradeHistory(Symbol symbol, long fromTime, long toTime) {
        stats.recordRequest();
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public void subscribeOrderUpdates(OrderUpdateHandler handler) { this.orderUpdateHandler = handler; }

    @Override
    public void subscribeTradeUpdates(TradeUpdateHandler handler) { this.tradeUpdateHandler = handler; }

    @Override
    public void subscribeBalanceUpdates(BalanceUpdateHandler handler) { this.balanceUpdateHandler = handler; }

    @Override
    public void subscribePositionUpdates(PositionUpdateHandler handler) { this.positionUpdateHandler = handler; }

    @Override
    public List<Symbol> getSupportedSymbols() {
        return Arrays.asList(Symbol.btcUsdt(), Symbol.ethUsdt());
    }

    @Override
    public AdapterStats getStats() {
        return stats;
    }

    private void scheduleWsReconnect() {
        if (wsUrl == null || wsUrl.isEmpty()) return;
        AdapterConfig.WebSocketConfig wsc = wsConfig;
        if (wsc == null || !wsc.isAutoReconnect()) return;
        final int maxAttempts = Math.max(1, wsc.getMaxReconnectAttempts());
        final long initialDelayMs = Math.max(500, wsc.getReconnectDelayMs());
        CompletableFuture.runAsync(() -> {
            long delayMs = initialDelayMs;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {}
                try {
                    log.info("{} attempting WS reconnect {}/{}", getName(), attempt, maxAttempts);
                    connectWebSocket();
                    stats.recordWsReconnect();
                    return;
                } catch (Exception e) {
                    log.warn("{} WS reconnect attempt {} failed: {}", getName(), attempt, e.toString());
                }
                delayMs = Math.min(delayMs * 2, 30000);
            }
            log.warn("{} WS reconnect exhausted attempts", getName());
        });
    }
}