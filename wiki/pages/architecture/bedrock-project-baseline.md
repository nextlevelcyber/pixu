# Bedrock Project Baseline

Bedrock 是 Java 21 实现的低延迟 HFT Market Maker 系统。当前 Phase 5（OMS Bus Wiring）已完成，单进程 tick-to-trade 链路已经打通，后续方向是 Phase 6 的 multi-process Aeron split 或生产加固。

## Architecture

目标是 4 进程架构：

1. MDS 归一化行情。
2. Pricing Engine 负责 FairMid 与 QuoteConstruction。
3. OMS 负责订单维护、风控、仓位和 ExecGateway。
4. Monitor 只读订阅所有 channel 并暴露运维观察面。

Market Maker 不使用独立 directional Strategy 模块。核心决策链是：

```text
FairMid Calculation -> Quote Construction -> Order Maintenance
```

每个 instrument 内部严格有序，instrument 之间独立并行，不要求全局有序。

## Module Boundaries

| 模块 | 职责 |
|------|------|
| `bedrock-sbe` | SBE schema 与 codegen |
| `bedrock-common` | 事件 payload、channel SPI、公共模型 |
| `bedrock-aeron` | EventBus、InstrumentEventBus、ChannelFactory |
| `bedrock-md-api` / `bedrock-md` | 行情接口、feed、L2 order book、sequence validation |
| `bedrock-pricing` | PricingOrchestrator、FairMidPipeline、QuoteConstructPipeline |
| `bedrock-oms` | OrderStateMachine、OrderStore、ExecGateway、PositionTracker、RegionManager、RiskGuard |
| `bedrock-app` | Spring Boot 入口与运行时 wiring |

## Hot Path Rules

- 禁止 heap allocation、阻塞 I/O、String 操作和 double 金融计算。
- 价格与数量使用 fixed-point long，scale 为 1e-8。
- SBE codec 使用 DirectBuffer，不在 codec 热路径做反射或字符串转换。
- bus loop 线程不能阻塞；策略或风控遇到 backlog 时只能更新状态或暂停发单。

## Operational Notes

- Spring profile 使用 `development`，不要用 `dev`。
- 运行应用优先用 `scripts/start.sh`，避免遗漏 JVM `--add-opens`。
- `chronicle-core` 版本 pin 不能移除。
- 事件丢失排查要检查 `DeadLetterChannel`。
- `ExecEvent` 当前可能从 async HTTP thread 回调，生产前应回投 Disruptor。
