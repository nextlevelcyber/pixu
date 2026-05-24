# Rust Venue Bootstrap Pipeline Design

日期：2026-05-14

状态：Implemented milestone 13

## Goal

把 `bedrock-rs-md-venue` 已有的 raw parser、REST/WS snapshot normalization、diff depth normalization 和 venue sequence policies 串成离线 pipeline，为后续 live client 提供单一状态机入口。

## Scope

修改 crate：

```text
bedrock-rs/crates/bedrock-rs-md-venue
```

新增 module：

```text
bedrock-rs/crates/bedrock-rs-md-venue/src/pipeline.rs
```

## Design

新增 public API：

- `VenuePipelineError`
- `VenuePipelineGap`
- `VenuePipelineOutput`
- `BinanceSpotDepthPipeline`
- `BinanceFuturesDepthPipeline`
- `BitgetBooksDepthPipeline`

Pipeline 规则：

- Spot：
  - `apply_snapshot` normalize REST snapshot，发布 `BookSnapshot`，并把 `lastUpdateId` 写入 Spot sequence policy。
  - `process_update` 对 raw diff depth 先执行 `U/u` policy；`Apply` 后 normalize 为 `BookDelta`，`IgnoreStale` 不下游发布，`Gap` 后清空 state 并要求重新 snapshot。
- Futures：
  - `apply_snapshot` normalize REST snapshot，记录 pending snapshot `lastUpdateId`，等待第一条 bridged diff depth。
  - 第一条 update 用 `apply_first_event_after_snapshot(lastUpdateId, U, u)`；后续 update 用 `pu == previous_u` continuity。
  - gap 后清空 pending snapshot 和 sequence state。
- Bitget：
  - `snapshot` message normalize 为 `BookSnapshot` 并设置 local `seq`。
  - `update` message 必须满足 `pseq == local_seq`；`pseq = 0` 输出 reset gap 并清空 state。

Output 规则：

- `VenuePipelineOutput::Normalized(NormalizedDepthEvent)`：可交给 venue-neutral MD router。
- `VenuePipelineOutput::IgnoredStale`：旧/重复 update，不能下游发布。
- `VenuePipelineOutput::Gap(VenuePipelineGap)`：必须停止当前 stream 并重新 bootstrap。

## Verification

Tests cover:

- Spot snapshot -> bridged delta。
- Spot gap resets state and next delta needs snapshot。
- Futures snapshot -> first bridged delta -> next `pu` delta。
- Futures `pu` mismatch emits gap and resets state。
- Bitget snapshot -> update。
- Bitget `pseq = 0` emits reset gap。
- JSON convenience methods propagate parse errors.

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
- `cd bedrock-rs && cargo test -p bedrock-rs-md-venue`，43 个 `bedrock-rs-md-venue` 单元测试通过。
- `cd bedrock-rs && cargo test`，73 个 workspace 单元测试通过，5 个 doc-test crate 无测试且通过。
- `cd bedrock-rs && cargo clippy --all-targets -- -D warnings` 通过。
- `cd bedrock-rs && cargo doc --no-deps` 通过。

## Non-Goals

- 不发起 HTTP/WebSocket IO。
- 不实现 async runtime。
- 不发布到 transport。
- 不维护 multi-instrument registry；pipeline 是单 `(venue, instrument)` 状态机。
