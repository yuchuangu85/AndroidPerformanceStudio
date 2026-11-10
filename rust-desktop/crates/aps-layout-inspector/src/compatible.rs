//! Live layout capture, ported from the Compose host's `LiveDeviceClient` and
//! `AdbFallbackCapture`: an agent-enabled foreground app is captured through the
//! in-app agent first (full runtime View tree + Compose semantics + screenshot);
//! every other case falls back to ADB, where the real per-window runtime View
//! tree (`cmd window dump-visible-window-views`) is preferred, the UIAutomator
//! accessibility projection is the fallback, and a Compose-host window is
//! compared against the accessibility projection so Compose semantics can be
//! promoted when that projection is richer. Commands are sequential:
//! dimensions are checked before and after, content is not atomic.

#[cfg(test)]
mod tests;

use crate::visible_window::{self, WindowDump, WindowTarget};
use crate::{FrameIdentity, InspectorProtocolFrame, ScreenshotEvidence, parse_snapshot};
use aps_adb::{AdbLocation, validate_serial};
use aps_contracts::SourceDigest;
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
use serde_json::{Value, json};
use std::io::{Cursor, Read};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const MAX_XML: usize = 8 * 1024 * 1024;
const MAX_PNG: usize = 32 * 1024 * 1024;
const MAX_PIXELS: u64 = 16 * 1024 * 1024;
const MAX_WINDOW_DUMP: usize = visible_window::MAX_VIEW_DUMP_BYTES;
const SYSTEM_UI_PACKAGE: &str = "com.android.systemui";
const WINDOW_DUMP_ATTEMPTS: usize = 3;
const WINDOW_DUMP_RETRY_DELAY: Duration = Duration::from_millis(75);

#[derive(Clone, Copy)]
pub enum CompatibleTarget {
    ForegroundApp,
    SystemUi,
}

/// One hierarchy acquisition plus the exact bytes whose digest identifies the
/// frame alongside the screenshot and display geometry.
struct Capture {
    hierarchy: CapturedHierarchy,
    evidence: Vec<u8>,
}

enum CapturedHierarchy {
    VisibleWindows(Vec<WindowDump>),
    Accessibility(AccessibilityCapture),
}

/// The UIAutomator projection, already parsed into bounded protocol node JSON.
struct AccessibilityCapture {
    package: String,
    dimensions: (u32, u32),
    windows: Vec<Value>,
    node_count: usize,
    compose: bool,
}

