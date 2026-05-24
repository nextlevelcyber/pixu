# Rust MDS In-Proc Fanout Design

日期：2026-05-17

状态：Implemented milestone 17

## Goal

让 `bedrock-rs-mds` 可以把 `MdsOutput` 发布到 transport-neutral `Publisher<MdsOutput>`，先验证 in-proc fanout，为后续 Pricing、Monitor 和 Aeron IPC/UDP adapter 留出稳定边界。

## Scope

更新 crate：

```text
bedrock-rs/crates/bedrock-rs-mds
bedrock-rs/crates/bedrock-rs-transport
```

## Design

`bedrock-rs-mds` adds:

- `MdsPublishError`
- `MdsRouter::apply_pipeline_output_to`
- `MdsRouter::apply_pipeline_outputs_to`

Semantics:

- Reconstruction still happens inside `MdsRouter`.
- Each resulting `MdsOutput` is published through a caller-provided `Publisher<MdsOutput>`.
- Backpressure/closed publish errors are surfaced immediately with the number of outputs already published.
- No rollback is attempted after publish failure; caller must treat publish failure as a stream-fatal condition and rebuild/resubscribe according to deployment policy.

## Non-Goals

- 不实现 Aeron IPC/UDP binding。
- 不定义 final SBE wire schema。
- 不实现 multi-consumer fanout hub。
- 不接 Pricing/OMS。

## Verification

Tests should cover:

- Snapshot pipeline output publishes a BBO into `InProcChannel<MdsOutput>`.
- Backpressure is returned with `published` count and does not get hidden.

Verification commands:

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-mds
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```
