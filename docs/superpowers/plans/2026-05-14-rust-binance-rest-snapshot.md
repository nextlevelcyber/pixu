# Rust Binance REST Snapshot Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse and normalize Binance Spot/Futures REST depth snapshots for live-feed bootstrap.

**Architecture:** Keep venue-specific REST snapshot parsing in `bedrock-rs-md-venue`; reuse existing raw level parsing and fixed-point normalization helpers; output venue-neutral `BookSnapshot` only after caller supplies the correct venue/instrument identity.

**Tech Stack:** Rust 2021, `serde_json`, existing `bedrock-rs-common` and `bedrock-rs-md`.

---

## Tasks

- [x] Add Binance Spot and Futures REST snapshot JSON fixtures.
- [x] Add failing parser tests for Spot and Futures snapshots.
- [x] Add failing normalization tests for Spot and Futures snapshots.
- [x] Implement `BinanceSpotDepthSnapshot` and `BinanceFuturesDepthSnapshot`.
- [x] Implement `parse_binance_spot_depth_snapshot`.
- [x] Implement `parse_binance_futures_depth_snapshot`.
- [x] Implement `normalize_binance_spot_snapshot`.
- [x] Implement `normalize_binance_futures_snapshot`.
- [x] Update `bedrock-rs-md-venue` README.
- [x] Update wiki/log handoff.
- [x] Run `cd bedrock-rs && cargo fmt --check`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-md-venue`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
