use super::*;

const XML: &str = r#"<?xml version="1.0"?><hierarchy rotation="0"><node package="example.app" class="android.widget.FrameLayout" bounds="[0,0][2,3]" enabled="true"><node package="example.app" class="android.widget.TextView" bounds="[0,1][2,3]" text="A &amp; B" clickable="false"/></node></hierarchy>"#;
const SIZE: &[u8] = b"Physical size: 2x3\n";
const DENSITY: &[u8] = b"Physical density: 320\n";

fn screenshot(width: u32, height: u32) -> Vec<u8> {
    let mut bytes = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut bytes, width, height);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        encoder
            .write_header()
            .unwrap()
            .write_image_data(&vec![255; width as usize * height as usize * 4])
            .unwrap();
    }
    bytes
}

fn parse(xml: &str, png: &[u8]) -> Result<InspectorProtocolFrame, String> {
    parse_capture(
        xml.as_bytes(),
        png,
        SIZE,
        DENSITY,
        CompatibleTarget::ForegroundApp,
        7,
        42,
        &CancellationToken::new(),
    )
}

#[test]
fn compatible_projection_preserves_evidence_without_compose_claims() {
    let png = screenshot(2, 3);
    let frame = parse(XML, &png).unwrap();
    assert_eq!(frame.generation, 7);
    assert_eq!(frame.archive.frame.package_name, "example.app");
    assert_eq!(frame.archive.display.density, 2.0);
    assert!(!frame.archive.capabilities.compose_semantics);
    assert!(frame.archive.compose.is_none());
    assert_eq!(
        frame.archive.screenshot_png.as_deref(),
        Some(png.as_slice())
    );
    let window = &frame.archive.windows[0];
    // Kotlin's accessibility fallback window identity.
    assert_eq!(window.id, "window:uiautomator");
    assert_eq!(window.kind, "ACTIVITY");
    let nodes = &window.nodes;
    assert_eq!(nodes[1].parent_index, Some(0));
    assert_eq!(nodes[1].text.as_deref(), Some("A & B"));
    // Kotlin's UIAutomator projection ids are structural paths.
    assert_eq!(nodes[1].id, "root/0");
    assert_eq!(nodes[0].attributes.enabled, Some(true));
    // Kotlin's UIAutomator parser keeps only the accessibility raw properties.
    assert!(nodes[0].attributes.raw_properties.is_empty());
    assert!(nodes[0].attributes.visibility.is_none());
}

#[test]
fn live_accessibility_frame_exports_without_an_input_archive_path() {
    let png = screenshot(2, 3);
    let frame = parse(XML, &png).unwrap();
    let temp = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let path = temp.path().join("live.zip");
    let cancel = CancellationToken::new();
    frame.archive.export_archive(&path, &cancel).unwrap();
    let imported = crate::InspectorArchive::open(&path, &cancel).unwrap();
    assert_eq!(imported.frame.captured_at_epoch_millis, 42);
    assert_eq!(imported.display.density, 2.0);
    assert_eq!(imported.default_window_id, frame.archive.default_window_id);
    assert_eq!(imported.windows[0].nodes[1].text.as_deref(), Some("A & B"));
    assert_eq!(imported.screenshot_png.as_deref(), Some(png.as_slice()));
    assert!(!imported.capabilities.compose_semantics);
    assert!(!imported.capabilities.timeline);
    assert!(imported.compose.is_none());
}

