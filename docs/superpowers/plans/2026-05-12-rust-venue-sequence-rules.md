# Rust Venue Sequence Rules Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Document Binance and Bitget venue-specific L2 sequence contracts before any Rust live feed implementation.

**Architecture:** Keep this as a documentation milestone. The Rust `BookReconstructor` remains venue-neutral; future venue adapters own raw bootstrap, continuity, and rebuild decisions.

**Tech Stack:** Markdown, official exchange API docs, existing Bedrock wiki.

---

## Tasks

- [x] Verify current official Binance Spot diff depth and local order book docs.
- [x] Verify current official Binance USDⓈ-M Futures diff depth and local order book docs.
- [x] Verify current official Bitget UTA depth channel docs and changelog.
- [x] Inspect current Java Binance/Bitget feed behavior for carry-over risks.
- [x] Write `docs/superpowers/specs/2026-05-12-rust-venue-sequence-rules-design.md`.
- [x] Add wiki source and architecture page for venue sequence rules.
- [x] Update wiki index/log and MDS foundation handoff.
- [x] Run documentation self-review for placeholders, contradictions, and scope creep.
- [x] Run `cd bedrock-rs && cargo test` to ensure no Rust regression while documenting.
