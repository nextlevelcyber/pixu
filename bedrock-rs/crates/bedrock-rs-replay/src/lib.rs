//! Fixture-driven replay harness for Rust Bedrock market data.
//!
//! This crate owns parsing human-readable `.events` fixtures and asserting
//! deterministic reconstruction outputs. Replay uses `BookRouter` by default so
//! mixed-instrument fixtures keep independent book state per `(venue,
//! instrument)`, and fixtures can opt into strict instrument registry checks. It
//! does not own live feeds, binary replay, transport behavior, pricing, OMS, or
//! storage.

use bedrock_rs_common::{
    InstrumentId, Level, Price, Quantity, Sequence, Side, TimestampNs, VenueId,
};
use bedrock_rs_md::{
    Bbo, BookDelta, BookGap, BookKey, BookReconstructor, BookReject, BookRouter, BookSnapshot,
    GapReason, InstrumentRegistry, LevelUpdate, ReconstructionOutput, RejectReason,
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReplayError {
    line: usize,
    message: String,
}

impl ReplayError {
    fn new(line: usize, message: impl Into<String>) -> Self {
        Self {
            line,
            message: message.into(),
        }
    }
}

enum ReplayEngine {
    Router(BookRouter),
    SingleBook(BookReconstructor),
}

impl ReplayEngine {
    fn apply_snapshot(&mut self, snapshot: BookSnapshot) -> Vec<ReconstructionOutput> {
        match self {
            Self::Router(router) => router.apply_snapshot(snapshot),
            Self::SingleBook(reconstructor) => reconstructor.apply_snapshot(snapshot),
        }
    }

    fn apply_delta(&mut self, delta: BookDelta) -> Vec<ReconstructionOutput> {
        match self {
            Self::Router(router) => router.apply_delta(delta),
            Self::SingleBook(reconstructor) => reconstructor.apply_delta(delta),
        }
    }
}

pub fn run_fixture(contents: &str) -> Result<(), ReplayError> {
    let mut engine = ReplayEngine::Router(BookRouter::new());
    let mut pending_outputs: Vec<ReconstructionOutput> = Vec::new();

    for (index, raw_line) in contents.lines().enumerate() {
        let line_number = index + 1;
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }

        let parts: Vec<&str> = line.split('|').collect();
        match parts.first().copied() {
            Some("single_book") => {
                if !pending_outputs.is_empty() {
                    return Err(ReplayError::new(
                        line_number,
                        "single_book cannot be declared while outputs are pending",
                    ));
                }
                require_len(line_number, &parts, 3)?;
                let venue_id = venue(line_number, parts[1])?;
                let instrument_id = instrument(line_number, parts[2])?;
                engine = ReplayEngine::SingleBook(BookReconstructor::new(venue_id, instrument_id));
            }
            Some("registry") => {
                if !pending_outputs.is_empty() {
                    return Err(ReplayError::new(
                        line_number,
                        "registry cannot be declared while outputs are pending",
                    ));
                }
                engine = parse_registry(line_number, &parts)?;
            }
            Some("snapshot") => {
                let snapshot = parse_snapshot(line_number, &parts)?;
                pending_outputs.extend(engine.apply_snapshot(snapshot));
            }
            Some("delta") => {
                let delta = parse_delta(line_number, &parts)?;
                pending_outputs.extend(engine.apply_delta(delta));
            }
            Some("expect_bbo") => {
                let expected = parse_expected_bbo(line_number, &parts)?;
                let actual = pop_next_output(line_number, &mut pending_outputs)?;
                if actual != ReconstructionOutput::Bbo(expected) {
                    return Err(ReplayError::new(
                        line_number,
                        format!("expected BBO {:?}, got {:?}", expected, actual),
                    ));
                }
            }
            Some("expect_gap") => {
                let expected = parse_expected_gap(line_number, &parts)?;
                let actual = pop_next_output(line_number, &mut pending_outputs)?;
                if actual != ReconstructionOutput::Gap(expected) {
                    return Err(ReplayError::new(
                        line_number,
                        format!("expected gap {:?}, got {:?}", expected, actual),
                    ));
                }
            }
            Some("expect_reject") => {
                let expected = parse_expected_reject(line_number, &parts)?;
                let actual = pop_next_output(line_number, &mut pending_outputs)?;
                if actual != ReconstructionOutput::Reject(expected) {
                    return Err(ReplayError::new(
                        line_number,
                        format!("expected reject {:?}, got {:?}", expected, actual),
                    ));
                }
            }
            Some(other) => {
                return Err(ReplayError::new(
                    line_number,
                    format!("unknown fixture directive {other}"),
                ));
            }
            None => {}
        }
    }

    if !pending_outputs.is_empty() {
        return Err(ReplayError::new(
            contents.lines().count(),
            format!("{} unconsumed outputs", pending_outputs.len()),
        ));
    }

    Ok(())
}

