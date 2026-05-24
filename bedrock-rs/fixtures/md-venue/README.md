# MD Venue Sequence Fixture Format

Fixtures are pipe-delimited `.events` files for `bedrock-rs-md-venue` sequence policy tests.

Blank lines and lines starting with `#` are ignored.

## Directives

```text
policy|binance_spot
policy|binance_futures
policy|bitget_books

spot_snapshot|last_update_id
spot_event|first_update_id|final_update_id

futures_first|last_update_id|first_update_id|final_update_id
futures_event|first_update_id|final_update_id|previous_final_update_id

bitget_snapshot|sequence
bitget_update|sequence|previous_sequence

expect|Apply
expect|IgnoreStale
expect_gap|NeedsSnapshot
expect_gap|SnapshotNotBridged
expect_gap|RangeGap
expect_gap|InvalidRange
expect_gap|PreviousFinalUpdateMismatch
expect_gap|PreviousSequenceMismatch
expect_reset|VenueReset

expect_channel|topic|Incremental
expect_channel|topic|SnapshotOnly
expect_channel|topic|None
```

Every sequence event must be followed by an expectation directive. Channel-kind expectations are standalone assertions.

These fixtures use raw venue sequence ids, not normalized `Sequence`, because Bitget `pseq = 0` is a valid reset signal.
