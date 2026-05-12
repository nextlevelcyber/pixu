# Orderbook Market Data

日期：2026-05-10

来源：`wiki/sources/business/orderbook-market-data.md`

## Scope

本文整理 L1、L2、L3 market data 的业务含义、适用场景和对当前 Bedrock MDS 的设计启发。源文档来自 CoinAPI.io 关于 crypto order book 数据层级的说明。

## Data Levels

### Level 1

L1 是 top-of-book 视图，通常包含：

- best bid / best ask
- last trade price
- best bid / ask size 或相关成交量

L1 适合价格展示、基础告警、低频策略和轻量 dashboard。它能给出当前买卖价差和最近成交状态，但不能解释价差背后的深度结构，也不能可靠估计滑点和排队风险。

### Level 2

L2 是按价格档位聚合的 order book depth，通常包含：

- 多档 bid / ask price level
- 每档聚合 size
- 实时 liquidity、depth imbalance 和短期供需压力

L2 是当前 crypto HFT / market making 的核心输入之一。它适合：

- 估计 slippage 和 execution risk
- 计算 order book imbalance
- 观察 liquidity clustering
- 构建被动挂单、短期价格压力、套利和延迟敏感策略

工程上，L2 的难点不在概念，而在可用性：不同交易所 schema、depth limit、snapshot/delta 语义、序列号模型、节流策略、WebSocket 断线恢复方式都不一致。可靠的 L2 系统需要持续处理 snapshot rebuild、delta buffering、gap detection、reconnect、normalization 和 stale book 监控。

### Level 3

L3 是逐订单视图，通常包含：

- individual order id
- price、quantity、side
- timestamp
- sequence number
- new / modify / cancel 等事件类型
- 可推导或直接观察的 queue position

L3 适合高级 market microstructure 分析、queue modeling、latency optimization、order-flow prediction、spoofing/layering/iceberg 行为研究。代价是数据量和处理复杂度显著高于 L2，而且 crypto 交易所的 L3 可用性不一致：并非所有主流 venue 都提供完整逐订单 feed。

## Strategy Implications

对 Bedrock 这类 HFT market maker：

- L1 可以作为低成本健康检查和兜底行情信号，但不足以支撑高质量做市。
- L2 是当前 MDS 的主战场，应优先保证 book reconstruction 正确、低延迟、可观测。
- L3 可以作为未来研究方向，但不应阻塞当前 L2 MDS 稳定化；如果引入，需要单独设计更高吞吐的 event model、storage/replay 和 queue-position analytics。

可从 L2 提取的第一批策略特征包括：

- top-N depth imbalance
- spread 和 mid-price movement
- best level size decay
- liquidity clustering
- book pressure before breakout
- snapshot/delta gap、stale book、rebuild frequency 等数据质量指标

## MDS Design Notes

当前 `MDS Current Status` 页面显示系统已有 Binance/Bitget feed、L2 order book、sequence validation、REST snapshot fetcher 和 BBO 发布能力。结合本 source，后续推进应把 MDS 明确定位为：

- ingestion：交易所原生 WebSocket / REST snapshot 接入
- normalization：统一 symbol、side、price level、size、timestamp、sequence 语义
- reconstruction：按 venue 原生规则完成 snapshot + incremental delta 闭环
- publication：发布 BBO、book delta、market tick 以及后续可计算的 depth features
- observability：暴露 gap、rewind、snapshot latency/failure、book stale、publish drop、BBO freshness

L2 数据链路的最低正确性标准：

- 每个 instrument 的 snapshot 与 delta 必须有明确串行化边界。
- sequence gap 必须触发可观测的 rebuild 或 quarantine。
- rebuild 完成后应发布 fresh BBO 或显式标记 book 已恢复。
- 任何跨 venue comparison 都必须先统一 timestamp 和 symbol 语义。

## Open Questions

- Bedrock 是否只支持 L2 normalized book，还是需要在 API contract 上预留 L3 event 类型？
- 是否需要把 L1/BBO 明确视为 L2 book 的 derived view，而不是独立行情源？
- 对 Binance/Bitget 的 sequence 和 snapshot/delta 规则，是否应分别建立 venue-specific reconstruction spec？
- 策略层需要 top-N depth features 还是完整 depth stream？
