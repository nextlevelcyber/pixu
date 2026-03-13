# Crypto HFT Market Maker 系统架构设计

> 文档定位：新系统的方向性架构设计，供实现 Agent 阅读并据此构建完整系统。
> 系统类型：Crypto 多交易所做市商（Market Maker）
> 目标延迟：tick-to-trade < 10μs（进程内），端到端 < 1ms（受交易所网络限制）
> 日期：2026-03-06

---

## 一、核心设计原则

在开始任何模块设计前，必须理解并遵守以下原则，这些原则来自对传统 HFT 系统和本项目历史问题的综合分析。

### 1.1 Market Maker 没有传统意义上的"策略"

这是最重要的认知前提。

传统量化系统的分层是：
```
Alpha Signal（方向判断）→ Position Sizing → Order Management
```

**Market Maker 没有方向性 Alpha**，盈利来源是价差（bid-ask spread），因此上述分层不适用。

MM 系统真正的决策链是：

```
「现在真实价格是多少？」→ 「我应该报什么价、报多宽？」→ 「我现在的单子是否符合目标？」
      FairMid 计算            Quote Construction（报价构造）        Order Maintenance（订单维护）
      ─── Pricing Engine ──────────────────────────────────    ─── OMS ───────────────────
```

- **FairMid 计算**：根据参考盘深度、成交流、指数价格，估算当前真实中间价。是纯数学计算，不受持仓影响。
- **Quote Construction**：根据 FairMid + 波动率 + 当前持仓偏斜，计算目标报价 `{bidPrice, askPrice, bidSize, askSize}`。这才是 MM 的核心"算法"。
- **Order Maintenance**：对比目标报价与当前实际挂单，机械地执行增删操作。是纯工程实现，不做市场判断。

**结论：系统中不存在独立的"Strategy 模块"。FairMid + Quote Construction 合并为 Pricing Engine，Order Maintenance 是 OMS 的一个功能。**

### 1.2 顺序性：Per-Instrument 有序，不是全局有序

顺序性要求是局部的，不是全局的：

| 必须有序 | 原因 |
|---------|------|
| 同一订单的生命周期事件：SENT → ACK → FILL | 状态机不可乱序 |
| 同一 Instrument 的行情增量更新 | 序列号跳跃会导致订单簿损坏 |
| 同一 Instrument 的报价决策序列 | 不能并发修改同一 Instrument 的目标报价 |

| 不需要全局有序 | 原因 |
|-------------|------|
| Instrument A 的事件 vs Instrument B 的事件 | 完全独立，可并行 |
| 行情更新 vs 成交回报 | 属于不同流，正交处理 |

**结论：每个 Instrument 内部严格有序，Instrument 之间完全并行。禁止引入全局序列号或全局锁。**

### 1.3 热路径原则

热路径定义：从收到行情推送，到订单发出，这条链路上的所有代码。

- 热路径上**禁止对象分配**（no heap allocation）
- 热路径上**禁止阻塞等待**（no blocking）
- 热路径上**禁止系统调用**（no syscall，除网络 IO）
- 热路径上**禁止 String 操作**
- 所有对象预分配（pre-allocate），运行时复用

### 1.4 HA 策略：REST 对账，不做事件回放

本系统不使用 Chronicle Queue 做事件溯源和 HA 回放。原因：

- **交易所是订单状态的最终真相来源**，OMS 崩溃后通过 REST 拉取当前状态即可重建
- 事件回放的复杂度远高于 REST 对账，且回放"历史事件"对做市商没有业务价值
- 我们只关心**当前状态**，不需要历史重建

**OMS 重启恢复流程**：
```
OMS 进程重启（systemd 自动拉起，< 500ms）
  → Step 1: REST GET /openOrders  → 重建 OrderStore
  → Step 2: REST GET /positions   → 重建 PositionTracker
  → Step 3: REST GET /balances    → 重建余额缓存
  → Step 4: cancelAll()（可配置）  → 清空所有挂单，安全地从零开始
  → Step 5: 开始正常消费新事件
  总耗时：< 2 秒
```

P&L 历史、成交明细等需要持久化的数据，异步写入数据库，不在热路径上。

