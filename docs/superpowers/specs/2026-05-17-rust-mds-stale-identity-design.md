# Rust MDS Stale Identity Design

日期：2026-05-17

状态：Implemented milestone 18

## Goal

让 stale/duplicate live updates 携带 `(venue_id, instrument_id, event_sequence)`，避免 MDS fanout 后监控、Pricing 或 replay 无法定位 stale 事件来源。

## Scope

更新 crate：

```text
bedrock-rs/crates/bedrock-rs-md-venue
bedrock-rs/crates/bedrock-rs-md-live
bedrock-rs/crates/bedrock-rs-mds
```

## Design

Add:

- `VenuePipelineIgnoredStale`
  - `venue_id`
  - `instrument_id`
  - `event_sequence`

Change:

- `VenuePipelineOutput::IgnoredStale` becomes `IgnoredStale(VenuePipelineIgnoredStale)`.
- `MdsOutput::IgnoredStale` carries the same identity payload.
- Live examples print stale identity.

## Verification

Tests should cover:

- Spot stale output carries identity and sequence.
- Futures stale output carries identity and sequence.
- MDS stale output preserves identity and does not create a book.

Verification commands:

```bash
cd bedrock-rs && cargo test -p bedrock-rs-md-venue
cd bedrock-rs && cargo test -p bedrock-rs-md-live
cd bedrock-rs && cargo test -p bedrock-rs-mds
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
cd bedrock-rs && cargo check -p bedrock-rs-md-live --example live_smoke
cd bedrock-rs && cargo check -p bedrock-rs-mds --example mds_live_smoke
cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-spot BNBBTC 8 3
```
