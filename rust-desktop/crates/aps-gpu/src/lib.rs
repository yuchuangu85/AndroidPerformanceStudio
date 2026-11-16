//! Read-only, content-verified GPU artifact indexing. Classification describes a
//! proposed open route, not parser support, AGI availability or launch authority.

mod analysis;
mod device;
mod store;
mod toolchain;

use aps_contracts::SourceDigest;
use aps_platform::CancellationToken;
use std::collections::BTreeSet;
use std::fmt;
use std::fs::{File, Metadata};
use std::io::{self, Read, Seek, SeekFrom};
use std::path::{Path, PathBuf};
use std::time::SystemTime;

pub use analysis::{
    GpuAnalysisError, GpuAnalysisPage, GpuAnalysisSummary, GpuFrequencySample, GpuMemorySample,
    GpuRenderStage, decode as decode_analysis_page, summarize as summarize_analysis,
};
pub use device::{GpuDeviceContext, GpuDeviceContextReader, parse as parse_device_context};
pub use store::{GpuArtifactStore, GpuStoreError};
pub use toolchain::{
    AgiCapability, AgiLaunchMode, AgiLocator, AgiPreferences, AgiToolchain, AgiToolchainError,
    WARNING_AGI_NOT_FOUND, WARNING_ARTIFACT_OPEN_UNVERIFIED, WARNING_GUI_ONLY, WARNING_HELP_FAILED,
    WARNING_HELP_TIMED_OUT, WARNING_PREFERENCE_NOT_SAVED, WARNING_VERSION_TIMED_OUT,
    WARNING_VERSION_UNKNOWN,
};

pub const MAX_ARTIFACT_BYTES: u64 = 8 * 1024 * 1024 * 1024;
pub const MAX_ARTIFACTS: usize = 10_000;
pub const MAX_LOCATIONS: usize = 64;
pub const MAX_PAGE_SIZE: usize = 200;
pub const MAX_LABEL_CHARS: usize = 512;
pub const MAX_WARNINGS: usize = 32;

/// Emitted when a file cannot be classified; the artifact is still indexed as
/// opaque evidence instead of being dropped or renamed.
pub const WARNING_UNKNOWN_ARTIFACT: &str =
    "Unknown artifact format; it is indexed as opaque evidence.";

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum ArtifactKind {
    AgiSystemProfile,
    AgiFrameProfile,
    PerfettoTrace,
    Screenshot,
    ExternalReport,
    Unknown,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum OpenRoute {
    Agi,
    Perfetto,
    Desktop,
    None,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LocationStatus {
    Available,
    Missing,
    SizeChanged,
    ContentChanged,
    Unreadable,
}

/// Graphics APIs observed on a device or recorded for an artifact.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum GraphicsApi {
    Vulkan,
    OpenGlEs,
    WebGpu,
}
impl GraphicsApi {
    pub const fn label(self) -> &'static str {
        match self {
            Self::Vulkan => "Vulkan",
            Self::OpenGlEs => "OpenGL ES",
            Self::WebGpu => "WebGPU",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum GpuError {
    InvalidFile,
    TooLarge,
    Unreadable,
    SourceChanged,
    SizeMismatch,
    DigestMismatch,
    Cancelled,
    IndexLimit,
    LocationLimit,
    InvalidPage,
    NotIndexed,
}
impl fmt::Display for GpuError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::InvalidFile => "Select a regular local GPU artifact",
            Self::TooLarge => "GPU artifact exceeds the 8 GiB limit",
            Self::Unreadable => "GPU artifact cannot be read",
            Self::SourceChanged => "GPU artifact changed during import",
            Self::SizeMismatch => "Selected file size differs from the indexed artifact",
            Self::DigestMismatch => "Selected file content differs from the indexed artifact",
            Self::Cancelled => "GPU artifact operation was cancelled",
            Self::IndexLimit => "GPU index exceeds the 10,000-artifact limit",
            Self::LocationLimit => "GPU artifact exceeds the 64-location limit",
            Self::InvalidPage => "GPU page must contain 1 to 200 artifacts",
            Self::NotIndexed => "GPU artifact is not in the catalog",
        })
    }
}
impl std::error::Error for GpuError {}

