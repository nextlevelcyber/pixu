package com.bedrock.mm.oms.exec.binance;

import com.bedrock.mm.common.idgen.OrderIdGenerator;
import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.oms.exec.ExecGateway;
import com.bedrock.mm.oms.exec.RestReconciliation;
import com.bedrock.mm.oms.model.ExecEvent;
import com.bedrock.mm.oms.model.Order;
import com.bedrock.mm.oms.model.OrderState;
import com.bedrock.mm.oms.store.OrderStore;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * BinanceExecGateway - Binance implementation of ExecGateway.
 *
 * Features:
 * - REST order placement (POST /api/v3/order)
 * - REST order cancellation (DELETE /api/v3/order)
 * - Private WebSocket for execution updates
 * - REST reconciliation on reconnect
 * - HMAC-SHA256 signature for authenticated requests
 */
public class BinanceExecGateway implements ExecGateway {
    private static final Logger log = LoggerFactory.getLogger(BinanceExecGateway.class);
    private static final int EXEC_EVENT_QUEUE_CAPACITY = 4096;

    private final String restBaseUrl;
    private final String wsBaseUrl;
    private final String apiKey;
    private final String secretKey;
    private final int gatewayInstrumentId;
    private final String symbol;
    private final OrderStore orderStore;
    private final Consumer<ExecEvent> execEventConsumer;
    private final RestReconciliation reconciliation;
    private final JsonFactory jsonFactory = new JsonFactory();
    private final ThreadPoolExecutor execEventDispatcher;
    private final AtomicLong dispatchedExecEvents = new AtomicLong(0L);
    private final AtomicLong droppedExecEvents = new AtomicLong(0L);

    private final HttpClient httpClient;
    private final OrderIdGenerator orderIdGenerator;
    private volatile boolean shuttingDown;
    private BinancePrivateWsClient privateWsClient;

    public BinanceExecGateway(String restBaseUrl, String wsBaseUrl, String apiKey, String secretKey,
                              OrderStore orderStore, Consumer<ExecEvent> execEventConsumer,
                              int instrumentId) {
        this(restBaseUrl, wsBaseUrl, apiKey, secretKey, orderStore, execEventConsumer,
                instrumentId, defaultSymbolForInstrument(instrumentId));
    }

