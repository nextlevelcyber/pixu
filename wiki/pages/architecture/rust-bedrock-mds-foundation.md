# Rust Bedrock MDS Foundation

日期：2026-05-11

来源：`docs/superpowers/specs/2026-05-11-rust-bedrock-mds-foundation-design.md`

## Scope

本文是 Rust-first Bedrock 第一阶段 MDS foundation 的 wiki 摘要。完整设计 spec 见 `docs/superpowers/specs/2026-05-11-rust-bedrock-mds-foundation-design.md`。

第一阶段目标是建立 `bedrock-rs/` Cargo workspace，并实现可测试的 market data foundation：

- MD/MDS domain model
- transport-neutral event contract
- replay/golden fixture 测试框架
- L2 order book reconstruction
- in-proc transport adapter
- Aeron IPC/UDP adapter 边界

本阶段不实现 Pricing、OMS、Monitor/Ops、真实交易所 live feed、真实下单、Aeron 生产 adapter 或 SBE codegen。

## Workspace Shape

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

职责划分：

- `bedrock-rs-common`：fixed-point value types、venue、instrument、side、timestamp、sequence、event envelope。
- `bedrock-rs-transport`：publisher/subscriber trait、in-proc bounded channel、Aeron IPC/UDP adapter 边界。
- `bedrock-rs-md`：normalized market data model、L2 book、snapshot/delta reconstruction、per-instrument router、BBO generation。
- `bedrock-rs-replay`：fixture loader、multi-instrument replay harness、golden assertions。

不创建 pricing/oms crates，避免过早占坑。

Milestone 4 后，每个 crate 都有 README 和 crate-level rustdoc：

- `bedrock-rs/crates/bedrock-rs-common/README.md`
- `bedrock-rs/crates/bedrock-rs-transport/README.md`
- `bedrock-rs/crates/bedrock-rs-md/README.md`
- `bedrock-rs/crates/bedrock-rs-replay/README.md`

README 说明该 crate owns / does not own / public API / verification / next design questions。

## Reconstruction Contract

`BookReconstructor` 的状态：

- `NeedsSnapshot`
- `Ready`
- `Gapped`

行为：

- snapshot 初始化或恢复 book。
- 连续 delta 更新 price level。
- delta quantity 为 0 删除 price level。
- sequence gap 进入 `Gapped`，停止发布可信 BBO，并输出 `BookGap`。
- 新 snapshot 可以从 `NeedsSnapshot` 或 `Gapped` 恢复到 `Ready`。

第一阶段 fixture 使用 normalized events，不实现 Binance/Bitget live sequence 规则。

Milestone 5 后，`BookRouter` 负责 per `(venue, instrument)` reconstruction ownership：

- 每个 `BookKey { venue_id, instrument_id }` 有独立 `BookReconstructor`。
- snapshot/delta 按事件自身携带的 `(venue, instrument)` 路由。
- 每个 instrument 的 sequence state 独立。
- 一个 instrument 进入 `Gapped` 不影响其他 instrument。
- delta first for a new key 会创建 book，并由 reconstructor 输出 `DeltaWithoutSnapshot` gap。
- Milestone 5 阶段 router 暂不拥有 instrument universe validation；该边界已在 Milestone 6 由 registry/config 补上。

Milestone 6 后，`BookRouter` 支持显式 instrument registry：

- `BookRouter::new()` 保持 auto-create 行为，便于早期 replay 和开发。
- `InstrumentRegistry::allow_list(...)` 声明允许创建的 `(venue, instrument)`。
- `BookRouter::with_registry(registry)` 在严格模式下拒绝未知 key。
- 未知 key 输出 `RejectReason::UnknownInstrument`，不创建 book，不改变已有 book state，不推进任何 sequence。
- registry unknown reject 的 `BookReject.expected_venue_id` 和 `expected_instrument_id` 为 `None`，因为它不是某个已配置 book 的 identity mismatch。

## Transport Contract

第一阶段 transport trait 是同步、非阻塞：

- `Publisher<T>::publish(event) -> Result<(), PublishError>`
- `Subscriber<T>::poll() -> Result<Option<T>, PollError>`

in-proc adapter 使用 bounded SPSC 起步。满队列返回 backpressure，空队列返回 `Ok(None)`。

Aeron IPC/UDP 是一等部署诉求，但第一阶段只定义 adapter 边界和 feature names，不引入 Aeron binding。

Milestone 3 后，transport semantics 已显式建模：

