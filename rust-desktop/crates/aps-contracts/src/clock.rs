use crate::{validate_text, ClockDomainId, MAX_METADATA_TEXT_BYTES};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClockKind {
    Monotonic,
    BootTime,
    Realtime,
    Trace,
    Unknown,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClockDomain {
    pub id: ClockDomainId,
    pub kind: ClockKind,
    /// E.g. a verified boot UUID or immutable trace identity. A kind alone does
    /// not establish that timestamps share an origin.
    pub origin_identity: String,
}
impl ClockDomain {
    pub fn new(
        id: ClockDomainId,
        kind: ClockKind,
        origin_identity: impl Into<String>,
    ) -> Result<Self, String> {
        let value = Self {
            id,
            kind,
            origin_identity: origin_identity.into(),
        };
        value.validate()?;
        Ok(value)
    }
    pub fn validate(&self) -> Result<(), String> {
        validate_text(
            &self.origin_identity,
            "clock origin identity",
            MAX_METADATA_TEXT_BYTES,
        )
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TimeRangeNanos {
    pub start: u64,
    pub end: u64,
}
impl TimeRangeNanos {
    pub fn new(start: u64, end: u64) -> Result<Self, String> {
        if start >= end {
            return Err("time range must satisfy start < end ([start,end))".into());
        }
        Ok(Self { start, end })
    }
    pub fn contains(&self, timestamp: u64) -> bool {
        timestamp >= self.start && timestamp < self.end
    }
    pub fn validate(&self) -> Result<(), String> {
        Self::new(self.start, self.end).map(|_| ())
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MappedTimestamp {
    pub estimate_nanos: u64,
    pub earliest_nanos: u64,
    pub latest_nanos: u64,
}
/// Explicit equal-rate mapping valid only for one source-domain interval. A
/// drift-sensitive relation needs multiple mappings/shorter intervals, not an
/// implicit extrapolation beyond this validity range.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ClockMapping {
    pub source: ClockDomainId,
    pub target: ClockDomainId,
    pub valid_source_range: TimeRangeNanos,
    pub source_anchor_nanos: u64,
    pub target_anchor_nanos: u64,
    pub max_error_nanos: u64,
}
impl ClockMapping {
    pub fn new(
        source: ClockDomainId,
        target: ClockDomainId,
        valid_source_range: TimeRangeNanos,
        source_anchor_nanos: u64,
        target_anchor_nanos: u64,
        max_error_nanos: u64,
    ) -> Result<Self, String> {
        let value = Self {
            source,
            target,
            valid_source_range,
            source_anchor_nanos,
            target_anchor_nanos,
            max_error_nanos,
        };
        value.validate()?;
        Ok(value)
    }
    pub fn validate(&self) -> Result<(), String> {
        self.valid_source_range.validate()?;
        if self.source == self.target {
            return Err("clock mapping must connect different domain identities".into());
        }
        if !self.valid_source_range.contains(self.source_anchor_nanos) {
            return Err("clock mapping source anchor is outside validity range".into());
        }
        Ok(())
    }
    pub fn map(
        &self,
        source_domain: &ClockDomainId,
        timestamp: u64,
    ) -> Result<MappedTimestamp, String> {
        self.validate()?;
        if source_domain != &self.source {
            return Err("timestamp clock domain does not match mapping source".into());
        }
        if !self.valid_source_range.contains(timestamp) {
            return Err("timestamp outside clock mapping validity range".into());
        }
        let estimate = i128::from(self.target_anchor_nanos) + i128::from(timestamp)
            - i128::from(self.source_anchor_nanos);
        let estimate_nanos = u64::try_from(estimate).map_err(|_| "mapped timestamp exceeds u64")?;
        let earliest_nanos = estimate_nanos
            .checked_sub(self.max_error_nanos)
            .ok_or("clock uncertainty underflows timestamp domain")?;
        let latest_nanos = estimate_nanos
            .checked_add(self.max_error_nanos)
            .ok_or("clock uncertainty exceeds timestamp domain")?;
        Ok(MappedTimestamp {
            estimate_nanos,
            earliest_nanos,
            latest_nanos,
        })
    }
}