/// Capture-time identity supplied by an explicit capture workflow. The desktop
/// file import never supplies it: file modification time is not capture time.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct GpuCaptureContext {
    pub device: Option<GpuDeviceContext>,
    pub package_name: Option<String>,
    pub graphics_api: Option<GraphicsApi>,
    pub graphics_implementation: Option<String>,
    pub captured_at: Option<SystemTime>,
    pub warnings: Vec<String>,
}

/// Intentionally not Debug: locations can contain private host path components.
#[derive(Clone)]
pub struct GpuArtifact {
    pub kind: ArtifactKind,
    pub sha256: SourceDigest,
    pub size_bytes: u64,
    pub modified_at: Option<SystemTime>,
    pub open_route: OpenRoute,
    pub agi_version: Option<String>,
    pub device: Option<GpuDeviceContext>,
    pub package_name: Option<String>,
    pub graphics_api: Option<GraphicsApi>,
    pub graphics_implementation: Option<String>,
    pub captured_at: Option<SystemTime>,
    pub imported_at: Option<SystemTime>,
    pub notes: Option<String>,
    pub warnings: Vec<String>,
    locations: Vec<PathBuf>,
}

/// One decoded index row before metadata validation.
pub(crate) struct IndexedArtifact {
    pub kind: ArtifactKind,
    pub sha256: SourceDigest,
    pub size_bytes: u64,
    pub modified_at: Option<SystemTime>,
    pub open_route: OpenRoute,
    pub agi_version: Option<String>,
    pub device: Option<GpuDeviceContext>,
    pub package_name: Option<String>,
    pub graphics_api: Option<GraphicsApi>,
    pub graphics_implementation: Option<String>,
    pub captured_at: Option<SystemTime>,
    pub imported_at: Option<SystemTime>,
    pub notes: Option<String>,
    pub warnings: Vec<String>,
    pub locations: Vec<PathBuf>,
}

impl GpuArtifact {
    pub(crate) fn from_indexed(parts: IndexedArtifact) -> Result<Self, GpuError> {
        if parts.size_bytes > MAX_ARTIFACT_BYTES
            || parts.locations.is_empty()
            || parts.locations.len() > MAX_LOCATIONS
            || parts.locations.iter().any(|path| !path.is_absolute())
            || parts.locations.iter().collect::<BTreeSet<_>>().len() != parts.locations.len()
            || !metadata_bounded(&parts)
        {
            return Err(GpuError::InvalidFile);
        }
        Ok(Self {
            kind: parts.kind,
            sha256: parts.sha256,
            size_bytes: parts.size_bytes,
            modified_at: parts.modified_at,
            open_route: parts.open_route,
            agi_version: parts.agi_version,
            device: parts.device,
            package_name: parts.package_name,
            graphics_api: parts.graphics_api,
            graphics_implementation: parts.graphics_implementation,
            captured_at: parts.captured_at,
            imported_at: parts.imported_at,
            notes: parts.notes,
            warnings: parts.warnings,
            locations: parts.locations,
        })
    }
}
impl GpuArtifact {
    /// Blocking file scanning, no executable launch or device interaction.
    pub fn import(selected_file: &Path, cancel: &CancellationToken) -> Result<Self, GpuError> {
        Self::import_capture(selected_file, None, None, None, cancel)
    }

