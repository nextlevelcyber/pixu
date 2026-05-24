# bedrock-rs-md-venue

Venue-specific market-data parsers and sequence policies for Rust Bedrock.

## Owns

- Binance Spot `U/u` range continuity decisions.
- Binance USDⓈ-M Futures `U/u/pu` continuity decisions.
- Bitget UTA `books` `seq/pseq` continuity decisions.
- Bitget depth channel classification for incremental vs snapshot-only topics.
- Raw JSON depth payload parsing for Binance Spot, Binance USDⓈ-M Futures, and Bitget UTA.
- Binance Spot and USDⓈ-M Futures REST depth snapshot parsing.
- Fixed-point decimal normalization from raw venue price/quantity strings.
- Conversion from parsed venue depth events into `BookSnapshot` / `BookDelta`.
- Single-instrument offline bootstrap pipelines that combine snapshot, sequence policy, normalization, stale ignore, and gap output.
- Text golden fixtures under `bedrock-rs/fixtures/md-venue/`.
- Raw JSON fixtures under `bedrock-rs/fixtures/md-venue/json/`.

## Does Not Own

- WebSocket clients.
- REST snapshot fetchers.
- SBE parsing.
- Network bootstrap buffering.
- Generic book reconstruction.
- Transport queues or Aeron behavior.
- Pricing, OMS, risk, execution, or monitoring decisions.

## Public API

- `RawLevel`.
- `VenueParseError`.
- `VenueNormalizeError`.
- `BinanceSpotDepthUpdate`.
- `BinanceFuturesDepthUpdate`.
- `BinanceSpotDepthSnapshot`.
- `BinanceFuturesDepthSnapshot`.
- `BitgetDepthMessage`.
- `BitgetDepthAction`.
- `NormalizedDepthEvent`.
- `VenuePipelineError`.
- `VenuePipelineGap`.
- `VenuePipelineOutput`.
- `BinanceSpotDepthPipeline`.
- `BinanceFuturesDepthPipeline`.
- `BitgetBooksDepthPipeline`.
- `SequenceDecision`.
- `SequenceGapReason`.
- `BinanceSpotSequencePolicy`.
- `BinanceFuturesSequencePolicy`.
- `BitgetBooksSequencePolicy`.
- `BitgetDepthChannelKind`.
- `parse_binance_spot_depth_update`.
- `parse_binance_futures_depth_update`.
- `parse_binance_spot_depth_snapshot`.
- `parse_binance_futures_depth_snapshot`.
- `parse_bitget_depth_message`.
- `decimal_to_scaled_i64`.
- `timestamp_ms_to_ns`.
- `normalize_binance_spot_snapshot`.
- `normalize_binance_futures_snapshot`.
- `normalize_binance_spot_delta`.
- `normalize_binance_futures_delta`.
- `normalize_bitget_depth_message`.

## Semantics

- Policies use raw `u64` venue sequence ids because Bitget `pseq = 0` is a valid reset signal.
- Parsers preserve price and quantity as raw strings until normalization.
- Normalization uses an exact decimal parser with scale `1e-8`; it does not use floats.
- Binance Spot REST snapshots normalize to `BookSnapshot` with caller-supplied receive/fetch timestamp because the Spot REST depth response does not include an exchange timestamp.
- Binance Futures REST snapshots normalize to `BookSnapshot` with sequence `lastUpdateId` and timestamp `E`.
- Binance Spot/Futures depth updates normalize to `BookDelta` with sequence `u`.
- Bitget depth snapshots normalize to `BookSnapshot`; Bitget updates normalize to `BookDelta`, both with sequence `seq`.
- `SequenceDecision::Apply` means a venue adapter may normalize and forward the event batch.
- `SequenceDecision::IgnoreStale` means the event is old or duplicate and should not be published downstream.
- `SequenceDecision::Gap` means the adapter must stop incremental application and rebuild/resubscribe.
- `SequenceDecision::ResetRequired` means the venue explicitly signaled a reset path and local state was cleared.
- `VenuePipelineOutput::Normalized` means the event passed venue sequence checks and can be forwarded to venue-neutral MD.
- `VenuePipelineOutput::IgnoredStale` means the update was old or duplicate and carries venue/instrument identity plus event sequence for downstream observability.
- `VenuePipelineOutput::Gap` means the live adapter must stop applying that stream and rebuild from a new snapshot.

## Verification

Run:

```bash
cd bedrock-rs
cargo test -p bedrock-rs-md-venue
```

## Next Design Questions

- Where to place network live clients and async runtime ownership.
- How bootstrap buffering should coordinate WS-first buffering and REST snapshot fetch.
- How to expose rebuild metrics without coupling this crate to monitoring.
