# Rust Venue JSON Parser Design

日期：2026-05-14

状态：Implemented milestone 10

## Goal

把 Binance Spot、Binance USDⓈ-M Futures、Bitget UTA 的官方 L2 depth JSON payload 解析成 raw venue event。该层只解析官方 payload 并保留 raw sequence / levels，不做 fixed-point decimal 转换、不做 sequence policy、不做 book reconstruction。

## Scope

修改 crate：

```text
bedrock-rs/crates/bedrock-rs-md-venue
```

新增 raw JSON fixtures：

```text
bedrock-rs/fixtures/md-venue/json/
```

本里程碑新增：

- `RawLevel`
- `BinanceSpotDepthUpdate`
- `BinanceFuturesDepthUpdate`
- `BitgetDepthMessage`
- `BitgetDepthAction`
- `VenueParseError`
- `parse_binance_spot_depth_update`
- `parse_binance_futures_depth_update`
- `parse_bitget_depth_message`

## Design

使用 `serde_json` 做结构化 JSON 解析。原因：

- Rust 标准库没有 JSON parser。
- 交易所 payload schema 有嵌套数组和 wrapper，手写字符串扫描容易隐藏错误。
- 当前是 parser skeleton，`serde_json::Value` 足够，不需要先引入复杂 schema derive。

parser 输出保留 price/quantity 字符串：

- decimal fixed-point 转换属于后续 normalize stage。
- raw parser 不应该提前绑定 instrument precision。

Binance Spot：

- 支持 raw stream payload。
- 支持 combined stream wrapper：`{"stream":"...","data":{...}}`。
- 必须读取 `e=depthUpdate`、`E`、`s`、`U`、`u`、`b`、`a`。

Binance Futures：

- 支持 raw stream payload。
- 支持 combined stream wrapper。
- 必须读取 `e=depthUpdate`、`E`、`T`、`s`、`U`、`u`、`pu`、`b`、`a`。

Bitget UTA：

- 必须读取 `arg.topic`、`arg.symbol`、`action`、`data[0].seq`、`data[0].pseq`、`data[0].a`、`data[0].b`。
- `data[0].ts` 优先作为 data timestamp；缺失时允许 fallback 到 top-level `ts`。
- `maxDepth` 是 optional。
- `topic` 通过 `BitgetDepthChannelKind::from_topic` 分类。

## Verification

新增 fixtures：

- `binance_spot_depth_update.json`
- `binance_spot_combined_depth_update.json`
- `binance_futures_depth_update.json`
- `bitget_books_snapshot.json`
- `bitget_books_update.json`
- `bitget_books5_snapshot.json`

Run:

```bash
cd bedrock-rs && cargo fmt
cd bedrock-rs && cargo test -p bedrock-rs-md-venue
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

## Non-Goals

- 不连接 WebSocket。
- 不获取 REST snapshot。
- 不把 raw event 转成 normalized `BookDelta`。
- 不做 decimal fixed-point parsing。
- 不处理 trades/ticker/private channels。
