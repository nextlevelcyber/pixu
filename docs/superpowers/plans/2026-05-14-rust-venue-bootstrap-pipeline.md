# Rust Venue Bootstrap Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a single-instrument venue pipeline that turns snapshots and depth updates into normalized output only after sequence-policy checks pass.

**Architecture:** Add a focused `pipeline.rs` module inside `bedrock-rs-md-venue`; re-export the public pipeline API from `lib.rs`; keep all network IO outside this crate.

**Tech Stack:** Rust 2021, existing `bedrock-rs-common`, `bedrock-rs-md`, `serde_json`.

---

## Tasks

- [x] Add `pipeline.rs` module and re-export it from `lib.rs`.
- [x] Add failing Spot pipeline tests for snapshot, bridged delta, stale ignore, and gap reset.
- [x] Add failing Futures pipeline tests for snapshot, first bridged delta, `pu` continuity, and `pu` gap reset.
- [x] Add failing Bitget pipeline tests for snapshot/update and `pseq = 0` reset.
- [x] Add failing JSON convenience parse error test.
- [x] Implement `VenuePipelineError`.
- [x] Implement `VenuePipelineGap`.
- [x] Implement `VenuePipelineOutput`.
- [x] Implement `BinanceSpotDepthPipeline`.
- [x] Implement `BinanceFuturesDepthPipeline`.
- [x] Implement `BitgetBooksDepthPipeline`.
- [x] Update `bedrock-rs-md-venue` README.
- [x] Update wiki/log handoff.
- [x] Run `cd bedrock-rs && cargo fmt --check`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-md-venue`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
