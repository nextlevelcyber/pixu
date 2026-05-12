# Rust MDS Contract Hardening Design

日期：2026-05-11

状态：Implemented milestone 2

## Goal

硬化 Rust Bedrock MDS foundation 的契约边界：明确 reconstructor 输出类型、明确 wrong venue / wrong instrument 的拒绝语义，并把 replay fixture 格式写成可维护 contract。

## Scope

本里程碑只修改 `bedrock-rs-md`、`bedrock-rs-replay`、fixtures 和文档。不实现 live feed、Pricing、OMS、Aeron adapter、SBE codegen 或 price grid。

## Design

`BookReconstructor` 不再返回 `Vec<MarketDataEvent>`，而是返回 `Vec<ReconstructionOutput>`。输入事件和 reconstruction 输出分离：

- `ReconstructionOutput::Bbo(Bbo)`
- `ReconstructionOutput::Gap(BookGap)`
- `ReconstructionOutput::Reject(BookReject)`

`BookReject` 表示事件属于错误 venue 或 instrument。错误身份事件不改变 book state，不推进 sequence，不产生 BBO，也不触发 gap。这样可以让上层 routing bug 被清晰暴露，同时避免污染当前 instrument book。

`RejectReason`：

- `WrongVenue`
- `WrongInstrument`

如果 venue 和 instrument 都不匹配，优先返回 `WrongVenue`，因为 venue 是更外层 routing 边界。

Replay fixture 增加 `expect_reject` directive，并新增 `wrong_identity_rejected.events`。fixture contract 写入 `bedrock-rs/fixtures/md/README.md`。

## Verification

成功标准：

- `cd bedrock-rs && cargo fmt`
- `cd bedrock-rs && cargo test`
- wrong venue / wrong instrument 单元测试通过
- wrong identity replay fixture 通过
- wiki 记录 Milestone 2 状态与下一步

## Non-Goals

- 不改变 `Price` / `Quantity` fixed-point 语义。
- 不引入外部依赖。
- 不实现 multi-producer transport。
- 不实现 venue-specific Binance/Bitget sequence 规则。
