//! Capture archives use captured payloads, not the filtered display projection.
//! A new ZIP has its own digest; captureSourceDigest is provenance only, never
//! a substitute for the importer's independently computed artifact digest.
use super::*;
use aps_json::Map;
use std::{borrow::Cow, cell::Cell, fs, fs::OpenOptions, io::Write, sync::Arc};
use zip::{ZipWriter, write::SimpleFileOptions};

/// Opaque evidence retained before display-only clipping/defaults. Cloning a
/// workspace frame shares the bounded payload bytes, not their source paths.
#[derive(Clone, Debug, Default)]
pub struct ExportPayload {
    manifest: Option<Value>,
    entries: BTreeMap<String, Arc<[u8]>>,
    projection_only: bool,
}

impl ExportPayload {
    pub(crate) fn snapshot(value: &Value) -> Result<Self, InspectorError> {
        let bytes = encode(value, SNAPSHOT)?;
        Ok(Self {
            entries: BTreeMap::from([(SNAPSHOT.into(), bytes.into())]),
            ..Self::default()
        })
    }

    pub(crate) fn imported(manifest: Value, entries: BTreeMap<String, Arc<[u8]>>) -> Self {
        Self {
            manifest: Some(manifest),
            entries,
            projection_only: false,
        }
    }

    pub(crate) fn projection_only() -> Self {
        Self {
            projection_only: true,
            ..Self::default()
        }
    }
}

#[derive(Clone, Debug)]
pub struct ArchiveExport {
    pub size_bytes: u64,
    pub source_digest: SourceDigest,
    pub entry_count: usize,
    /// True when original payloads (including fields outside the UI projection)
    /// were retained. This is not a claim about capture completeness.
    pub retained_payloads: bool,
}

impl InspectorArchive {
    /// Writes the known captured evidence without redaction. The caller must
    /// obtain explicit consent for sensitive runtime values/screenshots.
    /// Never overwrites a destination, even a dangling symlink. Cancellation
    /// before the atomic create-new publication leaves no destination behind.
    pub fn export_archive(
        &self,
        destination: &Path,
        cancel: &CancellationToken,
    ) -> Result<ArchiveExport, InspectorError> {
        // JSON Value assembly/serialization also needs the hierarchy parser's
        // stack allowance when called from a small GPUI executor worker.
        std::thread::scope(|scope| {
            std::thread::Builder::new()
                .name("aps-inspector-export".into())
                .stack_size(8 * 1024 * 1024)
                .spawn_scoped(scope, || {
                    self.export_with_limit(destination, cancel, MAX_ARCHIVE)
                })
                .map_err(|_| InspectorError::Output)?
                .join()
                .map_err(|_| InspectorError::Output)?
        })
    }

