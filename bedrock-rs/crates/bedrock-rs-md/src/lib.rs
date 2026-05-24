//! Normalized market data model and deterministic L2 order book reconstruction.
//!
//! This crate owns `BookReconstructor`, `BookRouter`, `InstrumentRegistry`,
//! normalized snapshot/delta inputs, and reconstruction outputs such as BBO,
//! gap, and reject events. It does not own exchange clients, venue-specific
//! sequence rules, transport, pricing, OMS, or persistence.

use std::collections::{BTreeMap, BTreeSet};

use bedrock_rs_common::{
    InstrumentId, Level, Price, Quantity, Sequence, Side, TimestampNs, VenueId,
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BookSnapshot {
    pub venue_id: VenueId,
    pub instrument_id: InstrumentId,
    pub sequence: Sequence,
    pub timestamp_ns: TimestampNs,
    pub bids: Vec<Level>,
    pub asks: Vec<Level>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct LevelUpdate {
    pub side: Side,
    pub price: Price,
    pub quantity: Quantity,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BookDelta {
    pub venue_id: VenueId,
    pub instrument_id: InstrumentId,
    pub sequence: Sequence,
    pub timestamp_ns: TimestampNs,
    pub updates: Vec<LevelUpdate>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Bbo {
    pub venue_id: VenueId,
    pub instrument_id: InstrumentId,
    pub sequence: Sequence,
    pub timestamp_ns: TimestampNs,
    pub bid_price: Price,
    pub bid_quantity: Quantity,
    pub ask_price: Price,
    pub ask_quantity: Quantity,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GapReason {
    DeltaWithoutSnapshot,
    SequenceGap,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BookGap {
    pub venue_id: VenueId,
    pub instrument_id: InstrumentId,
    pub expected_sequence: Option<Sequence>,
    pub actual_sequence: Sequence,
    pub reason: GapReason,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MarketDataEvent {
    Snapshot(BookSnapshot),
    Delta(BookDelta),
    Bbo(Bbo),
    Gap(BookGap),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RejectReason {
    WrongVenue,
    WrongInstrument,
    UnknownInstrument,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BookReject {
    pub expected_venue_id: Option<VenueId>,
    pub expected_instrument_id: Option<InstrumentId>,
    pub actual_venue_id: VenueId,
    pub actual_instrument_id: InstrumentId,
    pub sequence: Sequence,
    pub reason: RejectReason,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReconstructionOutput {
    Bbo(Bbo),
    Gap(BookGap),
    Reject(BookReject),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BookState {
    NeedsSnapshot,
    Ready,
    Gapped,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct BookKey {
    pub venue_id: VenueId,
    pub instrument_id: InstrumentId,
}

impl BookKey {
    pub fn new(venue_id: VenueId, instrument_id: InstrumentId) -> Self {
        Self {
            venue_id,
            instrument_id,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UnknownInstrumentPolicy {
    AutoCreate,
    Reject,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct InstrumentRegistry {
    allowed: BTreeSet<BookKey>,
    unknown_policy: UnknownInstrumentPolicy,
}

impl InstrumentRegistry {
    pub fn auto_create() -> Self {
        Self {
            allowed: BTreeSet::new(),
            unknown_policy: UnknownInstrumentPolicy::AutoCreate,
        }
    }

    pub fn allow_list<I>(keys: I) -> Self
    where
        I: IntoIterator<Item = BookKey>,
    {
        Self {
            allowed: keys.into_iter().collect(),
            unknown_policy: UnknownInstrumentPolicy::Reject,
        }
    }

    pub fn accepts(&self, key: BookKey) -> bool {
        self.allowed.contains(&key) || self.unknown_policy == UnknownInstrumentPolicy::AutoCreate
    }

    pub fn unknown_policy(&self) -> UnknownInstrumentPolicy {
        self.unknown_policy
    }
}

impl Default for InstrumentRegistry {
    fn default() -> Self {
        Self::auto_create()
    }
}

#[derive(Debug, Clone, Default)]
struct BookSide {
    side: Vec<(Price, Quantity)>,
}

impl BookSide {
    fn rebuild(&mut self, levels: &[Level], side: Side) {
        self.side.clear();
        for level in levels.iter().filter(|level| level.side == side) {
            if !level.quantity.is_zero() {
                self.upsert(side, level.price, level.quantity);
            }
        }
    }

    fn upsert(&mut self, side: Side, price: Price, quantity: Quantity) {
        if let Some(index) = self
            .side
            .iter()
            .position(|(candidate, _)| *candidate == price)
        {
            if quantity.is_zero() {
                self.side.remove(index);
            } else {
                self.side[index] = (price, quantity);
            }
            return;
        }

        if quantity.is_zero() {
            return;
        }

        self.side.push((price, quantity));
        match side {
            Side::Bid => self.side.sort_by(|left, right| right.0.cmp(&left.0)),
            Side::Ask => self.side.sort_by(|left, right| left.0.cmp(&right.0)),
        }
    }

    fn top(&self) -> Option<(Price, Quantity)> {
        self.side.first().copied()
    }
}

#[derive(Debug, Clone)]
pub struct BookReconstructor {
    venue_id: VenueId,
    instrument_id: InstrumentId,
    state: BookState,
    last_sequence: Option<Sequence>,
    bids: BookSide,
    asks: BookSide,
}

impl BookReconstructor {
    pub fn new(venue_id: VenueId, instrument_id: InstrumentId) -> Self {
        Self {
            venue_id,
            instrument_id,
            state: BookState::NeedsSnapshot,
            last_sequence: None,
            bids: BookSide::default(),
            asks: BookSide::default(),
        }
    }

    pub fn state(&self) -> BookState {
        self.state
    }

    pub fn apply_snapshot(&mut self, snapshot: BookSnapshot) -> Vec<ReconstructionOutput> {
        if let Some(reject) =
            self.reject_for_identity(snapshot.venue_id, snapshot.instrument_id, snapshot.sequence)
        {
            return vec![ReconstructionOutput::Reject(reject)];
        }

        self.bids.rebuild(&snapshot.bids, Side::Bid);
        self.asks.rebuild(&snapshot.asks, Side::Ask);
        self.last_sequence = Some(snapshot.sequence);
        self.state = BookState::Ready;

        self.current_bbo(snapshot.sequence, snapshot.timestamp_ns)
            .map(|bbo| vec![ReconstructionOutput::Bbo(bbo)])
            .unwrap_or_default()
    }

    pub fn apply_delta(&mut self, delta: BookDelta) -> Vec<ReconstructionOutput> {
        self.apply_delta_with_sequence_check(delta, true)
    }

    pub fn apply_trusted_delta(&mut self, delta: BookDelta) -> Vec<ReconstructionOutput> {
        self.apply_delta_with_sequence_check(delta, false)
    }

    fn apply_delta_with_sequence_check(
        &mut self,
        delta: BookDelta,
        check_sequence: bool,
    ) -> Vec<ReconstructionOutput> {
        if let Some(reject) =
            self.reject_for_identity(delta.venue_id, delta.instrument_id, delta.sequence)
        {
            return vec![ReconstructionOutput::Reject(reject)];
        }

        if self.state != BookState::Ready {
            let gap = BookGap {
                venue_id: delta.venue_id,
                instrument_id: delta.instrument_id,
                expected_sequence: self.last_sequence.map(Sequence::next),
                actual_sequence: delta.sequence,
                reason: GapReason::DeltaWithoutSnapshot,
            };
            self.state = BookState::Gapped;
            return vec![ReconstructionOutput::Gap(gap)];
        }

        let expected = self.last_sequence.map(Sequence::next);
        if check_sequence && expected != Some(delta.sequence) {
            let gap = BookGap {
                venue_id: delta.venue_id,
                instrument_id: delta.instrument_id,
                expected_sequence: expected,
                actual_sequence: delta.sequence,
                reason: GapReason::SequenceGap,
            };
            self.state = BookState::Gapped;
            return vec![ReconstructionOutput::Gap(gap)];
        }

        for update in &delta.updates {
            match update.side {
                Side::Bid => self.bids.upsert(Side::Bid, update.price, update.quantity),
                Side::Ask => self.asks.upsert(Side::Ask, update.price, update.quantity),
            }
        }

        self.last_sequence = Some(delta.sequence);
        self.current_bbo(delta.sequence, delta.timestamp_ns)
            .map(|bbo| vec![ReconstructionOutput::Bbo(bbo)])
            .unwrap_or_default()
    }

    pub fn current_bbo(&self, sequence: Sequence, timestamp_ns: TimestampNs) -> Option<Bbo> {
        let (bid_price, bid_quantity) = self.bids.top()?;
        let (ask_price, ask_quantity) = self.asks.top()?;
        Some(Bbo {
            venue_id: self.venue_id,
            instrument_id: self.instrument_id,
            sequence,
            timestamp_ns,
            bid_price,
            bid_quantity,
            ask_price,
            ask_quantity,
        })
    }

    fn reject_for_identity(
        &self,
        actual_venue_id: VenueId,
        actual_instrument_id: InstrumentId,
        sequence: Sequence,
    ) -> Option<BookReject> {
        let reason = if actual_venue_id != self.venue_id {
            Some(RejectReason::WrongVenue)
        } else if actual_instrument_id != self.instrument_id {
            Some(RejectReason::WrongInstrument)
        } else {
            None
        }?;

        Some(BookReject {
            expected_venue_id: Some(self.venue_id),
            expected_instrument_id: Some(self.instrument_id),
            actual_venue_id,
            actual_instrument_id,
            sequence,
            reason,
        })
    }
}

#[derive(Debug, Clone, Default)]
pub struct BookRouter {
    books: BTreeMap<BookKey, BookReconstructor>,
    registry: InstrumentRegistry,
}

impl BookRouter {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_registry(registry: InstrumentRegistry) -> Self {
        Self {
            books: BTreeMap::new(),
            registry,
        }
    }

    pub fn len(&self) -> usize {
        self.books.len()
    }

    pub fn is_empty(&self) -> bool {
        self.books.is_empty()
    }

    pub fn state(&self, key: BookKey) -> Option<BookState> {
        self.books.get(&key).map(BookReconstructor::state)
    }

    pub fn apply_snapshot(&mut self, snapshot: BookSnapshot) -> Vec<ReconstructionOutput> {
        let key = BookKey::new(snapshot.venue_id, snapshot.instrument_id);
        if !self.registry.accepts(key) {
            return vec![unknown_instrument_reject(
                key.venue_id,
                key.instrument_id,
                snapshot.sequence,
            )];
        }

        self.books
            .entry(key)
            .or_insert_with(|| BookReconstructor::new(key.venue_id, key.instrument_id))
            .apply_snapshot(snapshot)
    }

    pub fn apply_delta(&mut self, delta: BookDelta) -> Vec<ReconstructionOutput> {
        let key = BookKey::new(delta.venue_id, delta.instrument_id);
        if !self.registry.accepts(key) {
            return vec![unknown_instrument_reject(
                key.venue_id,
                key.instrument_id,
                delta.sequence,
            )];
        }

        self.books
            .entry(key)
            .or_insert_with(|| BookReconstructor::new(key.venue_id, key.instrument_id))
            .apply_delta(delta)
    }

    pub fn apply_trusted_delta(&mut self, delta: BookDelta) -> Vec<ReconstructionOutput> {
        let key = BookKey::new(delta.venue_id, delta.instrument_id);
        if !self.registry.accepts(key) {
            return vec![unknown_instrument_reject(
                key.venue_id,
                key.instrument_id,
                delta.sequence,
            )];
        }

        self.books
            .entry(key)
            .or_insert_with(|| BookReconstructor::new(key.venue_id, key.instrument_id))
            .apply_trusted_delta(delta)
    }
}

fn unknown_instrument_reject(
    venue_id: VenueId,
    instrument_id: InstrumentId,
    sequence: Sequence,
) -> ReconstructionOutput {
    ReconstructionOutput::Reject(BookReject {
        expected_venue_id: None,
        expected_instrument_id: None,
        actual_venue_id: venue_id,
        actual_instrument_id: instrument_id,
        sequence,
        reason: RejectReason::UnknownInstrument,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn venue() -> VenueId {
        VenueId::new(1).unwrap()
    }

    fn other_venue() -> VenueId {
        VenueId::new(2).unwrap()
    }

    fn instrument() -> InstrumentId {
        InstrumentId::new(1).unwrap()
    }

    fn other_instrument() -> InstrumentId {
        InstrumentId::new(2).unwrap()
    }

    fn seq(raw: u64) -> Sequence {
        Sequence::new(raw).unwrap()
    }

    fn ts(raw: u64) -> TimestampNs {
        TimestampNs::new(raw).unwrap()
    }

    fn price(raw: i64) -> Price {
        Price::new(raw).unwrap()
    }

    fn qty(raw: i64) -> Quantity {
        Quantity::new(raw).unwrap()
    }

    fn level(side: Side, raw_price: i64, raw_qty: i64) -> Level {
        Level::new(side, price(raw_price), qty(raw_qty))
    }

    fn update(side: Side, raw_price: i64, raw_qty: i64) -> LevelUpdate {
        LevelUpdate {
            side,
            price: price(raw_price),
            quantity: qty(raw_qty),
        }
    }

    fn snapshot(sequence: u64) -> BookSnapshot {
        BookSnapshot {
            venue_id: venue(),
            instrument_id: instrument(),
            sequence: seq(sequence),
            timestamp_ns: ts(sequence * 1_000),
            bids: vec![
                level(Side::Bid, 10_000_000_000, 100_000_000),
                level(Side::Bid, 9_900_000_000, 200_000_000),
            ],
            asks: vec![
                level(Side::Ask, 10_100_000_000, 150_000_000),
                level(Side::Ask, 10_200_000_000, 300_000_000),
            ],
        }
    }

    fn snapshot_for(raw_instrument: u32, sequence: u64, bid: i64, ask: i64) -> BookSnapshot {
        BookSnapshot {
            venue_id: venue(),
            instrument_id: InstrumentId::new(raw_instrument).unwrap(),
            sequence: seq(sequence),
            timestamp_ns: ts(raw_instrument as u64 * 1_000 + sequence),
            bids: vec![level(Side::Bid, bid, 100_000_000)],
            asks: vec![level(Side::Ask, ask, 100_000_000)],
        }
    }

    fn delta_for(
        raw_instrument: u32,
        sequence: u64,
        timestamp: u64,
        side: Side,
        raw_price: i64,
        raw_qty: i64,
    ) -> BookDelta {
        BookDelta {
            venue_id: venue(),
            instrument_id: InstrumentId::new(raw_instrument).unwrap(),
            sequence: seq(sequence),
            timestamp_ns: ts(timestamp),
            updates: vec![update(side, raw_price, raw_qty)],
        }
    }

    #[test]
    fn snapshot_initializes_book_and_emits_bbo() {
        let mut reconstructor = BookReconstructor::new(venue(), instrument());
        let outputs = reconstructor.apply_snapshot(snapshot(1));
        assert_eq!(reconstructor.state(), BookState::Ready);
        assert_eq!(
            outputs,
            vec![ReconstructionOutput::Bbo(Bbo {
                venue_id: venue(),
                instrument_id: instrument(),
                sequence: seq(1),
                timestamp_ns: ts(1_000),
                bid_price: price(10_000_000_000),
                bid_quantity: qty(100_000_000),
                ask_price: price(10_100_000_000),
                ask_quantity: qty(150_000_000),
            })]
        );
    }

    #[test]
    fn continuous_delta_updates_bbo() {
        let mut reconstructor = BookReconstructor::new(venue(), instrument());
        reconstructor.apply_snapshot(snapshot(1));
        let outputs = reconstructor.apply_delta(BookDelta {
            venue_id: venue(),
            instrument_id: instrument(),
            sequence: seq(2),
            timestamp_ns: ts(2_000),
            updates: vec![update(Side::Bid, 10_050_000_000, 120_000_000)],
        });
        assert_eq!(
            outputs,
            vec![ReconstructionOutput::Bbo(Bbo {
                venue_id: venue(),
                instrument_id: instrument(),
                sequence: seq(2),
                timestamp_ns: ts(2_000),
                bid_price: price(10_050_000_000),
                bid_quantity: qty(120_000_000),
                ask_price: price(10_100_000_000),
                ask_quantity: qty(150_000_000),
            })]
        );
    }

    #[test]
    fn trusted_delta_skips_scalar_sequence_check_after_external_validation() {
        let mut reconstructor = BookReconstructor::new(venue(), instrument());
        reconstructor.apply_snapshot(snapshot(1));

        let outputs = reconstructor.apply_trusted_delta(BookDelta {
            venue_id: venue(),
            instrument_id: instrument(),
            sequence: seq(5),
            timestamp_ns: ts(5_000),
            updates: vec![update(Side::Bid, 10_050_000_000, 120_000_000)],
        });

        assert_eq!(
            outputs,
            vec![ReconstructionOutput::Bbo(Bbo {
                venue_id: venue(),
                instrument_id: instrument(),
                sequence: seq(5),
                timestamp_ns: ts(5_000),
                bid_price: price(10_050_000_000),
                bid_quantity: qty(120_000_000),
                ask_price: price(10_100_000_000),
                ask_quantity: qty(150_000_000),
            })]
        );
    }

    #[test]
    fn zero_quantity_removes_level_and_falls_back() {
        let mut reconstructor = BookReconstructor::new(venue(), instrument());
        reconstructor.apply_snapshot(snapshot(1));
        let outputs = reconstructor.apply_delta(BookDelta {
            venue_id: venue(),
            instrument_id: instrument(),
            sequence: seq(2),
            timestamp_ns: ts(2_000),
            updates: vec![update(Side::Bid, 10_000_000_000, 0)],
        });
        assert_eq!(
            outputs,
            vec![ReconstructionOutput::Bbo(Bbo {
                venue_id: venue(),
                instrument_id: instrument(),
                sequence: seq(2),
                timestamp_ns: ts(2_000),
                bid_price: price(9_900_000_000),
                bid_quantity: qty(200_000_000),
                ask_price: price(10_100_000_000),
                ask_quantity: qty(150_000_000),
            })]
        );
    }

    #[test]
    fn sequence_gap_marks_book_gapped() {
        let mut reconstructor = BookReconstructor::new(venue(), instrument());
        reconstructor.apply_snapshot(snapshot(1));
        let outputs = reconstructor.apply_delta(BookDelta {
            venue_id: venue(),
            instrument_id: instrument(),
            sequence: seq(3),
            timestamp_ns: ts(3_000),
            updates: vec![update(Side::Bid, 10_050_000_000, 120_000_000)],
        });
        assert_eq!(reconstructor.state(), BookState::Gapped);
        assert_eq!(
            outputs,
            vec![ReconstructionOutput::Gap(BookGap {
                venue_id: venue(),
                instrument_id: instrument(),
                expected_sequence: Some(seq(2)),
                actual_sequence: seq(3),
                reason: GapReason::SequenceGap,
            })]
        );
    }

    #[test]
    fn snapshot_recovers_after_gap() {
        let mut reconstructor = BookReconstructor::new(venue(), instrument());
        reconstructor.apply_delta(BookDelta {
            venue_id: venue(),
            instrument_id: instrument(),
            sequence: seq(3),
            timestamp_ns: ts(3_000),
            updates: vec![update(Side::Bid, 10_050_000_000, 120_000_000)],
        });
        assert_eq!(reconstructor.state(), BookState::Gapped);
        let outputs = reconstructor.apply_snapshot(snapshot(4));
        assert_eq!(reconstructor.state(), BookState::Ready);
        assert_eq!(
            outputs,
            vec![ReconstructionOutput::Bbo(Bbo {
                venue_id: venue(),
                instrument_id: instrument(),
                sequence: seq(4),
                timestamp_ns: ts(4_000),
                bid_price: price(10_000_000_000),
                bid_quantity: qty(100_000_000),
                ask_price: price(10_100_000_000),
                ask_quantity: qty(150_000_000),
            })]
        );
    }

    #[test]
    fn wrong_venue_snapshot_is_rejected_without_mutating_state() {
        let mut reconstructor = BookReconstructor::new(venue(), instrument());
        let mut wrong_snapshot = snapshot(1);
        wrong_snapshot.venue_id = other_venue();

        let outputs = reconstructor.apply_snapshot(wrong_snapshot);

        assert_eq!(reconstructor.state(), BookState::NeedsSnapshot);
        assert_eq!(
            outputs,
            vec![ReconstructionOutput::Reject(BookReject {
                expected_venue_id: Some(venue()),
                expected_instrument_id: Some(instrument()),
                actual_venue_id: other_venue(),
                actual_instrument_id: instrument(),
                sequence: seq(1),
                reason: RejectReason::WrongVenue,
            })]
        );

        let outputs = reconstructor.apply_snapshot(snapshot(1));
        assert_eq!(reconstructor.state(), BookState::Ready);
        assert!(matches!(outputs.as_slice(), [ReconstructionOutput::Bbo(_)]));
    }

    #[test]
    fn wrong_instrument_delta_is_rejected_without_consuming_sequence() {
        let mut reconstructor = BookReconstructor::new(venue(), instrument());
        reconstructor.apply_snapshot(snapshot(1));

        let outputs = reconstructor.apply_delta(BookDelta {
            venue_id: venue(),
            instrument_id: other_instrument(),
            sequence: seq(2),
            timestamp_ns: ts(2_000),
            updates: vec![update(Side::Bid, 10_050_000_000, 120_000_000)],
        });

        assert_eq!(reconstructor.state(), BookState::Ready);
        assert_eq!(
            outputs,
            vec![ReconstructionOutput::Reject(BookReject {
                expected_venue_id: Some(venue()),
                expected_instrument_id: Some(instrument()),
                actual_venue_id: venue(),
                actual_instrument_id: other_instrument(),
                sequence: seq(2),
                reason: RejectReason::WrongInstrument,
            })]
        );

        let outputs = reconstructor.apply_delta(BookDelta {
            venue_id: venue(),
            instrument_id: instrument(),
            sequence: seq(2),
            timestamp_ns: ts(2_100),
            updates: vec![update(Side::Bid, 10_050_000_000, 120_000_000)],
        });

        assert_eq!(
            outputs,
            vec![ReconstructionOutput::Bbo(Bbo {
                venue_id: venue(),
                instrument_id: instrument(),
                sequence: seq(2),
                timestamp_ns: ts(2_100),
                bid_price: price(10_050_000_000),
                bid_quantity: qty(120_000_000),
                ask_price: price(10_100_000_000),
                ask_quantity: qty(150_000_000),
            })]
        );
    }

    #[test]
    fn router_keeps_interleaved_instruments_independent() {
        let mut router = BookRouter::new();
        let instrument_one = BookKey::new(venue(), InstrumentId::new(1).unwrap());
        let instrument_two = BookKey::new(venue(), InstrumentId::new(2).unwrap());

        let out_one = router.apply_snapshot(snapshot_for(1, 1, 10_000_000_000, 10_100_000_000));
        let out_two = router.apply_snapshot(snapshot_for(2, 1, 20_000_000_000, 20_100_000_000));
        let out_one_delta = router.apply_delta(delta_for(
            1,
            2,
            2_000,
            Side::Bid,
            10_050_000_000,
            120_000_000,
        ));
        let out_two_delta = router.apply_delta(delta_for(
            2,
            2,
            2_100,
            Side::Ask,
            20_050_000_000,
            130_000_000,
        ));

        assert_eq!(router.len(), 2);
        assert_eq!(router.state(instrument_one), Some(BookState::Ready));
        assert_eq!(router.state(instrument_two), Some(BookState::Ready));
        assert!(matches!(
            out_one.as_slice(),
            [ReconstructionOutput::Bbo(Bbo { instrument_id, .. })] if *instrument_id == InstrumentId::new(1).unwrap()
        ));
        assert!(matches!(
            out_two.as_slice(),
            [ReconstructionOutput::Bbo(Bbo { instrument_id, .. })] if *instrument_id == InstrumentId::new(2).unwrap()
        ));
        assert_eq!(
            out_one_delta,
            vec![ReconstructionOutput::Bbo(Bbo {
                venue_id: venue(),
                instrument_id: InstrumentId::new(1).unwrap(),
                sequence: seq(2),
                timestamp_ns: ts(2_000),
                bid_price: price(10_050_000_000),
                bid_quantity: qty(120_000_000),
                ask_price: price(10_100_000_000),
                ask_quantity: qty(100_000_000),
            })]
        );
        assert_eq!(
            out_two_delta,
            vec![ReconstructionOutput::Bbo(Bbo {
                venue_id: venue(),
                instrument_id: InstrumentId::new(2).unwrap(),
                sequence: seq(2),
                timestamp_ns: ts(2_100),
                bid_price: price(20_000_000_000),
                bid_quantity: qty(100_000_000),
                ask_price: price(20_050_000_000),
                ask_quantity: qty(130_000_000),
            })]
        );
    }

    #[test]
    fn router_gap_in_one_instrument_does_not_affect_another() {
        let mut router = BookRouter::new();
        let instrument_one = BookKey::new(venue(), InstrumentId::new(1).unwrap());
        let instrument_two = BookKey::new(venue(), InstrumentId::new(2).unwrap());

        router.apply_snapshot(snapshot_for(1, 1, 10_000_000_000, 10_100_000_000));
        router.apply_snapshot(snapshot_for(2, 1, 20_000_000_000, 20_100_000_000));
        let gap = router.apply_delta(delta_for(
            1,
            3,
            3_000,
            Side::Bid,
            10_050_000_000,
            120_000_000,
        ));
        let healthy = router.apply_delta(delta_for(
            2,
            2,
            2_000,
            Side::Bid,
            20_050_000_000,
            120_000_000,
        ));

        assert_eq!(router.state(instrument_one), Some(BookState::Gapped));
        assert_eq!(router.state(instrument_two), Some(BookState::Ready));
        assert_eq!(
            gap,
            vec![ReconstructionOutput::Gap(BookGap {
                venue_id: venue(),
                instrument_id: InstrumentId::new(1).unwrap(),
                expected_sequence: Some(seq(2)),
                actual_sequence: seq(3),
                reason: GapReason::SequenceGap,
            })]
        );
        assert!(matches!(
            healthy.as_slice(),
            [ReconstructionOutput::Bbo(Bbo { instrument_id, sequence, .. })]
                if *instrument_id == InstrumentId::new(2).unwrap() && *sequence == seq(2)
        ));
    }

    #[test]
    fn router_strict_registry_allows_configured_instrument() {
        let key = BookKey::new(venue(), instrument());
        let registry = InstrumentRegistry::allow_list([key]);
        let mut router = BookRouter::with_registry(registry);

        let outputs = router.apply_snapshot(snapshot(1));

        assert_eq!(router.len(), 1);
        assert_eq!(router.state(key), Some(BookState::Ready));
        assert_eq!(
            outputs,
            vec![ReconstructionOutput::Bbo(Bbo {
                venue_id: venue(),
                instrument_id: instrument(),
                sequence: seq(1),
                timestamp_ns: ts(1_000),
                bid_price: price(10_000_000_000),
                bid_quantity: qty(100_000_000),
                ask_price: price(10_100_000_000),
                ask_quantity: qty(150_000_000),
            })]
        );
    }

    #[test]
    fn router_strict_registry_rejects_unknown_instrument_without_creating_book() {
        let allowed = BookKey::new(venue(), instrument());
        let unknown = BookKey::new(venue(), other_instrument());
        let registry = InstrumentRegistry::allow_list([allowed]);
        let mut router = BookRouter::with_registry(registry);

        let mut unknown_snapshot = snapshot(1);
        unknown_snapshot.instrument_id = other_instrument();
        let outputs = router.apply_snapshot(unknown_snapshot);

        assert_eq!(router.len(), 0);
        assert_eq!(router.state(allowed), None);
        assert_eq!(router.state(unknown), None);
        assert_eq!(
            outputs,
            vec![ReconstructionOutput::Reject(BookReject {
                expected_venue_id: None,
                expected_instrument_id: None,
                actual_venue_id: venue(),
                actual_instrument_id: other_instrument(),
                sequence: seq(1),
                reason: RejectReason::UnknownInstrument,
            })]
        );
    }

    #[test]
    fn router_strict_registry_rejects_unknown_delta_without_affecting_configured_book() {
        let allowed = BookKey::new(venue(), instrument());
        let registry = InstrumentRegistry::allow_list([allowed]);
        let mut router = BookRouter::with_registry(registry);
        router.apply_snapshot(snapshot(1));

        let outputs = router.apply_delta(BookDelta {
            venue_id: venue(),
            instrument_id: other_instrument(),
            sequence: seq(7),
            timestamp_ns: ts(7_000),
            updates: vec![update(Side::Bid, 20_050_000_000, 120_000_000)],
        });

        assert_eq!(router.len(), 1);
        assert_eq!(router.state(allowed), Some(BookState::Ready));
        assert_eq!(
            outputs,
            vec![ReconstructionOutput::Reject(BookReject {
                expected_venue_id: None,
                expected_instrument_id: None,
                actual_venue_id: venue(),
                actual_instrument_id: other_instrument(),
                sequence: seq(7),
                reason: RejectReason::UnknownInstrument,
            })]
        );

        let outputs = router.apply_delta(BookDelta {
            venue_id: venue(),
            instrument_id: instrument(),
            sequence: seq(2),
            timestamp_ns: ts(2_000),
            updates: vec![update(Side::Bid, 10_050_000_000, 120_000_000)],
        });

        assert_eq!(
            outputs,
            vec![ReconstructionOutput::Bbo(Bbo {
                venue_id: venue(),
                instrument_id: instrument(),
                sequence: seq(2),
                timestamp_ns: ts(2_000),
                bid_price: price(10_050_000_000),
                bid_quantity: qty(120_000_000),
                ask_price: price(10_100_000_000),
                ask_quantity: qty(150_000_000),
            })]
        );
    }
}
