# Rust MDS In-Proc Fanout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Follow test-driven-development for changed behavior.

**Goal:** Publish MDS outputs to transport-neutral in-proc channel without coupling MDS to a concrete deployment mode.

**Architecture:** `bedrock-rs-mds` depends on the transport trait, not on a concrete daemon or Aeron implementation.

---

## Tasks

- [x] Add failing test for snapshot BBO published to `InProcChannel<MdsOutput>`.
- [x] Add failing test for publish backpressure surfaced with published count.
- [x] Add `bedrock-rs-transport` dependency to `bedrock-rs-mds`.
- [x] Implement `MdsPublishError`.
- [x] Implement `MdsRouter::apply_pipeline_output_to`.
- [x] Implement `MdsRouter::apply_pipeline_outputs_to`.
- [x] Update README/spec/wiki handoff.
- [x] Run `cd bedrock-rs && cargo fmt --check`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-mds`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