    /// Import with optional capture identity. A missing context leaves device,
    /// package, API and capture time unrecorded rather than inferring them.
    pub fn import_capture(
        selected_file: &Path,
        context: Option<&GpuCaptureContext>,
        agi_version: Option<&str>,
        notes: Option<&str>,
        cancel: &CancellationToken,
    ) -> Result<Self, GpuError> {
        check_cancel(cancel)?;
        let path = selected_file
            .canonicalize()
            .map_err(|_| GpuError::InvalidFile)?;
        let mut file = File::open(&path).map_err(|_| GpuError::Unreadable)?;
        let metadata = file.metadata().map_err(|_| GpuError::Unreadable)?;
        if !metadata.is_file() {
            return Err(GpuError::InvalidFile);
        }
        if metadata.len() > MAX_ARTIFACT_BYTES {
            return Err(GpuError::TooLarge);
        }
        // Classify the prefix actually consumed by the digest, not a separate
        // pathname open which may now point to replacement content.
        let (sha256, header) = hash_open_source(&path, &mut file, &metadata, cancel)?;
        let kind = classify(&path, &header);
        let mut warnings = context
            .map(|value| value.warnings.clone())
            .unwrap_or_default();
        if kind == ArtifactKind::Unknown {
            warnings.push(WARNING_UNKNOWN_ARTIFACT.into());
        }
        Ok(Self {
            kind,
            sha256,
            size_bytes: metadata.len(),
            modified_at: metadata.modified().ok(),
            open_route: match kind {
                ArtifactKind::AgiSystemProfile | ArtifactKind::AgiFrameProfile => OpenRoute::Agi,
                ArtifactKind::PerfettoTrace => OpenRoute::Perfetto,
                ArtifactKind::Screenshot | ArtifactKind::ExternalReport => OpenRoute::Desktop,
                ArtifactKind::Unknown => OpenRoute::None,
            },
            agi_version: normalize(agi_version.map(str::to_owned)),
            device: context.and_then(|value| value.device.clone()),
            package_name: context.and_then(|value| normalize(value.package_name.clone())),
            graphics_api: context.and_then(|value| value.graphics_api),
            graphics_implementation: context
                .and_then(|value| normalize(value.graphics_implementation.clone())),
            captured_at: context.and_then(|value| value.captured_at),
            imported_at: Some(SystemTime::now()),
            notes: normalize(notes.map(str::to_owned)),
            warnings: normalize_warnings(warnings),
            locations: vec![path],
        })
    }

    /// Only for an explicit local file-open workflow; never include in telemetry.
    pub fn locations(&self) -> &[PathBuf] {
        &self.locations
    }

    /// Verify all registered copies. A missing primary never hides a valid
    /// secondary. Availability requires both byte count AND SHA-256 identity.
    pub fn resolve_location(
        &self,
        cancel: &CancellationToken,
    ) -> Result<LocationResolution, GpuError> {
        let mut regular = false;
        let mut changed = false;
        let mut unreadable = false;
        for path in &self.locations {
            check_cancel(cancel)?;
            let mut file = match File::open(path) {
                Ok(file) => file,
                Err(error) if error.kind() == io::ErrorKind::NotFound => continue,
                Err(_) => {
                    unreadable = true;
                    continue;
                }
            };
            let metadata = match file.metadata() {
                Ok(metadata) => metadata,
                Err(_) => {
                    unreadable = true;
                    continue;
                }
            };
            if !metadata.is_file() {
                continue;
            }
            regular = true;
            if metadata.len() != self.size_bytes {
                continue;
            }
            let digest = match hash_open_source(path, &mut file, &metadata, cancel) {
                Ok((digest, _)) => digest,
                Err(GpuError::Cancelled) => return Err(GpuError::Cancelled),
                Err(GpuError::SourceChanged) => {
                    changed = true;
                    continue;
                }
                Err(_) => {
                    unreadable = true;
                    continue;
                }
            };
            check_cancel(cancel)?;
            if digest != self.sha256 {
                changed = true;
                continue;
            }
            return Ok(LocationResolution {
                status: LocationStatus::Available,
                path: Some(path.clone()),
            });
        }
        Ok(LocationResolution {
            status: if changed {
                LocationStatus::ContentChanged
            } else if unreadable {
                LocationStatus::Unreadable
            } else if regular {
                LocationStatus::SizeChanged
            } else {
                LocationStatus::Missing
            },
            path: None,
        })
    }

