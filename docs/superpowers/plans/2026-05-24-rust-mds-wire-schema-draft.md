# Rust MDS Wire Schema Draft Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Follow test-driven-development for changed behavior.

**Goal:** Add Rust-first wire schema draft mapping for MDS outputs.

**Architecture:** Keep SBE/codegen out of this milestone. Add explicit wire constants, template ids, payload structs, and `MdsOutput::wire_message()` in `bedrock-rs-mds`.

**Tech Stack:** Rust 2021, existing `bedrock-rs-mds`, `bedrock-rs-md`, `bedrock-rs-md-venue`.

---

## Tasks

- [x] Add failing test for BBO wire mapping.
- [x] Add failing test for BookGap wire mapping.
- [x] Add failing test for BookReject wire mapping.
- [x] Add failing test for VenueGap wire mapping.
- [x] Add failing test for IgnoredStale wire mapping.
- [x] Implement wire constants and `MdsWireTemplate`.
- [x] Implement `MdsWireEnvelope`.
- [x] Implement wire payload structs and reason enums.
- [x] Implement `MdsWireMessage`.
- [x] Implement `MdsOutput::wire_message`.
- [x] Update README/spec/wiki handoff.
- [x] Run `cd bedrock-rs && cargo fmt --check`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-mds`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
