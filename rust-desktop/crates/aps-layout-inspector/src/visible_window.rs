//! Port of the Compose host's visible-window View hierarchy capture
//! (\`adb exec-out cmd window dump-visible-window-views\`).
//!
//! The device returns a ZIP with one entry per visible window. Each entry is
//! Android's encoded View hierarchy (\`ViewHierarchyEncoder\` wire format): typed
//! values, maps keyed by property ids and a trailing property-name index. The
//! Kotlin oracle is \`VisibleWindowHierarchyParser.kt\`; this module keeps the same
//! window filtering, window identity/title/type derivation, absolute-bounds
//! composition (parent + scroll + translation) and protocol \`ViewAttributes\`
//! projection.
//!
//! Everything here is pure and blocking-free apart from cancellation checks;
//! callers perform the ADB command off the UI thread.
//!
//! Deliberate deviations from the Kotlin oracle, required by the Rust archive
//! ingestion contract (\`parse_snapshot\` in \`lib.rs\`):
//! - ZIP input bytes, per-entry bytes, decoded bytes and entry count are all
//!   bounded (Kotlin only bounds one entry).
//! - Decoded node depth is capped at 63 while projecting (Kotlin is unbounded);
//!   children deeper than that are dropped so the emitted frame stays valid
//!   (the archive ingestion limit is 64).
//! - Decoded nodes are capped at 100k across the dump; children past the budget
//!   are dropped, matching the archive-wide \`parse_snapshot\` limit.
//! - Absolute coordinates are composed in \`i64\` and rejected beyond +/-1e6
//!   instead of silently wrapping Kotlin's \`Int\`; a non-finite drawing
//!   translation rejects the window because Kotlin's \`roundToInt\` throws on NaN.
//! - Non-finite attribute floats are omitted and alpha is clamped to 0..=1 so
//!   the emitted JSON always stays protocol-valid.
//! - A wire string is accepted for the full \`u16\` length (Kotlin rejects
//!   >32767 bytes) and invalid UTF-8 is replaced, matching Kotlin's decoder.

#[cfg(test)]
mod tests;

use aps_platform::CancellationToken;
use serde_json::{Map, Value};
use std::collections::BTreeMap;
use std::io::{Cursor, Read};
use zip::ZipArchive;

/// Bounded input and output budget for one visible-window dump. A real device
/// dump is far smaller; oversized entries are rejected rather than truncated.
pub const MAX_VIEW_DUMP_BYTES: usize = 64 * 1024 * 1024;

/// Kotlin \`VisibleWindowHierarchyParser.MAX_ENTRY_BYTES\`.
const MAX_ENTRY_BYTES: usize = 16 * 1024 * 1024;
/// A real device reports <=20 visible windows; the extra room is still bounded.
const MAX_ENTRIES: usize = 1024;
/// Bounded entry-name bytes (entry names are never written to disk here).
const MAX_ENTRY_NAME_BYTES: usize = 4096;
/// Archive ingestion allows 100k nodes; stop projecting at the same budget.
const MAX_DECODED_NODES: usize = 100_000;
/// Kotlin projects unbounded depth; ingestion accepts depth <= 64, so keep the
/// deepest emitted node at 63 to stay strictly inside that contract.
const MAX_NODE_DEPTH: usize = 63;
/// Kotlin \`EncodedHierarchyDecoder.MAX_DEPTH\` (wire format nesting).
const MAX_WIRE_DEPTH: usize = 512;
/// Kotlin \`EncodedHierarchyDecoder.MAX_VALUES\`.
const MAX_WIRE_VALUES: usize = 1_000_000;
/// Absolute screen coordinates beyond this are treated as corrupt.
const MAX_ABS_COORD: i64 = 1_000_000;
const CANCEL_MESSAGE: &str = "Visible-window hierarchy capture cancelled";
const NODE_LIMIT_MESSAGE: &str = "Visible-window hierarchy has too many nodes";
const SYSTEM_UI_PACKAGE: &str = "com.android.systemui";
const SYSTEM_UI_WINDOW_TITLES: [&str; 14] = [
    "BackPanel",
    "Bouncer",
    "GlobalActions",
    "HeadsUp",
    "Keyguard",
    "NavigationBar",
    "NotificationShade",
    "PipMenu",
    "ScreenDecor",
    "Screenshot",
    "Shell",
    "StatusBar",
    "Taskbar",
    "VolumeDialog",
];
const COMPOSE_HOST_SUFFIXES: [&str; 2] = [".ComposeView", ".AndroidComposeView"];
const CHILD_PREFIX: &str = "meta:__child__";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum WindowTarget {
    ForegroundApp,
    SystemUi,
}

/// One decoded window, before it is projected into protocol snapshot JSON.
#[derive(Clone, Debug)]
pub struct WindowDump {
    /// Protocol window id: \`window:<sanitized entry token>\`.
    pub id: String,
    pub title: String,
    /// Protocol \`WindowType\` name: \`ACTIVITY\`, \`POPUP\`, \`DIALOG\` or \`OTHER\`.
    pub kind: &'static str,
    pub root: DumpNode,
}

#[derive(Clone, Debug)]
pub struct DumpNode {
    pub class_name: String,
    /// Absolute screen bounds \`(left, top, right, bottom)\`, already composed
    /// from parent position, scroll offsets and drawing translation.
    pub bounds: (i32, i32, i32, i32),
    pub visible: bool,
    pub alpha: f64,
    pub resource_name: Option<String>,
    pub text: Option<String>,
    /// Protocol \`ViewAttributes\` JSON object consumed by \`parse_snapshot\`.
    pub attributes: Value,
    pub children: Vec<DumpNode>,
}

/// Result of promoting a UIAutomator projection to Compose semantics.
#[derive(Clone, Copy, Debug, Default)]
pub struct Promotion {
    /// A \`ComposeView\`/\`AndroidComposeView\` host exists in the tree.
    pub compose_host: bool,
}

