# Rust Venue Sequence Fixtures Design

日期：2026-05-14

状态：Implemented milestone 9

## Goal

把 Binance/Bitget raw sequence policy 行为沉淀为文本 golden fixtures。后续写 JSON parser 或 live adapter 时，必须先让这些离线 replay 继续通过，避免把 `U/u`、`pu`、`seq/pseq` 的边界规则写偏。

## Scope

新增 fixture 目录：

```text
bedrock-rs/fixtures/md-venue/
```

只覆盖 sequence policy replay，不覆盖：

- WebSocket。
- REST snapshot。
- JSON parser。
- normalized `BookSnapshot` / `BookDelta`。
- Aeron 或 transport。

## Fixture Format

每个 fixture 是 pipe-delimited `.events` 文件。支持：

```text
policy|binance_spot
policy|binance_futures
policy|bitget_books

spot_snapshot|last_update_id
spot_event|first_update_id|final_update_id
expect|Apply
expect|IgnoreStale
expect_gap|RangeGap

futures_first|last_update_id|first_update_id|final_update_id
futures_event|first_update_id|final_update_id|previous_final_update_id
expect_gap|PreviousFinalUpdateMismatch

bitget_snapshot|sequence
bitget_update|sequence|previous_sequence
expect_reset|VenueReset
expect_channel|topic|Incremental
expect_channel|topic|SnapshotOnly
expect_channel|topic|None
```

每个 event directive 必须紧跟一个 expectation directive。fixture runner 对输出顺序做严格检查。

## Required Fixtures

- `binance_spot_snapshot_bridge.events`
- `binance_spot_stale_ignored.events`
- `binance_spot_range_gap.events`
- `binance_futures_pu_continuity.events`
- `binance_futures_pu_mismatch.events`
- `bitget_books_seq_pseq.events`
- `bitget_books_pseq_mismatch.events`
- `bitget_books_pseq_zero_reset.events`
- `bitget_channel_kind.events`

## Verification

Run:

```bash
cd bedrock-rs && cargo fmt
cd bedrock-rs && cargo test -p bedrock-rs-md-venue
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

## Non-Goals

- 不实现真实 exchange payload parsing。
- 不把 fixture runner 做成生产 API。
- 不在此里程碑引入第三方依赖。
