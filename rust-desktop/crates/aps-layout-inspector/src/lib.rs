//! Read-only native Inspector capture archive import. ZIP/manifest integrity is
//! checked before publishing a frame; no ADB, Agent injection or runtime capture.

use aps_contracts::SourceDigest;
use aps_json::{Limits, Value};
use aps_platform::CancellationToken;
use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::fs::File;
use std::io::{Cursor, Read, Seek, SeekFrom};
use std::path::Path;
use zip::{CompressionMethod, ZipArchive};

mod compatible;
mod compose_bridge;
mod protocol;
pub use compatible::{CompatibleTarget, capture_compatible};
pub use compose_bridge::ComposeBridge;
pub use protocol::{InspectorProtocolFrame, InspectorProtocolSession};

const MANIFEST: &str = "manifest.json";
const SNAPSHOT: &str = "capture/layout-snapshot.json";
const COMPOSE: &str = "capture/compose-inspection.json";
const SCREENSHOT: &str = "capture/screenshot.png";
const MAX_ARCHIVE: u64 = 172 * 1024 * 1024;
const MAX_TOTAL: u64 = 156 * 1024 * 1024;
const MAX_NODES: usize = 100_000;
pub const MAX_PAGE_SIZE: usize = 200;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum InspectorError {
    Input,
    Cancelled,
    InvalidZip,
    Limit,
    InvalidManifest,
    Integrity,
    UnsupportedVersion,
    InvalidSnapshot,
    FrameMismatch,
    InvalidPage,
    WindowMissing,
}
impl fmt::Display for InspectorError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Input => "Cannot read selected Inspector archive",
            Self::Cancelled => "Inspector import was cancelled",
            Self::InvalidZip => "Inspector archive ZIP is invalid or unsafe",
            Self::Limit => "Inspector archive exceeds a bounded input limit",
            Self::InvalidManifest => "Inspector archive manifest is invalid or unsupported",
            Self::Integrity => "Inspector archive integrity verification failed",
            Self::UnsupportedVersion => "Inspector protocol or archive version is unsupported",
            Self::InvalidSnapshot => "Inspector snapshot structure is invalid",
            Self::FrameMismatch => "Inspector archive components do not belong to the same frame",
            Self::InvalidPage => "Inspector page must contain 1 to 200 nodes",
            Self::WindowMissing => "Inspector window is unavailable",
        })
    }
}
impl std::error::Error for InspectorError {}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FrameIdentity {
    pub source_digest: SourceDigest,
    pub package_name: String,
    pub captured_at_epoch_millis: i64,
    pub protocol_major: u32,
    pub protocol_minor: u32,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Bounds {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct EdgeInsets {
    pub left: i32,
    pub top: i32,
    pub right: i32,
    pub bottom: i32,
}
/// Bounded structural projection of the protocol `ViewAttributes` plus Compose
/// semantic values. Optional fields stay `None` when the producer did not
/// capture them so the properties pane can render Compose's "—" rows.
#[derive(Clone, Debug, Default)]
pub struct ViewAttributes {
    pub visibility: Option<String>,
    pub layout_bounds: Option<Bounds>,
    pub elevation: Option<f32>,
    pub z: Option<f32>,
    pub translation_x: Option<f32>,
    pub translation_y: Option<f32>,
    pub translation_z: Option<f32>,
    pub rotation: Option<f32>,
    pub rotation_x: Option<f32>,
    pub rotation_y: Option<f32>,
    pub scale_x: Option<f32>,
    pub scale_y: Option<f32>,
    pub pivot_x: Option<f32>,
    pub pivot_y: Option<f32>,
    pub padding: Option<EdgeInsets>,
    pub margin: Option<EdgeInsets>,
    pub layout_width: Option<i32>,
    pub layout_height: Option<i32>,
    pub layout_params_class: Option<String>,
    pub measured_width: Option<i32>,
    pub measured_height: Option<i32>,
    pub min_width: Option<i32>,
    pub min_height: Option<i32>,
    pub scroll_x: Option<i32>,
    pub scroll_y: Option<i32>,
    pub clip_bounds: Option<Bounds>,
    pub clip_children: Option<bool>,
    pub clip_to_padding: Option<bool>,
    pub background: Option<String>,
    pub background_color: Option<String>,
    pub foreground: Option<String>,
    pub opaque: Option<bool>,
    pub will_not_draw: Option<bool>,
    pub hardware_accelerated: Option<bool>,
    pub layer_type: Option<String>,
    pub layout_requested: Option<bool>,
    pub enabled: Option<bool>,
    pub clickable: Option<bool>,
    pub long_clickable: Option<bool>,
    pub focusable: Option<bool>,
    pub focused: Option<bool>,
    pub selected: Option<bool>,
    pub content_description: Option<String>,
    /// Sorted runtime properties; only retained while the archive-wide raw
    /// property budget allows it, so the section can be absent on large captures.
    pub raw_properties: std::collections::BTreeMap<String, String>,
    /// Compose `semanticProperties`; merged into raw properties for display.
    pub semantic_properties: std::collections::BTreeMap<String, String>,
}
#[derive(Clone, Copy, Debug)]
pub struct DisplayInfo {
    pub width_px: u32,
    pub height_px: u32,
    pub density: f64,
}
#[derive(Clone, Copy, Debug)]
pub struct Capabilities {
    pub view_hierarchy: bool,
    pub compose_semantics: bool,
    pub screenshots: bool,
    pub timeline: bool,
}
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NodeKind {
    View,
    Compose,
}
#[derive(Clone, Debug)]
pub struct LayoutNode {
    pub id: String,
    pub kind: NodeKind,
    pub class_name: String,
    pub parent_index: Option<usize>,
    pub depth: usize,
    pub bounds: Bounds,
    pub visible: bool,
    pub alpha: f64,
    pub resource_name: Option<String>,
    /// Bounded runtime text for the properties pane; `None` when the producer
    /// omitted it or the value exceeded the retention limit.
    pub text: Option<String>,
    pub semantics_role: Option<String>,
    pub attributes: ViewAttributes,
}
#[derive(Clone, Debug)]
pub struct LayoutWindow {
    pub id: String,
    pub kind: String,
    pub bounds: Bounds,
    pub nodes: Vec<LayoutNode>,
}
#[derive(Clone, Debug)]
pub struct ScreenshotEvidence {
    pub sha256: SourceDigest,
    pub size_bytes: u64,
}
#[derive(Clone, Copy, Debug)]
pub struct CanvasModel {
    pub display: DisplayInfo,
    pub window_bounds: Bounds,
    pub screenshot_available: bool,
}
#[derive(Clone, Debug)]
pub struct ComposeCapability {
    pub capability: String,
    pub availability: String,
}
#[derive(Clone, Debug)]
pub struct ComposeCoverage {
    pub node_id: i64,
    pub field: String,
    pub state: String,
    pub recursion_depth: u32,
    pub loaded_elements: u32,
    pub total_elements: Option<u32>,
}
#[derive(Clone, Debug)]
pub struct ComposeEvidence {
    pub frame_id: String,
    pub generation: u32,
    pub mode: String,
    pub privacy: String,
    /// Producer declaration, not a verified assertion that details are complete.
    pub declared_completeness: String,
    pub capabilities: Vec<ComposeCapability>,
    pub coverage: Vec<ComposeCoverage>,
    pub coverage_record_count: usize,
    pub truncation_count: usize,
    pub details_available: bool,
    /// Bounded per-node Compose projection parsed from the archive sidecar while
    /// the Compose Agent toggle is off; the properties pane only shows it when
    /// the toggle is on.
    pub nodes: std::collections::BTreeMap<i64, ComposeNodeEvidence>,
    pub details: std::collections::BTreeMap<i64, ComposeDetailEvidence>,
    pub state_reads: std::collections::BTreeMap<i32, Vec<ComposeStateReadEvidence>>,
}

#[derive(Clone, Debug)]
pub struct ComposeSourceLocation {
    pub package_hash: i32,
    pub file_name: String,
    pub line_number: u32,
    pub offset: u32,
}

#[derive(Clone, Debug)]
pub struct ComposeNodeEvidence {
    pub node_id: i64,
    pub anchor_hash: i32,
    pub name: String,
    pub source: Option<ComposeSourceLocation>,
    pub recompose_count: Option<u32>,
    pub skip_count: Option<u32>,
    pub system_created: bool,
    pub flags: Vec<String>,
}

#[derive(Clone, Debug)]
pub struct ComposeValueEvidence {
    pub name: String,
    pub type_name: String,
    pub value: Option<String>,
    pub truncated: bool,
    pub elements: Vec<ComposeValueEvidence>,
}

#[derive(Clone, Debug, Default)]
pub struct ComposeDetailEvidence {
    pub parameters: Vec<ComposeValueEvidence>,
    pub modifiers: Vec<ComposeValueEvidence>,
    pub merged_semantics: Vec<ComposeValueEvidence>,
    pub unmerged_semantics: Vec<ComposeValueEvidence>,
}

#[derive(Clone, Debug)]
pub struct ComposeStateReadEvidence {
    pub recomposition_number: i32,
    pub value_name: Option<String>,
    pub value_text: Option<String>,
    pub invalidated: bool,
    pub top_frame: Option<String>,
}

#[derive(Clone, Debug)]
pub struct InspectorArchive {
    pub archive_version: u32,
    pub frame: FrameIdentity,
    pub display: DisplayInfo,
    pub capabilities: Capabilities,
    pub windows: Vec<LayoutWindow>,
    pub default_window_id: String,
    pub screenshot: Option<ScreenshotEvidence>,
    /// Integrity-validated PNG bytes for local display (never decoded by the
    /// adapter). `None` when the archive has no screenshot entry.
    pub screenshot_png: Option<Vec<u8>>,
    pub compose: Option<ComposeEvidence>,
}

#[derive(Clone)]
struct Entry {
    name: String,
    size: u64,
    digest: SourceDigest,
    required: bool,
}

impl InspectorArchive {
    pub fn open(selected_zip: &Path, cancel: &CancellationToken) -> Result<Self, InspectorError> {
        check_cancel(cancel)?;
        let mut file = File::open(selected_zip).map_err(|_| InspectorError::Input)?;
        let meta = file.metadata().map_err(|_| InspectorError::Input)?;
        if !meta.is_file() || meta.len() > MAX_ARCHIVE {
            return Err(InspectorError::Limit);
        }
        // Freeze one bounded byte sequence before hashing or parsing. Reopening
        // the path (even with pre/post hashes) cannot bind parsed bytes under
        // A -> B -> A replacement. This adds at most MAX_ARCHIVE retained bytes.
        let mut bytes = Vec::new();
        let mut buffer = [0; 64 * 1024];
        loop {
            check_cancel(cancel)?;
            let count = file.read(&mut buffer).map_err(|_| InspectorError::Input)?;
            if count == 0 {
                break;
            }
            if bytes.len() as u64 + count as u64 > MAX_ARCHIVE {
                return Err(InspectorError::Limit);
            }
            bytes.extend_from_slice(&buffer[..count]);
        }
        drop(file);
        Self::parse_frozen(&bytes, cancel)
    }

    fn parse_frozen(bytes: &[u8], cancel: &CancellationToken) -> Result<Self, InspectorError> {
        let source_digest =
            SourceDigest::hash_reader(bytes, MAX_ARCHIVE).map_err(|_| InspectorError::Integrity)?;
        check_cancel(cancel)?;
        let mut source = Cursor::new(bytes);
        preflight_directory(&mut source)?;
        let mut zip = ZipArchive::new(source).map_err(|_| InspectorError::InvalidZip)?;
        if zip.is_empty() || zip.len() > 17 {
            return Err(InspectorError::Limit);
        }
        let mut names = BTreeSet::new();
        let mut total = 0u64;
        for index in 0..zip.len() {
            let entry = zip
                .by_index(index)
                .map_err(|_| InspectorError::InvalidZip)?;
            let name = entry
                .name()
                .map_err(|_| InspectorError::InvalidZip)?
                .into_owned();
            if !safe_path(&name)
                || !names.insert(name.clone())
                || entry.is_dir()
                || entry.is_symlink()
                || entry.encrypted()
                || !matches!(
                    entry.compression(),
                    CompressionMethod::Stored | CompressionMethod::Deflated
                )
            {
                return Err(InspectorError::InvalidZip);
            }
            if entry.size() > entry_limit(&name) {
                return Err(InspectorError::Limit);
            }
            total = total
                .checked_add(entry.size())
                .ok_or(InspectorError::Limit)?;
            if total > MAX_TOTAL + 256 * 1024 {
                return Err(InspectorError::Limit);
            }
        }
        let manifest = read_entry(&mut zip, MANIFEST, 256 * 1024, cancel)?;
        let manifest = aps_json::parse_slice(&manifest, json_limits(256 * 1024))
            .map_err(|_| InspectorError::InvalidManifest)?;
        let root = object(&manifest)?;
        if text(root, "format")? != "agentperf-inspector-capture" {
            return Err(InspectorError::InvalidManifest);
        }
        let archive_version = unsigned(root, "archiveVersion")?;
        if !(1..=2).contains(&archive_version) {
            return Err(InspectorError::UnsupportedVersion);
        }
        let protocol_major = unsigned(root, "protocolMajor")?;
        let protocol_minor = unsigned(root, "protocolMinor")?;
        if protocol_major != 1 {
            return Err(InspectorError::UnsupportedVersion);
        }
        let package = text(root, "packageName")?.to_owned();
        let captured_at = integer(root, "capturedAtEpochMillis")?;
        let mut declared = BTreeMap::new();
        for value in root
            .get("entries")
            .and_then(Value::as_array)
            .ok_or(InspectorError::InvalidManifest)?
        {
            let value = object(value)?;
            let name = text(value, "path")?.to_owned();
            let size = value
                .get("size")
                .and_then(Value::as_u64)
                .ok_or(InspectorError::InvalidManifest)?;
            let digest = SourceDigest::from_hex(text(value, "sha256")?)
                .map_err(|_| InspectorError::InvalidManifest)?;
            let required = value
                .get("required")
                .and_then(Value::as_bool)
                .ok_or(InspectorError::InvalidManifest)?;
            if !safe_path(&name)
                || name == MANIFEST
                || size > entry_limit(&name)
                || (required && !known(&name))
            {
                return Err(InspectorError::InvalidManifest);
            }
            if declared
                .insert(
                    name.clone(),
                    Entry {
                        name,
                        size,
                        digest,
                        required,
                    },
                )
                .is_some()
            {
                return Err(InspectorError::InvalidManifest);
            }
        }
        if declared.len() + 1 != names.len()
            || declared.keys().any(|name| !names.contains(name))
            || !declared.get(SNAPSHOT).is_some_and(|entry| entry.required)
            || (archive_version == 1 && declared.contains_key(COMPOSE))
            || declared.contains_key("raw/visible-window-views.zip")
                != declared.contains_key("raw/visible-window-views.txt")
        {
            return Err(InspectorError::InvalidManifest);
        }
        let mut snapshot = None;
        let mut compose = None;
        let mut screenshot = None;
        let mut screenshot_png = None;
        let mut validated_total = 0u64;
        for entry in declared.values() {
            check_cancel(cancel)?;
            let bytes = read_entry(&mut zip, &entry.name, entry_limit(&entry.name), cancel)?;
            validated_total = validated_total
                .checked_add(bytes.len() as u64)
                .ok_or(InspectorError::Limit)?;
            if validated_total > MAX_TOTAL
                || bytes.len() as u64 != entry.size
                || SourceDigest::hash_reader(bytes.as_slice(), entry.size)
                    .map_err(|_| InspectorError::Integrity)?
                    != entry.digest
            {
                return Err(InspectorError::Integrity);
            }
            match entry.name.as_str() {
                SNAPSHOT => {
                    snapshot = Some(
                        aps_json::parse_slice(&bytes, json_limits(entry_limit(SNAPSHOT)))
                            .map_err(|_| InspectorError::InvalidSnapshot)?,
                    )
                }
                COMPOSE => {
                    compose = Some(parse_compose(
                        &aps_json::parse_slice(&bytes, json_limits(entry_limit(COMPOSE)))
                            .map_err(|_| InspectorError::InvalidSnapshot)?,
                        &package,
                        captured_at,
                    )?)
                }
                SCREENSHOT => {
                    screenshot = Some(ScreenshotEvidence {
                        sha256: entry.digest,
                        size_bytes: entry.size,
                    });
                    screenshot_png = Some(bytes);
                }
                _ => {}
            }
        }
        let frame = FrameIdentity {
            source_digest,
            package_name: package,
            captured_at_epoch_millis: captured_at,
            protocol_major,
            protocol_minor,
        };
        let mut archive = parse_snapshot(
            snapshot.as_ref().ok_or(InspectorError::InvalidManifest)?,
            frame,
            archive_version,
        )?;
        archive.compose = compose;
        archive.screenshot = screenshot;
        archive.screenshot_png = screenshot_png;
        drop(zip);
        check_cancel(cancel)?;
        Ok(archive)
    }

    pub fn hierarchy(
        &self,
        window_id: &str,
        offset: usize,
        limit: usize,
    ) -> Result<&[LayoutNode], InspectorError> {
        if limit == 0 || limit > MAX_PAGE_SIZE || offset.checked_add(limit).is_none() {
            return Err(InspectorError::InvalidPage);
        }
        let window = self
            .windows
            .iter()
            .find(|window| window.id == window_id)
            .ok_or(InspectorError::WindowMissing)?;
        let start = offset.min(window.nodes.len());
        Ok(&window.nodes[start..(start + limit).min(window.nodes.len())])
    }
    pub fn node(&self, window_id: &str, node_id: &str) -> Option<&LayoutNode> {
        self.windows
            .iter()
            .find(|window| window.id == window_id)?
            .nodes
            .iter()
            .find(|node| node.id == node_id)
    }
    pub fn properties(&self, window_id: &str, node_id: &str) -> Option<&LayoutNode> {
        self.node(window_id, node_id)
    }
    pub fn canvas(&self, window_id: &str) -> Result<CanvasModel, InspectorError> {
        let window = self
            .windows
            .iter()
            .find(|window| window.id == window_id)
            .ok_or(InspectorError::WindowMissing)?;
        Ok(CanvasModel {
            display: self.display,
            window_bounds: window.bounds,
            screenshot_available: self.screenshot.is_some(),
        })
    }
}

fn read_entry<R: Read + Seek>(
    zip: &mut ZipArchive<R>,
    name: &str,
    limit: u64,
    cancel: &CancellationToken,
) -> Result<Vec<u8>, InspectorError> {
    let mut entry = zip.by_name(name).map_err(|_| InspectorError::InvalidZip)?;
    let expected = entry.size();
    if expected > limit {
        return Err(InspectorError::Limit);
    }
    let mut bytes = Vec::new();
    let mut buffer = [0; 64 * 1024];
    loop {
        check_cancel(cancel)?;
        let count = entry
            .read(&mut buffer)
            .map_err(|_| InspectorError::Integrity)?;
        if count == 0 {
            break;
        }
        if bytes.len() as u64 + count as u64 > limit {
            return Err(InspectorError::Limit);
        }
        bytes.extend_from_slice(&buffer[..count]);
    }
    if bytes.len() as u64 != expected {
        return Err(InspectorError::Integrity);
    }
    Ok(bytes)
}

/// Reject oversized central directories before the ZIP crate allocates their
/// metadata. Native captures contain <=17 entries and do not need ZIP64.
fn preflight_directory(file: &mut (impl Read + Seek)) -> Result<(), InspectorError> {
    let length = file
        .seek(SeekFrom::End(0))
        .map_err(|_| InspectorError::Input)?;
    let tail_length = length.min(65_535 + 22) as usize;
    if tail_length < 22 {
        return Err(InspectorError::InvalidZip);
    }
    file.seek(SeekFrom::End(-(tail_length as i64)))
        .map_err(|_| InspectorError::Input)?;
    let mut tail = vec![0; tail_length];
    file.read_exact(&mut tail)
        .map_err(|_| InspectorError::Input)?;
    let mut found = false;
    for start in (0..=tail_length - 22).rev() {
        if tail[start..start + 4] != *b"PK\x05\x06" {
            continue;
        }
        let u16_at = |offset| u16::from_le_bytes([tail[start + offset], tail[start + offset + 1]]);
        let u32_at = |offset| {
            u32::from_le_bytes([
                tail[start + offset],
                tail[start + offset + 1],
                tail[start + offset + 2],
                tail[start + offset + 3],
            ])
        };
        if start + 22 + u16_at(20) as usize != tail_length {
            continue;
        }
        let count = u16_at(10);
        let directory_size = u32_at(12) as u64;
        let directory_offset = u32_at(16) as u64;
        if u16_at(4) != 0
            || u16_at(6) != 0
            || u16_at(8) != count
            || count == 0
            || count > 17
            || directory_size > 1024 * 1024
            || directory_offset
                .checked_add(directory_size)
                .is_none_or(|end| end > length - (tail_length - start) as u64)
        {
            return Err(InspectorError::InvalidZip);
        }
        found = true;
        break;
    }
    if !found {
        return Err(InspectorError::InvalidZip);
    }
    file.seek(SeekFrom::Start(0))
        .map_err(|_| InspectorError::Input)?;
    Ok(())
}
fn safe_path(path: &str) -> bool {
    !path.is_empty()
        && path.len() <= 4096
        && !path.starts_with('/')
        && !path.contains(['\\', ':', '\0'])
        && !path.chars().any(char::is_control)
        && path
            .split('/')
            .all(|part| !part.is_empty() && part != "." && part != "..")
}
fn known(name: &str) -> bool {
    matches!(
        name,
        SNAPSHOT
            | COMPOSE
            | SCREENSHOT
            | "raw/visible-window-views.zip"
            | "raw/visible-window-views.txt"
            | "report/analysis-report.json"
            | "report/ai-analysis-report.json"
            | "timeline/history.json"
    )
}
fn entry_limit(name: &str) -> u64 {
    match name {
        MANIFEST => 256 * 1024,
        SNAPSHOT => 108 * 1024 * 1024,
        COMPOSE => 64 * 1024 * 1024,
        SCREENSHOT | "raw/visible-window-views.zip" => 32 * 1024 * 1024,
        "raw/visible-window-views.txt" => 8 * 1024 * 1024,
        "report/analysis-report.json"
        | "report/ai-analysis-report.json"
        | "timeline/history.json" => 4 * 1024 * 1024,
        _ => 1024 * 1024,
    }
}
fn json_limits(max_bytes: u64) -> Limits {
    Limits {
        max_bytes,
        max_depth: 64,
        max_nodes: 2_000_000,
        max_string_bytes: 256 * 1024,
        max_collection_items: MAX_NODES,
    }
}
fn check_cancel(cancel: &CancellationToken) -> Result<(), InspectorError> {
    if cancel.is_cancelled() {
        Err(InspectorError::Cancelled)
    } else {
        Ok(())
    }
}
fn object(value: &Value) -> Result<&aps_json::Map<String, Value>, InspectorError> {
    value.as_object().ok_or(InspectorError::InvalidSnapshot)
}
fn text<'a>(value: &'a aps_json::Map<String, Value>, key: &str) -> Result<&'a str, InspectorError> {
    value
        .get(key)
        .and_then(Value::as_str)
        .filter(|text| !text.is_empty() && text.len() <= 4096)
        .ok_or(InspectorError::InvalidSnapshot)
}
fn unsigned(value: &aps_json::Map<String, Value>, key: &str) -> Result<u32, InspectorError> {
    value
        .get(key)
        .and_then(Value::as_u64)
        .and_then(|v| v.try_into().ok())
        .ok_or(InspectorError::InvalidSnapshot)
}
fn integer(value: &aps_json::Map<String, Value>, key: &str) -> Result<i64, InspectorError> {
    value
        .get(key)
        .and_then(Value::as_i64)
        .ok_or(InspectorError::InvalidSnapshot)
}
fn bounds(value: &Value) -> Result<Bounds, InspectorError> {
    let map = object(value)?;
    let b = Bounds {
        left: integer(map, "left")?
            .try_into()
            .map_err(|_| InspectorError::InvalidSnapshot)?,
        top: integer(map, "top")?
            .try_into()
            .map_err(|_| InspectorError::InvalidSnapshot)?,
        right: integer(map, "right")?
            .try_into()
            .map_err(|_| InspectorError::InvalidSnapshot)?,
        bottom: integer(map, "bottom")?
            .try_into()
            .map_err(|_| InspectorError::InvalidSnapshot)?,
    };
    if b.right < b.left || b.bottom < b.top {
        return Err(InspectorError::InvalidSnapshot);
    }
    Ok(b)
}