/// Decode one \`dump-visible-window-views\` ZIP into the target package's windows.
///
/// Kotlin parity: entries are selected by package name (or System UI window
/// titles / launcher taskbar), non-decodable entries are skipped, and the
/// command fails when no window decodes. Window order follows the ZIP.
pub fn parse_windows(
    zip: &[u8],
    package: &str,
    target: WindowTarget,
    cancel: &CancellationToken,
) -> Result<Vec<WindowDump>, String> {
    check_cancel(cancel)?;
    if zip.is_empty() {
        return Err("Visible-window hierarchy is empty".into());
    }
    if zip.len() > MAX_VIEW_DUMP_BYTES {
        return Err("Visible-window hierarchy exceeds input limit".into());
    }
    let mut archive = ZipArchive::new(Cursor::new(zip))
        .map_err(|_| "Visible-window hierarchy is not a ZIP archive".to_string())?;
    if archive.len() > MAX_ENTRIES {
        return Err("Visible-window hierarchy has too many entries".into());
    }
    let mut windows = Vec::new();
    let mut decoded_bytes = 0usize;
    let mut node_budget = MAX_DECODED_NODES;
    for index in 0..archive.len() {
        check_cancel(cancel)?;
        let mut entry = archive
            .by_index(index)
            .map_err(|_| "Visible-window hierarchy ZIP is unreadable".to_string())?;
        if entry.is_dir() {
            continue;
        }
        let name = entry
            .name()
            .map_err(|_| "Visible-window hierarchy entry name is invalid".to_string())?
            .into_owned();
        if name.len() > MAX_ENTRY_NAME_BYTES {
            return Err("Visible-window hierarchy entry name is too large".into());
        }
        // Kotlin skips non-matching entries without decoding their payload.
        if !matches_target(&name, package, target) {
            continue;
        }
        if entry.size() > MAX_ENTRY_BYTES as u64 {
            return Err("Visible-window hierarchy entry is too large".into());
        }
        let mut payload = Vec::new();
        let mut buffer = [0u8; 64 * 1024];
        loop {
            check_cancel(cancel)?;
            let count = entry
                .read(&mut buffer)
                .map_err(|_| "Visible-window hierarchy entry is unreadable".to_string())?;
            if count == 0 {
                break;
            }
            decoded_bytes = decoded_bytes
                .checked_add(count)
                .ok_or_else(|| "Visible-window hierarchy exceeds input limit".to_string())?;
            if decoded_bytes > MAX_VIEW_DUMP_BYTES || payload.len() + count > MAX_ENTRY_BYTES {
                return Err("Visible-window hierarchy entry is too large".into());
            }
            payload.extend_from_slice(&buffer[..count]);
        }
        // Kotlin wraps decode+projection in runCatching: a corrupt window is
        // skipped instead of failing the whole capture.
        if let Ok(root) = decode_window(&payload, &mut node_budget) {
            windows.push(WindowDump {
                id: window_id(&name),
                title: window_title(&name, package),
                kind: window_kind(&name),
                root,
            });
        }
        if node_budget == 0 {
            break;
        }
    }
    if windows.is_empty() {
        return Err(format!(
            "Visible-window hierarchy has no decodable window for {package}"
        ));
    }
    check_cancel(cancel)?;
    Ok(windows)
}

/// Project decoded windows into protocol \`WindowSnapshot\` JSON objects for
/// \`crate::parse_snapshot\`: \`{id, title, type, bounds, root}\`. Node ids are
/// namespaced as \`<window id>/<root-relative path>\` so they stay unique across
/// the archive.
pub fn snapshot_windows(windows: &[WindowDump]) -> Vec<Value> {
    windows
        .iter()
        .map(|window| {
            let mut snapshot = Map::new();
            snapshot.insert("id".into(), Value::String(window.id.clone()));
            snapshot.insert("title".into(), Value::String(window.title.clone()));
            snapshot.insert("type".into(), Value::String(window.kind.to_owned()));
            snapshot.insert("bounds".into(), bounds_value(window.root.bounds));
            snapshot.insert(
                "root".into(),
                snapshot_node(&window.root, &window.id, "root"),
            );
            Value::Object(snapshot)
        })
        .collect()
}

fn snapshot_node(node: &DumpNode, window_id: &str, path: &str) -> Value {
    let mut snapshot = Map::new();
    snapshot.insert("id".into(), Value::String(format!("{window_id}/{path}")));
    snapshot.insert("type".into(), Value::String("view".into()));
    snapshot.insert("className".into(), Value::String(node.class_name.clone()));
    snapshot.insert("bounds".into(), bounds_value(node.bounds));
    snapshot.insert("visible".into(), Value::Bool(node.visible));
    snapshot.insert("alpha".into(), number_json(node.alpha as f32));
    if let Some(resource_name) = &node.resource_name {
        snapshot.insert("resourceName".into(), Value::String(resource_name.clone()));
    }
    if let Some(text) = &node.text {
        snapshot.insert("text".into(), Value::String(text.clone()));
    }
    snapshot.insert("attributes".into(), node.attributes.clone());
    if !node.children.is_empty() {
        let children = node
            .children
            .iter()
            .enumerate()
            .map(|(index, child)| snapshot_node(child, window_id, &format!("{path}/{index}")))
            .collect();
        snapshot.insert("children".into(), Value::Array(children));
    }
    Value::Object(snapshot)
}

/// Total decoded node count across all windows.
pub fn node_count(windows: &[WindowDump]) -> usize {
    windows
        .iter()
        .map(|window| node_count_of(&window.root))
        .sum()
}

/// Whether any decoded window class name is a Compose host
/// (\`.ComposeView\` or \`.AndroidComposeView\`).
pub fn contains_compose_host(windows: &[WindowDump]) -> bool {
    windows
        .iter()
        .any(|window| node_contains_compose_host(&window.root))
}

/// Kotlin \`defaultWindow\`: the window with the most nodes (first wins on ties).
pub fn default_window_id(windows: &[WindowDump]) -> Option<String> {
    let mut best: Option<(usize, &WindowDump)> = None;
    for window in windows {
        let count = node_count_of(&window.root);
        match best {
            Some((best_count, _)) if count <= best_count => {}
            _ => best = Some((count, window)),
        }
    }
    best.map(|(_, window)| window.id.clone())
}

/// Kotlin parity for UIAutomator projection (\`promoteComposeSemantics\`): every
/// node strictly inside a Compose host becomes a protocol \`compose\` node with
/// the semantic class label, role and semantic properties. The host itself and
/// nodes outside any host stay \`view\` nodes.
pub fn promote_compose_semantics(root: &mut Value) -> Promotion {
    let mut promotion = Promotion::default();
    promote_node(root, false, &mut promotion);
    promotion
}

/// Count protocol node JSON objects (\`children\` arrays) in a tree.
pub fn count_json_nodes(root: &Value) -> usize {
    let Some(map) = root.as_object() else {
        return 0;
    };
    let mut count = 1;
    if let Some(children) = map.get("children").and_then(Value::as_array) {
        for child in children {
            count += count_json_nodes(child);
        }
    }
    count
}

// ---------------------------------------------------------------------------
// Window identity / selection (Kotlin VisibleWindowHierarchyParser parity)
// ---------------------------------------------------------------------------

fn check_cancel(cancel: &CancellationToken) -> Result<(), String> {
    if cancel.is_cancelled() {
        Err(CANCEL_MESSAGE.into())
    } else {
        Ok(())
    }
}

