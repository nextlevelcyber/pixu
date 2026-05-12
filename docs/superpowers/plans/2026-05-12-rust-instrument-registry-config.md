# Rust Instrument Registry Config Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add strict instrument registry support to `BookRouter` while preserving the current auto-create default.

**Architecture:** Keep registry ownership inside `bedrock-rs-md`; expose `InstrumentRegistry` and `BookRouter::with_registry`; extend replay with explicit registry directives for golden fixture coverage.

**Tech Stack:** Rust 1.93, standard library only.

---

## Tasks

- [x] Add md unit tests for strict registry allow-list and unknown reject.
- [x] Add replay fixture tests for configured instruments and unknown instrument reject.
- [x] Add `UnknownInstrumentPolicy`, `InstrumentRegistry`, and `RejectReason::UnknownInstrument`.
- [x] Change `BookReject.expected_venue_id` and `expected_instrument_id` to `Option`.
- [x] Add `BookRouter::with_registry` and route-time registry checks.
- [x] Update replay parser with `registry|reject_unknown|...` and optional expected reject parsing.
- [x] Add instrument registry fixtures.
- [x] Update crate README/rustdoc and fixture README.
- [x] Run `cd bedrock-rs && cargo fmt`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
- [x] Update wiki handoff and log.