---

## 二、系统架构总览

### 2.1 进程划分（4 个进程）

```
┌────────────────────────────────────────────────────────────────────────┐
│  Process 1: MDS                                                        │
│                                                                        │
│  职责：交易所行情接入 + 归一化 + L2 订单簿维护                            │
│  输入：各交易所公有 WebSocket / REST 推送（原始格式）                      │
│  输出：归一化的 BboEvent、DepthEvent、TradeEvent（per Instrument）        │
└──────────────────────────────────────┬─────────────────────────────────┘
                                       │ Aeron IPC（per-instrument 有序流）
                                       ▼
┌────────────────────────────────────────────────────────────────────────┐
│  Process 2: Pricing Engine                                             │
│                                                                        │
│  职责：公允价计算 + 报价目标构造                                          │
│  输入：MDS 归一化行情 + OMS 当前持仓（来自 Aeron）                        │
│  输出：per-Instrument 的 QuoteTarget                                    │
│        { bidPrice, askPrice, bidSize, askSize, regionIndex }           │
│                                                                        │
│  内部两步（顺序执行，单线程）：                                            │
│    Step 1 FairMid：参考盘 depth + index price → 公允中间价               │
│    Step 2 QuoteConstruct：fairMid + volatility + delta → 目标报价        │
└──────────────────────────────────────┬─────────────────────────────────┘
                                       │ Aeron IPC（QuoteTarget 流）
                                       ▼
┌────────────────────────────────────────────────────────────────────────┐
│  Process 3: OMS（Order Management System）                             │
│                                                                        │
│  职责：订单全生命周期管理                                                 │
│  输入：QuoteTarget（来自 Pricing Engine）                                │
│        ExecEvent（来自 ExecGateway 子模块）                              │
│                                                                        │
│  子模块（进程内，Disruptor 单线程顺序处理）：                              │
│    RegionManager   ← 对比 QuoteTarget 与当前挂单，产生下单/撤单指令        │
│    OrderStateMachine ← 维护每个订单的状态（PENDING/OPEN/FILLED/CANCEL）  │
│    PositionTracker ← 从 Fill 实时计算持仓和 Delta                        │
│    HedgeManager    ← Delta 超阈值时触发对冲单                            │
│    RiskGuard       ← 前置风控（价格偏差、数量超限、速率限制）               │
│                                                                        │
│  ┌──────────────────────────────────────────────────────────────────┐ │
│  │  ExecGateway（OMS 子模块，不独立成进程）                           │ │
│  │                                                                  │ │
│  │  Outbound：OMS 指令 → 交易所 WS/REST（place / cancel）            │ │
│  │  Inbound：  私有 WS 推送 → 归一化 ExecEvent → OMS 状态机           │ │
│  │                                                                  │ │
│  │  私有 WS 数据分类：                                               │ │
│  │    订单 ack/fill/cancel/reject → 热路径，直接进状态机              │ │
│  │    持仓快照 / 余额快照         → 冷路径，用于对账                   │ │
│  │                                                                  │ │
│  │  断连重连后：REST 拉取 openOrders 做对账，补发缺失的 ExecEvent      │ │
│  └──────────────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────┬─────────────────────────────────┘
                                       │ Aeron IPC（异步，非热路径）
                                       ▼
┌────────────────────────────────────────────────────────────────────────┐
│  Process 4: Monitor                                                    │
│                                                                        │
│  职责：运维、风控监控、告警、管理接口                                      │
│  订阅所有 channel，只读，不影响热路径                                     │
│  提供 REST API：开关 Instrument、热更新参数、查询状态                      │
└────────────────────────────────────────────────────────────────────────┘
```

### 2.2 Aeron Channel 定义

