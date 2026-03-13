# Bedrock — HFT Market Maker Infrastructure

Java 21 低延迟做市基础设施。单进程 tick-to-trade 闭环已完整实现（Phase 0–5），支持可插拔部署（单进程 in-proc / 多进程 Aeron IPC/UDP）。

---

## 架构概览

```
┌─────────────────────────────────────────────────────────────────┐
│                    bedrock-app (Spring Boot)                    │
│                                                                 │
│  BinanceFeed/BitgetFeed                                         │
│       │ BBO / MARKET_TICK                                       │
│       ▼                                                         │
│  InstrumentEventBus (per-symbol LMAX Disruptor)                 │
│       │                                                         │
│       ├──▶ PricingBusConsumer                                   │
│       │      │ onBbo() → FairMidPipeline → QuoteConstructPipeline│
│       │      │ onTrade() → TradeFlowComponent (EWMA)            │
│       │      │ onPosition() → DeltaSkewComponent (feedback)     │
│       │      └──▶ publish QUOTE_TARGET                          │
│       │                │                                        │
│       └──▶ OmsBusConsumer ◀──────────────────────────────────  │
│              │ RegionManager.diff() → RiskGuard.preCheck()      │
│              │ ExecGateway.placeOrder() / cancelOrder()         │
│              │ ExecEvent → OrderStateMachine → PositionTracker  │
│              └──▶ publish POSITION_UPDATE ──────────────────▶  │
└─────────────────────────────────────────────────────────────────┘
```

目标：4 进程分离（MDS / Pricing / OMS / Monitor），进程间用 Aeron IPC/UDP + SBE 通信。当前实现：单进程，全链路 in-proc Disruptor。

---

## 模块说明

```
bedrock/
 ├─ bedrock-sbe/        SBE schema + codegen（无外部依赖）
 ├─ bedrock-common/     事件模型、EventEnvelope、EventSerde、OrderIdGenerator
 ├─ bedrock-aeron/      InstrumentEventBus（LMAX Disruptor）、CrossInstrumentBridge
 ├─ bedrock-monitor/    MetricRegistry、MemoryMappedMetricRegistry（Chronicle Map）
 ├─ bedrock-md-api/     MarketDataService 接口
 ├─ bedrock-md/         行情接入：BinanceFeed、BitgetV3Feed、L2OrderBook、SequenceValidator
 ├─ bedrock-pricing/    PricingOrchestrator、FairMidPipeline、QuoteConstructPipeline
 ├─ bedrock-oms/        OrderStateMachine、OrderStore、ExecGateway、PositionTracker、RegionManager
 ├─ bedrock-strategy/   （遗留）SimpleMaStrategy、SimpleMarketMakingStrategy
 ├─ bedrock-adapter/    BinanceTradingAdapter、BitgetTradingAdapter、SimulationTradingAdapter
 └─ bedrock-app/        Spring Boot 装配入口，连接所有模块
```

**依赖方向（不可逆）：**
```
sbe → common → aeron
                 ↓
monitor   md-api → md → pricing
                       → oms
strategy  adapter
     ↓        ↓     ↓     ↓
              bedrock-app
```

Maven Enforcer 规则阻止上层模块被下层引用（如 `bedrock-md` 不能依赖 `bedrock-app`）。

---

## 实现进度

| Phase | 内容 | 状态 | 测试 |
|---|---|---|---|
| 0 | 基础层：SBE schema、OrderIdGenerator、ChannelConstants、InstrumentRegistry | ✅ | — |
| 1 | MDS 重构：L2OrderBook、SequenceValidator、RestSnapshotFetcher、InstrumentEventBus | ✅ | 通过 |
| 2 | OMS 重建：OrderStateMachine、OrderStore、BinanceExecGateway、PositionTracker、RegionManager、RiskGuard | ✅ | 102 个 |
| 3 | Pricing Engine：PricingOrchestrator、FairMidPipeline、QuoteConstructPipeline、5 个组件 | ✅ | 65 个 |
| 4 | Pricing Bus 接入：BboPayload、PricingBusConsumer、PricingEngineCoordinator | ✅ | 21 个 |
| 5 | OMS Bus 接入：OmsBusConsumer、OmsCoordinator、POSITION_UPDATE 反馈闭环 | ✅ | 13 个 |

