package com.bedrock.mm.md.providers.binance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Manages Binance Spot user data stream: obtain listenKey, connect WS, and keepalive.
 */
public class BinancePrivateFeed implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BinancePrivateFeed.class);

    private final BinancePrivateProperties props;
    private final Consumer<String> onMessage;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> keepAliveTask;

    private volatile String listenKey;
    private volatile BinancePrivateWebSocketClient legacyWsClient;
    private volatile BinanceUserDataWebSocketApiClient apiWsClient;

    public BinancePrivateFeed(BinancePrivateProperties props, Consumer<String> onMessage) {
        this.props = props;
        this.onMessage = onMessage;
    }

    /** Start user data stream: prefer WebSocket API; fallback to legacy listenKey WS. */
    public void start() {
        if (Boolean.TRUE.equals(props.isUseWsApi())) {
            // Connect to WebSocket API and start user data stream
            String endpoint = props.getWsApiUrl();
            apiWsClient = new BinanceUserDataWebSocketApiClient(endpoint, onMessage,
                    props.getReconnectIntervalSeconds(), props.getMaxReconnectAttempts(), props.getPingIntervalSeconds());
            apiWsClient.connect();
            log.info("Binance private user stream (WS-API) started. endpoint={}", endpoint);
        } else {
            // Legacy listenKey path
            ensureApiKey();
            createListenKey();
            String endpoint = props.getWsBase() + "/" + listenKey;
            legacyWsClient = new BinancePrivateWebSocketClient(endpoint, onMessage,
                    props.getReconnectIntervalSeconds(), props.getMaxReconnectAttempts());
            legacyWsClient.connect();
            scheduleKeepAlive();
            log.info("Binance private user stream (legacy listenKey) started. endpoint={}", endpoint);
        }
    }

    private void ensureApiKey() {
        if (props.getApiKey() == null || props.getApiKey().isBlank()) {
            throw new IllegalStateException("Binance private apiKey is required in configuration: bedrock.md.binance.private.apiKey");
        }
    }

    private void createListenKey() {
        try {
            String url = props.getRestBase() + "/api/v3/userDataStream";
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("X-MBX-APIKEY", props.getApiKey())
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) {
                throw new IllegalStateException("Create listenKey failed, status=" + resp.statusCode() + ", body=" + resp.body());
            }
            String body = resp.body();
            // expect {"listenKey":"..."}
            int i = body.indexOf("\"listenKey\":\"");
            if (i < 0) throw new IllegalStateException("listenKey not found in response: " + body);
            int j = i + "\"listenKey\":\"".length();
            int k = body.indexOf('"', j);
            listenKey = body.substring(j, k);
            log.info("Obtained listenKey for Binance private stream");
        } catch (Exception e) {
            throw new RuntimeException("Failed to create listenKey", e);
        }
    }

    private void scheduleKeepAlive() {
        int minutes = Math.max(1, props.getKeepAliveMinutes());
        // schedule a bit earlier to be safe
        int interval = Math.max(1, minutes - 5);
        keepAliveTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                keepAliveListenKey();
            } catch (Exception e) {
                log.warn("Keepalive listenKey failed", e);
            }
        }, interval, interval, TimeUnit.MINUTES);
    }

    private void keepAliveListenKey() throws Exception {
        if (listenKey == null) return;
        String url = props.getRestBase() + "/api/v3/userDataStream?listenKey=" + listenKey;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("X-MBX-APIKEY", props.getApiKey())
                .PUT(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            log.warn("Keepalive non-2xx status={}, body={} — will recreate listenKey", resp.statusCode(), resp.body());
            // fallback: recreate listenKey and reconnect
            recreateListenKeyAndReconnect();
        } else {
            log.debug("Keepalive success for listenKey");
        }
    }

    private void recreateListenKeyAndReconnect() {
        try {
            createListenKey();
            String endpoint = props.getWsBase() + "/" + listenKey;
            // Replace client to use new endpoint
            if (legacyWsClient != null) {
                try { legacyWsClient.close(); } catch (Exception ignore) {}
            }
            legacyWsClient = new BinancePrivateWebSocketClient(endpoint, onMessage,
                    props.getReconnectIntervalSeconds(), props.getMaxReconnectAttempts());
            legacyWsClient.connect();
            log.info("Reconnected Binance private user stream with new listenKey");
        } catch (Exception e) {
            log.error("Failed to recreate listenKey and reconnect", e);
        }
    }

    @Override
    public void close() {
        try {
            if (keepAliveTask != null) keepAliveTask.cancel(true);
        } catch (Exception ignore) {}
        try {
            scheduler.shutdownNow();
        } catch (Exception ignore) {}
        try {
            if (apiWsClient != null) {
                try { apiWsClient.stopStream(); } catch (Exception ignore) {}
                apiWsClient.close();
            }
        } catch (Exception e) {
            log.warn("Error closing WS-API client", e);
        }
        try {
            if (legacyWsClient != null) legacyWsClient.close();
        } catch (Exception e) {
            log.warn("Error closing legacy WS client", e);
        }
    }
}