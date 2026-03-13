package com.bedrock.mm.md.book;

import com.bedrock.mm.common.model.Symbol;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Bitget REST snapshot fetcher.
 * Endpoint: GET https://api.bitget.com/api/spot/v1/market/depth?symbol={symbol}
 */
public class BitgetRestSnapshotFetcher implements RestSnapshotFetcher {
    private static final Logger log = LoggerFactory.getLogger(BitgetRestSnapshotFetcher.class);
    private static final String BASE_URL = "https://api.bitget.com";

    private final HttpClient httpClient;
    private final JsonFactory jsonFactory = new JsonFactory();

    public BitgetRestSnapshotFetcher() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public RestSnapshotFetcher.SnapshotData fetch(Symbol symbol) throws IOException, InterruptedException {
        String url = BASE_URL + "/api/spot/v1/market/depth?symbol=" + symbol.getName() + "&type=step0&limit=150";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        log.info("Fetching Bitget snapshot for {}: {}", symbol.getName(), url);
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Bitget snapshot fetch failed: HTTP " + response.statusCode() + " " + response.body());
        }

        return parseSnapshot(response.body(), symbol);
    }

    private RestSnapshotFetcher.SnapshotData parseSnapshot(String json, Symbol symbol) throws IOException {
        try (JsonParser parser = jsonFactory.createParser(json)) {
            long timestamp = 0;
            List<long[]> bids = new ArrayList<>(150);
            List<long[]> asks = new ArrayList<>(150);
            boolean inData = false;

            while (parser.nextToken() != null) {
                String fieldName = parser.currentName();
                if (fieldName == null) continue;

                switch (fieldName) {
                    case "data":
                        inData = true;
                        break;
                    case "timestamp":
                        if (inData) {
                            parser.nextToken();
                            timestamp = parser.getLongValue();
                        }
                        break;
                    case "bids":
                        if (inData) {
                            parser.nextToken();
                            parseLevels(parser, bids);
                        }
                        break;
                    case "asks":
                        if (inData) {
                            parser.nextToken();
                            parseLevels(parser, asks);
                        }
                        break;
                }
            }

            int levels = Math.min(bids.size(), asks.size());
            long[] bidPrices = new long[levels];
            long[] bidSizes = new long[levels];
            long[] askPrices = new long[levels];
            long[] askSizes = new long[levels];

            for (int i = 0; i < levels; i++) {
                bidPrices[i] = bids.get(i)[0];
                bidSizes[i] = bids.get(i)[1];
                askPrices[i] = asks.get(i)[0];
                askSizes[i] = asks.get(i)[1];
            }

            log.info("Bitget snapshot parsed for {}: {} levels, timestamp={}",
                    symbol.getName(), levels, timestamp);
            return new RestSnapshotFetcher.SnapshotData(bidPrices, bidSizes, askPrices, askSizes, levels, timestamp);
        }
    }

    private void parseLevels(JsonParser parser, List<long[]> levels) throws IOException {
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken() == JsonToken.START_ARRAY) {
                parser.nextToken();
                String priceStr = parser.getText();
                parser.nextToken();
                String sizeStr = parser.getText();

                long price = parseDecimalToFixed(priceStr);
                long size = parseDecimalToFixed(sizeStr);
                levels.add(new long[]{price, size});

                parser.nextToken();
            }
        }
    }

    private long parseDecimalToFixed(String str) {
        return (long) (Double.parseDouble(str) * 100_000_000L);
    }
}
