# Rust MDS Live Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Route venue pipeline outputs into `BookRouter` so live market data can produce MDS reconstruction outputs.

**Architecture:** Add a thin `bedrock-rs-mds` composition crate. Keep network in `bedrock-rs-md-live`, venue policy in `bedrock-rs-md-venue`, and book reconstruction in `bedrock-rs-md`.

**Tech Stack:** Rust 2021, existing `bedrock-rs-common`, `bedrock-rs-md`, `bedrock-rs-md-venue`, `bedrock-rs-md-live`.

---

## Tasks

- [x] Add `bedrock-rs-mds` to the Cargo workspace.
- [x] Add crate manifest with runtime dependencies on common, md, md-venue, and example-only dev-dependencies on md-live/Tokio.
- [x] Add failing tests for snapshot -> BBO routing.
- [x] Add failing tests for delta -> updated BBO routing.
- [x] Add failing tests for ignored stale and venue gap behavior.
- [x] Add failing test for strict registry reject passthrough.
- [x] Add failing test for venue-validated range delta routing.
- [x] Implement `MdsOutput`.
- [x] Implement `MdsRouter`.
- [x] Add `BookRouter::apply_trusted_delta` for venue-validated deltas.
- [x] Add crate README and rustdoc.
- [x] Add `mds_live_smoke` example.
- [x] Update wiki/log handoff.
- [x] Run `cd bedrock-rs && cargo fmt --check`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-mds`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
- [x] Run `cd bedrock-rs && cargo check -p bedrock-rs-mds --example mds_live_smoke`.
- [x] Run `cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-spot BNBBTC 8`.
- [x] Run `cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-futures BTCUSDT 8`.
- [x] Run `cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- bitget-books BTCUSDT 2`.