#[test]
fn rejects_bad_xml_dtd_rotation_packages_and_png() {
    let png = screenshot(2, 3);
    assert!(parse("not xml", &png).is_err());
    assert!(
        parse(
            &XML.replace("<hierarchy", "<!DOCTYPE hierarchy><hierarchy"),
            &png
        )
        .is_err()
    );
    // Display mismatch between the accessibility dump and the screenshot.
    assert!(parse(XML, &screenshot(3, 2)).is_err());
    assert!(parse(XML, b"not png").is_err());
    let mut broken = png.clone();
    broken.truncate(broken.len() / 2);
    assert!(parse(XML, &broken).is_err());
    let two = XML.replace(
        "</hierarchy>",
        r#"<node package="other.app" class="Other" bounds="[0,0][2,3]"/></hierarchy>"#,
    );
    assert!(parse(&two, &png).is_err());
    let system = XML.replace("example.app", "com.android.systemui");
    assert!(parse(&system, &png).is_err());
    assert!(
        parse_capture(
            system.as_bytes(),
            &png,
            SIZE,
            DENSITY,
            CompatibleTarget::SystemUi,
            8,
            43,
            &CancellationToken::new()
        )
        .is_ok()
    );
    // Kotlin's UIAutomator parser does not clamp node bounds to the display:
    // offscreen and inverted sides stay representable (inverted extents coerce
    // to zero so the structural bounds invariant still holds).
    let offscreen = XML.replace("[0,1][2,3]", "[-5,1][2,3]");
    let frame = parse(&offscreen, &png).unwrap();
    assert_eq!(frame.archive.windows[0].nodes[1].bounds.left, -5);
    let inverted = XML.replace("[0,1][2,3]", "[2,3][0,1]");
    let frame = parse(&inverted, &png).unwrap();
    let bounds = frame.archive.windows[0].nodes[1].bounds;
    assert_eq!(
        (bounds.left, bounds.top, bounds.right, bounds.bottom),
        (2, 3, 2, 3)
    );
}

#[test]
fn accessibility_projection_matches_kotlin_node_fields() {
    let xml = r#"<?xml version="1.0"?><hierarchy rotation="0"><node package="example.app" class="android.widget.FrameLayout" bounds="[0,0][2,3]" visible-to-user="true"><node package="example.app" class="android.widget.Button" bounds="[0,0][1,1]" text="" resource-id=" " content-desc="" visible-to-user="false" checkable="true" scrollable="false" hint="h"/></node></hierarchy>"#;
    let frame = parse(xml, &screenshot(2, 3)).unwrap();
    let nodes = &frame.archive.windows[0].nodes;
    // Kotlin's parse: blank text/resource/content-description become null.
    assert_eq!(nodes[1].text, None);
    assert_eq!(nodes[1].resource_name, None);
    assert_eq!(nodes[1].attributes.content_description, None);
    // visible-to-user maps to the visible flag and visibility label.
    assert!(!nodes[1].visible);
    assert_eq!(
        nodes[1].attributes.visibility.as_deref(),
        Some("NOT_VISIBLE_TO_USER")
    );
    assert_eq!(nodes[1].alpha, 1.0);
    // Raw properties are the five accessibility attributes only.
    let raw = &nodes[1].attributes.raw_properties;
    assert_eq!(raw.get("checkable").map(String::as_str), Some("true"));
    assert_eq!(raw.get("scrollable").map(String::as_str), Some("false"));
    assert_eq!(raw.get("hint").map(String::as_str), Some("h"));
}

#[test]
fn compose_host_accessibility_projection_promotes_semantics() {
    let xml = r#"<?xml version="1.0"?><hierarchy rotation="0"><node package="example.app" class="android.widget.FrameLayout" bounds="[0,0][2,3]"><node package="example.app" class="androidx.compose.ui.platform.ComposeView" bounds="[0,0][2,3]"><node package="example.app" class="android.widget.Button" bounds="[0,0][2,1]" text="Go" enabled="true" visible-to-user="true"/></node></node></hierarchy>"#;
    let frame = parse(xml, &screenshot(2, 3)).unwrap();
    assert!(frame.archive.capabilities.compose_semantics);
    let nodes = &frame.archive.windows[0].nodes;
    assert_eq!(nodes[0].kind, crate::NodeKind::View);
    assert_eq!(nodes[1].kind, crate::NodeKind::View);
    let button = &nodes[2];
    assert_eq!(button.kind, crate::NodeKind::Compose);
    assert_eq!(button.class_name, "Button");
    assert_eq!(button.semantics_role.as_deref(), Some("Button"));
    assert_eq!(button.text.as_deref(), Some("Go"));
    assert_eq!(
        button
            .attributes
            .semantic_properties
            .get("AccessibilityClassName")
            .map(String::as_str),
        Some("android.widget.Button")
    );
    assert_eq!(
        button
            .attributes
            .semantic_properties
            .get("Enabled")
            .map(String::as_str),
        Some("true")
    );
}

