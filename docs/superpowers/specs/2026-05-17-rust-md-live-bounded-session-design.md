# Rust MD Live Bounded Session Design

日期：2026-05-17

状态：Implemented milestone 16

## Goal

把 `bedrock-rs-md-live` 从 one-shot smoke 扩展为 bounded live session：完成 snapshot/bootstrap 后继续读取一段真实 WebSocket depth frames，让 MDS 可以验证连续 live BBO，而不只验证第一条 actionable delta。

## Scope

更新 crate：

```text
bedrock-rs/crates/bedrock-rs-md-live
bedrock-rs/crates/bedrock-rs-mds
```

## Design

`bedrock-rs-md-live` adds:

- `BinanceLiveSessionLimits`
  - `bootstrap_buffer_messages`
  - `post_bootstrap_messages`
- `binance_spot_session_once`
- `binance_futures_session_once`

Semantics:

- Binance session still uses WS-first buffer + REST snapshot bootstrap.
- Buffered messages are processed first.
- After bootstrap, session reads exactly `post_bootstrap_messages` additional WebSocket text frames.
- Any venue gap is returned as `VenuePipelineOutput::Gap`; session does not silently rebuild.
- Existing `binance_spot_smoke_once` and `binance_futures_smoke_once` remain available for compatibility.

`bedrock-rs-mds` example updates:

- `mds_live_smoke` accepts optional `bootstrap_messages` and `post_bootstrap_messages`.
- Binance examples use bounded session.
- Bitget keeps its existing bounded depth-message count because it receives snapshot/update over the same WS channel.

## Non-Goals

- 不实现 infinite daemon。
- 不实现 automatic reconnect/rebuild loop。
- 不发布到 in-proc/Aeron transport。
- 不引入 instrument catalog 或 production config loader。

## Verification

Tests should cover:

- `BinanceLiveSessionLimits` stores bootstrap/post-bootstrap limits.
- MDS example compiles with the new session API.

Verification commands:

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-md-live
cd bedrock-rs && cargo test -p bedrock-rs-mds
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
cd bedrock-rs && cargo check -p bedrock-rs-md-live --example live_smoke
cd bedrock-rs && cargo check -p bedrock-rs-mds --example mds_live_smoke
```

Manual smoke:

```bash
cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-spot BNBBTC 8 5
cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-futures BTCUSDT 8 5
cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- bitget-books BTCUSDT 3
```

Smoke summaries:

```text
binance-spot BNBBTC 8 3: outputs=12, snapshot BBO + stale ignores + 3 live BBOs
binance-futures BTCUSDT 8 3: outputs=12, snapshot BBO + stale ignores + 2 live BBOs
bitget-books BTCUSDT 3: outputs=3, snapshot BBO + 2 live BBOs
```
