use aps_contracts::SourceDigest;
use aps_layout_inspector::{InspectorArchive, InspectorError, NodeKind};
use aps_platform::CancellationToken;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use zip::{CompressionMethod, ZipWriter, write::SimpleFileOptions};

static NEXT: AtomicU64 = AtomicU64::new(0);
struct Fixture(PathBuf);
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}
fn fixture(snapshot: &str, expected_digest: Option<&str>, extra: Option<(&str, &[u8])>) -> Fixture {
    let path = std::env::temp_dir().join(format!(
        "aps-inspector-archive-{}-{}.zip",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    let digest = SourceDigest::hash_reader(snapshot.as_bytes(), snapshot.len() as u64)
        .unwrap()
        .to_hex();
    let digest = expected_digest.unwrap_or(&digest);
    let manifest = format!(
        r#"{{"format":"agentperf-inspector-capture","archiveVersion":1,"producerVersion":"test","packageName":"example.test","capturedAtEpochMillis":42,"protocolMajor":1,"protocolMinor":0,"entries":[{{"path":"capture/layout-snapshot.json","size":{},"sha256":"{}","required":true}}]}}"#,
        snapshot.len(),
        digest
    );
    let file = OpenOptions::new()
        .create_new(true)
        .write(true)
        .read(true)
        .open(&path)
        .unwrap();
    let mut zip = ZipWriter::new(file);
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
    zip.start_file("manifest.json", options).unwrap();
    zip.write_all(manifest.as_bytes()).unwrap();
    zip.start_file("capture/layout-snapshot.json", options)
        .unwrap();
    zip.write_all(snapshot.as_bytes()).unwrap();
    if let Some((name, bytes)) = extra {
        zip.start_file(name, options).unwrap();
        zip.write_all(bytes).unwrap();
    }
    zip.finish().unwrap();
    Fixture(path)
}
const SNAPSHOT: &str = r#"{"protocolVersion":{"major":1,"minor":0},"packageName":"example.test","capturedAtEpochMillis":42,"display":{"widthPx":1080,"heightPx":2400,"density":3.0},"capabilities":{"viewHierarchy":true,"composeSemantics":false,"screenshots":false,"timeline":false},"root":{"type":"view","id":"root","className":"android.widget.FrameLayout","bounds":{"left":0,"top":0,"right":1080,"bottom":2400},"visible":true,"alpha":1.0,"text":"sensitive runtime content","children":[{"type":"compose","id":"child","className":"Example","bounds":{"left":10,"top":20,"right":100,"bottom":80},"children":[]}]}}"#;

#[test]
fn imports_native_zip_and_preserves_frame_hierarchy_canvas_properties() {
    let file = fixture(SNAPSHOT, None, None);
    let archive = InspectorArchive::open(&file.0, &CancellationToken::new()).unwrap();
    assert_eq!(archive.archive_version, 1);
    assert_eq!(
        archive.frame.source_digest,
        SourceDigest::hash_file(&file.0, 172 * 1024 * 1024).unwrap()
    );
    assert_eq!(archive.frame.package_name, "example.test");
    assert_eq!(archive.frame.captured_at_epoch_millis, 42);
    assert_eq!(archive.frame.protocol_minor, 0);
    assert_eq!(archive.default_window_id, "window:legacy");
    assert!(archive.capabilities.view_hierarchy);
    assert!(!archive.capabilities.compose_semantics);
    assert!(archive.compose.is_none());
    let nodes = archive.hierarchy(&archive.default_window_id, 0, 2).unwrap();
    assert_eq!(nodes[1].kind, NodeKind::Compose);
    assert_eq!(nodes[1].parent_index, Some(0));
    assert_eq!(nodes[1].bounds.left, 10);
    // Runtime text is now retained (bounded) for Compose-parity properties.
    assert_eq!(nodes[0].text.as_deref(), Some("sensitive runtime content"));
    assert!(nodes[0].attributes.raw_properties.is_empty());
    assert!(
        !archive
            .canvas(&archive.default_window_id)
            .unwrap()
            .screenshot_available
    );
    assert_eq!(
        archive
            .properties(&archive.default_window_id, "child")
            .unwrap()
            .depth,
        1
    );
    assert!(matches!(
        archive.hierarchy(&archive.default_window_id, 0, 201),
        Err(InspectorError::InvalidPage)
    ));
}

#[test]
fn accepts_compose_style_omitted_inset_sides_and_node_semantics() {
    // Compose encodes `EdgeInsets` with `encodeDefaults=false`, so zero sides
    // are omitted, and Compose nodes keep semanticProperties on the node.
    let snapshot = SNAPSHOT.replace(
        "\"text\":\"sensitive runtime content\"",
        "\"text\":\"hello\",\"attributes\":{\"padding\":{\"left\":16,\"right\":16},\"margin\":{\"top\":8},\"layoutRequested\":null,\"rawProperties\":{\"text:mText\":\"hello\"}},\"semanticProperties\":{\"semantics\":\"Button\"}",
    );
    let file = fixture(&snapshot, None, None);
    let archive = InspectorArchive::open(&file.0, &CancellationToken::new()).unwrap();
    let root = &archive.windows[0].nodes[0];
    let padding = root.attributes.padding.expect("padding retained");
    assert_eq!(
        (padding.left, padding.top, padding.right, padding.bottom),
        (16, 0, 16, 0)
    );
    let margin = root.attributes.margin.expect("margin retained");
    assert_eq!(
        (margin.left, margin.top, margin.right, margin.bottom),
        (0, 8, 0, 0)
    );
    assert_eq!(root.attributes.layout_requested, None);
    assert_eq!(
        root.attributes
            .semantic_properties
            .get("semantics")
            .map(String::as_str),
        Some("Button")
    );
}

#[test]
fn retains_bounded_protocol_attributes_text_and_raw_properties() {
    let snapshot = SNAPSHOT.replace(
        "\"text\":\"sensitive runtime content\"",
        "\"text\":\"hello\",\"attributes\":{\"visibility\":\"VISIBLE\",\"layoutWidth\":-1,\"measuredWidth\":1080,\"measuredHeight\":2160,\"padding\":{\"left\":1,\"top\":2,\"right\":3,\"bottom\":4},\"elevation\":8.0,\"clipChildren\":true,\"rawProperties\":{\"text:mText\":\"hello\"}}",
    );
    let file = fixture(&snapshot, None, None);
    let archive = InspectorArchive::open(&file.0, &CancellationToken::new()).unwrap();
    let root = &archive.windows[0].nodes[0];
    assert_eq!(root.text.as_deref(), Some("hello"));
    assert_eq!(root.attributes.visibility.as_deref(), Some("VISIBLE"));
    assert_eq!(root.attributes.layout_width, Some(-1));
    assert_eq!(root.attributes.measured_width, Some(1080));
    assert_eq!(root.attributes.measured_height, Some(2160));
    assert_eq!(root.attributes.padding.map(|insets| insets.right), Some(3));
    assert_eq!(root.attributes.elevation, Some(8.0));
    assert_eq!(root.attributes.clip_children, Some(true));
    assert_eq!(
        root.attributes
            .raw_properties
            .get("text:mText")
            .map(String::as_str),
        Some("hello")
    );
}

#[test]
fn rejects_entry_digest_manifest_and_frame_mismatches() {
    let digest = "0".repeat(64);
    let bad_digest = fixture(SNAPSHOT, Some(&digest), None);
    assert!(matches!(
        InspectorArchive::open(&bad_digest.0, &CancellationToken::new()),
        Err(InspectorError::Integrity)
    ));
    let bad_timestamp = fixture(
        &SNAPSHOT.replace(
            "\"capturedAtEpochMillis\":42",
            "\"capturedAtEpochMillis\":43",
        ),
        None,
        None,
    );
    assert!(matches!(
        InspectorArchive::open(&bad_timestamp.0, &CancellationToken::new()),
        Err(InspectorError::FrameMismatch)
    ));
    let undeclared = fixture(SNAPSHOT, None, Some(("unexpected.txt", b"private")));
    assert!(matches!(
        InspectorArchive::open(&undeclared.0, &CancellationToken::new()),
        Err(InspectorError::InvalidManifest)
    ));
}

#[test]
fn rejects_path_traversal_duplicate_node_identity_and_precancel() {
    let traversal = fixture(SNAPSHOT, None, Some(("../outside.txt", b"private")));
    assert!(matches!(
        InspectorArchive::open(&traversal.0, &CancellationToken::new()),
        Err(InspectorError::InvalidZip)
    ));
    let duplicate = fixture(
        &SNAPSHOT.replace("\"id\":\"child\"", "\"id\":\"root\""),
        None,
        None,
    );
    assert!(matches!(
        InspectorArchive::open(&duplicate.0, &CancellationToken::new()),
        Err(InspectorError::InvalidSnapshot)
    ));
    let cancel = CancellationToken::new();
    cancel.cancel();
    assert!(matches!(
        InspectorArchive::open(&duplicate.0, &cancel),
        Err(InspectorError::Cancelled)
    ));
}
