//! Venue-specific market-data sequence policies.
//!
//! This crate owns raw Binance/Bitget L2 sequence continuity checks before
//! events are normalized into `bedrock-rs-md` snapshots or deltas. It does not
//! own WebSocket clients, REST snapshot fetchers, JSON parsing, book
//! reconstruction, transport, pricing, OMS, or monitoring.

mod pipeline;

use bedrock_rs_common::{
    InstrumentId, Level, Price, Quantity, Sequence, Side, TimestampNs, ValueError, VenueId, SCALE,
};
use bedrock_rs_md::{BookDelta, BookSnapshot, LevelUpdate};
pub use pipeline::*;
use serde_json::Value;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VenueParseError {
    InvalidJson(String),
    MissingField(&'static str),
    InvalidField(&'static str),
    UnexpectedEventType(String),
    UnexpectedAction(String),
    UnexpectedChannel(String),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VenueNormalizeError {
    EmptyDecimal,
    InvalidDecimal,
    NegativeDecimal,
    TooManyFractionalDigits,
    DecimalOverflow,
    TimestampOverflow,
    InvalidPrice(ValueError),
    InvalidQuantity(ValueError),
    InvalidSequence(ValueError),
    InvalidTimestamp(ValueError),
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RawLevel {
    pub price: String,
    pub quantity: String,
}

impl RawLevel {
    pub fn new(price: impl Into<String>, quantity: impl Into<String>) -> Self {
        Self {
            price: price.into(),
            quantity: quantity.into(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BinanceSpotDepthUpdate {
    pub symbol: String,
    pub event_time_ms: u64,
    pub first_update_id: u64,
    pub final_update_id: u64,
    pub bids: Vec<RawLevel>,
    pub asks: Vec<RawLevel>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BinanceFuturesDepthUpdate {
    pub symbol: String,
    pub event_time_ms: u64,
    pub transaction_time_ms: u64,
    pub first_update_id: u64,
    pub final_update_id: u64,
    pub previous_final_update_id: u64,
    pub bids: Vec<RawLevel>,
    pub asks: Vec<RawLevel>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BinanceSpotDepthSnapshot {
    pub last_update_id: u64,
    pub bids: Vec<RawLevel>,
    pub asks: Vec<RawLevel>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BinanceFuturesDepthSnapshot {
    pub last_update_id: u64,
    pub message_output_time_ms: u64,
    pub transaction_time_ms: u64,
    pub bids: Vec<RawLevel>,
    pub asks: Vec<RawLevel>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BitgetDepthAction {
    Snapshot,
    Update,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BitgetDepthMessage {
    pub topic: String,
    pub symbol: String,
    pub channel_kind: BitgetDepthChannelKind,
    pub action: BitgetDepthAction,
    pub sequence: u64,
    pub previous_sequence: u64,
    pub timestamp_ms: u64,
    pub max_depth: Option<u64>,
    pub bids: Vec<RawLevel>,
    pub asks: Vec<RawLevel>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum NormalizedDepthEvent {
    Snapshot(BookSnapshot),
    Delta(BookDelta),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SequenceDecision {
    Apply,
    IgnoreStale,
    Gap(SequenceGapReason),
    ResetRequired(SequenceGapReason),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SequenceGapReason {
    NeedsSnapshot,
    SnapshotNotBridged,
    RangeGap,
    InvalidRange,
    PreviousFinalUpdateMismatch,
    PreviousSequenceMismatch,
    VenueReset,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct BinanceSpotSequencePolicy {
    local_update_id: Option<u64>,
}

impl BinanceSpotSequencePolicy {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn snapshot_too_old(last_update_id: u64, first_buffered_update_id: u64) -> bool {
        last_update_id < first_buffered_update_id
    }

    pub fn bridges_snapshot(
        last_update_id: u64,
        first_update_id: u64,
        final_update_id: u64,
    ) -> bool {
        first_update_id <= last_update_id && last_update_id <= final_update_id
    }

    pub fn set_snapshot(&mut self, last_update_id: u64) {
        self.local_update_id = Some(last_update_id);
    }

    pub fn local_update_id(&self) -> Option<u64> {
        self.local_update_id
    }

    pub fn apply_event(&mut self, first_update_id: u64, final_update_id: u64) -> SequenceDecision {
        if final_update_id < first_update_id {
            return SequenceDecision::Gap(SequenceGapReason::InvalidRange);
        }

        let Some(local_update_id) = self.local_update_id else {
            return SequenceDecision::Gap(SequenceGapReason::NeedsSnapshot);
        };

        if final_update_id <= local_update_id {
            return SequenceDecision::IgnoreStale;
        }

        if first_update_id > local_update_id.saturating_add(1) {
            return SequenceDecision::Gap(SequenceGapReason::RangeGap);
        }

        self.local_update_id = Some(final_update_id);
        SequenceDecision::Apply
    }
}

#[derive(Debug, Clone, Copy, Default)]
pub struct BinanceFuturesSequencePolicy {
    previous_u: Option<u64>,
}

impl BinanceFuturesSequencePolicy {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn bridges_snapshot(
        last_update_id: u64,
        first_update_id: u64,
        final_update_id: u64,
    ) -> bool {
        first_update_id <= last_update_id && final_update_id >= last_update_id
    }

    pub fn previous_u(&self) -> Option<u64> {
        self.previous_u
    }

    pub fn apply_first_event_after_snapshot(
        &mut self,
        last_update_id: u64,
        first_update_id: u64,
        final_update_id: u64,
    ) -> SequenceDecision {
        if final_update_id < first_update_id {
            return SequenceDecision::Gap(SequenceGapReason::InvalidRange);
        }

        if !Self::bridges_snapshot(last_update_id, first_update_id, final_update_id) {
            return SequenceDecision::Gap(SequenceGapReason::SnapshotNotBridged);
        }

        self.previous_u = Some(final_update_id);
        SequenceDecision::Apply
    }

    pub fn apply_event(
        &mut self,
        first_update_id: u64,
        final_update_id: u64,
        previous_final_update_id: u64,
    ) -> SequenceDecision {
        if final_update_id < first_update_id {
            return SequenceDecision::Gap(SequenceGapReason::InvalidRange);
        }

        let Some(previous_u) = self.previous_u else {
            return SequenceDecision::Gap(SequenceGapReason::NeedsSnapshot);
        };

        if final_update_id <= previous_u {
            return SequenceDecision::IgnoreStale;
        }

        if previous_final_update_id != previous_u {
            return SequenceDecision::Gap(SequenceGapReason::PreviousFinalUpdateMismatch);
        }

        self.previous_u = Some(final_update_id);
        SequenceDecision::Apply
    }
}

#[derive(Debug, Clone, Copy, Default)]
pub struct BitgetBooksSequencePolicy {
    local_seq: Option<u64>,
}

impl BitgetBooksSequencePolicy {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn bridges_snapshot(snapshot_seq: u64, previous_seq: u64, current_seq: u64) -> bool {
        previous_seq <= snapshot_seq && snapshot_seq <= current_seq
    }

    pub fn apply_snapshot(&mut self, sequence: u64) {
        self.local_seq = Some(sequence);
    }

    pub fn local_seq(&self) -> Option<u64> {
        self.local_seq
    }

    pub fn apply_update(&mut self, sequence: u64, previous_sequence: u64) -> SequenceDecision {
        if previous_sequence == 0 {
            self.local_seq = None;
            return SequenceDecision::ResetRequired(SequenceGapReason::VenueReset);
        }

        let Some(local_seq) = self.local_seq else {
            return SequenceDecision::Gap(SequenceGapReason::NeedsSnapshot);
        };

        if previous_sequence != local_seq {
            return SequenceDecision::Gap(SequenceGapReason::PreviousSequenceMismatch);
        }

        if sequence <= previous_sequence {
            return SequenceDecision::Gap(SequenceGapReason::InvalidRange);
        }

        self.local_seq = Some(sequence);
        SequenceDecision::Apply
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BitgetDepthChannelKind {
    Incremental,
    SnapshotOnly,
}

impl BitgetDepthChannelKind {
    pub fn from_topic(topic: &str) -> Option<Self> {
        match topic {
            "books" => Some(Self::Incremental),
            "books1" | "books5" | "books50" => Some(Self::SnapshotOnly),
            _ => None,
        }
    }
}

pub fn parse_binance_spot_depth_update(
    raw: &str,
) -> Result<BinanceSpotDepthUpdate, VenueParseError> {
    let root = parse_json(raw)?;
    let payload = unwrap_combined_payload(&root);
    require_event_type(payload, "depthUpdate")?;

    Ok(BinanceSpotDepthUpdate {
        symbol: required_string(payload, "s")?.to_owned(),
        event_time_ms: required_u64(payload, "E")?,
        first_update_id: required_u64(payload, "U")?,
        final_update_id: required_u64(payload, "u")?,
        bids: required_levels(payload, "b")?,
        asks: required_levels(payload, "a")?,
    })
}

pub fn parse_binance_futures_depth_update(
    raw: &str,
) -> Result<BinanceFuturesDepthUpdate, VenueParseError> {
    let root = parse_json(raw)?;
    let payload = unwrap_combined_payload(&root);
    require_event_type(payload, "depthUpdate")?;

    Ok(BinanceFuturesDepthUpdate {
        symbol: required_string(payload, "s")?.to_owned(),
        event_time_ms: required_u64(payload, "E")?,
        transaction_time_ms: required_u64(payload, "T")?,
        first_update_id: required_u64(payload, "U")?,
        final_update_id: required_u64(payload, "u")?,
        previous_final_update_id: required_u64(payload, "pu")?,
        bids: required_levels(payload, "b")?,
        asks: required_levels(payload, "a")?,
    })
}

pub fn parse_binance_spot_depth_snapshot(
    raw: &str,
) -> Result<BinanceSpotDepthSnapshot, VenueParseError> {
    let root = parse_json(raw)?;

    Ok(BinanceSpotDepthSnapshot {
        last_update_id: required_u64(&root, "lastUpdateId")?,
        bids: required_levels(&root, "bids")?,
        asks: required_levels(&root, "asks")?,
    })
}

pub fn parse_binance_futures_depth_snapshot(
    raw: &str,
) -> Result<BinanceFuturesDepthSnapshot, VenueParseError> {
    let root = parse_json(raw)?;

    Ok(BinanceFuturesDepthSnapshot {
        last_update_id: required_u64(&root, "lastUpdateId")?,
        message_output_time_ms: required_u64(&root, "E")?,
        transaction_time_ms: required_u64(&root, "T")?,
        bids: required_levels(&root, "bids")?,
        asks: required_levels(&root, "asks")?,
    })
}

pub fn parse_bitget_depth_message(raw: &str) -> Result<BitgetDepthMessage, VenueParseError> {
    let root = parse_json(raw)?;
    let arg = root
        .get("arg")
        .ok_or(VenueParseError::MissingField("arg"))?;
    arg.as_object()
        .ok_or(VenueParseError::InvalidField("arg"))?;
    let topic = required_string(arg, "topic")?.to_owned();
    let symbol = required_string(arg, "symbol")?.to_owned();
    let channel_kind = BitgetDepthChannelKind::from_topic(&topic)
        .ok_or_else(|| VenueParseError::UnexpectedChannel(topic.clone()))?;
    let action = match required_string(&root, "action")? {
        "snapshot" => BitgetDepthAction::Snapshot,
        "update" => BitgetDepthAction::Update,
        other => return Err(VenueParseError::UnexpectedAction(other.to_owned())),
    };

    let data = required_array(&root, "data")?;
    let first_value = data
        .first()
        .ok_or(VenueParseError::MissingField("data[0]"))?;
    let first = first_value
        .as_object()
        .ok_or(VenueParseError::InvalidField("data[0]"))?;
    let timestamp_ms = optional_u64(first, "ts")?.unwrap_or(required_u64(&root, "ts")?);

    Ok(BitgetDepthMessage {
        topic,
        symbol,
        channel_kind,
        action,
        sequence: required_u64(first_value, "seq")?,
        previous_sequence: required_u64(first_value, "pseq")?,
        timestamp_ms,
        max_depth: optional_u64(first, "maxDepth")?,
        bids: required_levels(first_value, "b")?,
        asks: required_levels(first_value, "a")?,
    })
}

pub fn normalize_binance_spot_snapshot(
    snapshot: BinanceSpotDepthSnapshot,
    venue_id: VenueId,
    instrument_id: InstrumentId,
    timestamp_ns: TimestampNs,
) -> Result<BookSnapshot, VenueNormalizeError> {
    Ok(BookSnapshot {
        venue_id,
        instrument_id,
        sequence: sequence(snapshot.last_update_id)?,
        timestamp_ns,
        bids: normalize_levels(&snapshot.bids, Side::Bid)?,
        asks: normalize_levels(&snapshot.asks, Side::Ask)?,
    })
}

pub fn normalize_binance_futures_snapshot(
    snapshot: BinanceFuturesDepthSnapshot,
    venue_id: VenueId,
    instrument_id: InstrumentId,
) -> Result<BookSnapshot, VenueNormalizeError> {
    Ok(BookSnapshot {
        venue_id,
        instrument_id,
        sequence: sequence(snapshot.last_update_id)?,
        timestamp_ns: timestamp_ms_to_ns(snapshot.message_output_time_ms)?,
        bids: normalize_levels(&snapshot.bids, Side::Bid)?,
        asks: normalize_levels(&snapshot.asks, Side::Ask)?,
    })
}

pub fn normalize_binance_spot_delta(
    update: BinanceSpotDepthUpdate,
    venue_id: VenueId,
    instrument_id: InstrumentId,
) -> Result<BookDelta, VenueNormalizeError> {
    Ok(BookDelta {
        venue_id,
        instrument_id,
        sequence: sequence(update.final_update_id)?,
        timestamp_ns: timestamp_ms_to_ns(update.event_time_ms)?,
        updates: normalize_updates(&update.bids, &update.asks)?,
    })
}

pub fn normalize_binance_futures_delta(
    update: BinanceFuturesDepthUpdate,
    venue_id: VenueId,
    instrument_id: InstrumentId,
) -> Result<BookDelta, VenueNormalizeError> {
    Ok(BookDelta {
        venue_id,
        instrument_id,
        sequence: sequence(update.final_update_id)?,
        timestamp_ns: timestamp_ms_to_ns(update.event_time_ms)?,
        updates: normalize_updates(&update.bids, &update.asks)?,
    })
}

pub fn normalize_bitget_depth_message(
    message: BitgetDepthMessage,
    venue_id: VenueId,
    instrument_id: InstrumentId,
) -> Result<NormalizedDepthEvent, VenueNormalizeError> {
    match message.action {
        BitgetDepthAction::Snapshot => Ok(NormalizedDepthEvent::Snapshot(BookSnapshot {
            venue_id,
            instrument_id,
            sequence: sequence(message.sequence)?,
            timestamp_ns: timestamp_ms_to_ns(message.timestamp_ms)?,
            bids: normalize_levels(&message.bids, Side::Bid)?,
            asks: normalize_levels(&message.asks, Side::Ask)?,
        })),
        BitgetDepthAction::Update => Ok(NormalizedDepthEvent::Delta(BookDelta {
            venue_id,
            instrument_id,
            sequence: sequence(message.sequence)?,
            timestamp_ns: timestamp_ms_to_ns(message.timestamp_ms)?,
            updates: normalize_updates(&message.bids, &message.asks)?,
        })),
    }
}

pub fn decimal_to_scaled_i64(raw: &str) -> Result<i64, VenueNormalizeError> {
    if raw.is_empty() {
        return Err(VenueNormalizeError::EmptyDecimal);
    }
    if raw.starts_with('-') {
        return Err(VenueNormalizeError::NegativeDecimal);
    }

    let mut parts = raw.split('.');
    let whole_raw = parts.next().ok_or(VenueNormalizeError::InvalidDecimal)?;
    let fractional_raw = parts.next().unwrap_or("");
    if parts.next().is_some() || whole_raw.is_empty() {
        return Err(VenueNormalizeError::InvalidDecimal);
    }
    if fractional_raw.len() > 8 {
        return Err(VenueNormalizeError::TooManyFractionalDigits);
    }
    if !whole_raw.chars().all(|ch| ch.is_ascii_digit())
        || !fractional_raw.chars().all(|ch| ch.is_ascii_digit())
    {
        return Err(VenueNormalizeError::InvalidDecimal);
    }

    let whole = whole_raw
        .parse::<i64>()
        .map_err(|_| VenueNormalizeError::DecimalOverflow)?;
    let mut fractional = 0_i64;
    for ch in fractional_raw.bytes() {
        fractional = fractional
            .checked_mul(10)
            .and_then(|value| value.checked_add((ch - b'0') as i64))
            .ok_or(VenueNormalizeError::DecimalOverflow)?;
    }
    for _ in fractional_raw.len()..8 {
        fractional = fractional
            .checked_mul(10)
            .ok_or(VenueNormalizeError::DecimalOverflow)?;
    }

    whole
        .checked_mul(SCALE)
        .and_then(|value| value.checked_add(fractional))
        .ok_or(VenueNormalizeError::DecimalOverflow)
}

pub fn timestamp_ms_to_ns(raw_ms: u64) -> Result<TimestampNs, VenueNormalizeError> {
    let raw_ns = raw_ms
        .checked_mul(1_000_000)
        .ok_or(VenueNormalizeError::TimestampOverflow)?;
    TimestampNs::new(raw_ns).map_err(VenueNormalizeError::InvalidTimestamp)
}

fn normalize_updates(
    bids: &[RawLevel],
    asks: &[RawLevel],
) -> Result<Vec<LevelUpdate>, VenueNormalizeError> {
    let mut updates = Vec::with_capacity(bids.len() + asks.len());
    for level in bids {
        updates.push(normalize_update(level, Side::Bid)?);
    }
    for level in asks {
        updates.push(normalize_update(level, Side::Ask)?);
    }
    Ok(updates)
}

fn normalize_update(level: &RawLevel, side: Side) -> Result<LevelUpdate, VenueNormalizeError> {
    Ok(LevelUpdate {
        side,
        price: price(&level.price)?,
        quantity: quantity(&level.quantity)?,
    })
}

fn normalize_levels(levels: &[RawLevel], side: Side) -> Result<Vec<Level>, VenueNormalizeError> {
    levels
        .iter()
        .map(|level| {
            Ok(Level::new(
                side,
                price(&level.price)?,
                quantity(&level.quantity)?,
            ))
        })
        .collect()
}

fn price(raw: &str) -> Result<Price, VenueNormalizeError> {
    Price::new(decimal_to_scaled_i64(raw)?).map_err(VenueNormalizeError::InvalidPrice)
}

fn quantity(raw: &str) -> Result<Quantity, VenueNormalizeError> {
    Quantity::new(decimal_to_scaled_i64(raw)?).map_err(VenueNormalizeError::InvalidQuantity)
}

fn sequence(raw: u64) -> Result<Sequence, VenueNormalizeError> {
    Sequence::new(raw).map_err(VenueNormalizeError::InvalidSequence)
}

fn parse_json(raw: &str) -> Result<Value, VenueParseError> {
    serde_json::from_str(raw).map_err(|err| VenueParseError::InvalidJson(err.to_string()))
}

fn unwrap_combined_payload(root: &Value) -> &Value {
    root.get("data").unwrap_or(root)
}

fn require_event_type(payload: &Value, expected: &'static str) -> Result<(), VenueParseError> {
    let actual = required_string(payload, "e")?;
    if actual != expected {
        return Err(VenueParseError::UnexpectedEventType(actual.to_owned()));
    }
    Ok(())
}

fn required_array<'a>(
    object: &'a Value,
    field: &'static str,
) -> Result<&'a Vec<Value>, VenueParseError> {
    object
        .get(field)
        .ok_or(VenueParseError::MissingField(field))?
        .as_array()
        .ok_or(VenueParseError::InvalidField(field))
}

fn required_string<'a>(object: &'a Value, field: &'static str) -> Result<&'a str, VenueParseError> {
    object
        .get(field)
        .ok_or(VenueParseError::MissingField(field))?
        .as_str()
        .ok_or(VenueParseError::InvalidField(field))
}

fn required_u64(object: &Value, field: &'static str) -> Result<u64, VenueParseError> {
    object
        .get(field)
        .ok_or(VenueParseError::MissingField(field))
        .and_then(|value| parse_u64_value(value, field))
}

fn optional_u64(
    object: &serde_json::Map<String, Value>,
    field: &'static str,
) -> Result<Option<u64>, VenueParseError> {
    object
        .get(field)
        .map(|value| parse_u64_value(value, field))
        .transpose()
}

fn parse_u64_value(value: &Value, field: &'static str) -> Result<u64, VenueParseError> {
    if let Some(raw) = value.as_u64() {
        return Ok(raw);
    }
    if let Some(raw) = value.as_str() {
        return raw
            .parse::<u64>()
            .map_err(|_| VenueParseError::InvalidField(field));
    }
    Err(VenueParseError::InvalidField(field))
}

fn required_levels(object: &Value, field: &'static str) -> Result<Vec<RawLevel>, VenueParseError> {
    required_array(object, field)?
        .iter()
        .map(|entry| parse_level(entry, field))
        .collect()
}

fn parse_level(entry: &Value, field: &'static str) -> Result<RawLevel, VenueParseError> {
    let fields = entry
        .as_array()
        .ok_or(VenueParseError::InvalidField(field))?;
    let price = fields
        .first()
        .and_then(Value::as_str)
        .ok_or(VenueParseError::InvalidField(field))?;
    let quantity = fields
        .get(1)
        .and_then(Value::as_str)
        .ok_or(VenueParseError::InvalidField(field))?;
    Ok(RawLevel::new(price, quantity))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn venue(raw: u16) -> VenueId {
        VenueId::new(raw).unwrap()
    }

    fn instrument(raw: u32) -> InstrumentId {
        InstrumentId::new(raw).unwrap()
    }

    fn sequence(raw: u64) -> Sequence {
        Sequence::new(raw).unwrap()
    }

    fn price(raw: i64) -> Price {
        Price::new(raw).unwrap()
    }

    fn quantity(raw: i64) -> Quantity {
        Quantity::new(raw).unwrap()
    }

    #[test]
    fn binance_spot_detects_snapshot_too_old() {
        assert!(BinanceSpotSequencePolicy::snapshot_too_old(99, 100));
        assert!(!BinanceSpotSequencePolicy::snapshot_too_old(100, 100));
    }

    #[test]
    fn binance_spot_bridges_snapshot_and_applies_continuous_ranges() {
        assert!(BinanceSpotSequencePolicy::bridges_snapshot(100, 98, 101));

        let mut policy = BinanceSpotSequencePolicy::new();
        policy.set_snapshot(100);

        assert_eq!(policy.apply_event(98, 101), SequenceDecision::Apply);
        assert_eq!(policy.local_update_id(), Some(101));
        assert_eq!(policy.apply_event(102, 105), SequenceDecision::Apply);
        assert_eq!(policy.local_update_id(), Some(105));
    }

    #[test]
    fn binance_spot_ignores_stale_or_duplicate_ranges() {
        let mut policy = BinanceSpotSequencePolicy::new();
        policy.set_snapshot(100);

        assert_eq!(policy.apply_event(90, 100), SequenceDecision::IgnoreStale);
        assert_eq!(policy.local_update_id(), Some(100));
    }

    #[test]
    fn binance_spot_detects_range_gap_without_advancing() {
        let mut policy = BinanceSpotSequencePolicy::new();
        policy.set_snapshot(100);

        assert_eq!(
            policy.apply_event(102, 105),
            SequenceDecision::Gap(SequenceGapReason::RangeGap)
        );
        assert_eq!(policy.local_update_id(), Some(100));
    }

    #[test]
    fn binance_futures_bridges_first_event_after_snapshot() {
        assert!(BinanceFuturesSequencePolicy::bridges_snapshot(100, 99, 101));

        let mut policy = BinanceFuturesSequencePolicy::new();
        assert_eq!(
            policy.apply_first_event_after_snapshot(100, 99, 101),
            SequenceDecision::Apply
        );
        assert_eq!(policy.previous_u(), Some(101));
    }

    #[test]
    fn binance_futures_requires_pu_to_match_previous_u() {
        let mut policy = BinanceFuturesSequencePolicy::new();
        assert_eq!(
            policy.apply_first_event_after_snapshot(100, 99, 101),
            SequenceDecision::Apply
        );

        assert_eq!(policy.apply_event(102, 105, 101), SequenceDecision::Apply);
        assert_eq!(policy.previous_u(), Some(105));
    }

    #[test]
    fn binance_futures_detects_pu_mismatch_without_advancing() {
        let mut policy = BinanceFuturesSequencePolicy::new();
        assert_eq!(
            policy.apply_first_event_after_snapshot(100, 99, 101),
            SequenceDecision::Apply
        );

        assert_eq!(
            policy.apply_event(102, 105, 100),
            SequenceDecision::Gap(SequenceGapReason::PreviousFinalUpdateMismatch)
        );
        assert_eq!(policy.previous_u(), Some(101));
    }

    #[test]
    fn bitget_books_applies_snapshot_then_bridged_update() {
        let mut policy = BitgetBooksSequencePolicy::new();
        policy.apply_snapshot(100);

        assert_eq!(policy.local_seq(), Some(100));
        assert!(BitgetBooksSequencePolicy::bridges_snapshot(100, 100, 101));
        assert_eq!(policy.apply_update(101, 100), SequenceDecision::Apply);
        assert_eq!(policy.local_seq(), Some(101));
    }

    #[test]
    fn bitget_books_detects_pseq_mismatch_without_advancing() {
        let mut policy = BitgetBooksSequencePolicy::new();
        policy.apply_snapshot(100);

        assert_eq!(
            policy.apply_update(102, 99),
            SequenceDecision::Gap(SequenceGapReason::PreviousSequenceMismatch)
        );
        assert_eq!(policy.local_seq(), Some(100));
    }

    #[test]
    fn bitget_books_detects_pseq_zero_reset_and_clears_state() {
        let mut policy = BitgetBooksSequencePolicy::new();
        policy.apply_snapshot(100);

        assert_eq!(
            policy.apply_update(101, 0),
            SequenceDecision::ResetRequired(SequenceGapReason::VenueReset)
        );
        assert_eq!(policy.local_seq(), None);
    }

    #[test]
    fn bitget_depth_channel_classifies_snapshot_only_topics() {
        assert_eq!(
            BitgetDepthChannelKind::from_topic("books"),
            Some(BitgetDepthChannelKind::Incremental)
        );
        assert_eq!(
            BitgetDepthChannelKind::from_topic("books5"),
            Some(BitgetDepthChannelKind::SnapshotOnly)
        );
        assert_eq!(BitgetDepthChannelKind::from_topic("ticker"), None);
    }

    #[test]
    fn runs_binance_spot_snapshot_bridge_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md-venue/binance_spot_snapshot_bridge.events"
        ))
        .unwrap();
    }

    #[test]
    fn runs_binance_spot_stale_ignored_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md-venue/binance_spot_stale_ignored.events"
        ))
        .unwrap();
    }

    #[test]
    fn runs_binance_spot_range_gap_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md-venue/binance_spot_range_gap.events"
        ))
        .unwrap();
    }

    #[test]
    fn runs_binance_futures_pu_continuity_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md-venue/binance_futures_pu_continuity.events"
        ))
        .unwrap();
    }

    #[test]
    fn runs_binance_futures_pu_mismatch_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md-venue/binance_futures_pu_mismatch.events"
        ))
        .unwrap();
    }

    #[test]
    fn runs_bitget_books_seq_pseq_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md-venue/bitget_books_seq_pseq.events"
        ))
        .unwrap();
    }

    #[test]
    fn runs_bitget_books_pseq_mismatch_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md-venue/bitget_books_pseq_mismatch.events"
        ))
        .unwrap();
    }

    #[test]
    fn runs_bitget_books_pseq_zero_reset_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md-venue/bitget_books_pseq_zero_reset.events"
        ))
        .unwrap();
    }

    #[test]
    fn runs_bitget_channel_kind_fixture() {
        run_fixture(include_str!(
            "../../../fixtures/md-venue/bitget_channel_kind.events"
        ))
        .unwrap();
    }

    #[test]
    fn parses_binance_spot_depth_update_fixture() {
        let update = parse_binance_spot_depth_update(include_str!(
            "../../../fixtures/md-venue/json/binance_spot_depth_update.json"
        ))
        .unwrap();

        assert_eq!(update.symbol, "BNBBTC");
        assert_eq!(update.event_time_ms, 1_672_515_782_136);
        assert_eq!(update.first_update_id, 157);
        assert_eq!(update.final_update_id, 160);
        assert_eq!(update.bids.len(), 2);
        assert_eq!(update.bids[0], RawLevel::new("0.0024", "10"));
        assert_eq!(update.asks[0], RawLevel::new("0.0026", "100"));
    }

    #[test]
    fn parses_binance_spot_combined_depth_update_fixture() {
        let update = parse_binance_spot_depth_update(include_str!(
            "../../../fixtures/md-venue/json/binance_spot_combined_depth_update.json"
        ))
        .unwrap();

        assert_eq!(update.symbol, "BNBBTC");
        assert_eq!(update.first_update_id, 157);
        assert_eq!(update.final_update_id, 160);
        assert_eq!(update.bids, vec![RawLevel::new("0.0024", "10")]);
    }

    #[test]
    fn parses_binance_futures_depth_update_fixture() {
        let update = parse_binance_futures_depth_update(include_str!(
            "../../../fixtures/md-venue/json/binance_futures_depth_update.json"
        ))
        .unwrap();

        assert_eq!(update.symbol, "BTCUSDT");
        assert_eq!(update.event_time_ms, 1_672_515_782_136);
        assert_eq!(update.transaction_time_ms, 1_672_515_782_135);
        assert_eq!(update.first_update_id, 102);
        assert_eq!(update.final_update_id, 105);
        assert_eq!(update.previous_final_update_id, 101);
        assert_eq!(update.asks[1], RawLevel::new("43187.30", "0"));
    }

    #[test]
    fn parses_binance_spot_depth_snapshot_fixture() {
        let snapshot = parse_binance_spot_depth_snapshot(include_str!(
            "../../../fixtures/md-venue/json/binance_spot_depth_snapshot.json"
        ))
        .unwrap();

        assert_eq!(snapshot.last_update_id, 1_027_024);
        assert_eq!(snapshot.bids.len(), 2);
        assert_eq!(snapshot.asks.len(), 1);
        assert_eq!(
            snapshot.bids[0],
            RawLevel::new("4.00000000", "431.00000000")
        );
        assert_eq!(snapshot.asks[0], RawLevel::new("4.00000200", "12.00000000"));
    }

    #[test]
    fn parses_binance_futures_depth_snapshot_fixture() {
        let snapshot = parse_binance_futures_depth_snapshot(include_str!(
            "../../../fixtures/md-venue/json/binance_futures_depth_snapshot.json"
        ))
        .unwrap();

        assert_eq!(snapshot.last_update_id, 1_027_024);
        assert_eq!(snapshot.message_output_time_ms, 1_589_436_922_972);
        assert_eq!(snapshot.transaction_time_ms, 1_589_436_922_959);
        assert_eq!(snapshot.bids.len(), 1);
        assert_eq!(snapshot.asks.len(), 2);
    }

    #[test]
    fn parses_bitget_books_snapshot_fixture() {
        let message = parse_bitget_depth_message(include_str!(
            "../../../fixtures/md-venue/json/bitget_books_snapshot.json"
        ))
        .unwrap();

        assert_eq!(message.topic, "books");
        assert_eq!(message.symbol, "BTCUSDT");
        assert_eq!(message.channel_kind, BitgetDepthChannelKind::Incremental);
        assert_eq!(message.action, BitgetDepthAction::Snapshot);
        assert_eq!(message.sequence, 1_304_314_508_780_744_705);
        assert_eq!(message.previous_sequence, 0);
        assert_eq!(message.max_depth, Some(50));
        assert_eq!(message.timestamp_ms, 1_746_698_732_562);
        assert_eq!(message.bids[0], RawLevel::new("99756.6", "0.0128"));
    }

    #[test]
    fn parses_bitget_books_update_fixture() {
        let message = parse_bitget_depth_message(include_str!(
            "../../../fixtures/md-venue/json/bitget_books_update.json"
        ))
        .unwrap();

        assert_eq!(message.action, BitgetDepthAction::Update);
        assert_eq!(message.sequence, 1_304_314_508_780_744_706);
        assert_eq!(message.previous_sequence, 1_304_314_508_780_744_705);
        assert_eq!(message.asks[0], RawLevel::new("99756.8", "1.25"));
        assert_eq!(message.bids[0], RawLevel::new("99756.5", "0"));
    }

    #[test]
    fn parses_bitget_books5_as_snapshot_only_fixture() {
        let message = parse_bitget_depth_message(include_str!(
            "../../../fixtures/md-venue/json/bitget_books5_snapshot.json"
        ))
        .unwrap();

        assert_eq!(message.topic, "books5");
        assert_eq!(message.channel_kind, BitgetDepthChannelKind::SnapshotOnly);
        assert_eq!(message.action, BitgetDepthAction::Snapshot);
        assert_eq!(message.max_depth, None);
    }

    #[test]
    fn decimal_parser_is_exact_at_eight_decimal_places() {
        assert_eq!(decimal_to_scaled_i64("0.0024").unwrap(), 240_000);
        assert_eq!(
            decimal_to_scaled_i64("43187.10").unwrap(),
            4_318_710_000_000
        );
        assert_eq!(decimal_to_scaled_i64("23.9774").unwrap(), 2_397_740_000);
        assert_eq!(decimal_to_scaled_i64("0").unwrap(), 0);
    }

    #[test]
    fn decimal_parser_rejects_too_many_fractional_digits() {
        assert!(matches!(
            decimal_to_scaled_i64("1.123456789"),
            Err(VenueNormalizeError::TooManyFractionalDigits)
        ));
    }

    #[test]
    fn normalizes_binance_spot_depth_update_to_book_delta() {
        let raw = parse_binance_spot_depth_update(include_str!(
            "../../../fixtures/md-venue/json/binance_spot_depth_update.json"
        ))
        .unwrap();
        let delta = normalize_binance_spot_delta(raw, venue(1), instrument(1)).unwrap();

        assert_eq!(delta.venue_id, venue(1));
        assert_eq!(delta.instrument_id, instrument(1));
        assert_eq!(delta.sequence, sequence(160));
        assert_eq!(
            delta.timestamp_ns,
            timestamp_ms_to_ns(1_672_515_782_136).unwrap()
        );
        assert_eq!(delta.updates.len(), 3);
        assert_eq!(delta.updates[0].side, bedrock_rs_common::Side::Bid);
        assert_eq!(delta.updates[0].price, price(240_000));
        assert_eq!(delta.updates[0].quantity, quantity(1_000_000_000));
        assert_eq!(delta.updates[1].quantity, quantity(0));
        assert_eq!(delta.updates[2].side, bedrock_rs_common::Side::Ask);
    }

    #[test]
    fn normalizes_binance_futures_depth_update_to_book_delta() {
        let raw = parse_binance_futures_depth_update(include_str!(
            "../../../fixtures/md-venue/json/binance_futures_depth_update.json"
        ))
        .unwrap();
        let delta = normalize_binance_futures_delta(raw, venue(2), instrument(7)).unwrap();

        assert_eq!(delta.venue_id, venue(2));
        assert_eq!(delta.instrument_id, instrument(7));
        assert_eq!(delta.sequence, sequence(105));
        assert_eq!(
            delta.timestamp_ns,
            timestamp_ms_to_ns(1_672_515_782_136).unwrap()
        );
        assert_eq!(delta.updates.len(), 3);
        assert_eq!(delta.updates[0].price, price(4_318_710_000_000));
        assert_eq!(delta.updates[0].quantity, quantity(24_500_000));
        assert_eq!(delta.updates[2].quantity, quantity(0));
    }

    #[test]
    fn normalizes_binance_spot_depth_snapshot_to_book_snapshot() {
        let raw = parse_binance_spot_depth_snapshot(include_str!(
            "../../../fixtures/md-venue/json/binance_spot_depth_snapshot.json"
        ))
        .unwrap();
        let timestamp_ns = timestamp_ms_to_ns(1_589_436_922_980).unwrap();
        let snapshot =
            normalize_binance_spot_snapshot(raw, venue(1), instrument(1), timestamp_ns).unwrap();

        assert_eq!(snapshot.venue_id, venue(1));
        assert_eq!(snapshot.instrument_id, instrument(1));
        assert_eq!(snapshot.sequence, sequence(1_027_024));
        assert_eq!(snapshot.timestamp_ns, timestamp_ns);
        assert_eq!(snapshot.bids.len(), 2);
        assert_eq!(snapshot.asks.len(), 1);
        assert_eq!(snapshot.bids[0].side, bedrock_rs_common::Side::Bid);
        assert_eq!(snapshot.asks[0].side, bedrock_rs_common::Side::Ask);
        assert_eq!(snapshot.bids[0].price, price(400_000_000));
        assert_eq!(snapshot.bids[0].quantity, quantity(43_100_000_000));
    }

    #[test]
    fn normalizes_binance_futures_depth_snapshot_to_book_snapshot() {
        let raw = parse_binance_futures_depth_snapshot(include_str!(
            "../../../fixtures/md-venue/json/binance_futures_depth_snapshot.json"
        ))
        .unwrap();
        let snapshot = normalize_binance_futures_snapshot(raw, venue(2), instrument(7)).unwrap();

        assert_eq!(snapshot.venue_id, venue(2));
        assert_eq!(snapshot.instrument_id, instrument(7));
        assert_eq!(snapshot.sequence, sequence(1_027_024));
        assert_eq!(
            snapshot.timestamp_ns,
            timestamp_ms_to_ns(1_589_436_922_972).unwrap()
        );
        assert_eq!(snapshot.bids.len(), 1);
        assert_eq!(snapshot.asks.len(), 2);
        assert_eq!(snapshot.asks[1].price, price(401_000_000));
        assert_eq!(snapshot.asks[1].quantity, quantity(15_000_000_000));
    }

    #[test]
    fn normalizes_bitget_snapshot_to_book_snapshot() {
        let raw = parse_bitget_depth_message(include_str!(
            "../../../fixtures/md-venue/json/bitget_books_snapshot.json"
        ))
        .unwrap();
        let event = normalize_bitget_depth_message(raw, venue(3), instrument(9)).unwrap();

        let NormalizedDepthEvent::Snapshot(snapshot) = event else {
            panic!("expected snapshot");
        };
        assert_eq!(snapshot.venue_id, venue(3));
        assert_eq!(snapshot.instrument_id, instrument(9));
        assert_eq!(snapshot.sequence, sequence(1_304_314_508_780_744_705));
        assert_eq!(snapshot.bids.len(), 1);
        assert_eq!(snapshot.asks.len(), 1);
        assert_eq!(snapshot.bids[0].side, bedrock_rs_common::Side::Bid);
        assert_eq!(snapshot.asks[0].side, bedrock_rs_common::Side::Ask);
    }

    #[test]
    fn normalizes_bitget_update_to_book_delta() {
        let raw = parse_bitget_depth_message(include_str!(
            "../../../fixtures/md-venue/json/bitget_books_update.json"
        ))
        .unwrap();
        let event = normalize_bitget_depth_message(raw, venue(3), instrument(9)).unwrap();

        let NormalizedDepthEvent::Delta(delta) = event else {
            panic!("expected delta");
        };
        assert_eq!(delta.sequence, sequence(1_304_314_508_780_744_706));
        assert_eq!(delta.updates.len(), 2);
        assert_eq!(delta.updates[0].side, bedrock_rs_common::Side::Bid);
        assert_eq!(delta.updates[0].quantity, quantity(0));
        assert_eq!(delta.updates[1].side, bedrock_rs_common::Side::Ask);
    }

    #[derive(Debug, Clone, PartialEq, Eq)]
    struct FixtureError {
        line: usize,
        message: String,
    }

    impl FixtureError {
        fn new(line: usize, message: impl Into<String>) -> Self {
            Self {
                line,
                message: message.into(),
            }
        }
    }

    enum FixturePolicy {
        None,
        BinanceSpot(BinanceSpotSequencePolicy),
        BinanceFutures(BinanceFuturesSequencePolicy),
        BitgetBooks(BitgetBooksSequencePolicy),
    }

    fn run_fixture(contents: &str) -> Result<(), FixtureError> {
        let mut policy = FixturePolicy::None;
        let mut pending_decision: Option<SequenceDecision> = None;

        for (index, raw_line) in contents.lines().enumerate() {
            let line_number = index + 1;
            let line = raw_line.trim();
            if line.is_empty() || line.starts_with('#') {
                continue;
            }

            let parts: Vec<&str> = line.split('|').collect();
            match parts.first().copied() {
                Some("policy") => {
                    ensure_no_pending(line_number, &pending_decision)?;
                    require_len(line_number, &parts, 2)?;
                    policy = match parts[1] {
                        "binance_spot" => {
                            FixturePolicy::BinanceSpot(BinanceSpotSequencePolicy::new())
                        }
                        "binance_futures" => {
                            FixturePolicy::BinanceFutures(BinanceFuturesSequencePolicy::new())
                        }
                        "bitget_books" => {
                            FixturePolicy::BitgetBooks(BitgetBooksSequencePolicy::new())
                        }
                        other => {
                            return Err(FixtureError::new(
                                line_number,
                                format!("unknown policy {other}"),
                            ));
                        }
                    };
                }
                Some("spot_snapshot") => {
                    ensure_no_pending(line_number, &pending_decision)?;
                    require_len(line_number, &parts, 2)?;
                    match &mut policy {
                        FixturePolicy::BinanceSpot(policy) => {
                            policy.set_snapshot(parse_u64(line_number, parts[1])?);
                        }
                        _ => {
                            return Err(FixtureError::new(
                                line_number,
                                "expected binance_spot policy",
                            ))
                        }
                    }
                }
                Some("spot_event") => {
                    ensure_no_pending(line_number, &pending_decision)?;
                    require_len(line_number, &parts, 3)?;
                    pending_decision = match &mut policy {
                        FixturePolicy::BinanceSpot(policy) => Some(policy.apply_event(
                            parse_u64(line_number, parts[1])?,
                            parse_u64(line_number, parts[2])?,
                        )),
                        _ => {
                            return Err(FixtureError::new(
                                line_number,
                                "expected binance_spot policy",
                            ))
                        }
                    };
                }
                Some("futures_first") => {
                    ensure_no_pending(line_number, &pending_decision)?;
                    require_len(line_number, &parts, 4)?;
                    pending_decision = match &mut policy {
                        FixturePolicy::BinanceFutures(policy) => {
                            Some(policy.apply_first_event_after_snapshot(
                                parse_u64(line_number, parts[1])?,
                                parse_u64(line_number, parts[2])?,
                                parse_u64(line_number, parts[3])?,
                            ))
                        }
                        _ => {
                            return Err(FixtureError::new(
                                line_number,
                                "expected binance_futures policy",
                            ));
                        }
                    };
                }
                Some("futures_event") => {
                    ensure_no_pending(line_number, &pending_decision)?;
                    require_len(line_number, &parts, 4)?;
                    pending_decision = match &mut policy {
                        FixturePolicy::BinanceFutures(policy) => Some(policy.apply_event(
                            parse_u64(line_number, parts[1])?,
                            parse_u64(line_number, parts[2])?,
                            parse_u64(line_number, parts[3])?,
                        )),
                        _ => {
                            return Err(FixtureError::new(
                                line_number,
                                "expected binance_futures policy",
                            ));
                        }
                    };
                }
                Some("bitget_snapshot") => {
                    ensure_no_pending(line_number, &pending_decision)?;
                    require_len(line_number, &parts, 2)?;
                    match &mut policy {
                        FixturePolicy::BitgetBooks(policy) => {
                            policy.apply_snapshot(parse_u64(line_number, parts[1])?);
                        }
                        _ => {
                            return Err(FixtureError::new(
                                line_number,
                                "expected bitget_books policy",
                            ))
                        }
                    }
                }
                Some("bitget_update") => {
                    ensure_no_pending(line_number, &pending_decision)?;
                    require_len(line_number, &parts, 3)?;
                    pending_decision = match &mut policy {
                        FixturePolicy::BitgetBooks(policy) => Some(policy.apply_update(
                            parse_u64(line_number, parts[1])?,
                            parse_u64(line_number, parts[2])?,
                        )),
                        _ => {
                            return Err(FixtureError::new(
                                line_number,
                                "expected bitget_books policy",
                            ))
                        }
                    };
                }
                Some("expect") => {
                    require_len(line_number, &parts, 2)?;
                    let expected = match parts[1] {
                        "Apply" => SequenceDecision::Apply,
                        "IgnoreStale" => SequenceDecision::IgnoreStale,
                        other => {
                            return Err(FixtureError::new(
                                line_number,
                                format!("unknown decision {other}"),
                            ));
                        }
                    };
                    assert_pending_decision(line_number, &mut pending_decision, expected)?;
                }
                Some("expect_gap") => {
                    require_len(line_number, &parts, 2)?;
                    let expected = SequenceDecision::Gap(parse_gap_reason(line_number, parts[1])?);
                    assert_pending_decision(line_number, &mut pending_decision, expected)?;
                }
                Some("expect_reset") => {
                    require_len(line_number, &parts, 2)?;
                    let expected =
                        SequenceDecision::ResetRequired(parse_gap_reason(line_number, parts[1])?);
                    assert_pending_decision(line_number, &mut pending_decision, expected)?;
                }
                Some("expect_channel") => {
                    ensure_no_pending(line_number, &pending_decision)?;
                    require_len(line_number, &parts, 3)?;
                    let actual = BitgetDepthChannelKind::from_topic(parts[1]);
                    let expected = match parts[2] {
                        "Incremental" => Some(BitgetDepthChannelKind::Incremental),
                        "SnapshotOnly" => Some(BitgetDepthChannelKind::SnapshotOnly),
                        "None" => None,
                        other => {
                            return Err(FixtureError::new(
                                line_number,
                                format!("unknown channel kind {other}"),
                            ));
                        }
                    };
                    if actual != expected {
                        return Err(FixtureError::new(
                            line_number,
                            format!("expected channel kind {:?}, got {:?}", expected, actual),
                        ));
                    }
                }
                Some(other) => {
                    return Err(FixtureError::new(
                        line_number,
                        format!("unknown directive {other}"),
                    ));
                }
                None => {}
            }
        }

        ensure_no_pending(contents.lines().count(), &pending_decision)?;
        Ok(())
    }

    fn assert_pending_decision(
        line: usize,
        pending_decision: &mut Option<SequenceDecision>,
        expected: SequenceDecision,
    ) -> Result<(), FixtureError> {
        let Some(actual) = pending_decision.take() else {
            return Err(FixtureError::new(
                line,
                "expected decision but none was pending",
            ));
        };
        if actual != expected {
            return Err(FixtureError::new(
                line,
                format!("expected decision {:?}, got {:?}", expected, actual),
            ));
        }
        Ok(())
    }

    fn ensure_no_pending(
        line: usize,
        pending_decision: &Option<SequenceDecision>,
    ) -> Result<(), FixtureError> {
        if pending_decision.is_some() {
            return Err(FixtureError::new(
                line,
                "previous decision has no expectation",
            ));
        }
        Ok(())
    }

    fn parse_gap_reason(line: usize, raw: &str) -> Result<SequenceGapReason, FixtureError> {
        match raw {
            "NeedsSnapshot" => Ok(SequenceGapReason::NeedsSnapshot),
            "SnapshotNotBridged" => Ok(SequenceGapReason::SnapshotNotBridged),
            "RangeGap" => Ok(SequenceGapReason::RangeGap),
            "InvalidRange" => Ok(SequenceGapReason::InvalidRange),
            "PreviousFinalUpdateMismatch" => Ok(SequenceGapReason::PreviousFinalUpdateMismatch),
            "PreviousSequenceMismatch" => Ok(SequenceGapReason::PreviousSequenceMismatch),
            "VenueReset" => Ok(SequenceGapReason::VenueReset),
            other => Err(FixtureError::new(
                line,
                format!("unknown gap reason {other}"),
            )),
        }
    }

    fn require_len(line: usize, parts: &[&str], expected: usize) -> Result<(), FixtureError> {
        if parts.len() != expected {
            return Err(FixtureError::new(
                line,
                format!("expected {expected} fields, got {}", parts.len()),
            ));
        }
        Ok(())
    }

    fn parse_u64(line: usize, raw: &str) -> Result<u64, FixtureError> {
        raw.parse::<u64>()
            .map_err(|err| FixtureError::new(line, format!("invalid u64 {raw}: {err}")))
    }
}
