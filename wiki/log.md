# Wiki Log

每条记录格式：`## [YYYY-MM-DD] {操作} | {标题}`
操作类型：ingest / query / lint

---

## [2026-05-07] ingest | Bedrock Project Baseline

- 来源：`wiki/sources/architecture/2026-05-07-bedrock-project-baseline.md`
- 页面：`wiki/pages/architecture/bedrock-project-baseline.md`

## [2026-05-07] ingest | MDS Current Status

- 来源：当前对话上下文与代码扫描
- 页面：`wiki/pages/architecture/mds-current-status.md`

## [2026-05-10] ingest | Orderbook Market Data

- 来源：`wiki/sources/business/orderbook-market-data.md`
- 页面：`wiki/pages/business/orderbook-market-data.md`

## [2026-05-10] ingest | Java HFT Overview

- 来源：`wiki/sources/architecture/java-hft-overview.md`
- 页面：`wiki/pages/architecture/java-hft-overview.md`

## [2026-05-10] ingest | Rust Migration Guardrails

- 来源：当前对话上下文
- 页面：`wiki/pages/architecture/rust-migration-guardrails.md`

## [2026-05-10] ingest | Rust Workspace Location

- 来源：当前对话上下文
- 页面：`wiki/pages/decisions/rust-workspace-location.md`
- 修订：确认 Rust workspace 先放在当前 repo 顶层 `bedrock-rs/`，wiki 继续维护在当前 repo 根目录 `wiki/`

## [2026-05-11] ingest | Rust-First Bedrock Direction

- 来源：当前对话上下文
- 页面：`wiki/pages/decisions/rust-first-bedrock-direction.md`
- 修订：确认当前 Java 项目不是生产基线，后续按 Rust-first 方向实现；`shadow` 定义为不驱动真实交易的旁路验证模式
- 关联更新：`wiki/pages/architecture/rust-migration-guardrails.md`、`wiki/pages/decisions/rust-workspace-location.md`

## [2026-05-11] ingest | Rust Bedrock MDS Foundation

- 来源：`docs/superpowers/specs/2026-05-11-rust-bedrock-mds-foundation-design.md`
- 页面：`wiki/pages/architecture/rust-bedrock-mds-foundation.md`

## [2026-05-11] ingest | Rust Bedrock MDS Foundation Milestone 1

- 来源：implementation handoff
- 页面：`wiki/pages/architecture/rust-bedrock-mds-foundation.md`
- 变更：完成 `bedrock-rs/` Cargo workspace、common/transport/md/replay crates、L2 reconstruction、in-proc transport、四类 replay fixtures
- 验证：`cd bedrock-rs && cargo fmt`；`cd bedrock-rs && cargo test`，15 个单元测试通过

## [2026-05-11] ingest | Rust Bedrock MDS Foundation Milestone 2

- 来源：implementation handoff
- 页面：`wiki/pages/architecture/rust-bedrock-mds-foundation.md`
- 变更：硬化 reconstruction output contract；新增 `ReconstructionOutput`、`BookReject`、wrong venue/instrument reject 语义；新增 `expect_reject` fixture directive 和 fixture contract README
- 验证：`cd bedrock-rs && cargo fmt`；`cd bedrock-rs && cargo test`，18 个单元测试通过

## [2026-05-11] ingest | Rust Bedrock MDS Foundation Milestone 3

- 来源：implementation handoff
- 页面：`wiki/pages/architecture/rust-bedrock-mds-foundation.md`
- 变更：硬化 transport semantics；新增 `TransportSemantics`、ordering/backpressure/failure descriptors、in-proc close/drain/closed poll 语义
- 验证：`cd bedrock-rs && cargo fmt`；`cd bedrock-rs && cargo test`，21 个单元测试通过

## [2026-05-11] ingest | Rust Bedrock MDS Foundation Milestone 4

- 来源：implementation handoff
- 页面：`wiki/pages/architecture/rust-bedrock-mds-foundation.md`
- 变更：为 common/transport/md/replay 四个 crate 增加 README 和 crate-level rustdoc，明确 ownership boundary、非职责、public API 和验证入口
- 验证：`cd bedrock-rs && cargo fmt`；`cd bedrock-rs && cargo test`；`cd bedrock-rs && cargo doc --no-deps`

## [2026-05-11] ingest | Rust Bedrock MDS Foundation Milestone 5

- 来源：implementation handoff
- 页面：`wiki/pages/architecture/rust-bedrock-mds-foundation.md`
- 变更：新增 `BookKey`、`BookRouter` 和 multi-instrument replay；支持 per `(venue, instrument)` 独立 sequence state，验证 interleaved instruments 与 gap isolation
- 验证：`cd bedrock-rs && cargo fmt`；`cd bedrock-rs && cargo test`，25 个单元测试通过；`cd bedrock-rs && cargo doc --no-deps`

## [2026-05-12] ingest | Rust Bedrock MDS Foundation Milestone 6

- 来源：implementation handoff
- 页面：`wiki/pages/architecture/rust-bedrock-mds-foundation.md`
- 变更：新增 `InstrumentRegistry`、`UnknownInstrumentPolicy` 和 `BookRouter::with_registry`；严格 allow-list 模式拒绝未知 `(venue, instrument)`，并扩展 replay registry directive 与 fixtures
- 验证：`cd bedrock-rs && cargo fmt`；`cd bedrock-rs && cargo test`，30 个单元测试通过；`cd bedrock-rs && cargo clippy --all-targets -- -D warnings`；`cd bedrock-rs && cargo doc --no-deps`

## [2026-05-12] ingest | Rust Venue Sequence Rules

- 来源：官方 Binance/Bitget API 文档核对与 `docs/superpowers/specs/2026-05-12-rust-venue-sequence-rules-design.md`
- 页面：`wiki/pages/architecture/rust-venue-sequence-rules.md`
- 变更：固化 Binance Spot `U/u`、Binance USDⓈ-M Futures `U/u/pu`、Bitget UTA `seq/pseq` 的 snapshot/delta/gap/rebuild 合同；明确 Java scalar `seq + 1` validator 不可作为 Rust live feed 基础
- 验证：官方链接已核对；`cd bedrock-rs && cargo test`
