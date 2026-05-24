# Rust MDS Wire Schema Draft Design

日期：2026-05-24

状态：Implemented milestone 20

## Goal

为 MDS 输出定义 Rust-first wire schema draft，把 `MdsOutputEnvelope + payload` 映射成稳定的 template id、schema id、version 和 raw primitive payload。后续 Aeron IPC/UDP 与正式 SBE XML/codegen 以此为合同基础。

## Scope

更新 crate：

```text
bedrock-rs/crates/bedrock-rs-mds
```

## Design

新增：

- `MDS_WIRE_SCHEMA_ID = 20`
- `MDS_WIRE_SCHEMA_VERSION = 1`
- `MdsWireTemplate`
  - `Bbo = 1200`
  - `BookGap = 1201`
  - `BookReject = 1202`
  - `VenueGap = 1203`
  - `IgnoredStale = 1204`
- `MdsWireEnvelope`
  - `schema_id`
  - `schema_version`
  - `template_id`
  - `venue_id`
  - `instrument_id`
  - `sequence`
  - `timestamp_ns`
- `MdsWireMessage`
  - `Bbo { envelope, payload }`
  - `BookGap { envelope, payload }`
  - `BookReject { envelope, payload }`
  - `VenueGap { envelope, payload }`
  - `IgnoredStale { envelope, payload }`

Null conventions:

- Missing sequence maps to `0`.
- Missing timestamp maps to `0`.
- Missing expected venue/instrument/sequence maps to `0`.

Payload structs:

- `MdsBboWirePayload`: bid/ask price and quantity scaled by `1e8`.
- `MdsBookGapWirePayload`: expected sequence, actual sequence, reason code.
- `MdsBookRejectWirePayload`: expected/actual identity, sequence, reason code.
- `MdsVenueGapWirePayload`: reason code.
- `MdsIgnoredStaleWirePayload`: zero-sized marker payload.

Reason codes are explicit Rust enums:

- `MdsBookGapWireReason`
- `MdsBookRejectWireReason`
- `MdsVenueGapWireReason`

## Non-Goals

- 不修改 Java `bedrock-sbe` XML。
- 不生成 Rust SBE codecs。
- 不改变 transport publisher trait。
- 不接 Aeron IPC/UDP。
- 不接 Pricing/OMS。

## Verification

Tests should cover:

- BBO output maps to template 1200 and scaled payload fields.
- Book gap maps expected/actual sequence and reason code.
- Book reject maps optional expected identity to zero and reason code.
- Venue gap maps event sequence into envelope and reason code.
- Ignored stale maps event sequence into envelope and template 1204.

Verification commands:

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-mds
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```
