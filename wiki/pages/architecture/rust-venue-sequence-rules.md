# Rust Venue Sequence Rules

日期：2026-05-12

来源：

- `wiki/sources/architecture/2026-05-12-binance-bitget-sequence-docs.md`
- `docs/superpowers/specs/2026-05-12-rust-venue-sequence-rules-design.md`

## Scope

本文记录 Rust-first Bedrock 在接入 Binance/Bitget live L2 feed 前必须遵守的 venue-specific sequence contract。

本页只定义规则，不实现 WebSocket、REST snapshot、parser、checksum、SBE 或 reconnect 策略。

## Core Decision

`BookReconstructor` 继续保持 venue-neutral。交易所原生 sequence/bootstrap 规则必须由 venue adapter 负责：

- Binance Spot：`U/u` range bridge 和 range gap detection。
- Binance USDⓈ-M Futures：`U/u` bootstrap plus `pu == previous_u` continuity。
- Bitget UTA：`action=snapshot/update` plus `seq/pseq` continuity。

因此，Rust 不能沿用 Java 当前的单一 `seq == last + 1` validator 作为 live feed 正确性基础。

## Binance Spot

使用 `<symbol>@depth` 或 `<symbol>@depth@100ms` diff depth，并通过 `GET /api/v3/depth` snapshot 初始化。

必须保留原始：

- `U`
- `u`
- `E`
- bid/ask absolute quantity updates

行为：

- WS 先连接并 buffer。
- REST snapshot 提供 `lastUpdateId`。
- stale buffered events 通过 `u <= lastUpdateId` 丢弃。
- 第一条应用事件必须 bridge snapshot 边界：丢弃 stale event 后，要求 `u > lastUpdateId` 且 `U <= lastUpdateId + 1`。
- 后续如 `U > local_update_id + 1`，认为 gap，触发 rebuild。
- 应用成功后，local update id 设置为该事件的 `u`。

## Binance USDⓈ-M Futures

使用 `<symbol>@depth` / `@500ms` / `@100ms` diff depth，并通过 `GET /fapi/v1/depth` snapshot 初始化。

必须保留原始：

- `U`
- `u`
- `pu`
- `E`
- `T`
- bid/ask absolute quantity updates

行为：

- bootstrap 仍需要 WS buffer + REST snapshot。
- 首条应用事件必须 bridge snapshot `lastUpdateId`，满足 `U <= lastUpdateId` 且 `u >= lastUpdateId`。
- 后续每条事件必须满足 `pu == previous_u`。
- `pu` mismatch 直接 gap/rebuild。
- quantity 为 0 删除 level；删除本地不存在 level 是正常情况。

## Bitget UTA

Rust 默认目标应是 UTA JSON `books` channel：

- `books`：首次 `snapshot`，后续 `update`，适合 L2 reconstruction。
- `books1` / `books5` / `books50`：每次 `snapshot`，适合 BBO/top-N snapshot，不应建模为 delta stream。

必须保留原始：

- `action`
- `seq`
- `pseq`
- `ts`
- `maxDepth`
- bid/ask levels

行为：

- 初始 `snapshot` 建立 local book，`local_seq = seq`。
- 首个 update 需要能覆盖 snapshot sequence。
- 正常 update 应满足 `pseq == local_seq`，然后 `local_seq = seq`。
- `pseq = 0` 是 reset/restart 信号，不能继续增量应用旧 book。
- 不依赖 checksum；Bitget 2026-05-12 changelog 已记录 checksum 移除路径，并要求使用 `seq/pseq` 做深度一致性校验。

## Future Rust Adapter Output

venue adapter 后续应输出三类结果给 venue-neutral MDS：

- `VenueSnapshotReady`
- `VenueDeltaBatchReady`
- `VenueGap`

只有通过 venue 原生 continuity 校验的数据才能转成 `BookSnapshot` / `BookDelta` 交给 `BookRouter`。

