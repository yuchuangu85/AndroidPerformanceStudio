use crate::*;
use aps_platform::TempWorkspace;

const WINDOW_IDS: [&str; 3] = ["window:0x9", "window:0x2", "window:0x5"];
const WINDOW_TITLES: [&str; 3] = ["Taskbar", "StatusBar", "NotificationShade"];

fn snapshot() -> Value {
    let windows: Vec<_> = WINDOW_IDS
        .iter()
        .zip(WINDOW_TITLES)
        .map(|(id, title)| {
            serde_json::json!({
                "id": id,
                "title": title,
                "type": "SYSTEM",
                "bounds": {"left": 0, "top": 0, "right": 1080, "bottom": 2400},
                "root": {
                    "type": "view",
                    "id": format!("{id}:root"),
                    "className": "android.widget.FrameLayout",
                    "bounds": {"left": 0, "top": 0, "right": 1080, "bottom": 2400},
                    "children": [{
                        "type": "view",
                        "id": format!("{id}:label"),
                        "className": "android.widget.TextView",
                        "bounds": {"left": 0, "top": 0, "right": 100, "bottom": 40},
                        "children": []
                    }]
                }
            })
        })
        .collect();
    serde_json::json!({
        "protocolVersion": {"major": 1, "minor": 0},
        "packageName": "com.android.systemui",
        "capturedAtEpochMillis": 42,
        "display": {"widthPx": 1080, "heightPx": 2400, "density": 2.75},
        "capabilities": {"viewHierarchy": true},
        "root": windows[0]["root"].clone(),
        "windows": windows,
        "defaultWindowId": WINDOW_IDS[1]
    })
}

fn parse(value: &Value) -> InspectorArchive {
    let bytes = serde_json::to_vec(value).unwrap();
    let value = aps_json::parse_slice(&bytes, json_limits(entry_limit(SNAPSHOT))).unwrap();
    parse_snapshot(
        &value,
        FrameIdentity {
            source_digest: SourceDigest::from_bytes([7; 32]),
            package_name: "com.android.systemui".into(),
            captured_at_epoch_millis: 42,
            protocol_major: 1,
            protocol_minor: 0,
        },
        1,
    )
    .unwrap()
}

fn export_and_reopen(archive: &InspectorArchive) -> (InspectorArchive, ArchiveExport) {
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let output = temp.path().join("window-titles.zip");
    let cancel = CancellationToken::new();
    let result = archive.export_archive(&output, &cancel).unwrap();
    let reopened = InspectorArchive::open(&output, &cancel).unwrap();
    (reopened, result)
}

fn assert_multiwindow_structure(archive: &InspectorArchive) {
    assert_eq!(archive.windows.len(), WINDOW_IDS.len());
    assert_eq!(archive.default_window_id, WINDOW_IDS[1]);
    for (window, id) in archive.windows.iter().zip(WINDOW_IDS) {
        assert_eq!(window.id, id);
        assert_eq!(window.kind, "SYSTEM");
        assert_eq!(window.nodes.len(), 2);
        assert_eq!(window.nodes[0].id, format!("{id}:root"));
        assert_eq!(window.nodes[1].id, format!("{id}:label"));
        assert_eq!(window.nodes[1].parent_index, Some(0));
    }
}

#[test]
fn snapshot_preserves_system_window_titles_verbatim() {
    let archive = parse(&snapshot());
    assert_multiwindow_structure(&archive);
    for (window, title) in archive.windows.iter().zip(WINDOW_TITLES) {
        assert_eq!(window.title.as_deref(), Some(title));
    }

    let mut value = snapshot();
    let title = "  Taskbar / \u{5916}\u{63a5}\u{5c4f}  ";
    value["windows"][0]["title"] = title.into();
    let archive = parse(&value);
    assert_eq!(archive.windows[0].title.as_deref(), Some(title));
}