fn pop_next_output(
    line: usize,
    pending_outputs: &mut Vec<ReconstructionOutput>,
) -> Result<ReconstructionOutput, ReplayError> {
    if pending_outputs.is_empty() {
        return Err(ReplayError::new(
            line,
            "expected output but none was pending",
        ));
    }
    Ok(pending_outputs.remove(0))
}

fn parse_snapshot(line: usize, parts: &[&str]) -> Result<BookSnapshot, ReplayError> {
    require_len(line, parts, 7)?;
    Ok(BookSnapshot {
        venue_id: venue(line, parts[1])?,
        instrument_id: instrument(line, parts[2])?,
        sequence: sequence(line, parts[3])?,
        timestamp_ns: timestamp(line, parts[4])?,
        bids: parse_levels(line, parts[5], Side::Bid)?,
        asks: parse_levels(line, parts[6], Side::Ask)?,
    })
}

fn parse_delta(line: usize, parts: &[&str]) -> Result<BookDelta, ReplayError> {
    require_len(line, parts, 6)?;
    Ok(BookDelta {
        venue_id: venue(line, parts[1])?,
        instrument_id: instrument(line, parts[2])?,
        sequence: sequence(line, parts[3])?,
        timestamp_ns: timestamp(line, parts[4])?,
        updates: parse_updates(line, parts[5])?,
    })
}

fn parse_expected_bbo(line: usize, parts: &[&str]) -> Result<Bbo, ReplayError> {
    require_len(line, parts, 9)?;
    Ok(Bbo {
        venue_id: venue(line, parts[1])?,
        instrument_id: instrument(line, parts[2])?,
        sequence: sequence(line, parts[3])?,
        timestamp_ns: timestamp(line, parts[4])?,
        bid_price: price(line, parts[5])?,
        bid_quantity: quantity(line, parts[6])?,
        ask_price: price(line, parts[7])?,
        ask_quantity: quantity(line, parts[8])?,
    })
}

fn parse_expected_gap(line: usize, parts: &[&str]) -> Result<BookGap, ReplayError> {
    require_len(line, parts, 6)?;
    Ok(BookGap {
        venue_id: venue(line, parts[1])?,
        instrument_id: instrument(line, parts[2])?,
        expected_sequence: parse_optional_sequence(line, parts[3])?,
        actual_sequence: sequence(line, parts[4])?,
        reason: match parts[5] {
            "DeltaWithoutSnapshot" => GapReason::DeltaWithoutSnapshot,
            "SequenceGap" => GapReason::SequenceGap,
            other => {
                return Err(ReplayError::new(
                    line,
                    format!("unknown gap reason {other}"),
                ));
            }
        },
    })
}

fn parse_expected_reject(line: usize, parts: &[&str]) -> Result<BookReject, ReplayError> {
    require_len(line, parts, 7)?;
    Ok(BookReject {
        expected_venue_id: parse_optional_venue(line, parts[1])?,
        expected_instrument_id: parse_optional_instrument(line, parts[2])?,
        actual_venue_id: venue(line, parts[3])?,
        actual_instrument_id: instrument(line, parts[4])?,
        sequence: sequence(line, parts[5])?,
        reason: match parts[6] {
            "WrongVenue" => RejectReason::WrongVenue,
            "WrongInstrument" => RejectReason::WrongInstrument,
            "UnknownInstrument" => RejectReason::UnknownInstrument,
            other => {
                return Err(ReplayError::new(
                    line,
                    format!("unknown reject reason {other}"),
                ));
            }
        },
    })
}