fn parse_snapshot(
    value: &Value,
    frame: FrameIdentity,
    archive_version: u32,
) -> Result<InspectorArchive, InspectorError> {
    let root = object(value)?;
    // Even multi-window protocol snapshots require the legacy root envelope.
    root.get("root")
        .and_then(Value::as_object)
        .ok_or(InspectorError::InvalidSnapshot)?;
    let version = object(
        root.get("protocolVersion")
            .ok_or(InspectorError::InvalidSnapshot)?,
    )?;
    if unsigned(version, "major")? != frame.protocol_major
        || unsigned(version, "minor")? != frame.protocol_minor
        || text(root, "packageName")? != frame.package_name
        || integer(root, "capturedAtEpochMillis")? != frame.captured_at_epoch_millis
    {
        return Err(InspectorError::FrameMismatch);
    }
    let display = object(root.get("display").ok_or(InspectorError::InvalidSnapshot)?)?;
    let display = DisplayInfo {
        width_px: unsigned(display, "widthPx")?,
        height_px: unsigned(display, "heightPx")?,
        density: display
            .get("density")
            .and_then(Value::as_f64)
            .ok_or(InspectorError::InvalidSnapshot)?,
    };
    if display.width_px == 0
        || display.height_px == 0
        || display.width_px > 65_536
        || display.height_px > 65_536
        || !display.density.is_finite()
        || display.density <= 0.
    {
        return Err(InspectorError::InvalidSnapshot);
    }
    let caps = object(
        root.get("capabilities")
            .ok_or(InspectorError::InvalidSnapshot)?,
    )?;
    let capability = |key| -> Result<bool, InspectorError> {
        match caps.get(key) {
            None => Ok(false),
            Some(v) => v.as_bool().ok_or(InspectorError::InvalidSnapshot),
        }
    };
    let capabilities = Capabilities {
        view_hierarchy: capability("viewHierarchy")?,
        compose_semantics: capability("composeSemantics")?,
        screenshots: capability("screenshots")?,
        timeline: capability("timeline")?,
    };
    let mut windows = Vec::new();
    let mut total_nodes = 0;
    let mut raw_budget = MAX_RETAINED_PROPERTY_BYTES;
    if let Some(values) = root.get("windows") {
        for value in values.as_array().ok_or(InspectorError::InvalidSnapshot)? {
            if windows.len() >= 64 {
                return Err(InspectorError::Limit);
            }
            let window = object(value)?;
            let id = text(window, "id")?.to_owned();
            if windows.iter().any(|window: &LayoutWindow| window.id == id) {
                return Err(InspectorError::InvalidSnapshot);
            }
            let kind = window
                .get("type")
                .and_then(Value::as_str)
                .unwrap_or("OTHER")
                .to_owned();
            let window_bounds = bounds(
                window
                    .get("bounds")
                    .ok_or(InspectorError::InvalidSnapshot)?,
            )?;
            let mut nodes = Vec::new();
            flatten(
                window.get("root").ok_or(InspectorError::InvalidSnapshot)?,
                None,
                0,
                &mut nodes,
                &mut BTreeSet::new(),
                &mut total_nodes,
                &mut raw_budget,
            )?;
            windows.push(LayoutWindow {
                id,
                kind,
                bounds: window_bounds,
                nodes,
            });
        }
    }
    if windows.is_empty() {
        let value = root.get("root").ok_or(InspectorError::InvalidSnapshot)?;
        let mut nodes = Vec::new();
        flatten(
            value,
            None,
            0,
            &mut nodes,
            &mut BTreeSet::new(),
            &mut total_nodes,
            &mut raw_budget,
        )?;
        windows.push(LayoutWindow {
            id: "window:legacy".into(),
            kind: "OTHER".into(),
            bounds: nodes[0].bounds,
            nodes,
        });
    }
    let default_window_id = root
        .get("defaultWindowId")
        .and_then(Value::as_str)
        .filter(|id| windows.iter().any(|w| w.id == *id))
        .unwrap_or(&windows[0].id)
        .to_owned();
    Ok(InspectorArchive {
        archive_version,
        frame,
        display,
        capabilities,
        windows,
        default_window_id,
        screenshot: None,
        screenshot_png: None,
        compose: None,
    })
}