    fn export_with_limit(
        &self,
        destination: &Path,
        cancel: &CancellationToken,
        max_archive: u64,
    ) -> Result<ArchiveExport, InspectorError> {
        check_cancel(cancel)?;
        if self.export_payload.projection_only {
            return Err(InspectorError::MissingEvidence);
        }
        if self.frame.protocol_major != 1 || !(1..=2).contains(&self.archive_version) {
            return Err(InspectorError::UnsupportedVersion);
        }
        let parent = destination
            .parent()
            .filter(|p| !p.as_os_str().is_empty())
            .unwrap_or(Path::new("."));
        if destination.file_name().is_none()
            || !parent.is_dir()
            || fs::symlink_metadata(destination).is_ok()
        {
            return Err(InspectorError::Output);
        }
        let mut content: BTreeMap<&str, Cow<'_, [u8]>> = self
            .export_payload
            .entries
            .iter()
            .map(|(name, bytes)| (name.as_str(), Cow::Borrowed(bytes.as_ref())))
            .collect();
        if !content.contains_key(SNAPSHOT) {
            content.insert(
                SNAPSHOT,
                Cow::Owned(encode(&typed_snapshot(self, cancel)?, SNAPSHOT)?),
            );
        }
        // Flattened Compose data cannot reconstruct roots, stack traces,
        // omission reasons or truncation records. Never invent a sidecar.
        if self.compose.is_some() && !content.contains_key(COMPOSE) {
            return Err(InspectorError::MissingEvidence);
        }
        match (&self.screenshot, &self.screenshot_png) {
            (Some(evidence), Some(png)) => {
                if png.len() as u64 > entry_limit(SCREENSHOT) {
                    return Err(InspectorError::Limit);
                }
                if evidence.size_bytes != png.len() as u64
                    || digest(png, cancel)? != evidence.sha256
                {
                    return Err(InspectorError::Integrity);
                }
                content.insert(SCREENSHOT, Cow::Borrowed(png));
            }
            (None, None) if !content.contains_key(SCREENSHOT) => {}
            _ => return Err(InspectorError::MissingEvidence),
        }
        if content.len() > 16 {
            return Err(InspectorError::Limit);
        }
        let mut total = 0u64;
        for (name, bytes) in &content {
            check_cancel(cancel)?;
            if !safe_path(name) || *name == MANIFEST {
                return Err(InspectorError::InvalidZip);
            }
            total = total
                .checked_add(bytes.len() as u64)
                .ok_or(InspectorError::Limit)?;
            if bytes.len() as u64 > entry_limit(name) || total > MAX_TOTAL {
                return Err(InspectorError::Limit);
            }
        }
        if content.contains_key("raw/visible-window-views.zip")
            != content.contains_key("raw/visible-window-views.txt")
        {
            return Err(InspectorError::InvalidManifest);
        }
        // Bind retained payload identity to this frame, not a mutable pathname.
        let snapshot = aps_json::parse_slice_with_extended_depth(
            &content[SNAPSHOT],
            hierarchy_json_limits(entry_limit(SNAPSHOT)),
        )
        .map_err(|_| InspectorError::InvalidSnapshot)?;
        parse_snapshot(&snapshot, self.frame.clone(), self.archive_version)?;
        let mut manifest = self.export_payload.manifest.clone().unwrap_or_else(|| {
            serde_json::json!({
                "format": "agentperf-inspector-capture",
                "producerVersion": concat!("aps-rust-", env!("CARGO_PKG_VERSION")),
                "packageName": self.frame.package_name,
                "capturedAtEpochMillis": self.frame.captured_at_epoch_millis,
                "protocolMajor": self.frame.protocol_major,
                "protocolMinor": self.frame.protocol_minor,
            })
        });
        if manifest.get("captureSourceDigest").is_none() {
            manifest["captureSourceDigest"] = self.frame.source_digest.to_hex().into();
        }
        manifest["archiveVersion"] = if content.contains_key(COMPOSE) {
            2
        } else {
            self.archive_version
        }
        .into();
        let mut entries = Vec::new();
        for (name, bytes) in &content {
            let mut entry = manifest["entries"]
                .as_array()
                .and_then(|entries| entries.iter().find(|entry| entry["path"] == *name))
                .cloned()
                .unwrap_or_else(
                    || serde_json::json!({"path": name, "required": *name == SNAPSHOT}),
                );
            entry["size"] = (bytes.len() as u64).into();
            entry["sha256"] = digest(bytes, cancel)?.to_hex().into();
            entries.push(entry);
        }
        manifest["entries"] = entries.into();
        let manifest = encode(&manifest, MANIFEST)?;

        // The private staging directory owns only our files. Publication uses
        // link(2)'s create-new behavior, not rename-overwrite or delete fallback.
        let staging =
            aps_platform::TempWorkspace::create(parent).map_err(|_| InspectorError::Output)?;
        let staged_path = staging.path().join("capture.zip");
        let mut options = OpenOptions::new();
        options.write(true).read(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let file = options
            .open(&staged_path)
            .map_err(|_| InspectorError::Output)?;
        let failure = Cell::new(None);
        let bounded = BoundedFile {
            file,
            cancel,
            max_bytes: max_archive.min(MAX_ARCHIVE),
            failure: &failure,
        };
        let mut zip = ZipWriter::new(bounded);
        let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
        let write_result = (|| {
            for (name, bytes) in std::iter::once((MANIFEST, manifest.as_slice()))
                .chain(content.iter().map(|(name, bytes)| (*name, bytes.as_ref())))
            {
                check_cancel(cancel)?;
                zip.start_file(name, options)
                    .map_err(|_| failure.get().unwrap_or(InspectorError::Output))?;
                for chunk in bytes.chunks(64 * 1024) {
                    check_cancel(cancel)?;
                    zip.write_all(chunk)
                        .map_err(|_| failure.get().unwrap_or(InspectorError::Output))?;
                }
            }
            let output = zip
                .finish()
                .map_err(|_| failure.get().unwrap_or(InspectorError::Output))?;
            output.file.sync_all().map_err(|_| InspectorError::Output)?;
            Ok::<_, InspectorError>(output.file)
        })();
        let mut file = write_result?;
        let size_bytes = file.metadata().map_err(|_| InspectorError::Output)?.len();
        file.rewind().map_err(|_| InspectorError::Output)?;
        let source_digest = SourceDigest::hash_reader(
            CancelReader {
                reader: &mut file,
                cancel,
            },
            MAX_ARCHIVE,
        )
        .map_err(|_| {
            if cancel.is_cancelled() {
                InspectorError::Cancelled
            } else {
                InspectorError::Output
            }
        })?;
        drop(file);
        // This is the commit point. Never unlink the user destination during
        // cleanup, including if another process replaces it after publication.
        publish_new(&staged_path, destination, cancel)?;
        Ok(ArchiveExport {
            size_bytes,
            source_digest,
            entry_count: content.len() + 1,
            retained_payloads: self.export_payload.entries.contains_key(SNAPSHOT),
        })
    }
}

fn publish_new(
    staged: &Path,
    destination: &Path,
    cancel: &CancellationToken,
) -> Result<(), InspectorError> {
    check_cancel(cancel)?;
    fs::hard_link(staged, destination).map_err(|_| InspectorError::Output)
}

struct CancelReader<'a, R> {
    reader: R,
    cancel: &'a CancellationToken,
}
impl<R: Read> Read for CancelReader<'_, R> {
    fn read(&mut self, bytes: &mut [u8]) -> std::io::Result<usize> {
        if self.cancel.is_cancelled() {
            return Err(std::io::Error::other("cancelled"));
        }
        let length = bytes.len().min(64 * 1024);
        self.reader.read(&mut bytes[..length])
    }
}