## Milestone 8: Offline Sequence Policy

状态：complete on 2026-05-13.

已实现 crate：

- `bedrock-rs/crates/bedrock-rs-md-venue`

已实现 public API：

- `SequenceDecision`
- `SequenceGapReason`
- `BinanceSpotSequencePolicy`
- `BinanceFuturesSequencePolicy`
- `BitgetBooksSequencePolicy`
- `BitgetDepthChannelKind`

行为边界：

- 本 crate 只判断 raw venue sequence continuity。
- `Apply` 表示 adapter 可以 normalize 并继续向 `BookRouter` 发送事件。
- `IgnoreStale` 表示旧事件或重复事件，不应下游发布。
- `Gap` 表示必须停止增量应用并 rebuild/resubscribe。
- `ResetRequired` 表示 venue 明确进入 reset/restart 路径，local state 已清空。

验证覆盖：

- Binance Spot snapshot-too-old、snapshot bridge、stale/duplicate、range gap。
- Binance Futures first bridge、`pu` continuity、`pu` mismatch。
- Bitget `books` snapshot/update、`pseq` mismatch、`pseq=0` reset。
- Bitget `books5` snapshot-only classification。

验证结果：

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

`cargo test` 通过：41 个单元测试通过，5 个 doc-test crate 无测试且通过。`cargo clippy --all-targets -- -D warnings` 通过。`cargo doc --no-deps` 通过。

## Milestone 9: Venue Sequence Fixtures

状态：complete on 2026-05-14.

已实现 fixture 目录：

- `bedrock-rs/fixtures/md-venue/`

已实现 fixtures：

- `binance_spot_snapshot_bridge.events`
- `binance_spot_stale_ignored.events`
- `binance_spot_range_gap.events`
- `binance_futures_pu_continuity.events`
- `binance_futures_pu_mismatch.events`
- `bitget_books_seq_pseq.events`
- `bitget_books_pseq_mismatch.events`
- `bitget_books_pseq_zero_reset.events`
- `bitget_channel_kind.events`

实现说明：

- fixture runner 只在 `bedrock-rs-md-venue` tests 中编译，不是生产 API。
- 每个 sequence event 必须紧跟 expectation，避免 silent pass。
- fixtures 使用 raw venue sequence id，允许 Bitget `pseq = 0` reset signal。
- 这些 `.events` 文件是测试合同，应该提交到 git。

验证结果：

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

`cargo test` 通过：50 个单元测试通过，5 个 doc-test crate 无测试且通过。`cargo clippy --all-targets -- -D warnings` 通过。`cargo doc --no-deps` 通过。

## Milestone 10: Raw JSON Parser Skeleton

状态：complete on 2026-05-14.

已实现 raw JSON fixtures：

- `bedrock-rs/fixtures/md-venue/json/binance_spot_depth_update.json`
- `bedrock-rs/fixtures/md-venue/json/binance_spot_combined_depth_update.json`
- `bedrock-rs/fixtures/md-venue/json/binance_futures_depth_update.json`
- `bedrock-rs/fixtures/md-venue/json/bitget_books_snapshot.json`
- `bedrock-rs/fixtures/md-venue/json/bitget_books_update.json`
- `bedrock-rs/fixtures/md-venue/json/bitget_books5_snapshot.json`

已实现 public API：

- `RawLevel`
- `VenueParseError`
- `BinanceSpotDepthUpdate`
- `BinanceFuturesDepthUpdate`
- `BitgetDepthMessage`
- `BitgetDepthAction`
- `parse_binance_spot_depth_update`
- `parse_binance_futures_depth_update`
- `parse_bitget_depth_message`

实现说明：

