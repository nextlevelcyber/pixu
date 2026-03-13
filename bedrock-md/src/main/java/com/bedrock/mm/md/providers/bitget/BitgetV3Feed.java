package com.bedrock.mm.md.providers.bitget;

import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.md.BookDelta;
import com.bedrock.mm.md.MarketTick;
import com.bedrock.mm.md.MarketDataServiceImpl;
import com.bedrock.mm.md.book.RestSnapshotFetcher;
import com.bedrock.mm.md.book.RestSnapshotFetcher.SnapshotData;
import com.bedrock.mm.md.book.BitgetRestSnapshotFetcher;
import com.bedrock.mm.md.book.SequenceValidator;
import com.bedrock.mm.md.book.SequenceValidator.ValidationResult;
import com.bedrock.mm.md.book.L2OrderBook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BitgetV3Feed implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BitgetV3Feed.class);
    private static final int MAX_SYMBOL_ID = 10000; // Max symbolId for array sizing

    private final MarketDataServiceImpl mdService;
    private final BitgetProperties props;
    private BitgetWebSocketClient client;
    private final BitgetMessageParser parser = new BitgetMessageParser();
    private final Map<String, Symbol> symbolMap = new HashMap<>();
    private final Map<String, L2OrderBook> orderBookMap = new HashMap<>();
    private final SequenceValidator seqValidator;
    private final RestSnapshotFetcher snapshotFetcher;
    private final ExecutorService snapshotExecutor;

    public BitgetV3Feed(MarketDataServiceImpl mdService, BitgetProperties props) {
        this.mdService = mdService;
        this.props = props;
        this.seqValidator = SequenceValidator.createDefault(MAX_SYMBOL_ID);
        this.snapshotFetcher = new BitgetRestSnapshotFetcher();
        this.snapshotExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bitget-snapshot-fetcher");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        if (!props.isEnabled()) {
            log.info("Bitget V3 feed disabled");
            return;
        }
        for (String s : props.getSymbols()) {
            String upper = s.toUpperCase();
            Symbol sym;
            if ("BTCUSDT".equals(upper)) {
                sym = Symbol.btcUsdt();
            } else if ("ETHUSDT".equals(upper)) {
                sym = Symbol.ethUsdt();
            } else {
                sym = Symbol.of(upper);
            }
            symbolMap.put(upper, sym);

            // Create per-symbol L2OrderBook instance
            orderBookMap.put(upper, new L2OrderBook());
        }
        connectAndSubscribe();
        log.info("Bitget V3 feed started for symbols: {}", props.getSymbols());
    }

    private void onMessage(String text) {
        try {
            int idx = text.indexOf("\"instId\":\"");
            if (idx < 0) return;
            int start = idx + "\"instId\":\"".length();
            int end = text.indexOf('"', start);
            String instId = text.substring(start, end);
            Symbol symbol = symbolMap.get(instId);
            if (symbol == null) return;

            // 优先尝试按频道解析
            List<MarketTick> ticks = parser.parseTicker(text, symbol);
            if (!ticks.isEmpty()) {
                for (MarketTick t : ticks) {
                    mdService.publishMarketTick(t);
                }
            }
            List<BookDelta> deltas = parser.parseBooks(text, symbol);
            if (!deltas.isEmpty()) {
                // Validate sequence before applying deltas
                // Use first delta's sequence (all deltas in one message share same seq)
                long seq = deltas.get(0).getSequenceNumber();
                ValidationResult result = seqValidator.validate(symbol, seq);

                switch (result) {
                    case NEED_SNAPSHOT:
                        // Bitget sends snapshot on first subscribe with action="snapshot"
                        // If this is truly a snapshot message, set baseline
                        log.info("First BookDelta for {}, setting snapshot baseline: {}", symbol.getName(), seq);
                        seqValidator.setSnapshotSequence(symbol, seq);
                        for (BookDelta d : deltas) {
                            mdService.publishBookDelta(d);
                        }
                        break;
                    case VALID:
                        L2OrderBook book = orderBookMap.get(symbol.getName());
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
                                mdService.publishBbo(symbol.getName(), bid0, book.getBidSize(0),
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
            log.warn("Failed to parse Bitget message", e);
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

                // Update sequence validator with snapshot sequence number (Bitget uses timestamp)
                seqValidator.setSnapshotSequence(symbol, snapshot.lastUpdateId());

            } catch (Exception e) {
                log.error("Failed to fetch snapshot for {}: {}", symbol.getName(), e.getMessage(), e);
                // On failure, reset validator to allow next delta to trigger retry
                seqValidator.reset(symbol);
            }
        });
    }

    private void connectAndSubscribe() {
        client = new BitgetWebSocketClient(props.getEndpoint(), this::onMessage,
                props.getReconnectIntervalSeconds(), props.getMaxReconnectAttempts());

        // Reset sequence validators on reconnect
        client.setOnOpen(() -> {
            log.info("Bitget WS connected, resetting sequence validators");
            for (Symbol symbol : symbolMap.values()) {
                seqValidator.reset(symbol);
            }
        });

        // Build subscriptions: ticker + depth channel per symbol
        String instType = props.getInstType();
        String depthChannel = props.getDepthChannel();
        for (String s : props.getSymbols()) {
            String instId = s.toUpperCase();
            client.addSubscription(instType, "ticker", instId);
            client.addSubscription(instType, depthChannel, instId);
        }
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