    /// Rebinding is authorized by an explicit selected path, never a search of
    /// arbitrary directories. Historical locations and content identity survive.
    pub fn relocate(
        &mut self,
        selected_copy: &Path,
        cancel: &CancellationToken,
    ) -> Result<(), GpuError> {
        let imported = Self::import(selected_copy, cancel)?;
        if imported.size_bytes != self.size_bytes {
            return Err(GpuError::SizeMismatch);
        }
        if imported.sha256 != self.sha256 {
            return Err(GpuError::DigestMismatch);
        }
        check_cancel(cancel)?;
        self.prepend_locations(imported.locations)
    }

    fn prepend_locations(&mut self, incoming: Vec<PathBuf>) -> Result<(), GpuError> {
        let mut merged = incoming;
        for location in &self.locations {
            if !merged.contains(location) {
                merged.push(location.clone());
            }
        }
        if merged.len() > MAX_LOCATIONS {
            return Err(GpuError::LocationLimit);
        }
        self.locations = merged;
        Ok(())
    }
}

pub struct LocationResolution {
    pub status: LocationStatus,
    pub path: Option<PathBuf>,
}

#[derive(Clone, Default)]
pub struct GpuArtifactIndex {
    artifacts: Vec<GpuArtifact>,
}
impl GpuArtifactIndex {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn len(&self) -> usize {
        self.artifacts.len()
    }
    pub fn is_empty(&self) -> bool {
        self.artifacts.is_empty()
    }

    pub fn artifacts(&self) -> &[GpuArtifact] {
        &self.artifacts
    }

    pub(crate) fn from_artifacts(artifacts: Vec<GpuArtifact>) -> Result<Self, GpuError> {
        if artifacts.len() > MAX_ARTIFACTS {
            return Err(GpuError::IndexLimit);
        }
        if artifacts
            .iter()
            .map(|artifact| artifact.sha256)
            .collect::<BTreeSet<_>>()
            .len()
            != artifacts.len()
        {
            return Err(GpuError::InvalidFile);
        }
        Ok(Self { artifacts })
    }

    /// Remove only the catalog entry. External evidence files are never deleted.
    pub fn remove(&mut self, digest: SourceDigest) -> bool {
        let old_len = self.artifacts.len();
        self.artifacts.retain(|artifact| artifact.sha256 != digest);
        self.artifacts.len() != old_len
    }

    /// Preserve catalog order and classification while rebinding verified content.
    pub fn relocate(
        &mut self,
        digest: SourceDigest,
        selected_copy: &Path,
        cancel: &CancellationToken,
    ) -> Result<(), GpuError> {
        self.artifacts
            .iter_mut()
            .find(|artifact| artifact.sha256 == digest)
            .ok_or(GpuError::NotIndexed)?
            .relocate(selected_copy, cancel)
    }

    /// Imports and merges duplicate content, preserving the original route/kind
    /// rather than trusting a renamed duplicate's new extension as new evidence.
    pub fn import(
        &mut self,
        selected_file: &Path,
        cancel: &CancellationToken,
    ) -> Result<SourceDigest, GpuError> {
        self.import_capture(selected_file, None, None, None, cancel)
    }

