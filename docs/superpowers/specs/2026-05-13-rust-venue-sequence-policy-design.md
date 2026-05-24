# Rust Venue Sequence Policy Design

日期：2026-05-13

状态：Implemented milestone 8

## Goal

把已确认的 Binance/Bitget sequence contract 变成可测试的 Rust 离线策略层。该层只判断 raw venue depth event 能否继续应用、是否 stale、是否需要 gap/rebuild，不接 WebSocket、不解析 JSON、不直接改 `BookRouter`。

## Scope

新增 crate：

```text
bedrock-rs/crates/bedrock-rs-md-venue
```

本 crate owns：

- Binance Spot `U/u` range continuity policy。
- Binance USDⓈ-M Futures `U/u/pu` continuity policy。
- Bitget UTA `books` `seq/pseq` continuity policy。
- Bitget depth channel kind classification：`books` 是 incremental，`books1/books5/books50` 是 snapshot-only。

本 crate does not own：

- WebSocket client。
- REST snapshot fetcher。
- JSON parser。
- SBE decoder。
- Generic order book reconstruction。
- Transport / Aeron。

## Design

保持 `bedrock-rs-md` venue-neutral。新增 crate 提供小而明确的 sequence policy API：

- `SequenceDecision`
- `SequenceGapReason`
- `BinanceSpotSequencePolicy`
- `BinanceFuturesSequencePolicy`
- `BitgetBooksSequencePolicy`
- `BitgetDepthChannelKind`

策略对象只保存必要的本地 sequence cursor：

- Binance Spot：`local_update_id`
- Binance Futures：`previous_u`
- Bitget `books`：`local_seq`

所有输入都是 raw `u64`，不复用 `bedrock-rs-common::Sequence`，因为 Bitget `pseq = 0` 是合法 reset signal，而 common `Sequence` 明确禁止 0。

## Required Behaviors

Binance Spot：

- `snapshot_too_old(last_update_id, first_buffered_u)` 判断 snapshot 是否落后于第一条 buffered event。
- `bridges_snapshot(last_update_id, first_update_id, final_update_id)` 判断第一条应用事件是否覆盖 snapshot 边界。
- event `u <= local_update_id` 返回 stale。
- event `U > local_update_id + 1` 返回 gap。
- 否则 apply，并把 `local_update_id` 推进到 `u`。

Binance USDⓈ-M Futures：

- first event after snapshot must satisfy `U <= lastUpdateId && u >= lastUpdateId`。
- first accepted event sets `previous_u = u`。
- subsequent events require `pu == previous_u`。
- `pu` mismatch returns gap and does not advance cursor。

Bitget UTA `books`：

- snapshot sets `local_seq = seq`。
- first update must bridge snapshot sequence with `pseq <= snapshot_seq <= seq`。
- normal update requires `pseq == local_seq` and `seq > pseq`。
- `pseq = 0` returns reset-required and clears local state.
- `books1/books5/books50` are snapshot-only channels.

## Verification

Unit tests must cover:

- Binance Spot snapshot-too-old.
- Binance Spot bridge success.
- Binance Spot stale ignored.
- Binance Spot range gap.
- Binance Futures first bridge success.
- Binance Futures `pu` continuity success.
- Binance Futures `pu` mismatch gap.
- Bitget snapshot then bridged update.
- Bitget `pseq` mismatch gap.
- Bitget `pseq = 0` reset path.
- Bitget `books5` snapshot-only classification.

Run:

```bash
cd bedrock-rs && cargo fmt
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

## Non-Goals

- 不实现 live feed。
- 不实现 parser。
- 不生成 normalized `BookSnapshot` / `BookDelta`。
- 不实现 REST rebuild scheduler。
- 不接 Aeron。