#[test]
fn old_multiwindow_archive_without_titles_remains_readable() {
    let mut value = snapshot();
    for window in value["windows"].as_array_mut().unwrap() {
        window.as_object_mut().unwrap().remove("title");
    }
    let archive = parse(&value);
    assert_multiwindow_structure(&archive);
    assert!(archive.windows.iter().all(|window| window.title.is_none()));

    let (reopened, _) = export_and_reopen(&archive);
    assert_multiwindow_structure(&reopened);
    assert!(reopened.windows.iter().all(|window| window.title.is_none()));
}

#[test]
fn legacy_root_only_archive_does_not_require_window_titles() {
    for empty_windows in [false, true] {
        let mut value = snapshot();
        let root = value.as_object_mut().unwrap();
        root.remove("defaultWindowId");
        if empty_windows {
            root.insert("windows".into(), serde_json::json!([]));
        } else {
            root.remove("windows");
        }
        let archive = parse(&value);
        let (reopened, _) = export_and_reopen(&archive);
        for archive in [&archive, &reopened] {
            assert_eq!(archive.windows.len(), 1);
            assert_eq!(archive.windows[0].id, "window:legacy");
            assert_eq!(archive.default_window_id, "window:legacy");
            assert!(archive.windows[0].title.is_none());
            assert_eq!(archive.windows[0].nodes.len(), 2);
            assert_eq!(archive.windows[0].nodes[1].parent_index, Some(0));
        }
    }
}

#[test]
fn null_and_invalid_title_types_do_not_reject_snapshot() {
    for title in [
        Value::Null,
        serde_json::json!(true),
        serde_json::json!(false),
        serde_json::json!(0),
        serde_json::json!(-42),
        serde_json::json!(2.75),
        serde_json::json!([]),
        serde_json::json!(["Taskbar"]),
        serde_json::json!({}),
        serde_json::json!({"title": "StatusBar"}),
    ] {
        let mut value = snapshot();
        value["windows"][1]["title"] = title.clone();
        let archive = parse(&value);
        assert_multiwindow_structure(&archive);
        assert!(archive.windows[1].title.is_none(), "title: {title:?}");
        assert_eq!(archive.windows[0].title.as_deref(), Some("Taskbar"));
        assert_eq!(
            archive.windows[2].title.as_deref(),
            Some("NotificationShade")
        );
    }
}

#[test]
fn empty_and_whitespace_titles_do_not_block_structure_parsing() {
    for title in ["", " ", "\t\r\n", "\u{2003}"] {
        let mut value = snapshot();
        value["windows"][1]["title"] = title.into();
        let archive = parse(&value);
        assert_multiwindow_structure(&archive);
        assert_eq!(archive.windows[0].title.as_deref(), Some("Taskbar"));
        assert_eq!(
            archive.windows[2].title.as_deref(),
            Some("NotificationShade")
        );
    }
}

#[test]
fn title_byte_limit_preserves_unicode_boundaries() {
    for (title, expected) in [
        ("a".repeat(4096), "a".repeat(4096)),
        ("a".repeat(4097), "a".repeat(4096)),
        ("\u{754c}".repeat(1365), "\u{754c}".repeat(1365)),
        ("\u{754c}".repeat(1366), "\u{754c}".repeat(1365)),
        ("\u{1f680}".repeat(1024), "\u{1f680}".repeat(1024)),
        ("\u{1f680}".repeat(1025), "\u{1f680}".repeat(1024)),
        (format!("{}\u{e9}", "a".repeat(4095)), "a".repeat(4095)),
        (format!("{}\u{754c}", "a".repeat(4094)), "a".repeat(4094)),
        (
            format!("{}\u{754c}tail", "a".repeat(4093)),
            format!("{}\u{754c}", "a".repeat(4093)),
        ),
        (
            format!("{}\u{1f680}tail", "a".repeat(4092)),
            format!("{}\u{1f680}", "a".repeat(4092)),
        ),
    ] {
        let mut value = snapshot();
        value["windows"][0]["title"] = title.clone().into();
        let archive = parse(&value);
        assert_multiwindow_structure(&archive);
        let actual = archive.windows[0].title.as_deref().unwrap();
        assert_eq!(actual, expected);
        assert!(actual.len() <= 4096);
        assert!(title.starts_with(actual));
        assert!(title.is_char_boundary(actual.len()));
    }
}

