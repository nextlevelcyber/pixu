# bedrock-rs-md

Normalized market data model and deterministic L2 order book reconstruction.

## Owns

- Normalized L2 market-data inputs: `BookSnapshot`, `BookDelta`, `LevelUpdate`.
- Derived market-data outputs: `Bbo`, `BookGap`, `BookReject`, `ReconstructionOutput`.
- `BookReconstructor` state machine.
- `BookRouter` per `(venue, instrument)` routing and state ownership.
- `InstrumentRegistry` allow-list behavior for router book creation.
- Generic snapshot/delta/gap semantics for normalized input.

## Does Not Own

- Exchange WebSocket or REST clients.
- Binance, Bitget, or other venue-specific sequence rules.
- Transport queues or Aeron behavior.
- Pricing, OMS, risk, execution, or monitoring decisions.
- Persistence, historical storage, or binary replay format.

## Public API

- `BookReconstructor::new`.
- `BookReconstructor::apply_snapshot`.
- `BookReconstructor::apply_delta`.
- `BookReconstructor::apply_trusted_delta`.
- `BookReconstructor::state`.
- `BookRouter::new`.
- `BookRouter::with_registry`.
- `BookRouter::apply_snapshot`.
- `BookRouter::apply_delta`.
- `BookRouter::apply_trusted_delta`.
- `BookRouter::state`.
- `InstrumentRegistry::auto_create`.
- `InstrumentRegistry::allow_list`.
- `BookState::{NeedsSnapshot, Ready, Gapped}`.
- `ReconstructionOutput::{Bbo, Gap, Reject}`.

## Reconstruction Semantics

- The reconstructor starts in `NeedsSnapshot`.
- Snapshot initializes or recovers the book and may emit BBO.
- Continuous delta updates price levels and may emit BBO.
- `apply_delta` enforces scalar `last_sequence + 1` continuity for venue-neutral replay or already scalar-normalized streams.
- `apply_trusted_delta` skips the scalar continuity check after an upstream venue adapter has already validated venue-native range continuity, such as Binance `U/u` diff depth batches.
- Quantity `0` removes a price level.
- Sequence gap moves the book to `Gapped` and emits `BookGap`.
- Wrong venue or instrument emits `BookReject` and does not mutate state or consume sequence.
- `BookRouter` keeps independent `BookReconstructor` state per `(venue, instrument)`.
- A gap in one routed book does not affect other books.
- `BookRouter::new` uses auto-create mode: a delta-first event for a new routed book creates the book and emits `DeltaWithoutSnapshot`.
- `BookRouter::with_registry(InstrumentRegistry::allow_list(...))` uses strict mode: unknown keys emit `RejectReason::UnknownInstrument`, do not create a book, and do not mutate existing state.
- `BookReject.expected_venue_id` and `expected_instrument_id` are `None` for unknown registry rejects because no configured expected book exists.

## Verification

Run:

```bash
cd bedrock-rs
cargo test -p bedrock-rs-md
```

## Next Design Questions

- Whether sorted vectors should be replaced by fixed-capacity price grids.
- Whether venue-specific reconstruction belongs in this crate or separate `md-venue-*` crates.
- Whether `MarketDataEvent` remains useful once `ReconstructionOutput` is established.
- Whether strict unknown instruments should later be routed to a quarantine stream in addition to reject output.
