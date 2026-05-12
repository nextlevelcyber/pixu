# Rust Bedrock MDS Foundation Design

日期：2026-05-11

状态：Implemented milestone 1

## Goal

建立 Rust-first Bedrock 的第一阶段基础：`bedrock-rs/` workspace、MD/MDS domain model、transport-neutral event contract、replay/golden fixture 测试框架、L2 order book reconstruction，以及一个可验证的 in-proc transport adapter。

本阶段不实现 Pricing、OMS、真实下单、真实交易所 live feed，也不实现 Aeron IPC/UDP 的生产 adapter。

## Context

Bedrock 后续方向是 Rust-first。当前 Java 项目不是生产基线，只作为探索资产、领域知识来源和可选参考。第一阶段的核心不是追求最终低延迟形态，而是把 market data correctness、领域边界、测试入口和文档纪律打牢。

必须遵守：

- `wiki/pages/decisions/rust-first-bedrock-direction.md`
- `wiki/pages/decisions/rust-workspace-location.md`
- `wiki/pages/architecture/rust-migration-guardrails.md`
- `ai-coding-standard.md`
- `karpathy-guidelines`

## Architecture

Rust code 放在当前 repo 顶层 `bedrock-rs/`，作为独立 Cargo workspace。Maven reactor 不调用 Rust，Rust build 也不调用 Maven。

第一阶段只创建与 MDS foundation 直接相关的 crates：

```text
bedrock-rs/
  Cargo.toml
  crates/
    bedrock-rs-common/
    bedrock-rs-transport/
    bedrock-rs-md/
    bedrock-rs-replay/
  fixtures/
    md/
```

职责边界：

- `bedrock-rs-common`：固定精度数值、venue、instrument、side、timestamp、sequence、event envelope 等跨 domain 类型。
- `bedrock-rs-transport`：transport-neutral publisher/subscriber trait、in-proc bounded ring adapter、Aeron IPC/UDP adapter 边界。
- `bedrock-rs-md`：normalized market data model、L2 order book、snapshot/delta reconstruction、BBO generation。
- `bedrock-rs-replay`：读取 replay fixtures，驱动 MDS reconstruction，输出 golden assertions。
- `fixtures/md`：小型确定性行情样本，不放大文件或真实密钥。

不创建 `pricing`、`oms`、`monitor` crates。它们是未来阶段，不为了“看起来完整”提前占坑。

## Domain Model

所有热路径数值使用 primitive-backed newtype：

- `Price(i64)`：fixed-point，scale 为 `1e-8`，必须大于 0。
- `Quantity(i64)`：fixed-point，scale 为 `1e-8`，必须大于等于 0；在 L2 delta 中数量为 0 表示删除 price level。
- `TimestampNs(u64)`：epoch nanos 或 normalized receive nanos，不使用 `Instant`/`DateTime` 进入 event contract。
- `Sequence(u64)`：venue-normalized sequence id。
- `InstrumentId(u32)`：内部 instrument id。
- `VenueId(u16)`：内部 venue id。

Market data event contract：

```text
MarketDataEvent
  Snapshot(BookSnapshot)
  Delta(BookDelta)
  Bbo(Bbo)
  Gap(BookGap)
```

`BookSnapshot` 表示某个 instrument 的完整或受限深度快照。`BookDelta` 表示 L2 聚合 price level 的绝对数量更新。`Bbo` 是从 book 派生的 view。`BookGap` 表示 sequence 不连续、snapshot 缺失或 book 被标记为不可信。

## L2 Reconstruction

`bedrock-rs-md` 提供一个 deterministic `BookReconstructor`：

- 初始状态是 `NeedsSnapshot`。
- 收到 snapshot 后进入 `Ready`，重建 bid/ask book。
- 收到连续 delta 时更新 book。
- delta quantity 为 0 时删除对应 price level。
- sequence gap 时进入 `Gapped`，停止发布可信 BBO，并输出 `BookGap`。
- 新 snapshot 可以从 `NeedsSnapshot` 或 `Gapped` 恢复到 `Ready`。

Order book 存储第一阶段使用简单、可审计的 fixed-capacity price-level arrays 或 sorted vectors。选择标准是 correctness 和测试清晰度优先；如果 sorted vector 无法满足后续性能目标，再用新的 ADR 迁移到价格网格或更底层结构。

第一阶段不接交易所 live feed，因此不实现 Binance/Bitget 原生 sequence 规则。fixtures 使用 normalized snapshot/delta/gap 事件。后续 live feed 阶段再为每个 venue 写单独 reconstruction spec。

## Transport