fn matches_target(entry_name: &str, package: &str, target: WindowTarget) -> bool {
    if entry_name.contains(package) {
        return true;
    }
    // Kotlin gates the title fallback on `packageName == com.android.systemui`;
    // this API makes the target explicit, so accept either signal.
    if target != WindowTarget::SystemUi && package != SYSTEM_UI_PACKAGE {
        return false;
    }
    let title = entry_title(entry_name);
    if SYSTEM_UI_WINDOW_TITLES
        .iter()
        .any(|candidate| title.starts_with(candidate))
    {
        return true;
    }
    is_launcher_taskbar(entry_name)
}

/// Kotlin \`ZipEntry.entryTitle\`: text after the first space, then after the last
/// \`/\`, falling back to the whole name when a delimiter is absent.
fn entry_title(entry_name: &str) -> &str {
    let after_space = entry_name
        .split_once(' ')
        .map(|(_, rest)| rest)
        .unwrap_or(entry_name);
    after_space.rsplit('/').next().unwrap_or(after_space)
}

/// Android 16 dumps the launcher taskbar without the System UI package name.
fn is_launcher_taskbar(entry_name: &str) -> bool {
    let lower = entry_name.to_lowercase();
    lower.contains("launcher")
        && lower.contains("taskbar")
        && !lower.contains("com.android.systemui")
}

fn window_id(entry_name: &str) -> String {
    // Kotlin: substringBefore(' ').substringBefore('/')
    let token = entry_name.split(' ').next().unwrap_or(entry_name);
    let token = token.split('/').next().unwrap_or(token);
    let mut stable: String = token
        .chars()
        .map(|character| {
            if character.is_ascii_alphanumeric() || matches!(character, '.' | '_' | '-') {
                character
            } else {
                '_'
            }
        })
        .collect();
    if stable.is_empty() {
        stable = format!("{:x}", java_hash_code(entry_name) as u32);
    }
    format!("window:{stable}")
}

/// Kotlin \`String.hashCode\` (Java algorithm, wrapping i32).
fn java_hash_code(value: &str) -> i32 {
    let mut hash = 0i32;
    for character in value.chars() {
        hash = hash.wrapping_mul(31).wrapping_add(character as u32 as i32);
    }
    hash
}

fn window_title(entry_name: &str, package: &str) -> String {
    // Kotlin: substringAfterLast('/').substringAfterLast(' ')
    let component = entry_name.rsplit('/').next().unwrap_or(entry_name);
    let component = component.rsplit(' ').next().unwrap_or(component);
    // Kotlin: removePrefix("$packageName.").removePrefix(packageName)
    let without_prefix = component
        .strip_prefix(package)
        .map(|rest| rest.strip_prefix('.').unwrap_or(rest))
        .unwrap_or(component);
    let without_prefix = without_prefix.trim_start_matches(['.', '/']);
    let title = without_prefix.rsplit('.').next().unwrap_or(without_prefix);
    if title.trim().is_empty() {
        "Window".into()
    } else {
        title.to_owned()
    }
}

fn window_kind(entry_name: &str) -> &'static str {
    let lower = entry_name.to_lowercase();
    if lower.contains("activity") {
        "ACTIVITY"
    } else if lower.contains("popup") {
        "POPUP"
    } else if lower.contains("dialog") {
        "DIALOG"
    } else {
        "OTHER"
    }
}

fn node_count_of(node: &DumpNode) -> usize {
    1 + node.children.iter().map(node_count_of).sum::<usize>()
}

fn node_contains_compose_host(node: &DumpNode) -> bool {
    is_compose_host_class(&node.class_name) || node.children.iter().any(node_contains_compose_host)
}

fn is_compose_host_class(class_name: &str) -> bool {
    COMPOSE_HOST_SUFFIXES
        .iter()
        .any(|suffix| class_name.ends_with(suffix))
}

// ---------------------------------------------------------------------------
// ViewHierarchyEncoder wire decoder
// ---------------------------------------------------------------------------

#[derive(Debug)]
enum WireValue {
    Bool(bool),
    Byte(i8),
    Short(i16),
    Int(i32),
    Long(i64),
    Float(f32),
    Double(f64),
    String(String),
    Map(WireMap),
}

#[derive(Debug, Default)]
struct WireMap(Vec<(i16, WireValue)>);

impl WireMap {
    fn get(&self, id: i16) -> Option<&WireValue> {
        // Kotlin uses a LinkedHashMap: the last write for a key wins.
        self.0
            .iter()
            .rev()
            .find(|(key, _)| *key == id)
            .map(|(_, value)| value)
    }
}

impl WireValue {
    /// Kotlin \`(value as? Number)?.toInt()\`, including Kotlin's float-to-int
    /// saturation (NaN -> 0, +inf -> i32::MAX, -inf -> i32::MIN).
    fn to_i32(&self) -> Option<i32> {
        match self {
            Self::Byte(value) => Some(i32::from(*value)),
            Self::Short(value) => Some(i32::from(*value)),
            Self::Int(value) => Some(*value),
            Self::Long(value) => Some(*value as i32),
            Self::Float(value) => Some(*value as i32),
            Self::Double(value) => Some(*value as i32),
            _ => None,
        }
    }

    /// Kotlin \`(value as? Number)?.toFloat()\`.
    fn to_f32(&self) -> Option<f32> {
        match self {
            Self::Byte(value) => Some(f32::from(*value)),
            Self::Short(value) => Some(f32::from(*value)),
            Self::Int(value) => Some(*value as f32),
            Self::Long(value) => Some(*value as f32),
            Self::Float(value) => Some(*value),
            Self::Double(value) => Some(*value as f32),
            _ => None,
        }
    }

    fn as_bool(&self) -> Option<bool> {
        match self {
            Self::Bool(value) => Some(*value),
            _ => None,
        }
    }

    fn as_str(&self) -> Option<&str> {
        match self {
            Self::String(value) => Some(value),
            _ => None,
        }
    }

    fn as_map(&self) -> Option<&WireMap> {
        match self {
            Self::Map(map) => Some(map),
            _ => None,
        }
    }
}

enum WireError {
    /// Transport EOF: Kotlin's \`decodeDocument\` stops on EOFException.
    Eof,
    Malformed(String),
}

struct WireReader<'a> {
    bytes: &'a [u8],
    position: usize,
    values: usize,
}

