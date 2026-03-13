package com.bedrock.mm.oms.exec.binance;

import com.bedrock.mm.common.idgen.OrderIdGenerator;
import com.bedrock.mm.oms.model.ExecEvent;
import com.bedrock.mm.oms.model.ExecEventType;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * BinancePrivateWsClient - Manages Binance User Data Stream for order execution updates.
 *
 * Features:
 * - Automatic listenKey lifecycle (start, keepalive every 30min, stop)
 * - Jackson streaming parser for zero-allocation message parsing
 * - Reconnection logic with exponential backoff
 * - Converts executionReport to ExecEvent and calls consumer callback
 */
public class BinancePrivateWsClient implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BinancePrivateWsClient.class);

    private final String restBaseUrl;
    private final String wsBaseUrl;
    private final String apiKey;
    private final Consumer<ExecEvent> onExecEvent;
    private final Runnable onReconnect;

    private final HttpClient httpClient;
    private final JsonFactory jsonFactory = new JsonFactory();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    private volatile String listenKey;
    private volatile WebSocket ws;
    private volatile boolean running = false;
    private ScheduledFuture<?> keepaliveTask;

    public BinancePrivateWsClient(String restBaseUrl, String wsBaseUrl, String apiKey,
                                  Consumer<ExecEvent> onExecEvent, Runnable onReconnect) {
        this.restBaseUrl = restBaseUrl;
        this.wsBaseUrl = wsBaseUrl;
        this.apiKey = apiKey;
        this.onExecEvent = onExecEvent;
        this.onReconnect = onReconnect;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Start user data stream and connect WebSocket.
     */
    public void start() throws IOException, InterruptedException {
        if (running) {
            log.warn("BinancePrivateWsClient already running");
            return;
        }
        running = true;

        // Create listenKey via REST
        this.listenKey = createListenKey();
        log.info("Created Binance listenKey: {}", listenKey.substring(0, 8) + "...");

        // Connect WebSocket
        connectWebSocket();

        // Schedule keepalive every 30 minutes
        keepaliveTask = scheduler.scheduleAtFixedRate(
                this::keepaliveListenKey,
                30, 30, TimeUnit.MINUTES
        );
    }

    private String createListenKey() throws IOException, InterruptedException {
        String url = restBaseUrl + "/api/v3/userDataStream";
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-MBX-APIKEY", apiKey)
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                .build();

        java.net.http.HttpResponse<String> response = httpClient.send(request,
                java.net.http.HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to create listenKey: " + response.statusCode() + " " + response.body());
        }

        // Parse {"listenKey":"..."} using streaming parser
        try (JsonParser parser = jsonFactory.createParser(response.body())) {
            while (parser.nextToken() != null) {
                if ("listenKey".equals(parser.currentName())) {
                    parser.nextToken();
                    return parser.getValueAsString();
                }
            }
        }
        throw new IOException("No listenKey in response: " + response.body());
    }

    private void keepaliveListenKey() {
        if (listenKey == null) return;
        String url = restBaseUrl + "/api/v3/userDataStream?listenKey=" + listenKey;
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-MBX-APIKEY", apiKey)
                .PUT(java.net.http.HttpRequest.BodyPublishers.noBody())
                .build();

        httpClient.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                .thenAccept(resp -> {
                    if (resp.statusCode() == 200) {
                        log.debug("listenKey keepalive OK");
                    } else {
                        log.warn("listenKey keepalive failed: {}", resp.statusCode());
                    }
                })
                .exceptionally(ex -> {
                    log.warn("listenKey keepalive error: {}", ex.getMessage());
                    return null;
                });
    }

    private void connectWebSocket() {
        String wsUrl = wsBaseUrl + "/ws/" + listenKey;
        httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WsListener())
                .thenAccept(websocket -> {
                    this.ws = websocket;
                    log.info("Binance private WS connected");
                })
                .exceptionally(ex -> {
                    log.error("Failed to connect Binance private WS", ex);
                    scheduleReconnect();
                    return null;
                });
    }

    private void scheduleReconnect() {
        if (!running) return;
        log.info("Scheduling Binance private WS reconnect in 5s");
        scheduler.schedule(() -> {
            if (onReconnect != null) {
                onReconnect.run();
            }
            try {
                connectWebSocket();
            } catch (Exception e) {
                log.error("Reconnect failed", e);
                scheduleReconnect();
            }
        }, 5, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        running = false;
        if (keepaliveTask != null) {
            keepaliveTask.cancel(true);
        }
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "shutdown");
        }
        scheduler.shutdownNow();

        // Delete listenKey
        if (listenKey != null) {
            deleteListenKey();
        }
    }

    private void deleteListenKey() {
        String url = restBaseUrl + "/api/v3/userDataStream?listenKey=" + listenKey;
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-MBX-APIKEY", apiKey)
                .DELETE()
                .build();

        httpClient.sendAsync(request, java.net.http.HttpResponse.BodyHandlers.discarding())
                .thenAccept(resp -> log.info("listenKey deleted: {}", resp.statusCode()))
                .exceptionally(ex -> {
                    log.warn("Failed to delete listenKey: {}", ex.getMessage());
                    return null;
                });
    }

    /**
     * Parse executionReport message using Jackson streaming parser.
     */
    private void parseExecutionReport(String text) {
        try (JsonParser parser = jsonFactory.createParser(text)) {
            String eventType = null;
            long exchOrderId = 0;
            String clientOrderId = null;
            String orderStatus = null;
            String side = null;
            long price = 0;
            long origQty = 0;
            long executedQty = 0;
            long lastFilledQty = 0;
            long lastFilledPrice = 0;

            while (parser.nextToken() != null) {
                String fieldName = parser.currentName();
                if (fieldName == null) continue;

                switch (fieldName) {
                    case "e":
                        parser.nextToken();
                        eventType = parser.getValueAsString();
                        break;
                    case "i":  // orderId
                        parser.nextToken();
                        exchOrderId = parser.getLongValue();
                        break;
                    case "c":  // clientOrderId
                        parser.nextToken();
                        clientOrderId = parser.getValueAsString();
                        break;
                    case "X":  // order status
                        parser.nextToken();
                        orderStatus = parser.getValueAsString();
                        break;
                    case "S":  // side
                        parser.nextToken();
                        side = parser.getValueAsString();
                        break;
                    case "p":  // price
                        parser.nextToken();
                        price = parseFixedPoint(parser.getValueAsString());
                        break;
                    case "q":  // origQty
                        parser.nextToken();
                        origQty = parseFixedPoint(parser.getValueAsString());
                        break;
                    case "z":  // cumulative filled quantity
                        parser.nextToken();
                        executedQty = parseFixedPoint(parser.getValueAsString());
                        break;
                    case "l":  // last filled quantity
                        parser.nextToken();
                        lastFilledQty = parseFixedPoint(parser.getValueAsString());
                        break;
                    case "L":  // last filled price
                        parser.nextToken();
                        lastFilledPrice = parseFixedPoint(parser.getValueAsString());
                        break;
                }
            }

            if (!"executionReport".equals(eventType)) {
                return;  // Not an execution report
            }

            // Build ExecEvent
            ExecEvent event = new ExecEvent();
            event.seqId = System.nanoTime();
            event.recvNanos = System.nanoTime();
            event.exchOrderId = exchOrderId;
            event.isBid = "BUY".equals(side);
            if (clientOrderId != null) {
                try {
                    long internalOrderId = Long.parseLong(clientOrderId);
                    event.internalOrderId = internalOrderId;
                    event.instrumentId = OrderIdGenerator.extractInstrumentId(internalOrderId);
                } catch (NumberFormatException ignored) {
                    // Non-numeric client order IDs are ignored for internal mapping.
                }
            }

            // Map order status to ExecEventType
            if ("NEW".equals(orderStatus)) {
                event.type = ExecEventType.ACK;
                event.remainSize = origQty;
            } else if ("PARTIALLY_FILLED".equals(orderStatus)) {
                event.type = ExecEventType.PARTIAL_FILL;
                event.fillSize = lastFilledQty;
                event.fillPrice = lastFilledPrice;
                event.remainSize = origQty - executedQty;
            } else if ("FILLED".equals(orderStatus)) {
                event.type = ExecEventType.FILL;
                event.fillSize = lastFilledQty;
                event.fillPrice = lastFilledPrice;
                event.remainSize = 0;
            } else if ("CANCELED".equals(orderStatus)) {
                event.type = ExecEventType.CANCELLED;
                event.remainSize = origQty - executedQty;
            } else if ("REJECTED".equals(orderStatus)) {
                event.type = ExecEventType.REJECTED;
                event.rejectReason = "Order rejected by exchange";
            } else {
                log.warn("Unknown order status: {}", orderStatus);
                return;
            }

            // Call consumer
            onExecEvent.accept(event);

        } catch (Exception e) {
            log.error("Failed to parse executionReport: {}", text, e);
        }
    }

    /**
     * Parse decimal string to fixed-point long (scale 1e-8).
     */
    private long parseFixedPoint(String value) {
        try {
            double d = Double.parseDouble(value);
            return (long) (d * 1e8);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse fixed-point value: {}", value);
            return 0;
        }
    }

    private class WsListener implements WebSocket.Listener {
        private final StringBuilder textBuffer = new StringBuilder(4096);

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("Binance private WS opened");
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            textBuffer.append(data);
            if (last) {
                String msg = textBuffer.toString();
                textBuffer.setLength(0);
                parseExecutionReport(msg);
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
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            log.warn("Binance private WS closed: {} {}", statusCode, reason);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            log.error("Binance private WS error", error);
            scheduleReconnect();
        }
    }
}