**当前总计：201+ 单元测试，0 失败。**

---

## 核心组件

### Pricing Engine（bedrock-pricing）

做市商无方向性 Alpha，决策链为：

```
FairMid 计算 → Quote 构建 → 报价维护（OMS）
```

**FairMid 组件**（叠加 offset 到 mid seed `(bid+ask)>>>1`）：
- `DepthFollowComponent`：L2 前 N 档 VWAP 加权
- `IndexFollowComponent`：参考指数价格偏差修正
- `TradeFlowComponent`：成交方向 EWMA 动量

**Quote 构建组件**（bid/ask 分别计算 offset）：
- `BaseSpreadComponent`：固定半价差
- `VolatilitySpreadComponent`：基于最近成交波动率动态展宽
- `DeltaSkewComponent`：净持仓偏斜（多仓 → ask 更激进）
- `PositionBoundComponent`：超软限制时挂单标志位抑制

### OMS（bedrock-oms）

- **OrderStateMachine**：静态转换表，11 条有效转换，终态阻断
- **OrderStore**：Agrona `Long2ObjectHashMap` 双索引（internalId + exchOrderId），零装箱
- **ExecGateway**：Binance HMAC-SHA256 REST + 私有 WS；`simulation` 模式为 no-op
- **PositionTracker**：热路径 < 500ns，全 primitive long，加权均价，未实现 PnL
- **RegionManager**：`diff(QuoteTarget, OrderStore)` → `List<OrderAction>`（NewOrder / CancelOrder）
- **RiskGuard**：价格偏差 / 挂单数量 / 持仓限额 / 速率限制，`volatile` 字段热更新；支持 `snapshotLimits()/applyLimits()` 进行热更新回滚

### Market Data（bedrock-md）

- **L2OrderBook**：纯 `long[]` 数组，20 档深度，二分搜索插入/删除，零 GC
- **SequenceValidator**：per-symbol O(1)，5 种状态（VALID / NEED_SNAPSHOT / GAP / DUPLICATE / REWIND）
- **RestSnapshotFetcher**：GAP 时异步拉取 Binance/Bitget REST 快照，Jackson streaming parser
- **BinanceFeed / BitgetV3Feed**：序列号校验 → applyDelta → 发布 BBO + BOOK_DELTA

---

## 构建与运行

### 环境要求

- Java 21+
- Maven 3.9.9（路径：`/Users/kaymen/tool/apache-maven-3.9.9`）
- Maven local repo：`/Users/kaymen/tool/repository`

### 构建命令

```bash
# 脚本方式（推荐）
./scripts/build.sh                     # 全量构建
./scripts/build.sh -c -t              # 清理 + 跳过测试
./scripts/build.sh -m app             # 仅构建 bedrock-app

# Maven 直接调用
MVN="/Users/kaymen/tool/apache-maven-3.9.9/bin/mvn \
  --settings /Users/kaymen/tool/apache-maven-3.9.9/conf/settings.xml \
  -Dmaven.repo.local=/Users/kaymen/tool/repository"

$MVN clean package -DskipTests -pl bedrock-app -am
```

### 运行

