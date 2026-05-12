# Rust Bedrock MDS Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Build the first Rust-first Bedrock MDS foundation milestone: Cargo workspace, common domain types, transport-neutral in-proc channel, deterministic L2 reconstruction, replay fixtures, tests, and wiki handoff.

**Architecture:** Add a standalone `bedrock-rs/` Cargo workspace inside the current repo. Keep crates focused: `bedrock-rs-common` owns primitive domain types, `bedrock-rs-transport` owns transport traits and in-proc channel, `bedrock-rs-md` owns normalized market-data events and book reconstruction, and `bedrock-rs-replay` owns fixture loading and replay assertions.

**Tech Stack:** Rust 1.93, Cargo workspace, standard library only, Rust 2021 edition, no external crates.

---

## Files

- Create `bedrock-rs/Cargo.toml`: workspace root.
- Create `bedrock-rs/crates/bedrock-rs-common/Cargo.toml`
- Create `bedrock-rs/crates/bedrock-rs-common/src/lib.rs`
- Create `bedrock-rs/crates/bedrock-rs-transport/Cargo.toml`
- Create `bedrock-rs/crates/bedrock-rs-transport/src/lib.rs`
- Create `bedrock-rs/crates/bedrock-rs-md/Cargo.toml`
- Create `bedrock-rs/crates/bedrock-rs-md/src/lib.rs`
- Create `bedrock-rs/crates/bedrock-rs-replay/Cargo.toml`
- Create `bedrock-rs/crates/bedrock-rs-replay/src/lib.rs`
- Create fixtures under `bedrock-rs/fixtures/md/`
- Update `wiki/pages/architecture/rust-bedrock-mds-foundation.md`
- Update `wiki/log.md`

## Task 1: Workspace Skeleton

- [x] Create workspace and four crates using `Cargo.toml` files with Rust 2021 edition.
- [x] Keep dependencies local and explicit: transport depends on common; md depends on common; replay depends on common and md.
- [x] Run `cd bedrock-rs && cargo metadata --no-deps`.
- [x] Expected: Cargo recognizes all four workspace members.

## Task 2: Common Domain Types

- [x] Implement `Price`, `Quantity`, `TimestampNs`, `Sequence`, `InstrumentId`, `VenueId`, `Side`, `Level`, and validation errors.
- [x] Use fixed-point scale `SCALE = 100_000_000`.
- [x] Reject invalid price 0 or below; allow quantity 0 for level deletion.
- [x] Add tests for valid values and invalid values.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-common`.
- [x] Expected: common tests pass.

## Task 3: Transport Traits And In-Proc Channel

- [x] Implement `Publisher<T>` and `Subscriber<T>` traits.
- [x] Implement bounded SPSC `InProcChannel<T>` using `VecDeque`.
- [x] `publish` must return `PublishError::Backpressure` when full.
- [x] `poll` must return `Ok(None)` when empty.
- [x] Add tests for publish/poll ordering, empty poll, and backpressure.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-transport`.
- [x] Expected: transport tests pass.

## Task 4: Market Data Events And L2 Reconstruction

- [x] Implement `BookSnapshot`, `BookDelta`, `Bbo`, `BookGap`, `MarketDataEvent`, `BookReconstructor`, and `BookState`.
- [x] Initial reconstructor state is `NeedsSnapshot`.
- [x] Snapshot transitions state to `Ready` and may emit BBO.
- [x] Continuous delta updates book and emits BBO when top-of-book changes or remains valid.
- [x] Quantity 0 deletes a level.
- [x] Sequence gap transitions to `Gapped` and emits `BookGap`.
- [x] Snapshot after gap recovers to `Ready`.
- [x] Add unit tests for snapshot initialization, delta update, level removal, gap, and recovery.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-md`.
- [x] Expected: md tests pass.

## Task 5: Replay Fixtures

- [x] Add human-readable `.events` fixture format with pipe-delimited fields.
- [x] Implement fixture parser in `bedrock-rs-replay` using standard library string parsing.
- [x] Implement `run_fixture` that loads normalized events and expected BBO/gap assertions.
- [x] Create fixtures:
  - `basic_snapshot_then_delta.events`
  - `remove_level.events`
  - `sequence_gap.events`
  - `snapshot_recovers_gap.events`
- [x] Add tests that execute all fixtures.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-replay`.
- [x] Expected: replay tests pass.

## Task 6: Full Verification And Docs

- [x] Run `cd bedrock-rs && cargo fmt`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Update `wiki/pages/architecture/rust-bedrock-mds-foundation.md` with implementation status and verification commands.
- [x] Append a handoff entry to `wiki/log.md`.
- [x] Run `rg` checks for the new wiki page and plan references.
- [x] Expected: all tests pass and wiki points to the completed milestone.

## Self-Review

- Spec coverage: workspace, common, transport, md, replay, fixtures, and docs are covered.
- Placeholder scan: no incomplete implementation placeholders.
- Type consistency: crate names and module names match the design spec.
- Scope check: Pricing, OMS, live feed, Aeron implementation, and SBE codegen remain out of scope.
