package com.bedrock.mm.md.providers.binance;

import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.md.BookDelta;
import com.bedrock.mm.md.MarketDataServiceImpl;
import com.bedrock.mm.md.MarketTick;
import com.bedrock.mm.md.book.RestSnapshotFetcher;
import com.bedrock.mm.md.book.RestSnapshotFetcher.SnapshotData;
import com.bedrock.mm.md.book.BinanceRestSnapshotFetcher;
import com.bedrock.mm.md.book.SequenceValidator;
import com.bedrock.mm.md.book.SequenceValidator.ValidationResult;
import com.bedrock.mm.md.book.L2OrderBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BinanceFeed implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BinanceFeed.class);
    private static final int MAX_SYMBOL_ID = 10000; // Max symbolId for array sizing

    private final MarketDataServiceImpl mdService;
    private final BinanceProperties props;
    private BinanceWebSocketClient client;
    private final BinanceMessageParser parser = new BinanceMessageParser();
    private final Map<String, Symbol> symbolMap = new HashMap<>();
    private final Map<String, String> streamSymbolLower = new HashMap<>();
    private final Map<String, L2OrderBook> orderBookMap = new HashMap<>();
    private final SequenceValidator seqValidator;
    private final RestSnapshotFetcher snapshotFetcher;
    private final ExecutorService snapshotExecutor;

    public BinanceFeed(MarketDataServiceImpl mdService, BinanceProperties props) {
        this.mdService = mdService;
        this.props = props;
        this.seqValidator = SequenceValidator.createDefault(MAX_SYMBOL_ID);
        boolean isFutures = "FUTURES".equalsIgnoreCase(props.getMarketType());
        this.snapshotFetcher = new BinanceRestSnapshotFetcher(isFutures);
        this.snapshotExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "binance-snapshot-fetcher");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!props.isEnabled()) {
            log.info("Binance feed disabled");
            return;
        }

        // Map symbols (upper-case Symbol, lower-case stream key)
        for (String s : props.getSymbols()) {
            String upper = s.toUpperCase();
            String lower = s.toLowerCase();
            Symbol sym;
            if ("BTCUSDT".equals(upper)) {
                sym = Symbol.btcUsdt();
            } else if ("ETHUSDT".equals(upper)) {
                sym = Symbol.ethUsdt();
            } else {
                sym = Symbol.of(upper);
            }
            symbolMap.put(upper, sym);
            streamSymbolLower.put(upper, lower);

            // Create per-symbol L2OrderBook instance
            orderBookMap.put(upper, new L2OrderBook());
        }

        connectAndSubscribe();
        log.info("Binance feed started for symbols: {} (marketType={})", props.getSymbols(), props.getMarketType());
    }

    private String extractSymbol(String text) {
        // Try find "s":"SYMBOL" in payload
        int idx = text.indexOf("\"s\":\"");
        if (idx < 0) return null;
        int start = idx + "\"s\":\"".length();
        int end = text.indexOf('"', start);
        if (end < 0) return null;
        return text.substring(start, end).toUpperCase();
    }

    private void onMessage(String text) {
        try {
            // Determine symbol
            String symbolUpper = extractSymbol(text);
            if (symbolUpper == null) return;
            Symbol symbol = symbolMap.get(symbolUpper);
            if (symbol == null) return;

            // Use per-channel parsers
            List<MarketTick> ticks = parser.parseTicker(text, symbol);
            if (!ticks.isEmpty()) {
                for (MarketTick t : ticks) {
                    mdService.publishMarketTick(t);
                }
            }
            List<BookDelta> deltas = parser.parseDepthUpdate(text, symbol);
            if (!deltas.isEmpty()) {
                // Validate sequence before applying deltas
                // Use first delta's sequence (all deltas in one message share same seq)
                long seq = deltas.get(0).getSequenceNumber();
                ValidationResult result = seqValidator.validate(symbol, seq);

                switch (result) {
                    case NEED_SNAPSHOT:
                        log.warn("First BookDelta for {}, triggering REST snapshot fetch. Current seq: {}",
                                symbol.getName(), seq);
                        triggerSnapshotFetch(symbol);
                        break;
                    case VALID:
                        L2OrderBook book = orderBookMap.get(symbolUpper);
                        for (BookDelta d : deltas) {
                            if (book != null) {
                                long size = d.getAction() == BookDelta.Action.DELETE ? 0 : d.getQuantity();
                                book.applyDelta(d.getPrice(), size, d.getSide() == Side.BUY,
                                        d.getSequenceNumber(), System.nanoTime());
                            }
                            mdService.publishBookDelta(d);
                        }
                        if (book != null) {
                            long bid0 = book.getBidPrice(0);
                            long ask0 = book.getAskPrice(0);
                            if (bid0 > 0 && ask0 > 0) {
                                long bboSeq = deltas.get(0).getSequenceNumber();
                                mdService.publishBbo(symbolUpper, bid0, book.getBidSize(0),
                                        ask0, book.getAskSize(0), System.nanoTime(), bboSeq);
                            }
                        }
                        break;
                    case GAP_DETECTED:
                    case REWIND_DETECTED:
                        log.error("Sequence anomaly for {}: {}. Triggering REST snapshot fetch and order book rebuild.",
                                symbol.getName(), result);
                        triggerSnapshotFetch(symbol);
                        break;
                    case DUPLICATE:
                        log.debug("Ignoring duplicate sequence for {}: {}", symbol.getName(), seq);
                        break;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse Binance message", e);
        }
    }

    private void triggerSnapshotFetch(Symbol symbol) {
        snapshotExecutor.submit(() -> {
            try {
                log.info("Fetching REST snapshot for {}", symbol.getName());
                SnapshotData snapshot = snapshotFetcher.fetch(symbol);
                log.info("Snapshot fetched for {}: {} levels, lastUpdateId={}",
                        symbol.getName(), snapshot.levels(), snapshot.lastUpdateId());

                // Rebuild L2OrderBook from snapshot
                L2OrderBook orderBook = orderBookMap.get(symbol.getName());
                if (orderBook != null) {
                    orderBook.rebuild(
                            snapshot.bidPrices(),
                            snapshot.bidSizes(),
                            snapshot.askPrices(),
                            snapshot.askSizes(),
                            snapshot.levels(),
                            snapshot.lastUpdateId());
                    log.debug("L2OrderBook rebuilt for {}: bid depth={}, ask depth={}",
                            symbol.getName(), orderBook.getBidDepth(), orderBook.getAskDepth());
                }

                // Update sequence validator with snapshot sequence number
                seqValidator.setSnapshotSequence(symbol, snapshot.lastUpdateId());

            } catch (Exception e) {
                log.error("Failed to fetch snapshot for {}: {}", symbol.getName(), e.getMessage(), e);
                // On failure, reset validator to allow next delta to trigger retry
                seqValidator.reset(symbol);
            }
        });
    }

    private void connectAndSubscribe() {
        String endpoint = "FUTURES".equalsIgnoreCase(props.getMarketType())
                ? props.getEndpointFutures()
                : props.getEndpointSpot();
        client = new BinanceWebSocketClient(endpoint, this::onMessage,
                props.getReconnectIntervalSeconds(), props.getMaxReconnectAttempts());

        // Reset sequence validators on reconnect
        client.setOnOpen(() -> {
            log.info("Binance WS connected, resetting sequence validators");
            for (Symbol symbol : symbolMap.values()) {
                seqValidator.reset(symbol);
            }
        });

        // 准备订阅参数并在连接成功时自动发送
        List<String> params = new ArrayList<>();
        for (String upper : symbolMap.keySet()) {
            String lower = streamSymbolLower.get(upper);
            params.add(lower + "@bookTicker");
            params.add(lower + "@" + props.getDepthStream());
        }
        client.setSubscribe(params, 1);
        client.connect();
    }

    @PreDestroy
    @Override
    public void close() {
        if (client != null) {
            client.close();
        }
        if (snapshotExecutor != null) {
            snapshotExecutor.shutdown();
        }
    }
}