fn parse_registry(line: usize, parts: &[&str]) -> Result<ReplayEngine, ReplayError> {
    let mode = parts
        .get(1)
        .copied()
        .ok_or_else(|| ReplayError::new(line, "missing registry mode"))?;
    match mode {
        "auto_create" => {
            require_len(line, parts, 2)?;
            Ok(ReplayEngine::Router(BookRouter::new()))
        }
        "reject_unknown" => {
            require_len(line, parts, 3)?;
            let keys = parse_book_keys(line, parts[2])?;
            Ok(ReplayEngine::Router(BookRouter::with_registry(
                InstrumentRegistry::allow_list(keys),
            )))
        }
        other => Err(ReplayError::new(
            line,
            format!("unknown registry mode {other}"),
        )),
    }
}

fn parse_book_keys(line: usize, raw: &str) -> Result<Vec<BookKey>, ReplayError> {
    if raw == "-" {
        return Ok(Vec::new());
    }
    raw.split(',')
        .map(|entry| {
            let mut fields = entry.split(':');
            let venue_raw = fields
                .next()
                .ok_or_else(|| ReplayError::new(line, "missing book key venue"))?;
            let instrument_raw = fields
                .next()
                .ok_or_else(|| ReplayError::new(line, "missing book key instrument"))?;
            if fields.next().is_some() {
                return Err(ReplayError::new(line, "too many book key fields"));
            }
            Ok(BookKey::new(
                venue(line, venue_raw)?,
                instrument(line, instrument_raw)?,
            ))
        })
        .collect()
}

fn parse_levels(line: usize, raw: &str, side: Side) -> Result<Vec<Level>, ReplayError> {
    if raw == "-" {
        return Ok(Vec::new());
    }
    raw.split(',')
        .map(|entry| {
            let mut fields = entry.split(':');
            let price_raw = fields
                .next()
                .ok_or_else(|| ReplayError::new(line, "missing level price"))?;
            let qty_raw = fields
                .next()
                .ok_or_else(|| ReplayError::new(line, "missing level quantity"))?;
            if fields.next().is_some() {
                return Err(ReplayError::new(line, "too many level fields"));
            }
            Ok(Level::new(
                side,
                price(line, price_raw)?,
                quantity(line, qty_raw)?,
            ))
        })
        .collect()
}

fn parse_updates(line: usize, raw: &str) -> Result<Vec<LevelUpdate>, ReplayError> {
    raw.split(',')
        .map(|entry| {
            let mut fields = entry.split(':');
            let side = match fields
                .next()
                .ok_or_else(|| ReplayError::new(line, "missing update side"))?
            {
                "B" => Side::Bid,
                "A" => Side::Ask,
                other => return Err(ReplayError::new(line, format!("unknown side {other}"))),
            };
            let price_raw = fields
                .next()
                .ok_or_else(|| ReplayError::new(line, "missing update price"))?;
            let qty_raw = fields
                .next()
                .ok_or_else(|| ReplayError::new(line, "missing update quantity"))?;
            if fields.next().is_some() {
                return Err(ReplayError::new(line, "too many update fields"));
            }
            Ok(LevelUpdate {
                side,
                price: price(line, price_raw)?,
                quantity: quantity(line, qty_raw)?,
            })
        })
        .collect()
}

fn require_len(line: usize, parts: &[&str], expected: usize) -> Result<(), ReplayError> {
    if parts.len() != expected {
        return Err(ReplayError::new(
            line,
            format!("expected {expected} fields, got {}", parts.len()),
        ));
    }
    Ok(())
}

fn venue(line: usize, raw: &str) -> Result<VenueId, ReplayError> {
    VenueId::new(parse_u16(line, raw)?).map_err(|err| ReplayError::new(line, format!("{err:?}")))
}