| Channel 名称 | 发布者 | 订阅者 | 内容 | 是否热路径 |
|-------------|-------|-------|------|----------|
| `mds.bbo` | MDS | Pricing Engine, Monitor | BboEvent（最优买卖价） | 是 |
| `mds.depth` | MDS | Pricing Engine | DepthEvent（L2 快照） | 是 |
| `mds.trade` | MDS | Pricing Engine | TradeEvent（成交流） | 是 |
| `pricing.target` | Pricing Engine | OMS | QuoteTarget | 是 |
| `oms.position` | OMS | Pricing Engine, Monitor | PositionEvent（持仓变更） | 否 |
| `oms.order` | OMS | Monitor | OrderEvent（订单状态变更） | 否 |
| `mgmt.cmd` | Monitor | 所有进程 | 管理指令（开关、参数更新） | 否 |

**同机部署全部使用 `aeron:ipc`**（共享内存，~200-400ns 延迟，零拷贝）。
跨机部署切换为 `aeron:udp`，业务代码无需修改。

### 2.3 进程内顺序保证

每个进程内，每个 Instrument 独立一个 LMAX Disruptor：

```
[网络 IO 线程 / Aeron 订阅线程]  → producer
                                    ↓
                              ring buffer (per Instrument)
                                    ↓
                            [单 consumer 线程]  → 所有业务逻辑
```

**单 consumer 是顺序保证的核心**：无论有多少个事件来源（行情、QuoteTarget、ExecEvent），都先进 ring buffer 排队，单线程按顺序消费，天然无锁，天然有序。

Aeron 保证：**单条 Publication → Subscription 链路严格 FIFO，无重排**。
两者组合：进程间传输有序（Aeron）+ 进程内处理有序（Disruptor 单消费）= 完整的 per-Instrument 顺序保证。

---

## 三、核心数据模型

所有进程共享同一套数据模型定义（`common` 模块）。

### 3.1 事件类型

```java
// 最优买卖价（高频更新，优先级最高）
record BboEvent(
    long seqId,           // MDS 内单调递增序列号
    long recvNanos,       // System.nanoTime() 收到时间
    long exchNanos,       // 交易所时间戳
    int  instrumentId,    // 全局唯一 Instrument ID（int，非 String）
    long bidPrice,        // Double.doubleToRawLongBits(price)，下同
    long bidSize,
    long askPrice,
    long askSize
) {}

// L2 深度快照
record DepthEvent(
    long seqId,
    long recvNanos,
    long exchNanos,
    int  instrumentId,
    int  levels,          // 本次快照档位数
    long[] bidPrices,     // long[levels]，降序
    long[] bidSizes,
    long[] askPrices,     // long[levels]，升序
    long[] askSizes
) {}

// 成交事件
record TradeEvent(
    long seqId,
    long recvNanos,
    long exchNanos,
    int  instrumentId,
    long price,
    long size,
    boolean isBuy
) {}

// 报价目标（Pricing Engine → OMS）
record QuoteTarget(
    long seqId,
    long publishNanos,
    int  instrumentId,
    int  regionIndex,     // 目标 Region 编号，-1 表示全局更新
    long bidPrice,
    long askPrice,
    long bidSize,
    long askSize,
    long fairMid,         // 透传给 OMS 用于 Region 边界计算
    int  flags            // 位标志：是否暂停报价、是否强制刷新等
) {}

// 执行回执（ExecGateway → OMS 状态机）
record ExecEvent(
    long seqId,
    int  instrumentId,
    long internalOrderId, // long，OMS 内部 ID
    long exchOrderId,     // 交易所 ID（long 化）
    ExecEventType type,   // ACK, FILL, PARTIAL_FILL, CANCELLED, REJECTED
    long fillPrice,       // 成交价，无成交时为 0
    long fillSize,        // 成交量，无成交时为 0
    long remainSize,      // 剩余数量
    String rejectReason   // 仅 REJECTED 时非空，其他时候 null
) {}

// 订单状态事件（OMS 对外发布，Monitor 订阅）
record OrderEvent(
    long seqId,
    long publishNanos,
    int  instrumentId,
    long orderId,
    OrderState state,     // PENDING_NEW, OPEN, PARTIAL_FILL, FILLED, CANCELLED, REJECTED
    long price,
    long origSize,
    long filledSize,
    long fillAvgPrice,
    int  regionIndex
) {}

// 持仓事件（OMS → Pricing Engine，用于偏斜计算）
record PositionEvent(
    long seqId,
    int  instrumentId,
    long netPosition,     // 正=多，负=空，long bits of double
    long delta,           // 当前 delta 敞口
    long unrealizedPnl
) {}
```