- 使用 `serde_json::Value` 做结构化 JSON 解析。
- 支持 Binance combined stream wrapper。
- Binance Spot parser 保留 `E/s/U/u/b/a`。
- Binance Futures parser 保留 `E/T/s/U/u/pu/b/a`。
- Bitget parser 保留 `topic/symbol/action/seq/pseq/ts/maxDepth/b/a`。
- price / quantity 保留为 raw string，后续 normalize 阶段再做 fixed-point。

验证覆盖：

- Binance Spot raw depth payload。
- Binance Spot combined stream depth payload。
- Binance Futures depth payload with `pu` and `T`。
- Bitget `books` snapshot/update payload。
- Bitget `books5` snapshot-only payload。

验证结果：

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

`cargo test` 通过：56 个单元测试通过，5 个 doc-test crate 无测试且通过。`cargo clippy --all-targets -- -D warnings` 通过。`cargo doc --no-deps` 通过。

## Milestone 11: Venue Normalization

状态：complete on 2026-05-14.

已实现 public API：

- `VenueNormalizeError`
- `NormalizedDepthEvent`
- `decimal_to_scaled_i64`
- `timestamp_ms_to_ns`
- `normalize_binance_spot_delta`
- `normalize_binance_futures_delta`
- `normalize_bitget_depth_message`

实现说明：

- raw venue parser 继续保留 price / quantity 字符串，normalization 阶段再做 fixed-point。
- decimal parser 使用精确字符串解析，scale 为 `1e-8`，不使用 float。
- Binance Spot/Futures diff depth 转为 `BookDelta`，sequence 使用 raw `u`。
- Bitget `snapshot` 转为 `BookSnapshot`，Bitget `update` 转为 `BookDelta`，sequence 使用 raw `seq`。
- bid levels 转 `Side::Bid`，ask levels 转 `Side::Ask`。
- quantity `0` 是合法 delete/update zero size。

验证覆盖：

- decimal parser 精确转换和超过 8 位小数拒绝。
- Binance Spot JSON fixture -> `BookDelta`。
- Binance Futures JSON fixture -> `BookDelta`。
- Bitget `books` snapshot fixture -> `BookSnapshot`。
- Bitget `books` update fixture -> `BookDelta`。

当前已验证：

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-md-venue
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

`bedrock-rs-md-venue` 32 个单元测试通过。workspace `cargo test` 通过：62 个单元测试通过，5 个 doc-test crate 无测试且通过。`cargo clippy --all-targets -- -D warnings` 通过。`cargo doc --no-deps` 通过。

## Milestone 12: Binance REST Snapshot Parser

状态：complete on 2026-05-14.

官方文档核对：

- Binance Spot REST `GET /api/v3/depth` 返回 `lastUpdateId`、`bids`、`asks`。
- Binance USDⓈ-M Futures REST `GET /fapi/v1/depth` 返回 `lastUpdateId`、`E`、`T`、`bids`、`asks`。

已实现 fixtures：

- `bedrock-rs/fixtures/md-venue/json/binance_spot_depth_snapshot.json`
- `bedrock-rs/fixtures/md-venue/json/binance_futures_depth_snapshot.json`

已实现 public API：

- `BinanceSpotDepthSnapshot`
- `BinanceFuturesDepthSnapshot`
- `parse_binance_spot_depth_snapshot`
- `parse_binance_futures_depth_snapshot`
- `normalize_binance_spot_snapshot`
- `normalize_binance_futures_snapshot`

实现说明：

- REST snapshot sequence 使用 `lastUpdateId`。
- Spot REST depth response 没有 exchange timestamp；Rust normalizer 要求 caller 传入 fetch/receive `TimestampNs`。
- Futures REST snapshot 使用 `E` message output time 作为 `BookSnapshot.timestamp_ns`，并在 raw struct 保留 `T` transaction time。
- price / quantity 继续复用 exact decimal parser，不使用 float。

当前已验证：

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-md-venue
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

