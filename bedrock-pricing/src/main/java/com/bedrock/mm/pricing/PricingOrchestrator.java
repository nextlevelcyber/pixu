package com.bedrock.mm.pricing;

import com.bedrock.mm.pricing.model.QuoteTarget;
import com.bedrock.mm.pricing.pipeline.FairMidPipeline;
import com.bedrock.mm.pricing.pipeline.QuoteConstructPipeline;

import java.util.function.Consumer;

/**
 * PricingOrchestrator - per-instrument pricing coordinator.
 *
 * Wires FairMid + QuoteConstruct pipelines and handles event callbacks:
 * - onBbo: hot path, triggers recompute
 * - onDepth: updates L2 arrays, no recompute
 * - onPosition: updates netPosition, no recompute
 * - onTrade: updates lastTrade fields, no recompute
 *
 * All recomputation is triggered by onBbo() only to minimize latency.
 *
 * Thread safety: Single-threaded only (per-instrument Disruptor).
 */
public class PricingOrchestrator {
    private final int instrumentId;
    private final FairMidPipeline fairMidPipeline;
    private final QuoteConstructPipeline quoteConstructPipeline;
    private final Consumer<QuoteTarget> publisher;

    private final InstrumentContext ctx;
    private final QuoteTarget target;
    private long seqId = 0;

    public PricingOrchestrator(
        int instrumentId,
        FairMidPipeline fairMidPipeline,
        QuoteConstructPipeline quoteConstructPipeline,
        Consumer<QuoteTarget> publisher
    ) {
        this.instrumentId = instrumentId;
        this.fairMidPipeline = fairMidPipeline;
        this.quoteConstructPipeline = quoteConstructPipeline;
        this.publisher = publisher;
        this.ctx = new InstrumentContext();
        this.ctx.instrumentId = instrumentId;
        this.target = new QuoteTarget();
        this.target.instrumentId = instrumentId;
    }

    /**
     * onBbo - hot path, triggers full recompute and publish.
     *
     * @param bidPrice bid price (IEEE 754 long bits)
     * @param askPrice ask price (IEEE 754 long bits)
     * @param bidSize bid size (IEEE 754 long bits)
     * @param askSize ask size (IEEE 754 long bits)
     * @param recvNanos receive timestamp (nanoseconds)
     */
    public void onBbo(long bidPrice, long askPrice, long bidSize, long askSize, long recvNanos) {
        ctx.bidPrice = bidPrice;
        ctx.askPrice = askPrice;
        ctx.bidSize = bidSize;
        ctx.askSize = askSize;
        ctx.recvNanos = recvNanos;
        recompute();
    }

    /**
     * onDepth - updates L2 book arrays, no recompute.
     *
     * @param bidPrices bid price array (IEEE 754 long bits)
     * @param bidSizes bid size array (IEEE 754 long bits)
     * @param askPrices ask price array (IEEE 754 long bits)
     * @param askSizes ask size array (IEEE 754 long bits)
     * @param levels number of valid levels
     */
    public void onDepth(long[] bidPrices, long[] bidSizes, long[] askPrices, long[] askSizes, int levels) {
        int copyLen = Math.min(levels, 10);
        System.arraycopy(bidPrices, 0, ctx.bidPrices, 0, copyLen);
        System.arraycopy(bidSizes, 0, ctx.bidSizes, 0, copyLen);
        System.arraycopy(askPrices, 0, ctx.askPrices, 0, copyLen);
        System.arraycopy(askSizes, 0, ctx.askSizes, 0, copyLen);
        ctx.bookLevels = copyLen;
    }

    /**
     * onPosition - updates netPosition, no recompute.
     *
     * @param netPosition net position (IEEE 754 long bits)
     */
    public void onPosition(long netPosition) {
        ctx.netPosition = netPosition;
    }

    /**
     * onTrade - updates lastTrade fields, no recompute.
     *
     * @param price trade price (IEEE 754 long bits)
     * @param size trade size (IEEE 754 long bits)
     * @param isBuy true if buy, false if sell
     */
    public void onTrade(long price, long size, boolean isBuy) {
        ctx.lastTradePrice = price;
        ctx.lastTradeSize = size;
        ctx.lastTradeBuy = isBuy;
    }

    /**
     * onIndexPrice - updates external index price, no recompute.
     *
     * @param indexPrice index price (IEEE 754 long bits)
     */
    public void onIndexPrice(long indexPrice) {
        ctx.indexPrice = indexPrice;
    }

    /**
     * recompute - runs both pipelines and publishes QuoteTarget.
     * Called only from onBbo() hot path.
     */
    private void recompute() {
        ctx.quoteFlags = 0;
        long fairMid = fairMidPipeline.compute(ctx);
        target.reset();
        target.instrumentId = instrumentId;
        target.seqId = ++seqId;
        target.publishNanos = System.nanoTime();
        target.fairMid = fairMid;
        quoteConstructPipeline.compute(ctx, fairMid, target);
        target.flags = ctx.quoteFlags;
        publisher.accept(target);
    }

    /**
     * Get instrument ID.
     */
    public int getInstrumentId() {
        return instrumentId;
    }

    /**
     * Get current sequence ID.
     */
    public long getSeqId() {
        return seqId;
    }

    /**
     * Get instrument context (for testing/inspection).
     */
    public InstrumentContext getContext() {
        return ctx;
    }
}
