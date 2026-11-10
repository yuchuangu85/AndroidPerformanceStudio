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
    let nodes = &frame.archive.windows[0].nodes;
    assert_eq!(nodes[1].parent_index, Some(0));
    assert_eq!(nodes[1].text.as_deref(), Some("A & B"));
    assert!(nodes[1].id.starts_with("uiautomator:7:"));
    assert_eq!(nodes[0].attributes.enabled, Some(true));
    assert!(nodes[0].attributes.raw_properties["captureConsistency"].contains("non-atomic"));
}

#[test]
fn rejects_bad_xml_dtd_geometry_ambiguous_package_and_png() {
    let png = screenshot(2, 3);
    assert!(parse("not xml", &png).is_err());
    assert!(
        parse(
            &XML.replace("<hierarchy", "<!DOCTYPE hierarchy><hierarchy"),
            &png
        )
        .is_err()
    );
    assert!(parse(&XML.replace("[0,0][2,3]", "[0,0][3,3]"), &png).is_err());
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
    let executable = fixture.0.join("adb");
    fs::write(
        &executable,
        format!(
            r##"#!/bin/sh
[ "$1" = '-s' ] && [ "$2" = 'test-serial' ] || exit 21
shift 2
case "$*" in
 'shell dumpsys activity activities') printf 'topResumedActivity=ActivityRecord{{abc u0 example.app/.Main}}\n' ;;
 'shell wm size') printf 'Physical size: 2x3\n' ;;
 'shell wm density') printf 'Physical density: 320\n' ;;
 'exec-out uiautomator dump /dev/tty') printf '%s' '{}' ;;
 'exec-out screencap -p') cat "$(dirname "$0")/screen.png" ;;
 *) exit 22 ;;
esac
"##,
            XML
        ),
    )
    .unwrap();
    fs::set_permissions(&executable, fs::Permissions::from_mode(0o700)).unwrap();
    let adb = AdbLocation {
        executable: executable.clone(),
        source: AdbLocationSource::ExplicitExecutable,
    };
    let frame = capture_compatible(
        &adb,
        "test-serial",
        CompatibleTarget::ForegroundApp,
        9,
        &CancellationToken::new(),
    )
    .unwrap();
    assert_eq!(frame.archive.frame.package_name, "example.app");
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