#[test]
fn rotation_override_density_precancel_and_text_limits() {
    let rotated = XML
        .replace("rotation=\"0\"", "rotation=\"1\"")
        .replace("[0,0][2,3]", "[0,0][3,2]")
        .replace("[0,1][2,3]", "[0,1][3,2]");
    let frame = parse(&rotated, &screenshot(3, 2)).unwrap();
    assert_eq!(
        (
            frame.archive.display.width_px,
            frame.archive.display.height_px
        ),
        (3, 2)
    );
    assert_eq!(
        wm_value(b"Physical size: 20x30\nOverride size: 2x3\n", "size").unwrap(),
        "2x3"
    );
    let cancel = CancellationToken::new();
    cancel.cancel();
    assert!(
        parse_capture(
            XML.as_bytes(),
            &screenshot(2, 3),
            SIZE,
            DENSITY,
            CompatibleTarget::ForegroundApp,
            1,
            1,
            &cancel
        )
        .is_err()
    );
    assert!(
        parse(
            &XML.replace("A &amp; B", &"x".repeat(65537)),
            &screenshot(2, 3)
        )
        .is_err()
    );
}

#[cfg(unix)]
#[test]
fn fake_adb_checks_commands_binary_transport_failures_and_cancellation() {
    use aps_adb::AdbLocationSource;
    use std::fs;
    use std::os::unix::fs::PermissionsExt;
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    struct Fixture(std::path::PathBuf);
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }
    let fixture = Fixture(std::env::temp_dir().join(format!(
        "aps-inspector-adb-{}-{}",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    )));
    fs::create_dir(&fixture.0).unwrap();
    fs::write(fixture.0.join("screen.png"), screenshot(2, 3)).unwrap();
    fs::write(fixture.0.join("hierarchy.xml"), XML).unwrap();
    let executable = fixture.0.join("adb");
    fs::write(
        &executable,
        r#"#!/bin/sh
[ "$1" = '-s' ] && [ "$2" = 'test-serial' ] || exit 21
shift 2
case "$*" in
 'shell dumpsys activity activities') printf 'mFocusedApp=ActivityRecord{abc u0 example.app/.Main}
' ;;
 'shell wm size') printf 'Physical size: 2x3
' ;;
 'shell wm density') printf 'Physical density: 320
' ;;
 'exec-out cmd window dump-visible-window-views') exit 23 ;;
 'exec-out uiautomator dump /dev/tty') cat "$(dirname "$0")/hierarchy.xml" ;;
 'exec-out screencap -p') cat "$(dirname "$0")/screen.png" ;;
 *) exit 22 ;;
esac
"#,
    )
    .unwrap();
    fs::set_permissions(&executable, fs::Permissions::from_mode(0o700)).unwrap();
    let adb = AdbLocation {
        executable: executable.clone(),
        source: AdbLocationSource::ExplicitExecutable,
    };
    // The runtime View dump fails, so capture falls back to the accessibility
    // projection after the bounded retries.
    let frame = capture_compatible(
        &adb,
        "test-serial",
        CompatibleTarget::ForegroundApp,
        9,
        &CancellationToken::new(),
    )
    .unwrap();
    assert_eq!(frame.archive.frame.package_name, "example.app");
    assert_eq!(frame.archive.windows[0].id, "window:uiautomator");
    assert!(
        capture_compatible(
            &adb,
            "-unsafe",
            CompatibleTarget::ForegroundApp,
            10,
            &CancellationToken::new()
        )
        .is_err()
    );
    fs::write(&executable, "#!/bin/sh\nexit 42\n").unwrap();
    assert!(
        capture_compatible(
            &adb,
            "test-serial",
            CompatibleTarget::ForegroundApp,
            10,
            &CancellationToken::new()
        )
        .unwrap_err()
        .contains("exit Some(42)")
    );
    let cancel = CancellationToken::new();
    cancel.cancel();
    assert!(
        capture_compatible(
            &adb,
            "test-serial",
            CompatibleTarget::ForegroundApp,
            11,
            &cancel
        )
        .unwrap_err()
        .contains("cancelled")
    );
}
