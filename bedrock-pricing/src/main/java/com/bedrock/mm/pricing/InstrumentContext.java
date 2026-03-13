package com.bedrock.mm.pricing;

public class InstrumentContext {
    public int instrumentId;

    public long bidPrice;
    public long askPrice;
    public long bidSize;
    public long askSize;

    public long lastTradePrice;
    public long lastTradeSize;
    public boolean lastTradeBuy;

    public long netPosition;
    public long fairMid;
    public long indexPrice;

    public long recvNanos;
    public int quoteFlags;

    // L2 book data
    public final long[] bidPrices = new long[10];
    public final long[] bidSizes = new long[10];
    public final long[] askPrices = new long[10];
    public final long[] askSizes = new long[10];
    public int bookLevels;

    public void reset() {
        bidPrice = 0;
        askPrice = 0;
        bidSize = 0;
        askSize = 0;
        lastTradePrice = 0;
        lastTradeSize = 0;
        lastTradeBuy = false;
        netPosition = 0;
        fairMid = 0;
        indexPrice = 0;
        recvNanos = 0;
        quoteFlags = 0;
        bookLevels = 0;
    }
}