    /// Imports with optional capture identity. Duplicate content keeps the
    /// recorded identity and only gains a verified location and import time.
    pub fn import_capture(
        &mut self,
        selected_file: &Path,
        context: Option<&GpuCaptureContext>,
        agi_version: Option<&str>,
        notes: Option<&str>,
        cancel: &CancellationToken,
    ) -> Result<SourceDigest, GpuError> {
        let imported =
            GpuArtifact::import_capture(selected_file, context, agi_version, notes, cancel)?;
        check_cancel(cancel)?;
        let digest = imported.sha256;
        if let Some(existing) = self
            .artifacts
            .iter()
            .position(|value| value.sha256 == digest)
        {
            self.artifacts[existing].imported_at = imported.imported_at;
            self.artifacts[existing].prepend_locations(imported.locations)?;
            let artifact = self.artifacts.remove(existing);
            self.artifacts.insert(0, artifact);
        } else {
            if self.artifacts.len() >= MAX_ARTIFACTS {
                return Err(GpuError::IndexLimit);
            }
            self.artifacts.insert(0, imported);
        }
        Ok(digest)
    }
    pub fn page(&self, offset: usize, limit: usize) -> Result<&[GpuArtifact], GpuError> {
        if limit == 0 || limit > MAX_PAGE_SIZE || offset.checked_add(limit).is_none() {
            return Err(GpuError::InvalidPage);
        }
        let start = offset.min(self.artifacts.len());
        Ok(&self.artifacts[start..(start + limit).min(self.artifacts.len())])
    }
    pub fn get(&self, digest: SourceDigest) -> Option<&GpuArtifact> {
        self.artifacts
            .iter()
            .find(|artifact| artifact.sha256 == digest)
    }

    /// Artifacts sharing an explicitly recorded device serial or package name.
    /// Matching metadata never implies a shared timeline.
    pub fn related(&self, digest: SourceDigest) -> Vec<&GpuArtifact> {
        let Some(anchor) = self.get(digest) else {
            return Vec::new();
        };
        let serial = anchor
            .device
            .as_ref()
            .map(|device| device.serial.as_str())
            .filter(|serial| !serial.is_empty());
        let package = anchor
            .package_name
            .as_deref()
            .filter(|package| !package.is_empty());
        self.artifacts
            .iter()
            .filter(|candidate| candidate.sha256 != digest)
            .filter(|candidate| {
                serial.is_some_and(|serial| {
                    candidate
                        .device
                        .as_ref()
                        .is_some_and(|device| device.serial == serial)
                }) || package
                    .is_some_and(|package| candidate.package_name.as_deref() == Some(package))
            })
            .collect()
    }
}

fn normalize(value: Option<String>) -> Option<String> {
    value
        .map(|text| {
            text.trim()
                .chars()
                .take(MAX_LABEL_CHARS)
                .collect::<String>()
        })
        .filter(|text| !text.is_empty())
}

fn normalize_warnings(mut warnings: Vec<String>) -> Vec<String> {
    let mut seen = BTreeSet::new();
    warnings.retain(|warning| {
        let warning = warning.trim();
        !warning.is_empty()
            && seen.insert(warning.chars().take(MAX_LABEL_CHARS).collect::<String>())
    });
    warnings.truncate(MAX_WARNINGS);
    warnings
        .into_iter()
        .map(|warning| warning.trim().chars().take(MAX_LABEL_CHARS).collect())
        .collect()
}

fn bounded(value: &Option<String>) -> bool {
    value
        .as_deref()
        .is_none_or(|text| text.chars().count() <= MAX_LABEL_CHARS)
}

fn metadata_bounded(parts: &IndexedArtifact) -> bool {
    bounded(&parts.agi_version)
        && bounded(&parts.package_name)
        && bounded(&parts.graphics_implementation)
        && bounded(&parts.notes)
        && parts.warnings.len() <= MAX_WARNINGS
        && parts
            .warnings
            .iter()
            .all(|warning| !warning.trim().is_empty() && warning.chars().count() <= MAX_LABEL_CHARS)
        && parts.device.as_ref().is_none_or(|device| {
            device.serial.chars().count() <= MAX_LABEL_CHARS
                && bounded(&device.model)
                && bounded(&device.android_release)
                && bounded(&device.gpu_vendor)
                && bounded(&device.gpu_renderer)
                && bounded(&device.driver)
                && bounded(&device.open_gl_es_version)
                && bounded(&device.vulkan_version)
                && bounded(&device.graphics_implementation)
                && device.evidence.len() <= MAX_WARNINGS
        })
}

