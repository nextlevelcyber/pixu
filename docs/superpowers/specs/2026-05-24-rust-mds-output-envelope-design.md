# Rust MDS Output Envelope Design

日期：2026-05-24

状态：Implemented milestone 19

## Goal

为 `MdsOutput` 增加稳定 envelope metadata，让 Pricing、Monitor、in-proc、Aeron IPC、Aeron UDP 后续都能用同一套 stream key 和 output kind 识别 MDS 输出。

## Scope

更新 crate：

```text
bedrock-rs/crates/bedrock-rs-mds
```

## Design

新增：

- `MdsOutputKind`
  - `Bbo`
  - `BookGap`
  - `BookReject`
  - `VenueGap`
  - `IgnoredStale`
- `MdsStreamKey`
  - `venue_id`
  - `instrument_id`
  - `kind`
- `MdsOutputEnvelope`
  - `key`
  - `sequence`
  - `timestamp_ns`
- `MdsOutput::envelope()`

Semantics:

- BBO envelope carries venue/instrument, `Bbo`, sequence, and timestamp.
- Book gap envelope carries venue/instrument, `BookGap`, actual sequence, and no timestamp.
- Book reject envelope carries actual venue/instrument, `BookReject`, sequence, and no timestamp.
- Venue gap envelope carries venue/instrument, `VenueGap`, event sequence, and no timestamp.
- Ignored stale envelope carries venue/instrument, `IgnoredStale`, event sequence, and no timestamp.

`sequence` remains `Option<u64>` instead of `Option<Sequence>` because venue gap/stale event sequences are venue-native raw ids and may come from adapters before a venue-neutral `Sequence` wrapper exists.

## Non-Goals

- 不实现 SBE schema/codegen。
- 不改变 `MdsOutput` payload。
- 不改变 publisher trait 或 transport behavior。
- 不接 Pricing/OMS。

## Verification

Tests should cover:

- BBO envelope metadata.
- Book gap envelope metadata.
- Book reject envelope metadata.
- Venue gap envelope metadata.
- Ignored stale envelope metadata.

Verification commands:

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-mds
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```
