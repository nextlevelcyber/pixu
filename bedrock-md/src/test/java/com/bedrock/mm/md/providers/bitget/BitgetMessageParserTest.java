package com.bedrock.mm.md.providers.bitget;

import com.bedrock.mm.common.model.Symbol;
import com.bedrock.mm.md.BookDelta;
import com.bedrock.mm.md.MarketTick;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BitgetMessageParserTest {

    @Test
    void testParseTicker() throws Exception {
        String json = "{" +
                "\"action\":\"snapshot\"," +
                "\"arg\":{\"instType\":\"USDT-FUTURES\",\"channel\":\"ticker\",\"instId\":\"BTCUSDT\"}," +
                "\"data\":[{" +
                "\"instId\":\"BTCUSDT\",\"lastPr\":\"27123.4\",\"bidPr\":\"27123.3\",\"askPr\":\"27123.5\"," +
                "\"bidSz\":\"12\",\"askSz\":\"8\",\"ts\":\"1714123456789\"}]}";

        Symbol symbol = Symbol.btcUsdt();
        BitgetMessageParser parser = new BitgetMessageParser();
        BitgetMessageParser.ParsedMessage pm = parser.parse(json, symbol);
        assertNotNull(pm);
        List<MarketTick> ticks = pm.ticks();
        assertEquals(2, ticks.size());
        MarketTick bid = ticks.stream().filter(MarketTick::isBuy).findFirst().orElseThrow();
        MarketTick ask = ticks.stream().filter(t -> !t.isBuy()).findFirst().orElseThrow();
        assertEquals(symbol.decimalToPrice(27123.3), bid.getPrice());
        assertEquals(symbol.decimalToQty(12), bid.getQuantity());
        assertEquals(symbol.decimalToPrice(27123.5), ask.getPrice());
        assertEquals(symbol.decimalToQty(8), ask.getQuantity());
    }

    @Test
    void testParseBooks5() throws Exception {
        String json = "{" +
                "\"action\":\"snapshot\"," +
                "\"arg\":{\"instType\":\"USDT-FUTURES\",\"channel\":\"books5\",\"instId\":\"BTCUSDT\"}," +
                "\"data\":[{" +
                "\"asks\":[[\"27123.5\",\"8\"],[\"27124.0\",\"5\"]]," +
                "\"bids\":[[\"27123.3\",\"12\"],[\"27122.8\",\"7\"]]," +
                "\"ts\":\"1714123456789\"}]}";

        Symbol symbol = Symbol.btcUsdt();
        BitgetMessageParser parser = new BitgetMessageParser();
        BitgetMessageParser.ParsedMessage pm = parser.parse(json, symbol);
        assertNotNull(pm);
        List<BookDelta> deltas = pm.deltas();
        assertEquals(4, deltas.size());
        BookDelta b0 = deltas.get(0);
        assertEquals(symbol.decimalToPrice(27123.3), b0.getPrice());
        assertEquals(symbol.decimalToQty(12), b0.getQuantity());
        BookDelta a0 = deltas.stream().filter(d -> d.getSide().name().equals("SELL")).findFirst().orElseThrow();
        assertEquals(symbol.decimalToPrice(27123.5), a0.getPrice());
        assertEquals(symbol.decimalToQty(8), a0.getQuantity());
    }
}