Transport 是一等抽象，不写死部署模式。

第一阶段定义同步、非阻塞 trait：

```text
Publisher<T>
  publish(event: T) -> Result<(), PublishError>

Subscriber<T>
  poll() -> Result<Option<T>, PollError>
```

`bedrock-rs-transport` 第一阶段实现 `InProcChannel`：

- bounded capacity
- single producer / single consumer 起步
- 不阻塞
- 满队列返回 `PublishError::Backpressure`
- 空队列返回 `Ok(None)`

Aeron IPC/UDP 第一阶段只定义 adapter 边界和 feature names，例如 `aeron-ipc`、`aeron-udp`。不引入 Aeron binding、FFI 或网络依赖。这样保留部署诉求，同时避免第一阶段被不成熟依赖拖住。

## Replay And Golden Tests

`bedrock-rs-replay` 提供 fixture-driven tests。fixture 格式使用小型 human-readable 文件，第一阶段优先 JSONL 或 CSV，标准是可读、可 diff、可手写。

最小 fixtures：

- `basic_snapshot_then_delta`：snapshot 后连续 delta，验证 BBO 更新。
- `remove_level`：quantity 为 0 删除 level，验证 BBO 回退到下一档。
- `sequence_gap`：sequence 不连续，验证进入 `Gapped` 且不发布可信 BBO。
- `snapshot_recovers_gap`：gap 后新 snapshot 恢复，验证重新发布 BBO。

测试入口：

```bash
cd bedrock-rs
cargo test
```

第一阶段成功标准：

- Rust workspace 可以独立 `cargo test`。
- domain model validation 有单元测试。
- L2 reconstruction 的四类 fixture 全部通过。
- in-proc channel 的 publish/poll/backpressure 有单元测试。
- 所有模块都有明确 public API；MD 不依赖 Pricing/OMS，Transport 不依赖 MD 内部结构。

## Error Handling

Library 层不因普通坏数据 panic。以下情况返回 typed error 或输出 gap event：

- invalid price / quantity
- invalid instrument / venue id
- delta without snapshot
- sequence gap
- transport backpressure
- malformed replay fixture

Panic 只允许用于测试断言或不可能的内部 bug。生产路径必须能把错误转成状态、event 或 result。

## Documentation

本阶段要同步维护：

- `docs/superpowers/specs/2026-05-11-rust-bedrock-mds-foundation-design.md`：本设计 spec。
- `wiki/pages/architecture/rust-bedrock-mds-foundation.md`：长期 wiki 摘要和后续状态入口。
- 后续 implementation plan：`docs/superpowers/plans/2026-05-11-rust-bedrock-mds-foundation.md`。

每次改变 domain boundary、transport semantics、fixture contract 或 reconstruction state machine，都必须更新 wiki。

## Non-Goals

- 不实现 Pricing。
- 不实现 OMS。
- 不实现 Monitor/Ops。
- 不连接 Binance/Bitget live feed。
- 不实现 Aeron IPC/UDP adapter。
- 不引入 SBE codegen。
- 不做 kernel bypass、CPU affinity、NUMA tuning。
- 不承诺兼容 Java class/interface。

## Risks And Tradeoffs

### Sorted vector vs price grid

第一阶段用更简单的数据结构会牺牲极致性能，但可以降低 correctness 风险。HFT 最终可能需要 price grid、fixed-capacity arrays 或更底层内存布局。这个优化应在 replay correctness 稳定后做。

### JSONL/CSV fixtures vs binary replay

文本 fixture 不是最终高性能 replay 格式，但更适合第一阶段人工审查、golden test 和文档化。后续可新增 binary replay，不替代早期 fixtures。

### Aeron deferred

延后 Aeron 实现会让第一阶段无法证明多进程部署性能，但可以先把 transport contract 和 backpressure 语义设计清楚。过早接 Aeron binding 会把任务复杂度从 MDS correctness 拉偏到 FFI/依赖治理。

### Java not reference

不以 Java 为硬参考会减少兼容负担，但也少了一个可自动对拍对象。第一阶段用 golden fixture 和明确规则弥补；后续如有价值，可以补 Java/Rust replay comparison。

## Self-Review

- Placeholder scan：无占位标记、未完成段落或未定义占位。
- Scope check：只覆盖 Rust MDS foundation，不包含 Pricing/OMS/live feed/Aeron implementation。
- Boundary check：MD、Transport、Replay、Common 职责分离；Transport 不依赖 MD 内部结构。
- Ambiguity check：shadow 定义为旁路验证；Java 不是生产参考；Aeron 只定义边界不实现。
