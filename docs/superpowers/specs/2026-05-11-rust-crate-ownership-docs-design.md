# Rust Crate Ownership Docs Design

日期：2026-05-11

状态：Implemented milestone 4

## Goal

为 `bedrock-rs-common`、`bedrock-rs-transport`、`bedrock-rs-md`、`bedrock-rs-replay` 建立 crate-level ownership 文档，让不同研发可以独立理解边界、公共 API 和非职责。

## Scope

本里程碑只新增 README 和 crate-level rustdoc，不改变运行时行为，不引入外部依赖。

## Design

每个 crate 增加：

- `README.md`：给人读的 ownership boundary。
- `//!` crate-level rustdoc：给 IDE 和 `cargo doc` 使用。

每份 README 覆盖：

- Owns
- Does Not Own
- Public API
- Verification
- Next Design Questions

## Verification

成功标准：

- `cd bedrock-rs && cargo fmt`
- `cd bedrock-rs && cargo test`
- `cd bedrock-rs && cargo doc --no-deps`
- wiki 记录 Milestone 4 状态与下一步

