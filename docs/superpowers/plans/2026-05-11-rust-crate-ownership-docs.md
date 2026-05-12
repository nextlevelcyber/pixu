# Rust Crate Ownership Docs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Document ownership boundaries for the four Rust foundation crates.

**Architecture:** Add human README files next to each crate manifest and concise rustdoc summaries at each crate root.

**Tech Stack:** Markdown, Rust rustdoc, Cargo.

---

## Tasks

- [x] Add `README.md` for `bedrock-rs-common`.
- [x] Add `README.md` for `bedrock-rs-transport`.
- [x] Add `README.md` for `bedrock-rs-md`.
- [x] Add `README.md` for `bedrock-rs-replay`.
- [x] Add crate-level rustdoc to each `src/lib.rs`.
- [x] Run `cd bedrock-rs && cargo fmt`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
- [x] Update wiki handoff and log.
