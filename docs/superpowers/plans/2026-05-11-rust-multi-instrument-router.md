# Rust Multi-Instrument Router Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add per `(venue, instrument)` MDS routing so replay can validate independent instrument sequence state.

**Architecture:** Implement routing in `bedrock-rs-md` as `BookRouter`; update `bedrock-rs-replay` to use router instead of one reconstructor.

**Tech Stack:** Rust 1.93, standard library only.

---

## Tasks

- [x] Add `BookKey`.
- [x] Add `BookRouter` backed by `BTreeMap<BookKey, BookReconstructor>`.
- [x] Add `apply_snapshot`, `apply_delta`, `state`, and `len` APIs.
- [x] Add md unit tests for interleaved instruments and gap isolation.
- [x] Update replay harness to use `BookRouter`.
- [x] Add `multi_instrument_interleaved.events`.
- [x] Add `multi_instrument_gap_isolated.events`.
- [x] Update fixture README for mixed instrument support.
- [x] Run `cd bedrock-rs && cargo fmt`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Update wiki handoff and log.