/// Archive-wide bound for retained raw/semantic property text. Nodes after the
/// budget is spent keep their structural attributes but no property map.
const MAX_RETAINED_PROPERTY_BYTES: u64 = 4 * 1024 * 1024;
const MAX_RETAINED_PROPERTY_ENTRIES: usize = 256;
const MAX_RETAINED_PROPERTY_KEY_BYTES: usize = 512;
const MAX_RETAINED_PROPERTY_VALUE_BYTES: usize = 8 * 1024;
const MAX_RETAINED_TEXT_BYTES: usize = 64 * 1024;

/// Nullable/defaulted protocol fields may be omitted; Kotlin's serializer also
/// emits explicit null for some producers. Both mean "absent" here.
fn optional_bool(
    map: &aps_json::Map<String, Value>,
    key: &str,
) -> Result<Option<bool>, InspectorError> {
    match map.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value
            .as_bool()
            .map(Some)
            .ok_or(InspectorError::InvalidSnapshot),
    }
}

fn optional_i32(
    map: &aps_json::Map<String, Value>,
    key: &str,
) -> Result<Option<i32>, InspectorError> {
    match map.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value
            .as_i64()
            .and_then(|value| value.try_into().ok())
            .map(Some)
            .ok_or(InspectorError::InvalidSnapshot),
    }
}

