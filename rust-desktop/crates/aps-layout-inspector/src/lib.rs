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

mod protocol;
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
    /// Runtime text/semantics/raw attributes are deliberately not retained.
    pub runtime_values_omitted: bool,
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
                    })
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
        compose: None,
    })
}

fn flatten(
    value: &Value,
    parent: Option<usize>,
    depth: usize,
    nodes: &mut Vec<LayoutNode>,
    ids: &mut BTreeSet<String>,
    total: &mut usize,
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
    nodes.push(LayoutNode {
        id,
        kind,
        class_name: text(map, "className")?.to_owned(),
        parent_index: parent,
        depth,
        bounds: bounds(map.get("bounds").ok_or(InspectorError::InvalidSnapshot)?)?,
        visible: match map.get("visible") {
            None => true,
            Some(value) => value.as_bool().ok_or(InspectorError::InvalidSnapshot)?,
        },
        alpha,
        resource_name: map
            .get("resourceName")
            .and_then(Value::as_str)
            .filter(|v| v.len() <= 4096)
            .map(str::to_owned),
        runtime_values_omitted: true,
    });
    *total += 1;
    if let Some(children) = map.get("children") {
        for child in children.as_array().ok_or(InspectorError::InvalidSnapshot)? {
            flatten(child, Some(index), depth + 1, nodes, ids, total)?;
        }
    }
    Ok(())
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
        details_available: false,
    })
}
