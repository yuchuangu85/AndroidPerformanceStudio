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

const ALL_WINDOWS_XML: &str = r#"<?xml version="1.0"?><hierarchy rotation="0"><node package="com.android.systemui" class="StatusBar" bounds="[0,0][2,1]"/><node package="example.app" class="MainActivity" bounds="[0,1][2,3]"><node package="example.app" class="TextView" bounds="[0,1][2,2]"/></node><node class="TctStylusWindow" bounds="[0,0][2,3]"/></hierarchy>"#;

#[test]
fn system_accessibility_cannot_claim_window_manager_type_evidence() {
    let error = parse_capture(
        ALL_WINDOWS_XML.as_bytes(),
        &screenshot(2, 3),
        SIZE,
        DENSITY,
        CompatibleTarget::SystemUi,
        8,
        43,
        &CancellationToken::new(),
    )
    .unwrap_err();
    assert!(error.contains("WindowManager types"));
}

#[test]
fn system_capture_fails_closed_without_window_manager_types_and_never_uses_xml() {
    let cancel = CancellationToken::new();
    let calls = std::cell::Cell::new(0);
    let run = |args: &[&str], _limit| match args {
        ["shell", "dumpsys", "window", "windows"] => {
            calls.set(calls.get() + 1);
            Err("permission denied".into())
        }
        _ => panic!("unexpected capture command"),
    };
    let error = capture_hierarchy(
        &run,
        SYSTEM_CAPTURE_SCOPE,
        CompatibleTarget::SystemUi,
        SIZE,
        &cancel,
    )
    .err()
    .unwrap();
    assert_eq!(calls.get(), WINDOW_DUMP_ATTEMPTS);
    assert!(error.contains("WindowManager type >= 2000"));
    assert!(error.contains("Accessibility fallback cannot verify"));
}

const WINDOW_TYPES: &str = "  Window #0 Window{a3db2af u0 StatusBar}:\n    mAttrs={ty=STATUS_BAR}\n  Window #1 Window{b473c9d u0 Taskbar}:\n    mAttrs={ty=NAVIGATION_BAR}\n";

fn capture_system_fixture(after: &str) -> (Result<Capture, String>, Vec<Vec<String>>) {
    let calls = std::cell::RefCell::new(Vec::new());
    let snapshots = std::cell::Cell::new(0);
    let zip = visible_window::tests::system_ui_bare_taskbar_zip();
    let run = |args: &[&str], _limit| {
        calls
            .borrow_mut()
            .push(args.iter().map(|value| (*value).to_owned()).collect());
        match args {
            ["shell", "dumpsys", "window", "windows"] => {
                let index = snapshots.get();
                snapshots.set(index + 1);
                Ok(if index % 2 == 0 { WINDOW_TYPES } else { after }
                    .as_bytes()
                    .to_vec())
            }
            ["exec-out", "cmd", "window", "dump-visible-window-views"] => Ok(zip.clone()),
            _ => panic!("unexpected capture command"),
        }
    };
    let result = capture_hierarchy(
        &run,
        SYSTEM_CAPTURE_SCOPE,
        CompatibleTarget::SystemUi,
        SIZE,
        &CancellationToken::new(),
    );
    (result, calls.into_inner())
}