/// Blocking; callers must use a background executor. No installs, app launches or
/// settings changes are performed; the agent path only reads the app's session
/// descriptor through `run-as`, and nothing is written to the device.
pub fn capture_compatible(
    adb: &AdbLocation,
    serial: &str,
    target: CompatibleTarget,
    generation: u64,
    cancel: &CancellationToken,
) -> Result<InspectorProtocolFrame, String> {
    validate_serial(serial).map_err(|error| error.to_string())?;
    let run = |args: &[&str], limit| -> Result<Vec<u8>, String> {
        let mut request = ProcessRequest::new(&adb.executable)
            .args(["-s", serial])
            .args(args);
        request.timeout = Duration::from_secs(30);
        request.stdout_limit = limit;
        request.stderr_limit = 64 * 1024;
        let output = ProcessRunner
            .run(&request, cancel)
            .map_err(|error| error.to_string())?;
        if !output.status.success() {
            return Err(format!(
                "ADB {} failed (exit {:?})",
                args.join(" "),
                output.status.code()
            ));
        }
        Ok(output.stdout)
    };
    // Compose resolves the target package before capture: the foreground app for
    // app capture, the fixed System UI package otherwise.
    let expected = match target {
        CompatibleTarget::ForegroundApp => {
            let before = run(
                &["shell", "dumpsys", "activity", "activities"],
                4 * 1024 * 1024,
            )?;
            foreground_package(&before)?
        }
        CompatibleTarget::SystemUi => SYSTEM_UI_PACKAGE.to_owned(),
    };
    // Compose `LiveDeviceClient.connectWithFallback`: an agent-enabled foreground
    // app is captured through the in-app agent first. A non-agent app (and the
    // System UI target) falls through to the ADB projection below.
    if matches!(target, CompatibleTarget::ForegroundApp) {
        match crate::agent_bridge::capture_agent(
            &adb.executable,
            serial,
            &expected,
            generation,
            cancel,
        )? {
            crate::agent_bridge::AgentCapture::Captured(frame) => {
                let focus_after = run(
                    &["shell", "dumpsys", "activity", "activities"],
                    4 * 1024 * 1024,
                )?;
                if foreground_package(&focus_after)? != expected {
                    return Err("Foreground app changed during capture; refresh again".into());
                }
                return Ok(frame);
            }
            crate::agent_bridge::AgentCapture::Unavailable(_reason) => {}
        }
    }
    let size = run(&["shell", "wm", "size"], 4096)?;
    let density = run(&["shell", "wm", "density"], 4096)?;
    let capture = capture_hierarchy(&run, &expected, target, &size, cancel)?;
    let png = run(&["exec-out", "screencap", "-p"], MAX_PNG)?;
    let after = run(&["shell", "wm", "size"], 4096)?;
    if wm_value(&size, "size")? != wm_value(&after, "size")? {
        return Err("Display size changed during capture; refresh again".into());
    }
    if matches!(target, CompatibleTarget::ForegroundApp) {
        let focus_after = run(
            &["shell", "dumpsys", "activity", "activities"],
            4 * 1024 * 1024,
        )?;
        if foreground_package(&focus_after)? != expected {
            return Err("Foreground app changed during capture; refresh again".into());
        }
    }
    let captured_at = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "Host clock precedes epoch")?
        .as_millis();
    parse_frame(
        &capture.hierarchy,
        &capture.evidence,
        &expected,
        &png,
        &size,
        &density,
        generation,
        i64::try_from(captured_at).map_err(|_| "Host timestamp exceeds range")?,
        cancel,
    )
}

/// Compose `AdbFallbackCapture.captureHierarchy`: try the runtime View dump
/// three times, then fall back to the accessibility projection. A Compose
/// window also compares the accessibility projection, exactly like
/// `expandShallowComposeHierarchy`.
fn capture_hierarchy<F>(
    run: &F,
    expected_package: &str,
    target: CompatibleTarget,
    size: &[u8],
    cancel: &CancellationToken,
) -> Result<Capture, String>
where
    F: Fn(&[&str], usize) -> Result<Vec<u8>, String>,
{
    let window_target = match target {
        CompatibleTarget::ForegroundApp => WindowTarget::ForegroundApp,
        CompatibleTarget::SystemUi => WindowTarget::SystemUi,
    };
    let mut last_error = String::new();
    for attempt in 0..WINDOW_DUMP_ATTEMPTS {
        check_cancel(cancel)?;
        match run(
            &["exec-out", "cmd", "window", "dump-visible-window-views"],
            MAX_WINDOW_DUMP,
        ) {
            Ok(bytes) => {
                match visible_window::parse_windows(&bytes, expected_package, window_target, cancel)
                {
                    Ok(windows) if !windows.is_empty() => {
                        if visible_window::contains_compose_host(&windows)
                            && let Some((accessibility, xml)) =
                                expand_compose_windows(run, expected_package, target, size, cancel)?
                            && accessibility.compose
                            && accessibility.node_count > visible_window::node_count(&windows)
                        {
                            return Ok(Capture {
                                hierarchy: CapturedHierarchy::Accessibility(accessibility),
                                evidence: xml,
                            });
                        }
                        return Ok(Capture {
                            hierarchy: CapturedHierarchy::VisibleWindows(windows),
                            evidence: bytes,
                        });
                    }
                    Ok(_) => last_error = "Visible-window dump returned no window".into(),
                    Err(error) => last_error = error,
                }
            }
            Err(error) => last_error = error,
        }
        if attempt + 1 < WINDOW_DUMP_ATTEMPTS {
            std::thread::sleep(WINDOW_DUMP_RETRY_DELAY);
        }
    }
    let xml = run(&["exec-out", "uiautomator", "dump", "/dev/tty"], MAX_XML).map_err(|error| {
        if last_error.is_empty() {
            error
        } else {
            format!("{error}. Visible-window dump failed: {last_error}")
        }
    })?;
    let accessibility = parse_accessibility(&xml, target, Some(expected_package), size, cancel)?;
    Ok(Capture {
        hierarchy: CapturedHierarchy::Accessibility(accessibility),
        evidence: xml,
    })
}

