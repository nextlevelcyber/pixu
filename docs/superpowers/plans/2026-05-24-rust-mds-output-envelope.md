# Rust MDS Output Envelope Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Follow test-driven-development for changed behavior.

**Goal:** Add stable MDS output envelope metadata for downstream stream routing.

**Architecture:** Keep payload enum unchanged and add metadata helpers in `bedrock-rs-mds`. Do not add SBE codegen or transport changes in this milestone.

**Tech Stack:** Rust 2021, existing `bedrock-rs-common`, `bedrock-rs-md`, `bedrock-rs-md-venue`, `bedrock-rs-mds`.

---

## Tasks

- [x] Add failing test for BBO envelope metadata.
- [x] Add failing test for book reject envelope metadata.
- [x] Add failing test for venue gap envelope metadata.
- [x] Add failing test for ignored stale envelope metadata.
- [x] Add test coverage for book gap envelope metadata.
- [x] Implement `MdsOutputKind`.
- [x] Implement `MdsStreamKey`.
- [x] Implement `MdsOutputEnvelope`.
- [x] Implement `MdsOutput::envelope`.
- [x] Update README/spec/wiki handoff.
- [x] Run `cd bedrock-rs && cargo fmt --check`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-mds`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
