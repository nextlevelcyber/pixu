# Rust MDS Live Routing Design

日期：2026-05-17

状态：Implemented milestone 15

## Goal

新增 `bedrock-rs-mds` crate，把 live venue pipeline 输出接入 venue-neutral `BookRouter`，让真实 live 行情不只停留在 snapshot/delta/gap 解析层，而能形成 MDS 的 BBO / venue gap / stale 输出。

## Scope

新增 crate：

```text
bedrock-rs/crates/bedrock-rs-mds
```

更新 workspace：

```text
bedrock-rs/Cargo.toml
```

## Design

`bedrock-rs-mds` owns:

- `MdsRouter`：组合 `BookRouter` 与 venue pipeline output。
- `MdsOutput`：MDS 层统一输出。
- live smoke example：调用 `bedrock-rs-md-live` one-shot smoke，随后把输出路由进 `MdsRouter`。

It does not own:

- Exchange HTTP/WebSocket IO。
- Venue sequence policy 或 normalization。
- `BookRouter` 内部 reconstruction。
- Transport fanout/Aeron。
- Pricing/OMS/risk/execution。

## API

- `MdsOutput`
  - `Reconstruction(ReconstructionOutput)`
  - `IgnoredStale`
  - `VenueGap(VenuePipelineGap)`
- `MdsRouter`
  - `new`
  - `with_registry`
  - `apply_pipeline_output`
  - `apply_pipeline_outputs`

Mapping rules:

- `VenuePipelineOutput::Normalized(Snapshot)` -> `BookRouter::apply_snapshot`。
- `VenuePipelineOutput::Normalized(Delta)` -> `BookRouter::apply_trusted_delta`。
- `VenuePipelineOutput::IgnoredStale` -> `MdsOutput::IgnoredStale`。
- `VenuePipelineOutput::Gap` -> `MdsOutput::VenueGap`，不合成 delta、不推进 `BookRouter`。

## Trusted Delta Decision

Binance Spot/Futures diff depth uses venue-native range update ids (`U/u`, plus Futures `pu`) rather than scalar `last_sequence + 1` ids. `bedrock-rs-md-venue` validates those range contracts before emitting `VenuePipelineOutput::Normalized(Delta)`.

Therefore MDS must not re-apply scalar `seq + 1` continuity to already venue-validated deltas. The contract is:

- `BookRouter::apply_delta` remains strict and continues to enforce scalar continuity for replay or scalar-normalized streams.
- `BookRouter::apply_trusted_delta` validates identity/state and applies the delta without scalar continuity checking.
- `MdsRouter` uses `apply_trusted_delta` only after the venue pipeline has already accepted the raw venue sequence contract.

## Verification

Tests cover:

- Snapshot pipeline output initializes book and emits BBO。
- Delta pipeline output updates book and emits BBO。
- Venue-validated range delta is accepted without false scalar sequence gap。
- Ignored stale does not create book。
- Venue gap is emitted and does not mutate router state。
- Strict registry reject is surfaced as reconstruction reject。

Verification commands:

```bash
cd bedrock-rs && cargo fmt --check
cd bedrock-rs && cargo test -p bedrock-rs-mds
cd bedrock-rs && cargo test
cd bedrock-rs && cargo clippy --all-targets -- -D warnings
cd bedrock-rs && cargo doc --no-deps
cd bedrock-rs && cargo check -p bedrock-rs-mds --example mds_live_smoke
```

Manual live smoke:

```bash
cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-spot BNBBTC 8
cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-futures BTCUSDT 8
cd bedrock-rs && cargo run -p bedrock-rs-mds --example mds_live_smoke -- bitget-books BTCUSDT 2
```

Smoke summaries:

```text
binance-spot: outputs=11, snapshot/BBO then live BBO after stale ignores
binance-futures: outputs=11, snapshot/BBO then live BBO after stale ignores
bitget-books: outputs=2, snapshot/BBO then live BBO
```

## Non-Goals

- 不实现 continuous daemon loop。
- 不发布到 in-proc/Aeron transport。
- 不维护 instrument metadata catalog。
- 不接 Pricing/OMS。
