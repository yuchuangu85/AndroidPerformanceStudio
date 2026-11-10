//! UIAutomator accessibility projection, not an injected View/Compose inspector.
//! Commands are sequential: dimensions are checked, but content is not atomic.

#[cfg(test)]
mod tests;

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

#[derive(Clone, Copy)]
pub enum CompatibleTarget {
    ForegroundApp,
    SystemUi,
}

/// Blocking; callers must use a background executor. No installs, app launches,
/// settings changes or device files: UIAutomator writes only to /dev/tty.
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
    let before = run(
        &["shell", "dumpsys", "activity", "activities"],
        4 * 1024 * 1024,
    )?;
    let foreground = foreground_package(&before)?;
    let size = run(&["shell", "wm", "size"], 4096)?;
    let density = run(&["shell", "wm", "density"], 4096)?;
    let xml = run(&["exec-out", "uiautomator", "dump", "/dev/tty"], MAX_XML)?;
    let png = run(&["exec-out", "screencap", "-p"], MAX_PNG)?;
    let after = run(&["shell", "wm", "size"], 4096)?;
    if wm_value(&size, "size")? != wm_value(&after, "size")? {
        return Err("Display size changed during capture; refresh again".into());
    }
    let focus_after = run(
        &["shell", "dumpsys", "activity", "activities"],
        4 * 1024 * 1024,
    )?;
    if foreground_package(&focus_after)? != foreground {
        return Err("Foreground app changed during capture; refresh again".into());
    }
    let captured_at = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "Host clock precedes epoch")?
        .as_millis();
    let frame = parse_capture(
        &xml,
        &png,
        &size,
        &density,
        target,
        generation,
        i64::try_from(captured_at).map_err(|_| "Host timestamp exceeds range")?,
        cancel,
    )?;
    if matches!(target, CompatibleTarget::ForegroundApp)
        && frame.archive.frame.package_name != foreground
    {
        return Err("Captured hierarchy does not match the foreground app; refresh again".into());
    }
    Ok(frame)
}