### 3.2 Long-based 订单 ID

```
 Bit 63-48    Bit 47-32    Bit 31-1      Bit 0
┌────────────┬────────────┬─────────────┬──────┐
│instrId: 16 │  seq: 16   │timestamp:31 │side:1│
└────────────┴────────────┴─────────────┴──────┘

- instrId：16 位，支持 65535 个 Instrument
- seq：单毫秒内序列号，16 位，支持 65535 个/ms
- timestamp：毫秒级，31 位，约 24 天滚动复用
- side：0 = bid，1 = ask

clientOrderId（仅在调用交易所 API 时生成）：
  格式："{instrId}-{orderId}"，按需生成，不存储，不作为内部 key
```

### 3.3 价格表示规范

**所有价格、数量在存储和传输中统一使用 `long`**（`Double.doubleToRawLongBits(v)`）：
- 消除装箱开销
- 数组可以是 `long[]` 而非 `Double[]`
- SBE 序列化友好（固定宽度）
- 需要运算时转回 `double`，结果再转回 `long`

### 3.4 Instrument 元数据

```java
// 全局只读，启动时从配置文件加载，运行时不变
record InstrumentMeta(
    int    instrumentId,      // 全局唯一 int ID
    String exchange,          // 交易所名称
    String symbol,            // 原始交易对（如 "BTCUSDT"）
    String accountId,         // 做市账户 ID
    int    hedgeInstrumentId, // 对冲标的 instrumentId，0 表示不对冲
    double tickSize,          // 最小价格变动单位
    double lotSize,           // 最小数量单位
    int    pricePrecision,
    int    qtyPrecision,
    boolean isFutures         // true = 合约，false = 现货
) {}
```

---

## 四、各模块详细设计

### 4.1 MDS（市场数据服务）

**职责**：接入多交易所行情，归一化输出，维护 L2 订单簿。

```
MDS 内部结构（per Instrument，Disruptor 单线程）：

[Exchange WS/REST IO 线程]
  ├── 公有 WS 连接（depth、trade、ticker）
  ├── 原始消息解码（各交易所格式 → 内部事件）
  └── → Disruptor ring buffer (per Instrument)
              ↓ 单消费线程
        L2OrderBook.applyDelta(price, size, isBid)  // 增量更新
        或
        L2OrderBook.applySnapshot(...)               // 全量替换（重连后）
              ↓
        发布到 Aeron：mds.bbo / mds.depth / mds.trade
```

**L2OrderBook 数据结构**（纯 primitive，cache-friendly）：

```java
class L2OrderBook {
    final int   instrumentId;
    final int   depth;           // 维护档数，如 20
    final long[] bidPrices;      // long[depth]，降序
    final long[] bidSizes;
    final long[] askPrices;      // long[depth]，升序
    final long[] askSizes;
    long lastSeqId;
    long lastUpdateNanos;
    // 无任何 Object 字段，无 HashMap，无装箱
}
```

**序列号保护**：每个 Instrument 的行情消息带有交易所序列号，MDS 检测到序列号跳跃时，
主动触发全量快照拉取（REST），而不是丢弃或乱序处理。

**每个交易所一套 Adapter**，实现以下接口：

```java
interface ExchangeMdsAdapter {
    void connect(InstrumentMeta instrument, L2OrderBook book, MdsPublisher publisher);
    void disconnect();
    void requestSnapshot(int instrumentId);  // 重连后主动拉快照
}
```

---

### 4.2 Pricing Engine（定价引擎）

**职责**：计算 FairMid，构造每个 Instrument 的 QuoteTarget，发布给 OMS。

**明确不是**：Strategy、Alpha、方向性信号。

**内部处理流（单线程 Disruptor）**：

