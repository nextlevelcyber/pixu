# Pixu HFT Refactor Checklist

This checklist tracks structural refactor work for the event-driven, multi-process/multi-host runtime.

## A. Event Payload and Serialization Boundary

- [x] Move bus payload contracts to `bedrock-common` (`OrderCommand`, `OrderAckPayload`, `FillPayload`).
- [x] Enable generated SBE module `bedrock-sbe` in root reactor.
- [x] Switch `SbeEventSerde` from custom framed SBE-like bytes to generated SBE codecs.
- [x] Add generated-SBE mappings for `MarketTickPayload` and `BookDeltaPayload`.
- [x] Convert MD publish/consume path to use common payload contracts instead of module-local DTO transport.
- [x] Remove fallback JSON path for high-frequency event types (keep only for non-critical control/debug events).
- [x] Add template-id based guardrails for wrong-type deserialization (fail-fast with explicit template mismatch).

## B. Transport/Wire Path (Aeron/InProc)

- [x] Replace `EventEnvelopeWireCodec` JSON transport with binary wire format (fixed header + var fields).
- [x] Add zero-copy decode path to reduce allocation in `AeronEventBus` and `InProcEventBus`.
- [x] Introduce envelope versioning strategy for wire backward compatibility.

## C. Module Dependency Direction

- [x] Keep serialization implementation concentrated in `bedrock-common`.
- [x] Split `bedrock-md` into `md-api` (interfaces/contracts) and `md-runtime` (adapters/impl) to reduce cross-module leakage.
- [x] Prevent `bedrock-app` from depending on concrete runtime internals where interface suffices.
- [x] Add Maven Enforcer rules for forbidden dependency directions.

## D. Runtime and Reliability

- [x] Introduce event ordering/sequence validation at consumer boundary.
- [x] Add dead-letter/error channel for decode and handler failures.
- [x] Add backpressure/drop policy metrics for each bus mode.

## E. Build and Verification

- [x] Centralize `maven-surefire-plugin` version in parent `pluginManagement`.
- [x] Add contract tests for all event payloads under both JSON and SBE codecs.
- [x] Add an offline profile for CI/dev when external artifact mirrors are unavailable.
