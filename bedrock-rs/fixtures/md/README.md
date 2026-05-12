# MD Replay Fixture Format

Fixtures are pipe-delimited `.events` files. Blank lines and lines starting with `#` are ignored.

All prices and quantities use fixed-point raw integers with scale `1e-8`.

Fixtures may interleave multiple instruments. Replay routes each event by `(venue, instrument)` and keeps independent book state and sequence state per key.

## Directives

```text
single_book|venue|instrument
registry|auto_create
registry|reject_unknown|venue:instrument,venue:instrument
snapshot|venue|instrument|sequence|timestamp_ns|bid_levels|ask_levels
delta|venue|instrument|sequence|timestamp_ns|updates
expect_bbo|venue|instrument|sequence|timestamp_ns|bid_price|bid_qty|ask_price|ask_qty
expect_gap|venue|instrument|expected_sequence|actual_sequence|reason
expect_reject|expected_venue|expected_instrument|actual_venue|actual_instrument|sequence|reason
```

`bid_levels` and `ask_levels` are comma-separated `price:quantity` entries. Use `-` for an empty side.

`updates` are comma-separated `side:price:quantity` entries. `side` is `B` for bid or `A` for ask. Quantity `0` deletes the price level.

`single_book` switches the fixture from router mode to a single expected book. Use it only for tests that intentionally send wrong venue or instrument events to verify `BookReject`.

`registry|auto_create` switches replay back to the default router mode.

`registry|reject_unknown|...` switches replay to strict router mode. The third field is a comma-separated allow-list of `venue:instrument` keys. Use `-` for an empty allow-list.

`expected_sequence` in `expect_gap` is either a sequence number or `none`.

`expected_venue` and `expected_instrument` in `expect_reject` are either ids or `none`. Registry unknown rejects use `none|none` because no configured expected book exists.

Gap reasons:

- `DeltaWithoutSnapshot`
- `SequenceGap`

Reject reasons:

- `WrongVenue`
- `WrongInstrument`
- `UnknownInstrument`

Mixed instrument fixtures should assert each emitted output immediately after the input that produces it. A sequence gap for one instrument must not affect expected output for another instrument.
