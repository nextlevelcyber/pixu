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
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public class BitgetTradingAdapter implements TradingAdapter {

    private static final String ADAPTER_NAME = "Bitget";
    private static final String ADAPTER_VERSION = "0.1.0";
    private static final Logger log = LoggerFactory.getLogger(BitgetTradingAdapter.class);

    private AdapterConfig config;
    private final AdapterStats stats = new AdapterStats();
    private final AtomicBoolean connected = new AtomicBoolean(false);

    private AdapterConfig.AuthDetails authDetails;
    private Map<String, Object> customSettings;
    private String baseUrl;
    private String wsUrl;
    private String productType; // e.g., "mix"
    private String healthPath; // depends on product type
    private HttpClient httpClient;
    private WebSocket webSocket;
    private Duration connectTimeout = Duration.ofSeconds(10);
    private AdapterConfig.WebSocketConfig wsConfig;
    private final ObjectMapper mapper = new ObjectMapper();
    private int wsReconnectAttempts = 0;

    private OrderUpdateHandler orderUpdateHandler;
    private TradeUpdateHandler tradeUpdateHandler;
    private BalanceUpdateHandler balanceUpdateHandler;
    private PositionUpdateHandler positionUpdateHandler;
    private final Map<String, SubmittedOrderRef> ordersByClientOrderId = new ConcurrentHashMap<>();
    private final Map<String, SubmittedOrderRef> ordersByExchangeOrderId = new ConcurrentHashMap<>();

    @Override
    public String getName() {
        return ADAPTER_NAME;
    }

    @Override
    public String getVersion() {
        return ADAPTER_VERSION;
    }

    @Override
    public void initialize(AdapterConfig config) {
        this.config = config;
        String key = getName().toLowerCase();
        this.authDetails = config.getAuth() != null ? config.getAuth().getAuthFor(key) : null;
        this.customSettings = config.getCustomSettingsFor(key);
        if (this.customSettings == null) {
            this.customSettings = new HashMap<>();
        }
        Object bu = customSettings.getOrDefault("base-url", config.getConnection().getBaseUrl());
        Object wu = customSettings.getOrDefault("ws-url", config.getConnection().getWsUrl());
        Object pt = customSettings.getOrDefault("product-type", null);
        Object hp = customSettings.getOrDefault("health-path", null);
        this.baseUrl = bu != null ? String.valueOf(bu) : null;
        this.wsUrl = wu != null ? String.valueOf(wu) : null;
        this.productType = pt != null ? String.valueOf(pt) : null;
        if (hp != null) {
            this.healthPath = String.valueOf(hp);
        } else {
            // Default to time endpoint depending on product type
            this.healthPath = ("mix".equalsIgnoreCase(this.productType)) ? "/api/mix/v1/market/time" : "/api/spot/v1/market/time";
        }
        this.wsConfig = config.getWebSocket();
        long ctMs = Math.max(1000, config.getConnection().getConnectTimeoutMs());
        this.connectTimeout = Duration.ofMillis(ctMs);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        log.info("Initialized {} adapter v{} (env={}) baseUrl={} wsUrl={} productType={}", getName(), getVersion(), config.getEnvironment(), baseUrl, wsUrl, productType);
    }

    @Override
    public CompletableFuture<Void> connect() {
        stats.recordConnectAttempt();
        log.info("{} connecting (env={}) baseUrl={} wsUrl={} apiKeyPresent={}",
                getName(),
                config.getEnvironment(), baseUrl,
                wsUrl,
                authDetails != null && authDetails.getApiKey() != null && !authDetails.getApiKey().isEmpty());

        performRestHealthCheck();

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
            // Optional API key headers (Bitget public time does not require auth)
            if (authDetails != null && authDetails.getApiKey() != null && !authDetails.getApiKey().isEmpty()) {
                builder.header("ACCESS-KEY", authDetails.getApiKey());
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
            webSocket.sendPing(ByteBuffer.wrap("ping".getBytes()));
            log.info("{} websocket connected to {}", getName(), wsUrl);
            // Private WS login if enabled via config flag OR URL indicates private
            boolean wsLoginEnabled = false;
            try {
                Object v = (customSettings != null) ? customSettings.get("ws-login-enabled") : null;
                if (v != null) {
                    if (v instanceof Boolean) {
                        wsLoginEnabled = (Boolean) v;
                    } else {
                        wsLoginEnabled = Boolean.parseBoolean(String.valueOf(v));
                    }
                }
            } catch (Exception ignored) {
            }

            // Auto-login if private channels are present in ws-subscriptions
            boolean privateSubsPresent = hasPrivateWsSubscriptions();

            if (authDetails != null && authDetails.getApiKey() != null && !authDetails.getApiKey().isEmpty()
                    && authDetails.getSecretKey() != null && !authDetails.getSecretKey().isEmpty()
                    && (wsLoginEnabled || privateSubsPresent || (wsUrl != null && wsUrl.contains("/private")))) {
                sendBitgetWsLogin();
            }
            sendBitgetSubscriptions();
        } catch (Exception e) {
            log.warn("{} websocket connect failed: {}", getName(), e.toString());
        }
    }


    private void sendBitgetSubscriptions() {
        // If custom ws-subscriptions provided in config, use them
        Object subsObj = customSettings != null ? customSettings.get("ws-subscriptions") : null;
        if (subsObj instanceof List) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> argsList = (List<Map<String, Object>>) subsObj;
            String argsJson = argsList.stream()
                    .map(mapper::valueToTree)
                    .map(node -> node.toString())
                    .collect(Collectors.joining(","));
            String payload = "{\"op\":\"subscribe\",\"args\":[" + argsJson + "]}";
            try {
                webSocket.sendText(payload, true)
                        .thenAccept(ws -> log.info("{} sent WS custom subscriptions ({} args)", getName(), argsList.size()))
                        .exceptionally(ex -> {
                            log.warn("{} WS custom subscribe failed: {}", getName(), ex.toString());
                            return null;
                        });
            } catch (Exception e) {
                log.warn("{} WS custom subscribe exception: {}", getName(), e.toString());
            }
            return;
        }

        // Fallback to default: ticker on first two supported symbols
        List<String> symbols = getSupportedSymbols().stream().map(Symbol::getName).collect(Collectors.toList());
        if (symbols.isEmpty()) {
            log.info("{} no symbols for subscriptions; skipping", getName());
            return;
        }
        String instType = ("mix".equalsIgnoreCase(productType)) ? "UMCBL" : "SPOT";
        List<String> use = symbols.size() > 2 ? symbols.subList(0, 2) : symbols;
        String args = use.stream()
                .map(sym -> "{\"instType\":\"" + instType + "\",\"channel\":\"ticker\",\"instId\":\"" + sym + "\"}")
                .collect(Collectors.joining(","));
        String json = "{\"op\":\"subscribe\",\"args\":[" + args + "]}";
        try {
            webSocket.sendText(json, true)
                    .thenAccept(ws -> log.info("{} sent WS subscribe to ticker: {}", getName(), use))
                    .exceptionally(ex -> {
                        log.warn("{} WS subscribe failed: {}", getName(), ex.toString());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("{} WS subscribe exception: {}", getName(), e.toString());
        }
    }

    private void sendBitgetWsLogin() {
        try {
            long tsSec = System.currentTimeMillis() / 1000L;
            String prehash = tsSec + "GET" + "/user/verify";
            String sign = bitgetHmacBase64(authDetails.getSecretKey(), prehash, authDetails.getSignatureMethod());
            String payload = "{\"op\":\"login\",\"args\":[{\"apiKey\":\"" + authDetails.getApiKey() +
                    "\",\"passphrase\":\"" + (authDetails.getPassphrase() != null ? authDetails.getPassphrase() : "") +
                    "\",\"timestamp\":\"" + tsSec +
                    "\",\"sign\":\"" + sign + "\"}]}";
            webSocket.sendText(payload, true)
                    .thenAccept(ws -> log.info("{} sent WS login", getName()))
                    .exceptionally(ex -> {
                        log.warn("{} WS login failed: {}", getName(), ex.toString());
                        return null;
                    });
        } catch (Exception e) {
            log.warn("{} WS login exception: {}", getName(), e.toString());
        }
    }

    // Detect presence of private Bitget channels in custom ws-subscriptions
    // Private channels typically include: orders, positions, balance, account
    private boolean hasPrivateWsSubscriptions() {
        try {
            Object subsObj = customSettings != null ? customSettings.get("ws-subscriptions") : null;
            if (!(subsObj instanceof List<?>)) {
                return false;
            }
            List<?> argsList = (List<?>) subsObj;
            Set<String> privateChannels = Set.of("orders", "positions", "balance", "account");
            for (Object item : argsList) {
                if (item instanceof Map<?, ?>) {
                    Map<?, ?> m = (Map<?, ?>) item;
                    Object ch = m.get("channel");
                    if (ch != null && privateChannels.contains(String.valueOf(ch))) {
                        return true;
                    }
                } else if (item instanceof String) {
                    String s = (String) item;
                    String channel = s.contains(":") ? s.substring(0, s.indexOf(':')) : s;
                    if (privateChannels.contains(channel)) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return false;
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
        stats.recordOrderSubmitted();
        if (!hasTradingCredentials() || baseUrl == null || baseUrl.isEmpty()) {
            return CompletableFuture.completedFuture(rejectOrder(request, "Bitget credentials or baseUrl not configured"));
        }

        String requestPath = "/api/v3/trade/place-order";
        long ts = System.currentTimeMillis();
        Map<String, Object> payload = new HashMap<>();
        payload.put("symbol", request.symbol().getName());
        payload.put("side", toBitgetSide(request.side()));
        payload.put("orderType", toBitgetOrderType(request.type()));
        payload.put("qty", scaledDecimal(request.quantity()).toPlainString());
        payload.put("clientOid", request.clientOrderId());
        String category = resolveUtaCategory(request.symbol());
        if (category != null && !category.isEmpty()) {
            payload.put("category", category);
        }
        if (request.type() == OrderType.LIMIT) {
            payload.put("price", scaledDecimal(request.price()).toPlainString());
            String force = toBitgetTimeInForce(request.timeInForce());
            if (force != null && !force.isEmpty()) {
                payload.put("force", force);
            }
        }

        try {
            String body = mapper.writeValueAsString(payload);
            HttpRequest httpRequest = buildBitgetSignedPost(requestPath, body, ts);
            long startNs = System.nanoTime();
            return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> mapSubmitOrderResponse(request, category, startNs, response))
                    .exceptionally(ex -> rejectOrder(request, "Bitget place-order failed: " + ex.getMessage()));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(rejectOrder(request, "Bitget place-order build failed: " + e.getMessage()));
        }
    }

    @Override
    public CompletableFuture<CancelResponse> cancelOrder(String orderId) {
        stats.recordRequest();
        SubmittedOrderRef ref = resolveOrderRef(orderId);
        if (!hasTradingCredentials() || baseUrl == null || baseUrl.isEmpty()) {
            return CompletableFuture.completedFuture(new CancelResponse(orderId, false, "Bitget credentials or baseUrl not configured", System.currentTimeMillis()));
        }
        String requestPath = "/api/v3/trade/cancel-order";
        Map<String, Object> payload = new HashMap<>();
        if (ref != null) {
            if (ref.orderId() != null && !ref.orderId().isEmpty()) {
                payload.put("orderId", ref.orderId());
            } else {
                payload.put("clientOid", ref.clientOrderId());
            }
            payload.put("symbol", ref.symbol().getName());
            if (ref.category() != null && !ref.category().isEmpty()) {
                payload.put("category", ref.category());
            }
        } else if (looksLikeBitgetOrderId(orderId)) {
            payload.put("orderId", orderId);
        } else {
            payload.put("clientOid", orderId);
        }

        try {
            String body = mapper.writeValueAsString(payload);
            HttpRequest httpRequest = buildBitgetSignedPost(requestPath, body, System.currentTimeMillis());
            return httpClient.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> mapCancelOrderResponse(orderId, ref, response))
                    .exceptionally(ex -> new CancelResponse(orderId, false, "Bitget cancel failed: " + ex.getMessage(), System.currentTimeMillis()));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(new CancelResponse(orderId, false, "Bitget cancel build failed: " + e.getMessage(), System.currentTimeMillis()));
        }
    }

    @Override
    public CompletableFuture<CancelResponse> cancelAllOrders(Symbol symbol) {
        stats.recordRequest();
        return CompletableFuture.completedFuture(new CancelResponse("ALL", true, "CANCELLED_ALL", System.currentTimeMillis()));
    }

    @Override
    public CompletableFuture<OrderStatus> getOrderStatus(String orderId) {
        stats.recordRequest();
        SubmittedOrderRef ref = resolveOrderRef(orderId);
        Symbol symbol = ref != null ? ref.symbol() : Symbol.btcUsdt();
        String clientOrderId = ref != null ? ref.clientOrderId() : null;
        String exchangeOrderId = ref != null && ref.orderId() != null ? ref.orderId() : orderId;
        BigDecimal price = ref != null ? ref.price() : null;
        BigDecimal quantity = ref != null ? ref.quantity() : null;
        OrderStatus.Status currentStatus = ref != null ? ref.status() : OrderStatus.Status.NEW;
        OrderStatus status = new OrderStatus(exchangeOrderId, clientOrderId, symbol, ref != null ? ref.side() : null, ref != null ? ref.type() : null,
                price, quantity, quantity, price, currentStatus, System.currentTimeMillis(), System.currentTimeMillis());
        return CompletableFuture.completedFuture(status);
    }

    @Override
    public CompletableFuture<List<Balance>> getBalances() {
        stats.recordRequest();
        List<Balance> results = new ArrayList<>();
        if (baseUrl == null) {
            log.info("{} balances skipped: baseUrl not set", getName());
            return CompletableFuture.completedFuture(List.of());
        }
        if (authDetails == null || authDetails.getApiKey() == null || authDetails.getSecretKey() == null || authDetails.getPassphrase() == null) {
            log.info("{} balances skipped: missing API credentials", getName());
            return CompletableFuture.completedFuture(List.of());
        }

        String path = "/api/v2/spot/account/assets";
        Map<String, String> query = new HashMap<>(); // no required params
        long ts = System.currentTimeMillis();
        try {
            HttpRequest request = buildBitgetSignedGet(path, query, ts);
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(resp -> {
                        if (resp.statusCode() >= 400) {
                            log.warn("{} balances error {} -> {}", getName(), resp.statusCode(), trimBody(resp.body()));
                            return List.<Balance>of();
                        }
                        try {
                            JsonNode root = mapper.readTree(resp.body());
                            JsonNode data = root.get("data");
                            if (data != null && data.isArray()) {
                                for (JsonNode node : data) {
                                    String coin = asText(node, "coin", "");
                                    BigDecimal available = asBigDec(node, "available");
                                    BigDecimal locked = asBigDec(node, "locked");
                                    BigDecimal frozen = asBigDec(node, "frozen");
                                    long uTime = asLong(node, "uTime", System.currentTimeMillis());
                                    BigDecimal total = safe(available).add(safe(locked)).add(safe(frozen));
                                    Balance b = new Balance(coin, safe(available), safe(locked), total, uTime);
                                    results.add(b);
                                    if (balanceUpdateHandler != null) {
                                        balanceUpdateHandler.onBalanceUpdate(new BalanceUpdate(coin, safe(available), safe(locked), total, uTime));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.warn("{} balances parse error: {}", getName(), e.toString());
                        }
                        return results;
                    })
                    .exceptionally(ex -> {
                        log.warn("{} balances request failed: {}", getName(), ex.toString());
                        return List.of();
                    });
        } catch (Exception e) {
            log.warn("{} balances build request exception: {}", getName(), e.toString());
            return CompletableFuture.completedFuture(List.of());
        }
    }

    @Override
    public CompletableFuture<List<Position>> getPositions() {
        stats.recordRequest();
        List<Position> results = new ArrayList<>();
        if (baseUrl == null) {
            log.info("{} positions skipped: baseUrl not set", getName());
            return CompletableFuture.completedFuture(List.of());
        }
        if (authDetails == null || authDetails.getApiKey() == null || authDetails.getSecretKey() == null || authDetails.getPassphrase() == null) {
            log.info("{} positions skipped: missing API credentials", getName());
            return CompletableFuture.completedFuture(List.of());
        }

        String path = "/api/v2/mix/position/all-position";
        String productTypeParam = resolveProductTypeParam();
        String marginCoin = String.valueOf(customSettings.getOrDefault("margin-coin", "USDT"));
        Map<String, String> query = new HashMap<>();
        query.put("productType", productTypeParam);
        query.put("marginCoin", marginCoin);
        long ts = System.currentTimeMillis();
        try {
            HttpRequest request = buildBitgetSignedGet(path, query, ts);
            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(resp -> {
                        if (resp.statusCode() >= 400) {
                            log.warn("{} positions error {} -> {}", getName(), resp.statusCode(), trimBody(resp.body()));
                            return List.<Position>of();
                        }
                        try {
                            JsonNode root = mapper.readTree(resp.body());
                            JsonNode data = root.get("data");
                            if (data != null && data.isArray()) {
                                for (JsonNode p : data) {
                                    String symStr = asText(p, "symbol", "");
                                    Symbol sym = Symbol.of(symStr);
                                    BigDecimal size = asBigDec(p, "total");
                                    BigDecimal avgPrice = asBigDec(p, "openPriceAvg");
                                    BigDecimal markPrice = asBigDec(p, "markPrice");
                                    BigDecimal notional = safe(size).multiply(safe(markPrice));
                                    BigDecimal unrealized = asBigDec(p, "unrealizedPL");
                                    BigDecimal realized = asBigDec(p, "achievedProfits");
                                    long tsOut = asLong(p, "uTime", System.currentTimeMillis());
                                    Position pos = new Position(sym, safe(size), safe(notional), safe(avgPrice), safe(unrealized), safe(realized), tsOut);
                                    results.add(pos);
                                    if (positionUpdateHandler != null) {
                                        PositionUpdate pu = new PositionUpdate(sym, safe(size), safe(notional), safe(avgPrice), safe(unrealized), safe(realized), tsOut);
                                        positionUpdateHandler.onPositionUpdate(pu);
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.warn("{} positions parse error: {}", getName(), e.toString());
                        }
                        return results;
                    })
                    .exceptionally(ex -> {
                        log.warn("{} positions request failed: {}", getName(), ex.toString());
                        return List.of();
                    });
        } catch (Exception e) {
            log.warn("{} positions build request exception: {}", getName(), e.toString());
            return CompletableFuture.completedFuture(List.of());
        }
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
    public void subscribeOrderUpdates(OrderUpdateHandler handler) {
        this.orderUpdateHandler = handler;
    }

    @Override
    public void subscribeTradeUpdates(TradeUpdateHandler handler) {
        this.tradeUpdateHandler = handler;
    }

    @Override
    public void subscribeBalanceUpdates(BalanceUpdateHandler handler) {
        this.balanceUpdateHandler = handler;
    }

    @Override
    public void subscribePositionUpdates(PositionUpdateHandler handler) {
        this.positionUpdateHandler = handler;
    }

    @Override
    public List<Symbol> getSupportedSymbols() {
        return Arrays.asList(Symbol.btcUsdt(), Symbol.ethUsdt());
    }

    @Override
    public AdapterStats getStats() {
        return stats;
    }

    // ========================= Helpers =========================

    private String joinUrl(String base, String path) {
        if (base == null) return path;
        if (path == null) return base;
        boolean bSlash = base.endsWith("/");
        boolean pSlash = path.startsWith("/");
        if (bSlash && pSlash) return base + path.substring(1);
        if (!bSlash && !pSlash) return base + "/" + path;
        return base + path;
    }

    private String bitgetHmacBase64(String secretKey, String preHash, String algo) {
        try {
            String method = (algo != null && !algo.isEmpty()) ? algo : "HmacSHA256";
            Mac mac = Mac.getInstance(method);
            mac.init(new SecretKeySpec(secretKey.getBytes(), method));
            byte[] raw = mac.doFinal(preHash.getBytes());
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException("Bitget HMAC error: " + e.getMessage(), e);
        }
    }

    private HttpRequest buildBitgetSignedGet(String requestPath, Map<String, String> query, long ts) {
        String queryString = buildQueryString(query);
        String prehash = ts + "GET" + requestPath + (queryString.isEmpty() ? "" : ("?" + queryString));
        String sign = bitgetHmacBase64(authDetails.getSecretKey(), prehash, authDetails.getSignatureMethod());
        String url = joinUrl(baseUrl, requestPath) + (queryString.isEmpty() ? "" : ("?" + queryString));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(connectTimeout)
                .header("ACCESS-KEY", authDetails.getApiKey())
                .header("ACCESS-SIGN", sign)
                .header("ACCESS-TIMESTAMP", String.valueOf(ts))
                .header("ACCESS-PASSPHRASE", authDetails.getPassphrase())
                .header("Content-Type", "application/json")
                .GET();
        applyDemoTradingHeader(builder);
        return builder.build();
    }

    private HttpRequest buildBitgetSignedPost(String requestPath, String body, long ts) {
        String prehash = ts + "POST" + requestPath + body;
        String sign = bitgetHmacBase64(authDetails.getSecretKey(), prehash, authDetails.getSignatureMethod());
        String url = joinUrl(baseUrl, requestPath);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(connectTimeout)
                .header("ACCESS-KEY", authDetails.getApiKey())
                .header("ACCESS-SIGN", sign)
                .header("ACCESS-TIMESTAMP", String.valueOf(ts))
                .header("ACCESS-PASSPHRASE", authDetails.getPassphrase())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        applyDemoTradingHeader(builder);
        return builder.build();
    }

    private String buildQueryString(Map<String, String> query) {
        if (query == null || query.isEmpty()) return "";
        return query.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("&"));
    }

    private String trimBody(String body) {
        if (body == null) return "";
        return body.length() > 512 ? body.substring(0, 512) + "..." : body;
    }

    private String asText(JsonNode node, String field, String def) {
        try {
            JsonNode n = node.get(field);
            return n != null ? n.asText() : def;
        } catch (Exception e) {
            return def;
        }
    }

    private BigDecimal asBigDec(JsonNode node, String field) {
        try {
            JsonNode n = node.get(field);
            return (n != null) ? new BigDecimal(n.asText("0")) : BigDecimal.ZERO;
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private long asLong(JsonNode node, String field, long def) {
        try {
            JsonNode n = node.get(field);
            return n != null ? Long.parseLong(n.asText(String.valueOf(def))) : def;
        } catch (Exception e) {
            return def;
        }
    }

    private BigDecimal safe(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private BigDecimal scaledDecimal(long scaledValue) {
        return BigDecimal.valueOf(scaledValue, 8).stripTrailingZeros();
    }

    private boolean hasTradingCredentials() {
        return authDetails != null
                && authDetails.getApiKey() != null
                && !authDetails.getApiKey().isEmpty()
                && authDetails.getSecretKey() != null
                && !authDetails.getSecretKey().isEmpty()
                && authDetails.getPassphrase() != null
                && !authDetails.getPassphrase().isEmpty();
    }

    private void applyDemoTradingHeader(HttpRequest.Builder builder) {
        if (builder != null && isDemoTradingEnabled()) {
            builder.header("paptrading", "1");
        }
    }

    private boolean isDemoTradingEnabled() {
        Object configured = customSettings.get("pap-trading");
        if (configured != null) {
            return Boolean.parseBoolean(String.valueOf(configured));
        }
        return config != null && "sandbox".equalsIgnoreCase(config.getEnvironment());
    }

    private String toBitgetSide(com.bedrock.mm.common.model.Side side) {
        return side == com.bedrock.mm.common.model.Side.SELL ? "sell" : "buy";
    }

    private String toBitgetOrderType(OrderType type) {
        return type == OrderType.MARKET ? "market" : "limit";
    }

    private String toBitgetTimeInForce(TimeInForce timeInForce) {
        if (timeInForce == null) {
            return "gtc";
        }
        return switch (timeInForce) {
            case IOC -> "ioc";
            case FOK -> "fok";
            default -> "gtc";
        };
    }

    private String resolveUtaCategory(Symbol symbol) {
        Object category = customSettings.get("uta-category");
        if (category != null) {
            return String.valueOf(category);
        }
        String configuredProductType = customSettings.getOrDefault("product-type", productType) != null
                ? String.valueOf(customSettings.getOrDefault("product-type", productType))
                : "";
        if ("spot".equalsIgnoreCase(configuredProductType)) {
            return "spot";
        }
        if (symbol != null && symbol.getName() != null && symbol.getName().contains("_")) {
            return "usdt-futures";
        }
        return "spot";
    }

    private OrderResponse mapSubmitOrderResponse(OrderRequest request, String category, long startNs, HttpResponse<String> response) {
        long tsMs = System.currentTimeMillis();
        stats.recordLatency((System.nanoTime() - startNs) / 1_000_000.0);
        if (response.statusCode() >= 400) {
            stats.recordFailedRequest();
            stats.recordClientError();
            return rejectOrder(request, "Bitget place-order HTTP " + response.statusCode() + ": " + trimBody(response.body()));
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            String code = asText(root, "code", "");
            String msg = asText(root, "msg", "");
            if (!"00000".equals(code)) {
                stats.recordFailedRequest();
                if (response.statusCode() == 429) {
                    stats.recordRateLimitedRequest();
                }
                return rejectOrder(request, "Bitget place-order rejected: " + code + " " + msg);
            }
            stats.recordSuccessfulRequest();
            stats.recordOrderAccepted();
            JsonNode data = root.get("data");
            String exchangeOrderId = data != null ? asText(data, "orderId", "") : "";
            if (exchangeOrderId == null || exchangeOrderId.isEmpty()) {
                exchangeOrderId = request.clientOrderId();
            }
            SubmittedOrderRef ref = new SubmittedOrderRef(
                    request.clientOrderId(),
                    exchangeOrderId,
                    request.symbol(),
                    request.side(),
                    request.type(),
                    scaledDecimal(request.price()),
                    scaledDecimal(request.quantity()),
                    category,
                    OrderStatus.Status.NEW
            );
            indexOrder(ref);
            emitOrderUpdate(ref, OrderStatus.Status.NEW, null);
            return new OrderResponse(exchangeOrderId, request.clientOrderId(), OrderStatus.Status.NEW, msg.isEmpty() ? "ACCEPTED" : msg, tsMs);
        } catch (Exception e) {
            stats.recordFailedRequest();
            return rejectOrder(request, "Bitget place-order parse failed: " + e.getMessage());
        }
    }

    private CancelResponse mapCancelOrderResponse(String requestedId, SubmittedOrderRef ref, HttpResponse<String> response) {
        long tsMs = System.currentTimeMillis();
        if (response.statusCode() >= 400) {
            stats.recordFailedRequest();
            return new CancelResponse(requestedId, false, "Bitget cancel HTTP " + response.statusCode() + ": " + trimBody(response.body()), tsMs);
        }
        try {
            JsonNode root = mapper.readTree(response.body());
            String code = asText(root, "code", "");
            String msg = asText(root, "msg", "");
            if (!"00000".equals(code)) {
                stats.recordFailedRequest();
                return new CancelResponse(requestedId, false, "Bitget cancel rejected: " + code + " " + msg, tsMs);
            }
            stats.recordSuccessfulRequest();
            stats.recordOrderCancelled();
            SubmittedOrderRef cancelledRef = ref != null ? ref.withStatus(OrderStatus.Status.CANCELED) : null;
            if (cancelledRef != null) {
                indexOrder(cancelledRef);
                emitOrderUpdate(cancelledRef, OrderStatus.Status.CANCELED, null);
            } else if (orderUpdateHandler != null) {
                orderUpdateHandler.onOrderUpdate(new OrderUpdate(
                        requestedId, requestedId, Symbol.btcUsdt(), null, null, null, null, null, null,
                        OrderStatus.Status.CANCELED, null, tsMs
                ));
            }
            return new CancelResponse(requestedId, true, msg.isEmpty() ? "CANCELLED" : msg, tsMs);
        } catch (Exception e) {
            stats.recordFailedRequest();
            return new CancelResponse(requestedId, false, "Bitget cancel parse failed: " + e.getMessage(), tsMs);
        }
    }

    private OrderResponse rejectOrder(OrderRequest request, String reason) {
        long tsMs = System.currentTimeMillis();
        stats.recordFailedRequest();
        stats.recordOrderRejected();
        SubmittedOrderRef ref = new SubmittedOrderRef(
                request.clientOrderId(),
                request.clientOrderId(),
                request.symbol(),
                request.side(),
                request.type(),
                scaledDecimal(request.price()),
                scaledDecimal(request.quantity()),
                resolveUtaCategory(request.symbol()),
                OrderStatus.Status.REJECTED
        );
        indexOrder(ref);
        emitOrderUpdate(ref, OrderStatus.Status.REJECTED, reason);
        return new OrderResponse(request.clientOrderId(), request.clientOrderId(), OrderStatus.Status.REJECTED, reason, tsMs);
    }

    private void emitOrderUpdate(SubmittedOrderRef ref, OrderStatus.Status status, String reason) {
        if (orderUpdateHandler == null || ref == null) {
            return;
        }
        orderUpdateHandler.onOrderUpdate(new OrderUpdate(
                ref.orderId(),
                ref.clientOrderId(),
                ref.symbol(),
                ref.side(),
                ref.type(),
                ref.price(),
                ref.quantity(),
                ref.quantity(),
                ref.price(),
                status,
                reason,
                System.currentTimeMillis()
        ));
    }

    private void indexOrder(SubmittedOrderRef ref) {
        if (ref == null) {
            return;
        }
        if (ref.clientOrderId() != null && !ref.clientOrderId().isEmpty()) {
            ordersByClientOrderId.put(ref.clientOrderId(), ref);
        }
        if (ref.orderId() != null && !ref.orderId().isEmpty()) {
            ordersByExchangeOrderId.put(ref.orderId(), ref);
        }
    }

    private SubmittedOrderRef resolveOrderRef(String orderId) {
        if (orderId == null || orderId.isEmpty()) {
            return null;
        }
        SubmittedOrderRef byExchangeId = ordersByExchangeOrderId.get(orderId);
        if (byExchangeId != null) {
            return byExchangeId;
        }
        return ordersByClientOrderId.get(orderId);
    }

    private boolean looksLikeBitgetOrderId(String value) {
        return value != null && value.chars().allMatch(Character::isDigit);
    }

    private String resolveProductTypeParam() {
        Object ptObj = customSettings != null ? customSettings.get("product-type") : null;
        if (ptObj != null) {
            String val = String.valueOf(ptObj).trim();
            if (!val.isEmpty()) return val;
        }
        // Derive from instType if possible
        if ("mix".equalsIgnoreCase(productType)) return "USDT-FUTURES"; // default
        return "USDT-FUTURES";
    }

    private void scheduleWsReconnect() {
        try {
            int maxAttempts = Math.max(1, wsConfig.getMaxReconnectAttempts());
            if (wsReconnectAttempts >= maxAttempts) {
                log.warn("{} WS reconnect attempts exhausted ({} max)", getName(), maxAttempts);
                return;
            }
            wsReconnectAttempts++;
            long baseDelay = Math.max(1000, wsConfig.getReconnectDelayMs());
            long delay = Math.min(baseDelay * wsReconnectAttempts, 30000); // cap at 30s
            log.info("{} scheduling WS reconnect attempt {} in {} ms", getName(), wsReconnectAttempts, delay);
            stats.recordWsReconnect();
            new Thread(() -> {
                try {
                    Thread.sleep(delay);
                    connectWebSocket();
                } catch (InterruptedException ignored) {
                }
            }, "bitget-ws-reconnect").start();
        } catch (Exception e) {
            log.warn("{} scheduleWsReconnect error: {}", getName(), e.toString());
        }
    }

    private record SubmittedOrderRef(
            String clientOrderId,
            String orderId,
            Symbol symbol,
            com.bedrock.mm.common.model.Side side,
            OrderType type,
            BigDecimal price,
            BigDecimal quantity,
            String category,
            OrderStatus.Status status
    ) {
        private SubmittedOrderRef withStatus(OrderStatus.Status nextStatus) {
            return new SubmittedOrderRef(clientOrderId, orderId, symbol, side, type, price, quantity, category, nextStatus);
        }
    }
}
