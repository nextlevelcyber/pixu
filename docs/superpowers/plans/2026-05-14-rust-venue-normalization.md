# Rust Venue Normalization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert parsed raw venue depth events into `bedrock-rs-md` normalized book snapshots/deltas.

**Architecture:** Keep conversion in `bedrock-rs-md-venue`; depend on `bedrock-rs-common` and `bedrock-rs-md`; do not mutate `BookRouter` or live feed code.

**Tech Stack:** Rust 2021, standard library, existing `serde_json`.

---

## Tasks

- [x] Add `bedrock-rs-common` and `bedrock-rs-md` dependencies to `bedrock-rs-md-venue`.
- [x] Add fixed-point decimal parser tests.
- [x] Add Binance Spot/Futures normalization tests from JSON fixtures.
- [x] Add Bitget snapshot/update normalization tests from JSON fixtures.
- [x] Implement `VenueNormalizeError`.
- [x] Implement `NormalizedDepthEvent`.
- [x] Implement `decimal_to_scaled_i64`.
- [x] Implement Binance normalization functions.
- [x] Implement Bitget normalization function.
- [x] Update README/wiki/log/handoff.
- [x] Run `cd bedrock-rs && cargo fmt --check`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-md-venue`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