```
[Aeron 订阅线程] 收到 BboEvent / DepthEvent / TradeEvent / PositionEvent
      ↓ 放入 per-Instrument Disruptor ring buffer
      ↓ 单消费线程
  Step 1: FairMidPipeline.compute(book, indexPrice) → fairMid
    子组件（顺序叠加偏移量，每个组件返回 long offset）：
      DepthFollowComponent    — 参考盘深度加权中间价
      IndexFollowComponent    — 指数价格跟随
      AsymmetricFollowComponent — 非对称跟随（如现货参考永续）
      TradeFlowComponent      — 近期成交流方向偏移

  Step 2: QuoteConstructPipeline.compute(fairMid, position, volatility) → {bid, ask}
    子组件：
      BaseSpreadComponent     — 基础价差
      VolatilitySpreadComponent — 波动率动态调整价差
      DeltaSkewComponent      — 持仓偏斜（多头时压低 bid，空头时抬高 ask）
      PositionBoundComponent  — 持仓超限时单向停报

  Step 3: 构造 QuoteTarget（per Region）
  Step 4: 发布到 Aeron pricing.target
```

**组件接口**（统一，可热插拔）：

```java
@FunctionalInterface
interface PricingComponent {
    // 返回价格偏移量（单位：最小 tick 的整数倍 * 1e6，避免浮点误差）
    long compute(InstrumentContext ctx, L2OrderBook book, long currentEstimate);
}
```

**组件参数全部可热更新**（来自 Monitor 进程的 `mgmt.cmd` channel），不需要重启进程。

---

### 4.3 OMS（订单管理系统）

**职责**：订单生命周期管理、Region 维护、持仓跟踪、对冲、风控前置。

**ExecGateway 是 OMS 的子模块**，不独立成进程。

#### 4.3.1 内部处理流（每个 Instrument 独立 Disruptor，单消费线程）

```
两个事件来源（两个 Aeron 流），汇入同一个 Disruptor ring buffer：
  - Aeron 订阅线程 A：接收 QuoteTarget（来自 Pricing Engine）
  - ExecGateway 内部线程 B：接收 ExecEvent（来自私有 WS 回调）

单消费线程顺序处理所有事件：

  收到 QuoteTarget：
    → RiskGuard.preCheck(target)        // 价格偏差检查
    → RegionManager.diff(target)        // 对比目标与当前，产生 OrderAction 列表
    → 对每个 OrderAction：
        NEW    → ExecGateway.placeOrder()
        CANCEL → ExecGateway.cancelOrder()

  收到 ExecEvent：
    → OrderStateMachine.transition(event) // 更新订单状态
    → PositionTracker.onFill(event)       // 更新持仓和 Delta
    → HedgeManager.check()               // 检查是否需要对冲
    → 发布 OrderEvent 到 oms.order channel（异步，Monitor 订阅）
    → 发布 PositionEvent 到 oms.position channel（Pricing Engine 订阅）
```

#### 4.3.2 Region Manager（订单维护核心）

Region 是 MM 做市的核心组织方式：按价格带分区挂单，每个 Region 维护一组在特定价格范围内的委托单。

```java
class PriceRegion {
    final int     regionIndex;
    final boolean isBid;

    // 价格边界（相对 fairMid 的比例，double，非 BigDecimal）
    double minRatio;    // 如 -0.001（fairMid 以下 0.1%）
    double maxRatio;    // 如 -0.002

    // 当前边界绝对价格（每次 fairMid 更新时重算，缓存）
    double minPrice;
    double maxPrice;

    // 当前区间内的订单 ID 列表（primitive long array，预分配）
    long[] orderIds;    // long[MAX_ORDERS_PER_REGION]，如 32 个槽位
    int    orderCount;

    // 约束参数（可热更新）
    int    minCount, maxCount;
    double minQty, maxQty;
    double recommendQty;

    // 以下操作均为 O(1) 或 O(MAX_ORDERS_PER_REGION)，不用 HashMap
    void addOrder(long orderId)    { ... }
    void removeOrder(long orderId) { ... }

    // 返回价格已越界的订单 ID 列表（需要撤单）
    long[] getStaleOrders(double newFairMid) { ... }
}
```

**OrderStore 核心存储**：