`bedrock-rs-md-venue` 36 个单元测试通过。workspace `cargo test` 通过：66 个单元测试通过，5 个 doc-test crate 无测试且通过。`cargo clippy --all-targets -- -D warnings` 通过。`cargo doc --no-deps` 通过。

## Milestone 13: Venue Bootstrap Pipeline

状态：complete on 2026-05-14.

已实现 module：

- `bedrock-rs/crates/bedrock-rs-md-venue/src/pipeline.rs`

已实现 public API：

- `VenuePipelineError`
- `VenuePipelineGap`
- `VenuePipelineOutput`
- `BinanceSpotDepthPipeline`
- `BinanceFuturesDepthPipeline`
- `BitgetBooksDepthPipeline`

实现说明：

- Pipeline 是单 `(venue, instrument)` 状态机，不做网络 IO、不拥有 transport。
- Spot `apply_snapshot` 会 normalize REST snapshot 并设置 `lastUpdateId`，后续 `process_update` 先走 `U/u` policy，再输出 delta / stale ignore / gap。
- Futures `apply_snapshot` 会 normalize REST snapshot，并记录 pending `lastUpdateId`；第一条 diff depth 必须 bridge snapshot，后续必须满足 `pu == previous_u`。
- Bitget `snapshot` message 会设置 local `seq` 并输出 snapshot；`update` 必须满足 `pseq == local_seq`。
- 任一 gap/reset 后 pipeline 清空 state，后续 update 会输出 `NeedsSnapshot` gap，避免误用不可信 book。

当前已验证：

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-md-venue
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

`bedrock-rs-md-venue` 43 个单元测试通过。workspace `cargo test` 通过：73 个单元测试通过，5 个 doc-test crate 无测试且通过。`cargo clippy --all-targets -- -D warnings` 通过。`cargo doc --no-deps` 通过。

## Milestone 14: MD Live Client Boundary

状态：complete with Binance Spot network smoke on 2026-05-14.

已实现 crate：

- `bedrock-rs/crates/bedrock-rs-md-live`

已实现 public API：

- `BinanceMarket`
- `BinanceDepthSpeed`
- `BinanceLiveConfig`
- `BitgetLiveConfig`
- `LiveMarketDataError`
- `unix_now_timestamp_ns`
- `is_bitget_event_frame`
- `binance_spot_smoke_once`
- `binance_futures_smoke_once`
- `bitget_books_smoke_once`

实现说明：

- `bedrock-rs-md-live` 只拥有 public exchange HTTP/WebSocket IO 边界，不拥有 sequence policy、normalization、book reconstruction、transport fanout 或交易决策。
- Binance smoke helpers 先连接 WS 并 buffer 有界 text messages，再 fetch REST snapshot，随后把 snapshot 和 buffered diff depth 交给 `bedrock-rs-md-venue` pipeline；若 buffered frames 都 stale，会继续读 post-snapshot WS frames，直到拿到 delta/gap 或达到 bounded read limit。
- Bitget smoke helper 发送 UTA public WS `books` subscribe frame（当前默认 `instType=usdt-futures`），忽略 event/ack frame，并把 data frame 交给 Bitget pipeline。
- smoke helper 只返回 `VenuePipelineOutput`，不下单、不发布到 Aeron、不驱动 Pricing/OMS。

当前已验证：

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-md-live
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

`bedrock-rs-md-live` 4 个离线单元测试通过。workspace `cargo test` 通过：79 个单元测试通过，6 个 doc-test crate 无测试且通过。`cargo clippy --all-targets -- -D warnings` 通过。`cargo doc --no-deps` 通过。

网络 smoke 已验证：

```bash
cd bedrock-rs && cargo run -p bedrock-rs-md-live --example live_smoke -- binance-spot BNBBTC 8
```

输出摘要：

```text
outputs=11
#0 snapshot sequence=4547269050 bids=1000 asks=1000
#10 delta sequence=4547269053 updates=3
```

Binance Futures network smoke 已验证：

