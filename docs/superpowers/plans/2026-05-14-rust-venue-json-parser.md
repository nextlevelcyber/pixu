# Rust Venue JSON Parser Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Parse official Binance/Bitget L2 depth JSON payloads into raw venue event structs.

**Architecture:** Extend `bedrock-rs-md-venue` with structured JSON parsing using `serde_json::Value`; keep parsing separate from sequence policy and normalized book reconstruction.

**Tech Stack:** Rust 2021, `serde_json`, text JSON fixtures.

---

## Tasks

- [x] Add `serde_json` dependency to `bedrock-rs-md-venue`.
- [x] Add raw JSON fixtures under `bedrock-rs/fixtures/md-venue/json/`.
- [x] Add tests for Binance Spot raw and combined diff depth payloads.
- [x] Add test for Binance Futures diff depth payload with `pu` and `T`.
- [x] Add tests for Bitget `books` snapshot/update and `books5` snapshot-only payloads.
- [x] Implement raw event structs.
- [x] Implement `VenueParseError`.
- [x] Implement Binance parser helpers.
- [x] Implement Bitget parser helpers.
- [x] Update README/wiki/log/handoff.
- [x] Run `cd bedrock-rs && cargo fmt`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-md-venue`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
