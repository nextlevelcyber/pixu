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

## [2026-05-13] ingest | Rust Venue Sequence Policy

- 来源：`docs/superpowers/specs/2026-05-13-rust-venue-sequence-policy-design.md`
- 页面：`wiki/pages/architecture/rust-venue-sequence-rules.md`
- 变更：新增 `bedrock-rs-md-venue` crate，实现 Binance Spot `U/u`、Binance Futures `U/u/pu`、Bitget UTA `seq/pseq` 的离线 sequence policy；保持 `bedrock-rs-md` venue-neutral
- 验证：`cd bedrock-rs && cargo fmt --check`；`cd bedrock-rs && cargo test`，41 个单元测试通过；`cd bedrock-rs && cargo clippy --all-targets -- -D warnings`；`cd bedrock-rs && cargo doc --no-deps`

## [2026-05-14] ingest | Rust Venue Sequence Fixtures

- 来源：`docs/superpowers/specs/2026-05-14-rust-venue-sequence-fixtures-design.md`
- 页面：`wiki/pages/architecture/rust-venue-sequence-rules.md`
- 变更：新增 `bedrock-rs/fixtures/md-venue/*.events` 和 test-only fixture runner，使用 golden files 验证 Binance Spot/Futures 与 Bitget sequence policy 行为
- 验证：`cd bedrock-rs && cargo fmt --check`；`cd bedrock-rs && cargo test -p bedrock-rs-md-venue`；`cd bedrock-rs && cargo test`，50 个单元测试通过；`cd bedrock-rs && cargo clippy --all-targets -- -D warnings`；`cd bedrock-rs && cargo doc --no-deps`

## [2026-05-14] ingest | Rust Venue JSON Parser

- 来源：`docs/superpowers/specs/2026-05-14-rust-venue-json-parser-design.md`
- 页面：`wiki/pages/architecture/rust-venue-sequence-rules.md`
- 变更：为 `bedrock-rs-md-venue` 增加 `serde_json` raw depth parser 和 JSON fixtures，支持 Binance Spot/Futures、Bitget UTA `books`/`books5` depth payload
- 验证：`cd bedrock-rs && cargo fmt --check`；`cd bedrock-rs && cargo test -p bedrock-rs-md-venue`；`cd bedrock-rs && cargo test`，56 个单元测试通过；`cd bedrock-rs && cargo clippy --all-targets -- -D warnings`；`cd bedrock-rs && cargo doc --no-deps`

## [2026-05-14] ingest | Rust Venue Normalization

- 来源：`docs/superpowers/specs/2026-05-14-rust-venue-normalization-design.md`
- 页面：`wiki/pages/architecture/rust-venue-sequence-rules.md`
- 变更：为 `bedrock-rs-md-venue` 增加 fixed-point decimal normalization，把 Binance Spot/Futures raw depth 转成 `BookDelta`，把 Bitget depth snapshot/update 转成 `BookSnapshot` / `BookDelta`
- 验证：`cd bedrock-rs && cargo fmt --check`；`cd bedrock-rs && cargo test -p bedrock-rs-md-venue`，32 个 `bedrock-rs-md-venue` 单元测试通过；`cd bedrock-rs && cargo test`，62 个 workspace 单元测试通过；`cd bedrock-rs && cargo clippy --all-targets -- -D warnings`；`cd bedrock-rs && cargo doc --no-deps`

## [2026-05-14] ingest | Rust Binance REST Snapshot Parser

- 来源：`docs/superpowers/specs/2026-05-14-rust-binance-rest-snapshot-design.md` 和 Binance 官方 Spot/Futures REST depth 文档
- 页面：`wiki/pages/architecture/rust-venue-sequence-rules.md`
- 变更：为 `bedrock-rs-md-venue` 增加 Binance Spot/Futures REST depth snapshot raw structs、parser、fixtures 和 `BookSnapshot` normalization；Spot snapshot timestamp 由 caller 提供，Futures 使用 `E`
- 验证：`cd bedrock-rs && cargo fmt --check`；`cd bedrock-rs && cargo test -p bedrock-rs-md-venue`，36 个 `bedrock-rs-md-venue` 单元测试通过；`cd bedrock-rs && cargo test`，66 个 workspace 单元测试通过；`cd bedrock-rs && cargo clippy --all-targets -- -D warnings`；`cd bedrock-rs && cargo doc --no-deps`

## [2026-05-14] ingest | Rust Venue Bootstrap Pipeline

