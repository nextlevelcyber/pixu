package com.bedrock.mm.md.providers.bitget;

import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.md.BookDelta;
import com.bedrock.mm.md.MarketTick;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for Bitget V3 WebSocket public market data messages.
 * Uses streaming JSON parsing to avoid tree allocation.
 */
public class BitgetMessageParser {

    private final JsonFactory jsonFactory = new JsonFactory();

    // ThreadLocal buffer for level storage - eliminates double[] allocation on every parse
    // Bitget books typically contain 5-15 levels; pre-allocate 50 capacity for headroom
    private static final ThreadLocal<LevelBuffer> levelBufferTL = ThreadLocal.withInitial(() -> new LevelBuffer(50));

    public ParsedMessage parse(String text, Symbol symbol) throws IOException {
        try (JsonParser parser = jsonFactory.createParser(text)) {
            String channel = null;
            String action = "update";
            long ts = 0L;

            // Ticker fields
            double bidPr = 0, bidSz = 0, askPr = 0, askSz = 0;
            boolean hasBidPr = false, hasBidSz = false, hasAskPr = false, hasAskSz = false;

            // Book fields
            LevelBuffer bidBuffer = null;
            LevelBuffer askBuffer = null;

            int depth = 0;
            boolean inArg = false;
            boolean inData = false;
            boolean inDataElement = false;

            while (parser.nextToken() != null) {
                JsonToken token = parser.currentToken();
                String fieldName = parser.currentName();

                if (token == JsonToken.START_OBJECT) {
                    depth++;
                } else if (token == JsonToken.END_OBJECT) {
                    depth--;
                    if (inDataElement && depth == 2) {
                        inDataElement = false;
                    }
                    if (inArg && depth == 1) {
                        inArg = false;
                    }
                } else if (token == JsonToken.START_ARRAY) {
                    if ("data".equals(fieldName)) {
                        inData = true;
                    } else if (inDataElement && "bids".equals(fieldName)) {
                        bidBuffer = levelBufferTL.get();
                        bidBuffer.clear();
                        parseLevelArray(parser, bidBuffer);
                    } else if (inDataElement && "asks".equals(fieldName)) {
                        askBuffer = levelBufferTL.get();
                        askBuffer.clear();
                        parseLevelArray(parser, askBuffer);
                    }
                } else if (token == JsonToken.END_ARRAY) {
                    if (inData && depth == 1) {
                        inData = false;
                    }
                }

                if (fieldName == null) continue;

                if (depth == 1 && "arg".equals(fieldName)) {
                    inArg = true;
                } else if (inArg && "channel".equals(fieldName)) {
                    parser.nextToken();
                    channel = parser.getValueAsString();
                } else if (depth == 1 && "action".equals(fieldName)) {
                    parser.nextToken();
                    action = parser.getValueAsString();
                } else if (inData && token == JsonToken.START_OBJECT && depth == 2) {
                    inDataElement = true;
                } else if (inDataElement) {
                    switch (fieldName) {
                        case "ts":
                            parser.nextToken();
                            ts = parser.getLongValue();
                            break;
                        case "bidPr":
                            parser.nextToken();
                            bidPr = parser.getDoubleValue();
                            hasBidPr = true;
                            break;
                        case "bidSz":
                            parser.nextToken();
                            bidSz = parser.getDoubleValue();
                            hasBidSz = true;
                            break;
                        case "askPr":
                            parser.nextToken();
                            askPr = parser.getDoubleValue();
                            hasAskPr = true;
                            break;
                        case "askSz":
                            parser.nextToken();
                            askSz = parser.getDoubleValue();
                            hasAskSz = true;
                            break;
                    }
                }
            }

            // Pre-size result lists with expected capacity
            // ticker: always 2 ticks (bid + ask)
            // books: typically 5-15 levels per side
            List<MarketTick> ticks = new ArrayList<>(2);
            List<BookDelta> deltas = new ArrayList<>(30);

            if ("ticker".equals(channel)) {
                long seq = ts;
                if (hasBidPr && hasBidSz) {
                    long price = symbol.decimalToPrice(bidPr);
                    long qty = symbol.decimalToQty(bidSz);
                    ticks.add(MarketTick.buy(symbol, ts, price, qty, seq));
                }
                if (hasAskPr && hasAskSz) {
                    long price = symbol.decimalToPrice(askPr);
                    long qty = symbol.decimalToQty(askSz);
                    ticks.add(MarketTick.sell(symbol, ts, price, qty, seq));
                }
            } else if ("books".equals(channel) || "books5".equals(channel) || "books15".equals(channel)) {
                long seq = ts;
                if (bidBuffer != null && bidBuffer.size > 0) {
                    for (int i = 0; i < bidBuffer.size; i++) {
                        double p = bidBuffer.prices[i];
                        double q = bidBuffer.quantities[i];
                        long price = symbol.decimalToPrice(p);
                        long qty = symbol.decimalToQty(q);
                        if ("snapshot".equals(action)) {
                            deltas.add(BookDelta.add(symbol, ts, Side.BUY, price, qty, seq));
                        } else if (q == 0.0) {
                            deltas.add(BookDelta.delete(symbol, ts, Side.BUY, price, seq));
                        } else {
                            deltas.add(BookDelta.update(symbol, ts, Side.BUY, price, qty, seq));
                        }
                    }
                }
                if (askBuffer != null && askBuffer.size > 0) {
                    for (int i = 0; i < askBuffer.size; i++) {
                        double p = askBuffer.prices[i];
                        double q = askBuffer.quantities[i];
                        long price = symbol.decimalToPrice(p);
                        long qty = symbol.decimalToQty(q);
                        if ("snapshot".equals(action)) {
                            deltas.add(BookDelta.add(symbol, ts, Side.SELL, price, qty, seq));
                        } else if (q == 0.0) {
                            deltas.add(BookDelta.delete(symbol, ts, Side.SELL, price, seq));
                        } else {
                            deltas.add(BookDelta.update(symbol, ts, Side.SELL, price, qty, seq));
                        }
                    }
                }
            }

            return new ParsedMessage(channel, symbol, ts, ticks, deltas);
        }
    }