fn optional_f32(
    map: &aps_json::Map<String, Value>,
    key: &str,
) -> Result<Option<f32>, InspectorError> {
    match map.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => {
            let value = value.as_f64().ok_or(InspectorError::InvalidSnapshot)?;
            if !value.is_finite() {
                return Err(InspectorError::InvalidSnapshot);
            }
            Ok(Some(value as f32))
        }
    }
}

fn clipped_text(value: &str, limit: usize) -> String {
    if value.len() <= limit {
        return value.to_owned();
    }
    let mut end = limit;
    while end > 0 && !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].to_owned()
}

fn optional_text(
    map: &aps_json::Map<String, Value>,
    key: &str,
    limit: usize,
) -> Result<Option<String>, InspectorError> {
    match map.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value
            .as_str()
            .map(|value| clipped_text(value, limit))
            .map(Some)
            .ok_or(InspectorError::InvalidSnapshot),
    }
}

fn optional_bounds(
    map: &aps_json::Map<String, Value>,
    key: &str,
) -> Result<Option<Bounds>, InspectorError> {
    match map.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => bounds(value).map(Some),
    }
}

fn optional_insets(
    map: &aps_json::Map<String, Value>,
    key: &str,
) -> Result<Option<EdgeInsets>, InspectorError> {
    let Some(value) = map.get(key).filter(|value| !value.is_null()) else {
        return Ok(None);
    };
    let insets = object(value)?;
    // Kotlin's `EdgeInsets` fields default to 0 and `encodeDefaults=false`
    // omits zero sides, so only present keys are read.
    let value = |key: &str| -> Result<i32, InspectorError> {
        match insets.get(key) {
            None | Some(Value::Null) => Ok(0),
            Some(_) => integer(insets, key)?
                .try_into()
                .map_err(|_| InspectorError::InvalidSnapshot),
        }
    };
    Ok(Some(EdgeInsets {
        left: value("left")?,
        top: value("top")?,
        right: value("right")?,
        bottom: value("bottom")?,
    }))
}