- `TransportMode`：`InProc`、`AeronIpc`、`AeronUdp`
- `OrderingGuarantee::FifoPerPublisher`
- `BackpressurePolicy::RejectWhenFull`
- `FailureSemantics::ExplicitClose`
- `TransportSemantics::in_proc()` 声明 in-proc 是 non-blocking、FIFO per publisher、满队列拒绝、显式关闭

`InProcChannel` close 语义：

- `close()` 后 `publish` 返回 `PublishError::Closed`
- `close()` 后 `poll` 先 drain 已入队事件
- closed 且 empty 时 `poll` 返回 `PollError::Closed`
- open 且 empty 时 `poll` 返回 `Ok(None)`

## Verification

最小 fixture：

- `basic_snapshot_then_delta`
- `remove_level`
- `sequence_gap`
- `snapshot_recovers_gap`
- `wrong_identity_rejected`
- `multi_instrument_interleaved`
- `multi_instrument_gap_isolated`
- `instrument_registry_allows_configured`
- `instrument_registry_rejects_unknown`

成功标准：

- `cd bedrock-rs && cargo test` 通过。
- domain model validation 有单元测试。
- L2 reconstruction fixture 全部通过。
- in-proc transport publish/poll/backpressure 有单元测试。
- MD、Transport、Replay、Common public API 边界清晰。

## Implementation Status

状态：Milestone 1 complete on 2026-05-11.

已创建：

- `bedrock-rs/Cargo.toml`
- `bedrock-rs/crates/bedrock-rs-common`
- `bedrock-rs/crates/bedrock-rs-transport`
- `bedrock-rs/crates/bedrock-rs-md`
- `bedrock-rs/crates/bedrock-rs-replay`
- `bedrock-rs/fixtures/md/*.events`

已实现：

- fixed-point `Price` / `Quantity`、`TimestampNs`、`Sequence`、`InstrumentId`、`VenueId`、`Side`、`Level`
- transport-neutral `Publisher<T>` / `Subscriber<T>` trait
- bounded SPSC `InProcChannel<T>`，满队列返回 `PublishError::Backpressure`
- `BookReconstructor` 状态机：`NeedsSnapshot`、`Ready`、`Gapped`
- snapshot 初始化、连续 delta 更新、quantity 0 删除 level、sequence gap、snapshot recovery
- pipe-delimited `.events` replay fixture parser
- 四类 golden fixtures：basic update、remove level、sequence gap、snapshot recovers gap

验证结果：

```bash
cd bedrock-rs && cargo fmt
cd bedrock-rs && cargo test
```

`cargo test` 通过：15 个单元测试通过，4 个 doc-test crate 无测试且通过。

## Milestone 2: Contract Hardening

状态：complete on 2026-05-11.

已实现：

- `BookReconstructor` 输出从 `Vec<MarketDataEvent>` 收敛为 `Vec<ReconstructionOutput>`，避免输入事件和 reconstruction output 混在一起。
- 新增 `ReconstructionOutput::Reject(BookReject)`。
- 新增 `RejectReason::WrongVenue` 和 `RejectReason::WrongInstrument`。
- wrong venue / wrong instrument 的 snapshot 或 delta 会被 reject，不改变 book state，不推进 sequence，不产生 BBO，也不触发 gap。
- 如果 venue 和 instrument 都不匹配，优先返回 `WrongVenue`，因为 venue 是更外层 routing 边界。
- replay harness 新增 `expect_reject` directive。
- 新增 `bedrock-rs/fixtures/md/README.md` 记录 fixture contract。
- 新增 `wrong_identity_rejected.events`，验证错误 instrument delta 被拒绝后，正确 delta 仍可使用同一 sequence 更新 book。

验证结果：

```bash
cd bedrock-rs && cargo fmt
cd bedrock-rs && cargo test
```

`cargo test` 通过：18 个单元测试通过，4 个 doc-test crate 无测试且通过。

## Milestone 3: Transport Semantics Hardening

状态：complete on 2026-05-11.

已实现：

- 新增 `TransportMode`、`OrderingGuarantee`、`BackpressurePolicy`、`FailureSemantics`、`TransportSemantics`。
- 新增 `PublishError::Closed` 和 `PollError::Closed`。
- `InProcChannel::semantics()` 返回明确的 in-proc transport contract。
- `InProcChannel::close()` 和 `is_closed()`。
- closed channel 拒绝 publish，但允许 poll drain 剩余事件。
- closed 且 empty 后 poll 返回 `PollError::Closed`。