/// Runs the accessibility projection used to expand a Compose-host window.
/// Failure keeps the runtime View tree, as in Compose's `runCatching`.
fn expand_compose_windows<F>(
    run: &F,
    expected_package: &str,
    target: CompatibleTarget,
    size: &[u8],
    cancel: &CancellationToken,
) -> Result<Option<(AccessibilityCapture, Vec<u8>)>, String>
where
    F: Fn(&[&str], usize) -> Result<Vec<u8>, String>,
{
    let Ok(xml) = run(&["exec-out", "uiautomator", "dump", "/dev/tty"], MAX_XML) else {
        return Ok(None);
    };
    check_cancel(cancel)?;
    // Kotlin's runCatching: an accessibility projection that fails or belongs to
    // another target keeps the runtime View tree.
    let Ok(accessibility) = parse_accessibility(&xml, target, Some(expected_package), size, cancel)
    else {
        return Ok(None);
    };
    Ok(Some((accessibility, xml)))
}

pub(crate) fn foreground_package(output: &[u8]) -> Result<String, String> {
    let text = std::str::from_utf8(output).map_err(|_| "Invalid foreground activity encoding")?;
    // Kotlin `AdbOutputParser.parseForegroundPackage` marker priority.
    for marker in ["mFocusedApp", "topResumedActivity", "mResumedActivity"] {
        for line in text.lines().filter(|line| line.contains(marker)) {
            for token in line.split_whitespace() {
                if let Some((package, _)) = token.split_once('/')
                    && package.contains('.')
                    && package
                        .bytes()
                        .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_'))
                {
                    return Ok(package.to_owned());
                }
            }
        }
    }
    Err("Cannot determine foreground app; unlock the device and refresh".into())
}

fn wm_value<'a>(bytes: &'a [u8], kind: &str) -> Result<&'a str, String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "Invalid wm output encoding")?;
    let physical = format!("Physical {kind}: ");
    let override_prefix = format!("Override {kind}: ");
    text.lines()
        .find_map(|line| line.trim().strip_prefix(&override_prefix))
        .or_else(|| {
            text.lines()
                .find_map(|line| line.trim().strip_prefix(&physical))
        })
        .filter(|value| !value.is_empty())
        .ok_or_else(|| format!("Missing wm {kind}"))
}

fn check_cancel(cancel: &CancellationToken) -> Result<(), String> {
    if cancel.is_cancelled() {
        Err("Inspector capture cancelled".into())
    } else {
        Ok(())
    }
}

fn wm_dimensions(size: &[u8]) -> Result<(u32, u32), String> {
    let (width, height) = wm_value(size, "size")?
        .split_once('x')
        .ok_or("Invalid wm size")?;
    let width = width.parse::<u32>().map_err(|_| "Invalid display width")?;
    let height = height
        .parse::<u32>()
        .map_err(|_| "Invalid display height")?;
    if width == 0 || height == 0 || u64::from(width) * u64::from(height) > MAX_PIXELS {
        return Err("Display exceeds bounded screenshot size".into());
    }
    Ok((width, height))
}

fn wm_density(density: &[u8]) -> Result<u32, String> {
    let dpi: u32 = wm_value(density, "density")?
        .parse()
        .map_err(|_| "Invalid wm density")?;
    if dpi == 0 || dpi > 10_000 {
        return Err("Invalid wm density".into());
    }
    Ok(dpi)
}

