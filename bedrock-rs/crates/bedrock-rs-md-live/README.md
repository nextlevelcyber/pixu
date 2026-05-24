# bedrock-rs-md-live

Live market-data network boundary for Rust Bedrock.

## Owns

- Binance Spot REST/WS depth endpoint construction.
- Binance USDⓈ-M Futures REST/WS depth endpoint construction.
- Bitget UTA public WebSocket subscribe message construction for USDT futures `books`.
- One-shot and bounded-session helpers that connect public market-data endpoints and feed text payloads into `bedrock-rs-md-venue` pipelines.

## Does Not Own

- Venue sequence rules or normalization.
- Venue-neutral book reconstruction.
- Transport fanout or Aeron behavior.
- Pricing, OMS, risk, execution, or monitoring decisions.
- Production reconnect/backoff policy.

## Public API

- `BinanceMarket`.
- `BinanceDepthSpeed`.
- `BinanceLiveConfig`.
- `BinanceLiveSessionLimits`.
- `BitgetLiveConfig`.
- `LiveMarketDataError`.
- `unix_now_timestamp_ns`.
- `is_bitget_event_frame`.
- `binance_spot_session_once`.
- `binance_futures_session_once`.
- `binance_spot_smoke_once`.
- `binance_futures_smoke_once`.
- `bitget_books_smoke_once`.

## Semantics

- Binance REST symbols remain uppercase; WS stream names are lowercase.
- Binance smoke helpers open WS first, buffer a bounded number of text messages, fetch REST snapshot, then feed both through the venue pipeline. If the buffered messages are stale, the compatibility smoke helpers continue reading a bounded number of post-snapshot WS frames until a delta/gap is observed.
- Binance session helpers use the same bootstrap path, then read a fixed `post_bootstrap_messages` count so callers can observe a short live stream without starting an infinite daemon.
- Bitget smoke helper sends a UTA `books` subscribe frame with `instType=usdt-futures`, ignores event/ack frames, and feeds depth data frames through the venue pipeline.
- Smoke helpers return `VenuePipelineOutput` values and do not publish to transport or trigger orders.

## Verification

Run:

```bash
cd bedrock-rs
cargo test -p bedrock-rs-md-live
```

Network smoke helpers are intentionally not run by unit tests.

Manual smoke examples:

```bash
cargo run -p bedrock-rs-md-live --example live_smoke -- binance-spot BNBBTC 8
cargo run -p bedrock-rs-md-live --example live_smoke -- binance-futures BTCUSDT 8
cargo run -p bedrock-rs-md-live --example live_smoke -- bitget-books BTCUSDT 2
cargo run -p bedrock-rs-md-live --example live_smoke -- binance-spot BNBBTC 8 5
```
