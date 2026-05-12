# Java HFT Overview

日期：2026-05-10

来源：`wiki/sources/architecture/java-hft-overview.md`

## Scope

本文整理 Rust 低延时交易系统思路到 Java market making 系统的映射。重点是 Java 21 环境下构建低延迟行情、策略和报单链路时应优先关注的架构原则、数据结构和运行时约束。

## Core Mapping

Rust 侧的核心低延时思路包括零拷贝解析、二进制行情编码、无锁队列、异步日志、CPU 亲和性、NUMA/L3 cache/网卡绑核，以及 busy loop 避免线程唤醒。

在 Java 中，对应落地方向是：

- 零拷贝解析：使用 direct memory、`DirectBuffer`、offset-based parser，避免把网络数据复制成堆对象。
- 二进制行情：使用 Real Logic SBE 生成 Java codec，decoder 直接 wrap buffer，字段访问保持零对象分配。
- 无锁队列：优先使用 Disruptor 或 Agrona ring/array queue，避免 `BlockingQueue`、`synchronized` 和热路径 `ConcurrentHashMap`。
- 异步日志：日志写入必须移出热路径；热路径避免字符串格式化和临时日志对象。
- CPU 亲和性：行情、策略、OMS 等关键线程绑定独立物理核，并把 GC/JIT/系统线程排除出 trading cores。
- Busy spin：策略或队列消费者使用 busy spin / `Thread.onSpinWait()` 时必须配合绑核，否则会放大调度抖动。

## Java Low-Latency Principles

### Zero Allocation

Java HFT 的第一原则是热路径零分配。ZGC、Shenandoah 等现代 GC 可以降低 STW，但不能替代热路径不分配。行情解析、订单簿更新、策略计算、报单编码都应使用预分配对象、primitive fields、对象池或堆外 buffer。

验证手段包括 GC 日志、paper trading 阶段的 allocation profiling，以及针对 hot path 的 microbenchmark。

### Stable Runtime

JVM warmup 是 Java 系统特有风险。冷启动后 JIT 会在一段时间内反复编译和优化，导致延迟不稳定。生产前需要 warmup：用合成行情和模拟报单驱动关键路径，使热点方法进入稳定编译层。AOT 或 GraalVM Native Image 可以作为未来方向，但需要单独评估库兼容性、启动模型和运行时可观测性。

### Cache-Friendly Data Layout

热路径应使用 data-oriented design。避免将 order、quote、price level 建模为大量对象引用；优先使用 `long[]`、primitive maps、struct-of-arrays 或固定价格网格。这样可以减少 pointer chasing、boxing、cache miss 和 GC 压力。

需要警惕 false sharing。跨线程频繁写入的 sequence、counter、state 字段应做 cache line padding 或使用成熟库中的 padded sequence 实现。

### Time Representation

热路径 timestamp 使用 `long`，例如 epoch nanos 或 venue-normalized nanos。避免 `Instant`、`LocalDateTime`、字符串时间格式化进入行情、策略和报单路径。跨核或跨 NUMA socket 的时间一致性需要结合线程绑核和硬件时钟策略评估。

## Order Book Design

HFT order book 不应使用 `TreeMap<BigDecimal, Long>` 这类通用结构作为热路径存储。推荐方向：

- price 和 size 使用 fixed-point `long`。
- 对固定 tick size 的产品使用价格网格和数组索引，常见访问为 O(1)。
- bid/ask 侧分别维护 primitive arrays 或专门结构。
- BBO 单独缓存为 primitive fields，供策略线程低成本读取。
- 完整 depth stream 和 BBO derived view 要有明确一致性边界。

对当前 Bedrock MDS，这意味着 `L2OrderBook` 的长期演进应继续偏向 primitive-heavy、固定容量、低分配结构，并把 snapshot/delta rebuild 的正确性放在优化之前。

## Messaging And IPC

Java 生态中，Aeron、Agrona、SBE、Disruptor 是低延迟系统常见基础设施：

- SBE 负责二进制消息 schema 和 codec。
- Agrona 提供 `DirectBuffer`、primitive collections 和低分配工具。
- Disruptor 或 Agrona queues 负责线程间事件传递。
- Aeron 可用于低延迟 UDP 或 IPC，避免用 localhost TCP 作为进程间热路径。

如果进程内模块通信已经足够，优先保证单进程 ring buffer/event bus 的正确性和可观测性；跨进程 IPC 应在吞吐、隔离和部署边界明确后再引入。

## Strategy And Order Path

策略引擎的极致低延迟形态是 polling/ring-buffer 消费，而不是深层 callback/interface 调用链。事件进入策略线程后，尽量用直接分支、final class、primitive data 和可内联方法处理。

报单路径应保持零拷贝：

- 策略生成 intent。
- OMS 校验和状态迁移使用 primitive state。
- encoder 直接写预分配 `DirectBuffer`。
- 网络层直接发送 buffer。

FIX 或 binary protocol 的 checksum、序列号、时间戳、订单 ID 生成都必须避免堆对象和字符串格式化进入热路径。

## Priority For Bedrock

P0：

- 热路径 zero allocation：对象池、primitive fields、direct/off-heap buffer。
- 用 ring buffer / Disruptor / Agrona queue 替换阻塞队列和 synchronized 热路径。
- 修正并验证 Java 21 编译链路，确保项目实际按 Java 21 编译运行。

P1：

- SBE + `DirectBuffer` 行情和内部事件 codec。
- MDS book reconstruction 的 snapshot/delta 串行化、gap detection、rebuild 和 BBO freshness。
- 线程绑核、busy spin、trading core 与 JVM/system core 隔离。
- hot path timestamp、order id、price/size 全部 fixed-point primitive 化。

P2：

- Aeron IPC 或共享内存跨进程链路。
- Native Image / AOT 方案评估。
- SIMD checksum 或其他协议编码优化。
- 更完整的 NUMA、kernel bypass、busy-poll 网络优化。

## Open Questions

- 当前 `bedrock-md` 的 `L2OrderBook` 是否已经满足 zero allocation 和 fixed-point primitive 的长期目标？
- 内部事件总线是否需要统一到 SBE/Agrona buffer，还是先保留 Java object event 以降低实现风险？
- MDS、pricing、OMS 是否应按独立物理核规划线程模型？
- 引入 Disruptor/Aeron 的边界在哪里：模块内队列、进程间 IPC，还是两者都需要？
- Java 21 编译配置实际未生效的问题是否应作为 P0 基建任务优先处理？
