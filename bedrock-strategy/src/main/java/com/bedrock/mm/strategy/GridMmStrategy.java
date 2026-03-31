package com.bedrock.mm.strategy;

import com.bedrock.mm.common.model.Side;
import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.md.BookDelta;
import com.bedrock.mm.md.MarketTick;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Grid Market Maker Strategy
 *
 * 简单网格做市策略：在参考价格上下固定间距处放置买卖订单
 *
 * 核心逻辑:
 * - 参考价格 = (bestBid + bestAsk) / 2 或 lastTradePrice
 * - 买单价格 = 参考价格 × (1 - gridSpacingBps/10000 × level)
 * - 卖单价格 = 参考价格 × (1 + gridSpacingBps/10000 × level)
 * - 尊重最大仓位限制
 * - 支持 reduce-only 模式
 */
public class GridMmStrategy implements Strategy {

    private static final String STRATEGY_NAME = "GridMM";
    private static final String STRATEGY_VERSION = "1.0.0";
    private static final double BASIS_POINTS_DENOMINATOR = 10_000.0;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<String, GridSymbolState> stateBySymbol = new HashMap<>();
    private final Map<String, GridQuoteLevel> ordersByClientOrderId = new HashMap<>();
    private final Map<String, GridQuoteLevel> ordersByExchangeOrderId = new HashMap<>();

    private StrategyContext context;
    private StrategyParameters parameters;
    private StrategyStats stats;
    private Symbol[] configuredSymbols = new Symbol[] { Symbol.btcUsdt() };

    // 策略参数
    private double gridSpacingBps = 10.0;
    private int numLevels = 3;
    private double sizePerLevel = 0.001;
    private double maxPosition = 0.01;
    private double repriceThresholdBps = 5.0;

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
        applyParameters(this.parameters);
        context.logInfo("Initialized {} strategy v{} gridSpacing={}bps numLevels={} size={}",
                getName(), getVersion(), gridSpacingBps, numLevels, sizePerLevel);
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
            GridSymbolState state = stateBySymbol.computeIfAbsent(symbol.getName(), ignored -> new GridSymbolState(symbol));
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
            GridSymbolState state = stateBySymbol.computeIfAbsent(tick.getSymbol().getName(), ignored -> new GridSymbolState(tick.getSymbol()));
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
            GridSymbolState state = stateBySymbol.computeIfAbsent(delta.getSymbol().getName(), ignored -> new GridSymbolState(delta.getSymbol()));
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
            GridQuoteLevel quote = findQuote(ack.getClientOrderId(), ack.getOrderId());
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
            GridSymbolState state = stateBySymbol.computeIfAbsent(fill.getSymbol().getName(), ignored -> new GridSymbolState(fill.getSymbol()));
            double signedQty = fill.getSide() == Side.BUY ? fill.getFillQuantityAsDecimal() : -fill.getFillQuantityAsDecimal();
            state.positionQty += signedQty;
            state.lastTradePrice = fill.getFillPrice();
            stats.incrementFills();
            stats.addVolume(fill.getFillQuantityAsDecimal());
            stats.addPnl(calculatePnl(fill));
            stats.addCommission(fill.getCommissionAsDecimal());

