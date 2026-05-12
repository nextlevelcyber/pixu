# Bedrock Project Baseline

日期：2026-05-07
来源：项目现有 `CLAUDE.md`、`AGENTS.md`、`docs/QT_HFT_MM_ARCHITECTURE.md` 摘要

## 项目定位

Bedrock 是 Java 21 实现的低延迟 HFT Market Maker 系统。当前代码处于 Phase 5（OMS Bus Wiring）完成状态，单进程 tick-to-trade 链路已经打通，下一阶段可进入 Phase 6（multi-process Aeron split）或生产加固。

目标架构是 4 进程设计：

1. MDS：归一化行情。
2. Pricing Engine：FairMid 与 QuoteConstruction。
3. OMS：订单维护、风控、仓位与 ExecGateway。
4. Monitor：只读订阅所有 channel，提供运维观察面。

## 核心约束

- 不再引入独立 directional Strategy 模块；Market Maker 的决策链是 FairMid Calculation -> Quote Construction -> Order Maintenance。
- 每个 instrument 内严格有序，不要求全局有序；每个 instrument 使用独立 LMAX Disruptor。
- 热路径禁止 heap allocation、阻塞 I/O、String 操作和 double 金融计算。
- 价格与数量使用 fixed-point long，scale 为 1e-8。
- OMS 高可用恢复依赖 REST reconciliation，不依赖 Chronicle Queue replay。
- ExecGateway 是 OMS 子模块，生命周期绑定 OMS。

## 主要模块

- `bedrock-sbe`：SBE schema 与 codegen。
- `bedrock-common`：事件 payload、channel SPI、公共模型。
- `bedrock-aeron`：EventBus、InstrumentEventBus、ChannelFactory。
- `bedrock-md-api` / `bedrock-md`：行情接口、feed、L2 order book、sequence validation。
- `bedrock-pricing`：PricingOrchestrator、FairMidPipeline、QuoteConstructPipeline。
- `bedrock-oms`：OrderStateMachine、OrderStore、ExecGateway、PositionTracker、RegionManager、RiskGuard。
- `bedrock-app`：Spring Boot 入口与运行时 wiring。

## 已知注意事项

- Spring profile 必须使用 `development`，不要用 `dev`。
- 运行应用优先使用 `scripts/start.sh`，避免遗漏 JVM `--add-opens`。
- `chronicle-core` 版本 pin 不能移除。
- bus consumer 异常进入 `DeadLetterChannel`，排查事件丢失时需要检查 dead-letter。
- `ExecEvent` 当前可能从 async HTTP thread 回调，生产前需要回投 Disruptor。
- `BedrockApplicationTest` 有既有 Mockito MockMaker 初始化问题，与 Phase 3-5 改动无关。