fn foreground_package(output: &[u8]) -> Result<String, String> {
    let text = std::str::from_utf8(output).map_err(|_| "Invalid foreground activity encoding")?;
    for marker in ["topResumedActivity", "mResumedActivity", "mFocusedApp"] {
        for line in text.lines().filter(|line| line.contains(marker)) {
            for token in line.split_whitespace() {
                if let Some((package, _)) = token.split_once('/') {
                    if package.contains('.')
                        && package
                            .bytes()
                            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_'))
                    {
                        return Ok(package.to_owned());
                    }
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
    check_cancel(cancel)?;
    if xml.len() > MAX_XML || png.len() > MAX_PNG {
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
    let (width, height) = wm_value(size, "size")?
        .split_once('x')
        .ok_or("Invalid wm size")?;
    let mut dimensions = (
        width.parse::<u32>().map_err(|_| "Invalid display width")?,
        height
            .parse::<u32>()
            .map_err(|_| "Invalid display height")?,
    );
    if rotation % 2 == 1 {
        dimensions = (dimensions.1, dimensions.0);
    }
    let (width, height) = dimensions;
    if width == 0 || height == 0 || u64::from(width) * u64::from(height) > MAX_PIXELS {
        return Err("Display exceeds bounded screenshot size".into());
    }
    let dpi: u32 = wm_value(density, "density")?
        .parse()
        .map_err(|_| "Invalid wm density")?;
    if dpi == 0 || dpi > 10_000 {
        return Err("Invalid wm density".into());
    }
    let mut decoder = png::Decoder::new(Cursor::new(png));
    decoder.set_limits(png::Limits {
        bytes: 64 * 1024 * 1024,
    });
    let mut reader = decoder.read_info().map_err(|_| "Invalid screenshot PNG")?;
    if (reader.info().width, reader.info().height) != dimensions {
        return Err("Screenshot/display rotation or dimensions changed; refresh again".into());
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
    check_cancel(cancel)?;
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
            CompatibleTarget::ForegroundApp => *package != "com.android.systemui",
            CompatibleTarget::SystemUi => *package == "com.android.systemui",
        })
        .collect();
    if packages.len() != 1 {
        return Err("Cannot identify one visible target package from UIAutomator; select another target or refresh".into());
    }
    let package = *packages.first().ok_or("Missing target package")?;
    let mut count = 0;
    let mut windows = Vec::new();
    for (index, root) in roots
        .iter()
        .filter(|node| node.attribute("package") == Some(package))
        .enumerate()
    {
        let node = parse_node(*root, 0, &mut count, dimensions, generation, cancel)?;
        windows.push(
            json!({"id": format!("uiautomator:{generation}:{index}"), "type": "OTHER",
            "bounds": node["bounds"], "root": node}),
        );
    }
    let snapshot = json!({
        "protocolVersion": {"major": 1, "minor": 0}, "packageName": package,
        "capturedAtEpochMillis": captured_at,
        "display": {"widthPx": width, "heightPx": height, "density": dpi as f64 / 160.0},
        "capabilities": {"viewHierarchy": true, "composeSemantics": false, "screenshots": true, "timeline": false},
        "root": windows[0]["root"], "windows": windows
    });
    let input = Cursor::new(xml)
        .chain(Cursor::new(png))
        .chain(Cursor::new(size))
        .chain(Cursor::new(density));
    let source_digest = SourceDigest::hash_reader(input, (MAX_XML + MAX_PNG + 8192) as u64)
        .map_err(|_| "Cannot hash captured evidence")?;
    let frame = FrameIdentity {
        source_digest,
        package_name: package.into(),
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
    })
}

fn parse_node(
    node: roxmltree::Node<'_, '_>,
    depth: usize,
    count: &mut usize,
    dimensions: (u32, u32),
    generation: u64,
    cancel: &CancellationToken,
) -> Result<Value, String> {
    check_cancel(cancel)?;
    if depth > 60 || *count >= 100_000 || !node.has_tag_name("node") {
        return Err("UIAutomator hierarchy exceeds limits or has an invalid node".into());
    }
    let id = format!("uiautomator:{generation}:{}", *count);
    if node
        .attributes()
        .any(|attribute| attribute.value().len() > 64 * 1024)
    {
        return Err("UIAutomator attribute exceeds text limit".into());
    }
    *count += 1;
    let bounds = node
        .attribute("bounds")
        .ok_or("Missing UIAutomator bounds")?;
    let (first, second) = bounds
        .strip_prefix('[')
        .and_then(|s| s.strip_suffix(']'))
        .and_then(|s| s.split_once("]["))
        .ok_or("Invalid UIAutomator bounds")?;
    let point = |s: &str| -> Result<(i32, i32), String> {
        let (x, y) = s.split_once(',').ok_or("Invalid bounds point")?;
        Ok((
            x.parse().map_err(|_| "Invalid bounds coordinate")?,
            y.parse().map_err(|_| "Invalid bounds coordinate")?,
        ))
    };
    let ((left, top), (right, bottom)) = (point(first)?, point(second)?);
    if left < 0
        || top < 0
        || right < left
        || bottom < top
        || right as u32 > dimensions.0
        || bottom as u32 > dimensions.1
    {
        return Err("UIAutomator bounds do not match screenshot display".into());
    }
    let mut attributes = serde_json::Map::new();
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
        json!(node.attribute("content-desc")),
    );
    attributes.insert("rawProperties".into(), json!({
        "captureSource": "ADB UIAutomator compatible view; accessibility projection, not runtime View tree",
        "captureConsistency": "Sequential non-atomic XML and screenshot; refresh-local node IDs",
        "capturePackage": node.attribute("package").unwrap_or(""),
        "captureLimitations": "No Compose details, alpha, transforms, actual window identity or cross-frame identity"
    }));
    let children = node
        .children()
        .filter(|child| child.is_element())
        .map(|child| parse_node(child, depth + 1, count, dimensions, generation, cancel))
        .collect::<Result<Vec<_>, _>>()?;
    Ok(json!({"type": "view", "id": id,
        "className": node.attribute("class").filter(|s| !s.is_empty()).ok_or("Missing node class")?,
        "bounds": {"left": left, "top": top, "right": right, "bottom": bottom},
        "resourceName": node.attribute("resource-id").filter(|s| !s.is_empty()),
        "text": node.attribute("text"), "attributes": attributes, "children": children}))
}
