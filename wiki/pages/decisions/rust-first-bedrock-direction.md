# Rust-First Bedrock Direction

日期：2026-05-11

状态：Accepted

来源：当前对话上下文

## Decision

Bedrock 后续长期实现方向调整为 Rust-first。当前 Java 项目不视为已上线生产系统，也不作为必须兼容的主实现；它只作为已有探索资产、领域知识来源和可选参考。

后续计划和实现可以从 Rust 重新开始，只要严格保留核心诉求：

- Agent 在 Rust/HFT 架构层面主动把关，明确说明风险和 tradeoff。
- MD/MDS、EventBus/Transport、Pricing、OMS、Monitor/Ops 保持清晰领域边界，支持不同研发独立开发。
- in-proc、Aeron IPC、Aeron UDP 都是一等 transport/deployment 模式。
- MD/MDS 先行，先验证 market data correctness，再进入 paper trading 和真实交易路径。
- 严格遵守 `karpathy-guidelines` 和 `ai-coding-standard.md`。
- 长任务持续维护 wiki、ADR、module spec、handoff notes，防止 context compact 后丢失判断链。

## Meaning Of Shadow

在本文档中，shadow 不是指“已有 Java 生产系统旁路替换”。更准确地说，shadow 是一种验证模式：

- 系统接收真实或 replay 行情输入。
- 系统计算 book、BBO、pricing 或订单意图。
- 输出只用于对比、记录、监控或人工分析。
- 输出不驱动真实下单，不影响线上资金风险。

因此，即使完全 Rust-first，shadow 仍然有价值：它用于验证 Rust MDS、Pricing、OMS 的行为是否稳定、可解释、可观测。对拍对象可以是 golden fixture、交易所公开规则、离线 replay、人工预期结果，Java 不是必须对拍对象。

## Rationale

继续把任务称为“Java 到 Rust 迁移”会误导后续设计，因为当前 Java 实现并不是生产基线。真正重要的是把 Bedrock 的目标系统做对：

- market data reconstruction 必须正确、可观测、可 replay。
- domain boundaries 必须清晰，避免一个模块跨越 feed、pricing、risk、execution 多个职责。
- transport 必须抽象成能力，而不是写死某一种部署方式。
- Rust workspace 应从第一天开始围绕可测试 contract 和 module ownership 建立。

Java 代码仍可用于提取概念、命名、模块经验和踩坑记录，但不应限制 Rust 设计。

## Consequences

- 后续文档优先使用 “Rust-first implementation” 或 “Rust Bedrock” 表述，而不是 “Java migration”。
- 第一阶段仍以 MD/MDS 为核心，但验收不依赖 Java 行为一致。
- Java/Rust replay 对拍可以作为可选验证，不作为默认硬门槛。
- 如果 Java 代码中的结构与 Rust-first 目标冲突，以 Rust-first 架构目标为准。

## Initial Direction

第一阶段建议聚焦 Rust MDS foundation：

1. 定义 normalized market data domain model。
2. 定义 transport-neutral event contract。
3. 建立 replay/golden fixture 测试框架。
4. 实现 L2 order book reconstruction。
5. 验证 snapshot/delta/gap/BBO freshness。
6. 先提供 in-proc transport adapter，再设计 Aeron IPC/UDP adapter。

## Non-Goals

- 不承诺兼容 Java 内部类、接口或 Spring wiring。
- 不先实现 OMS 真实下单。
- 不先追求极致内核/network tuning。
- 不因为 Rust-first 就跳过 replay、paper trading 和文档审查。
