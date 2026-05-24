# Rust Venue Sequence Fixtures Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add text golden fixtures for `bedrock-rs-md-venue` sequence policies.

**Architecture:** Keep the fixture runner inside `bedrock-rs-md-venue` tests. Store fixtures under `bedrock-rs/fixtures/md-venue/`.

**Tech Stack:** Rust 2021, standard library only, text `.events` fixtures.

---

## Tasks

- [x] Add `bedrock-rs/fixtures/md-venue/README.md`.
- [x] Add Binance Spot bridge/stale/gap fixtures.
- [x] Add Binance Futures continuity/mismatch fixtures.
- [x] Add Bitget `books` continuity/mismatch/reset fixtures.
- [x] Add Bitget channel-kind fixture.
- [x] Implement test-only fixture runner in `bedrock-rs-md-venue`.
- [x] Add fixture tests using `include_str!`.
- [x] Update crate README and wiki handoff.
- [x] Run `cd bedrock-rs && cargo fmt`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-md-venue`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
