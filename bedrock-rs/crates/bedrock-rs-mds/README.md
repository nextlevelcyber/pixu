# bedrock-rs-mds

Market Data Service composition layer for Rust Bedrock.

## Owns

- Routing `VenuePipelineOutput` into `BookRouter`.
- MDS-level output shape for BBO/reconstruction, stale venue updates, and venue gaps.
- Stable output envelope metadata for downstream stream routing.
- Rust-first wire schema draft metadata and payload mapping for future SBE/Aeron work.
- Publishing `MdsOutput` into a caller-provided transport-neutral `Publisher<MdsOutput>`.
- Bounded live smoke example that receives public market data and routes it through MDS reconstruction.

## Does Not Own

- Exchange HTTP/WebSocket clients.
- Venue sequence rules or normalization.
- Book reconstruction internals.
- Transport fanout or Aeron behavior.
- Pricing, OMS, risk, execution, or monitoring decisions.

## Public API

- `MdsOutput`.
- `MdsOutputKind`.
- `MdsStreamKey`.
- `MdsOutputEnvelope`.
- `MdsWireTemplate`.
- `MdsWireEnvelope`.
- `MdsWireMessage`.
- `MDS_WIRE_SCHEMA_ID`.
- `MDS_WIRE_SCHEMA_VERSION`.
- `MdsPublishError`.
- `MdsRouter`.

## Semantics

- Normalized snapshots are forwarded into `BookRouter::apply_snapshot`.
- Normalized deltas from venue pipelines are forwarded into `BookRouter::apply_trusted_delta`, because venue-native range continuity has already been validated upstream.
- Ignored stale venue updates remain visible as `MdsOutput::IgnoredStale` with venue/instrument identity.
- Venue gaps remain visible as `MdsOutput::VenueGap` and are not converted into synthetic deltas.
- `MdsOutput::envelope()` returns stable stream metadata: venue, instrument, output kind, optional sequence, and optional timestamp.
- `MdsOutput::wire_message()` maps each output into a Rust-first wire draft with template IDs `1200..1204`; missing optional fields are encoded as `0`.
- `apply_pipeline_output_to` and `apply_pipeline_outputs_to` publish reconstructed outputs to any `Publisher<MdsOutput>`.
- Publish backpressure/closed errors are surfaced with the number of outputs already published; no rollback is attempted after a publish failure.

## Verification

Run:

```bash
cd bedrock-rs
cargo test -p bedrock-rs-mds
```

Manual live smoke:

```bash
cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-spot BNBBTC 8
cargo run -p bedrock-rs-mds --example mds_live_smoke -- binance-spot BNBBTC 8 5
```
