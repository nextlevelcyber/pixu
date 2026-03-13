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
 * REST API snapshot fetcher for order book recovery.
 * Blocking call, must be executed on non-hot-path thread.
 */
public interface RestSnapshotFetcher {

    /**
     * Fetch full order book snapshot from exchange REST API.
     *
     * @param symbol trading symbol
     * @return snapshot data with bids/asks and lastUpdateId
     * @throws IOException if HTTP request fails
     */
    SnapshotData fetch(Symbol symbol) throws IOException, InterruptedException;

    /**
     * Snapshot data record.
     * Prices and sizes are already converted to fixed-point long (scale 1e-8).
     *
     * @param bidPrices bid prices (highest to lowest)
     * @param bidSizes bid quantities
     * @param askPrices ask prices (lowest to highest)
     * @param askSizes ask quantities
     * @param levels number of valid levels (arrays may be pre-allocated larger)
     * @param lastUpdateId sequence number of this snapshot
     */
    record SnapshotData(
            long[] bidPrices,
            long[] bidSizes,
            long[] askPrices,
            long[] askSizes,
            int levels,
            long lastUpdateId
    ) {}
}
