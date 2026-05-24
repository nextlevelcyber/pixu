use bedrock_rs_common::{InstrumentId, TimestampNs, VenueId};

use crate::{
    normalize_binance_futures_delta, normalize_binance_futures_snapshot,
    normalize_binance_spot_delta, normalize_binance_spot_snapshot, normalize_bitget_depth_message,
    parse_binance_futures_depth_snapshot, parse_binance_futures_depth_update,
    parse_binance_spot_depth_snapshot, parse_binance_spot_depth_update, parse_bitget_depth_message,
    BinanceFuturesDepthSnapshot, BinanceFuturesDepthUpdate, BinanceFuturesSequencePolicy,
    BinanceSpotDepthSnapshot, BinanceSpotDepthUpdate, BinanceSpotSequencePolicy,
    BitgetBooksSequencePolicy, BitgetDepthAction, BitgetDepthMessage, NormalizedDepthEvent,
    SequenceDecision, SequenceGapReason, VenueNormalizeError, VenueParseError,
};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VenuePipelineError {
    Parse(VenueParseError),
    Normalize(VenueNormalizeError),
}

impl From<VenueParseError> for VenuePipelineError {
    fn from(value: VenueParseError) -> Self {
        Self::Parse(value)
    }
}

impl From<VenueNormalizeError> for VenuePipelineError {
    fn from(value: VenueNormalizeError) -> Self {
        Self::Normalize(value)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VenuePipelineGap {
    pub venue_id: VenueId,
    pub instrument_id: InstrumentId,
    pub reason: SequenceGapReason,
    pub event_sequence: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VenuePipelineIgnoredStale {
    pub venue_id: VenueId,
    pub instrument_id: InstrumentId,
    pub event_sequence: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum VenuePipelineOutput {
    Normalized(NormalizedDepthEvent),
    IgnoredStale(VenuePipelineIgnoredStale),
    Gap(VenuePipelineGap),
}

#[derive(Debug, Clone)]
pub struct BinanceSpotDepthPipeline {
    venue_id: VenueId,
    instrument_id: InstrumentId,
    policy: BinanceSpotSequencePolicy,
}

impl BinanceSpotDepthPipeline {
    pub fn new(venue_id: VenueId, instrument_id: InstrumentId) -> Self {
        Self {
            venue_id,
            instrument_id,
            policy: BinanceSpotSequencePolicy::new(),
        }
    }

    pub fn apply_snapshot_json(
        &mut self,
        raw: &str,
        timestamp_ns: TimestampNs,
    ) -> Result<VenuePipelineOutput, VenuePipelineError> {
        let snapshot = parse_binance_spot_depth_snapshot(raw)?;
        self.apply_snapshot(snapshot, timestamp_ns)
    }

    pub fn apply_snapshot(
        &mut self,
        snapshot: BinanceSpotDepthSnapshot,
        timestamp_ns: TimestampNs,
    ) -> Result<VenuePipelineOutput, VenuePipelineError> {
        let last_update_id = snapshot.last_update_id;
        let normalized = normalize_binance_spot_snapshot(
            snapshot,
            self.venue_id,
            self.instrument_id,
            timestamp_ns,
        )?;
        self.policy.set_snapshot(last_update_id);
        Ok(VenuePipelineOutput::Normalized(
            NormalizedDepthEvent::Snapshot(normalized),
        ))
    }

    pub fn process_update_json(
        &mut self,
        raw: &str,
    ) -> Result<VenuePipelineOutput, VenuePipelineError> {
        let update = parse_binance_spot_depth_update(raw)?;
        self.process_update(update)
    }

    pub fn process_update(
        &mut self,
        update: BinanceSpotDepthUpdate,
    ) -> Result<VenuePipelineOutput, VenuePipelineError> {
        let event_sequence = Some(update.final_update_id);
        match self
            .policy
            .apply_event(update.first_update_id, update.final_update_id)
        {
            SequenceDecision::Apply => {
                let delta =
                    normalize_binance_spot_delta(update, self.venue_id, self.instrument_id)?;
                Ok(VenuePipelineOutput::Normalized(
                    NormalizedDepthEvent::Delta(delta),
                ))
            }
            SequenceDecision::IgnoreStale => Ok(self.ignored_stale(event_sequence)),
            SequenceDecision::Gap(reason) | SequenceDecision::ResetRequired(reason) => {
                self.reset();
                Ok(self.gap(reason, event_sequence))
            }
        }
    }

    fn reset(&mut self) {
        self.policy = BinanceSpotSequencePolicy::new();
    }

    fn gap(&self, reason: SequenceGapReason, event_sequence: Option<u64>) -> VenuePipelineOutput {
        VenuePipelineOutput::Gap(VenuePipelineGap {
            venue_id: self.venue_id,
            instrument_id: self.instrument_id,
            reason,
            event_sequence,
        })
    }

    fn ignored_stale(&self, event_sequence: Option<u64>) -> VenuePipelineOutput {
        VenuePipelineOutput::IgnoredStale(VenuePipelineIgnoredStale {
            venue_id: self.venue_id,
            instrument_id: self.instrument_id,
            event_sequence,
        })
    }
}

#[derive(Debug, Clone)]
pub struct BinanceFuturesDepthPipeline {
    venue_id: VenueId,
    instrument_id: InstrumentId,
    pending_snapshot_last_update_id: Option<u64>,
    policy: BinanceFuturesSequencePolicy,
}

impl BinanceFuturesDepthPipeline {
    pub fn new(venue_id: VenueId, instrument_id: InstrumentId) -> Self {
        Self {
            venue_id,
            instrument_id,
            pending_snapshot_last_update_id: None,
            policy: BinanceFuturesSequencePolicy::new(),
        }
    }

    pub fn apply_snapshot_json(
        &mut self,
        raw: &str,
    ) -> Result<VenuePipelineOutput, VenuePipelineError> {
        let snapshot = parse_binance_futures_depth_snapshot(raw)?;
        self.apply_snapshot(snapshot)
    }

    pub fn apply_snapshot(
        &mut self,
        snapshot: BinanceFuturesDepthSnapshot,
    ) -> Result<VenuePipelineOutput, VenuePipelineError> {
        let last_update_id = snapshot.last_update_id;
        let normalized =
            normalize_binance_futures_snapshot(snapshot, self.venue_id, self.instrument_id)?;
        self.policy = BinanceFuturesSequencePolicy::new();
        self.pending_snapshot_last_update_id = Some(last_update_id);
        Ok(VenuePipelineOutput::Normalized(
            NormalizedDepthEvent::Snapshot(normalized),
        ))
    }

    pub fn process_update_json(
        &mut self,
        raw: &str,
    ) -> Result<VenuePipelineOutput, VenuePipelineError> {
        let update = parse_binance_futures_depth_update(raw)?;
        self.process_update(update)
    }

    pub fn process_update(
        &mut self,
        update: BinanceFuturesDepthUpdate,
    ) -> Result<VenuePipelineOutput, VenuePipelineError> {
        let event_sequence = Some(update.final_update_id);
        let is_first_after_snapshot = self.policy.previous_u().is_none();
        let decision = if self.policy.previous_u().is_some() {
            self.policy.apply_event(
                update.first_update_id,
                update.final_update_id,
                update.previous_final_update_id,
            )
        } else if let Some(last_update_id) = self.pending_snapshot_last_update_id {
            if update.final_update_id < last_update_id {
                SequenceDecision::IgnoreStale
            } else {
                self.policy.apply_first_event_after_snapshot(
                    last_update_id,
                    update.first_update_id,
                    update.final_update_id,
                )
            }
        } else {
            SequenceDecision::Gap(SequenceGapReason::NeedsSnapshot)
        };

        match decision {
            SequenceDecision::Apply => {
                if is_first_after_snapshot {
                    self.pending_snapshot_last_update_id = None;
                }
                let delta =
                    normalize_binance_futures_delta(update, self.venue_id, self.instrument_id)?;
                Ok(VenuePipelineOutput::Normalized(
                    NormalizedDepthEvent::Delta(delta),
                ))
            }
            SequenceDecision::IgnoreStale => Ok(self.ignored_stale(event_sequence)),
            SequenceDecision::Gap(reason) | SequenceDecision::ResetRequired(reason) => {
                self.reset();
                Ok(self.gap(reason, event_sequence))
            }
        }
    }

    fn reset(&mut self) {
        self.pending_snapshot_last_update_id = None;
        self.policy = BinanceFuturesSequencePolicy::new();
    }

    fn gap(&self, reason: SequenceGapReason, event_sequence: Option<u64>) -> VenuePipelineOutput {
        VenuePipelineOutput::Gap(VenuePipelineGap {
            venue_id: self.venue_id,
            instrument_id: self.instrument_id,
            reason,
            event_sequence,
        })
    }

    fn ignored_stale(&self, event_sequence: Option<u64>) -> VenuePipelineOutput {
        VenuePipelineOutput::IgnoredStale(VenuePipelineIgnoredStale {
            venue_id: self.venue_id,
            instrument_id: self.instrument_id,
            event_sequence,
        })
    }
}

#[derive(Debug, Clone)]
pub struct BitgetBooksDepthPipeline {
    venue_id: VenueId,
    instrument_id: InstrumentId,
    pending_snapshot_sequence: Option<u64>,
    policy: BitgetBooksSequencePolicy,
}

impl BitgetBooksDepthPipeline {
    pub fn new(venue_id: VenueId, instrument_id: InstrumentId) -> Self {
        Self {
            venue_id,
            instrument_id,
            pending_snapshot_sequence: None,
            policy: BitgetBooksSequencePolicy::new(),
        }
    }

    pub fn process_json(&mut self, raw: &str) -> Result<VenuePipelineOutput, VenuePipelineError> {
        let message = parse_bitget_depth_message(raw)?;
        self.process_message(message)
    }

    pub fn process_message(
        &mut self,
        message: BitgetDepthMessage,
    ) -> Result<VenuePipelineOutput, VenuePipelineError> {
        let event_sequence = Some(message.sequence);
        match message.action {
            BitgetDepthAction::Snapshot => {
                self.policy.apply_snapshot(message.sequence);
                self.pending_snapshot_sequence = Some(message.sequence);
                let normalized =
                    normalize_bitget_depth_message(message, self.venue_id, self.instrument_id)?;
                Ok(VenuePipelineOutput::Normalized(normalized))
            }
            BitgetDepthAction::Update => {
                let decision = if message.previous_sequence == 0 {
                    SequenceDecision::ResetRequired(SequenceGapReason::VenueReset)
                } else if let Some(snapshot_sequence) = self.pending_snapshot_sequence {
                    if BitgetBooksSequencePolicy::bridges_snapshot(
                        snapshot_sequence,
                        message.previous_sequence,
                        message.sequence,
                    ) {
                        self.policy.apply_snapshot(message.sequence);
                        self.pending_snapshot_sequence = None;
                        SequenceDecision::Apply
                    } else {
                        SequenceDecision::Gap(SequenceGapReason::SnapshotNotBridged)
                    }
                } else {
                    self.policy
                        .apply_update(message.sequence, message.previous_sequence)
                };

                match decision {
                    SequenceDecision::Apply => {
                        let normalized = normalize_bitget_depth_message(
                            message,
                            self.venue_id,
                            self.instrument_id,
                        )?;
                        Ok(VenuePipelineOutput::Normalized(normalized))
                    }
                    SequenceDecision::IgnoreStale => Ok(self.ignored_stale(event_sequence)),
                    SequenceDecision::Gap(reason) | SequenceDecision::ResetRequired(reason) => {
                        self.reset();
                        Ok(self.gap(reason, event_sequence))
                    }
                }
            }
        }
    }

    fn reset(&mut self) {
        self.pending_snapshot_sequence = None;
        self.policy = BitgetBooksSequencePolicy::new();
    }

    fn gap(&self, reason: SequenceGapReason, event_sequence: Option<u64>) -> VenuePipelineOutput {
        VenuePipelineOutput::Gap(VenuePipelineGap {
            venue_id: self.venue_id,
            instrument_id: self.instrument_id,
            reason,
            event_sequence,
        })
    }

    fn ignored_stale(&self, event_sequence: Option<u64>) -> VenuePipelineOutput {
        VenuePipelineOutput::IgnoredStale(VenuePipelineIgnoredStale {
            venue_id: self.venue_id,
            instrument_id: self.instrument_id,
            event_sequence,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{BitgetDepthChannelKind, RawLevel};
    use bedrock_rs_common::Sequence;

    fn venue(raw: u16) -> VenueId {
        VenueId::new(raw).unwrap()
    }

    fn instrument(raw: u32) -> InstrumentId {
        InstrumentId::new(raw).unwrap()
    }

    fn timestamp(raw: u64) -> TimestampNs {
        TimestampNs::new(raw).unwrap()
    }

    fn sequence(raw: u64) -> Sequence {
        Sequence::new(raw).unwrap()
    }

    fn spot_snapshot(last_update_id: u64) -> BinanceSpotDepthSnapshot {
        BinanceSpotDepthSnapshot {
            last_update_id,
            bids: vec![RawLevel::new("1.00", "2.00")],
            asks: vec![RawLevel::new("1.01", "3.00")],
        }
    }

    fn spot_update(first_update_id: u64, final_update_id: u64) -> BinanceSpotDepthUpdate {
        BinanceSpotDepthUpdate {
            symbol: "BNBBTC".to_owned(),
            event_time_ms: 1_700_000_000_000,
            first_update_id,
            final_update_id,
            bids: vec![RawLevel::new("1.00", "2.50")],
            asks: vec![RawLevel::new("1.02", "1.00")],
        }
    }

    fn futures_snapshot(last_update_id: u64) -> BinanceFuturesDepthSnapshot {
        BinanceFuturesDepthSnapshot {
            last_update_id,
            message_output_time_ms: 1_700_000_000_000,
            transaction_time_ms: 1_699_999_999_999,
            bids: vec![RawLevel::new("100.00", "0.10")],
            asks: vec![RawLevel::new("100.01", "0.20")],
        }
    }

    fn futures_update(
        first_update_id: u64,
        final_update_id: u64,
        previous_final_update_id: u64,
    ) -> BinanceFuturesDepthUpdate {
        BinanceFuturesDepthUpdate {
            symbol: "BTCUSDT".to_owned(),
            event_time_ms: 1_700_000_000_100,
            transaction_time_ms: 1_700_000_000_099,
            first_update_id,
            final_update_id,
            previous_final_update_id,
            bids: vec![RawLevel::new("100.00", "0.11")],
            asks: vec![RawLevel::new("100.02", "0.01")],
        }
    }

    fn bitget_message(
        action: BitgetDepthAction,
        sequence: u64,
        previous_sequence: u64,
    ) -> BitgetDepthMessage {
        BitgetDepthMessage {
            topic: "books".to_owned(),
            symbol: "BTCUSDT".to_owned(),
            channel_kind: BitgetDepthChannelKind::Incremental,
            action,
            sequence,
            previous_sequence,
            timestamp_ms: 1_700_000_000_000,
            max_depth: Some(50),
            bids: vec![RawLevel::new("100.00", "0.10")],
            asks: vec![RawLevel::new("100.01", "0.20")],
        }
    }

    #[test]
    fn spot_pipeline_emits_snapshot_delta_and_ignores_stale_update() {
        let mut pipeline = BinanceSpotDepthPipeline::new(venue(1), instrument(1));

        let snapshot_output = pipeline
            .apply_snapshot(spot_snapshot(100), timestamp(1_700_000_000_000_000_000))
            .unwrap();
        let VenuePipelineOutput::Normalized(NormalizedDepthEvent::Snapshot(snapshot)) =
            snapshot_output
        else {
            panic!("expected snapshot");
        };
        assert_eq!(snapshot.sequence, sequence(100));

        let delta_output = pipeline.process_update(spot_update(99, 101)).unwrap();
        let VenuePipelineOutput::Normalized(NormalizedDepthEvent::Delta(delta)) = delta_output
        else {
            panic!("expected delta");
        };
        assert_eq!(delta.sequence, sequence(101));

        let stale_output = pipeline.process_update(spot_update(100, 101)).unwrap();
        assert_eq!(
            stale_output,
            VenuePipelineOutput::IgnoredStale(VenuePipelineIgnoredStale {
                venue_id: venue(1),
                instrument_id: instrument(1),
                event_sequence: Some(101),
            })
        );
    }

    #[test]
    fn spot_pipeline_gap_resets_to_needs_snapshot() {
        let mut pipeline = BinanceSpotDepthPipeline::new(venue(1), instrument(1));
        pipeline
            .apply_snapshot(spot_snapshot(100), timestamp(1_700_000_000_000_000_000))
            .unwrap();

        let gap_output = pipeline.process_update(spot_update(102, 105)).unwrap();
        assert_eq!(
            gap_output,
            VenuePipelineOutput::Gap(VenuePipelineGap {
                venue_id: venue(1),
                instrument_id: instrument(1),
                reason: SequenceGapReason::RangeGap,
                event_sequence: Some(105),
            })
        );

        let next_output = pipeline.process_update(spot_update(106, 107)).unwrap();
        assert_eq!(
            next_output,
            VenuePipelineOutput::Gap(VenuePipelineGap {
                venue_id: venue(1),
                instrument_id: instrument(1),
                reason: SequenceGapReason::NeedsSnapshot,
                event_sequence: Some(107),
            })
        );
    }

    #[test]
    fn futures_pipeline_emits_snapshot_first_delta_and_pu_delta() {
        let mut pipeline = BinanceFuturesDepthPipeline::new(venue(2), instrument(7));

        let snapshot_output = pipeline.apply_snapshot(futures_snapshot(100)).unwrap();
        let VenuePipelineOutput::Normalized(NormalizedDepthEvent::Snapshot(snapshot)) =
            snapshot_output
        else {
            panic!("expected snapshot");
        };
        assert_eq!(snapshot.sequence, sequence(100));

        let first_output = pipeline
            .process_update(futures_update(99, 101, 98))
            .unwrap();
        let VenuePipelineOutput::Normalized(NormalizedDepthEvent::Delta(first_delta)) =
            first_output
        else {
            panic!("expected first delta");
        };
        assert_eq!(first_delta.sequence, sequence(101));

        let next_output = pipeline
            .process_update(futures_update(102, 105, 101))
            .unwrap();
        let VenuePipelineOutput::Normalized(NormalizedDepthEvent::Delta(next_delta)) = next_output
        else {
            panic!("expected next delta");
        };
        assert_eq!(next_delta.sequence, sequence(105));
    }

    #[test]
    fn futures_pipeline_ignores_stale_buffered_update_before_first_bridge() {
        let mut pipeline = BinanceFuturesDepthPipeline::new(venue(2), instrument(7));
        pipeline.apply_snapshot(futures_snapshot(100)).unwrap();

        let stale_output = pipeline.process_update(futures_update(90, 99, 89)).unwrap();
        assert_eq!(
            stale_output,
            VenuePipelineOutput::IgnoredStale(VenuePipelineIgnoredStale {
                venue_id: venue(2),
                instrument_id: instrument(7),
                event_sequence: Some(99),
            })
        );

        let first_output = pipeline
            .process_update(futures_update(99, 101, 98))
            .unwrap();
        let VenuePipelineOutput::Normalized(NormalizedDepthEvent::Delta(first_delta)) =
            first_output
        else {
            panic!("expected first delta");
        };
        assert_eq!(first_delta.sequence, sequence(101));
    }

    #[test]
    fn futures_pipeline_pu_mismatch_resets_to_needs_snapshot() {
        let mut pipeline = BinanceFuturesDepthPipeline::new(venue(2), instrument(7));
        pipeline.apply_snapshot(futures_snapshot(100)).unwrap();
        pipeline
            .process_update(futures_update(99, 101, 98))
            .unwrap();

        let gap_output = pipeline
            .process_update(futures_update(102, 105, 100))
            .unwrap();
        assert_eq!(
            gap_output,
            VenuePipelineOutput::Gap(VenuePipelineGap {
                venue_id: venue(2),
                instrument_id: instrument(7),
                reason: SequenceGapReason::PreviousFinalUpdateMismatch,
                event_sequence: Some(105),
            })
        );

        let next_output = pipeline
            .process_update(futures_update(106, 107, 105))
            .unwrap();
        assert_eq!(
            next_output,
            VenuePipelineOutput::Gap(VenuePipelineGap {
                venue_id: venue(2),
                instrument_id: instrument(7),
                reason: SequenceGapReason::NeedsSnapshot,
                event_sequence: Some(107),
            })
        );
    }

    #[test]
    fn bitget_pipeline_emits_snapshot_then_update() {
        let mut pipeline = BitgetBooksDepthPipeline::new(venue(3), instrument(9));

        let snapshot_output = pipeline
            .process_message(bitget_message(BitgetDepthAction::Snapshot, 100, 0))
            .unwrap();
        let VenuePipelineOutput::Normalized(NormalizedDepthEvent::Snapshot(snapshot)) =
            snapshot_output
        else {
            panic!("expected snapshot");
        };
        assert_eq!(snapshot.sequence, sequence(100));

        let update_output = pipeline
            .process_message(bitget_message(BitgetDepthAction::Update, 101, 100))
            .unwrap();
        let VenuePipelineOutput::Normalized(NormalizedDepthEvent::Delta(delta)) = update_output
        else {
            panic!("expected delta");
        };
        assert_eq!(delta.sequence, sequence(101));
    }

    #[test]
    fn bitget_pipeline_allows_first_update_that_bridges_snapshot() {
        let mut pipeline = BitgetBooksDepthPipeline::new(venue(3), instrument(9));
        pipeline
            .process_message(bitget_message(BitgetDepthAction::Snapshot, 100, 0))
            .unwrap();

        let bridged_output = pipeline
            .process_message(bitget_message(BitgetDepthAction::Update, 105, 99))
            .unwrap();
        let VenuePipelineOutput::Normalized(NormalizedDepthEvent::Delta(delta)) = bridged_output
        else {
            panic!("expected bridged delta");
        };
        assert_eq!(delta.sequence, sequence(105));

        let next_output = pipeline
            .process_message(bitget_message(BitgetDepthAction::Update, 106, 105))
            .unwrap();
        let VenuePipelineOutput::Normalized(NormalizedDepthEvent::Delta(next_delta)) = next_output
        else {
            panic!("expected next delta");
        };
        assert_eq!(next_delta.sequence, sequence(106));
    }

    #[test]
    fn bitget_pipeline_pseq_zero_resets_to_needs_snapshot() {
        let mut pipeline = BitgetBooksDepthPipeline::new(venue(3), instrument(9));
        pipeline
            .process_message(bitget_message(BitgetDepthAction::Snapshot, 100, 0))
            .unwrap();

        let reset_output = pipeline
            .process_message(bitget_message(BitgetDepthAction::Update, 101, 0))
            .unwrap();
        assert_eq!(
            reset_output,
            VenuePipelineOutput::Gap(VenuePipelineGap {
                venue_id: venue(3),
                instrument_id: instrument(9),
                reason: SequenceGapReason::VenueReset,
                event_sequence: Some(101),
            })
        );

        let next_output = pipeline
            .process_message(bitget_message(BitgetDepthAction::Update, 102, 101))
            .unwrap();
        assert_eq!(
            next_output,
            VenuePipelineOutput::Gap(VenuePipelineGap {
                venue_id: venue(3),
                instrument_id: instrument(9),
                reason: SequenceGapReason::NeedsSnapshot,
                event_sequence: Some(102),
            })
        );
    }

    #[test]
    fn spot_pipeline_json_convenience_propagates_parse_error() {
        let mut pipeline = BinanceSpotDepthPipeline::new(venue(1), instrument(1));

        assert!(matches!(
            pipeline.process_update_json("{"),
            Err(VenuePipelineError::Parse(_))
        ));
    }
}