```java
class OrderStore {
    // 主索引：orderId → Order
    // 使用 Agrona Long2ObjectHashMap（原生 long key，无装箱，open-addressing hash）
    final Long2ObjectHashMap<Order> orders;

    // Region 索引：instrumentId → PriceRegion[]（bid regions + ask regions）
    final Map<Integer, PriceRegion[]> regionMap;

    // 反查索引：exchOrderId → internalOrderId（处理交易所回执）
    final Long2LongHashMap exchToInternal;
}
```

#### 4.3.3 ExecGateway（OMS 子模块）

```
ExecGateway 的完整职责：

Outbound（主动发出）：
  placeOrder(instrument, price, size, side, regionIndex)
    → 生成 clientOrderId = "{instrId}-{orderId}"
    → 调用交易所 WS/REST
    → 写入 pendingAck Map（等待 ACK 确认）

  cancelOrder(instrument, orderId)
    → 查找 exchOrderId
    → 调用交易所 WS/REST

Inbound（被动接收）：
  私有 WS 连接（per 交易所账户，独立维护心跳和重连）
    → 收到订单状态推送（ack/fill/cancel/reject）
        → 解码为 ExecEvent
        → 放入 OMS per-Instrument Disruptor ring buffer（同进程，直接放）
    → 收到持仓快照
        → 放入冷路径对账队列（不经过热路径 Disruptor）
    → 收到余额快照
        → 放入冷路径对账队列

断连重连处理：
  onPrivateWsReconnect():
    List<Order> exchOrders = restClient.getOpenOrders()
    List<ReconcileAction> diffs = orderStore.reconcile(exchOrders)
    for (diff : diffs):
        if (内部 OPEN 但交易所已 FILLED)  → 补发 ExecEvent.FILL
        if (内部 OPEN 但交易所已 CANCELLED) → 补发 ExecEvent.CANCELLED
```

#### 4.3.4 订单状态机

```
                     PENDING_NEW
                    /     |      \
                  ACK   REJECT  超时
                  /              \
               OPEN          REJECTED
              /    \
        CANCEL   FILL(partial)
          |          |
    PENDING_CANCEL  PARTIAL_FILL
          |          |   \
      CANCELLED    FILL   继续等待
                    |
                  FILLED
```

状态转换由 ExecEvent 驱动，由 OrderStateMachine 在 Disruptor 单消费线程中处理，无并发。

---

### 4.4 Monitor（监控与运维）

**职责**：只读订阅所有 Aeron channel，提供运维接口。不参与热路径。

- 订阅 `oms.order`、`oms.position`、`mds.bbo` 等 channel
- 聚合指标（延迟分布、PnL、成交率、Region 覆盖率）
- 告警规则（Delta 超阈值、价格偏差过大、成交量异常）
- REST API：
  - `POST /instrument/{id}/pause` — 暂停某 Instrument 报价
  - `POST /instrument/{id}/resume` — 恢复报价
  - `PUT /instrument/{id}/params` — 热更新 Pricing Engine 参数（通过 mgmt.cmd channel）
  - `GET /instrument/{id}/status` — 查询当前状态

---

## 五、技术选型

| 方面 | 选型 | 理由 |
|------|------|------|
| 进程间通信 | **Aeron IPC**（同机）/ **Aeron UDP**（跨机） | 同机 ~200-400ns，零拷贝，单流严格有序 |
| 进程内事件总线 | **LMAX Disruptor** | 无锁环形队列，单消费线程顺序保证，延迟 < 1μs |
| 序列化 | **SBE（Simple Binary Encoding）** | 固定宽度，无反射，解码 ~10ns，无 GC |
| 订单索引 | **Agrona Long2ObjectHashMap** | 原生 long key，无装箱，开放寻址 hash |
| 订单簿存储 | **primitive long[] arrays** | 最高 cache locality，无 GC，无装箱 |
| 持久化（异步） | **PostgreSQL / InfluxDB** | 非热路径，用于 P&L 历史和监控，异步写入 |
| 进程守护 | **systemd** | `Restart=always`，崩溃后 500ms 自动重启 |
| 语言 | **Java 21（LTS）** | ZGC 可用，Virtual Threads 用于 IO，生态成熟 |
| 构建 | **Maven multi-module** | — |