```bash
cd bedrock-rs && cargo run -p bedrock-rs-md-live --example live_smoke -- binance-futures BTCUSDT 8
```

输出摘要：

```text
outputs=11
#0 snapshot sequence=10559073941099 bids=1000 asks=1000
#10 delta sequence=10559073954851 updates=620
```

Bitget books network smoke 已验证连接与风险输出：

```bash
cd bedrock-rs && cargo run -p bedrock-rs-md-live --example live_smoke -- bitget-books BTCUSDT 2
```

输出摘要：

```text
outputs=2
#0 snapshot sequence=569569176400 bids=500 asks=500
#1 gap reason=PreviousSequenceMismatch event_sequence=Some(569569176989)
```

该 Bitget 结果说明 public feed 已接通；后续 update 没有 bridge 当前 snapshot，pipeline 按设计保守输出 gap/rebuild 信号，而不是错误应用不可信增量。

## Milestone 15: MDS Live Routing

状态：complete on 2026-05-17.

已实现 crate：

- `bedrock-rs/crates/bedrock-rs-mds`

已实现 public API：

- `MdsOutput`
- `MdsRouter`

实现说明：

- `MdsRouter` 是薄 composition layer：只把 `VenuePipelineOutput` 路由到 `BookRouter`，不拥有交易所网络、venue sequence policy、normalization、transport fanout、Pricing 或 OMS。
- `VenuePipelineOutput::Normalized(Snapshot)` 走 `BookRouter::apply_snapshot`。
- `VenuePipelineOutput::Normalized(Delta)` 走 `BookRouter::apply_trusted_delta`。
- `VenuePipelineOutput::IgnoredStale` 转成 `MdsOutput::IgnoredStale`，不创建 book。
- `VenuePipelineOutput::Gap` 转成 `MdsOutput::VenueGap`，不合成 delta、不推进 `BookRouter`。

关键决策：

- Binance Spot/Futures diff depth 的 `U/u` 是 venue-native range id，不保证满足 scalar `last_sequence + 1`。
- `bedrock-rs-md-venue` 已经在输出 normalized delta 前验证 raw venue continuity：Spot `U/u` bridge/range gap，Futures `U/u/pu` continuity，Bitget `seq/pseq` continuity。
- 因此 MDS 对 venue pipeline 输出使用 `BookRouter::apply_trusted_delta`，避免对已验证的 range delta 再套一层错误的 scalar continuity。
- `BookRouter::apply_delta` 继续保留严格 scalar continuity，用于 replay 和 scalar-normalized streams。

当前已验证：

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-mds
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
cd bedrock-rs && cargo check -p bedrock-rs-mds --example mds_live_smoke
```

`bedrock-rs-mds` 6 个单元测试通过。workspace `cargo test` 通过：86 个单元测试通过，7 个 doc-test crate 无测试且通过。`cargo clippy --all-targets -- -D warnings` 通过。`cargo doc --no-deps` 通过。`mds_live_smoke` example 编译通过。

网络 smoke 已验证：

```bash
cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-spot BNBBTC 8
cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-futures BTCUSDT 8
cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- bitget-books BTCUSDT 2
```

输出摘要：

```text
binance-spot: snapshot BBO -> stale ignores -> live BBO
binance-futures: snapshot BBO -> stale ignores -> live BBO
bitget-books: snapshot BBO -> live BBO
```

## Milestone 16: Bounded Live Session

状态：complete on 2026-05-17.

已实现 public API：

- `BinanceLiveSessionLimits`
- `binance_spot_session_once`
- `binance_futures_session_once`

实现说明：

- Binance bounded session 复用 WS-first buffer + REST snapshot bootstrap。
- `bootstrap_buffer_messages` 控制 snapshot 前先收集多少 WS depth frames。
- `post_bootstrap_messages` 控制 snapshot/bootstrap 后继续读取多少 live WS depth frames。
- session 不做自动 reconnect/rebuild；venue gap 仍作为 `VenuePipelineOutput::Gap` 暴露给上层。
- `live_smoke` 与 `mds_live_smoke` examples 已支持可选 `[post_bootstrap_messages]` 参数。
- Bitget 继续使用同一 WS channel 的 bounded depth-message count，因为 snapshot/update 都来自该 channel。

当前已验证：

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-md-live
cd bedrock-rs && cargo test -p bedrock-rs-mds
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
cd bedrock-rs && cargo check -p bedrock-rs-md-live --example live_smoke
cd bedrock-rs && cargo check -p bedrock-rs-mds --example mds_live_smoke
```