```bash
# 开发模式（前台）
./scripts/start.sh -p development -m FULL

# 后台模式
./scripts/start.sh -p development -b

# 远程调试（端口 5005）
./scripts/start.sh -p development -d 5005

# 指定总线模式
./scripts/start.sh --bus-mode IN_PROC      # 单进程（默认）
./scripts/start.sh --bus-mode AERON_IPC    # 多进程 IPC
./scripts/start.sh --bus-mode AERON_UDP    # 跨机 UDP

# 多实例命名（独立 pid/log）
./scripts/start.sh -I pricing -b --bus-mode AERON_IPC
./scripts/stop.sh --instance pricing

# 显式指定可执行 jar（建议用于多进程回归）
./scripts/start.sh --jar-file bedrock-app/target/bedrock-app-1.0.0-SNAPSHOT.jar --no-build -b

# Phase 6：一键拉起 4 进程（MDS/Pricing/OMS/Monitor）
./scripts/start-phase6.sh
./scripts/start-phase6.sh --jar-file bedrock-app/target/bedrock-app-1.0.0-SNAPSHOT.jar
./scripts/stop-phase6.sh
./scripts/phase6-smoke.sh                  # 自动构建/复用可执行 jar + 启停 + 健康检查 + OMS recoveryReady 检查
./scripts/phase6-smoke.sh --skip-build     # 若 jar 缺失则失败（不触发构建）
./scripts/phase6-smoke.sh --jar-file bedrock-app/target/bedrock-app-1.0.0-SNAPSHOT.jar
./scripts/phase6-smoke.sh --skip-recovery-check   # 跳过 OMS recoveryReady 校验（仅排查用）

# 停止
./scripts/stop.sh
```

> **Chronicle 启动**：`MemoryMappedMetricRegistry` 需要 `--add-opens` JVM 参数，请务必通过 `start.sh` 启动，不要直接 `java -jar`。

### 测试

```bash
./scripts/test.sh             # 全部测试
./scripts/test.sh -u          # 单元测试
./scripts/test.sh -m oms      # 指定模块
./scripts/test.sh --phase1-gate   # 执行 Phase 1 OMS 门禁（含 OMS/App 定向回归）
./scripts/test.sh --phase1-gate --phase1-latency-url http://127.0.0.1:8080/api/v1/oms/latency
./scripts/test.sh --phase1-gate --phase1-latency-file /tmp/oms-latency.json
./scripts/test.sh --phase1-gate --phase1-recovery-url http://127.0.0.1:8080/api/v1/oms/recovery
./scripts/test.sh --phase1-gate --phase1-recovery-file /tmp/oms-recovery.json
./scripts/test.sh --phasec-gate   # 执行 Phase C OMS 生产硬化门禁（对账/启动恢复/风控回滚）
./scripts/test.sh --phase-d-pack  # 测试后生成 Phase D Go/No-Go 决策包
./scripts/test.sh --phase6-smoke  # 执行 Phase 6 4进程冒烟
./scripts/phasec-gate.sh          # 直接执行 Phase C 门禁
./scripts/phase-d-pack.sh         # 生成 Phase D 上线决策包（默认 Phase1 + PhaseC）
./scripts/phase-d-pack.sh --with-phase6 --phase1-latency-file /tmp/oms-latency.json --phase1-recovery-file /tmp/oms-recovery.json

# Maven 直接
$MVN test -pl bedrock-oms
$MVN test -pl bedrock-pricing
$MVN test -pl bedrock-app -Dtest="OmsBusConsumerTest,OmsCoordinatorTest,OmsUnifiedBusOrderingIntegrationTest"
```

---

## 配置

所有配置在 `bedrock-app/src/main/resources/application.yml`，命名空间 `bedrock.*`。

Spring 环境名：`development` / `production` / `test`（注意：不能用 `dev`）。

### 关键开关

```yaml
bedrock:
  bus:
    mode: IN_PROC          # IN_PROC | AERON_IPC | AERON_UDP
    payloadCodec: JSON     # JSON | SBE

  md:
    binance:
      enabled: true
      symbols: [BTCUSDT, ETHUSDT]

  pricing:
    enabled: false         # true 启动 PricingEngineCoordinator
    symbols: [BTCUSDT, ETHUSDT]

  oms:
    enabled: false         # true 启动 OmsCoordinator
    symbols: [BTCUSDT, ETHUSDT]
    exchange: binance      # binance | simulation（simulation = no-op，测试用）
```

> OMS 采用无持久化启动恢复：进程启动后通过交易所 `openOrders` 自动重建本地挂单状态。
> 若检测到无法映射到本系统 `clientOrderId` 的外部挂单，恢复判定为失败并保持报价门禁关闭（可用 `/api/v1/oms/recovery/{symbol}/retry` 重试）。

### 交易所凭证（环境变量）

