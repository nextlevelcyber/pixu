package com.bedrock.mm.strategy;

import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.md.MarketDataService;
import com.bedrock.mm.md.MarketTick;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Simple Moving Average Crossover Strategy (MA Cross)
 * - Subscribes to BTCUSDT 1-minute kline (internally aggregated from
 * MarketTick)
 * - Maintains MA(9) and MA(21) on close prices
 * - Generates BUY when MA9 crosses above MA21, SELL when crosses below
 * - Only holds one position at a time; each trade uses fixed quantity
 *
 * The class provides a public onKline(KlineEvent event) method as requested,
 * and integrates with the existing MarketDataService by aggregating ticks into
 * 1m klines.
 */
public class SimpleMaStrategy implements Strategy {
    private final AtomicBoolean running = new AtomicBoolean(false);
    private StrategyContext context;
    private StrategyStats stats = new StrategyStats();

    // Config defaults (can be overridden via properties)
    private String symbol = "BTCUSDT";
    private int fastPeriod = 9;
    private int slowPeriod = 21;
    private double tradeQty = 0.001d;

    // Internal state
    private final Deque<Double> closes = new ArrayDeque<>();
    private Double lastMaFast = null;
    private Double lastMaSlow = null;
    private int position = 0; // 0: flat, 1: long, -1: short
    private Double lastClose = null;

    // Time bucket aggregator to emit 1-minute kline from MarketTick stream
    private final KlineAggregator aggregator = new KlineAggregator(60_000L, this::onKline);

