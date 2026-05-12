# Rust-First Bedrock Guardrails

日期：2026-05-11

来源：当前对话上下文

## Scope

本文记录 Bedrock 长期 Rust-first 实现任务的硬约束。当前 Java 项目是探索资产，不是必须兼容的生产基线。本文不是实现计划，而是后续架构设计、模块拆分、任务分配和代码审查必须遵守的 guardrails。

## Non-Negotiable Requirements

### Architecture Ownership

用户不假设自己具备完整 Rust 经验或完整 HFT 架构能力。Agent 必须在架构层面主动把关：

- 解释关键 tradeoff，不把复杂选择默默藏进实现。
- 遇到 Rust、HFT、market data、transport、latency 相关不确定性时，先说明风险。
- 不为了追求性能而牺牲可验证性、模块边界和上线安全。
- 对明显过度设计或过早优化主动降级。

### Clear Domain Boundaries

Bedrock 必须保持明确领域划分，使不同研发可以独立开发、测试和部署。至少包含：

| Domain | Responsibility | Must Not Own |
|--------|----------------|--------------|
| MD / MDS | 交易所接入、行情归一化、order book reconstruction、BBO/book delta 发布 | pricing 策略逻辑、订单状态机 |
| EventBus / Transport | in-proc、Aeron IPC、Aeron UDP 的统一传输抽象、序列化、ordering、backpressure | 业务定价、交易所 feed 语义 |
| Pricing | FairMid、QuoteConstruction、spread/inventory/volatility-aware quote 计算 | feed parsing、报单执行、仓位账本 |
| OMS | 订单状态机、风控、仓位、execution gateway、交易所回报处理 | order book reconstruction、fair mid 计算 |
| Monitor / Ops | 只读订阅、指标、日志、健康检查、运维观察面 | 修改交易决策或订单状态 |

模块之间通过显式 contract 通信，不能依赖彼此内部数据结构。每个 domain 都应可以拥有独立测试、独立 mock、独立 replay 验证。

### Transport Modes Remain First-Class

Bedrock 需要继续支持：

- in-proc：单进程 collocated 部署，最低复杂度和最低延迟。
- Aeron IPC：同机多进程部署，适合 MD、Pricing、OMS、Monitor 分离但仍 collocate。
- Aeron UDP：跨机器或多节点部署，适合更强隔离或分布式拓扑。

Rust 迁移不能把 transport 固化成单一模式。正确方向是先定义业务事件 contract，再让 transport adapter 承载不同部署形态。MD、Pricing、OMS、EventBus 未来都可能独立部署，因此 contract、schema、ordering、backpressure、failure semantics 必须在模块边界处明确。

### MD First, Validate Before Trading

实现顺序以 MD/MDS 先行为默认方向。原因：

- L2 book reconstruction、sequence gap、snapshot/delta buffering 是当前系统关键缺口。
- MD 的输入输出边界相对清晰，适合先做 Rust-first foundation。
- MDS correctness 可以通过 replay、golden fixture、交易所规则和 shadow validation 验证，不依赖 Java 作为生产参考。

不得直接进入 OMS 真实下单链路。Rust 版本必须先证明 deterministic correctness，再进入 paper trading，再考虑真实交易路径。

Shadow 在此处表示旁路验证模式：接收 live 或 replay 输入，计算 book/BBO/pricing/order intent，但输出只用于记录、对比、监控或人工分析，不驱动真实下单。对拍对象可以是 golden fixture、交易所公开规则、离线 replay、人工预期结果；Java 不是必需对拍对象。

### Process Constraints

后续 feature、refactor、review 必须遵守：

- `karpathy-guidelines`：先思考、简单优先、外科手术式修改、目标驱动执行。
- `ai-coding-standard.md`：使用团队固定 workflow，重大任务先 plan，新增或修改可测行为要验证，长任务要写交接记录。
- 不引入未评审的 plugin、skill、MCP 或大型技术依赖。
- 不把临时实验混入稳定 contract；实验必须有单独说明和退出条件。

### Documentation As Memory

由于这是长周期、跨语言、跨模块任务，文档是工程记忆的一部分。每个较大阶段必须更新 wiki：

- architecture page：当前架构、模块边界、运行拓扑。
- decision page / ADR：关键取舍，例如 transport contract、schema 策略、MDS rebuild 语义。
- module spec：每个 domain 的输入、输出、状态、错误处理、测试入口。
- handoff notes：context compact 或 session 结束前记录已完成事项、未解决问题、验证结果和下一步。

任何影响 MD、Pricing、OMS、EventBus 边界的设计变化，都必须先写入 wiki，再进入实现。

## Initial Rust-First Shape

建议的第一阶段不是创建完整 Rust 交易系统，而是建立可验证的 Rust MDS foundation：

1. 定义 normalized market data event contract。
2. 定义 transport-neutral publisher/subscriber interface。
3. 实现 Rust L2 order book reconstruction。
4. 建 replay/golden test，验证 BBO、book depth、gap behavior。
5. 输出 in-proc transport，随后补 Aeron IPC/UDP adapter。

后续是否迁移 Pricing 和 OMS，应以 shadow validation 结果、模块 contract 稳定度和测试覆盖为准。

## Open Questions

- Rust workspace 位置已决策：先放在当前 repo 顶层 `bedrock-rs/`，wiki 继续维护在当前 repo 根目录 `wiki/`。详见 `wiki/pages/decisions/rust-workspace-location.md`。
- SBE schema 是否继续作为 Java/Rust 共享 contract 的 source of truth？
- Aeron 在 Rust 侧采用现成 binding、FFI，还是先用 transport trait + adapter placeholder？
- MD/Pricing/OMS 独立部署时，ordering 和 backpressure 的失败语义如何定义？
- Java 版定位已决策：只作为探索资产和可选参考，不作为必须兼容的 reference implementation。详见 `wiki/pages/decisions/rust-first-bedrock-direction.md`。
