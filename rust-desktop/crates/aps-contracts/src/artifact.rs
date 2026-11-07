use crate::{
    validate_text, ArtifactId, CapabilityId, ClockDomain, DeviceTargetId, ProcessIdentity,
    ProfilerSessionId, SourceDigest, MAX_CAPABILITIES, MAX_METADATA_TEXT_BYTES,
    MAX_SESSION_ARTIFACTS,
};
use std::collections::BTreeSet;

/// Source information capabilities, not permission grants and not evidence that
/// an individual capture retained all requested data.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Capabilities(BTreeSet<CapabilityId>);
impl Capabilities {
    pub fn new(values: impl IntoIterator<Item = CapabilityId>) -> Result<Self, String> {
        let mut set = BTreeSet::new();
        for value in values {
            set.insert(value);
            if set.len() > MAX_CAPABILITIES {
                return Err("capability set exceeds 256 entries".into());
            }
        }
        Ok(Self(set))
    }
    pub fn contains(&self, id: &CapabilityId) -> bool {
        self.0.contains(id)
    }
    pub fn iter(&self) -> impl Iterator<Item = &CapabilityId> {
        self.0.iter()
    }
    pub fn len(&self) -> usize {
        self.0.len()
    }
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
    pub fn is_subset(&self, other: &Self) -> bool {
        self.0.is_subset(&other.0)
    }
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MissingCapability {
    pub capability: CapabilityId,
    pub reason: String,
}
impl MissingCapability {
    pub fn new(capability: CapabilityId, reason: impl Into<String>) -> Result<Self, String> {
        let value = Self {
            capability,
            reason: reason.into(),
        };
        value.validate()?;
        Ok(value)
    }
    pub fn validate(&self) -> Result<(), String> {
        validate_text(
            &self.reason,
            "missing capability reason",
            MAX_METADATA_TEXT_BYTES,
        )
    }
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ArtifactCompleteness {
    Unknown {
        reason: String,
    },
    Complete {
        requested: Capabilities,
    },
    Partial {
        requested: Capabilities,
        retained: Capabilities,
        missing: Vec<MissingCapability>,
    },
}
impl ArtifactCompleteness {
    pub fn unknown(reason: impl Into<String>) -> Result<Self, String> {
        let value = Self::Unknown {
            reason: reason.into(),
        };
        value.validate()?;
        Ok(value)
    }
    pub fn validate(&self) -> Result<(), String> {
        match self {
            Self::Unknown { reason } => validate_text(
                reason,
                "unknown completeness reason",
                MAX_METADATA_TEXT_BYTES,
            ),
            Self::Complete { requested } => {
                if requested.is_empty() {
                    Err("complete artifact must name its requested capabilities".into())
                } else {
                    Ok(())
                }
            }
            Self::Partial {
                requested,
                retained,
                missing,
            } => {
                if requested.is_empty()
                    || !retained.is_subset(requested)
                    || missing.is_empty()
                    || missing.len() > MAX_CAPABILITIES
                {
                    return Err("partial completeness requires requested capabilities, a retained subset and bounded missing reasons".into());
                }
                let mut expected: BTreeSet<_> = requested
                    .iter()
                    .filter(|id| !retained.contains(id))
                    .cloned()
                    .collect();
                for item in missing {
                    item.validate()?;
                    if !expected.remove(&item.capability) {
                        return Err(
                            "missing capability is duplicated or not missing from requested set"
                                .into(),
                        );
                    }
                }
                if !expected.is_empty() {
                    return Err("each missing requested capability must have a reason".into());
                }
                Ok(())
            }
        }
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProvenanceAction {
    Capture,
    Import,
    Convert,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProvenanceStep {
    pub action: ProvenanceAction,
    pub tool: String,
    pub input_digest: Option<SourceDigest>,
    pub output_digest: SourceDigest,
}
impl ProvenanceStep {
    pub fn new(
        action: ProvenanceAction,
        tool: impl Into<String>,
        input_digest: Option<SourceDigest>,
        output_digest: SourceDigest,
    ) -> Result<Self, String> {
        let value = Self {
            action,
            tool: tool.into(),
            input_digest,
            output_digest,
        };
        value.validate()?;
        Ok(value)
    }
    pub fn validate(&self) -> Result<(), String> {
        validate_text(&self.tool, "provenance tool", MAX_METADATA_TEXT_BYTES)?;
        if self.action == ProvenanceAction::Import && self.input_digest != Some(self.output_digest)
        {
            return Err("import must preserve input content digest".into());
        }
        if self.action == ProvenanceAction::Convert && self.input_digest.is_none() {
            return Err("conversion must identify its input digest".into());
        }
        Ok(())
    }
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Provenance {
    /// Actual original producer, never replaced by the importing application.
    pub producer: String,
    pub steps: Vec<ProvenanceStep>,
}
impl Provenance {
    pub fn new(producer: impl Into<String>, steps: Vec<ProvenanceStep>) -> Result<Self, String> {
        let value = Self {
            producer: producer.into(),
            steps,
        };
        value.validate()?;
        Ok(value)
    }
    pub fn validate(&self) -> Result<(), String> {
        validate_text(&self.producer, "original producer", MAX_METADATA_TEXT_BYTES)?;
        if self.steps.is_empty() || self.steps.len() > 64 {
            return Err("provenance requires 1..64 explicit steps".into());
        }
        let mut previous = None;
        for step in &self.steps {
            step.validate()?;
            if previous.is_some() && step.input_digest != previous {
                return Err("provenance digest chain is broken".into());
            }
            previous = Some(step.output_digest);
        }
        Ok(())
    }
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CaptureArtifact {
    pub id: ArtifactId,
    pub source_digest: SourceDigest,
    pub byte_length: u64,
    pub media_type: String,
    pub provenance: Provenance,
    pub capabilities: Capabilities,
    pub completeness: ArtifactCompleteness,
    pub clock: ClockDomain,
    pub device: Option<DeviceTargetId>,
    pub process: Option<ProcessIdentity>,
}
impl CaptureArtifact {
    pub fn validate(&self) -> Result<(), String> {
        validate_text(&self.media_type, "artifact media type", 256)?;
        self.provenance.validate()?;
        self.completeness.validate()?;
        self.clock.validate()?;
        if self.provenance.steps.last().map(|step| step.output_digest) != Some(self.source_digest) {
            return Err("artifact digest does not match final provenance output".into());
        }
        match &self.completeness {
            ArtifactCompleteness::Complete { requested }
                if !requested.is_subset(&self.capabilities) =>
            {
                return Err("complete artifact requests unsupported source capabilities".into())
            }
            ArtifactCompleteness::Partial { retained, .. }
                if !retained.is_subset(&self.capabilities) =>
            {
                return Err("artifact retains unsupported source capabilities".into())
            }
            _ => {}
        }
        if let Some(process) = &self.process {
            process.validate()?;
            if self.device.as_ref() != Some(&process.device) {
                return Err("artifact process must match its explicit device target".into());
            }
        }
        Ok(())
    }
    pub fn new(value: Self) -> Result<Self, String> {
        value.validate()?;
        Ok(value)
    }
}
/// Cross-feature association of immutable artifacts, not a unified profiler
/// business-data model and not a device connection/capture lifecycle.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProfilerSession {
    pub id: ProfilerSessionId,
    pub artifacts: Vec<ArtifactId>,
    pub device: Option<DeviceTargetId>,
    pub process: Option<ProcessIdentity>,
}
impl ProfilerSession {
    pub fn new(id: ProfilerSessionId) -> Self {
        Self {
            id,
            artifacts: Vec::new(),
            device: None,
            process: None,
        }
    }
    pub fn validate(&self) -> Result<(), String> {
        if self.artifacts.len() > MAX_SESSION_ARTIFACTS {
            return Err("profiler session exceeds artifact reference limit".into());
        }
        let unique: BTreeSet<_> = self.artifacts.iter().collect();
        if unique.len() != self.artifacts.len() {
            return Err("profiler session repeats an artifact identity".into());
        }
        if let Some(process) = &self.process {
            process.validate()?;
            if self.device.as_ref() != Some(&process.device) {
                return Err("profiler session process does not match device target".into());
            }
        }
        Ok(())
    }
}