fn retained_properties(
    value: Option<&Value>,
    budget: &mut u64,
) -> Result<std::collections::BTreeMap<String, String>, InspectorError> {
    let mut properties = std::collections::BTreeMap::new();
    let Some(value) = value else {
        return Ok(properties);
    };
    let map = object(value)?;
    for (key, value) in map {
        if properties.len() >= MAX_RETAINED_PROPERTY_ENTRIES || *budget == 0 {
            break;
        }
        // Maps are typed as String->String in the protocol; tolerate null or
        // non-string vendor entries by skipping them instead of rejecting an
        // otherwise valid saved archive.
        let Some(value) = value.as_str() else {
            continue;
        };
        if key.len() > MAX_RETAINED_PROPERTY_KEY_BYTES {
            continue;
        }
        let value = clipped_text(value, MAX_RETAINED_PROPERTY_VALUE_BYTES);
        let retained = (key.len() + value.len()) as u64;
        if retained > *budget {
            break;
        }
        *budget -= retained;
        properties.insert(key.clone(), value);
    }
    Ok(properties)
}

fn parse_attributes(
    value: Option<&Value>,
    budget: &mut u64,
) -> Result<ViewAttributes, InspectorError> {
    let Some(value) = value.filter(|value| !value.is_null()) else {
        return Ok(ViewAttributes::default());
    };
    let map = object(value)?;
    Ok(ViewAttributes {
        visibility: optional_text(map, "visibility", 64)?,
        layout_bounds: optional_bounds(map, "layoutBounds")?,
        elevation: optional_f32(map, "elevation")?,
        z: optional_f32(map, "z")?,
        translation_x: optional_f32(map, "translationX")?,
        translation_y: optional_f32(map, "translationY")?,
        translation_z: optional_f32(map, "translationZ")?,
        rotation: optional_f32(map, "rotation")?,
        rotation_x: optional_f32(map, "rotationX")?,
        rotation_y: optional_f32(map, "rotationY")?,
        scale_x: optional_f32(map, "scaleX")?,
        scale_y: optional_f32(map, "scaleY")?,
        pivot_x: optional_f32(map, "pivotX")?,
        pivot_y: optional_f32(map, "pivotY")?,
        padding: optional_insets(map, "padding")?,
        margin: optional_insets(map, "margin")?,
        layout_width: optional_i32(map, "layoutWidth")?,
        layout_height: optional_i32(map, "layoutHeight")?,
        layout_params_class: optional_text(map, "layoutParamsClass", 512)?,
        measured_width: optional_i32(map, "measuredWidth")?,
        measured_height: optional_i32(map, "measuredHeight")?,
        min_width: optional_i32(map, "minWidth")?,
        min_height: optional_i32(map, "minHeight")?,
        scroll_x: optional_i32(map, "scrollX")?,
        scroll_y: optional_i32(map, "scrollY")?,
        clip_bounds: optional_bounds(map, "clipBounds")?,
        clip_children: optional_bool(map, "clipChildren")?,
        clip_to_padding: optional_bool(map, "clipToPadding")?,
        background: optional_text(map, "background", 4096)?,
        background_color: optional_text(map, "backgroundColor", 4096)?,
        foreground: optional_text(map, "foreground", 4096)?,
        opaque: optional_bool(map, "opaque")?,
        will_not_draw: optional_bool(map, "willNotDraw")?,
        hardware_accelerated: optional_bool(map, "hardwareAccelerated")?,
        layer_type: optional_text(map, "layerType", 4096)?,
        layout_requested: optional_bool(map, "layoutRequested")?,
        enabled: optional_bool(map, "enabled")?,
        clickable: optional_bool(map, "clickable")?,
        long_clickable: optional_bool(map, "longClickable")?,
        focusable: optional_bool(map, "focusable")?,
        focused: optional_bool(map, "focused")?,
        selected: optional_bool(map, "selected")?,
        content_description: optional_text(map, "contentDescription", 4096)?,
        raw_properties: retained_properties(map.get("rawProperties"), budget)?,
        semantic_properties: retained_properties(map.get("semanticProperties"), budget)?,
    })
}

