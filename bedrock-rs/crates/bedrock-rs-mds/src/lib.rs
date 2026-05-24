//! Market Data Service composition layer for Rust Bedrock.
//!
//! This crate wires venue pipeline outputs into venue-neutral book
//! reconstruction. It does not own exchange IO, venue sequence rules,
//! reconstruction internals, concrete transport fanout, pricing, OMS, or
//! execution.

use bedrock_rs_common::{InstrumentId, TimestampNs, VenueId};
use bedrock_rs_md::{
    BookRouter, GapReason, InstrumentRegistry, ReconstructionOutput, RejectReason,
};
use bedrock_rs_md_venue::{
    NormalizedDepthEvent, SequenceGapReason, VenuePipelineGap, VenuePipelineIgnoredStale,
    VenuePipelineOutput,
};
use bedrock_rs_transport::{PublishError, Publisher};

pub const MDS_WIRE_SCHEMA_ID: u16 = 20;
pub const MDS_WIRE_SCHEMA_VERSION: u16 = 1;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum MdsOutput {
    Reconstruction(ReconstructionOutput),
    IgnoredStale(VenuePipelineIgnoredStale),
    VenueGap(VenuePipelineGap),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MdsOutputKind {
    Bbo,
    BookGap,
    BookReject,
    VenueGap,
    IgnoredStale,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MdsStreamKey {
    pub venue_id: VenueId,
    pub instrument_id: InstrumentId,
    pub kind: MdsOutputKind,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MdsOutputEnvelope {
    pub key: MdsStreamKey,
    pub sequence: Option<u64>,
    pub timestamp_ns: Option<TimestampNs>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MdsWireTemplate {
    Bbo = 1200,
    BookGap = 1201,
    BookReject = 1202,
    VenueGap = 1203,
    IgnoredStale = 1204,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MdsWireEnvelope {
    pub schema_id: u16,
    pub schema_version: u16,
    pub template_id: u16,
    pub venue_id: u16,
    pub instrument_id: u32,
    pub sequence: u64,
    pub timestamp_ns: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MdsBboWirePayload {
    pub bid_price: i64,
    pub bid_quantity: i64,
    pub ask_price: i64,
    pub ask_quantity: i64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MdsBookGapWireReason {
    DeltaWithoutSnapshot = 1,
    SequenceGap = 2,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MdsBookGapWirePayload {
    pub expected_sequence: u64,
    pub actual_sequence: u64,
    pub reason: u8,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MdsBookRejectWireReason {
    WrongVenue = 1,
    WrongInstrument = 2,
    UnknownInstrument = 3,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MdsBookRejectWirePayload {
    pub expected_venue_id: u16,
    pub expected_instrument_id: u32,
    pub actual_venue_id: u16,
    pub actual_instrument_id: u32,
    pub sequence: u64,
    pub reason: u8,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MdsVenueGapWireReason {
    NeedsSnapshot = 1,
    SnapshotNotBridged = 2,
    RangeGap = 3,
    InvalidRange = 4,
    PreviousFinalUpdateMismatch = 5,
    PreviousSequenceMismatch = 6,
    VenueReset = 7,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MdsVenueGapWirePayload {
    pub reason: u8,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MdsIgnoredStaleWirePayload;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MdsWireMessage {
    Bbo {
        envelope: MdsWireEnvelope,
        payload: MdsBboWirePayload,
    },
    BookGap {
        envelope: MdsWireEnvelope,
        payload: MdsBookGapWirePayload,
    },
    BookReject {
        envelope: MdsWireEnvelope,
        payload: MdsBookRejectWirePayload,
    },
    VenueGap {
        envelope: MdsWireEnvelope,
        payload: MdsVenueGapWirePayload,
    },
    IgnoredStale {
        envelope: MdsWireEnvelope,
        payload: MdsIgnoredStaleWirePayload,
    },
}

impl MdsOutput {
    pub fn envelope(&self) -> MdsOutputEnvelope {
        match self {
            Self::Reconstruction(ReconstructionOutput::Bbo(bbo)) => MdsOutputEnvelope {
                key: MdsStreamKey {
                    venue_id: bbo.venue_id,
                    instrument_id: bbo.instrument_id,
                    kind: MdsOutputKind::Bbo,
                },
                sequence: Some(bbo.sequence.raw()),
                timestamp_ns: Some(bbo.timestamp_ns),
            },
            Self::Reconstruction(ReconstructionOutput::Gap(gap)) => MdsOutputEnvelope {
                key: MdsStreamKey {
                    venue_id: gap.venue_id,
                    instrument_id: gap.instrument_id,
                    kind: MdsOutputKind::BookGap,
                },
                sequence: Some(gap.actual_sequence.raw()),
                timestamp_ns: None,
            },
            Self::Reconstruction(ReconstructionOutput::Reject(reject)) => MdsOutputEnvelope {
                key: MdsStreamKey {
                    venue_id: reject.actual_venue_id,
                    instrument_id: reject.actual_instrument_id,
                    kind: MdsOutputKind::BookReject,
                },
                sequence: Some(reject.sequence.raw()),
                timestamp_ns: None,
            },
            Self::IgnoredStale(stale) => MdsOutputEnvelope {
                key: MdsStreamKey {
                    venue_id: stale.venue_id,
                    instrument_id: stale.instrument_id,
                    kind: MdsOutputKind::IgnoredStale,
                },
                sequence: stale.event_sequence,
                timestamp_ns: None,
            },
            Self::VenueGap(gap) => MdsOutputEnvelope {
                key: MdsStreamKey {
                    venue_id: gap.venue_id,
                    instrument_id: gap.instrument_id,
                    kind: MdsOutputKind::VenueGap,
                },
                sequence: gap.event_sequence,
                timestamp_ns: None,
            },
        }
    }

    pub fn wire_message(&self) -> MdsWireMessage {
        match self {
            Self::Reconstruction(ReconstructionOutput::Bbo(bbo)) => MdsWireMessage::Bbo {
                envelope: wire_envelope(self.envelope(), MdsWireTemplate::Bbo),
                payload: MdsBboWirePayload {
                    bid_price: bbo.bid_price.raw(),
                    bid_quantity: bbo.bid_quantity.raw(),
                    ask_price: bbo.ask_price.raw(),
                    ask_quantity: bbo.ask_quantity.raw(),
                },
            },
            Self::Reconstruction(ReconstructionOutput::Gap(gap)) => MdsWireMessage::BookGap {
                envelope: wire_envelope(self.envelope(), MdsWireTemplate::BookGap),
                payload: MdsBookGapWirePayload {
                    expected_sequence: gap
                        .expected_sequence
                        .map(|sequence| sequence.raw())
                        .unwrap_or(0),
                    actual_sequence: gap.actual_sequence.raw(),
                    reason: book_gap_reason_code(gap.reason),
                },
            },
            Self::Reconstruction(ReconstructionOutput::Reject(reject)) => {
                MdsWireMessage::BookReject {
                    envelope: wire_envelope(self.envelope(), MdsWireTemplate::BookReject),
                    payload: MdsBookRejectWirePayload {
                        expected_venue_id: reject
                            .expected_venue_id
                            .map(|venue_id| venue_id.raw())
                            .unwrap_or(0),
                        expected_instrument_id: reject
                            .expected_instrument_id
                            .map(|instrument_id| instrument_id.raw())
                            .unwrap_or(0),
                        actual_venue_id: reject.actual_venue_id.raw(),
                        actual_instrument_id: reject.actual_instrument_id.raw(),
                        sequence: reject.sequence.raw(),
                        reason: book_reject_reason_code(reject.reason),
                    },
                }
            }
            Self::VenueGap(gap) => MdsWireMessage::VenueGap {
                envelope: wire_envelope(self.envelope(), MdsWireTemplate::VenueGap),
                payload: MdsVenueGapWirePayload {
                    reason: venue_gap_reason_code(gap.reason),
                },
            },
            Self::IgnoredStale(_) => MdsWireMessage::IgnoredStale {
                envelope: wire_envelope(self.envelope(), MdsWireTemplate::IgnoredStale),
                payload: MdsIgnoredStaleWirePayload,
            },
        }
    }
}

fn wire_envelope(envelope: MdsOutputEnvelope, template: MdsWireTemplate) -> MdsWireEnvelope {
    MdsWireEnvelope {
        schema_id: MDS_WIRE_SCHEMA_ID,
        schema_version: MDS_WIRE_SCHEMA_VERSION,
        template_id: template as u16,
        venue_id: envelope.key.venue_id.raw(),
        instrument_id: envelope.key.instrument_id.raw(),
        sequence: envelope.sequence.unwrap_or(0),
        timestamp_ns: envelope
            .timestamp_ns
            .map(|timestamp_ns| timestamp_ns.raw())
            .unwrap_or(0),
    }
}

fn book_gap_reason_code(reason: GapReason) -> u8 {
    match reason {
        GapReason::DeltaWithoutSnapshot => MdsBookGapWireReason::DeltaWithoutSnapshot as u8,
        GapReason::SequenceGap => MdsBookGapWireReason::SequenceGap as u8,
    }
}

fn book_reject_reason_code(reason: RejectReason) -> u8 {
    match reason {
        RejectReason::WrongVenue => MdsBookRejectWireReason::WrongVenue as u8,
        RejectReason::WrongInstrument => MdsBookRejectWireReason::WrongInstrument as u8,
        RejectReason::UnknownInstrument => MdsBookRejectWireReason::UnknownInstrument as u8,
    }
}

fn venue_gap_reason_code(reason: SequenceGapReason) -> u8 {
    match reason {
        SequenceGapReason::NeedsSnapshot => MdsVenueGapWireReason::NeedsSnapshot as u8,
        SequenceGapReason::SnapshotNotBridged => MdsVenueGapWireReason::SnapshotNotBridged as u8,
        SequenceGapReason::RangeGap => MdsVenueGapWireReason::RangeGap as u8,
        SequenceGapReason::InvalidRange => MdsVenueGapWireReason::InvalidRange as u8,
        SequenceGapReason::PreviousFinalUpdateMismatch => {
            MdsVenueGapWireReason::PreviousFinalUpdateMismatch as u8
        }
        SequenceGapReason::PreviousSequenceMismatch => {
            MdsVenueGapWireReason::PreviousSequenceMismatch as u8
        }
        SequenceGapReason::VenueReset => MdsVenueGapWireReason::VenueReset as u8,
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MdsPublishError {
    pub published: usize,
    pub source: PublishError,
}

#[derive(Debug, Clone, Default)]
pub struct MdsRouter {
    router: BookRouter,
}

impl MdsRouter {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_registry(registry: InstrumentRegistry) -> Self {
        Self {
            router: BookRouter::with_registry(registry),
        }
    }

    pub fn len(&self) -> usize {
        self.router.len()
    }

    pub fn is_empty(&self) -> bool {
        self.router.is_empty()
    }

    pub fn apply_pipeline_outputs<I>(&mut self, outputs: I) -> Vec<MdsOutput>
    where
        I: IntoIterator<Item = VenuePipelineOutput>,
    {
        outputs
            .into_iter()
            .flat_map(|output| self.apply_pipeline_output(output))
            .collect()
    }

    pub fn apply_pipeline_output(&mut self, output: VenuePipelineOutput) -> Vec<MdsOutput> {
        match output {
            VenuePipelineOutput::Normalized(NormalizedDepthEvent::Snapshot(snapshot)) => self
                .router
                .apply_snapshot(snapshot)
                .into_iter()
                .map(MdsOutput::Reconstruction)
                .collect(),
            VenuePipelineOutput::Normalized(NormalizedDepthEvent::Delta(delta)) => self
                .router
                .apply_trusted_delta(delta)
                .into_iter()
                .map(MdsOutput::Reconstruction)
                .collect(),
            VenuePipelineOutput::IgnoredStale(stale) => vec![MdsOutput::IgnoredStale(stale)],
            VenuePipelineOutput::Gap(gap) => vec![MdsOutput::VenueGap(gap)],
        }
    }

    pub fn apply_pipeline_output_to<P>(
        &mut self,
        output: VenuePipelineOutput,
        publisher: &mut P,
    ) -> Result<usize, MdsPublishError>
    where
        P: Publisher<MdsOutput>,
    {
        let outputs = self.apply_pipeline_output(output);
        publish_outputs(outputs, publisher, 0)
    }

    pub fn apply_pipeline_outputs_to<I, P>(
        &mut self,
        outputs: I,
        publisher: &mut P,
    ) -> Result<usize, MdsPublishError>
    where
        I: IntoIterator<Item = VenuePipelineOutput>,
        P: Publisher<MdsOutput>,
    {
        let mut published = 0;
        for output in outputs {
            published = publish_outputs(self.apply_pipeline_output(output), publisher, published)?;
        }
        Ok(published)
    }
}

fn publish_outputs<P>(
    outputs: Vec<MdsOutput>,
    publisher: &mut P,
    mut published: usize,
) -> Result<usize, MdsPublishError>
where
    P: Publisher<MdsOutput>,
{
    for output in outputs {
        if let Err(source) = publisher.publish(output) {
            return Err(MdsPublishError { published, source });
        }
        published += 1;
    }
    Ok(published)
}

#[cfg(test)]
mod tests {
    use super::*;
    use bedrock_rs_common::{
        InstrumentId, Level, Price, Quantity, Sequence, Side, TimestampNs, VenueId,
    };
    use bedrock_rs_md::{
        BookGap, BookKey, BookReject, BookSnapshot, GapReason, InstrumentRegistry,
        ReconstructionOutput, RejectReason,
    };
    use bedrock_rs_md_venue::{
        NormalizedDepthEvent, SequenceGapReason, VenuePipelineGap, VenuePipelineIgnoredStale,
        VenuePipelineOutput,
    };
    use bedrock_rs_transport::{InProcChannel, PublishError, Subscriber};

    fn venue(raw: u16) -> VenueId {
        VenueId::new(raw).unwrap()
    }

    fn instrument(raw: u32) -> InstrumentId {
        InstrumentId::new(raw).unwrap()
    }

    fn sequence(raw: u64) -> Sequence {
        Sequence::new(raw).unwrap()
    }

    fn timestamp(raw: u64) -> TimestampNs {
        TimestampNs::new(raw).unwrap()
    }

    fn price(raw: i64) -> Price {
        Price::new(raw).unwrap()
    }

    fn quantity(raw: i64) -> Quantity {
        Quantity::new(raw).unwrap()
    }

    fn snapshot(seq: u64, venue_id: VenueId, instrument_id: InstrumentId) -> BookSnapshot {
        BookSnapshot {
            venue_id,
            instrument_id,
            sequence: sequence(seq),
            timestamp_ns: timestamp(1_700_000_000_000_000_000 + seq),
            bids: vec![Level::new(
                Side::Bid,
                price(100_000_000),
                quantity(10_000_000),
            )],
            asks: vec![Level::new(
                Side::Ask,
                price(101_000_000),
                quantity(20_000_000),
            )],
        }
    }

    fn delta(seq: u64, venue_id: VenueId, instrument_id: InstrumentId) -> bedrock_rs_md::BookDelta {
        bedrock_rs_md::BookDelta {
            venue_id,
            instrument_id,
            sequence: sequence(seq),
            timestamp_ns: timestamp(1_700_000_000_000_000_000 + seq),
            updates: vec![bedrock_rs_md::LevelUpdate {
                side: Side::Bid,
                price: price(102_000_000),
                quantity: quantity(30_000_000),
            }],
        }
    }

    #[test]
    fn routes_snapshot_pipeline_output_to_bbo() {
        let mut router = MdsRouter::new();

        let outputs = router.apply_pipeline_output(VenuePipelineOutput::Normalized(
            NormalizedDepthEvent::Snapshot(snapshot(10, venue(1), instrument(7))),
        ));

        assert_eq!(outputs.len(), 1);
        let MdsOutput::Reconstruction(ReconstructionOutput::Bbo(bbo)) = outputs[0] else {
            panic!("expected bbo");
        };
        assert_eq!(bbo.venue_id, venue(1));
        assert_eq!(bbo.instrument_id, instrument(7));
        assert_eq!(bbo.sequence, sequence(10));
        assert_eq!(bbo.bid_price, price(100_000_000));
    }

    #[test]
    fn routes_delta_pipeline_output_to_updated_bbo() {
        let mut router = MdsRouter::new();
        router.apply_pipeline_output(VenuePipelineOutput::Normalized(
            NormalizedDepthEvent::Snapshot(snapshot(10, venue(1), instrument(7))),
        ));

        let outputs = router.apply_pipeline_output(VenuePipelineOutput::Normalized(
            NormalizedDepthEvent::Delta(delta(11, venue(1), instrument(7))),
        ));

        assert_eq!(outputs.len(), 1);
        let MdsOutput::Reconstruction(ReconstructionOutput::Bbo(bbo)) = outputs[0] else {
            panic!("expected bbo");
        };
        assert_eq!(bbo.sequence, sequence(11));
        assert_eq!(bbo.bid_price, price(102_000_000));
    }

    #[test]
    fn routes_venue_validated_range_delta_without_scalar_sequence_gap() {
        let mut router = MdsRouter::new();
        router.apply_pipeline_output(VenuePipelineOutput::Normalized(
            NormalizedDepthEvent::Snapshot(snapshot(10, venue(1), instrument(7))),
        ));

        let outputs = router.apply_pipeline_output(VenuePipelineOutput::Normalized(
            NormalizedDepthEvent::Delta(delta(15, venue(1), instrument(7))),
        ));

        assert_eq!(outputs.len(), 1);
        let MdsOutput::Reconstruction(ReconstructionOutput::Bbo(bbo)) = outputs[0] else {
            panic!("expected bbo");
        };
        assert_eq!(bbo.sequence, sequence(15));
        assert_eq!(bbo.bid_price, price(102_000_000));
    }

    #[test]
    fn ignored_stale_does_not_create_book() {
        let mut router = MdsRouter::new();
        let stale = VenuePipelineIgnoredStale {
            venue_id: venue(1),
            instrument_id: instrument(7),
            event_sequence: Some(10),
        };

        let outputs =
            router.apply_pipeline_output(VenuePipelineOutput::IgnoredStale(stale.clone()));

        assert_eq!(outputs, vec![MdsOutput::IgnoredStale(stale)]);
        assert_eq!(router.len(), 0);
    }

    #[test]
    fn venue_gap_is_emitted_without_mutating_router() {
        let mut router = MdsRouter::new();
        let gap = VenuePipelineGap {
            venue_id: venue(1),
            instrument_id: instrument(7),
            reason: SequenceGapReason::RangeGap,
            event_sequence: Some(12),
        };

        let outputs = router.apply_pipeline_output(VenuePipelineOutput::Gap(gap.clone()));

        assert_eq!(outputs, vec![MdsOutput::VenueGap(gap)]);
        assert_eq!(router.len(), 0);
    }

    #[test]
    fn strict_registry_reject_is_surface_as_reconstruction_output() {
        let mut router = MdsRouter::with_registry(InstrumentRegistry::allow_list([BookKey::new(
            venue(1),
            instrument(7),
        )]));

        let outputs = router.apply_pipeline_output(VenuePipelineOutput::Normalized(
            NormalizedDepthEvent::Snapshot(snapshot(10, venue(1), instrument(8))),
        ));

        assert_eq!(outputs.len(), 1);
        let MdsOutput::Reconstruction(ReconstructionOutput::Reject(reject)) = outputs[0] else {
            panic!("expected reject");
        };
        assert_eq!(reject.reason, RejectReason::UnknownInstrument);
        assert_eq!(router.len(), 0);
    }

    #[test]
    fn publishes_snapshot_bbo_to_in_proc_channel() {
        let mut router = MdsRouter::new();
        let mut channel = InProcChannel::with_capacity(1).unwrap();

        let published = router
            .apply_pipeline_output_to(
                VenuePipelineOutput::Normalized(NormalizedDepthEvent::Snapshot(snapshot(
                    10,
                    venue(1),
                    instrument(7),
                ))),
                &mut channel,
            )
            .unwrap();

        assert_eq!(published, 1);
        let Some(MdsOutput::Reconstruction(ReconstructionOutput::Bbo(bbo))) =
            channel.poll().unwrap()
        else {
            panic!("expected published bbo");
        };
        assert_eq!(bbo.sequence, sequence(10));
    }

    #[test]
    fn publish_backpressure_reports_already_published_count() {
        let mut router = MdsRouter::new();
        let mut channel = InProcChannel::with_capacity(1).unwrap();

        let error = router
            .apply_pipeline_outputs_to(
                [
                    VenuePipelineOutput::Normalized(NormalizedDepthEvent::Snapshot(snapshot(
                        10,
                        venue(1),
                        instrument(7),
                    ))),
                    VenuePipelineOutput::Normalized(NormalizedDepthEvent::Delta(delta(
                        11,
                        venue(1),
                        instrument(7),
                    ))),
                ],
                &mut channel,
            )
            .unwrap_err();

        assert_eq!(
            error,
            MdsPublishError {
                published: 1,
                source: PublishError::Backpressure,
            }
        );
        assert_eq!(channel.len(), 1);
    }

    #[test]
    fn bbo_output_envelope_uses_bbo_key_sequence_and_timestamp() {
        let mut router = MdsRouter::new();
        let output = router
            .apply_pipeline_output(VenuePipelineOutput::Normalized(
                NormalizedDepthEvent::Snapshot(snapshot(10, venue(1), instrument(7))),
            ))
            .remove(0);

        assert_eq!(
            output.envelope(),
            MdsOutputEnvelope {
                key: MdsStreamKey {
                    venue_id: venue(1),
                    instrument_id: instrument(7),
                    kind: MdsOutputKind::Bbo,
                },
                sequence: Some(10),
                timestamp_ns: Some(timestamp(1_700_000_000_000_000_010)),
            }
        );
    }

    #[test]
    fn book_reject_output_envelope_uses_actual_identity_and_sequence() {
        let output = MdsOutput::Reconstruction(ReconstructionOutput::Reject(BookReject {
            expected_venue_id: None,
            expected_instrument_id: None,
            actual_venue_id: venue(2),
            actual_instrument_id: instrument(9),
            sequence: sequence(44),
            reason: RejectReason::UnknownInstrument,
        }));

        assert_eq!(
            output.envelope(),
            MdsOutputEnvelope {
                key: MdsStreamKey {
                    venue_id: venue(2),
                    instrument_id: instrument(9),
                    kind: MdsOutputKind::BookReject,
                },
                sequence: Some(44),
                timestamp_ns: None,
            }
        );
    }

    #[test]
    fn book_gap_output_envelope_uses_gap_identity_and_actual_sequence() {
        let output = MdsOutput::Reconstruction(ReconstructionOutput::Gap(BookGap {
            venue_id: venue(2),
            instrument_id: instrument(10),
            expected_sequence: Some(sequence(43)),
            actual_sequence: sequence(44),
            reason: GapReason::SequenceGap,
        }));

        assert_eq!(
            output.envelope(),
            MdsOutputEnvelope {
                key: MdsStreamKey {
                    venue_id: venue(2),
                    instrument_id: instrument(10),
                    kind: MdsOutputKind::BookGap,
                },
                sequence: Some(44),
                timestamp_ns: None,
            }
        );
    }

    #[test]
    fn venue_gap_output_envelope_uses_gap_identity_and_event_sequence() {
        let output = MdsOutput::VenueGap(VenuePipelineGap {
            venue_id: venue(3),
            instrument_id: instrument(11),
            reason: SequenceGapReason::RangeGap,
            event_sequence: Some(55),
        });

        assert_eq!(
            output.envelope(),
            MdsOutputEnvelope {
                key: MdsStreamKey {
                    venue_id: venue(3),
                    instrument_id: instrument(11),
                    kind: MdsOutputKind::VenueGap,
                },
                sequence: Some(55),
                timestamp_ns: None,
            }
        );
    }

    #[test]
    fn ignored_stale_output_envelope_uses_stale_identity_and_event_sequence() {
        let output = MdsOutput::IgnoredStale(VenuePipelineIgnoredStale {
            venue_id: venue(4),
            instrument_id: instrument(12),
            event_sequence: Some(66),
        });

        assert_eq!(
            output.envelope(),
            MdsOutputEnvelope {
                key: MdsStreamKey {
                    venue_id: venue(4),
                    instrument_id: instrument(12),
                    kind: MdsOutputKind::IgnoredStale,
                },
                sequence: Some(66),
                timestamp_ns: None,
            }
        );
    }

    #[test]
    fn bbo_output_maps_to_wire_template_and_scaled_payload() {
        let mut router = MdsRouter::new();
        let output = router
            .apply_pipeline_output(VenuePipelineOutput::Normalized(
                NormalizedDepthEvent::Snapshot(snapshot(10, venue(1), instrument(7))),
            ))
            .remove(0);

        assert_eq!(
            output.wire_message(),
            MdsWireMessage::Bbo {
                envelope: MdsWireEnvelope {
                    schema_id: MDS_WIRE_SCHEMA_ID,
                    schema_version: MDS_WIRE_SCHEMA_VERSION,
                    template_id: MdsWireTemplate::Bbo as u16,
                    venue_id: 1,
                    instrument_id: 7,
                    sequence: 10,
                    timestamp_ns: 1_700_000_000_000_000_010,
                },
                payload: MdsBboWirePayload {
                    bid_price: 100_000_000,
                    bid_quantity: 10_000_000,
                    ask_price: 101_000_000,
                    ask_quantity: 20_000_000,
                },
            }
        );
    }

    #[test]
    fn book_gap_output_maps_to_wire_reason_and_sequences() {
        let output = MdsOutput::Reconstruction(ReconstructionOutput::Gap(BookGap {
            venue_id: venue(2),
            instrument_id: instrument(10),
            expected_sequence: Some(sequence(43)),
            actual_sequence: sequence(44),
            reason: GapReason::SequenceGap,
        }));

        assert_eq!(
            output.wire_message(),
            MdsWireMessage::BookGap {
                envelope: MdsWireEnvelope {
                    schema_id: MDS_WIRE_SCHEMA_ID,
                    schema_version: MDS_WIRE_SCHEMA_VERSION,
                    template_id: MdsWireTemplate::BookGap as u16,
                    venue_id: 2,
                    instrument_id: 10,
                    sequence: 44,
                    timestamp_ns: 0,
                },
                payload: MdsBookGapWirePayload {
                    expected_sequence: 43,
                    actual_sequence: 44,
                    reason: MdsBookGapWireReason::SequenceGap as u8,
                },
            }
        );
    }

    #[test]
    fn book_reject_output_maps_optional_expected_identity_to_zero() {
        let output = MdsOutput::Reconstruction(ReconstructionOutput::Reject(BookReject {
            expected_venue_id: None,
            expected_instrument_id: None,
            actual_venue_id: venue(2),
            actual_instrument_id: instrument(9),
            sequence: sequence(44),
            reason: RejectReason::UnknownInstrument,
        }));

        assert_eq!(
            output.wire_message(),
            MdsWireMessage::BookReject {
                envelope: MdsWireEnvelope {
                    schema_id: MDS_WIRE_SCHEMA_ID,
                    schema_version: MDS_WIRE_SCHEMA_VERSION,
                    template_id: MdsWireTemplate::BookReject as u16,
                    venue_id: 2,
                    instrument_id: 9,
                    sequence: 44,
                    timestamp_ns: 0,
                },
                payload: MdsBookRejectWirePayload {
                    expected_venue_id: 0,
                    expected_instrument_id: 0,
                    actual_venue_id: 2,
                    actual_instrument_id: 9,
                    sequence: 44,
                    reason: MdsBookRejectWireReason::UnknownInstrument as u8,
                },
            }
        );
    }

    #[test]
    fn venue_gap_output_maps_event_sequence_and_reason_code() {
        let output = MdsOutput::VenueGap(VenuePipelineGap {
            venue_id: venue(3),
            instrument_id: instrument(11),
            reason: SequenceGapReason::RangeGap,
            event_sequence: Some(55),
        });

        assert_eq!(
            output.wire_message(),
            MdsWireMessage::VenueGap {
                envelope: MdsWireEnvelope {
                    schema_id: MDS_WIRE_SCHEMA_ID,
                    schema_version: MDS_WIRE_SCHEMA_VERSION,
                    template_id: MdsWireTemplate::VenueGap as u16,
                    venue_id: 3,
                    instrument_id: 11,
                    sequence: 55,
                    timestamp_ns: 0,
                },
                payload: MdsVenueGapWirePayload {
                    reason: MdsVenueGapWireReason::RangeGap as u8,
                },
            }
        );
    }

    #[test]
    fn ignored_stale_output_maps_event_sequence_to_wire_envelope() {
        let output = MdsOutput::IgnoredStale(VenuePipelineIgnoredStale {
            venue_id: venue(4),
            instrument_id: instrument(12),
            event_sequence: Some(66),
        });

        assert_eq!(
            output.wire_message(),
            MdsWireMessage::IgnoredStale {
                envelope: MdsWireEnvelope {
                    schema_id: MDS_WIRE_SCHEMA_ID,
                    schema_version: MDS_WIRE_SCHEMA_VERSION,
                    template_id: MdsWireTemplate::IgnoredStale as u16,
                    venue_id: 4,
                    instrument_id: 12,
                    sequence: 66,
                    timestamp_ns: 0,
                },
                payload: MdsIgnoredStaleWirePayload,
            }
        );
    }
}
