package com.bedrock.mm.md.providers.binance;

import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.md.BookDelta;
import com.bedrock.mm.md.MarketTick;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for Binance public WebSocket market data messages.
 * Supports bookTicker and depthUpdate messages.
 * Handles both direct stream payloads and combined streams (with top-level "stream" and "data").
 * Uses streaming JSON parsing to avoid tree allocation.
 */
@Slf4j
public class BinanceMessageParser {

    private final JsonFactory jsonFactory = new JsonFactory();

    // ThreadLocal buffer for level storage - eliminates double[] allocation on every parse
    // Binance depth updates typically contain 20-100 levels; pre-allocate 200 capacity
    private static final ThreadLocal<LevelBuffer> levelBufferTL = ThreadLocal.withInitial(() -> new LevelBuffer(200));

    public ParsedMessage parse(String text, Symbol symbol) throws IOException {
        try (JsonParser parser = jsonFactory.createParser(text)) {
            // Parse state
            String stream = null;
            long ts = System.currentTimeMillis();
            long seq = ts;

            // Payload fields for bookTicker
            double bidPr = 0, bidSz = 0, askPr = 0, askSz = 0;
            boolean hasB = false, hasBSize = false, hasA = false, hasASize = false;

            // Payload fields for depthUpdate
            String eventType = null;
            LevelBuffer bidBuffer = null;
            LevelBuffer askBuffer = null;

            boolean inData = false;
            boolean inBidsArray = false;
            boolean inAsksArray = false;

            while (parser.nextToken() != null) {
                String fieldName = parser.currentName();

                if (fieldName == null) continue;

                switch (fieldName) {
                    case "stream":
                        parser.nextToken();
                        stream = parser.getValueAsString();
                        break;
                    case "data":
                        inData = true;
                        break;
                    case "E":
                        parser.nextToken();
                        ts = parser.getLongValue();
                        break;
                    case "u":
                        parser.nextToken();
                        seq = parser.getLongValue();
                        break;
                    case "e":
                        parser.nextToken();
                        eventType = parser.getValueAsString();
                        break;
                    case "b":
                        parser.nextToken();
                        if (parser.currentToken() == JsonToken.VALUE_STRING || parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
                            // bookTicker bid price
                            bidPr = parser.getDoubleValue();
                            hasB = true;
                        } else if (parser.currentToken() == JsonToken.START_ARRAY) {
                            // depthUpdate bids array - use ThreadLocal buffer
                            inBidsArray = true;
                            bidBuffer = levelBufferTL.get();
                            bidBuffer.clear();
                            parseLevelArray(parser, bidBuffer);
                            inBidsArray = false;
                        }
                        break;
                    case "B":
                        parser.nextToken();
                        bidSz = parser.getDoubleValue();
                        hasBSize = true;
                        break;
                    case "a":
                        parser.nextToken();
                        if (parser.currentToken() == JsonToken.VALUE_STRING || parser.currentToken() == JsonToken.VALUE_NUMBER_FLOAT) {
                            // bookTicker ask price
                            askPr = parser.getDoubleValue();
                            hasA = true;
                        } else if (parser.currentToken() == JsonToken.START_ARRAY) {
                            // depthUpdate asks array - use ThreadLocal buffer
                            inAsksArray = true;
                            askBuffer = levelBufferTL.get();
                            askBuffer.clear();
                            parseLevelArray(parser, askBuffer);
                            inAsksArray = false;
                        }
                        break;
                    case "A":
                        parser.nextToken();
                        askSz = parser.getDoubleValue();
                        hasASize = true;
                        break;
                }
            }

            // Pre-size result lists with expected capacity
            // bookTicker: always 2 ticks (bid + ask)
            // depthUpdate: typically 20-100 levels per side
            List<MarketTick> ticks = new ArrayList<>(2);
            List<BookDelta> deltas = new ArrayList<>(50);

            // bookTicker: best bid/ask
            boolean isBookTicker = hasB && hasBSize && hasA && hasASize;
            boolean isDepthUpdate = "depthUpdate".equals(eventType);

            if (isBookTicker) {
                long bidPrice = symbol.decimalToPrice(bidPr);
                long bidQty = symbol.decimalToQty(bidSz);
                long askPrice = symbol.decimalToPrice(askPr);
                long askQty = symbol.decimalToQty(askSz);
                ticks.add(MarketTick.buy(symbol, ts, bidPrice, bidQty, seq));
                ticks.add(MarketTick.sell(symbol, ts, askPrice, askQty, seq));
            } else if (isDepthUpdate) {
                if (bidBuffer != null && bidBuffer.size > 0) {
                    for (int i = 0; i < bidBuffer.size; i++) {
                        double p = bidBuffer.prices[i];
                        double q = bidBuffer.quantities[i];
                        long price = symbol.decimalToPrice(p);
                        long qty = symbol.decimalToQty(q);
                        if (q == 0.0) {
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
                        if (q == 0.0) {
                            deltas.add(BookDelta.delete(symbol, ts, Side.SELL, price, seq));
                        } else {
                            deltas.add(BookDelta.update(symbol, ts, Side.SELL, price, qty, seq));
                        }
                    }
                }
            }

            return new ParsedMessage(stream, symbol, ts, ticks, deltas);
        } catch (IOException e) {
            log.error("Error parsing Binance message: {}", text, e);
            return null;
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
            long ts = System.currentTimeMillis();
            long seq = ts;
            double bidPr = 0, bidSz = 0, askPr = 0, askSz = 0;
            boolean hasB = false, hasBSize = false, hasA = false, hasASize = false;

            while (parser.nextToken() != null) {
                String fieldName = parser.currentName();
                if (fieldName == null) continue;

                switch (fieldName) {
                    case "E":
                        parser.nextToken();
                        ts = parser.getLongValue();
                        break;
                    case "u":
                        parser.nextToken();
                        seq = parser.getLongValue();
                        break;
                    case "b":
                        parser.nextToken();
                        bidPr = parser.getDoubleValue();
                        hasB = true;
                        break;
                    case "B":
                        parser.nextToken();
                        bidSz = parser.getDoubleValue();
                        hasBSize = true;
                        break;
                    case "a":
                        parser.nextToken();
                        askPr = parser.getDoubleValue();
                        hasA = true;
                        break;
                    case "A":
                        parser.nextToken();
                        askSz = parser.getDoubleValue();
                        hasASize = true;
                        break;
                }
            }

            boolean isBookTicker = hasB && hasBSize && hasA && hasASize;
            if (!isBookTicker) return List.of();

            long bidPrice = symbol.decimalToPrice(bidPr);
            long bidQty = symbol.decimalToQty(bidSz);
            long askPrice = symbol.decimalToPrice(askPr);
            long askQty = symbol.decimalToQty(askSz);
            List<MarketTick> ticks = new ArrayList<>(2);
            ticks.add(MarketTick.buy(symbol, ts, bidPrice, bidQty, seq));
            ticks.add(MarketTick.sell(symbol, ts, askPrice, askQty, seq));
            return ticks;
        }
    }

    public List<BookDelta> parseDepthUpdate(String text, Symbol symbol) throws IOException {
        try (JsonParser parser = jsonFactory.createParser(text)) {
            long ts = System.currentTimeMillis();
            long seq = ts;
            String eventType = null;
            LevelBuffer bidBuffer = null;
            LevelBuffer askBuffer = null;

            while (parser.nextToken() != null) {
                String fieldName = parser.currentName();
                if (fieldName == null) continue;

                switch (fieldName) {
                    case "E":
                        parser.nextToken();
                        ts = parser.getLongValue();
                        break;
                    case "u":
                        parser.nextToken();
                        seq = parser.getLongValue();
                        break;
                    case "e":
                        parser.nextToken();
                        eventType = parser.getValueAsString();
                        break;
                    case "b":
                        parser.nextToken();
                        if (parser.currentToken() == JsonToken.START_ARRAY) {
                            bidBuffer = levelBufferTL.get();
                            bidBuffer.clear();
                            parseLevelArray(parser, bidBuffer);
                        }
                        break;
                    case "a":
                        parser.nextToken();
                        if (parser.currentToken() == JsonToken.START_ARRAY) {
                            askBuffer = levelBufferTL.get();
                            askBuffer.clear();
                            parseLevelArray(parser, askBuffer);
                        }
                        break;
                }
            }

            boolean isDepthUpdate = "depthUpdate".equals(eventType);
            if (!isDepthUpdate) return List.of();

            // Pre-size with typical depth update size (50 levels per side)
            List<BookDelta> deltas = new ArrayList<>(100);
            if (bidBuffer != null && bidBuffer.size > 0) {
                for (int i = 0; i < bidBuffer.size; i++) {
                    double p = bidBuffer.prices[i];
                    double q = bidBuffer.quantities[i];
                    long price = symbol.decimalToPrice(p);
                    long qty = symbol.decimalToQty(q);
                    if (q == 0.0) {
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
                    if (q == 0.0) {
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
        private final String stream;
        private final Symbol symbol;
        private final long ts;
        private final List<MarketTick> ticks;
        private final List<BookDelta> deltas;

        public ParsedMessage(String stream, Symbol symbol, long ts, List<MarketTick> ticks, List<BookDelta> deltas) {
            this.stream = stream;
            this.symbol = symbol;
            this.ts = ts;
            this.ticks = ticks;
            this.deltas = deltas;
        }

        public String stream() { return stream; }
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