# Rust MDS Contract Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Separate MDS reconstruction outputs from input market-data events, reject wrong venue/instrument updates explicitly, document fixture format, and verify through unit and replay tests.

**Architecture:** Keep the existing four-crate workspace. Only `bedrock-rs-md` owns reconstruction semantics; `bedrock-rs-replay` consumes those outputs for fixture assertions.

**Tech Stack:** Rust 1.93, standard library only.

---

## Tasks

- [x] Add `ReconstructionOutput`, `BookReject`, and `RejectReason` to `bedrock-rs-md`.
- [x] Change `BookReconstructor::apply_snapshot` and `apply_delta` to return `Vec<ReconstructionOutput>`.
- [x] Reject wrong venue / wrong instrument without mutating state or sequence.
- [x] Update MDS unit tests for BBO/gap output enum and identity rejection.
- [x] Update replay harness to consume `ReconstructionOutput`.
- [x] Add `expect_reject` fixture directive.
- [x] Add `wrong_identity_rejected.events`.
- [x] Add `bedrock-rs/fixtures/md/README.md`.
- [x] Run `cd bedrock-rs && cargo fmt`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Update wiki handoff and log.