fn instrument(line: usize, raw: &str) -> Result<InstrumentId, ReplayError> {
    InstrumentId::new(parse_u32(line, raw)?)
        .map_err(|err| ReplayError::new(line, format!("{err:?}")))
}

fn parse_optional_venue(line: usize, raw: &str) -> Result<Option<VenueId>, ReplayError> {
    if raw == "none" {
        return Ok(None);
    }
    Ok(Some(venue(line, raw)?))
}

fn parse_optional_instrument(line: usize, raw: &str) -> Result<Option<InstrumentId>, ReplayError> {
    if raw == "none" {
        return Ok(None);
    }
    Ok(Some(instrument(line, raw)?))
}

fn sequence(line: usize, raw: &str) -> Result<Sequence, ReplayError> {
    Sequence::new(parse_u64(line, raw)?).map_err(|err| ReplayError::new(line, format!("{err:?}")))
}

fn parse_optional_sequence(line: usize, raw: &str) -> Result<Option<Sequence>, ReplayError> {
    if raw == "none" {
        return Ok(None);
    }
    Ok(Some(sequence(line, raw)?))
}

fn timestamp(line: usize, raw: &str) -> Result<TimestampNs, ReplayError> {
    TimestampNs::new(parse_u64(line, raw)?)
        .map_err(|err| ReplayError::new(line, format!("{err:?}")))
}

fn price(line: usize, raw: &str) -> Result<Price, ReplayError> {
    Price::new(parse_i64(line, raw)?).map_err(|err| ReplayError::new(line, format!("{err:?}")))
}

fn quantity(line: usize, raw: &str) -> Result<Quantity, ReplayError> {
    Quantity::new(parse_i64(line, raw)?).map_err(|err| ReplayError::new(line, format!("{err:?}")))
}

fn parse_i64(line: usize, raw: &str) -> Result<i64, ReplayError> {
    raw.parse::<i64>()
        .map_err(|err| ReplayError::new(line, format!("invalid i64 {raw}: {err}")))
}

fn parse_u64(line: usize, raw: &str) -> Result<u64, ReplayError> {
    raw.parse::<u64>()
        .map_err(|err| ReplayError::new(line, format!("invalid u64 {raw}: {err}")))
}

fn parse_u32(line: usize, raw: &str) -> Result<u32, ReplayError> {
    raw.parse::<u32>()
        .map_err(|err| ReplayError::new(line, format!("invalid u32 {raw}: {err}")))
}

fn parse_u16(line: usize, raw: &str) -> Result<u16, ReplayError> {
    raw.parse::<u16>()
        .map_err(|err| ReplayError::new(line, format!("invalid u16 {raw}: {err}")))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn runs_basic_snapshot_then_delta_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md/basic_snapshot_then_delta.events"
        ))
        .unwrap();
    }

    #[test]
    fn runs_remove_level_fixture() {
        run_fixture(include_str!("../../../fixtures/md/remove_level.events")).unwrap();
    }

    #[test]
    fn runs_sequence_gap_fixture() {
        run_fixture(include_str!("../../../fixtures/md/sequence_gap.events")).unwrap();
    }

    #[test]
    fn runs_snapshot_recovers_gap_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md/snapshot_recovers_gap.events"
        ))
        .unwrap();
    }

    #[test]
    fn runs_wrong_identity_rejected_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md/wrong_identity_rejected.events"
        ))
        .unwrap();
    }

    #[test]
    fn runs_multi_instrument_interleaved_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md/multi_instrument_interleaved.events"
        ))
        .unwrap();
    }

    #[test]
    fn runs_multi_instrument_gap_isolated_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md/multi_instrument_gap_isolated.events"
        ))
        .unwrap();
    }

    #[test]
    fn runs_instrument_registry_allows_configured_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md/instrument_registry_allows_configured.events"
        ))
        .unwrap();
    }

    #[test]
    fn runs_instrument_registry_rejects_unknown_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md/instrument_registry_rejects_unknown.events"
        ))
        .unwrap();
    }
}
