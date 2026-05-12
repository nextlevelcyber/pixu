# Rust Instrument Registry Config Design

日期：2026-05-12

状态：Implemented milestone 6

## Goal

为 Rust MDS `BookRouter` 增加 instrument universe 边界：线上严格模式只允许配置过的 `(venue, instrument)` 创建 book，未知 key 必须显式 reject，避免错误订阅、错误 venue mapping 或脏数据静默进入 MDS 状态。

## Scope

本里程碑只修改 `bedrock-rs-md`、`bedrock-rs-replay`、fixtures 和文档。不接 live feed，不实现动态热更新，不实现 quarantine queue，不实现 Pricing/OMS，不改变 order book 存储结构。

## Design

在 `bedrock-rs-md` 中新增：

- `UnknownInstrumentPolicy`
- `InstrumentRegistry`

`BookRouter::new()` 保持当前默认行为：遇到新 `(venue, instrument)` 自动创建 book。这让已有 replay fixture 和早期开发体验不被打断。

新增 `BookRouter::with_registry(registry)` 支持严格 allow-list。严格 registry 行为：

- 如果 key 在 registry allowed set 中，正常创建或路由到对应 `BookReconstructor`。
- 如果 key 不在 allowed set 中，返回 `ReconstructionOutput::Reject(BookReject)`。
- unknown reject 不创建 book，不改变已有 book state，不推进任何 sequence。

`RejectReason` 增加 `UnknownInstrument`。`BookReject` 的 expected venue/instrument 改为 `Option<VenueId>` / `Option<InstrumentId>`：

- 单 book wrong venue/instrument reject 继续填 `Some(expected)`。
- registry unknown reject 填 `None`，因为它不是“路由到某个已有 book 后发现身份不匹配”，而是“没有被配置允许”。

Replay harness 新增 fixture directive：

```text
registry|reject_unknown|venue:instrument,venue:instrument
registry|auto_create
```

默认 replay 仍使用 auto-create router；需要严格行为的 fixture 显式声明 `registry|reject_unknown|...`。

## Verification

新增 fixture：

- `instrument_registry_allows_configured.events`
- `instrument_registry_rejects_unknown.events`

成功标准：

- `cd bedrock-rs && cargo fmt`
- `cd bedrock-rs && cargo test`
- `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`
- `cd bedrock-rs && cargo doc --no-deps`
- 默认 multi-instrument fixtures 继续通过
- 严格 registry fixture 能验证 unknown instrument reject
- wiki 记录 Milestone 6 状态和下一步风险

## Non-Goals

- 不实现动态配置 reload。
- 不实现 unknown event quarantine queue。
- 不实现 venue-specific symbol mapping。
- 不实现 subscription lifecycle。
- 不实现 Aeron IPC/UDP adapter。