    public BinanceExecGateway(String restBaseUrl, String wsBaseUrl, String apiKey, String secretKey,
                              OrderStore orderStore, Consumer<ExecEvent> execEventConsumer,
                              int instrumentId, String symbol) {
        this.restBaseUrl = restBaseUrl;
        this.wsBaseUrl = wsBaseUrl;
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.gatewayInstrumentId = instrumentId;
        this.symbol = (symbol == null || symbol.isBlank())
                ? defaultSymbolForInstrument(instrumentId)
                : symbol.toUpperCase(Locale.ROOT);
        this.orderStore = orderStore;
        this.execEventConsumer = execEventConsumer;
        this.reconciliation = new RestReconciliation();
        this.orderIdGenerator = new OrderIdGenerator(instrumentId);
        this.execEventDispatcher = new ThreadPoolExecutor(
                1, 1,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(EXEC_EVENT_QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "binance-exec-dispatch-" + instrumentId);
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.execEventDispatcher.prestartAllCoreThreads();

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Start the gateway (connect private WebSocket).
     */
    public void start() {
        try {
            privateWsClient = new BinancePrivateWsClient(
                    restBaseUrl, wsBaseUrl, apiKey,
                    this::handleExecEvent,
                    this::onPrivateWsReconnect
            );
            privateWsClient.start();
            log.info("BinanceExecGateway started");
        } catch (Exception e) {
            log.error("Failed to start BinanceExecGateway", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void placeOrder(int instrumentId, long price, long size, boolean isBid, int regionIndex) {
        placeOrder(instrumentId, price, size, isBid, regionIndex, 0L, 0L);
    }

    @Override
    public void placeOrder(int instrumentId, long price, long size, boolean isBid, int regionIndex,
                           long quotePublishNanos, long quoteSeqId) {
        try {
            Side side = isBid ? Side.BUY : Side.SELL;
            long orderId = orderIdGenerator.nextId(side);

            // Create Order object and add to store
            Order order = new Order();
            order.orderId = orderId;
            order.exchOrderId = 0;  // Will be updated on ACK
            order.instrumentId = instrumentId;
            order.price = price;
            order.origSize = size;
            order.filledSize = 0;
            order.fillValueSum = 0;
            order.state = OrderState.PENDING_NEW;
            order.regionIndex = regionIndex;
            order.isBid = isBid;
            order.createNanos = System.nanoTime();
            order.quotePublishNanos = quotePublishNanos;
            order.quoteSeqId = quoteSeqId;

            synchronized (orderStore) {
                orderStore.addOrder(order);
            }

            // Build REST request
            String symbol = resolveSymbol(instrumentId);
            String priceStr = formatPrice(price);
            String qtyStr = formatQuantity(size);
            String sideStr = isBid ? "BUY" : "SELL";

            StringBuilder queryString = new StringBuilder();
            queryString.append("symbol=").append(URLEncoder.encode(symbol, StandardCharsets.UTF_8));
            queryString.append("&side=").append(sideStr);
            queryString.append("&type=LIMIT");
            queryString.append("&timeInForce=GTC");
            queryString.append("&price=").append(priceStr);
            queryString.append("&quantity=").append(qtyStr);
            queryString.append("&newClientOrderId=").append(orderId);
            queryString.append("&timestamp=").append(System.currentTimeMillis());
            queryString.append("&recvWindow=5000");

            String signature = signQueryString(queryString.toString());
            queryString.append("&signature=").append(signature);

            String url = restBaseUrl + "/api/v3/order?" + queryString;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-MBX-APIKEY", apiKey)
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> {
                        if (resp.statusCode() == 200) {
                            log.info("Order placed: orderId={} exchResp={}", orderId, resp.body());
                        } else {
                            log.error("Order placement failed: orderId={} status={} body={}",
                                    orderId, resp.statusCode(), resp.body());
                            // Generate synthetic REJECTED event
                            ExecEvent event = new ExecEvent();
                            event.seqId = System.nanoTime();
                            event.recvNanos = System.nanoTime();
                            event.instrumentId = instrumentId;
                            event.internalOrderId = orderId;
                            event.exchOrderId = 0;
                            event.type = com.bedrock.mm.oms.model.ExecEventType.REJECTED;
                            event.rejectReason = "REST placement failed: " + resp.statusCode();
                            event.isBid = isBid;
                            dispatchExecEvent(event);
                        }
                    })
                    .exceptionally(ex -> {
                        log.error("Order placement exception: orderId={}", orderId, ex);
                        return null;
                    });

        } catch (Exception e) {
            log.error("Failed to place order", e);
        }
    }

    @Override
    public void cancelOrder(int instrumentId, long orderId) {
        try {
            Order order;
            synchronized (orderStore) {
                order = orderStore.getOrder(orderId);
            }
            if (order == null) {
                log.warn("Cannot cancel unknown order: {}", orderId);
                return;
            }

            if (order.exchOrderId == 0) {
                log.warn("Cannot cancel order without exchOrderId: {}", orderId);
                return;
            }

            String symbol = resolveSymbol(instrumentId);

            StringBuilder queryString = new StringBuilder();
            queryString.append("symbol=").append(URLEncoder.encode(symbol, StandardCharsets.UTF_8));
            queryString.append("&orderId=").append(order.exchOrderId);
            queryString.append("&timestamp=").append(System.currentTimeMillis());
            queryString.append("&recvWindow=5000");

            String signature = signQueryString(queryString.toString());
            queryString.append("&signature=").append(signature);

            String url = restBaseUrl + "/api/v3/order?" + queryString;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("X-MBX-APIKEY", apiKey)
                    .DELETE()
                    .build();

            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(resp -> {
                        if (resp.statusCode() == 200) {
                            log.info("Order cancelled: orderId={} exchOrderId={}", orderId, order.exchOrderId);
                        } else {
                            log.error("Order cancellation failed: orderId={} status={} body={}",
                                    orderId, resp.statusCode(), resp.body());
                        }
                    })
                    .exceptionally(ex -> {
                        log.error("Order cancellation exception: orderId={}", orderId, ex);
                        return null;
                    });

        } catch (Exception e) {
            log.error("Failed to cancel order: {}", orderId, e);
        }
    }

    @Override
    public boolean onPrivateWsReconnect() {
        log.info("Private WS reconnected, performing REST reconciliation");
        try {
            List<RestReconciliation.ExchangeOrder> restOrders = fetchOpenOrders();
            List<ExecEvent> syntheticEvents;
            int reconstructedOrders;
            int unmappedOpenOrders;
            synchronized (orderStore) {
                reconstructedOrders = reconciliation.bootstrapOpenOrders(restOrders, orderStore);
                syntheticEvents = reconciliation.reconcile(restOrders, orderStore);
                unmappedOpenOrders = reconciliation.countUnmappedOpenOrders(restOrders, orderStore);
            }
            if (reconstructedOrders > 0) {
                log.info("REST reconciliation bootstrapped {} orders from openOrders snapshot", reconstructedOrders);
            }
            for (ExecEvent event : syntheticEvents) {
                dispatchExecEvent(event);
            }
            if (unmappedOpenOrders > 0) {
                log.error("REST reconciliation found {} unmapped open orders; keep startup gate closed",
                        unmappedOpenOrders);
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("REST reconciliation failed", e);
            return false;
        }
    }

    @Override
    public void shutdown() {
        shuttingDown = true;
        if (privateWsClient != null) {
            privateWsClient.close();
        }
        execEventDispatcher.shutdown();
        try {
            if (!execEventDispatcher.awaitTermination(1, TimeUnit.SECONDS)) {
                execEventDispatcher.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            execEventDispatcher.shutdownNow();
        }
        log.info("BinanceExecGateway shutdown");
    }

    private void handleExecEvent(ExecEvent event) {
        // Lookup internal order ID from exchOrderId (if ACK, it won't be mapped yet)
        if (event.type == com.bedrock.mm.oms.model.ExecEventType.ACK) {
            // For ACK events, we need to find the order by pending state
            // This is a simplification - production would use clientOrderId mapping
            log.info("ACK received: exchOrderId={}", event.exchOrderId);
        }
        if (event.recvNanos == 0L) {
            event.recvNanos = System.nanoTime();
        }
        dispatchExecEvent(event);
    }

    /**
     * Visible for tests.
     */
    void dispatchExecEventForTest(ExecEvent event) {
        dispatchExecEvent(event);
    }

    long dispatchedExecEventsForTest() {
        return dispatchedExecEvents.get();
    }

    long droppedExecEventsForTest() {
        return droppedExecEvents.get();
    }

    private void dispatchExecEvent(ExecEvent event) {
        if (shuttingDown) {
            droppedExecEvents.incrementAndGet();
            return;
        }
        ExecEvent snapshot = copyEvent(event);
        try {
            execEventDispatcher.execute(() -> {
                try {
                    execEventConsumer.accept(snapshot);
                    dispatchedExecEvents.incrementAndGet();
                } catch (Exception e) {
                    log.warn("Exec event dispatch failed: {}", e.toString());
                }
            });
        } catch (RejectedExecutionException e) {
            droppedExecEvents.incrementAndGet();
            log.warn("Exec event dropped: seq={} type={} reason={}",
                    snapshot.seqId, snapshot.type, e.toString());
        }
    }

    private List<RestReconciliation.ExchangeOrder> fetchOpenOrders() throws IOException, InterruptedException {
        StringBuilder queryString = new StringBuilder();
        queryString.append("symbol=").append(URLEncoder.encode(symbol, StandardCharsets.UTF_8));
        queryString.append("&");
        queryString.append("timestamp=").append(System.currentTimeMillis());
        queryString.append("&recvWindow=5000");

        String signature = signQueryString(queryString.toString());
        queryString.append("&signature=").append(signature);

        String url = restBaseUrl + "/api/v3/openOrders?" + queryString;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("X-MBX-APIKEY", apiKey)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch open orders: " + response.statusCode() + " " + response.body());
        }

        List<RestReconciliation.ExchangeOrder> orders =
                parseOpenOrdersResponse(response.body(), gatewayInstrumentId);
        log.info("Fetched {} open orders from REST", orders.size());
        return orders;
    }

    List<RestReconciliation.ExchangeOrder> parseOpenOrdersResponse(
            String responseBody, int defaultInstrumentId) throws IOException {
        List<RestReconciliation.ExchangeOrder> orders = new ArrayList<>();
        try (JsonParser parser = jsonFactory.createParser(responseBody)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IOException("Unexpected openOrders payload: expected JSON array");
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                RestReconciliation.ExchangeOrder order = new RestReconciliation.ExchangeOrder();
                order.instrumentId = defaultInstrumentId;

                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String fieldName = parser.currentName();
                    if (fieldName == null) {
                        continue;
                    }
                    parser.nextToken();
                    switch (fieldName) {
                        case "orderId" -> order.exchOrderId = parser.getLongValue();
                        case "clientOrderId" -> order.clientOrderId = parser.getValueAsString();
                        case "price" -> order.price = parseFixedPoint(parser.getValueAsString());
                        case "origQty" -> order.origSize = parseFixedPoint(parser.getValueAsString());
                        case "executedQty" -> order.filledSize = parseFixedPoint(parser.getValueAsString());
                        case "side" -> order.isBid = "BUY".equalsIgnoreCase(parser.getValueAsString());
                        case "status" -> order.status = parser.getValueAsString();
                        default -> parser.skipChildren();
                    }
                }

                if (order.exchOrderId != 0L) {
                    orders.add(order);
                }
            }
        }
        return orders;
    }

    private String signQueryString(String queryString) {
        try {
            Mac sha256HMAC = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            sha256HMAC.init(secretKeySpec);
            byte[] hash = sha256HMAC.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to sign query string", e);
        }
    }

    private String formatPrice(long price) {
        // Convert fixed-point to decimal string
        return String.format("%.8f", price / 1e8);
    }

    private String formatQuantity(long quantity) {
        // Convert fixed-point to decimal string
        return String.format("%.8f", quantity / 1e8);
    }

    private long parseFixedPoint(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return (long) (Double.parseDouble(value) * 1e8);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse fixed-point value: {}", value);
            return 0L;
        }
    }

    private String resolveSymbol(int instrumentId) {
        if (instrumentId != gatewayInstrumentId) {
            log.warn("place/cancel instrumentId={} differs from gatewayInstrumentId={}, using symbol={}",
                    instrumentId, gatewayInstrumentId, symbol);
        }
        return symbol;
    }

    private static String defaultSymbolForInstrument(int instrumentId) {
        if (instrumentId == 1) {
            return "BTCUSDT";
        }
        if (instrumentId == 2) {
            return "ETHUSDT";
        }
        return "BTCUSDT";
    }

    private static ExecEvent copyEvent(ExecEvent src) {
        ExecEvent dst = new ExecEvent();
        dst.seqId = src.seqId;
        dst.recvNanos = src.recvNanos;
        dst.instrumentId = src.instrumentId;
        dst.internalOrderId = src.internalOrderId;
        dst.exchOrderId = src.exchOrderId;
        dst.type = src.type;
        dst.fillPrice = src.fillPrice;
        dst.fillSize = src.fillSize;
        dst.remainSize = src.remainSize;
        dst.isBid = src.isBid;
        dst.rejectReason = src.rejectReason;
        return dst;
    }
}
