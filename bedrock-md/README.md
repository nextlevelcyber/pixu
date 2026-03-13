# Bedrock Market Data (bedrock-md)

This module provides a unified market data service capable of publishing market ticks and order book deltas to channels and local handlers.

## Bitget V3 Futures WS Provider

Bitget V3 public WebSocket provider for unified futures account market data (ticker, depth). Data is mapped to `MarketTick` and `BookDelta` structures.

### Enable Provider

Add the following properties to your application configuration:

```
bedrock.md.bitget.enabled: true
bedrock.md.bitget.endpoint: "wss://ws.bitget.com/v2/ws/public"
bedrock.md.bitget.instType: "USDT-FUTURES"
bedrock.md.bitget.depthChannel: "books5" # books, books5, books15
bedrock.md.bitget.symbols:
  - BTCUSDT
  - ETHUSDT
```

When enabled, `BitgetV3Feed` connects and subscribes to ticker and depth channels for configured symbols, parsing JSON messages and publishing unified events via `MarketDataServiceImpl`.

### Data Mapping

- `ticker` → two `MarketTick` events (best bid as BUY, best ask as SELL)
- `books5`/`books`/`books15` → `BookDelta.update` for each bid/ask level

### Notes

- Public channels do not require authentication.
- Heartbeat `ping` is sent every 20 seconds; incoming `pong` is handled automatically.
- Symbols are mapped using `Symbol.btcUsdt()`, `Symbol.ethUsdt()`, or `Symbol.of(name)` fallback.

## Per-Channel Parsing

Both Binance and Bitget parsers expose dedicated per-channel methods to keep concerns clean and avoid a single parse function handling multiple message types:

- Binance: `parseTicker(...)` for `bookTicker`, `parseDepthUpdate(...)` for `depthUpdate`
- Bitget V3: `parseTicker(...)` for `ticker`, `parseBooks(...)` for `books`/`books5`/`books15`

The unified `parse(...)` remains for backward compatibility, but feeds now call the per-channel methods directly.

## Feed Lifecycle via FeedManager

Feeds are started and stopped centrally by `FeedManager`, based on configuration. This reduces coupling and makes it easy to add or remove providers:

- `FeedManager.startAll()` creates and starts enabled feeds (Binance, Bitget V3)
- `FeedManager.stopAll()` closes all active feeds

`ApplicationService` is updated to use `FeedManager` for feed lifecycle, with a legacy fallback if the manager is not present.

## Config-Driven Publishers

`MarketDataServiceImpl` initializes channel publishers from configuration. Supported modes:

- `IN_PROC` (default): in-process ringbuffer
- `AERON_IPC`: Aeron IPC
- `AERON_UDP`: Aeron UDP (requires endpoints)

Example configuration:

```
bedrock.md.channels:
  mode: IN_PROC           # IN_PROC | AERON_IPC | AERON_UDP
  marketTickStreamId: 1001
  bookDeltaStreamId: 1002
  marketTickChannel: "aeron:udp?endpoint=localhost:40123"  # used when mode=AERON_UDP
  bookDeltaChannel: "aeron:udp?endpoint=localhost:40124"   # used when mode=AERON_UDP
```

When `mode` is `AERON_IPC` or `AERON_UDP`, the Aeron provider is initialized with sensible local defaults (embedded MediaDriver at `/tmp/aeron`). Override via JVM property `-Daeron.dir=/your/dir` as needed.