fn flatten(
    value: &Value,
    parent: Option<usize>,
    depth: usize,
    nodes: &mut Vec<LayoutNode>,
    ids: &mut BTreeSet<String>,
    total: &mut usize,
    raw_budget: &mut u64,
) -> Result<(), InspectorError> {
    if depth > 64 || *total >= MAX_NODES {
        return Err(InspectorError::Limit);
    }
    let map = object(value)?;
    let id = text(map, "id")?.to_owned();
    if !ids.insert(id.clone()) {
        return Err(InspectorError::InvalidSnapshot);
    }
    let kind = match text(map, "type")? {
        "view" => NodeKind::View,
        "compose" => NodeKind::Compose,
        _ => return Err(InspectorError::InvalidSnapshot),
    };
    let alpha = match map.get("alpha") {
        None => 1.,
        Some(value) => value.as_f64().ok_or(InspectorError::InvalidSnapshot)?,
    };
    if !alpha.is_finite() || !(0.0..=1.0).contains(&alpha) {
        return Err(InspectorError::InvalidSnapshot);
    }
    let index = nodes.len();
    // Attributes are display-only. A malformed optional field must not reject an
    // otherwise valid saved archive; structural fields stay strict.
    let mut attributes = parse_attributes(map.get("attributes"), raw_budget).unwrap_or_default();
    // Compose nodes carry semanticProperties on the node itself; keep them in
    // the same bounded map the Evidence tab displays.
    attributes.semantic_properties =
        retained_properties(map.get("semanticProperties"), raw_budget).unwrap_or_default();
    nodes.push(LayoutNode {
        id,
        kind,
        class_name: text(map, "className")?.to_owned(),
        parent_index: parent,
        depth,
        bounds: bounds(map.get("bounds").ok_or(InspectorError::InvalidSnapshot)?)?,
        visible: match map.get("visible") {
            None | Some(Value::Null) => true,
            Some(value) => value.as_bool().ok_or(InspectorError::InvalidSnapshot)?,
        },
        alpha,
        resource_name: optional_text(map, "resourceName", 4096)?,
        text: optional_text(map, "text", MAX_RETAINED_TEXT_BYTES)?,
        semantics_role: optional_text(map, "semanticsRole", 512)?,
        attributes,
    });
    *total += 1;
    if let Some(children) = map.get("children") {
        for child in children.as_array().ok_or(InspectorError::InvalidSnapshot)? {
            flatten(child, Some(index), depth + 1, nodes, ids, total, raw_budget)?;
        }
    }
    Ok(())
}

