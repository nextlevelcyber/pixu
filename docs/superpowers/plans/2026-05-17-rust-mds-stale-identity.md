# Rust MDS Stale Identity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Follow test-driven-development for changed behavior.

**Goal:** Preserve venue/instrument identity on ignored stale live updates.

---

## Tasks

- [x] Add failing tests for Spot/Futures stale identity.
- [x] Add failing MDS test for stale identity passthrough.
- [x] Implement `VenuePipelineIgnoredStale`.
- [x] Update venue pipelines to emit keyed stale output.
- [x] Update MDS output and examples.
- [x] Update README/spec/wiki handoff.
- [x] Run `cd bedrock-rs && cargo fmt --check`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-md-venue`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-md-live`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-mds`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
- [x] Run `cd bedrock-rs && cargo check -p bedrock-rs-md-live --example live_smoke`.
- [x] Run `cd bedrock-rs && cargo check -p bedrock-rs-mds --example mds_live_smoke`.
- [x] Run `cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-spot BNBBTC 8 3`.
