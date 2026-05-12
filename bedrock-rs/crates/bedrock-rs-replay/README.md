# bedrock-rs-replay

Fixture-driven replay harness for Rust Bedrock market data.

## Owns

- Human-readable `.events` fixture parsing.
- Running normalized snapshot/delta fixtures through `BookRouter` by default.
- Strict instrument registry fixture directives for allow-list validation.
- Single-book fixture mode for explicit wrong identity rejection tests.
- Golden assertions for BBO, gap, and reject outputs.
- Fixture parser errors with source line numbers.

## Does Not Own

- Live exchange feeds.
- Binary replay format.
- Historical data storage.
- Transport behavior.
- Pricing, OMS, risk, execution, or monitoring behavior.

## Public API

- `run_fixture(contents: &str) -> Result<(), ReplayError>`.
- `ReplayError` for malformed fixture or failed golden assertion.

## Fixture Format

The format is documented in `bedrock-rs/fixtures/md/README.md`.

Supported directives:

- `snapshot`
- `delta`
- `expect_bbo`
- `expect_gap`
- `expect_reject`
- `single_book`
- `registry`

By default, replay routes by `(venue, instrument)` and keeps independent book state per key. Use `registry|reject_unknown|venue:instrument,...` when a fixture needs strict allow-list behavior. Use `single_book` only when a fixture intentionally sends wrong venue or instrument events into one expected book.

## Verification

Run:

```bash
cd bedrock-rs
cargo test -p bedrock-rs-replay
```

## Next Design Questions

- Whether to add a binary replay format after text fixtures stabilize.
- Whether replay should emit structured reports for shadow validation.