const MAX_COMPOSE_NODES: usize = 100_000;
const MAX_COMPOSE_DETAIL_NODES: usize = 20_000;
const MAX_COMPOSE_DETAIL_VALUES: usize = 512;
const MAX_COMPOSE_VALUE_DEPTH: usize = 8;
const MAX_COMPOSE_TOTAL_VALUES: usize = 200_000;
const MAX_COMPOSE_TEXT_BYTES: usize = 4 * 1024;
const MAX_COMPOSE_STATE_READS: usize = 20_000;
const MAX_COMPOSE_FLAGS: usize = 32;

fn optional_u32(
    map: &aps_json::Map<String, Value>,
    key: &str,
) -> Result<Option<u32>, InspectorError> {
    match map.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value
            .as_i64()
            .and_then(|value| u32::try_from(value).ok())
            .map(Some)
            .ok_or(InspectorError::InvalidSnapshot),
    }
}

fn parse_compose_source(value: &Value) -> Result<ComposeSourceLocation, InspectorError> {
    let map = object(value)?;
    Ok(ComposeSourceLocation {
        package_hash: integer(map, "packageHash")?
            .try_into()
            .map_err(|_| InspectorError::InvalidSnapshot)?,
        file_name: text(map, "fileName")?.to_owned(),
        line_number: unsigned(map, "lineNumber")?,
        offset: unsigned(map, "offset")?,
    })
}

fn parse_compose_node(
    value: &Value,
    nodes: &mut std::collections::BTreeMap<i64, ComposeNodeEvidence>,
) -> Result<(), InspectorError> {
    if nodes.len() >= MAX_COMPOSE_NODES {
        return Ok(());
    }
    let map = object(value)?;
    let node_id = integer(map, "id")?;
    let source = match map.get("source").filter(|value| !value.is_null()) {
        None => None,
        Some(value) => Some(parse_compose_source(value)?),
    };
    let mut flags = Vec::new();
    if let Some(values) = map.get("flags") {
        for value in values.as_array().ok_or(InspectorError::InvalidSnapshot)? {
            if flags.len() >= MAX_COMPOSE_FLAGS {
                break;
            }
            flags.push(clipped_text(
                value.as_str().ok_or(InspectorError::InvalidSnapshot)?,
                256,
            ));
        }
    }
    nodes.insert(
        node_id,
        ComposeNodeEvidence {
            node_id,
            anchor_hash: integer(map, "anchorHash")?
                .try_into()
                .map_err(|_| InspectorError::InvalidSnapshot)?,
            name: clipped_text(text(map, "name")?, MAX_COMPOSE_TEXT_BYTES),
            source,
            recompose_count: optional_u32(map, "recomposeCount")?,
            skip_count: optional_u32(map, "skipCount")?,
            system_created: optional_bool(map, "systemCreated")?.unwrap_or(false),
            flags,
        },
    );
    if let Some(children) = map.get("children").filter(|value| !value.is_null()) {
        for child in children.as_array().ok_or(InspectorError::InvalidSnapshot)? {
            parse_compose_node(child, nodes)?;
        }
    }
    Ok(())
}

fn parse_compose_nodes(
    frame: &aps_json::Map<String, Value>,
) -> Result<std::collections::BTreeMap<i64, ComposeNodeEvidence>, InspectorError> {
    let mut nodes = std::collections::BTreeMap::new();
    let Some(roots) = frame.get("roots") else {
        return Ok(nodes);
    };
    for root in roots.as_array().ok_or(InspectorError::InvalidSnapshot)? {
        let root = object(root)?;
        let Some(children) = root.get("nodes").filter(|value| !value.is_null()) else {
            continue;
        };
        for child in children.as_array().ok_or(InspectorError::InvalidSnapshot)? {
            parse_compose_node(child, &mut nodes)?;
        }
    }
    Ok(nodes)
}

fn parse_compose_values(
    value: Option<&Value>,
    depth: usize,
    budget: &mut usize,
) -> Result<Vec<ComposeValueEvidence>, InspectorError> {
    if depth > MAX_COMPOSE_VALUE_DEPTH || *budget == 0 {
        return Ok(Vec::new());
    }
    let Some(value) = value.filter(|value| !value.is_null()) else {
        return Ok(Vec::new());
    };
    let mut values = Vec::new();
    for value in value.as_array().ok_or(InspectorError::InvalidSnapshot)? {
        if values.len() >= MAX_COMPOSE_DETAIL_VALUES || *budget == 0 {
            break;
        }
        let map = object(value)?;
        let elements = parse_compose_values(map.get("elements"), depth + 1, budget)?;
        *budget = (*budget).saturating_sub(1);
        values.push(ComposeValueEvidence {
            name: clipped_text(text(map, "name")?, MAX_COMPOSE_TEXT_BYTES),
            type_name: clipped_text(text(map, "type")?, MAX_COMPOSE_TEXT_BYTES),
            value: optional_text(map, "value", MAX_COMPOSE_TEXT_BYTES)?,
            truncated: optional_bool(map, "truncated")?.unwrap_or(false),
            elements,
        });
    }
    Ok(values)
}

fn parse_compose_details(
    frame: &aps_json::Map<String, Value>,
) -> Result<std::collections::BTreeMap<i64, ComposeDetailEvidence>, InspectorError> {
    let mut details = std::collections::BTreeMap::new();
    let Some(values) = frame.get("details").filter(|value| !value.is_null()) else {
        return Ok(details);
    };
    let mut budget = MAX_COMPOSE_TOTAL_VALUES;
    for (key, value) in values.as_object().ok_or(InspectorError::InvalidSnapshot)? {
        if details.len() >= MAX_COMPOSE_DETAIL_NODES || budget == 0 {
            break;
        }
        let Ok(node_id) = key.parse::<i64>() else {
            continue;
        };
        let detail = object(value)?;
        details.insert(
            node_id,
            ComposeDetailEvidence {
                parameters: parse_compose_values(detail.get("parameters"), 0, &mut budget)?,
                modifiers: parse_compose_values(detail.get("modifiers"), 0, &mut budget)?,
                merged_semantics: parse_compose_values(
                    detail.get("mergedSemantics"),
                    0,
                    &mut budget,
                )?,
                unmerged_semantics: parse_compose_values(
                    detail.get("unmergedSemantics"),
                    0,
                    &mut budget,
                )?,
            },
        );
    }
    Ok(details)
}