`bedrock-rs-md-live` 5 个单元测试通过。`bedrock-rs-mds` 6 个单元测试通过。workspace `cargo test` 通过：87 个单元测试通过，7 个 doc-test crate 无测试且通过。`cargo clippy --all-targets -- -D warnings` 通过。`cargo doc --no-deps` 通过。两个 live examples 编译通过。

网络 smoke 已验证：

```bash
cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-spot BNBBTC 8 3
cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-futures BTCUSDT 8 3
cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- bitget-books BTCUSDT 3
```

输出摘要：

```text
binance-spot: outputs=12, snapshot BBO -> stale ignores -> 3 live BBOs
binance-futures: outputs=12, snapshot BBO -> stale ignores -> 2 live BBOs
bitget-books: outputs=3, snapshot BBO -> 2 live BBOs
```

## Milestone 17: MDS In-Proc Fanout

状态：complete on 2026-05-17.

已实现 public API：

- `MdsPublishError`
- `MdsRouter::apply_pipeline_output_to`
- `MdsRouter::apply_pipeline_outputs_to`

实现说明：

- MDS reconstruction 仍由 `MdsRouter` 完成。
- 每个 `MdsOutput` 可以发布到调用方提供的 `Publisher<MdsOutput>`。
- 当前验证使用 `InProcChannel<MdsOutput>`，不把 MDS 绑定到具体部署模式。
- publish backpressure / closed error 会立即返回，并携带已经成功发布的 output 数量。
- publish 失败后不做 rollback；调用方应把该错误视作 stream-fatal，按部署策略 rebuild/resubscribe。

当前已验证：

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-mds
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

`bedrock-rs-mds` 8 个单元测试通过。workspace `cargo test` 通过：89 个单元测试通过，7 个 doc-test crate 无测试且通过。`cargo clippy --all-targets -- -D warnings` 通过。`cargo doc --no-deps` 通过。

## Milestone 18: Keyed Stale Output

状态：complete on 2026-05-17.

已实现 public API：

- `VenuePipelineIgnoredStale`
- `VenuePipelineOutput::IgnoredStale(VenuePipelineIgnoredStale)`
- `MdsOutput::IgnoredStale(VenuePipelineIgnoredStale)`

实现说明：

- Spot/Futures stale 或 duplicate update 现在携带 `venue_id`、`instrument_id`、`event_sequence`。
- MDS 不再输出无 key 的 stale 事件，避免 fanout 后 Monitor/Pricing 无法定位来源。
- live examples 打印 stale 的 venue/instrument/event sequence。
- Bitget 当前 policy 不产生 stale 分支，但类型合同已经统一。

当前已验证：

```bash
cd bedrock-rs && cargo test -p bedrock-rs-md-venue
cd bedrock-rs && cargo test -p bedrock-rs-md-live
cd bedrock-rs && cargo test -p bedrock-rs-mds
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
cd bedrock-rs && cargo check -p bedrock-rs-md-live --example live_smoke
cd bedrock-rs && cargo check -p bedrock-rs-mds --example mds_live_smoke
```

