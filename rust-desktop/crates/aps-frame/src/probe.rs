//! Pure, bounded decoding of probe overlap facts stored with a frame. Decoding
//! does not certify their provenance; unbound readers must discard probe state.
use crate::{FrameSample, FrameSource};

pub const PER_FRAME: usize = 8;
pub fn valid_marker(name: &str) -> bool {
    name.strip_prefix("APS.Frame/").is_some_and(|id| {
        id.len() == 16 && id.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
    })
}

/// Only fixed roles may leave the trace; never attach arbitrary thread names.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ThreadRole {
    Main,
    RenderThread,
    Other,
}
impl ThreadRole {
    pub fn label(self) -> &'static str {
        match self {
            Self::Main => "Main",
            Self::RenderThread => "RenderThread",
            Self::Other => "Other",
        }
    }
    pub fn parse(role: &str) -> Option<Self> {
        match role {
            "Main" => Some(Self::Main),
            "RenderThread" => Some(Self::RenderThread),
            "Other" => Some(Self::Other),
            _ => None,
        }
    }
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Probe {
    pub marker: String,
    pub tid: i64,
    pub role: ThreadRole,
    pub overlap_ns: i64,
    /// Observed scheduled time only; no scheduling evidence is not zero.
    pub scheduled_ns: Option<i64>,
}

pub fn probes(frame: &FrameSample) -> Vec<Probe> {
    if frame.source != FrameSource::Perfetto {
        return Vec::new();
    }
    (0..PER_FRAME)
        .filter_map(|i| {
            let raw = frame.states.get(&format!("aps.trace.{i}"))?;
            let mut fields = raw.split(' ');
            let marker = fields.next()?;
            let tid = fields.next()?.parse().ok()?;
            let overlap_ns = fields.next()?.parse().ok()?;
            let running: i64 = fields.next()?.parse().ok()?;
            let role = ThreadRole::parse(fields.next()?)?;
            if fields.next().is_some()
                || !valid_marker(marker)
                || tid <= 0
                || !(1..=frame.resolved_duration_ns()?).contains(&overlap_ns)
                || !(-1..=overlap_ns).contains(&running)
            {
                return None;
            }
            Some(Probe {
                marker: marker.to_owned(),
                role,
                tid,
                overlap_ns,
                scheduled_ns: (running >= 0).then_some(running),
            })
        })
        .collect()
}
