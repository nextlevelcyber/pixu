# bedrock-rs-transport

Transport-neutral publisher/subscriber contracts for Rust Bedrock.

## Owns

- `Publisher<T>` and `Subscriber<T>` traits.
- Transport semantics descriptors: mode, ordering, backpressure, failure behavior.
- In-process bounded SPSC channel used by the first foundation milestone.
- Open/closed lifecycle behavior for the in-proc channel.

## Does Not Own

- Market-data event semantics.
- Pricing, OMS, risk, execution, or monitoring behavior.
- Aeron binding implementation.
- Channel naming, stream id, session id, or deployment configuration.
- Serialization format or SBE schema.

## Public API

- `Publisher<T>::publish(event)`.
- `Subscriber<T>::poll()`.
- `InProcChannel::with_capacity`.
- `InProcChannel::semantics`.
- `InProcChannel::close` and `is_closed`.
- `PublishError::{Backpressure, Closed}`.
- `PollError::Closed`.

## Semantics

Current in-proc semantics:

- non-blocking
- FIFO per publisher
- bounded capacity
- reject when full
- explicit close
- closed channel drains queued events before reporting closed to pollers

## Verification

Run:

```bash
cd bedrock-rs
cargo test -p bedrock-rs-transport
```

## Next Design Questions

- How Aeron IPC maps publication pressure to `PublishError`.
- How Aeron UDP loss or unavailable image maps to poll errors.
- Whether multi-producer channels are separate types or a mode of the same trait.

