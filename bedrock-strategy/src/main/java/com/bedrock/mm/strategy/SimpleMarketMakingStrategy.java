package com.bedrock.mm.strategy;

import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.md.BookDelta;
import com.bedrock.mm.md.MarketTick;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimpleMarketMakingStrategy implements Strategy {

    private static final String STRATEGY_NAME = "SimpleMarketMaking";
    private static final String STRATEGY_VERSION = "2.0.0";
    private static final double BASIS_POINTS_DENOMINATOR = 10_000.0;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, SymbolState> stateBySymbol = new HashMap<>();
    private final Map<String, QuoteLevel> ordersByClientOrderId = new HashMap<>();
    private final Map<String, QuoteLevel> ordersByExchangeOrderId = new HashMap<>();

    private StrategyContext context;
    private StrategyParameters parameters;
    private StrategyStats stats;
    private Symbol[] configuredSymbols = new Symbol[] { Symbol.btcUsdt() };

    private int numLevels = 3;
    private double gridSpacingBps = 10.0;
    private double repriceThresholdBps = 5.0;
    private double sizePerLevel = 0.001;
    private double inventoryTarget = 0.0;
    private double skewFactor = 0.0;

    @Override
    public String getName() {
        return STRATEGY_NAME;
    }

    @Override
    public String getVersion() {
        return STRATEGY_VERSION;
    }

    @Override
    public void initialize(StrategyContext context) {
        this.context = context;
        this.parameters = createDefaultParameters();
        this.stats = new StrategyStats();
        applyRuntimeConfiguration(this.parameters);
        context.logInfo("Initialized {} strategy v{}", getName(), getVersion());
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        if (context.getMarketDataService() == null) {
            running.set(false);
            context.logWarn("{} strategy cannot start without market data service", getName());
            return;
        }
        for (Symbol symbol : configuredSymbols) {
            SymbolState state = stateBySymbol.computeIfAbsent(symbol.getName(), ignored -> new SymbolState(symbol));
            state.symbol = symbol;
            context.getMarketDataService().subscribeMarketTick(symbol, this::onMarketTick);
            context.getMarketDataService().subscribeBookDelta(symbol, this::onBookDelta);
        }
        context.logInfo("Started {} strategy on symbols {}", getName(), Arrays.toString(configuredSymbols));
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        cancelAllOrders();
        if (context.getMarketDataService() != null) {
            for (Symbol symbol : configuredSymbols) {
                context.getMarketDataService().unsubscribeMarketTick(symbol);
                context.getMarketDataService().unsubscribeBookDelta(symbol);
            }
        }
        context.logInfo("Stopped {} strategy", getName());
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void onMarketTick(MarketTick tick) {
        if (!running.get()) {
            return;
        }
        try {
            SymbolState state = stateBySymbol.computeIfAbsent(tick.getSymbol().getName(), ignored -> new SymbolState(tick.getSymbol()));
            state.lastTradePrice = tick.getPrice();
            stats.incrementTicksProcessed();
            refreshQuotes(state);
        } catch (Exception e) {
            context.logError("Error processing market tick: {}", tick, e);
        }
    }

    @Override
    public void onBookDelta(BookDelta delta) {
        if (!running.get()) {
            return;
        }
        try {
            SymbolState state = stateBySymbol.computeIfAbsent(delta.getSymbol().getName(), ignored -> new SymbolState(delta.getSymbol()));
            if (delta.getSide() == Side.BUY) {
                if (delta.getAction() == BookDelta.Action.DELETE && delta.getPrice() == state.bestBidPrice) {
                    state.bestBidPrice = 0L;
                } else if (delta.getPrice() >= state.bestBidPrice) {
                    state.bestBidPrice = delta.getPrice();
                }
            } else {
                if (delta.getAction() == BookDelta.Action.DELETE && delta.getPrice() == state.bestAskPrice) {
                    state.bestAskPrice = 0L;
                } else if (state.bestAskPrice == 0L || delta.getPrice() <= state.bestAskPrice) {
                    state.bestAskPrice = delta.getPrice();
                }
            }
            stats.incrementDeltasProcessed();
            refreshQuotes(state);
        } catch (Exception e) {
            context.logError("Error processing book delta: {}", delta, e);
        }
    }

    @Override
    public void onOrderAck(OrderAck ack) {
        if (!running.get()) {
            return;
        }
        try {
            QuoteLevel quote = findQuote(ack.getClientOrderId(), ack.getOrderId());
            if (quote == null) {
                return;
            }
            quote.exchangeOrderId = ack.getOrderId();
            quote.status = ack.getStatus();
            if (ack.getOrderId() != null && !ack.getOrderId().isEmpty()) {
                ordersByExchangeOrderId.put(ack.getOrderId(), quote);
            }
            if (ack.getStatus() == OrderAck.OrderStatus.REJECTED) {
                stats.incrementOrdersRejected();
                clearQuote(quote);
                refreshQuotes(stateBySymbol.get(quote.symbol.getName()));
                return;
            }
            if (ack.getStatus() == OrderAck.OrderStatus.CANCELED || ack.getStatus() == OrderAck.OrderStatus.EXPIRED) {
                clearQuote(quote);
                refreshQuotes(stateBySymbol.get(quote.symbol.getName()));
            }
        } catch (Exception e) {
            context.logError("Error processing order ack: {}", ack, e);
        }
    }

    @Override
    public void onFill(Fill fill) {
        if (!running.get()) {
            return;
        }
        try {
            SymbolState state = stateBySymbol.computeIfAbsent(fill.getSymbol().getName(), ignored -> new SymbolState(fill.getSymbol()));
            double signedQty = fill.getSide() == Side.BUY ? fill.getFillQuantityAsDecimal() : -fill.getFillQuantityAsDecimal();
            state.positionQty += signedQty;
            state.lastTradePrice = fill.getFillPrice();
            stats.incrementFills();
            stats.addVolume(fill.getFillQuantityAsDecimal());
            stats.addPnl(calculatePnl(fill));
            stats.addCommission(fill.getCommissionAsDecimal());
            QuoteLevel quote = findQuote(fill.getClientOrderId(), fill.getOrderId());
            if (quote != null && fill.getFillQuantityAsDecimal() >= quote.quantity) {
                clearQuote(quote);
            }
            refreshQuotes(state);
        } catch (Exception e) {
            context.logError("Error processing fill: {}", fill, e);
        }
    }

    @Override
    public Symbol[] getSymbols() {
        return Arrays.copyOf(configuredSymbols, configuredSymbols.length);
    }

    @Override
    public StrategyParameters getParameters() {
        return parameters;
    }

    @Override
    public void updateParameters(StrategyParameters parameters) {
        this.parameters = parameters != null ? parameters : createDefaultParameters();
        applyRuntimeConfiguration(this.parameters);
        if (context != null) {
            context.logInfo("Updated strategy parameters: {}", this.parameters);
        }
    }

    @Override
    public StrategyStats getStats() {
        return stats;
    }

    @Override
    public void reset() {
        cancelAllOrders();
        stateBySymbol.clear();
        ordersByClientOrderId.clear();
        ordersByExchangeOrderId.clear();
        stats = new StrategyStats();
        applyRuntimeConfiguration(parameters != null ? parameters : createDefaultParameters());
        context.logInfo("Reset {} strategy state", getName());
    }

    private void refreshQuotes(SymbolState state) {
        if (state == null) {
            return;
        }
        long referencePrice = computeReferencePrice(state);
        if (referencePrice <= 0L) {
            return;
        }
        if (!shouldRefresh(state, referencePrice)) {
            return;
        }
        state.lastReferencePrice = referencePrice;
        state.lastQuoteTimeNs = System.nanoTime();
        double maxPosition = Math.max(sizePerLevel, readDoubleParameter("maxPosition", parameters.getMaxPosition()));
        double normalizedInventory = clamp((state.positionQty - inventoryTarget) / maxPosition, -1.0, 1.0);
        double inventorySkewBps = normalizedInventory * skewFactor * gridSpacingBps;

        for (int level = 1; level <= numLevels; level++) {
            double levelExposure = sizePerLevel * level;
            boolean canBuy = state.positionQty + levelExposure <= maxPosition;
            boolean canSell = state.positionQty - levelExposure >= -maxPosition;
            long bidPrice = computeQuotePrice(referencePrice, true, level, inventorySkewBps);
            long askPrice = computeQuotePrice(referencePrice, false, level, inventorySkewBps);
            syncQuote(state, Side.BUY, level, bidPrice, canBuy);
            syncQuote(state, Side.SELL, level, askPrice, canSell);
        }
        stats.setCurrentPosition(state.positionQty);
        stats.setAverageSpread(state.bestBidPrice > 0L && state.bestAskPrice > 0L
                ? state.symbol.priceToDecimal(state.bestAskPrice - state.bestBidPrice)
                : 0.0);
    }

    private void syncQuote(SymbolState state, Side side, int level, long desiredPrice, boolean enabled) {
        String key = quoteKey(side, level);
        QuoteLevel existing = state.quotes.get(key);
        if (!enabled || desiredPrice <= 0L) {
            cancelQuote(existing);
            return;
        }
        if (existing != null && !requiresReplace(existing, desiredPrice)) {
            return;
        }
        if (existing != null) {
            cancelQuote(existing);
        }
        double priceDecimal = state.symbol.priceToDecimal(desiredPrice);
        String clientOrderId = context.getOrderManager().submitOrder(
                state.symbol.getName(),
                side == Side.BUY ? "BUY" : "SELL",
                priceDecimal,
                sizePerLevel
        );
        if (clientOrderId == null || clientOrderId.isEmpty()) {
            stats.incrementOrdersRejected();
            return;
        }
        QuoteLevel quote = new QuoteLevel(state.symbol, side, level, clientOrderId, desiredPrice, sizePerLevel, System.currentTimeMillis());
        state.quotes.put(key, quote);
        ordersByClientOrderId.put(clientOrderId, quote);
        stats.incrementOrdersPlaced();
    }

    private boolean requiresReplace(QuoteLevel existing, long desiredPrice) {
        long ageMs = System.currentTimeMillis() - existing.createdAtMs;
        if (ageMs >= parameters.getOrderTimeoutMs()) {
            return true;
        }
        double currentPrice = existing.symbol.priceToDecimal(existing.price);
        double nextPrice = existing.symbol.priceToDecimal(desiredPrice);
        if (currentPrice <= 0.0 || nextPrice <= 0.0) {
            return true;
        }
        double diffBps = Math.abs(nextPrice - currentPrice) / currentPrice * BASIS_POINTS_DENOMINATOR;
        return diffBps >= repriceThresholdBps;
    }

    private void cancelAllOrders() {
        List<QuoteLevel> quotes = new ArrayList<>(ordersByClientOrderId.values());
        for (QuoteLevel quote : quotes) {
            cancelQuote(quote);
        }
    }

    private void cancelQuote(QuoteLevel quote) {
        if (quote == null || quote.clientOrderId == null) {
            return;
        }
        boolean success = context.getOrderManager().cancelOrder(quote.clientOrderId);
        if (success) {
            stats.incrementOrdersCancelled();
        }
        clearQuote(quote);
    }

    private void clearQuote(QuoteLevel quote) {
        if (quote == null) {
            return;
        }
        ordersByClientOrderId.remove(quote.clientOrderId);
        if (quote.exchangeOrderId != null) {
            ordersByExchangeOrderId.remove(quote.exchangeOrderId);
        }
        SymbolState state = stateBySymbol.get(quote.symbol.getName());
        if (state != null) {
            state.quotes.remove(quoteKey(quote.side, quote.level));
        }
        quote.clientOrderId = null;
        quote.exchangeOrderId = null;
    }

    private QuoteLevel findQuote(String clientOrderId, String orderId) {
        if (clientOrderId != null && !clientOrderId.isEmpty()) {
            QuoteLevel byClient = ordersByClientOrderId.get(clientOrderId);
            if (byClient != null) {
                return byClient;
            }
        }
        if (orderId != null && !orderId.isEmpty()) {
            return ordersByExchangeOrderId.get(orderId);
        }
        return null;
    }

    private long computeReferencePrice(SymbolState state) {
        if (state.bestBidPrice > 0L && state.bestAskPrice > 0L && state.bestAskPrice >= state.bestBidPrice) {
            return (state.bestBidPrice + state.bestAskPrice) / 2L;
        }
        if (state.lastTradePrice > 0L) {
            return state.lastTradePrice;
        }
        if (state.bestBidPrice > 0L) {
            return state.bestBidPrice;
        }
        return state.bestAskPrice;
    }

    private boolean shouldRefresh(SymbolState state, long referencePrice) {
        long nowNs = System.nanoTime();
        if (state.lastQuoteTimeNs == 0L) {
            return true;
        }
        if ((nowNs - state.lastQuoteTimeNs) >= parameters.getQuoteRefreshMs() * 1_000_000L) {
            return true;
        }
        if (state.lastReferencePrice <= 0L) {
            return true;
        }
        double previous = state.symbol.priceToDecimal(state.lastReferencePrice);
        double current = state.symbol.priceToDecimal(referencePrice);
        if (previous <= 0.0 || current <= 0.0) {
            return true;
        }
        double diffBps = Math.abs(current - previous) / previous * BASIS_POINTS_DENOMINATOR;
        return diffBps >= repriceThresholdBps;
    }

    private long computeQuotePrice(long referencePrice, boolean bid, int level, double inventorySkewBps) {
        double baseHalfSpreadBps = Math.max(parameters.getMinSpread() * BASIS_POINTS_DENOMINATOR / 2.0,
                readDoubleParameter("spread", parameters.getSpread()) * BASIS_POINTS_DENOMINATOR / 2.0);
        double levelOffsetBps = baseHalfSpreadBps + Math.max(0, level - 1) * gridSpacingBps;
        double adjustedBps = bid
                ? Math.max(0.1, levelOffsetBps + inventorySkewBps)
                : Math.max(0.1, levelOffsetBps - inventorySkewBps);
        double multiplier = bid
                ? (1.0 - adjustedBps / BASIS_POINTS_DENOMINATOR)
                : (1.0 + adjustedBps / BASIS_POINTS_DENOMINATOR);
        return Math.max(1L, Math.round(referencePrice * multiplier));
    }

    private double calculatePnl(Fill fill) {
        double notional = fill.getNotionalValue();
        return fill.getSide() == Side.BUY ? -notional : notional;
    }

    private void applyRuntimeConfiguration(StrategyParameters runtimeParameters) {
        configuredSymbols = resolveSymbols(runtimeParameters);
        stateBySymbol.keySet().retainAll(Arrays.stream(configuredSymbols).map(Symbol::getName).toList());
        for (Symbol symbol : configuredSymbols) {
            stateBySymbol.computeIfAbsent(symbol.getName(), ignored -> new SymbolState(symbol)).symbol = symbol;
        }
        sizePerLevel = readDoubleParameter("sizePerLevel", readDoubleParameter("quantity", runtimeParameters.getQuantity()));
        numLevels = Math.max(1, readIntParameter("numLevels", Math.max(1, runtimeParameters.getMaxOrders() / 2)));
        gridSpacingBps = Math.max(0.1, readDoubleParameter("gridSpacingBps", Math.max(1.0, runtimeParameters.getSpread() * BASIS_POINTS_DENOMINATOR)));
        repriceThresholdBps = Math.max(0.1, readDoubleParameter("repriceThresholdBps", Math.max(0.5, gridSpacingBps / 2.0)));
        inventoryTarget = readDoubleParameter("inventoryTarget", runtimeParameters.getInventoryTarget());
        skewFactor = Math.max(0.0, readDoubleParameter("skewFactor", runtimeParameters.getSkewFactor()));
    }

    private Symbol[] resolveSymbols(StrategyParameters runtimeParameters) {
        Object raw = runtimeParameters.getCustomParameter("symbols", List.of("BTCUSDT"));
        if (raw instanceof List<?> values && !values.isEmpty()) {
            List<Symbol> resolved = new ArrayList<>();
            for (Object value : values) {
                if (value == null) {
                    continue;
                }
                String symbolName = String.valueOf(value).trim();
                if (!symbolName.isEmpty()) {
                    resolved.add(Symbol.of(symbolName));
                }
            }
            if (!resolved.isEmpty()) {
                return resolved.toArray(Symbol[]::new);
            }
        }
        return new Symbol[] { Symbol.btcUsdt() };
    }

    private double readDoubleParameter(String key, double defaultValue) {
        Object value = parameters.getCustomParameter(key, null);
        if (value == null) {
            value = parameters.getCustomParameter(toKebabCase(key), null);
        }
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private int readIntParameter(String key, int defaultValue) {
        Object value = parameters.getCustomParameter(key, null);
        if (value == null) {
            value = parameters.getCustomParameter(toKebabCase(key), null);
        }
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String toKebabCase(String key) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char ch = key.charAt(i);
            if (Character.isUpperCase(ch)) {
                if (i > 0) {
                    builder.append('-');
                }
                builder.append(Character.toLowerCase(ch));
            } else {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    private String quoteKey(Side side, int level) {
        return side.name() + "-" + level;
    }

    private StrategyParameters createDefaultParameters() {
        StrategyParameters params = new StrategyParameters();
        params.setSpread(0.0010);
        params.setQuantity(0.0010);
        params.setMaxPosition(0.01);
        params.setRiskLimit(1000.0);
        params.setQuoteRefreshMs(500L);
        params.setOrderTimeoutMs(2000L);
        params.setMaxOrders(6);
        params.setMinSpread(0.0005);
        params.setMaxSpread(0.01);
        params.setSkewFactor(0.5);
        params.setInventoryTarget(0.0);
        return params;
    }

    private final class SymbolState {
        private Symbol symbol;
        private long bestBidPrice;
        private long bestAskPrice;
        private long lastTradePrice;
        private long lastReferencePrice;
        private long lastQuoteTimeNs;
        private double positionQty;
        private final Map<String, QuoteLevel> quotes = new HashMap<>();

        private SymbolState(Symbol symbol) {
            this.symbol = symbol;
        }
    }

    private final class QuoteLevel {
        private final Symbol symbol;
        private final Side side;
        private final int level;
        private final long price;
        private final double quantity;
        private final long createdAtMs;
        private String clientOrderId;
        private String exchangeOrderId;
        private OrderAck.OrderStatus status = OrderAck.OrderStatus.NEW;

        private QuoteLevel(Symbol symbol, Side side, int level, String clientOrderId, long price, double quantity, long createdAtMs) {
            this.symbol = symbol;
            this.side = side;
            this.level = level;
            this.clientOrderId = clientOrderId;
            this.price = price;
            this.quantity = quantity;
            this.createdAtMs = createdAtMs;
        }
    }
}
