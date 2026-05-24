# Rust MD Live Client Design

日期：2026-05-14

状态：Implemented milestone 14, Binance Spot network smoke verified

## Goal

新增 `bedrock-rs-md-live` crate，负责 Binance/Bitget live market data 的网络接入边界：构造 REST/WS endpoint、订阅消息，并提供不驱动交易的 smoke path，把 live payload 交给 `bedrock-rs-md-venue` pipeline。

## Sources Checked

- Binance Spot diff depth stream：`wss://stream.binance.com:9443/ws/<symbol>@depth@100ms`；REST snapshot：`GET /api/v3/depth`。
- Binance USDⓈ-M Futures diff depth stream：`wss://fstream.binance.com/ws/<symbol>@depth@100ms`；REST snapshot：`GET /fapi/v1/depth`。
- Bitget UTA public WebSocket：`wss://ws.bitget.com/v3/ws/public`；depth topics：`books` incremental、`books1/books5/books50` snapshot-only。

## Scope

新增 crate：

```text
bedrock-rs/crates/bedrock-rs-md-live
```

更新 workspace：

```text
bedrock-rs/Cargo.toml
```

## Design

`bedrock-rs-md-live` owns:

- Public live endpoint builders.
- Binance Spot/Futures REST depth URL builders.
- Binance Spot/Futures WS depth URL builders.
- Bitget UTA WS subscribe JSON builder.
- Async smoke helpers that:
  - connect WebSocket,
  - buffer a small number of text depth messages,
  - fetch REST snapshot where Binance requires it,
  - feed messages through `bedrock-rs-md-venue` pipeline,
  - return `VenuePipelineOutput` values.

It does not own:

- Book reconstruction.
- Pricing/OMS/risk.
- Transport fanout.
- Reconnect policy beyond one-shot smoke.
- Production metrics.

## API Sketch

- `BinanceMarket`
- `BinanceDepthSpeed`
- `BinanceLiveConfig`
- `BitgetLiveConfig`
- `LiveMarketDataError`
- `binance_spot_smoke_once`
- `binance_futures_smoke_once`
- `bitget_books_smoke_once`

## Verification

Offline tests cover:

- Binance Spot/Futures REST and WS URL construction.
- Binance symbol lower-casing only for WS stream names.
- Bitget UTA subscribe JSON.
- Ack/event frame classification.

Network smoke is intentionally not run in unit tests. It should be run manually once dependencies compile and the user wants to hit public exchange endpoints.

Run:

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-md-live
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

已验证：

- `cd bedrock-rs && cargo fmt --check` 通过。
- `cd bedrock-rs && cargo test -p bedrock-rs-md-live`，4 个 `bedrock-rs-md-live` 离线单元测试通过。
- `cd bedrock-rs && cargo test`，79 个 workspace 单元测试通过，6 个 doc-test crate 无测试且通过。
- `cd bedrock-rs && cargo clippy --all-targets -- -D warnings` 通过。
- `cd bedrock-rs && cargo doc --no-deps` 通过。
- `cd bedrock-rs && cargo run -p bedrock-rs-md-live --example live_smoke -- binance-spot BNBBTC 8` 成功连接 Binance Spot public REST/WebSocket，输出 1000x1000 snapshot 和 live delta。
- `cd bedrock-rs && cargo run -p bedrock-rs-md-live --example live_smoke -- binance-futures BTCUSDT 8` 成功连接 Binance Futures public REST/WebSocket，输出 1000x1000 snapshot 和 live delta。
- `cd bedrock-rs && cargo run -p bedrock-rs-md-live --example live_smoke -- bitget-books BTCUSDT 2` 成功连接 Bitget public WebSocket，输出 500x500 snapshot，并保守检测到 subsequent update 与 snapshot 不 bridge 的 gap。

Binance Spot live smoke 输出摘要：

```text
outputs=11
#0 snapshot sequence=4547269050 bids=1000 asks=1000
#10 delta sequence=4547269053 updates=3
```

Binance Futures live smoke 输出摘要：

```text
outputs=11
#0 snapshot sequence=10559073941099 bids=1000 asks=1000
#10 delta sequence=10559073954851 updates=620
```

Bitget books live smoke 输出摘要：

```text
outputs=2
#0 snapshot sequence=569569176400 bids=500 asks=500
#1 gap reason=PreviousSequenceMismatch event_sequence=Some(569569176989)
```

## Non-Goals

- 不接私有账户或下单。
- 不实现无限 reconnect loop。
- 不引入 Aeron。
- 不把 live 网络错误包装成交易决策。