**JVM 参数（生产必须配置）**：

```bash
-XX:+UseZGC -XX:MaxGCPauseMillis=1    # ZGC，暂停目标 < 1ms
-XX:+AlwaysPreTouch                   # 启动时预申请内存，避免运行时 page fault
-XX:+DisableExplicitGC                # 禁止 System.gc()
-server
--add-opens java.base/sun.nio.ch=ALL-UNNAMED   # Aeron 需要
```

**CPU 亲和性**（每个 Disruptor hot 线程绑定到独立物理 CPU core）：

```java
// 使用 OpenHFT/Java-Thread-Affinity 库
try (AffinityLock lock = AffinityLock.acquireCore()) {
    runHotLoop();
}
```

---

## 六、项目模块结构

```
hft-mm/
├── common/                     # 所有进程共享的数据模型和工具
│   ├── model/                  # Event records, Order, InstrumentMeta
│   ├── sbe/                    # SBE schema + 生成的 codec
│   ├── idgen/                  # Long orderId 生成器
│   └── util/                   # 价格工具（tickRound, lotRound, bitsToDouble）
│
├── aeron-infra/                # Aeron 封装（Publication/Subscription 工厂，MediaDriver 启动）
│
├── mds/                        # MDS 进程
│   ├── adapter/                # 各交易所 Adapter
│   │   ├── binance/
│   │   ├── okx/
│   │   ├── bybit/
│   │   └── ...（按需增加，其他进程无需改动）
│   ├── book/                   # L2OrderBook，SequenceValidator
│   └── MdsApplication.java
│
├── pricing/                    # Pricing Engine 进程
│   ├── component/fairmid/      # FairMid 子组件
│   ├── component/spread/       # QuoteConstruct 子组件
│   ├── pipeline/               # 组件编排
│   └── PricingApplication.java
│
├── oms/                        # OMS 进程（含 ExecGateway 子模块）
│   ├── statemachine/           # 订单状态机
│   ├── store/                  # OrderStore, PriceRegion
│   ├── region/                 # RegionManager（核心报价维护逻辑）
│   ├── position/               # PositionTracker, HedgeManager
│   ├── risk/                   # RiskGuard（前置风控）
│   ├── exec/                   # ExecGateway（含私有 WS，各交易所实现）
│   │   ├── binance/
│   │   ├── okx/
│   │   └── ...
│   └── OmsApplication.java
│
├── monitor/                    # Monitor 进程
│   ├── api/                    # REST Controller
│   ├── alert/                  # 告警规则
│   └── MonitorApplication.java
│
└── config/                     # 配置文件
    ├── instruments.yaml        # InstrumentMeta 定义
    ├── regions.yaml            # Region 参数（各 Instrument 的 Region 配置）
    └── pricing.yaml            # Pricing Engine 组件参数
```

---

## 七、阶段拆解

### Phase 0：基础层（所有后续 Phase 的前提）

| 任务 | 产出 | 验收 |
|------|------|------|
| 定义全部 Event 类型（SBE schema） | `common/sbe/` | 解码 benchmark < 50ns |
| Long orderId 生成器 | `common/idgen/` | 单线程 > 10M ops/s，无重复 |
| Aeron 封装（IPC Publication/Subscription） | `aeron-infra/` | Ping-pong 延迟 < 500ns |
| InstrumentMeta YAML 加载 + InstrumentRegistry | `common/` + `config/` | 支持热加载 |
| JVM 调优 baseline 脚本 | 文档 | GC 暂停 < 1ms（ZGC） |

### Phase 1：MDS

| 任务 | 产出 | 验收 |
|------|------|------|
| L2OrderBook（primitive 数组实现） | `mds/book/` | 增量更新 < 1μs |
| 实现 2 个交易所 Adapter（Binance + OKX） | `mds/adapter/` | 收包到发布 < 10μs |
| 序列号验证 + 快照重取 | `mds/book/` | 序列号跳跃后自动恢复 |
| MdsApplication 进程 | `mds/` | 端到端行情延迟 < 50μs（95th pct） |
| 补齐其余交易所 Adapter | `mds/adapter/` | — |