impl<'a> WireReader<'a> {
    fn take(&mut self, count: usize) -> Result<&'a [u8], WireError> {
        let end = self
            .position
            .checked_add(count)
            .ok_or_else(|| WireError::Malformed("Encoded hierarchy offset overflow".into()))?;
        if end > self.bytes.len() {
            return Err(WireError::Eof);
        }
        let slice = &self.bytes[self.position..end];
        self.position = end;
        Ok(slice)
    }

    fn read_value(&mut self, depth: usize) -> Result<WireValue, WireError> {
        if depth > MAX_WIRE_DEPTH {
            return Err(WireError::Malformed(
                "Encoded hierarchy is too deeply nested".into(),
            ));
        }
        self.values += 1;
        if self.values > MAX_WIRE_VALUES {
            return Err(WireError::Malformed(
                "Encoded hierarchy has too many values".into(),
            ));
        }
        let signature = self.take(1)?[0];
        match signature {
            b'Z' => Ok(WireValue::Bool(self.take(1)?[0] != 0)),
            b'B' => Ok(WireValue::Byte(self.take(1)?[0] as i8)),
            b'S' => Ok(WireValue::Short(i16::from_be_bytes(
                self.take(2)?.try_into().unwrap_or([0, 0]),
            ))),
            b'I' => Ok(WireValue::Int(i32::from_be_bytes(
                self.take(4)?.try_into().unwrap_or([0; 4]),
            ))),
            b'J' => Ok(WireValue::Long(i64::from_be_bytes(
                self.take(8)?.try_into().unwrap_or([0; 8]),
            ))),
            b'F' => Ok(WireValue::Float(f32::from_be_bytes(
                self.take(4)?.try_into().unwrap_or([0; 4]),
            ))),
            b'D' => Ok(WireValue::Double(f64::from_be_bytes(
                self.take(8)?.try_into().unwrap_or([0; 8]),
            ))),
            b'R' => Ok(WireValue::String(self.read_string()?)),
            b'M' => Ok(WireValue::Map(self.read_map(depth + 1)?)),
            other => Err(WireError::Malformed(format!(
                "Unsupported encoded hierarchy type: {}",
                char::from(other)
            ))),
        }
    }

    fn read_map(&mut self, depth: usize) -> Result<WireMap, WireError> {
        let mut entries = Vec::new();
        loop {
            let key = self.read_value(depth)?;
            let WireValue::Short(key) = key else {
                return Err(WireError::Malformed(
                    "Encoded hierarchy map key is not a short".into(),
                ));
            };
            if key == 0 {
                return Ok(WireMap(entries));
            }
            entries.push((key, self.read_value(depth)?));
        }
    }

    fn read_string(&mut self) -> Result<String, WireError> {
        // The wire length is a big-endian u16, so it is inherently <= 64 KiB.
        let length = u16::from_be_bytes(self.take(2)?.try_into().unwrap_or([0, 0])) as usize;
        let bytes = self.take(length)?;
        Ok(String::from_utf8_lossy(bytes).into_owned())
    }
}

fn decode_window(payload: &[u8], node_budget: &mut usize) -> Result<DumpNode, String> {
    let values = decode_values(payload)?;
    // Kotlin: the first map is the root view; the last map after it is the
    // property-name index; the typed pairs before the root are the window
    // prefix (\`window:left\` / \`window:top\`).
    let root_index = values
        .iter()
        .position(|value| matches!(value, WireValue::Map(_)))
        .ok_or_else(|| "Encoded hierarchy has no root view".to_string())?;
    let WireValue::Map(root) = &values[root_index] else {
        return Err("Encoded hierarchy has no root view".into());
    };
    let property_index = values[root_index + 1..]
        .iter()
        .rev()
        .find_map(|value| match value {
            WireValue::Map(map) => Some(map),
            _ => None,
        })
        .ok_or_else(|| "Encoded hierarchy has no property index".to_string())?;
    let mut property_names = BTreeMap::new();
    for (id, value) in &property_index.0 {
        if let WireValue::String(name) = value {
            property_names.insert(*id, name.clone());
        }
    }
    let mut parent_left = 0i32;
    let mut parent_top = 0i32;
    for pair in values[..root_index].chunks(2) {
        let (Some(WireValue::Short(id)), Some(value)) = (pair.first(), pair.get(1)) else {
            continue;
        };
        let Some(name) = property_names.get(id) else {
            continue;
        };
        match name.as_str() {
            "window:left" => parent_left = value.to_i32().unwrap_or(0),
            "window:top" => parent_top = value.to_i32().unwrap_or(0),
            _ => {}
        }
    }
    let frame = Frame {
        left: parent_left,
        top: parent_top,
        scroll_x: 0,
        scroll_y: 0,
        visible: true,
    };
    project_node(root, &property_names, frame, 0, node_budget)
}

fn decode_values(payload: &[u8]) -> Result<Vec<WireValue>, String> {
    let mut reader = WireReader {
        bytes: payload,
        position: 0,
        values: 0,
    };
    let mut values = Vec::new();
    loop {
        match reader.read_value(0) {
            Ok(value) => values.push(value),
            Err(WireError::Eof) => break,
            Err(WireError::Malformed(message)) => return Err(message),
        }
    }
    Ok(values)
}

// ---------------------------------------------------------------------------
// Projection to protocol nodes
// ---------------------------------------------------------------------------

#[derive(Clone, Copy)]
struct Frame {
    left: i32,
    top: i32,
    scroll_x: i32,
    scroll_y: i32,
    visible: bool,
}

fn named_properties<'a>(
    map: &'a WireMap,
    names: &'a BTreeMap<i16, String>,
) -> BTreeMap<&'a str, &'a WireValue> {
    let mut properties = BTreeMap::new();
    for (id, value) in &map.0 {
        if let Some(name) = names.get(id) {
            properties.insert(name.as_str(), value);
        }
    }
    properties
}