fn classify(path: &Path, header: &[u8]) -> ArtifactKind {
    let extension = path
        .extension()
        .and_then(|v| v.to_str())
        .unwrap_or("")
        .to_ascii_lowercase();
    match extension.as_str() {
        "perfetto-trace" | "pftrace" => ArtifactKind::PerfettoTrace,
        _ if header.windows(8).any(|bytes| bytes == b"PERFETTO") => ArtifactKind::PerfettoTrace,
        "gfxtrace" | "agi" => ArtifactKind::AgiFrameProfile,
        "trace" => ArtifactKind::AgiSystemProfile,
        "png" | "jpg" | "jpeg" => ArtifactKind::Screenshot,
        "html" | "pdf" => ArtifactKind::ExternalReport,
        _ => ArtifactKind::Unknown,
    }
}
fn check_cancel(cancel: &CancellationToken) -> Result<(), GpuError> {
    if cancel.is_cancelled() {
        Err(GpuError::Cancelled)
    } else {
        Ok(())
    }
}

/// Bind classification, digest and observed metadata to one descriptor. These
/// checks detect ordinary mutation/path replacement; they are not a sandbox or
/// a promise that a later external viewer reads the same bytes.
fn hash_open_source(
    path: &Path,
    file: &mut File,
    expected: &Metadata,
    cancel: &CancellationToken,
) -> Result<(SourceDigest, Vec<u8>), GpuError> {
    check_cancel(cancel)?;
    validate_open_source(path, file, expected)?;
    file.seek(SeekFrom::Start(0))
        .map_err(|_| GpuError::Unreadable)?;
    let mut reader = CheckedSourceReader {
        source: file,
        cancel,
        prefix: Vec::with_capacity(32),
    };
    let digest = SourceDigest::hash_reader(&mut reader, MAX_ARTIFACT_BYTES);
    check_cancel(cancel)?;
    validate_open_source(path, reader.source, expected)?;
    Ok((digest.map_err(|_| GpuError::Unreadable)?, reader.prefix))
}

fn validate_open_source(path: &Path, file: &File, expected: &Metadata) -> Result<(), GpuError> {
    let handle = file.metadata().map_err(|_| GpuError::SourceChanged)?;
    let path = std::fs::metadata(path).map_err(|_| GpuError::SourceChanged)?;
    if !same_source_metadata(expected, &handle) || !same_source_metadata(expected, &path) {
        return Err(GpuError::SourceChanged);
    }
    Ok(())
}

fn same_source_metadata(before: &Metadata, after: &Metadata) -> bool {
    if !after.is_file()
        || before.len() != after.len()
        || before.modified().ok() != after.modified().ok()
        || before.created().ok() != after.created().ok()
    {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if before.dev() != after.dev()
            || before.ino() != after.ino()
            || before.ctime() != after.ctime()
            || before.ctime_nsec() != after.ctime_nsec()
        {
            return false;
        }
    }
    true
}

struct CheckedSourceReader<'a, R> {
    source: R,
    cancel: &'a CancellationToken,
    prefix: Vec<u8>,
}
impl<R: Read> Read for CheckedSourceReader<'_, R> {
    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        if self.cancel.is_cancelled() {
            return Err(io::Error::other("GPU artifact scan cancelled"));
        }
        let limit = output.len().min(64 * 1024);
        let count = self.source.read(&mut output[..limit])?;
        if self.cancel.is_cancelled() {
            return Err(io::Error::other("GPU artifact scan cancelled"));
        }
        let prefix_count = count.min(32 - self.prefix.len());
        self.prefix.extend_from_slice(&output[..prefix_count]);
        Ok(count)
    }
}

#[cfg(test)]
#[path = "../tests/support/source_safety.rs"]
mod source_safety;