- 来源：`docs/superpowers/specs/2026-05-14-rust-venue-bootstrap-pipeline-design.md`
- 页面：`wiki/pages/architecture/rust-venue-sequence-rules.md`
- 变更：新增 `pipeline.rs`，实现 Spot/Futures/Bitget 单 instrument 离线 bootstrap pipeline，把 snapshot、sequence policy、normalization、stale ignore 和 gap/reset 输出串成 live client 可复用的状态机
- 验证：`cd bedrock-rs && cargo fmt --check`；`cd bedrock-rs && cargo test -p bedrock-rs-md-venue`，43 个 `bedrock-rs-md-venue` 单元测试通过；`cd bedrock-rs && cargo test`，73 个 workspace 单元测试通过；`cd bedrock-rs && cargo clippy --all-targets -- -D warnings`；`cd bedrock-rs && cargo doc --no-deps`

## [2026-05-14] ingest | Rust MD Live Client Boundary

- 来源：`docs/superpowers/specs/2026-05-14-rust-md-live-client-design.md` 和 Binance/Bitget 官方 public market data 文档
- 页面：`wiki/pages/architecture/rust-venue-sequence-rules.md`，`wiki/pages/architecture/rust-bedrock-mds-foundation.md`
- 变更：新增 `bedrock-rs-md-live` crate，负责 Binance Spot/Futures REST/WS endpoint、Bitget UTA subscribe JSON、one-shot live smoke helpers；网络层只返回 `VenuePipelineOutput`，不拥有 reconstruction/transport/pricing/OMS
- 验证：`cd bedrock-rs && cargo fmt --check`；`cd bedrock-rs && cargo test -p bedrock-rs-md-live`，4 个离线单元测试通过；`cd bedrock-rs && cargo test`，79 个 workspace 单元测试通过；`cd bedrock-rs && cargo clippy --all-targets -- -D warnings`；`cd bedrock-rs && cargo doc --no-deps`；Binance Spot/Futures network smoke 成功输出 snapshot + live delta；Bitget network smoke 成功输出 snapshot + sequence gap 风险信号

## [2026-05-17] ingest | Rust MDS Live Routing

- 来源：`docs/superpowers/specs/2026-05-17-rust-mds-live-routing-design.md`
- 页面：`wiki/pages/architecture/rust-venue-sequence-rules.md`，`wiki/pages/architecture/rust-bedrock-mds-foundation.md`
- 变更：新增 `bedrock-rs-mds` crate，把 `VenuePipelineOutput` 路由到 `BookRouter` 并输出 MDS reconstruction/BBO/stale/gap；新增 `BookRouter::apply_trusted_delta`，让已通过 venue-native `U/u`、`U/u/pu`、`seq/pseq` 校验的 live delta 不再被 scalar `seq + 1` 规则误判为 gap
- 验证：`cd bedrock-rs && cargo fmt --check`；`cd bedrock-rs && cargo test -p bedrock-rs-mds`，6 个单元测试通过；`cd bedrock-rs && cargo test`，86 个 workspace 单元测试通过；`cd bedrock-rs && cargo clippy --all-targets -- -D warnings`；`cd bedrock-rs && cargo doc --no-deps`；`cd bedrock-rs && cargo check -p bedrock-rs-mds --example mds_live_smoke`；Binance Spot/Futures/Bitget MDS network smoke 成功输出 live BBO

## [2026-05-17] ingest | Rust MD Live Bounded Session

- 来源：`docs/superpowers/specs/2026-05-17-rust-md-live-bounded-session-design.md`
- 页面：`wiki/pages/architecture/rust-venue-sequence-rules.md`
- 变更：新增 `BinanceLiveSessionLimits`、`binance_spot_session_once`、`binance_futures_session_once`；`live_smoke` 和 `mds_live_smoke` 支持 snapshot/bootstrap 后继续读取 bounded live depth frames
- 验证：`cd bedrock-rs && cargo fmt --check`；`cd bedrock-rs && cargo test -p bedrock-rs-md-live`，5 个单元测试通过；`cd bedrock-rs && cargo test -p bedrock-rs-mds`，6 个单元测试通过；`cd bedrock-rs && cargo test`，87 个 workspace 单元测试通过；`cd bedrock-rs && cargo clippy --all-targets -- -D warnings`；`cd bedrock-rs && cargo doc --no-deps`；`cd bedrock-rs && cargo check -p bedrock-rs-md-live --example live_smoke`；`cd bedrock-rs && cargo check -p bedrock-rs-mds --example mds_live_smoke`；Binance Spot/Futures/Bitget bounded MDS smoke 成功输出连续 live BBO

