# bedrock-rs-common

Shared primitive domain types for Rust Bedrock.

## Owns

- Fixed-point numeric wrappers: `Price`, `Quantity`.
- Time and sequencing wrappers: `TimestampNs`, `Sequence`.
- Identity wrappers: `VenueId`, `InstrumentId`.
- Shared market side and level primitives: `Side`, `Level`.
- Basic value validation errors.

## Does Not Own

- Market-data reconstruction state.
- Transport, queues, Aeron, IPC, UDP, or backpressure behavior.
- Pricing, OMS, risk, execution, or monitoring concepts.
- Venue-specific symbol parsing or exchange-specific sequence rules.

## Public API

- `SCALE`: fixed-point scale, currently `100_000_000`.
- `Price::new`, `Quantity::new`, `TimestampNs::new`, `Sequence::new`, `InstrumentId::new`, `VenueId::new`.
- `raw()` accessors for primitive conversion at module boundaries.
- `Quantity::is_zero()` for L2 level deletion semantics.

## Verification

Run:

```bash
cd bedrock-rs
cargo test -p bedrock-rs-common
```

## Next Design Questions

- Whether `TimestampNs` should distinguish exchange timestamp from local receive timestamp.
- Whether fixed-point scale remains global or becomes instrument-specific metadata.
- Whether symbol strings belong in common or in a separate instrument registry crate.

