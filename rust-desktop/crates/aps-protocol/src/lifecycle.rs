use crate::{FrameLimits, Request};
use aps_contracts::{ProfilerSessionId, RequestId, SourceDigest};
use std::collections::BTreeSet;
use std::sync::{
    atomic::{AtomicU8, Ordering},
    Arc,
};

pub const MAX_SEEN_REQUEST_IDS: usize = 4096;
/// Caller-owned correlation/replay state. Accepting a request is not a grant of
/// operation permission, file access, credential access, or sandbox authority.
#[derive(Debug)]
pub struct GenerationGuard {
    session_id: ProfilerSessionId,
    source_digest: SourceDigest,
    generation: u64,
    seen: BTreeSet<RequestId>,
}
impl GenerationGuard {
    pub fn new(
        session_id: ProfilerSessionId,
        source_digest: SourceDigest,
        generation: u64,
    ) -> Result<Self, String> {
        if generation > i64::MAX as u64 {
            return Err("generation exceeds wire range".into());
        }
        Ok(Self {
            session_id,
            source_digest,
            generation,
            seen: BTreeSet::new(),
        })
    }
    pub fn generation(&self) -> u64 {
        self.generation
    }
    pub fn accept(&mut self, request: &Request) -> Result<(), String> {
        request.validate(FrameLimits::default())?;
        if request.session_id != self.session_id || request.source_digest != self.source_digest {
            return Err("request source/session identity mismatch".into());
        }
        if request.generation != self.generation {
            return Err("stale or unexpected request generation".into());
        }
        if self.seen.contains(&request.request_id) {
            return Err("request ID replay within generation".into());
        }
        if self.seen.len() >= MAX_SEEN_REQUEST_IDS {
            return Err("generation request budget exhausted; advance or close session".into());
        }
        self.seen.insert(request.request_id.clone());
        Ok(())
    }
    /// Caller must separately cancel/drain old workers. Moving correlation
    /// state forward does not stop any work or clean up external resources.
    pub fn advance(&mut self, generation: u64) -> Result<(), String> {
        if generation <= self.generation || generation > i64::MAX as u64 {
            return Err("generation must strictly advance within wire range".into());
        }
        self.generation = generation;
        self.seen.clear();
        Ok(())
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum CancellationPhase {
    Pending = 0,
    Running = 1,
    CancelRequested = 2,
    Cancelled = 3,
    Completed = 4,
    Failed = 5,
}
impl CancellationPhase {
    pub fn is_terminal(self) -> bool {
        matches!(self, Self::Cancelled | Self::Completed | Self::Failed)
    }
}
/// Per-request cooperative lifecycle. Cancellation is a request until the
/// performer acknowledges it; no field implies the OS process has exited.
#[derive(Debug, Clone)]
pub struct CancellationState(Arc<AtomicU8>);
impl Default for CancellationState {
    fn default() -> Self {
        Self::new()
    }
}
impl CancellationState {
    pub fn new() -> Self {
        Self(Arc::new(AtomicU8::new(CancellationPhase::Pending as u8)))
    }
    pub fn phase(&self) -> CancellationPhase {
        match self.0.load(Ordering::Acquire) {
            0 => CancellationPhase::Pending,
            1 => CancellationPhase::Running,
            2 => CancellationPhase::CancelRequested,
            3 => CancellationPhase::Cancelled,
            4 => CancellationPhase::Completed,
            _ => CancellationPhase::Failed,
        }
    }
    pub fn is_cancel_requested(&self) -> bool {
        matches!(
            self.phase(),
            CancellationPhase::CancelRequested | CancellationPhase::Cancelled
        )
    }
    pub fn start(&self) -> Result<(), String> {
        self.transition(CancellationPhase::Pending, CancellationPhase::Running)
    }
    pub fn request_cancel(&self) -> Result<(), String> {
        loop {
            let phase = self.phase();
            match phase {
                CancellationPhase::CancelRequested | CancellationPhase::Cancelled => return Ok(()),
                CancellationPhase::Completed | CancellationPhase::Failed => {
                    return Err("cannot cancel a terminal request".into())
                }
                _ => {
                    if self
                        .0
                        .compare_exchange(
                            phase as u8,
                            CancellationPhase::CancelRequested as u8,
                            Ordering::AcqRel,
                            Ordering::Acquire,
                        )
                        .is_ok()
                    {
                        return Ok(());
                    }
                }
            }
        }
    }
    pub fn acknowledge_cancelled(&self) -> Result<(), String> {
        self.transition(
            CancellationPhase::CancelRequested,
            CancellationPhase::Cancelled,
        )
    }
    pub fn complete(&self) -> Result<(), String> {
        self.transition(CancellationPhase::Running, CancellationPhase::Completed)
    }
    pub fn fail(&self) -> Result<(), String> {
        loop {
            let phase = self.phase();
            if phase.is_terminal() {
                return Err("request lifecycle is already terminal".into());
            }
            if self
                .0
                .compare_exchange(
                    phase as u8,
                    CancellationPhase::Failed as u8,
                    Ordering::AcqRel,
                    Ordering::Acquire,
                )
                .is_ok()
            {
                return Ok(());
            }
        }
    }
    fn transition(&self, from: CancellationPhase, to: CancellationPhase) -> Result<(), String> {
        self.0
            .compare_exchange(from as u8, to as u8, Ordering::AcqRel, Ordering::Acquire)
            .map(|_| ())
            .map_err(|_| "request lifecycle transition is not valid in the current state".into())
    }
}