验证结果：

```bash
cd bedrock-rs && cargo fmt
cd bedrock-rs && cargo test
```

`cargo test` 通过：21 个单元测试通过，4 个 doc-test crate 无测试且通过。

## Milestone 4: Crate Ownership Docs

状态：complete on 2026-05-11.

已实现：

- 为 `bedrock-rs-common`、`bedrock-rs-transport`、`bedrock-rs-md`、`bedrock-rs-replay` 新增 README。
- 为四个 crate 的 `src/lib.rs` 新增 crate-level rustdoc。
- 每个 README 明确 owns、does not own、public API、verification、next design questions。
- 文档保持当前边界：common 不拥有 reconstruction，transport 不拥有业务语义，md 不拥有 venue client / transport / pricing / OMS，replay 不拥有 live feed / storage。

验证结果：

```bash
cd bedrock-rs && cargo fmt
cd bedrock-rs && cargo test
cd bedrock-rs && cargo doc --no-deps
```

`cargo test` 通过：21 个单元测试通过，4 个 doc-test crate 无测试且通过。`cargo doc --no-deps` 通过并生成 crate docs。

## Milestone 5: Multi-Instrument Router

状态：complete on 2026-05-11.

已实现：

- 新增 `BookKey`。
- 新增 `BookRouter`，使用 `BTreeMap<BookKey, BookReconstructor>` 管理 per-instrument book。
- `BookRouter::apply_snapshot` / `apply_delta` 按事件 key route 到对应 reconstructor。
- `BookRouter::state` 和 `len` 提供基本可观测入口。
- replay harness 默认使用 `BookRouter`，支持单 fixture 内混合多个 instruments。
- `single_book` fixture directive 保留单 book 负向测试能力，用于 wrong venue/instrument reject。
- 新增 `multi_instrument_interleaved.events`。
- 新增 `multi_instrument_gap_isolated.events`。

验证结果：

```bash
cd bedrock-rs && cargo fmt
cd bedrock-rs && cargo test
cd bedrock-rs && cargo doc --no-deps
```

`cargo test` 通过：25 个单元测试通过，4 个 doc-test crate 无测试且通过。`cargo doc --no-deps` 通过。

## Milestone 6: Instrument Registry Config

状态：complete on 2026-05-12.

已实现：

- 新增 `UnknownInstrumentPolicy`。
- 新增 `InstrumentRegistry::auto_create()` 和 `InstrumentRegistry::allow_list(...)`。
- 新增 `BookRouter::with_registry(registry)`。
- `BookRouter::new()` 继续保持 auto-create 默认行为。
- 严格 allow-list 模式下，未知 `(venue, instrument)` 输出 `RejectReason::UnknownInstrument`。
- unknown instrument reject 不创建 book、不改变已配置 book 状态、不推进任何 sequence。
- `BookReject.expected_venue_id` / `expected_instrument_id` 改为 `Option`；single-book identity reject 使用 `Some(expected)`，registry unknown reject 使用 `None`。
- replay harness 新增 `registry|auto_create` 和 `registry|reject_unknown|...` directive。
- 新增 `instrument_registry_allows_configured.events`。
- 新增 `instrument_registry_rejects_unknown.events`。

验证结果：

```bash
cd bedrock-rs && cargo fmt
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

`cargo test` 通过：30 个单元测试通过，4 个 doc-test crate 无测试且通过。`cargo clippy --all-targets -- -D warnings` 通过。`cargo doc --no-deps` 通过。

## Handoff Notes

下一阶段优先事项：

- 决定是否将 simple sorted vector 升级为 fixed-capacity price grid；该优化必须在新的 ADR 中说明。
- 在实现 live feed 前，按 `wiki/pages/architecture/rust-venue-sequence-rules.md` 先加 venue-specific fixtures，再实现 parser/sequence policy。
- 为 Aeron IPC/UDP adapter 设计 channel id、stream id、publication/subscription lifecycle 和 loss/backpressure mapping。
- 判断 unknown instrument reject 是否需要额外进入 quarantine/report stream。

## Risks

- 简单 book 结构优先 correctness，后续可能需要 price grid/fixed-capacity arrays 优化。
- 文本 fixture 方便审查，但不是最终高性能 replay 格式。
- Aeron 延后实现会暂时无法验证多进程性能，但能避免第一阶段被 FFI/依赖治理拉偏。
- Java 不作为硬 reference，因此 golden fixture 必须写清楚预期行为。