fn project_node(
    map: &WireMap,
    names: &BTreeMap<i16, String>,
    frame: Frame,
    depth: usize,
    budget: &mut usize,
) -> Result<DumpNode, String> {
    if *budget == 0 {
        return Err(NODE_LIMIT_MESSAGE.into());
    }
    *budget -= 1;
    let properties = named_properties(map, names);

    let left = int_property(&properties, "layout:left");
    let top = int_property(&properties, "layout:top");
    let right = int_property(&properties, "layout:right");
    let bottom = int_property(&properties, "layout:bottom");
    let translation_x = round_translation(number_property(&properties, "drawing:translationX"))?;
    let translation_y = round_translation(number_property(&properties, "drawing:translationY"))?;
    let absolute_left =
        frame.left as i64 - frame.scroll_x as i64 + left as i64 + i64::from(translation_x);
    let absolute_top =
        frame.top as i64 - frame.scroll_y as i64 + top as i64 + i64::from(translation_y);
    let width = (right as i64 - left as i64).max(0);
    let height = (bottom as i64 - top as i64).max(0);
    let absolute_right = absolute_left + width;
    let absolute_bottom = absolute_top + height;
    let bounds = (
        checked_coord(absolute_left)?,
        checked_coord(absolute_top)?,
        checked_coord(absolute_right)?,
        checked_coord(absolute_bottom)?,
    );

    // Kotlin `properties.number("drawing:alpha", default = 1f)`.
    let alpha = float_property_or_null(&properties, "drawing:alpha").unwrap_or(1.0);
    let visible =
        frame.visible && int_property(&properties, "misc:visibility") == 0 && alpha > 0.0f32;
    let class_name = properties
        .get("meta:__name__")
        .and_then(|value| value.as_str())
        .filter(|name| !name.trim().is_empty())
        .unwrap_or("android.view.View")
        .to_owned();
    let resource_name = properties
        .get("id")
        .and_then(|value| value.as_str())
        .filter(|id| !id.trim().is_empty() && *id != "NO_ID")
        .map(str::to_owned);
    let text = string_property_or_null(&properties, "text:text")
        .or_else(|| string_property_or_null(&properties, "text:mText"))
        .map(str::to_owned);
    let attributes = build_attributes(&properties, names);
    // Kotlin's protocol alpha must satisfy 0..=1 for archive ingestion.
    let stored_alpha = if alpha.is_finite() {
        f64::from(alpha.clamp(0.0, 1.0))
    } else {
        0.0
    };

    let mut projected_children = Vec::new();
    if depth < MAX_NODE_DEPTH {
        // Kotlin iterates its LinkedHashMap entries and sorts by the parsed
        // `meta:__child__<n>` suffix (unknown suffixes first, stable otherwise).
        // Collect from the wire map so equal/non-numeric suffixes keep wire order
        // instead of a lexicographic BTreeMap order.
        let mut children: Vec<(Option<i32>, usize, &WireMap)> = map
            .0
            .iter()
            .enumerate()
            .filter_map(|(position, (id, value))| {
                let name = names.get(id)?;
                if !name.starts_with(CHILD_PREFIX) {
                    return None;
                }
                value
                    .as_map()
                    .map(|child| (child_sort_index(name), position, child))
            })
            .collect();
        children.sort_by(compare_child_entries);
        let child_frame = Frame {
            left: bounds.0,
            top: bounds.1,
            scroll_x: int_property(&properties, "scrolling:scrollX"),
            scroll_y: int_property(&properties, "scrolling:scrollY"),
            visible,
        };
        for (_, _, child) in children {
            if *budget == 0 {
                break;
            }
            projected_children.push(project_node(child, names, child_frame, depth + 1, budget)?);
        }
    }

    Ok(DumpNode {
        class_name,
        bounds,
        visible,
        alpha: stored_alpha,
        resource_name,
        text,
        attributes,
        children: projected_children,
    })
}

/// Kotlin's \`properties.int(name)\`: numeric properties only, defaulting to 0.
fn int_property(properties: &BTreeMap<&str, &WireValue>, name: &str) -> i32 {
    properties
        .get(name)
        .and_then(|value| value.to_i32())
        .unwrap_or(0)
}

fn int_property_or_null(properties: &BTreeMap<&str, &WireValue>, name: &str) -> Option<i32> {
    properties.get(name).and_then(|value| value.to_i32())
}

/// Kotlin's \`properties.number(name, default = 0f)\`.
fn number_property(properties: &BTreeMap<&str, &WireValue>, name: &str) -> f32 {
    properties
        .get(name)
        .and_then(|value| value.to_f32())
        .unwrap_or(0.0)
}

fn float_property_or_null(properties: &BTreeMap<&str, &WireValue>, name: &str) -> Option<f32> {
    properties.get(name).and_then(|value| value.to_f32())
}

fn bool_property_or_null(properties: &BTreeMap<&str, &WireValue>, name: &str) -> Option<bool> {
    properties.get(name).and_then(|value| value.as_bool())
}

/// Kotlin's \`stringOrNull\`: non-blank strings that are not the literal "null".
fn string_property_or_null<'a>(
    properties: &BTreeMap<&'a str, &'a WireValue>,
    name: &str,
) -> Option<&'a str> {
    properties
        .get(name)
        .and_then(|value| value.as_str())
        .filter(|text| !text.trim().is_empty() && *text != "null")
}

/// Kotlin \`roundToInt\`: round half towards positive infinity (Math.round); NaN
/// throws and non-finite values are rejected because the window is untrustworthy.
fn round_translation(value: f32) -> Result<i32, String> {
    if !value.is_finite() {
        return Err("Encoded hierarchy has a non-finite drawing translation".into());
    }
    let rounded = (f64::from(value) + 0.5).floor();
    if rounded < f64::from(i32::MIN) || rounded > f64::from(i32::MAX) {
        return Err("Encoded hierarchy has an absurd drawing translation".into());
    }
    Ok(rounded as i32)
}

fn checked_coord(value: i64) -> Result<i32, String> {
    if !(-MAX_ABS_COORD..=MAX_ABS_COORD).contains(&value) {
        return Err("Encoded hierarchy has an absurd view coordinate".into());
    }
    Ok(value as i32)
}

fn child_sort_index(name: &str) -> Option<i32> {
    name.strip_prefix(CHILD_PREFIX)?.parse().ok()
}

/// Kotlin `sortedBy { it.toIntOrNull() }` puts unparsable child names first
/// and keeps the underlying linked-map order for equal keys.
fn compare_child_entries(
    left: &(Option<i32>, usize, &WireMap),
    right: &(Option<i32>, usize, &WireMap),
) -> std::cmp::Ordering {
    match (left.0, right.0) {
        (None, None) => left.1.cmp(&right.1),
        (None, Some(_)) => std::cmp::Ordering::Less,
        (Some(_), None) => std::cmp::Ordering::Greater,
        (Some(left), Some(right)) => left.cmp(&right),
    }
}

// ---------------------------------------------------------------------------
// ViewAttributes projection
// ---------------------------------------------------------------------------

