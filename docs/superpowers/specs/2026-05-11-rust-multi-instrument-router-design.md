# Rust Multi-Instrument Router Design

日期：2026-05-11

状态：Implemented milestone 5

## Goal

把 Bedrock 的核心约束“单 instrument 内严格有序，instrument 之间独立并行”落到 Rust MDS foundation：为每个 `(venue, instrument)` 维护独立 `BookReconstructor` 和 sequence state，并让 replay fixture 可以混合多个 instrument。

## Scope

本里程碑只修改 `bedrock-rs-md`、`bedrock-rs-replay`、fixtures 和文档。不接 live feed，不实现 transport fan-out，不实现 Pricing/OMS，不改变 order book 存储结构。

## Design

在 `bedrock-rs-md` 中新增：

- `BookKey { venue_id, instrument_id }`
- `BookRouter`

`BookRouter` 使用 `BTreeMap<BookKey, BookReconstructor>` 管理每个 book。router 根据 snapshot/delta 自身携带的 venue/instrument route 到对应 reconstructor；如果不存在则创建。

行为：

- 每个 key 的 sequence state 独立。
- 一个 instrument gap 不影响其他 instrument。
- delta first for a key 会创建 reconstructor，并由该 reconstructor 输出 `DeltaWithoutSnapshot` gap。
- router 不拥有 instrument universe validation；未知 instrument 是否允许由未来 registry / config 决定。

Replay 从单 reconstructor 改为 `BookRouter`，因此单 fixture 可以混合多个 instruments。

## Verification

新增 fixture：

- `multi_instrument_interleaved.events`
- `multi_instrument_gap_isolated.events`

成功标准：

- `cd bedrock-rs && cargo fmt`
- `cd bedrock-rs && cargo test`
- mixed instrument fixture 通过
- gap isolation fixture 通过
- wiki 记录 Milestone 5 状态

## Non-Goals

- 不实现多线程并行。
- 不实现 instrument registry。
- 不实现 live exchange feed fan-out。
- 不实现 transport channel partitioning。

