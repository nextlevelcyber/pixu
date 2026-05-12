# Rust Workspace Location

日期：2026-05-10

状态：Accepted

来源：当前对话上下文

## Decision

Rust 迁移阶段先在当前 Bedrock repo 内新增顶层 `bedrock-rs/` Cargo workspace，不另起独立项目。

项目 wiki 继续维护在当前 repo 根目录的 `wiki/` 下，作为 Java 与 Rust 两条实现共享的架构记忆和决策记录。

## Rationale

当前阶段的目标是在当前 repo 内建立 Rust-first Bedrock foundation，尤其是 MDS/L2 order book。把 Rust workspace 放在当前 repo 内有几个直接好处：

- Java 实现可以继续作为探索资产和可选参考，但不作为必须兼容的 reference implementation。
- `bedrock-sbe`、事件 contract、测试 fixture、行情 replay 数据和 wiki 可以共享，减少漂移。
- MD、EventBus、Pricing、OMS 的领域边界可以通过 Rust crates 保持清晰，不需要靠 repo 拆分实现边界。
- 一个 repo 内更容易维护迁移计划、ADR、module spec 和 handoff notes，降低 context compact 后的信息丢失。
- 早期不引入跨 repo CI、版本同步、schema 发布和文档复制成本。

## Repository Shape

建议后续结构：

```text
pixu/
  bedrock-md/             # Java implementation
  bedrock-pricing/
  bedrock-oms/
  bedrock-aeron/
  bedrock-sbe/
  bedrock-rs/             # Rust Cargo workspace
    Cargo.toml
    crates/
      bedrock-rs-common/
      bedrock-rs-transport/
      bedrock-rs-md/
      bedrock-rs-pricing/
      bedrock-rs-oms/
      bedrock-rs-replay/
  wiki/                   # Canonical documentation for both Java and Rust
```

初期不要求 Rust workspace 与 Maven reactor 互相调用，也不要求 Java build 触发 Rust build。Rust 侧先通过 shared contract、fixtures 和 replay/golden tests 验证自身行为。

## Documentation Location

后续所有 Rust 迁移相关 wiki 仍写入当前 repo：

- `wiki/pages/architecture/`：架构、模块边界、运行拓扑、迁移计划。
- `wiki/pages/decisions/`：ADR，例如 workspace 位置、transport contract、schema 策略。
- `wiki/pages/business/`：业务语义，例如 order book、pricing input、risk semantics。
- `wiki/log.md`：每次 ingest/query/decision 更新记录。
- `wiki/index.md`：所有页面入口。

`bedrock-rs/` 内可以有 crate README，但 README 只解释本 crate 的 build/test/use；长期设计结论以根目录 `wiki/` 为准。

## Consequences

- 当前 repo 会从 Java Maven multi-module 变成 Java + Rust mixed monorepo。
- Rust 领域边界必须靠 Cargo workspace/crate ownership、public API 和 tests 维护，不能因为在同一 repo 就互相读取内部结构。
- CI 后续需要分 Java verification 与 Rust verification，但第一阶段可以先互不耦合。
- 如果将来 Rust 实现稳定、团队需要独立 release cadence 或权限隔离，可以再通过新 ADR 决定拆分到独立 `bedrock-rs` repo。

## Non-Goals

- 本决策不创建 Rust 代码骨架。
- 本决策不改变 Java Maven module 结构。
- 本决策不决定 Aeron Rust binding 方案。
- 本决策不改变 Rust-first 方向；Java 只作为探索资产和可选参考。

## Review Triggers

出现以下情况时重新评估是否拆成独立 repo：

- Rust implementation 已经能独立完成 MD/Pricing/OMS 主要链路。
- Rust 与 Java 的 CI/CD、发布节奏明显冲突。
- 团队权限、ownership 或部署要求需要 repo 级隔离。
- 根 repo 体积或构建复杂度显著影响开发效率。
