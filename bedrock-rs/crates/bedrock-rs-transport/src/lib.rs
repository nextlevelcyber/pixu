//! Transport-neutral publisher/subscriber contracts for Rust Bedrock.
//!
//! This crate owns transport traits, transport semantics descriptors, and the
//! first in-process bounded channel. It does not own market-data semantics,
//! pricing, OMS, Aeron bindings, serialization, or deployment configuration.

use std::collections::VecDeque;

pub trait Publisher<T> {
    fn publish(&mut self, event: T) -> Result<(), PublishError>;
}

pub trait Subscriber<T> {
    fn poll(&mut self) -> Result<Option<T>, PollError>;
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TransportMode {
    InProc,
    AeronIpc,
    AeronUdp,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OrderingGuarantee {
    FifoPerPublisher,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BackpressurePolicy {
    RejectWhenFull,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FailureSemantics {
    ExplicitClose,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TransportSemantics {
    pub mode: TransportMode,
    pub ordering: OrderingGuarantee,
    pub backpressure: BackpressurePolicy,
    pub failure: FailureSemantics,
    pub non_blocking: bool,
}

impl TransportSemantics {
    pub const fn in_proc() -> Self {
        Self {
            mode: TransportMode::InProc,
            ordering: OrderingGuarantee::FifoPerPublisher,
            backpressure: BackpressurePolicy::RejectWhenFull,
            failure: FailureSemantics::ExplicitClose,
            non_blocking: true,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChannelError {
    ZeroCapacity,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PublishError {
    Backpressure,
    Closed,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PollError {
    Closed,
}

pub struct InProcChannel<T> {
    capacity: usize,
    queue: VecDeque<T>,
    closed: bool,
}

impl<T> InProcChannel<T> {
    pub fn with_capacity(capacity: usize) -> Result<Self, ChannelError> {
        if capacity == 0 {
            return Err(ChannelError::ZeroCapacity);
        }
        Ok(Self {
            capacity,
            queue: VecDeque::with_capacity(capacity),
            closed: false,
        })
    }

    pub fn semantics(&self) -> TransportSemantics {
        TransportSemantics::in_proc()
    }

    pub fn len(&self) -> usize {
        self.queue.len()
    }

    pub fn is_empty(&self) -> bool {
        self.queue.is_empty()
    }

    pub fn is_closed(&self) -> bool {
        self.closed
    }

    pub fn close(&mut self) {
        self.closed = true;
    }
}

impl<T> Publisher<T> for InProcChannel<T> {
    fn publish(&mut self, event: T) -> Result<(), PublishError> {
        if self.closed {
            return Err(PublishError::Closed);
        }
        if self.queue.len() == self.capacity {
            return Err(PublishError::Backpressure);
        }
        self.queue.push_back(event);
        Ok(())
    }
}

impl<T> Subscriber<T> for InProcChannel<T> {
    fn poll(&mut self) -> Result<Option<T>, PollError> {
        if let Some(event) = self.queue.pop_front() {
            return Ok(Some(event));
        }
        if self.closed {
            return Err(PollError::Closed);
        }
        Ok(None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_zero_capacity() {
        assert_eq!(
            InProcChannel::<u64>::with_capacity(0).err(),
            Some(ChannelError::ZeroCapacity)
        );
    }

    #[test]
    fn publishes_and_polls_in_order() {
        let mut channel = InProcChannel::with_capacity(2).unwrap();
        channel.publish(10).unwrap();
        channel.publish(20).unwrap();
        assert_eq!(channel.poll().unwrap(), Some(10));
        assert_eq!(channel.poll().unwrap(), Some(20));
        assert_eq!(channel.poll().unwrap(), None);
    }

    #[test]
    fn reports_backpressure_when_full() {
        let mut channel = InProcChannel::with_capacity(1).unwrap();
        channel.publish(10).unwrap();
        assert_eq!(channel.publish(20), Err(PublishError::Backpressure));
        assert_eq!(channel.poll().unwrap(), Some(10));
        channel.publish(30).unwrap();
        assert_eq!(channel.poll().unwrap(), Some(30));
    }

    #[test]
    fn reports_in_proc_semantics() {
        let channel = InProcChannel::<u64>::with_capacity(1).unwrap();
        assert_eq!(
            channel.semantics(),
            TransportSemantics {
                mode: TransportMode::InProc,
                ordering: OrderingGuarantee::FifoPerPublisher,
                backpressure: BackpressurePolicy::RejectWhenFull,
                failure: FailureSemantics::ExplicitClose,
                non_blocking: true,
            }
        );
    }

    #[test]
    fn closed_channel_rejects_publish() {
        let mut channel = InProcChannel::with_capacity(1).unwrap();
        channel.close();
        assert!(channel.is_closed());
        assert_eq!(channel.publish(10), Err(PublishError::Closed));
    }

    #[test]
    fn closed_channel_drains_before_reporting_closed() {
        let mut channel = InProcChannel::with_capacity(2).unwrap();
        channel.publish(10).unwrap();
        channel.publish(20).unwrap();
        channel.close();

        assert_eq!(channel.poll().unwrap(), Some(10));
        assert_eq!(channel.poll().unwrap(), Some(20));
        assert_eq!(channel.poll(), Err(PollError::Closed));
    }
}