fn parse_compose_state_reads(
    frame: &aps_json::Map<String, Value>,
) -> Result<std::collections::BTreeMap<i32, Vec<ComposeStateReadEvidence>>, InspectorError> {
    let mut reads_by_anchor = std::collections::BTreeMap::new();
    let Some(values) = frame.get("stateReads").filter(|value| !value.is_null()) else {
        return Ok(reads_by_anchor);
    };
    let mut total = 0usize;
    for (key, value) in values.as_object().ok_or(InspectorError::InvalidSnapshot)? {
        let Ok(anchor) = key.parse::<i32>() else {
            continue;
        };
        let mut all_reads = Vec::new();
        for group in value.as_array().ok_or(InspectorError::InvalidSnapshot)? {
            if total >= MAX_COMPOSE_STATE_READS {
                break;
            }
            let group = object(group)?;
            let number = integer(group, "recompositionNumber")?
                .try_into()
                .map_err(|_| InspectorError::InvalidSnapshot)?;
            let mut reads = Vec::new();
            if let Some(values) = group.get("reads").filter(|value| !value.is_null()) {
                for read in values.as_array().ok_or(InspectorError::InvalidSnapshot)? {
                    if total >= MAX_COMPOSE_STATE_READS {
                        break;
                    }
                    let read = object(read)?;
                    let (value_name, value_text) =
                        match read.get("value").filter(|value| !value.is_null()) {
                            None => (None, None),
                            Some(value) => {
                                let value = object(value)?;
                                (
                                    optional_text(value, "name", MAX_COMPOSE_TEXT_BYTES)?,
                                    optional_text(value, "value", MAX_COMPOSE_TEXT_BYTES)?,
                                )
                            }
                        };
                    let mut top_frame = None;
                    if let Some(stack) = read.get("stackTrace").filter(|value| !value.is_null()) {
                        for line in stack.as_array().ok_or(InspectorError::InvalidSnapshot)? {
                            let line = object(line)?;
                            let file = line
                                .get("fileName")
                                .and_then(Value::as_str)
                                .unwrap_or_default();
                            if file.is_empty() {
                                continue;
                            }
                            let line_number =
                                line.get("lineNumber").and_then(Value::as_i64).unwrap_or(-1);
                            top_frame = Some(if line_number >= 0 {
                                format!("{file}:{line_number}")
                            } else {
                                file.to_owned()
                            });
                            break;
                        }
                    }
                    reads.push(ComposeStateReadEvidence {
                        recomposition_number: number,
                        value_name,
                        value_text,
                        invalidated: optional_bool(read, "invalidated")?.unwrap_or(false),
                        top_frame,
                    });
                    total += 1;
                }
            }
            all_reads.extend(reads);
        }
        if !all_reads.is_empty() {
            reads_by_anchor.insert(anchor, all_reads);
        }
    }
    Ok(reads_by_anchor)
}

fn parse_compose(
    value: &Value,
    package: &str,
    timestamp: i64,
) -> Result<ComposeEvidence, InspectorError> {
    let root = object(value)?;
    if unsigned(root, "schemaVersion")? != 1 {
        return Err(InspectorError::UnsupportedVersion);
    }
    if text(root, "packageName")? != package || integer(root, "capturedAtEpochMillis")? != timestamp
    {
        return Err(InspectorError::FrameMismatch);
    }
    let frame = object(root.get("frame").ok_or(InspectorError::InvalidSnapshot)?)?;
    let mode = text(frame, "mode")?.to_owned();
    if !matches!(mode.as_str(), "FULL" | "SEMANTICS_ONLY") {
        return Err(InspectorError::InvalidSnapshot);
    }
    let privacy = root
        .get("privacy")
        .and_then(Value::as_str)
        .unwrap_or("SAFE_REDACTED")
        .to_owned();
    if !matches!(privacy.as_str(), "SAFE_REDACTED" | "FULL_FIDELITY") {
        return Err(InspectorError::InvalidSnapshot);
    }
    let mut capabilities = Vec::new();
    let mut seen_capabilities = BTreeSet::new();
    for cap in frame
        .get("capabilities")
        .and_then(Value::as_array)
        .ok_or(InspectorError::InvalidSnapshot)?
    {
        let cap = object(cap)?;
        if capabilities.len() >= 64 {
            return Err(InspectorError::Limit);
        }
        let capability = text(cap, "capability")?.to_owned();
        let availability = text(cap, "availability")?.to_owned();
        if !seen_capabilities.insert(capability.clone())
            || !matches!(
                availability.as_str(),
                "AVAILABLE" | "UNAVAILABLE" | "NOT_REQUESTED"
            )
        {
            return Err(InspectorError::InvalidSnapshot);
        }
        capabilities.push(ComposeCapability {
            capability,
            availability,
        });
    }
    let mut coverage = Vec::new();
    if let Some(values) = frame.get("coverage") {
        for value in values.as_array().ok_or(InspectorError::InvalidSnapshot)? {
            if coverage.len() >= MAX_NODES {
                return Err(InspectorError::Limit);
            }
            let value = object(value)?;
            let state = text(value, "state")?.to_owned();
            if !matches!(
                state.as_str(),
                "COLLECTED" | "NOT_COLLECTED" | "TRUNCATED" | "FAILED"
            ) {
                return Err(InspectorError::InvalidSnapshot);
            }
            let total_elements = match value.get("totalElements") {
                None | Some(Value::Null) => None,
                Some(v) => Some(
                    v.as_u64()
                        .and_then(|v| v.try_into().ok())
                        .ok_or(InspectorError::InvalidSnapshot)?,
                ),
            };
            coverage.push(ComposeCoverage {
                node_id: integer(value, "nodeId")?,
                field: text(value, "field")?.to_owned(),
                state,
                recursion_depth: if value.contains_key("recursionDepth") {
                    unsigned(value, "recursionDepth")?
                } else {
                    0
                },
                loaded_elements: if value.contains_key("loadedElements") {
                    unsigned(value, "loadedElements")?
                } else {
                    0
                },
                total_elements,
            });
        }
    }
    let count = |key| frame.get(key).and_then(Value::as_array).map_or(0, Vec::len);
    // Per-node Compose data is display-only: a malformed optional part must not
    // reject an otherwise valid saved archive.
    let nodes = parse_compose_nodes(frame).unwrap_or_default();
    let details = parse_compose_details(frame).unwrap_or_default();
    let state_reads = parse_compose_state_reads(frame).unwrap_or_default();
    Ok(ComposeEvidence {
        frame_id: text(frame, "frameId")?.to_owned(),
        generation: unsigned(frame, "generation")?,
        mode,
        privacy,
        declared_completeness: frame
            .get("completeness")
            .and_then(Value::as_str)
            .unwrap_or("COMPLETE")
            .to_owned(),
        capabilities,
        coverage,
        coverage_record_count: count("coverage"),
        truncation_count: count("truncations"),
        details_available: !details.is_empty() || !nodes.is_empty(),
        nodes,
        details,
        state_reads,
    })
}