/// Decode the screenshot (bounded) and return its pixel dimensions. Decoding is
/// the integrity check the canvas later relies on; the adapter never draws.
fn decode_screenshot(png: &[u8]) -> Result<(u32, u32), String> {
    if png.len() > MAX_PNG {
        return Err("Capture exceeds input limit".into());
    }
    let mut decoder = png::Decoder::new(Cursor::new(png));
    decoder.set_limits(png::Limits {
        bytes: 64 * 1024 * 1024,
    });
    let mut reader = decoder.read_info().map_err(|_| "Invalid screenshot PNG")?;
    let dimensions = (reader.info().width, reader.info().height);
    if dimensions.0 == 0
        || dimensions.1 == 0
        || u64::from(dimensions.0) * u64::from(dimensions.1) > MAX_PIXELS
    {
        return Err("Screenshot exceeds decoded limit".into());
    }
    let length = reader
        .output_buffer_size()
        .filter(|size| *size <= 64 * 1024 * 1024)
        .ok_or("Screenshot exceeds decoded limit")?;
    let mut pixels = vec![0; length];
    reader
        .next_frame(&mut pixels)
        .map_err(|_| "Corrupt screenshot PNG")?;
    drop(pixels);
    Ok(dimensions)
}

#[allow(clippy::too_many_arguments)]
fn parse_frame(
    hierarchy: &CapturedHierarchy,
    evidence: &[u8],
    expected_package: &str,
    png: &[u8],
    size: &[u8],
    density: &[u8],
    generation: u64,
    captured_at: i64,
    cancel: &CancellationToken,
) -> Result<InspectorProtocolFrame, String> {
    check_cancel(cancel)?;
    let (wm_width, wm_height) = wm_dimensions(size)?;
    let dpi = wm_density(density)?;
    let (screenshot_width, screenshot_height) = decode_screenshot(png)?;
    let (package, display, compose, windows, root, default_window_id) = match hierarchy {
        CapturedHierarchy::VisibleWindows(windows) => {
            // Rotation is resolved from the screenshot orientation: the runtime
            // dump and the screenshot must agree on the current display.
            let (width, height) = if (screenshot_width, screenshot_height) == (wm_width, wm_height)
            {
                (wm_width, wm_height)
            } else if (screenshot_width, screenshot_height) == (wm_height, wm_width) {
                (wm_height, wm_width)
            } else {
                return Err(
                    "Screenshot/display rotation or dimensions changed; refresh again".into(),
                );
            };
            let default_id = visible_window::default_window_id(windows)
                .or_else(|| windows.first().map(|window| window.id.clone()))
                .ok_or("Visible-window capture produced no windows")?;
            let default_index = windows
                .iter()
                .position(|window| window.id == default_id)
                .unwrap_or(0);
            let windows_json = visible_window::snapshot_windows(windows);
            let root = windows_json
                .get(default_index)
                .map(|window| window["root"].clone())
                .ok_or("Visible-window capture produced no windows")?;
            (
                expected_package.to_owned(),
                (width, height),
                false,
                windows_json,
                root,
                Some(default_id),
            )
        }
        CapturedHierarchy::Accessibility(accessibility) => {
            if (screenshot_width, screenshot_height) != accessibility.dimensions {
                return Err(
                    "Screenshot/display rotation or dimensions changed; refresh again".into(),
                );
            }
            let root = accessibility
                .windows
                .first()
                .map(|window| window["root"].clone())
                .ok_or("UIAutomator hierarchy has no window")?;
            let default_id = accessibility
                .windows
                .first()
                .and_then(|window| window["id"].as_str())
                .map(str::to_owned);
            (
                accessibility.package.clone(),
                accessibility.dimensions,
                accessibility.compose,
                accessibility.windows.clone(),
                root,
                default_id,
            )
        }
    };
    if !expected_package.is_empty() && package != expected_package {
        return Err("Captured hierarchy does not match the target app; refresh again".into());
    }
    check_cancel(cancel)?;
    let mut snapshot = json!({
        "protocolVersion": {"major": 1, "minor": 0},
        "packageName": package,
        "capturedAtEpochMillis": captured_at,
        "display": {"widthPx": display.0, "heightPx": display.1, "density": dpi as f64 / 160.0},
        "capabilities": {
            "viewHierarchy": true,
            "composeSemantics": compose,
            "screenshots": true,
            "timeline": false,
        },
        "root": root,
        "windows": windows,
    });
    if let Some(window_id) = default_window_id {
        snapshot["defaultWindowId"] = json!(window_id);
    }
    let input = Cursor::new(evidence)
        .chain(Cursor::new(png))
        .chain(Cursor::new(size))
        .chain(Cursor::new(density));
    let source_digest = SourceDigest::hash_reader(input, (MAX_WINDOW_DUMP + MAX_PNG + 8192) as u64)
        .map_err(|_| "Cannot hash captured evidence")?;
    let frame = FrameIdentity {
        source_digest,
        package_name: package,
        captured_at_epoch_millis: captured_at,
        protocol_major: 1,
        protocol_minor: 0,
    };
    let mut archive = parse_snapshot(&snapshot, frame, 1).map_err(|error| error.to_string())?;
    archive.screenshot = Some(ScreenshotEvidence {
        sha256: SourceDigest::hash_reader(Cursor::new(png), MAX_PNG as u64)
            .map_err(|_| "Cannot hash screenshot")?,
        size_bytes: png.len() as u64,
    });
    archive.screenshot_png = Some(png.to_vec());
    check_cancel(cancel)?;
    Ok(InspectorProtocolFrame {
        archive,
        generation,
        from_agent: false,
    })
}