#[test]
fn system_capture_binds_window_types_to_source_evidence_and_archive() {
    let (capture, calls) = capture_system_fixture(WINDOW_TYPES);
    let capture = capture.unwrap();
    assert_eq!(
        calls,
        vec![
            vec!["shell", "dumpsys", "window", "windows"],
            vec!["exec-out", "cmd", "window", "dump-visible-window-views"],
            vec!["shell", "dumpsys", "window", "windows"],
        ]
    );
    let frame = parse_frame(
        &capture.hierarchy,
        &capture.evidence,
        SYSTEM_CAPTURE_SCOPE,
        &screenshot(2, 3),
        SIZE,
        DENSITY,
        8,
        43,
        &CancellationToken::new(),
    )
    .unwrap();
    assert_eq!(frame.archive.frame.package_name, "system-windows");
    assert_eq!(frame.archive.windows.len(), 2);
    assert_eq!(
        frame.archive.windows[0].nodes[0].attributes.raw_properties["WindowManager.LayoutParams.type"],
        "2000"
    );
    assert_eq!(
        frame.archive.windows[1].nodes[0].attributes.raw_properties["WindowManager.LayoutParams.type"],
        "2019"
    );
    let temp = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let path = temp.path().join("system-types.zip");
    frame
        .archive
        .export_archive(&path, &CancellationToken::new())
        .unwrap();
    let imported = crate::InspectorArchive::open(&path, &CancellationToken::new()).unwrap();
    assert_eq!(
        imported.windows[1].nodes[0].attributes.raw_properties["WindowManager.LayoutParams.type"],
        "2019"
    );

    let changed_types = WINDOW_TYPES.replace("STATUS_BAR", "2003");
    let zip = visible_window::tests::system_ui_bare_taskbar_zip();
    let run = |args: &[&str], _limit| match args {
        ["shell", "dumpsys", "window", "windows"] => Ok(changed_types.as_bytes().to_vec()),
        ["exec-out", "cmd", "window", "dump-visible-window-views"] => Ok(zip.clone()),
        _ => panic!("unexpected command"),
    };
    let changed = capture_system_windows(&run, &CancellationToken::new()).unwrap();
    let changed = parse_frame(
        &changed.hierarchy,
        &changed.evidence,
        SYSTEM_CAPTURE_SCOPE,
        &screenshot(2, 3),
        SIZE,
        DENSITY,
        8,
        43,
        &CancellationToken::new(),
    )
    .unwrap();
    assert_ne!(
        frame.archive.frame.source_digest,
        changed.archive.frame.source_digest
    );
}

#[test]
fn system_capture_rejects_changed_types_and_has_no_accessibility_escape() {
    let changed = WINDOW_TYPES
        .replace("STATUS_BAR", "BASE_APPLICATION")
        .replace("NAVIGATION_BAR", "APPLICATION_PANEL");
    let (capture, calls) = capture_system_fixture(&changed);
    assert!(
        capture
            .err()
            .unwrap()
            .contains("WindowManager type >= 2000")
    );
    assert_eq!(calls.len(), WINDOW_DUMP_ATTEMPTS * 3);
    assert!(
        !calls
            .iter()
            .any(|args| args.iter().any(|value| value == "uiautomator"))
    );

    let partially_changed = WINDOW_TYPES.replace("STATUS_BAR", "BASE_APPLICATION");
    let (capture, _) = capture_system_fixture(&partially_changed);
    let capture = capture.unwrap();
    let CapturedHierarchy::VisibleWindows(windows) = capture.hierarchy else {
        panic!("expected runtime windows")
    };
    assert_eq!(windows.len(), 1);
    assert_eq!(windows[0].title, "Taskbar");
}

#[test]
fn system_runtime_windows_are_not_replaced_by_a_narrower_compose_projection() {
    let calls = std::cell::Cell::new(0);
    let run = |_args: &[&str], _limit| {
        calls.set(calls.get() + 1);
        Ok(XML.as_bytes().to_vec())
    };
    let cancel = CancellationToken::new();
    let expanded = expand_compose_windows(
        &run,
        SYSTEM_CAPTURE_SCOPE,
        CompatibleTarget::SystemUi,
        SIZE,
        &cancel,
    )
    .unwrap();
    assert!(expanded.is_none());
    assert_eq!(calls.get(), 0);
    assert!(
        expand_compose_windows(
            &run,
            "example.app",
            CompatibleTarget::ForegroundApp,
            SIZE,
            &cancel
        )
        .unwrap()
        .is_some()
    );
    assert_eq!(calls.get(), 1);
}

#[test]
fn foreground_accessibility_retains_archive_window_limits() {
    let roots = r#"<node package="example.app" class="Window" bounds="[0,0][2,3]"/>"#.repeat(65);
    let xml = format!("<hierarchy rotation=\"0\">{roots}</hierarchy>");
    assert!(
        parse_capture(
            xml.as_bytes(),
            &screenshot(2, 3),
            SIZE,
            DENSITY,
            CompatibleTarget::ForegroundApp,
            8,
            43,
            &CancellationToken::new()
        )
        .is_err()
    );
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
        .is_err()
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