```bash
export BINANCE_API_KEY=xxx
export BINANCE_SECRET_KEY=xxx
export BITGET_API_KEY=xxx
export BITGET_SECRET_KEY=xxx
export BITGET_PASSPHRASE=xxx
```

### 启用完整做市闭环

```yaml
bedrock:
  pricing:
    enabled: true
    symbols: [BTCUSDT]
  oms:
    enabled: true
    symbols: [BTCUSDT]
    exchange: binance
```

---

## SBE 消息协议

定义于 `bedrock-sbe/src/main/resources/sbe/bedrock-messages.xml`。

| 消息 | Stream ID | 用途 |
|---|---|---|
| `BboEvent` | 1100 | MDS → Pricing：逐档最优报价 |
| `DepthEvent` | 1101 | MDS → Pricing：L2 深度快照 |
| `TradeEvent` | 1102 | MDS → Pricing：公开成交 |
| `QuoteTarget` | 2100 | Pricing → OMS：报价维护目标 |
| `ExecEvent` | 3100 | ExecGateway → OMS：执行回报（内部回调） |
| `PositionEvent` | 3101 | OMS → Pricing：持仓反馈（delta skew） |

所有价格和数量使用 **定点整数（scale 1e-8）**：`(long)(doubleValue * 1e8)`。

---

## REST API

基础路径：`/api/v1`（管理端点：`/actuator/*`，端口 8081）。

| 端点 | 方法 | 说明 |
|---|---|---|
| `/api/v1/status` | GET | 应用状态 |
| `/api/v1/health` | GET | 健康检查 |
| `/api/v1/oms/latency` | GET | OMS 延迟快照（全部 symbol） |
| `/api/v1/oms/latency/{symbol}` | GET | OMS 延迟快照（单 symbol） |
| `/api/v1/oms/recovery` | GET | OMS 启动恢复状态（全部 symbol） |
| `/api/v1/oms/recovery/{symbol}` | GET | OMS 启动恢复状态（单 symbol） |
| `/api/v1/oms/recovery/{symbol}/retry` | POST | 手动重试该 symbol 启动恢复 |
| `/api/v1/md/status` | GET | 行情服务状态 |
| `/api/v1/md/start` | POST | 启动行情 |
| `/api/v1/md/stop` | POST | 停止行情 |
| `/api/v1/strategies` | GET | 策略列表（遗留） |
| `/api/v1/adapters/balances` | GET | 账户余额 |
| `/api/v1/adapters/positions` | GET | 持仓信息 |

---

## 热路径设计原则

- **零堆分配**：热路径全部使用 `long[]` / Agrona `DirectBuffer` / 对象池，禁止 `new`
- **零字符串操作**：价格/数量全链路 `long`（1e-8 定点），不转 `String`
- **零阻塞**：所有 I/O 异步；Disruptor 消费者线程不得调用阻塞方法
- **硬风控无锁**：RiskGuard 全部 `volatile` 字段，preCheck() 纯计算无锁
- **启动门禁**：OMS 启动恢复未完成前，`QUOTE_TARGET` 会被丢弃，避免未恢复状态下先开报价

---

## 已知限制与技术债

- `BedrockApplicationTest` 5 个测试因 Mockito `MockMaker` 插件初始化失败（pre-existing，与业务无关）
- `BinanceExecGateway` 已改为单独分发线程（有界队列）异步投递 ExecEvent，再由 OMS 事件总线线程消费；仍需 24h 网络扰动演练验证并评估极端高峰下丢弃策略
- `bedrock-strategy` 模块为遗留代码，后续重构或移除
- `chronicle-core` 版本需锁定为 `2.24ea2`（根 pom.xml 已 pin）；不得删除该 pin

---

## 参考文档

- `docs/QT_HFT_MM_ARCHITECTURE.md` — 目标 4 进程架构蓝图，tick-to-trade < 10μs
- `docs/QT_CLAUDE_ANOTHER.md` — 生产 MM 系统参考（LMAX Disruptor、TradeUnit、KBChange 定价）
- `CLAUDE.md` — Claude Code 工作指南（构建命令、模块依赖、设计决策）