/// Backwards-compatible entry point for the accessibility projection only;
/// production capture always goes through `capture_compatible`.
#[cfg(test)]
#[allow(clippy::too_many_arguments)]
fn parse_capture(
    xml: &[u8],
    png: &[u8],
    size: &[u8],
    density: &[u8],
    target: CompatibleTarget,
    generation: u64,
    captured_at: i64,
    cancel: &CancellationToken,
) -> Result<InspectorProtocolFrame, String> {
    let accessibility = parse_accessibility(xml, target, None, size, cancel)?;
    let expected = accessibility.package.clone();
    parse_frame(
        &CapturedHierarchy::Accessibility(accessibility),
        xml,
        &expected,
        png,
        size,
        density,
        generation,
        captured_at,
        cancel,
    )
}

/// Parse the UIAutomator hierarchy into the target package's windows. Bounds are
/// validated against the rotated display because XML and screenshot are
/// sequential and non-atomic.
fn parse_accessibility(
    xml: &[u8],
    target: CompatibleTarget,
    expected_package: Option<&str>,
    size: &[u8],
    cancel: &CancellationToken,
) -> Result<AccessibilityCapture, String> {
    check_cancel(cancel)?;
    if xml.len() > MAX_XML {
        return Err("Capture exceeds input limit".into());
    }
    let text = std::str::from_utf8(xml).map_err(|_| "Invalid UIAutomator XML encoding")?;
    // Android may print a status line before/after the XML document.
    let start = text
        .find("<?xml")
        .or_else(|| text.find("<hierarchy"))
        .ok_or("UIAutomator returned no hierarchy (device may be locked or busy)")?;
    let end = text
        .rfind("</hierarchy>")
        .ok_or("Incomplete UIAutomator XML")?
        + "</hierarchy>".len();
    if end <= start {
        return Err("Invalid UIAutomator document".into());
    }
    let doc = roxmltree::Document::parse_with_options(
        &text[start..end],
        roxmltree::ParsingOptions {
            allow_dtd: false,
            nodes_limit: 200_000,
            ..Default::default()
        },
    )
    .map_err(|error| format!("Invalid UIAutomator XML: {error}"))?;
    let hierarchy = doc.root_element();
    if !hierarchy.has_tag_name("hierarchy") {
        return Err("Missing hierarchy root".into());
    }
    let rotation: u32 = hierarchy
        .attribute("rotation")
        .ok_or("Missing display rotation")?
        .parse()
        .map_err(|_| "Invalid display rotation")?;
    if rotation > 3 {
        return Err("Invalid display rotation".into());
    }
    let mut dimensions = wm_dimensions(size)?;
    if rotation % 2 == 1 {
        dimensions = (dimensions.1, dimensions.0);
    }
    let roots: Vec<_> = hierarchy
        .children()
        .filter(|node| node.is_element())
        .collect();
    if roots.is_empty() || roots.iter().any(|node| !node.has_tag_name("node")) {
        return Err("UIAutomator hierarchy has no valid roots".into());
    }
    let packages: std::collections::BTreeSet<_> = roots
        .iter()
        .filter_map(|node| node.attribute("package"))
        .filter(|package| !package.is_empty())
        .filter(|package| match target {
            CompatibleTarget::ForegroundApp => *package != SYSTEM_UI_PACKAGE,
            CompatibleTarget::SystemUi => *package == SYSTEM_UI_PACKAGE,
        })
        .collect();
    if let Some(expected) = expected_package {
        // Kotlin parity: the expected package node is the first descendant that
        // declares it, not necessarily a top-level root.
        let candidate = hierarchy
            .descendants()
            .find(|node| node.has_tag_name("node") && node.attribute("package") == Some(expected));
        let Some(candidate) = candidate else {
            let visible: Vec<&str> = hierarchy
                .descendants()
                .filter_map(|node| node.attribute("package"))
                .filter(|package| !package.is_empty())
                .collect();
            return Err(format!(
                "UIAutomator hierarchy does not contain {expected} (visible packages: {}); the target app may not be in the foreground",
                visible.join(", ")
            ));
        };
        let (windows, node_count, compose) =
            parse_package_windows(std::slice::from_ref(&candidate), expected, cancel)?;
        return Ok(AccessibilityCapture {
            package: expected.to_owned(),
            dimensions,
            windows,
            node_count,
            compose,
        });
    }
    if packages.len() != 1 {
        return Err("Cannot identify one visible target package from UIAutomator; select another target or refresh".into());
    }
    let package = packages
        .first()
        .map(|package| (*package).to_owned())
        .ok_or("Missing target package")?;
    let (windows, node_count, compose) = parse_package_windows(&roots, &package, cancel)?;
    Ok(AccessibilityCapture {
        package,
        dimensions,
        windows,
        node_count,
        compose,
    })
}

