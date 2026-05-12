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

## Implementation Fixtures Required Next

下一次写 Rust venue adapter 代码前，应先加 fixtures：

- Binance Spot snapshot bridge success。
- Binance Spot stale event ignored。
- Binance Spot range gap triggers rebuild。
- Binance USDⓈ-M futures `pu` continuity success。
- Binance USDⓈ-M futures `pu` mismatch triggers rebuild。
- Bitget `books` snapshot then bridged update success。
- Bitget `pseq` mismatch triggers rebuild。
- Bitget `pseq = 0` reset path。
- Bitget `books5` snapshot-only behavior。

## Risks

- 交易所文档会变，特别是 Bitget UTA depth/SBE。实现 live feed 前必须再次核对官方文档。
- Java 现有 feed 只能作为探索参考，不能作为 Rust sequence contract 的权威来源。
- REST snapshot latency 会影响 bootstrap buffer size 和 rebuild frequency，后续需要单独设计 metrics。