fn build_attributes(
    properties: &BTreeMap<&str, &WireValue>,
    names: &BTreeMap<i16, String>,
) -> Value {
    let mut attributes = Map::new();
    if let Some(visibility) = int_property_or_null(properties, "misc:visibility") {
        attributes.insert(
            "visibility".into(),
            Value::String(visibility_label(visibility)),
        );
    }
    attributes.insert(
        "layoutBounds".into(),
        bounds_value((
            int_property(properties, "layout:left"),
            int_property(properties, "layout:top"),
            int_property(properties, "layout:right"),
            int_property(properties, "layout:bottom"),
        )),
    );
    let elevation = float_property_or_null(properties, "drawing:elevation");
    let translation_z = float_property_or_null(properties, "drawing:translationZ");
    put_float(&mut attributes, "elevation", elevation);
    if elevation.is_some() || translation_z.is_some() {
        let z = elevation.unwrap_or(0.0) + translation_z.unwrap_or(0.0);
        put_float(&mut attributes, "z", Some(z));
    }
    put_float(
        &mut attributes,
        "translationX",
        float_property_or_null(properties, "drawing:translationX"),
    );
    put_float(
        &mut attributes,
        "translationY",
        float_property_or_null(properties, "drawing:translationY"),
    );
    put_float(&mut attributes, "translationZ", translation_z);
    put_float(
        &mut attributes,
        "rotation",
        float_property_or_null(properties, "drawing:rotation"),
    );
    put_float(
        &mut attributes,
        "rotationX",
        float_property_or_null(properties, "drawing:rotationX"),
    );
    put_float(
        &mut attributes,
        "rotationY",
        float_property_or_null(properties, "drawing:rotationY"),
    );
    put_float(
        &mut attributes,
        "scaleX",
        float_property_or_null(properties, "drawing:scaleX"),
    );
    put_float(
        &mut attributes,
        "scaleY",
        float_property_or_null(properties, "drawing:scaleY"),
    );
    put_float(
        &mut attributes,
        "pivotX",
        float_property_or_null(properties, "drawing:pivotX"),
    );
    put_float(
        &mut attributes,
        "pivotY",
        float_property_or_null(properties, "drawing:pivotY"),
    );
    // Kotlin always passes a non-null EdgeInsets (zero sides defaulted).
    attributes.insert(
        "padding".into(),
        insets_value([
            int_property(properties, "padding:paddingLeft"),
            int_property(properties, "padding:paddingTop"),
            int_property(properties, "padding:paddingRight"),
            int_property(properties, "padding:paddingBottom"),
        ]),
    );

    let encoded_layout_params = properties
        .get("layoutParams")
        .and_then(|value| value.as_map());
    let layout_params = encoded_layout_params
        .map(|map| named_properties(map, names))
        .unwrap_or_default();
    if !layout_params.is_empty() {
        attributes.insert(
            "margin".into(),
            insets_value([
                int_property(&layout_params, "leftMargin"),
                int_property(&layout_params, "topMargin"),
                int_property(&layout_params, "rightMargin"),
                int_property(&layout_params, "bottomMargin"),
            ]),
        );
        put_integer(
            &mut attributes,
            "layoutWidth",
            int_property_or_null(&layout_params, "width"),
        );
        put_integer(
            &mut attributes,
            "layoutHeight",
            int_property_or_null(&layout_params, "height"),
        );
    }
    // Kotlin falls back to the raw value at property id 3 for the class name.
    let layout_params_class = layout_params
        .get("class")
        .and_then(|value| value.as_str())
        .map(str::to_owned)
        .or_else(|| {
            encoded_layout_params
                .and_then(|map| map.get(3))
                .and_then(|value| value.as_str())
                .map(str::to_owned)
        })
        .filter(|class| !class.trim().is_empty());
    if let Some(class) = layout_params_class {
        attributes.insert("layoutParamsClass".into(), Value::String(class));
    }
    for (field, key) in [
        ("measuredWidth", "measurement:measuredWidth"),
        ("measuredHeight", "measurement:measuredHeight"),
        ("minWidth", "measurement:minWidth"),
        ("minHeight", "measurement:minHeight"),
    ] {
        put_integer(
            &mut attributes,
            field,
            int_property_or_null(properties, key),
        );
    }
    for (field, key) in [
        ("scrollX", "scrolling:scrollX"),
        ("scrollY", "scrolling:scrollY"),
    ] {
        put_integer(
            &mut attributes,
            field,
            int_property_or_null(properties, key),
        );
    }
    if let Some(clip_bounds) = properties
        .get("drawing:clipBounds")
        .and_then(|value| value.as_str())
        .and_then(parse_rect)
    {
        attributes.insert("clipBounds".into(), bounds_value(clip_bounds));
    }
    put_bool(
        &mut attributes,
        "clipChildren",
        bool_property_or_null(properties, "drawing:clipChildren"),
    );
    put_bool(
        &mut attributes,
        "clipToPadding",
        bool_property_or_null(properties, "drawing:clipToPadding"),
    );
    let background = string_property_or_null(properties, "drawing:background")
        .map(str::to_owned)
        .or_else(|| map_class_name(properties.get("drawing:background").copied(), names));
    if let Some(background) = background {
        attributes.insert("background".into(), Value::String(background));
    }
    if let Some(background_color) = string_property_or_null(properties, "drawing:backgroundColor") {
        attributes.insert(
            "backgroundColor".into(),
            Value::String(background_color.to_owned()),
        );
    }
    let foreground = string_property_or_null(properties, "drawing:foreground")
        .map(str::to_owned)
        .or_else(|| map_class_name(properties.get("drawing:foreground").copied(), names));
    if let Some(foreground) = foreground {
        attributes.insert("foreground".into(), Value::String(foreground));
    }
    for (field, key) in [
        ("opaque", "drawing:opaque"),
        ("willNotDraw", "drawing:willNotDraw"),
        ("hardwareAccelerated", "drawing:hardwareAccelerated"),
    ] {
        put_bool(
            &mut attributes,
            field,
            bool_property_or_null(properties, key),
        );
    }
    if let Some(layer_type) = int_property_or_null(properties, "drawing:layerType") {
        attributes.insert(
            "layerType".into(),
            Value::String(layer_type_label(layer_type)),
        );
    }
    for (field, key) in [
        ("enabled", "misc:enabled"),
        ("clickable", "misc:clickable"),
        ("longClickable", "misc:longClickable"),
        ("selected", "misc:selected"),
    ] {
        put_bool(
            &mut attributes,
            field,
            bool_property_or_null(properties, key),
        );
    }
    put_bool(
        &mut attributes,
        "focusable",
        bool_property_or_null(properties, "focus:isFocusable"),
    );
    put_bool(
        &mut attributes,
        "focused",
        bool_property_or_null(properties, "focus:isFocused"),
    );
    let content_description =
        string_property_or_null(properties, "accessibility:getContentDescription()")
            .or_else(|| string_property_or_null(properties, "contentDescription"))
            .map(str::to_owned);
    if let Some(content_description) = content_description {
        attributes.insert(
            "contentDescription".into(),
            Value::String(content_description),
        );
    }
    let raw_properties = raw_properties(properties, names);
    attributes.insert(
        "rawProperties".into(),
        Value::Object(
            raw_properties
                .into_iter()
                .map(|(key, value)| (key, Value::String(value)))
                .collect(),
        ),
    );
    Value::Object(attributes)
}

fn put_float(attributes: &mut Map<String, Value>, key: &str, value: Option<f32>) {
    if let Some(value) = value.filter(|value| value.is_finite()) {
        attributes.insert(key.into(), number_json(value));
    }
}

fn put_integer(attributes: &mut Map<String, Value>, key: &str, value: Option<i32>) {
    if let Some(value) = value {
        attributes.insert(key.into(), Value::from(value));
    }
}

fn put_bool(attributes: &mut Map<String, Value>, key: &str, value: Option<bool>) {
    if let Some(value) = value {
        attributes.insert(key.into(), Value::Bool(value));
    }
}