### Phase 2：OMS + ExecGateway

| 任务 | 产出 | 验收 |
|------|------|------|
| OrderStore（Long2ObjectHashMap + PriceRegion） | `oms/store/` | 1万订单 Region 清理 < 100μs |
| OrderStateMachine | `oms/statemachine/` | 状态转换 < 500ns |
| ExecGateway（Binance 实现，含私有 WS） | `oms/exec/binance/` | 下单 ACK 端到端 < 500μs（testnet）|
| 私有 WS 断连重连 + REST 对账 | `oms/exec/` | 断连重连后状态无丢失 |
| OMS 重启恢复流程（REST 对账） | `oms/` | 重启后 < 2s 完成状态重建 |
| OMS Disruptor pipeline（per Instrument） | `oms/` | 处理延迟 < 5μs |
| Testnet 集成测试 | 测试 | 1000 个订单，状态无异常 |
| 补齐其余交易所 ExecGateway | `oms/exec/` | — |

### Phase 3：Pricing Engine

| 任务 | 产出 | 验收 |
|------|------|------|
| PricingComponent 接口 + Pipeline 框架 | `pricing/pipeline/` | — |
| FairMid 子组件（DepthFollow、IndexFollow） | `pricing/component/fairmid/` | — |
| QuoteConstruct 子组件（BaseSpread、VolSpread、DeltaSkew） | `pricing/component/spread/` | — |
| QuoteTarget 发布（Aeron） | `pricing/` | 信号计算延迟 < 10μs |
| 接收 PositionEvent 更新持仓偏斜 | `pricing/` | — |
| 参数热更新（接收 mgmt.cmd） | `pricing/` | 不重启进程更新参数 |

### Phase 4：集成 + Monitor

| 任务 | 产出 | 验收 |
|------|------|------|
| OMS RegionManager（对比 QuoteTarget 与当前挂单） | `oms/region/` | Region 决策延迟 < 2μs |
| HedgeManager（Delta 对冲） | `oms/position/` | — |
| RiskGuard（价格偏差、速率限制） | `oms/risk/` | 拦截延迟 < 1μs |
| Monitor 进程（REST API + 告警） | `monitor/` | — |
| 端到端集成测试（MDS→Pricing→OMS→Exec） | 测试 | Testnet 稳定运行 24h |
| DryRun 模式（所有进程支持，不发真实订单） | 全模块 | — |

---

## 八、关键设计决策速查

| 决策点 | 结论 | 原因 |
|-------|------|------|
| 是否有 Strategy 模块 | **无** | MM 没有方向性 Alpha，QuoteConstruction 就是"策略"，归入 Pricing Engine |
| ExecGateway 是否独立进程 | **否，是 OMS 子模块** | 无独立状态，生命周期与 OMS 绑定，独立进程只增加 IPC 开销 |
| 私有 WS 属于哪个模块 | **ExecGateway（OMS 子模块）** | ExecGateway 是双向交易所连接管理器，负责所有非行情数据的收发 |
| 顺序性保证方式 | **per-Instrument Disruptor 单消费** | 不需要全局有序，per-Instrument 局部有序即可，Disruptor 单消费线程天然保证 |
| HA 方式 | **REST 对账重建，无事件回放** | 交易所是状态真相来源，启动时 REST 拉取比回放历史事件更简单可靠 |
| 价格表示 | **long（double IEEE 754 bits）** | 无装箱，primitive 数组，SBE 友好 |
| 订单 ID | **64 位 long（snowflake 结构）** | 消除 String key 的 GC 和 hash 开销，Long2ObjectHashMap 原生 long key |
| Region 价格边界 | **double ratio，非 BigDecimal** | 热路径计算，BigDecimal 有对象分配开销 |
| 进程间通信 | **Aeron IPC** | 同机 ~200-400ns，单流严格有序，零拷贝 |
| 跨 Instrument 共享数据（如 index price） | **Chronicle Map（mmap）** | 零拷贝读取，~50ns，无需 Aeron 发布 |
| GC 策略 | **ZGC，目标 < 1ms 暂停** | 热路径预分配消除 GC，ZGC 处理无法避免的分配 |