            GridQuoteLevel quote = findQuote(fill.getClientOrderId(), fill.getOrderId());
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
        applyParameters(this.parameters);
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
        applyParameters(parameters != null ? parameters : createDefaultParameters());
        context.logInfo("Reset {} strategy state", getName());
    }

    // ========================= 核心逻辑 =========================

    /**
     * 刷新报价 - 策略核心
     */
    private void refreshQuotes(GridSymbolState state) {
        if (state == null) {
            return;
        }

        long referencePrice = computeReferencePrice(state);
        if (referencePrice <= 0L) {
            return;
        }

        // 检查是否需要刷新
        if (!shouldRefresh(state, referencePrice)) {
            return;
        }

        state.lastReferencePrice = referencePrice;
        state.lastQuoteTimeNs = System.nanoTime();

        // 放置网格订单
        for (int level = 1; level <= numLevels; level++) {
            // 买单价格 = referencePrice × (1 - spacing × level)
            long bidPrice = computeQuotePrice(referencePrice, true, level);
            // 卖单价格 = referencePrice × (1 + spacing × level)
            long askPrice = computeQuotePrice(referencePrice, false, level);

            // 检查仓位限制
            double levelExposure = sizePerLevel * level;
            boolean canBuy = state.positionQty + levelExposure <= maxPosition;
            boolean canSell = state.positionQty - levelExposure >= -maxPosition;

            syncQuote(state, Side.BUY, level, bidPrice, canBuy);
            syncQuote(state, Side.SELL, level, askPrice, canSell);
        }

        stats.setCurrentPosition(state.positionQty);
        stats.setAverageSpread(state.bestBidPrice > 0L && state.bestAskPrice > 0L
                ? state.symbol.priceToDecimal(state.bestAskPrice - state.bestBidPrice)
                : 0.0);
    }

    /**
     * 同步单个报价到交易所
     */
    private void syncQuote(GridSymbolState state, Side side, int level, long desiredPrice, boolean enabled) {
        String key = quoteKey(side, level);
        GridQuoteLevel existing = state.quotes.get(key);

        // 不需要此订单
        if (!enabled || desiredPrice <= 0L) {
            cancelQuote(existing);
            return;
        }

        // 订单已存在且价格有效
        if (existing != null && !requiresReplace(existing, desiredPrice)) {
            return;
        }

        // 撤掉旧订单
        if (existing != null) {
            cancelQuote(existing);
        }

        // 下新订单
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

        GridQuoteLevel quote = new GridQuoteLevel(
                state.symbol, side, level, clientOrderId, desiredPrice, sizePerLevel, System.currentTimeMillis()
        );
        state.quotes.put(key, quote);
        ordersByClientOrderId.put(clientOrderId, quote);
        stats.incrementOrdersPlaced();
    }

    /**
     * 检查是否需要撤单重报
     */
    private boolean requiresReplace(GridQuoteLevel existing, long desiredPrice) {
        // 订单超时
        long ageMs = System.currentTimeMillis() - existing.createdAtMs;
        if (ageMs >= parameters.getOrderTimeoutMs()) {
            return true;
        }

        // 价格偏离超过阈值
        double currentPrice = existing.symbol.priceToDecimal(existing.price);
        double nextPrice = existing.symbol.priceToDecimal(desiredPrice);
        if (currentPrice <= 0.0 || nextPrice <= 0.0) {
            return true;
        }

        double diffBps = Math.abs(nextPrice - currentPrice) / currentPrice * BASIS_POINTS_DENOMINATOR;
        return diffBps >= repriceThresholdBps;
    }

    /**
     * 计算参考价格
     */
    private long computeReferencePrice(GridSymbolState state) {
        // 优先使用中价
        if (state.bestBidPrice > 0L && state.bestAskPrice > 0L && state.bestAskPrice >= state.bestBidPrice) {
            return (state.bestBidPrice + state.bestAskPrice) / 2L;
        }
        //  fallback 到最新成交价
        if (state.lastTradePrice > 0L) {
            return state.lastTradePrice;
        }
        // fallback 到买一/卖一
        if (state.bestBidPrice > 0L) {
            return state.bestBidPrice;
        }
        return state.bestAskPrice;
    }

    /**
     * 检查是否需要刷新报价
     */
    private boolean shouldRefresh(GridSymbolState state, long referencePrice) {
        long nowNs = System.nanoTime();

        // 首次报价
        if (state.lastQuoteTimeNs == 0L) {
            return true;
        }

        // 时间超过刷新间隔
        if ((nowNs - state.lastQuoteTimeNs) >= parameters.getQuoteRefreshMs() * 1_000_000L) {
            return true;
        }

        // 价格变动超过阈值
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

    /**
     * 计算网格价格
     */
    private long computeQuotePrice(long referencePrice, boolean bid, int level) {
        double spacingBps = gridSpacingBps * level;
        double multiplier = bid
                ? (1.0 - spacingBps / BASIS_POINTS_DENOMINATOR)
                : (1.0 + spacingBps / BASIS_POINTS_DENOMINATOR);
        return Math.max(1L, Math.round(referencePrice * multiplier));
    }

    /**
     * 撤单
     */
    private void cancelQuote(GridQuoteLevel quote) {
        if (quote == null || quote.clientOrderId == null) {
            return;
        }
        boolean success = context.getOrderManager().cancelOrder(quote.clientOrderId);
        if (success) {
            stats.incrementOrdersCancelled();
        }
        clearQuote(quote);
    }

    /**
     * 撤销所有订单
     */
    private void cancelAllOrders() {
        List<GridQuoteLevel> quotes = List.copyOf(ordersByClientOrderId.values());
        for (GridQuoteLevel quote : quotes) {
            cancelQuote(quote);
        }
    }

    /**
     * 清理订单记录
     */
    private void clearQuote(GridQuoteLevel quote) {
        if (quote == null) {
            return;
        }
        ordersByClientOrderId.remove(quote.clientOrderId);
        if (quote.exchangeOrderId != null) {
            ordersByExchangeOrderId.remove(quote.exchangeOrderId);
        }
        GridSymbolState state = stateBySymbol.get(quote.symbol.getName());
        if (state != null) {
            state.quotes.remove(quoteKey(quote.side, quote.level));
        }
    }

    /**
     * 查找订单
     */
    private GridQuoteLevel findQuote(String clientOrderId, String orderId) {
        if (clientOrderId != null && !clientOrderId.isEmpty()) {
            GridQuoteLevel byClient = ordersByClientOrderId.get(clientOrderId);
            if (byClient != null) {
                return byClient;
            }
        }
        if (orderId != null && !orderId.isEmpty()) {
            return ordersByExchangeOrderId.get(orderId);
        }
        return null;
    }

    /**
     * 计算 PnL
     */
    private double calculatePnl(Fill fill) {
        double notional = fill.getNotionalValue();
        return fill.getSide() == Side.BUY ? -notional : notional;
    }

    /**
     * 应用参数
     */
    private void applyParameters(StrategyParameters params) {
        configuredSymbols = resolveSymbols(params);
        stateBySymbol.keySet().retainAll(Arrays.stream(configuredSymbols).map(Symbol::getName).toList());
        for (Symbol symbol : configuredSymbols) {
            stateBySymbol.computeIfAbsent(symbol.getName(), ignored -> new GridSymbolState(symbol)).symbol = symbol;
        }

        gridSpacingBps = readDoubleParameter("grid-spacing-bps", 10.0, params);
        numLevels = Math.max(1, readIntParameter("num-levels", 3, params));
        sizePerLevel = readDoubleParameter("size-per-level", 0.001, params);
        maxPosition = readDoubleParameter("max-position", 0.01, params);
        repriceThresholdBps = Math.max(0.1, readDoubleParameter("reprice-threshold-bps", gridSpacingBps / 2, params));
    }

    /**
     * 解析交易对
     */
    private Symbol[] resolveSymbols(StrategyParameters params) {
        Object raw = params.getCustomParameter("symbols", List.of("BTCUSDT"));
        if (raw instanceof List<?> values && !values.isEmpty()) {
            List<Symbol> resolved = new ArrayList<>();
            for (Object value : values) {
                if (value == null) continue;
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

    /**
     * 读取 double 参数
     */
    private double readDoubleParameter(String key, double defaultValue, StrategyParameters params) {
        Object value = params.getCustomParameter(key, null);
        if (value == null) {
            value = params.getCustomParameter(toKebabCase(key), null);
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

    /**
     * 读取 int 参数
     */
    private int readIntParameter(String key, int defaultValue, StrategyParameters params) {
        Object value = params.getCustomParameter(key, null);
        if (value == null) {
            value = params.getCustomParameter(toKebabCase(key), null);
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

    /**
     * 转 kebab-case
     */
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

    /**
     * 报价键
     */
    private String quoteKey(Side side, int level) {
        return side.name() + "-" + level;
    }

    /**
     * 创建默认参数
     */
    private StrategyParameters createDefaultParameters() {
        StrategyParameters params = new StrategyParameters();
        params.setEnabled(true);
        params.setSpread(0.0010);
        params.setQuantity(0.0010);
        params.setMaxPosition(0.01);
        params.setRiskLimit(1000.0);
        params.setQuoteRefreshMs(500L);
        params.setOrderTimeoutMs(2000L);
        params.setMaxOrders(6);
        params.setMinSpread(0.0005);
        params.setMaxSpread(0.01);
        return params;
    }

    // ========================= 内部类 =========================

    /**
     * 交易对状态
     */
    private static class GridSymbolState {
        Symbol symbol;
        long bestBidPrice;
        long bestAskPrice;
        long lastTradePrice;
        long lastReferencePrice;
        long lastQuoteTimeNs;
        double positionQty;
        final Map<String, GridQuoteLevel> quotes = new HashMap<>();

        GridSymbolState(Symbol symbol) {
            this.symbol = symbol;
        }
    }

    /**
     * 报价层级
     */
    private static class GridQuoteLevel {
        final Symbol symbol;
        final Side side;
        final int level;
        final long price;
        final double quantity;
        final long createdAtMs;
        String clientOrderId;
        String exchangeOrderId;
        OrderAck.OrderStatus status = OrderAck.OrderStatus.NEW;

        GridQuoteLevel(Symbol symbol, Side side, int level, String clientOrderId, long price, double quantity, long createdAtMs) {
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