    private void parseLevelArray(JsonParser parser, LevelBuffer buffer) throws IOException {
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() == JsonToken.START_ARRAY) {
                parser.nextToken(); // price
                double price = parser.getDoubleValue();
                parser.nextToken(); // qty
                double qty = parser.getDoubleValue();
                buffer.add(price, qty);
                parser.nextToken(); // END_ARRAY for this level
            }
        }
    }

    // Per-channel parsing methods
    public List<MarketTick> parseTicker(String text, Symbol symbol) throws IOException {
        try (JsonParser parser = jsonFactory.createParser(text)) {
            String channel = null;
            long ts = System.currentTimeMillis();
            double bidPr = 0, bidSz = 0, askPr = 0, askSz = 0;
            boolean hasBidPr = false, hasBidSz = false, hasAskPr = false, hasAskSz = false;

            int depth = 0;
            boolean inArg = false;
            boolean inDataElement = false;

            while (parser.nextToken() != null) {
                JsonToken token = parser.currentToken();
                String fieldName = parser.currentName();

                if (token == JsonToken.START_OBJECT) {
                    depth++;
                    if (depth == 2 && "data".equals(parser.getParsingContext().getParent().getCurrentName())) {
                        inDataElement = true;
                    }
                } else if (token == JsonToken.END_OBJECT) {
                    depth--;
                    if (inDataElement && depth == 1) {
                        inDataElement = false;
                    }
                    if (inArg && depth == 1) {
                        inArg = false;
                    }
                }

                if (fieldName == null) continue;

                if (depth == 1 && "arg".equals(fieldName)) {
                    inArg = true;
                } else if (inArg && "channel".equals(fieldName)) {
                    parser.nextToken();
                    channel = parser.getValueAsString();
                } else if (inDataElement) {
                    switch (fieldName) {
                        case "ts":
                            parser.nextToken();
                            ts = parser.getLongValue();
                            break;
                        case "bidPr":
                            parser.nextToken();
                            bidPr = parser.getDoubleValue();
                            hasBidPr = true;
                            break;
                        case "bidSz":
                            parser.nextToken();
                            bidSz = parser.getDoubleValue();
                            hasBidSz = true;
                            break;
                        case "askPr":
                            parser.nextToken();
                            askPr = parser.getDoubleValue();
                            hasAskPr = true;
                            break;
                        case "askSz":
                            parser.nextToken();
                            askSz = parser.getDoubleValue();
                            hasAskSz = true;
                            break;
                    }
                }
            }

            if (!"ticker".equals(channel)) return List.of();

            long seq = ts;
            List<MarketTick> ticks = new ArrayList<>(2);
            if (hasBidPr && hasBidSz) {
                long price = symbol.decimalToPrice(bidPr);
                long qty = symbol.decimalToQty(bidSz);
                ticks.add(MarketTick.buy(symbol, ts, price, qty, seq));
            }
            if (hasAskPr && hasAskSz) {
                long price = symbol.decimalToPrice(askPr);
                long qty = symbol.decimalToQty(askSz);
                ticks.add(MarketTick.sell(symbol, ts, price, qty, seq));
            }
            return ticks;
        }
    }

    public List<BookDelta> parseBooks(String text, Symbol symbol) throws IOException {
        try (JsonParser parser = jsonFactory.createParser(text)) {
            String channel = null;
            String action = "update";
            long ts = System.currentTimeMillis();
            LevelBuffer bidBuffer = null;
            LevelBuffer askBuffer = null;

            int depth = 0;
            boolean inArg = false;
            boolean inDataElement = false;

            while (parser.nextToken() != null) {
                JsonToken token = parser.currentToken();
                String fieldName = parser.currentName();

                if (token == JsonToken.START_OBJECT) {
                    depth++;
                } else if (token == JsonToken.END_OBJECT) {
                    depth--;
                    if (inDataElement && depth == 1) {
                        inDataElement = false;
                    }
                    if (inArg && depth == 1) {
                        inArg = false;
                    }
                } else if (token == JsonToken.START_ARRAY) {
                    if (inDataElement && "bids".equals(fieldName)) {
                        bidBuffer = levelBufferTL.get();
                        bidBuffer.clear();
                        parseLevelArray(parser, bidBuffer);
                    } else if (inDataElement && "asks".equals(fieldName)) {
                        askBuffer = levelBufferTL.get();
                        askBuffer.clear();
                        parseLevelArray(parser, askBuffer);
                    } else if ("data".equals(fieldName)) {
                        // Mark that we're about to process data elements
                    }
                }

                if (fieldName == null) continue;

                if (depth == 1 && "arg".equals(fieldName)) {
                    inArg = true;
                } else if (inArg && "channel".equals(fieldName)) {
                    parser.nextToken();
                    channel = parser.getValueAsString();
                } else if (depth == 1 && "action".equals(fieldName)) {
                    parser.nextToken();
                    action = parser.getValueAsString();
                } else if (depth == 2 && token == JsonToken.START_OBJECT) {
                    inDataElement = true;
                } else if (inDataElement && "ts".equals(fieldName)) {
                    parser.nextToken();
                    ts = parser.getLongValue();
                }
            }

            if (!("books".equals(channel) || "books5".equals(channel) || "books15".equals(channel))) {
                return List.of();
            }

            long seq = ts;
            // Pre-size with typical books depth (15 levels per side)
            List<BookDelta> deltas = new ArrayList<>(30);
            if (bidBuffer != null && bidBuffer.size > 0) {
                for (int i = 0; i < bidBuffer.size; i++) {
                    double p = bidBuffer.prices[i];
                    double q = bidBuffer.quantities[i];
                    long price = symbol.decimalToPrice(p);
                    long qty = symbol.decimalToQty(q);
                    if ("snapshot".equals(action)) {
                        deltas.add(BookDelta.add(symbol, ts, Side.BUY, price, qty, seq));
                    } else if (q == 0.0) {
                        deltas.add(BookDelta.delete(symbol, ts, Side.BUY, price, seq));
                    } else {
                        deltas.add(BookDelta.update(symbol, ts, Side.BUY, price, qty, seq));
                    }
                }
            }
            if (askBuffer != null && askBuffer.size > 0) {
                for (int i = 0; i < askBuffer.size; i++) {
                    double p = askBuffer.prices[i];
                    double q = askBuffer.quantities[i];
                    long price = symbol.decimalToPrice(p);
                    long qty = symbol.decimalToQty(q);
                    if ("snapshot".equals(action)) {
                        deltas.add(BookDelta.add(symbol, ts, Side.SELL, price, qty, seq));
                    } else if (q == 0.0) {
                        deltas.add(BookDelta.delete(symbol, ts, Side.SELL, price, seq));
                    } else {
                        deltas.add(BookDelta.update(symbol, ts, Side.SELL, price, qty, seq));
                    }
                }
            }
            return deltas;
        }
    }

    public static class ParsedMessage {
        private final String channel;
        private final Symbol symbol;
        private final long ts;
        private final List<MarketTick> ticks;
        private final List<BookDelta> deltas;

        public ParsedMessage(String channel, Symbol symbol, long ts, List<MarketTick> ticks, List<BookDelta> deltas) {
            this.channel = channel;
            this.symbol = symbol;
            this.ts = ts;
            this.ticks = ticks;
            this.deltas = deltas;
        }

        public String channel() { return channel; }
        public Symbol symbol() { return symbol; }
        public long ts() { return ts; }
        public List<MarketTick> ticks() { return ticks; }
        public List<BookDelta> deltas() { return deltas; }
    }

    /**
     * Reusable buffer for level storage - eliminates double[] allocation on every parse.
     * ThreadLocal ensures thread safety without synchronization.
     */
    private static class LevelBuffer {
        double[] prices;
        double[] quantities;
        int size;

        LevelBuffer(int capacity) {
            this.prices = new double[capacity];
            this.quantities = new double[capacity];
            this.size = 0;
        }

        void clear() {
            size = 0;
        }

        void add(double price, double quantity) {
            if (size >= prices.length) {
                // Grow arrays if needed (rare case)
                int newCapacity = prices.length * 2;
                double[] newPrices = new double[newCapacity];
                double[] newQuantities = new double[newCapacity];
                System.arraycopy(prices, 0, newPrices, 0, prices.length);
                System.arraycopy(quantities, 0, newQuantities, 0, quantities.length);
                prices = newPrices;
                quantities = newQuantities;
            }
            prices[size] = price;
            quantities[size] = quantity;
            size++;
        }
    }
}