//! Shared primitive domain types for Rust Bedrock.
//!
//! This crate owns fixed-point value wrappers, timestamp and sequence wrappers,
//! venue/instrument identity wrappers, and shared side/level primitives. It does
//! not own market-data reconstruction, transport behavior, pricing, OMS, risk,
//! execution, or monitoring logic.

pub const SCALE: i64 = 100_000_000;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ValueError {
    NonPositivePrice(i64),
    NegativeQuantity(i64),
    ZeroTimestamp,
    ZeroSequence,
    ZeroInstrumentId,
    ZeroVenueId,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Price(i64);

impl Price {
    pub fn new(raw: i64) -> Result<Self, ValueError> {
        if raw <= 0 {
            return Err(ValueError::NonPositivePrice(raw));
        }
        Ok(Self(raw))
    }

    pub fn raw(self) -> i64 {
        self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Quantity(i64);

impl Quantity {
    pub fn new(raw: i64) -> Result<Self, ValueError> {
        if raw < 0 {
            return Err(ValueError::NegativeQuantity(raw));
        }
        Ok(Self(raw))
    }

    pub fn raw(self) -> i64 {
        self.0
    }

    pub fn is_zero(self) -> bool {
        self.0 == 0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct TimestampNs(u64);

impl TimestampNs {
    pub fn new(raw: u64) -> Result<Self, ValueError> {
        if raw == 0 {
            return Err(ValueError::ZeroTimestamp);
        }
        Ok(Self(raw))
    }

    pub fn raw(self) -> u64 {
        self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct Sequence(u64);

impl Sequence {
    pub fn new(raw: u64) -> Result<Self, ValueError> {
        if raw == 0 {
            return Err(ValueError::ZeroSequence);
        }
        Ok(Self(raw))
    }

    pub fn raw(self) -> u64 {
        self.0
    }

    pub fn next(self) -> Self {
        Self(self.0 + 1)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct InstrumentId(u32);

impl InstrumentId {
    pub fn new(raw: u32) -> Result<Self, ValueError> {
        if raw == 0 {
            return Err(ValueError::ZeroInstrumentId);
        }
        Ok(Self(raw))
    }

    pub fn raw(self) -> u32 {
        self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct VenueId(u16);

impl VenueId {
    pub fn new(raw: u16) -> Result<Self, ValueError> {
        if raw == 0 {
            return Err(ValueError::ZeroVenueId);
        }
        Ok(Self(raw))
    }

    pub fn raw(self) -> u16 {
        self.0
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Side {
    Bid,
    Ask,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Level {
    pub side: Side,
    pub price: Price,
    pub quantity: Quantity,
}

impl Level {
    pub fn new(side: Side, price: Price, quantity: Quantity) -> Self {
        Self {
            side,
            price,
            quantity,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_price() {
        assert_eq!(Price::new(1).unwrap().raw(), 1);
        assert_eq!(Price::new(0), Err(ValueError::NonPositivePrice(0)));
        assert_eq!(Price::new(-1), Err(ValueError::NonPositivePrice(-1)));
    }

    #[test]
    fn validates_quantity() {
        assert_eq!(Quantity::new(0).unwrap().raw(), 0);
        assert_eq!(Quantity::new(7).unwrap().raw(), 7);
        assert_eq!(Quantity::new(-1), Err(ValueError::NegativeQuantity(-1)));
    }

    #[test]
    fn validates_ids_and_time() {
        assert_eq!(TimestampNs::new(0), Err(ValueError::ZeroTimestamp));
        assert_eq!(Sequence::new(0), Err(ValueError::ZeroSequence));
        assert_eq!(InstrumentId::new(0), Err(ValueError::ZeroInstrumentId));
        assert_eq!(VenueId::new(0), Err(ValueError::ZeroVenueId));
        assert_eq!(Sequence::new(41).unwrap().next().raw(), 42);
    }
}