fn parse_package_windows(
    roots: &[roxmltree::Node<'_, '_>],
    package: &str,
    cancel: &CancellationToken,
) -> Result<(Vec<Value>, usize, bool), String> {
    let selected: Vec<&roxmltree::Node<'_, '_>> = roots
        .iter()
        .filter(|node| node.attribute("package") == Some(package))
        .collect();
    let mut count = 0;
    let mut windows = Vec::new();
    let mut node_count = 0;
    let mut compose = false;
    for (index, root) in selected.iter().enumerate() {
        let root_path = if selected.len() == 1 {
            "root".to_owned()
        } else {
            format!("w{index}")
        };
        let mut node = parse_node(**root, &root_path, 0, &mut count, cancel)?;
        compose |= visible_window::promote_compose_semantics(&mut node).compose_host;
        node_count += visible_window::count_json_nodes(&node);
        // Kotlin's accessibility fallback keeps one `window:uiautomator` window;
        // extra same-package roots (legacy caller only) stay unique.
        let window_id = if selected.len() == 1 {
            "window:uiautomator".to_owned()
        } else {
            format!("window:uiautomator:{index}")
        };
        windows.push(json!({
            "id": window_id,
            "title": package.rsplit('.').next().unwrap_or(package),
            "type": "ACTIVITY",
            "bounds": node["bounds"],
            "root": node,
        }));
    }
    if windows.is_empty() {
        return Err("UIAutomator hierarchy has no window for the target package".into());
    }
    Ok((windows, node_count, compose))
}