fn digest(bytes: &[u8], cancel: &CancellationToken) -> Result<SourceDigest, InspectorError> {
    check_cancel(cancel)?;
    SourceDigest::hash_reader(
        CancelReader {
            reader: bytes,
            cancel,
        },
        bytes.len() as u64,
    )
    .map_err(|_| {
        if cancel.is_cancelled() {
            InspectorError::Cancelled
        } else {
            InspectorError::Integrity
        }
    })
}

struct BoundedFile<'a> {
    file: File,
    cancel: &'a CancellationToken,
    max_bytes: u64,
    failure: &'a Cell<Option<InspectorError>>,
}
impl Write for BoundedFile<'_> {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        let error = if self.cancel.is_cancelled() {
            Some(InspectorError::Cancelled)
        } else if self
            .file
            .stream_position()?
            .saturating_add(bytes.len() as u64)
            > self.max_bytes
        {
            Some(InspectorError::Limit)
        } else {
            None
        };
        if let Some(error) = error {
            self.failure.set(Some(error));
            return Err(std::io::Error::other(error));
        }
        self.file.write(bytes)
    }
    fn flush(&mut self) -> std::io::Result<()> {
        self.file.flush()
    }
}
impl Seek for BoundedFile<'_> {
    fn seek(&mut self, from: SeekFrom) -> std::io::Result<u64> {
        self.file.seek(from)
    }
}

