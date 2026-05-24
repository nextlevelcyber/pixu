# Rust MD Live Client Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a network-bound live market data crate with offline-tested endpoint builders and one-shot smoke helpers for Binance/Bitget depth feeds.

**Architecture:** Keep exchange protocol rules in `bedrock-rs-md-venue`; put HTTP/WebSocket IO in `bedrock-rs-md-live`; return `VenuePipelineOutput` without publishing to transport.

**Tech Stack:** Rust 2021, `tokio`, `tokio-tungstenite`, `reqwest`, `futures-util`, `serde_json`.

---

## Tasks

- [x] Add `bedrock-rs-md-live` to the Cargo workspace.
- [x] Add crate manifest with dependencies on common, md-venue, Tokio, Reqwest, Tokio Tungstenite, Futures, and serde_json.
- [x] Add failing tests for Binance endpoint URL builders.
- [x] Add failing tests for Bitget subscribe JSON builder.
- [x] Add failing tests for Bitget ack/event frame classification.
- [x] Implement `BinanceMarket`.
- [x] Implement `BinanceDepthSpeed`.
- [x] Implement `BinanceLiveConfig`.
- [x] Implement `BitgetLiveConfig`.
- [x] Implement `LiveMarketDataError`.
- [x] Implement one-shot Binance Spot smoke helper.
- [x] Implement one-shot Binance Futures smoke helper.
- [x] Implement one-shot Bitget books smoke helper.
- [x] Add crate README and rustdoc.
- [x] Update wiki/log handoff.
- [x] Run `cd bedrock-rs && cargo fmt --check`.
- [x] Run `cd bedrock-rs && cargo test -p bedrock-rs-md-live`.
- [x] Run `cd bedrock-rs && cargo test`.
- [x] Run `cd bedrock-rs && cargo clippy --all-targets -- -D warnings`.
- [x] Run `cd bedrock-rs && cargo doc --no-deps`.
- [x] Run `cd bedrock-rs && cargo run -p bedrock-rs-md-live --example live_smoke -- binance-spot BNBBTC 8`.
- [x] Run `cd bedrock-rs && cargo run -p bedrock-rs-md-live --example live_smoke -- binance-futures BTCUSDT 8`.
- [x] Run `cd bedrock-rs && cargo run -p bedrock-rs-md-live --example live_smoke -- bitget-books BTCUSDT 2`.
