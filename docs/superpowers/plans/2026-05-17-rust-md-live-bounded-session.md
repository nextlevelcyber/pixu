# Rust MD Live Bounded Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Follow test-driven-development for changed behavior.

**Goal:** Continue reading bounded live depth frames after snapshot/bootstrap so live MDS can verify more than the first actionable delta.

**Architecture:** Keep network IO in `bedrock-rs-md-live`; keep MDS routing in `bedrock-rs-mds`; do not add daemon/reconnect/Aeron in this milestone.

---

## Tasks

- [x] Add failing unit test for `BinanceLiveSessionLimits`.
- [x] Implement `BinanceLiveSessionLimits`.
- [x] Add `binance_spot_session_once`.
- [x] Add `binance_futures_session_once`.
- [x] Keep existing smoke helpers available for compatibility.
- [x] Update `live_smoke` and `mds_live_smoke` examples to use bounded Binance sessions and optional post-bootstrap arg.
- [x] Update crate README/spec/wiki handoff.
- [x] Run `cd bedrock-rs && cargo fmt --check`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-md-live`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-mds`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
- [x] Run `cd bedrock-rs && cargo check -p bedrock-rs-md-live --example live_smoke`.
- [x] Run `cd bedrock-rs && cargo check -p bedrock-rs-mds --example mds_live_smoke`.
- [x] Run Binance Spot MDS bounded live smoke.
- [x] Run Binance Futures MDS bounded live smoke.
- [x] Run Bitget MDS bounded smoke.