/// Kotlin's map-typed background/foreground fallback: \`named["class"] as? String\`.
fn map_class_name(value: Option<&WireValue>, names: &BTreeMap<i16, String>) -> Option<String> {
    let WireValue::Map(map) = value? else {
        return None;
    };
    named_properties(map, names)
        .get("class")
        .and_then(|value| value.as_str())
        .map(str::to_owned)
}

/// Sorted \`rawProperties\`: \`meta:*\` and map values are dropped, LayoutParams is
/// flattened with its class, and scalar values use Kotlin's \`toString()\`.
fn raw_properties(
    properties: &BTreeMap<&str, &WireValue>,
    names: &BTreeMap<i16, String>,
) -> BTreeMap<String, String> {
    let mut raw = BTreeMap::new();
    for (name, value) in properties {
        if *name == "layoutParams"
            && let Some(map) = value.as_map()
        {
            let layout_params = named_properties(map, names);
            let class_value = layout_params.get("class").copied().or_else(|| map.get(3));
            if let Some(class_value) = class_value {
                raw.insert("layoutParams:class".into(), raw_value_string(class_value));
            }
            for (layout_name, layout_value) in &layout_params {
                if !matches!(layout_value, WireValue::Map(_)) {
                    raw.insert(
                        format!("layoutParams:{layout_name}"),
                        raw_value_string(layout_value),
                    );
                }
            }
            continue;
        }
        if name.starts_with("meta:") || matches!(value, WireValue::Map(_)) {
            continue;
        }
        raw.insert((*name).to_owned(), raw_value_string(value));
    }
    raw
}

/// Kotlin \`Any.toString()\` for the scalar wire values.
fn raw_value_string(value: &WireValue) -> String {
    match value {
        WireValue::Bool(value) => value.to_string(),
        WireValue::Byte(value) => value.to_string(),
        WireValue::Short(value) => value.to_string(),
        WireValue::Int(value) => value.to_string(),
        WireValue::Long(value) => value.to_string(),
        WireValue::Float(value) => java_float_string(*value),
        WireValue::Double(value) => java_double_string(*value),
        WireValue::String(value) => value.clone(),
        WireValue::Map(map) => {
            let entries = map
                .0
                .iter()
                .map(|(id, value)| format!("{id}={}", raw_value_string(value)))
                .collect::<Vec<_>>()
                .join(", ");
            format!("EncodedMap(values={{{entries}}})")
        }
    }
}

fn visibility_label(visibility: i32) -> String {
    match visibility {
        0 => "VISIBLE".into(),
        4 => "INVISIBLE".into(),
        8 => "GONE".into(),
        other => format!("UNKNOWN({other})"),
    }
}

fn layer_type_label(layer_type: i32) -> String {
    match layer_type {
        0 => "NONE".into(),
        1 => "SOFTWARE".into(),
        2 => "HARDWARE".into(),
        other => format!("UNKNOWN({other})"),
    }
}

/// Kotlin's \`Bounds\` JSON object.
fn bounds_value(bounds: (i32, i32, i32, i32)) -> Value {
    let mut object = Map::new();
    object.insert("left".into(), Value::from(bounds.0));
    object.insert("top".into(), Value::from(bounds.1));
    object.insert("right".into(), Value::from(bounds.2));
    object.insert("bottom".into(), Value::from(bounds.3));
    Value::Object(object)
}

fn insets_value(insets: [i32; 4]) -> Value {
    let mut object = Map::new();
    object.insert("left".into(), Value::from(insets[0]));
    object.insert("top".into(), Value::from(insets[1]));
    object.insert("right".into(), Value::from(insets[2]));
    object.insert("bottom".into(), Value::from(insets[3]));
    Value::Object(object)
}

/// JSON number for a finite f32, using the shortest decimal representation of
/// the f32 value so protocol JSON matches Kotlin's serializer (0.3f -> 0.3).
fn number_json(value: f32) -> Value {
    if !value.is_finite() {
        return Value::Null;
    }
    serde_json::from_str::<Value>(&format!("{value:?}")).unwrap_or(Value::Null)
}

/// Kotlin's \`Any.toString()\` for \`Float\` (Java \`Float.toString\`).
pub(crate) fn java_float_string(value: f32) -> String {
    if value.is_nan() {
        return "NaN".into();
    }
    if value.is_infinite() {
        return if value > 0.0 {
            "Infinity".into()
        } else {
            "-Infinity".into()
        };
    }
    let (digits, exponent) = shortest_digits(&format!("{:e}", value.abs()));
    format_java_decimal(&digits, exponent, value.is_sign_negative())
}

/// Kotlin's \`Any.toString()\` for \`Double\` (Java \`Double.toString\`).
pub(crate) fn java_double_string(value: f64) -> String {
    if value.is_nan() {
        return "NaN".into();
    }
    if value.is_infinite() {
        return if value > 0.0 {
            "Infinity".into()
        } else {
            "-Infinity".into()
        };
    }
    let (digits, exponent) = shortest_digits(&format!("{:e}", value.abs()));
    format_java_decimal(&digits, exponent, value.is_sign_negative())
}

/// Extracts the shortest round-trip digits and decimal exponent from Rust's
/// \`{:e}\` formatting (\`"1.25e-3"\` -> (\`"125"\`, -3)).
fn shortest_digits(formatted: &str) -> (String, i32) {
    let (mantissa, exponent) = formatted.split_once('e').unwrap_or((formatted, "0"));
    let digits: String = mantissa
        .chars()
        .filter(|character| *character != '.')
        .collect();
    (digits, exponent.parse().unwrap_or(0))
}

/// Java's \`Float/Double.toString\` layout: decimal form for exponents in
/// [-3, 7), otherwise \`d.dddE<exp>\` with an uppercase exponent and no \`+\`.
fn format_java_decimal(digits: &str, exponent: i32, negative: bool) -> String {
    if digits.is_empty() {
        return if negative {
            "-0.0".into()
        } else {
            "0.0".into()
        };
    }
    let mut output = String::new();
    if negative {
        output.push('-');
    }
    if !(-3..7).contains(&exponent) {
        output.push(digits.as_bytes()[0] as char);
        output.push('.');
        if digits.len() > 1 {
            output.push_str(&digits[1..]);
        } else {
            output.push('0');
        }
        output.push('E');
        output.push_str(&exponent.to_string());
        return output;
    }
    if exponent >= 0 {
        let integer_digits = exponent as usize + 1;
        if integer_digits >= digits.len() {
            output.push_str(digits);
            for _ in digits.len()..integer_digits {
                output.push('0');
            }
            output.push_str(".0");
        } else {
            output.push_str(&digits[..integer_digits]);
            output.push('.');
            output.push_str(&digits[integer_digits..]);
        }
    } else {
        output.push_str("0.");
        for _ in 0..(-exponent - 1) {
            output.push('0');
        }
        output.push_str(digits);
    }
    output
}

