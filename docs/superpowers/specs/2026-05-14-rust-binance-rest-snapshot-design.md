# Rust Binance REST Snapshot Design

日期：2026-05-14

状态：Implemented milestone 12

## Goal

在 `bedrock-rs-md-venue` 中补齐 Binance Spot 和 Binance USDⓈ-M Futures 的 REST order book snapshot 解析与 normalization，让后续 live feed bootstrap 能先发布可信 `BookSnapshot`，再应用 diff depth `BookDelta`。

## Sources Checked

- Binance Spot REST market data：`GET /api/v3/depth` 返回 `lastUpdateId`、`bids`、`asks`，limit 最大 5000。
- Binance USDⓈ-M Futures REST market data：`GET /fapi/v1/depth` 返回 `lastUpdateId`、`E`、`T`、`bids`、`asks`。

## Scope

修改 crate：

```text
bedrock-rs/crates/bedrock-rs-md-venue
```

新增 fixtures：

```text
bedrock-rs/fixtures/md-venue/json/binance_spot_depth_snapshot.json
bedrock-rs/fixtures/md-venue/json/binance_futures_depth_snapshot.json
```

## Design

新增 raw snapshot structs：

- `BinanceSpotDepthSnapshot`
- `BinanceFuturesDepthSnapshot`

新增 parser：

- `parse_binance_spot_depth_snapshot`
- `parse_binance_futures_depth_snapshot`

新增 normalizer：

- `normalize_binance_spot_snapshot`
- `normalize_binance_futures_snapshot`

转换规则：

- snapshot sequence 使用 REST `lastUpdateId`。
- Spot REST response 没有 exchange timestamp，调用方必须传入 fetch/receive timestamp `TimestampNs`。
- Futures REST response 使用 `E` message output time 转 `TimestampNs`，同时保留 `T` transaction time 在 raw struct 中。
- price/quantity 继续使用 milestone 11 的 exact decimal parser。
- bids 转 `Side::Bid`，asks 转 `Side::Ask`。

## Verification

Tests cover:

- Binance Spot REST snapshot fixture parse。
- Binance Futures REST snapshot fixture parse。
- Binance Spot REST snapshot -> `BookSnapshot`。
- Binance Futures REST snapshot -> `BookSnapshot`。

Run:

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-md-venue
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

已验证：

- `cd bedrock-rs && cargo fmt --check` 通过。
- `cd bedrock-rs && cargo test -p bedrock-rs-md-venue`，36 个 `bedrock-rs-md-venue` 单元测试通过。
- `cd bedrock-rs && cargo test`，66 个 workspace 单元测试通过，5 个 doc-test crate 无测试且通过。
- `cd bedrock-rs && cargo clippy --all-targets -- -D warnings` 通过。
- `cd bedrock-rs && cargo doc --no-deps` 通过。

## Non-Goals

- 不发起 HTTP 请求。
- 不实现 WebSocket。
- 不做 snapshot/delta buffer orchestration。
- 不把 REST snapshot parser 放进 `bedrock-rs-md`，保持 venue-neutral MD 边界。
