# Rust Transport Semantics Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Make transport semantics explicit in code and harden in-proc close/backpressure behavior.

**Architecture:** Keep transport generic over event type. Add semantics descriptors and close behavior to `InProcChannel<T>` without changing other crates.

**Tech Stack:** Rust 1.93, standard library only.

---

## Tasks

- [x] Add `TransportMode`, `OrderingGuarantee`, `BackpressurePolicy`, `FailureSemantics`, and `TransportSemantics`.
- [x] Add `PublishError::Closed` and `PollError::Closed`.
- [x] Add `InProcChannel::semantics()`.
- [x] Add `InProcChannel::close()` and `is_closed()`.
- [x] Keep FIFO and backpressure behavior unchanged while channel is open.
- [x] Make closed publish fail with `PublishError::Closed`.
- [x] Make closed poll drain queued events before returning `PollError::Closed`.
- [x] Add unit tests for semantics, close, drain, and errors.
- [x] Run `cd bedrock-rs && cargo fmt`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Update wiki handoff and log.
