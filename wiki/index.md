# Wiki Index

| 页面 | 分类 | 摘要 | 最后更新 |
|------|------|------|---------|
| `wiki/pages/architecture/bedrock-project-baseline.md` | architecture | Bedrock HFT Market Maker 的当前架构阶段、模块边界、热路径约束和运行注意事项。 | 2026-05-07 |
| `wiki/pages/architecture/java-hft-overview.md` | architecture | Rust 低延时交易思路映射到 Java HFT/market making 的架构原则、热路径约束和 Bedrock 落地优先级。 | 2026-05-10 |
| `wiki/pages/architecture/mds-current-status.md` | architecture | MDS 当前实现、主要缺口、测试验证阻塞点和后续推进方向。 | 2026-05-07 |
| `wiki/pages/architecture/rust-bedrock-mds-foundation.md` | architecture | Rust-first Bedrock 第一阶段 MDS foundation 的 scope、workspace/crate 边界、reconstruction contract、transport contract、instrument registry 和验证标准。 | 2026-05-12 |
| `wiki/pages/architecture/rust-migration-guardrails.md` | architecture | Bedrock 长期 Rust-first 实现的硬约束：架构把关、领域边界、transport 模式、MD 先行和文档纪律。 | 2026-05-11 |
| `wiki/pages/architecture/rust-venue-sequence-rules.md` | architecture | Binance/Bitget live L2 feed 前必须遵守的 snapshot、delta、sequence、gap/rebuild 合同。 | 2026-05-12 |
| `wiki/pages/business/orderbook-market-data.md` | business | L1/L2/L3 order book market data 的业务含义、适用场景，以及对 Bedrock MDS 的设计启发。 | 2026-05-10 |
| `wiki/pages/decisions/rust-first-bedrock-direction.md` | decisions | 决定 Bedrock 后续按 Rust-first 方向实现，Java 只作为探索资产和可选参考；定义 shadow 为旁路验证模式。 | 2026-05-11 |
| `wiki/pages/decisions/rust-workspace-location.md` | decisions | 决定 Rust 迁移先在当前 repo 顶层 `bedrock-rs/` 中进行，根目录 `wiki/` 作为 Java/Rust 共享文档源。 | 2026-05-10 |