    // Reusable formatter (thread-safe in our single-threaded strategy context)
    private static final DateTimeFormatter TS_FORMATTER =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    @Override
    public String getName() {
        return "SimpleMaStrategy";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void initialize(StrategyContext context) {
        this.context = context;
        // Per-strategy parameters will be applied via
        // StrategyService.updateParameters()
        // using StrategyDefinition.parameters -> StrategyParameters.customParameters.
        // Here we keep defaults; updateParameters will override if provided.

        context.logInfo("Initialized {} with symbol={}, fastPeriod={}, slowPeriod={}, tradeQty={}",
                getName(), symbol, fastPeriod, slowPeriod, tradeQty);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            context.logInfo("Starting {} strategy", getName());
            MarketDataService md = context.getMarketDataService();
            if (md != null) {
                md.subscribeMarketTick(Symbol.of(symbol), this::onMarketTick);
            } else {
                context.logWarn("MarketDataService not available; strategy will not receive data");
            }
            context.logInfo("Started {} strategy", getName());
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            context.logInfo("Stopped {} strategy", getName());
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public void onMarketTick(MarketTick tick) {
        if (!running.get())
            return;
        try {
            stats.incrementTicksProcessed();
            lastClose = tick.getPriceAsDecimal();
            aggregator.onTick(tick.getTimestamp(), lastClose);
        } catch (Exception e) {
            context.logError("Error processing tick: {}", tick, e);
        }
    }

    // Not used in this strategy
    @Override
    public void onBookDelta(com.bedrock.mm.md.BookDelta delta) {
    }

    @Override
    public void onOrderAck(OrderAck ack) {
    }

    @Override
    public void onFill(Fill fill) {
    }

    @Override
    public Symbol[] getSymbols() {
        return new Symbol[] { Symbol.of(symbol) };
    }

    @Override
    public StrategyParameters getParameters() {
        StrategyParameters p = new StrategyParameters();
        p.setEnabled(true);
        p.setCustomParameter("symbol", symbol);
        p.setCustomParameter("fastPeriod", fastPeriod);
        p.setCustomParameter("slowPeriod", slowPeriod);
        p.setCustomParameter("tradeQty", tradeQty);
        return p;
    }

    @Override
    public void updateParameters(StrategyParameters parameters) {
        if (parameters == null)
            return;
        Map<String, Object> custom = parameters.getCustomParameters();
        if (custom != null) {
            Object sym = custom.get("symbol");
            Object fp = custom.get("fastPeriod");
            Object sp = custom.get("slowPeriod");
            Object qty = custom.get("tradeQty");
            if (sym != null)
                this.symbol = String.valueOf(sym);
            if (fp != null)
                this.fastPeriod = toInt(fp, fastPeriod);
            if (sp != null)
                this.slowPeriod = toInt(sp, slowPeriod);
            if (qty != null)
                this.tradeQty = toDouble(qty, tradeQty);
        }
        context.logInfo("Updated parameters: symbol={}, fastPeriod={}, slowPeriod={}, tradeQty={}",
                symbol, fastPeriod, slowPeriod, tradeQty);
    }

    @Override
    public StrategyStats getStats() {
        return stats;
    }

    @Override
    public void reset() {
        closes.clear();
        lastMaFast = null;
        lastMaSlow = null;
        position = 0;
        stats = new StrategyStats();
    }

    /** Public handler as requested to process Kline events. */
    public void onKline(KlineEvent event) {
        if (!running.get())
            return;
        double close = event.close();
        closes.addLast(close);
        if (closes.size() > Math.max(fastPeriod, slowPeriod)) {
            closes.removeFirst();
        }

        Double maFast = simpleMovingAverage(closes, fastPeriod);
        Double maSlow = simpleMovingAverage(closes, slowPeriod);
        if (maFast == null || maSlow == null)
            return;

        lastMaFast = maFast;
        lastMaSlow = maSlow;
        String tsStr = formatTs(event.timestampMs());
        context.logInfo("[{}] FastMA={} SlowMA={} Close={}", tsStr, f(maFast), f(maSlow), f(close));

        Integer signal = crossoverSignal(maFast, maSlow);
        if (signal == null)
            return; // no change

        // Account balance query example
        double usdtFree = context.getPositionManager().getAvailableBalance("USDT");
        context.logInfo("Available USDT balance: {}", f(usdtFree));

        // Execute trade: only hold one position
        if (signal > 0) { // CrossUp → Buy
            if (position <= 0) {
                placeOrder("BUY", tradeQty);
                position = 1;
                context.logInfo("MA CrossUp → Buy {} {}", tradeQty, symbol);
            }
        } else if (signal < 0) { // CrossDown → Sell
            if (position >= 0) {
                placeOrder("SELL", tradeQty);
                position = -1;
                context.logInfo("MA CrossDown → Sell {} {}", tradeQty, symbol);
            }
        }
    }

    /** Place order via provided OrderService abstraction (by requirement). */
    private void placeOrder(String side, double qty) {
        OrderService svc = resolveOrderService();
        try {
            svc.placeOrder(symbol, side, qty);
            stats.incrementOrdersPlaced();
        } catch (Exception e) {
            context.logError("Order failed: side={} qty={} msg={}", side, qty, e.getMessage());
        }
    }

    /** Resolve an OrderService based on StrategyContext's OrderManager. */
    private OrderService resolveOrderService() {
        OrderManager om = context.getOrderManager();
        if (om == null) {
            return (s, side, q) -> {
                // Fallback: log-only
                context.logWarn("OrderService not available; would place {} {} {}", side, q, s);
            };
        }
        // Wrap OrderManager into an OrderService; for market orders use last close
        return (s, side, q) -> {
            double px = (lastClose != null) ? lastClose : 0.0;
            om.submitOrder(s, side, px, q);
        };
    }

    // --- Helpers ---

    private static Double simpleMovingAverage(Deque<Double> values, int period) {
        if (values.size() < period)
            return null;
        double sum = 0.0;
        int count = 0;
        for (Double v : values) {
            sum += v;
            count++;
            if (count == period)
                break;
        }
        return sum / period;
    }

    private Integer crossoverSignal(Double fast, Double slow) {
        if (lastMaFast == null || lastMaSlow == null)
            return null;
        boolean prevUp = lastMaFast > lastMaSlow;
        boolean nowUp = fast > slow;
        if (nowUp && !prevUp)
            return +1; // CrossUp
        if (!nowUp && prevUp)
            return -1; // CrossDown
        return null; // no change
    }

    private static int toInt(Object o, int defVal) {
        try {
            return Integer.parseInt(String.valueOf(o));
        } catch (Exception e) {
            return defVal;
        }
    }

    private static double toDouble(Object o, double defVal) {
        try {
            return Double.parseDouble(String.valueOf(o));
        } catch (Exception e) {
            return defVal;
        }
    }

    private static String formatTs(long tsMs) {
        return TS_FORMATTER.format(Instant.ofEpochMilli(tsMs));
    }

    private static String f(double v) {
        // Zero-allocation alternative to String.format for hot path
        // Convert to fixed-point representation with 4 decimal places
        long scaled = (long) (v * 10000);
        long intPart = scaled / 10000;
        long fracPart = Math.abs(scaled % 10000);
        // Manual padding to avoid String.format allocation
        String fracStr;
        if (fracPart < 10) fracStr = "000" + fracPart;
        else if (fracPart < 100) fracStr = "00" + fracPart;
        else if (fracPart < 1000) fracStr = "0" + fracPart;
        else fracStr = String.valueOf(fracPart);
        return intPart + "." + fracStr;
    }

    // --- Kline Aggregation ---
    private static class KlineAggregator {
        private final long bucketMs;
        private final KlineListener listener;
        private long currentBucketStart = -1L;
        private Double open = null;
        private Double high = null;
        private Double low = null;
        private Double close = null;

        KlineAggregator(long bucketMs, KlineListener listener) {
            this.bucketMs = bucketMs;
            this.listener = listener;
        }

        void onTick(long tsMs, double price) {
            long bucket = tsMs - (tsMs % bucketMs);
            if (currentBucketStart == -1L) {
                // first tick
                currentBucketStart = bucket;
                open = price;
                high = price;
                low = price;
                close = price;
                return;
            }
            if (bucket != currentBucketStart) {
                // emit previous kline
                listener.onKline(new KlineEvent(currentBucketStart, open, high, low, close));
                // start new bucket
                currentBucketStart = bucket;
                open = price;
                high = price;
                low = price;
                close = price;
            } else {
                // update current bucket
                high = Math.max(high, price);
                low = Math.min(low, price);
                close = price;
            }
        }
    }

    // --- Kline event & listener ---
    public static class KlineEvent {
        private final long timestampMs;
        private final double open;
        private final double high;
        private final double low;
        private final double close;

        public KlineEvent(long timestampMs, double open, double high, double low, double close) {
            this.timestampMs = timestampMs;
            this.open = open;
            this.high = high;
            this.low = low;
            this.close = close;
        }

        public long timestampMs() {
            return timestampMs;
        }

        public double open() {
            return open;
        }

        public double high() {
            return high;
        }

        public double low() {
            return low;
        }

        public double close() {
            return close;
        }
    }

    @FunctionalInterface
    public interface KlineListener {
        void onKline(KlineEvent event);
    }

    // --- Required interface by prompt ---
    @FunctionalInterface
    public interface OrderService {
        void placeOrder(String symbol, String side, double quantity);
    }
}