`bedrock-rs-md-venue` 45 个单元测试通过。`bedrock-rs-md-live` 5 个单元测试通过。`bedrock-rs-mds` 8 个单元测试通过。workspace `cargo test` 通过：89 个单元测试通过，7 个 doc-test crate 无测试且通过。`cargo clippy --all-targets -- -D warnings` 通过。`cargo doc --no-deps` 通过。两个 live examples 编译通过。

网络 smoke 已验证：

```bash
cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-spot BNBBTC 8 3
```

输出摘要：

```text
outputs=12, snapshot BBO -> keyed stale ignores -> 3 live BBOs
```

## Milestone 19: MDS Output Envelope

状态：complete on 2026-05-24.

已实现 public API：

- `MdsOutputKind`
- `MdsStreamKey`
- `MdsOutputEnvelope`
- `MdsOutput::envelope`

实现说明：

- BBO envelope 携带 venue/instrument、`Bbo` kind、sequence、timestamp。
- Book gap envelope 携带 venue/instrument、`BookGap` kind、actual sequence，无 timestamp。
- Book reject envelope 使用 actual venue/instrument、`BookReject` kind、sequence，无 timestamp。
- Venue gap envelope 携带 venue/instrument、`VenueGap` kind、event sequence，无 timestamp。
- Ignored stale envelope 携带 venue/instrument、`IgnoredStale` kind、event sequence，无 timestamp。
- envelope 的 `sequence` 使用 `Option<u64>`，因为 venue gap/stale 仍是 venue-native raw id，不强制包装成 venue-neutral `Sequence`。

当前已验证：

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-mds
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

`bedrock-rs-mds` 13 个单元测试通过。workspace `cargo test` 通过：94 个单元测试通过，7 个 doc-test crate 无测试且通过。`cargo clippy --all-targets -- -D warnings` 通过。`cargo doc --no-deps` 通过。

## Milestone 20: MDS Wire Schema Draft

状态：complete on 2026-05-24.

已实现 public API：

- `MDS_WIRE_SCHEMA_ID = 20`
- `MDS_WIRE_SCHEMA_VERSION = 1`
- `MdsWireTemplate`
- `MdsWireEnvelope`
- `MdsWireMessage`
- `MdsOutput::wire_message`

Template id：

- `Bbo = 1200`
- `BookGap = 1201`
- `BookReject = 1202`
- `VenueGap = 1203`
- `IgnoredStale = 1204`

实现说明：

- 这是 Rust-first wire schema draft，不是正式 SBE XML/codegen。
- `MdsWireEnvelope` 固定携带 schema id/version、template id、venue、instrument、sequence、timestamp。
- 缺失 sequence/timestamp/expected identity 使用 `0` 作为 wire null convention。
- BBO payload 使用 `1e8` fixed-point raw price/quantity。
- Book gap / reject / venue gap 的 reason code 使用显式 Rust enum 映射。

当前已验证：

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-mds
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
```

`bedrock-rs-mds` 18 个单元测试通过。workspace `cargo test` 通过：99 个单元测试通过，7 个 doc-test crate 无测试且通过。`cargo clippy --all-targets -- -D warnings` 通过。`cargo doc --no-deps` 通过。

## Implementation Required Next

下一阶段进入 live MDS daemon / Aeron fanout 前，应先补齐：

- 正式 SBE XML/codegen：把 Rust-first `MdsWireMessage` 映射到 schema 文件与 generated codecs。
- Aeron IPC/UDP publication/subscription lifecycle。
- metrics：snapshot latency、buffer depth、stale count、gap count、rebuild count、last sequence。
- Bitget bootstrap/rebuild 策略和 gap frequency 观测。

## Risks

- 交易所文档会变，特别是 Bitget UTA depth/SBE。实现 live feed 前必须再次核对官方文档。
- Java 现有 feed 只能作为探索参考，不能作为 Rust sequence contract 的权威来源。
- REST snapshot latency 会影响 bootstrap buffer size 和 rebuild frequency，后续需要单独设计 metrics。