## [2026-05-17] ingest | Rust MDS In-Proc Fanout

- 来源：`docs/superpowers/specs/2026-05-17-rust-mds-inproc-fanout-design.md`
- 页面：`wiki/pages/architecture/rust-venue-sequence-rules.md`，`wiki/pages/architecture/rust-bedrock-mds-foundation.md`
- 变更：`bedrock-rs-mds` 新增 `MdsPublishError`、`apply_pipeline_output_to`、`apply_pipeline_outputs_to`，可把 `MdsOutput` 发布到 transport-neutral `Publisher<MdsOutput>`；当前用 `InProcChannel` 验证 BBO 发布和 backpressure 显式返回
- 验证：`cd bedrock-rs && cargo fmt --check`；`cd bedrock-rs && cargo test -p bedrock-rs-mds`，8 个单元测试通过；`cd bedrock-rs && cargo test`，89 个 workspace 单元测试通过；`cd bedrock-rs && cargo clippy --all-targets -- -D warnings`；`cd bedrock-rs && cargo doc --no-deps`

## [2026-05-17] ingest | Rust MDS Keyed Stale Output

- 来源：`docs/superpowers/specs/2026-05-17-rust-mds-stale-identity-design.md`
- 页面：`wiki/pages/architecture/rust-venue-sequence-rules.md`
- 变更：`VenuePipelineOutput::IgnoredStale` 和 `MdsOutput::IgnoredStale` 改为携带 `VenuePipelineIgnoredStale { venue_id, instrument_id, event_sequence }`，避免 live fanout 后 stale 事件丢失来源 identity
- 验证：`cd bedrock-rs && cargo test -p bedrock-rs-md-venue`，45 个单元测试通过；`cd bedrock-rs && cargo test -p bedrock-rs-md-live`，5 个单元测试通过；`cd bedrock-rs && cargo test -p bedrock-rs-mds`，8 个单元测试通过；`cd bedrock-rs && cargo test`，89 个 workspace 单元测试通过；`cd bedrock-rs && cargo clippy --all-targets -- -D warnings`；`cd bedrock-rs && cargo doc --no-deps`；`cd bedrock-rs && cargo check -p bedrock-rs-md-live --example live_smoke`；`cd bedrock-rs && cargo check -p bedrock-rs-mds --example mds_live_smoke`；Binance Spot bounded MDS smoke 成功输出 keyed stale 和 live BBO

## [2026-05-24] ingest | Rust MDS Output Envelope

- 来源：`docs/superpowers/specs/2026-05-24-rust-mds-output-envelope-design.md`
- 页面：`wiki/pages/architecture/rust-venue-sequence-rules.md`，`wiki/pages/architecture/rust-bedrock-mds-foundation.md`
- 变更：`bedrock-rs-mds` 新增 `MdsOutputKind`、`MdsStreamKey`、`MdsOutputEnvelope` 和 `MdsOutput::envelope()`，为 BBO、BookGap、BookReject、VenueGap、IgnoredStale 提供统一 stream routing metadata
- 验证：`cd bedrock-rs && cargo fmt --check`；`cd bedrock-rs && cargo test -p bedrock-rs-mds`，13 个单元测试通过；`cd bedrock-rs && cargo test`，94 个 workspace 单元测试通过；`cd bedrock-rs && cargo clippy --all-targets -- -D warnings`；`cd bedrock-rs && cargo doc --no-deps`

## [2026-05-24] ingest | Rust MDS Wire Schema Draft

- 来源：`docs/superpowers/specs/2026-05-24-rust-mds-wire-schema-draft-design.md`
- 页面：`wiki/pages/architecture/rust-venue-sequence-rules.md`，`wiki/pages/architecture/rust-bedrock-mds-foundation.md`
- 变更：`bedrock-rs-mds` 新增 Rust-first wire schema draft：`MDS_WIRE_SCHEMA_ID=20`、`MDS_WIRE_SCHEMA_VERSION=1`、template ids `1200..1204`、`MdsWireEnvelope`、`MdsWireMessage` 和 `MdsOutput::wire_message()`；锁定 reason code 与 wire null convention
- 验证：`cd bedrock-rs && cargo fmt --check`；`cd bedrock-rs && cargo test -p bedrock-rs-mds`，18 个单元测试通过；`cd bedrock-rs && cargo test`，99 个 workspace 单元测试通过；`cd bedrock-rs && cargo clippy --all-targets -- -D warnings`；`cd bedrock-rs && cargo doc --no-deps`