/// Parse one UIAutomator node into protocol ViewNode JSON, matching Kotlin's
/// `UiAutomatorHierarchyParser.toViewNode`: blank text/resource/content
/// descriptions become null, `visible-to-user` maps to the visibility label and
/// the visible flag, and the only raw properties are the five accessibility
/// attributes.
fn parse_node(
    node: roxmltree::Node<'_, '_>,
    path: &str,
    depth: usize,
    count: &mut usize,
    cancel: &CancellationToken,
) -> Result<Value, String> {
    check_cancel(cancel)?;
    if depth > 60 || *count >= 100_000 || !node.has_tag_name("node") {
        return Err("UIAutomator hierarchy exceeds limits or has an invalid node".into());
    }
    // Kotlin's UIAutomator projection ids are structural paths ("root/0/1").
    let id = path.to_owned();
    if node
        .attributes()
        .any(|attribute| attribute.value().len() > 64 * 1024)
    {
        return Err("UIAutomator attribute exceeds text limit".into());
    }
    *count += 1;
    let (left, top, right, bottom) = uiautomator_bounds(node)?;
    let visible_to_user = node
        .attribute("visible-to-user")
        .filter(|value| !value.is_empty())
        .map(|value| {
            value
                .parse::<bool>()
                .map_err(|_| "Invalid UIAutomator boolean")
        })
        .transpose()?;
    let mut attributes = serde_json::Map::new();
    if let Some(visible) = visible_to_user {
        attributes.insert(
            "visibility".into(),
            json!(if visible {
                "VISIBLE_TO_USER"
            } else {
                "NOT_VISIBLE_TO_USER"
            }),
        );
    }
    for (xml, field) in [
        ("enabled", "enabled"),
        ("clickable", "clickable"),
        ("long-clickable", "longClickable"),
        ("focusable", "focusable"),
        ("focused", "focused"),
        ("selected", "selected"),
    ] {
        if let Some(value) = node.attribute(xml) {
            attributes.insert(
                field.into(),
                value
                    .parse::<bool>()
                    .map(Value::Bool)
                    .map_err(|_| "Invalid UIAutomator boolean")?,
            );
        }
    }
    attributes.insert(
        "contentDescription".into(),
        json!(blank_to_none(node.attribute("content-desc"))),
    );
    let mut raw_properties = serde_json::Map::new();
    for name in ["checkable", "checked", "scrollable", "password", "hint"] {
        if let Some(value) = node.attribute(name).filter(|value| !value.is_empty()) {
            raw_properties.insert(name.into(), json!(value));
        }
    }
    attributes.insert("rawProperties".into(), Value::Object(raw_properties));
    let children = node
        .children()
        .filter(|child| child.is_element())
        .enumerate()
        .map(|(index, child)| {
            parse_node(child, &format!("{path}/{index}"), depth + 1, count, cancel)
        })
        .collect::<Result<Vec<_>, _>>()?;
    Ok(json!({"type": "view", "id": id,
        "className": node.attribute("class").filter(|value| !value.is_empty()).ok_or("Missing node class")?,
        "bounds": {"left": left, "top": top, "right": right, "bottom": bottom},
        "visible": visible_to_user.unwrap_or(true), "alpha": 1.0,
        "resourceName": blank_to_none(node.attribute("resource-id")),
        "text": blank_to_none(node.attribute("text")),
        "attributes": attributes, "children": children}))
}

/// Kotlin `UiAutomatorHierarchyParser.parseBounds`, bounded like the runtime View
/// dump: coordinates are sanity-checked instead of clamped to the display, and
/// inverted sides coerce to a zero extent so structural bounds stay valid.
fn uiautomator_bounds(node: roxmltree::Node<'_, '_>) -> Result<(i32, i32, i32, i32), String> {
    const MAX_ABS_COORD: i64 = 1_000_000;
    let bounds = node
        .attribute("bounds")
        .ok_or("Missing UIAutomator bounds")?;
    let (first, second) = bounds
        .strip_prefix('[')
        .and_then(|value| value.strip_suffix(']'))
        .and_then(|value| value.split_once("]["))
        .ok_or("Invalid UIAutomator bounds")?;
    let point = |value: &str| -> Result<(i64, i64), String> {
        let (x, y) = value.split_once(',').ok_or("Invalid bounds point")?;
        Ok((
            x.parse().map_err(|_| "Invalid bounds coordinate")?,
            y.parse().map_err(|_| "Invalid bounds coordinate")?,
        ))
    };
    let ((left, top), (right, bottom)) = (point(first)?, point(second)?);
    for coordinate in [left, top, right, bottom] {
        if !(-MAX_ABS_COORD..=MAX_ABS_COORD).contains(&coordinate) {
            return Err("UIAutomator bounds are out of range".into());
        }
    }
    let right = right.max(left);
    let bottom = bottom.max(top);
    Ok((left as i32, top as i32, right as i32, bottom as i32))
}

/// Kotlin `String.ifBlank { null }`.
fn blank_to_none(value: Option<&str>) -> Option<&str> {
    value.filter(|text| !text.trim().is_empty())
}