fn encode(value: &Value, name: &str) -> Result<Vec<u8>, InspectorError> {
    if name == MANIFEST {
        return aps_json::to_vec(value, json_limits(entry_limit(name)))
            .map_err(|_| InspectorError::Limit);
    }
    // aps-json's ordinary serializer deliberately caps depth at 64. Serialize
    // with its re-exported Value type, then validate against the same explicit
    // extended hierarchy budget as archive import; do not relax global limits.
    std::thread::scope(|scope| {
        std::thread::Builder::new()
            .name("aps-inspector-json".into())
            .stack_size(8 * 1024 * 1024)
            .spawn_scoped(scope, || {
                let mut output = JsonBuffer {
                    bytes: Vec::new(),
                    limit: entry_limit(name),
                };
                serde_json::to_writer(&mut output, value).map_err(|_| InspectorError::Limit)?;
                aps_json::parse_slice_with_extended_depth(
                    &output.bytes,
                    hierarchy_json_limits(output.limit),
                )
                .map_err(|_| InspectorError::Limit)?;
                Ok(output.bytes)
            })
            .map_err(|_| InspectorError::Output)?
            .join()
            .map_err(|_| InspectorError::Output)?
    })
}

struct JsonBuffer {
    bytes: Vec<u8>,
    limit: u64,
}
impl Write for JsonBuffer {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        if (self.bytes.len() as u64).saturating_add(bytes.len() as u64) > self.limit {
            return Err(std::io::Error::other("Inspector JSON byte budget exceeded"));
        }
        self.bytes.extend_from_slice(bytes);
        Ok(bytes.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

fn rect(bounds: Bounds) -> Value {
    serde_json::json!({"left": bounds.left, "top": bounds.top, "right": bounds.right, "bottom": bounds.bottom})
}

fn insets(insets: EdgeInsets) -> Value {
    serde_json::json!({"left": insets.left, "top": insets.top, "right": insets.right, "bottom": insets.bottom})
}

fn attributes(attributes: &ViewAttributes) -> Result<Value, InspectorError> {
    let mut map = Map::new();
    macro_rules! optional {
        ($($field:ident => $key:literal),* $(,)?) => { $(if let Some(value) = &attributes.$field { map.insert($key.into(), Value::from(value.clone())); })* };
    }
    optional!(visibility => "visibility", layout_width => "layoutWidth", layout_height => "layoutHeight",
        layout_params_class => "layoutParamsClass", measured_width => "measuredWidth", measured_height => "measuredHeight",
        min_width => "minWidth", min_height => "minHeight", scroll_x => "scrollX", scroll_y => "scrollY",
        clip_children => "clipChildren", clip_to_padding => "clipToPadding", background => "background",
        background_color => "backgroundColor", foreground => "foreground", opaque => "opaque", will_not_draw => "willNotDraw",
        hardware_accelerated => "hardwareAccelerated", layer_type => "layerType", layout_requested => "layoutRequested",
        enabled => "enabled", clickable => "clickable", long_clickable => "longClickable", focusable => "focusable",
        focused => "focused", selected => "selected", content_description => "contentDescription");
    for (key, value) in [
        ("elevation", attributes.elevation),
        ("z", attributes.z),
        ("translationX", attributes.translation_x),
        ("translationY", attributes.translation_y),
        ("translationZ", attributes.translation_z),
        ("rotation", attributes.rotation),
        ("rotationX", attributes.rotation_x),
        ("rotationY", attributes.rotation_y),
        ("scaleX", attributes.scale_x),
        ("scaleY", attributes.scale_y),
        ("pivotX", attributes.pivot_x),
        ("pivotY", attributes.pivot_y),
    ] {
        if let Some(value) = value {
            if !value.is_finite() {
                return Err(InspectorError::InvalidSnapshot);
            }
            map.insert(key.into(), Value::from(value));
        }
    }
    for (key, value) in [
        ("layoutBounds", attributes.layout_bounds),
        ("clipBounds", attributes.clip_bounds),
    ] {
        if let Some(value) = value {
            map.insert(key.into(), rect(value));
        }
    }
    for (key, value) in [
        ("padding", attributes.padding),
        ("margin", attributes.margin),
    ] {
        if let Some(value) = value {
            map.insert(key.into(), insets(value));
        }
    }
    if !attributes.raw_properties.is_empty() {
        map.insert(
            "rawProperties".into(),
            serde_json::json!(attributes.raw_properties),
        );
    }
    Ok(map.into())
}

fn node_value(node: &LayoutNode) -> Result<Value, InspectorError> {
    if !node.alpha.is_finite() {
        return Err(InspectorError::InvalidSnapshot);
    }
    let mut value = serde_json::json!({"type": match node.kind { NodeKind::View => "view", NodeKind::Compose => "compose" },
        "id": node.id, "className": node.class_name, "bounds": rect(node.bounds), "visible": node.visible,
        "alpha": node.alpha, "children": []});
    for (key, text) in [
        ("text", &node.text),
        ("resourceName", &node.resource_name),
        ("semanticsRole", &node.semantics_role),
    ] {
        if let Some(text) = text {
            value[key] = text.clone().into();
        }
    }
    let attrs = attributes(&node.attributes)?;
    if !attrs.as_object().unwrap().is_empty() {
        value["attributes"] = attrs;
    }
    if !node.attributes.semantic_properties.is_empty() {
        value["semanticProperties"] = serde_json::json!(node.attributes.semantic_properties);
    }
    Ok(value)
}

fn typed_root(window: &LayoutWindow, cancel: &CancellationToken) -> Result<Value, InspectorError> {
    if window.nodes.is_empty() {
        return Err(InspectorError::InvalidSnapshot);
    }
    let mut ancestors: Vec<usize> = Vec::new();
    let mut children = vec![Vec::new(); window.nodes.len()];
    let mut ids = BTreeSet::new();
    for (index, node) in window.nodes.iter().enumerate() {
        check_cancel(cancel)?;
        if node.depth > MAX_HIERARCHY_DEPTH {
            return Err(InspectorError::Limit);
        }
        if !ids.insert(&node.id)
            || (index == 0 && (node.depth != 0 || node.parent_index.is_some()))
            || (index > 0
                && (node.depth == 0
                    || node.depth > ancestors.len()
                    || node.parent_index != ancestors.get(node.depth - 1).copied()))
        {
            return Err(InspectorError::InvalidSnapshot);
        }
        if let Some(parent) = node.parent_index {
            children[parent].push(index);
        }
        ancestors.truncate(node.depth);
        ancestors.push(index);
    }
    // Bottom-up assembly avoids a recursive walk and repeated descendant scans.
    let mut nodes: Vec<Option<Value>> = vec![None; window.nodes.len()];
    for index in (0..window.nodes.len()).rev() {
        check_cancel(cancel)?;
        let mut value = node_value(&window.nodes[index])?;
        value["children"] = children[index]
            .iter()
            .map(|child| nodes[*child].take().unwrap())
            .collect::<Vec<_>>()
            .into();
        nodes[index] = Some(value);
    }
    Ok(nodes[0].take().unwrap())
}

fn typed_snapshot(
    archive: &InspectorArchive,
    cancel: &CancellationToken,
) -> Result<Value, InspectorError> {
    if archive.windows.is_empty()
        || archive.windows.len() > 64
        || archive
            .windows
            .iter()
            .map(|window| window.nodes.len())
            .sum::<usize>()
            > MAX_NODES
    {
        return Err(InspectorError::Limit);
    }
    let default = archive
        .windows
        .iter()
        .position(|window| window.id == archive.default_window_id)
        .ok_or(InspectorError::WindowMissing)?;
    if !archive.display.density.is_finite() {
        return Err(InspectorError::InvalidSnapshot);
    }
    let mut windows = Vec::new();
    for window in &archive.windows {
        let mut value = serde_json::json!({"id": window.id, "type": window.kind, "bounds": rect(window.bounds), "root": typed_root(window, cancel)?});
        if let Some(title) = &window.title {
            value["title"] = title.clone().into();
        }
        windows.push(value);
    }
    Ok(
        serde_json::json!({"protocolVersion": {"major": archive.frame.protocol_major, "minor": archive.frame.protocol_minor},
        "packageName": archive.frame.package_name, "capturedAtEpochMillis": archive.frame.captured_at_epoch_millis,
        "display": {"widthPx": archive.display.width_px, "heightPx": archive.display.height_px, "density": archive.display.density},
        "capabilities": {"viewHierarchy": archive.capabilities.view_hierarchy, "composeSemantics": archive.capabilities.compose_semantics,
            "screenshots": archive.capabilities.screenshots, "timeline": archive.capabilities.timeline},
        "root": windows[default]["root"].clone(), "windows": windows, "defaultWindowId": archive.default_window_id}),
    )
}

#[cfg(test)]
mod tests;
