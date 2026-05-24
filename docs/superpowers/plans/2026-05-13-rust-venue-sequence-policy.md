# Rust Venue Sequence Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Rust crate for offline Binance/Bitget L2 sequence policy checks before live feed parsing.

**Architecture:** Create `bedrock-rs-md-venue` as a venue-specific crate. Keep `bedrock-rs-md` generic and unchanged except for workspace membership.

**Tech Stack:** Rust 2021, standard library only.

---

## Tasks

- [x] Add `bedrock-rs-md-venue` to the Cargo workspace.
- [x] Write unit tests for Binance Spot `U/u` snapshot bridge, stale, and gap behavior.
- [x] Write unit tests for Binance Futures first bridge, `pu` continuity, and `pu` mismatch behavior.
- [x] Write unit tests for Bitget `books` `seq/pseq` continuity, reset, and channel kind classification.
- [x] Implement `SequenceDecision` and `SequenceGapReason`.
- [x] Implement `BinanceSpotSequencePolicy`.
- [x] Implement `BinanceFuturesSequencePolicy`.
- [x] Implement `BitgetBooksSequencePolicy`.
- [x] Implement `BitgetDepthChannelKind`.
- [x] Add crate README and crate-level rustdoc.
- [x] Update wiki/log/handoff.
- [x] Run `cd bedrock-rs && cargo fmt`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