#[test]
fn retained_archive_roundtrip_preserves_titles_ids_and_default_window() {
    let archive = parse(&snapshot());
    let (reopened, result) = export_and_reopen(&archive);
    assert!(result.retained_payloads);
    assert_multiwindow_structure(&reopened);
    for (window, title) in reopened.windows.iter().zip(WINDOW_TITLES) {
        assert_eq!(window.title.as_deref(), Some(title));
    }
}

#[test]
fn typed_archive_roundtrip_preserves_titles_ids_and_default_window() {
    let mut archive = parse(&snapshot());
    // Discard retained JSON so the public exporter must serialize typed windows.
    archive.export_payload = ExportPayload::default();
    archive.windows[0].title = Some("Taskbar / \u{5916}\u{63a5}\u{5c4f}".into());
    let (reopened, result) = export_and_reopen(&archive);
    assert!(!result.retained_payloads);
    assert_multiwindow_structure(&reopened);
    assert_eq!(reopened.frame.package_name, archive.frame.package_name);
    assert_eq!(reopened.frame.captured_at_epoch_millis, 42);
    for (actual, expected) in reopened.windows.iter().zip(&archive.windows) {
        assert_eq!(actual.title, expected.title);
        assert_eq!(actual.id, expected.id);
        assert_eq!(actual.bounds, expected.bounds);
    }
}

#[test]
fn typed_archive_roundtrip_keeps_missing_titles_absent() {
    let mut value = snapshot();
    value["windows"][0].as_object_mut().unwrap().remove("title");
    value["windows"][2]["title"] = Value::Null;
    let mut archive = parse(&value);
    archive.export_payload = ExportPayload::default();
    let (reopened, result) = export_and_reopen(&archive);
    assert!(!result.retained_payloads);
    assert_multiwindow_structure(&reopened);
    assert!(reopened.windows[0].title.is_none());
    assert_eq!(reopened.windows[1].title.as_deref(), Some("StatusBar"));
    assert!(reopened.windows[2].title.is_none());
}

#[test]
#[ignore = "requires APS_INSPECTOR_SYSTEM_UI_SERIAL; reads the connected device System UI"]
fn real_system_ui_capture_retains_window_titles() {
    let serial = std::env::var("APS_INSPECTOR_SYSTEM_UI_SERIAL").expect("device serial");
    let adb = aps_adb::AdbLocator::new().locate(None).unwrap();
    let captured = capture_compatible(
        &adb,
        &serial,
        CompatibleTarget::SystemUi,
        1,
        &CancellationToken::new(),
    )
    .unwrap();
    assert!(!captured.archive.windows.is_empty());
    for window in &captured.archive.windows {
        let title = window
            .title
            .as_deref()
            .filter(|title| !title.trim().is_empty())
            .expect("captured window title");
        eprintln!(
            "System UI window: {} | {title} | nodes={}",
            window.id,
            window.nodes.len()
        );
    }
    let (reopened, _) = export_and_reopen(&captured.archive);
    for (actual, expected) in reopened.windows.iter().zip(&captured.archive.windows) {
        assert_eq!(actual.id, expected.id);
        assert_eq!(actual.title, expected.title);
    }
    if let Some(path) = std::env::var_os("APS_INSPECTOR_SYSTEM_UI_ARCHIVE") {
        captured
            .archive
            .export_archive(Path::new(&path), &CancellationToken::new())
            .unwrap();
    }
}