/// Kotlin \`RECT_PATTERN\` over \`"Rect(l, t - r, b)"\`.
fn parse_rect(value: &str) -> Option<(i32, i32, i32, i32)> {
    let inner = value.strip_prefix("Rect(")?.strip_suffix(')')?;
    let mut scanner = RectScanner {
        bytes: inner.as_bytes(),
        position: 0,
    };
    let left = scanner.integer()?;
    scanner.skip_whitespace();
    scanner.expect(b',')?;
    scanner.skip_whitespace();
    let top = scanner.integer()?;
    scanner.skip_whitespace();
    scanner.expect(b'-')?;
    scanner.skip_whitespace();
    let right = scanner.integer()?;
    scanner.skip_whitespace();
    scanner.expect(b',')?;
    scanner.skip_whitespace();
    let bottom = scanner.integer()?;
    scanner.skip_whitespace();
    if scanner.position != scanner.bytes.len() {
        return None;
    }
    Some((left, top, right, bottom))
}

struct RectScanner<'a> {
    bytes: &'a [u8],
    position: usize,
}

impl RectScanner<'_> {
    fn skip_whitespace(&mut self) {
        while self
            .bytes
            .get(self.position)
            .is_some_and(u8::is_ascii_whitespace)
        {
            self.position += 1;
        }
    }

    fn expect(&mut self, byte: u8) -> Option<()> {
        if self.bytes.get(self.position) == Some(&byte) {
            self.position += 1;
            Some(())
        } else {
            None
        }
    }

    fn integer(&mut self) -> Option<i32> {
        let start = self.position;
        if self.bytes.get(self.position) == Some(&b'-') {
            self.position += 1;
        }
        let digits_start = self.position;
        while self
            .bytes
            .get(self.position)
            .is_some_and(u8::is_ascii_digit)
        {
            self.position += 1;
        }
        if self.position == digits_start {
            return None;
        }
        let number = std::str::from_utf8(&self.bytes[start..self.position]).ok()?;
        number.parse::<i32>().ok()
    }
}

// ---------------------------------------------------------------------------
// Compose semantics promotion (UIAutomator projection)
// ---------------------------------------------------------------------------

fn promote_node(node: &mut Value, inside_host: bool, promotion: &mut Promotion) {
    let Some(map) = node.as_object_mut() else {
        return;
    };
    let is_view = map.get("type").and_then(Value::as_str) != Some("compose");
    let class_name = map
        .get("className")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_owned();
    let is_host = is_compose_host_class(&class_name);
    if is_host {
        promotion.compose_host = true;
    }
    let children_inside = inside_host || is_host;
    if let Some(children) = map.get_mut("children").and_then(Value::as_array_mut) {
        for child in children {
            promote_node(child, children_inside, promotion);
        }
    }
    if !inside_host || !is_view {
        return;
    }
    let semantic = semantic_class(&class_name);
    let mut semantic_properties = Map::new();
    semantic_properties.insert(
        "AccessibilityClassName".into(),
        Value::String(class_name.clone()),
    );
    if let Some(resource_name) = map.get("resourceName").and_then(Value::as_str) {
        semantic_properties.insert("ResourceId".into(), Value::String(resource_name.to_owned()));
    }
    if let Some(attributes) = map.get("attributes").and_then(Value::as_object) {
        for (attribute, key) in [
            ("contentDescription", "ContentDescription"),
            ("enabled", "Enabled"),
            ("clickable", "Clickable"),
            ("longClickable", "LongClickable"),
            ("focusable", "Focusable"),
            ("focused", "Focused"),
            ("selected", "Selected"),
        ] {
            match attributes.get(attribute) {
                Some(Value::String(text)) if !text.trim().is_empty() && text != "null" => {
                    semantic_properties.insert(key.into(), Value::String(text.clone()));
                }
                Some(Value::Bool(flag)) => {
                    semantic_properties.insert(key.into(), Value::String(flag.to_string()));
                }
                _ => {}
            }
        }
        if let Some(raw) = attributes.get("rawProperties").and_then(Value::as_object) {
            for (name, value) in raw {
                if let Some(value) = value.as_str() {
                    semantic_properties
                        .insert(uppercase_first(name), Value::String(value.to_owned()));
                }
            }
        }
    }
    map.insert("type".into(), Value::String("compose".into()));
    map.insert("className".into(), Value::String(semantic.label.into()));
    if let Some(role) = semantic.role {
        map.insert("semanticsRole".into(), Value::String(role.into()));
    } else {
        map.remove("semanticsRole");
    }
    map.insert(
        "semanticProperties".into(),
        Value::Object(semantic_properties),
    );
    // ComposeNode has no resourceName or attributes fields.
    let _ = map.remove("resourceName");
    let _ = map.remove("attributes");
}

struct SemanticClass {
    label: &'static str,
    role: Option<&'static str>,
}

fn semantic_class(accessibility_class_name: &str) -> SemanticClass {
    if accessibility_class_name.ends_with(".Button")
        || accessibility_class_name.ends_with(".ImageButton")
    {
        SemanticClass {
            label: "Button",
            role: Some("Button"),
        }
    } else if accessibility_class_name.ends_with(".CheckBox") {
        SemanticClass {
            label: "Checkbox",
            role: Some("Checkbox"),
        }
    } else if accessibility_class_name.ends_with(".RadioButton") {
        SemanticClass {
            label: "RadioButton",
            role: Some("RadioButton"),
        }
    } else if accessibility_class_name.ends_with(".Switch")
        || accessibility_class_name.ends_with(".ToggleButton")
    {
        SemanticClass {
            label: "Switch",
            role: Some("Switch"),
        }
    } else if accessibility_class_name.ends_with(".EditText") {
        SemanticClass {
            label: "TextField",
            role: Some("TextField"),
        }
    } else if accessibility_class_name.ends_with(".TextView") {
        SemanticClass {
            label: "Text",
            role: None,
        }
    } else if accessibility_class_name.ends_with(".ImageView") {
        SemanticClass {
            label: "Image",
            role: Some("Image"),
        }
    } else if accessibility_class_name.ends_with(".ScrollView") {
        SemanticClass {
            label: "ScrollContainer",
            role: None,
        }
    } else if accessibility_class_name.ends_with(".SeekBar") {
        SemanticClass {
            label: "Slider",
            role: Some("Slider"),
        }
    } else if accessibility_class_name.ends_with(".ProgressBar") {
        SemanticClass {
            label: "ProgressIndicator",
            role: None,
        }
    } else {
        SemanticClass {
            label: "SemanticsNode",
            role: None,
        }
    }
}

/// Kotlin \`replaceFirstChar(Char::uppercase)\`.
fn uppercase_first(value: &str) -> String {
    let mut characters = value.chars();
    let Some(first) = characters.next() else {
        return String::new();
    };
    let mut result: String = first.to_uppercase().collect();
    result.push_str(characters.as_str());
    result
}
