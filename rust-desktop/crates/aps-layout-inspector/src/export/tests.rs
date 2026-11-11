use super::*;
use aps_platform::TempWorkspace;

fn snapshot() -> Value {
    let root = serde_json::json!({"type": "view", "id": "root", "className": "android.view.View",
        "bounds": {"left": -10, "top": -20, "right": 100, "bottom": 200},
        "attributes": {"layoutWidth": -1, "layoutHeight": -2, "translationX": -7.5,
            "rotation": -90.0, "enabled": false, "padding": {"left": -4},
            "clipBounds": {"left": 0, "top": 40, "right": 10, "bottom": -2},
            "rawProperties": {"runtime:text": "private runtime value"}},
        "semanticProperties": {"role": "Button"}, "text": "hello", "children": []});
    serde_json::json!({"protocolVersion": {"major": 1, "minor": 1}, "packageName": "example.test",
        "capturedAtEpochMillis": -42, "display": {"widthPx": 1080, "heightPx": 2400, "density": 2.75},
        "capabilities": {"viewHierarchy": true, "composeSemantics": false, "screenshots": false, "timeline": false},
        "root": root, "windows": [
            {"id": "dialog", "type": "DIALOG", "bounds": root["bounds"], "root": root},
            {"id": "activity", "type": "ACTIVITY", "bounds": root["bounds"], "root": root}],
        "defaultWindowId": "activity"})
}

fn typed(value: &Value) -> InspectorArchive {
    let mut archive = parse_snapshot(
        value,
        FrameIdentity {
            source_digest: SourceDigest::from_bytes([7; 32]),
            package_name: "example.test".into(),
            captured_at_epoch_millis: -42,
            protocol_major: 1,
            protocol_minor: 1,
        },
        1,
    )
    .unwrap();
    archive.export_payload = ExportPayload::default();
    archive
}

fn read_zip(path: &Path) -> BTreeMap<String, Vec<u8>> {
    let mut zip = ZipArchive::new(File::open(path).unwrap()).unwrap();
    (0..zip.len())
        .map(|index| {
            let mut entry = zip.by_index(index).unwrap();
            let name = entry.name().unwrap().into_owned();
            let mut bytes = Vec::new();
            entry.read_to_end(&mut bytes).unwrap();
            (name, bytes)
        })
        .collect()
}

fn png() -> Vec<u8> {
    let mut bytes = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut bytes, 1, 1);
        encoder.set_color(png::ColorType::Rgba);
        let mut writer = encoder.write_header().unwrap();
        writer.write_image_data(&[255, 0, 0, 255]).unwrap();
    }
    bytes
}

#[test]
fn typed_deep_multiwindow_roundtrip_preserves_signed_values_and_absence() {
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let mut value = snapshot();
    let mut root = value["root"].clone();
    // 128 hierarchy edges, well beyond serde_json's default reader depth.
    for index in (0..128).rev() {
        root = serde_json::json!({"type": "compose", "id": format!("node-{index}"), "className": "Example",
            "bounds": {"left": -1, "top": -2, "right": 10, "bottom": 20}, "children": [root]});
    }
    value["windows"][0]["root"] = root;
    let archive = typed(&value);
    let output = temp.path().join("typed.zip");
    let result = archive
        .export_archive(&output, &CancellationToken::new())
        .unwrap();
    assert!(!result.retained_payloads);
    let imported = InspectorArchive::open(&output, &CancellationToken::new()).unwrap();
    assert_eq!(imported.frame.source_digest, result.source_digest);
    assert_eq!(imported.frame.package_name, archive.frame.package_name);
    assert_eq!(imported.frame.captured_at_epoch_millis, -42);
    assert_eq!(imported.frame.protocol_minor, 1);
    assert_eq!(imported.display.density, 2.75);
    assert_eq!(imported.windows.len(), 2);
    assert_eq!(imported.default_window_id, "activity");
    assert_eq!(imported.windows[0].nodes.len(), 129);
    let leaf = imported.windows[0].nodes.last().unwrap();
    assert_eq!(leaf.depth, 128);
    assert_eq!(leaf.parent_index, Some(127));
    assert_eq!(leaf.attributes.layout_width, Some(-1));
    assert_eq!(leaf.attributes.layout_height, Some(-2));
    assert_eq!(leaf.attributes.translation_x, Some(-7.5));
    assert_eq!(leaf.attributes.padding.unwrap().left, -4);
    assert_eq!(leaf.attributes.clip_bounds.unwrap().bottom, -2);
    assert_eq!(leaf.attributes.enabled, Some(false));
    assert_eq!(
        leaf.attributes.raw_properties["runtime:text"],
        "private runtime value"
    );
    assert_eq!(leaf.attributes.semantic_properties["role"], "Button");
    assert!(!imported.capabilities.compose_semantics);
    assert!(!imported.capabilities.screenshots);
    assert!(imported.compose.is_none());
    let payloads = read_zip(&output);
    let value = aps_json::parse_slice_with_extended_depth(
        &payloads[SNAPSHOT],
        hierarchy_json_limits(entry_limit(SNAPSHOT)),
    )
    .unwrap();
    let attrs = &value["windows"][1]["root"]["attributes"];
    assert!(attrs.get("measuredWidth").is_none());
    assert!(attrs.get("clipChildren").is_none());
    assert!(value["windows"][1]["root"].get("resourceName").is_none());
    assert_eq!(value["capabilities"]["composeSemantics"], false);
}

#[test]
fn imported_roundtrip_retains_exact_payloads_even_after_input_is_deleted() {
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let cancel = CancellationToken::new();
    let mut value = snapshot();
    // Evidence beyond the display map's 8 KiB value limit must survive export.
    value["windows"][0]["root"]["attributes"]["rawProperties"]["large"] = "x".repeat(20_000).into();
    value["display"]["vendorRotation"] = (-90).into();
    let mut archive = typed(&value);
    archive.export_payload = ExportPayload::snapshot(&value).unwrap();
    let compose = serde_json::json!({"schemaVersion": 1, "packageName": "example.test", "capturedAtEpochMillis": -42,
        "privacy": "FULL_FIDELITY", "frame": {"frameId": "captured-frame", "generation": 37, "mode": "FULL",
            "completeness": "PARTIAL", "capabilities": [{"capability": "PARAMETERS", "availability": "UNAVAILABLE"}],
            "coverage": [{"nodeId": -8, "field": "PARAMETERS", "state": "TRUNCATED", "recursionDepth": 2, "loadedElements": 1, "totalElements": 3}],
            "truncations": [{"reason": "BYTE_LIMIT", "nodeId": -8}],
            "omissions": [{"reason": "UNSUPPORTED", "field": "modifiers"}],
            "roots": [{"viewId": -12, "nodes": [{"id": -8, "anchorHash": -9, "name": "Feed", "flags": ["SYSTEM"],
                "source": {"packageHash": -3, "fileName": "Feed.kt", "lineNumber": 12, "offset": 5}}]}],
            "details": {"-8": {"parameters": [{"name": "state", "type": "List", "truncated": true,
                "elements": [{"name": "item", "type": "String", "value": "secret", "truncated": false}]}]}},
            "stateReads": {"-9": [{"recompositionNumber": -1, "reads": [{"invalidated": true,
                "value": {"name": "state", "value": "-4", "type": "Int"},
                "stackTrace": [{"fileName": "Feed.kt", "lineNumber": 10}, {"fileName": "Other.kt", "lineNumber": 90}]}]}]}}});
    archive.compose = Some(parse_compose(&compose, "example.test", -42).unwrap());
    archive
        .export_payload
        .entries
        .insert(COMPOSE.into(), encode(&compose, COMPOSE).unwrap().into());
    let screenshot = png();
    archive.screenshot = Some(ScreenshotEvidence {
        sha256: digest(&screenshot, &cancel).unwrap(),
        size_bytes: screenshot.len() as u64,
    });
    archive.screenshot_png = Some(screenshot.clone());
    for (name, bytes) in [
        ("raw/visible-window-views.zip", b"raw zip".as_slice()),
        ("raw/visible-window-views.txt", b"raw properties"),
        ("report/analysis-report.json", b"{\"captured\":true}"),
        ("report/ai-analysis-report.json", b"{\"report\":[]}"),
        ("timeline/history.json", b"[]"),
        ("vendor/optional.bin", b"optional"),
    ] {
        archive
            .export_payload
            .entries
            .insert(name.into(), bytes.into());
    }
    let original = temp.path().join("input.zip");
    archive.export_archive(&original, &cancel).unwrap();
    let captured = read_zip(&original);
    let imported = InspectorArchive::open(&original, &cancel).unwrap();
    fs::remove_file(original).unwrap();
    let output = temp.path().join("output.zip");
    assert!(
        imported
            .export_archive(&output, &cancel)
            .unwrap()
            .retained_payloads
    );
    let exported = read_zip(&output);
    for (name, bytes) in &captured {
        assert_eq!(&exported[name], bytes, "entry {name}");
    }
    let again = InspectorArchive::open(&output, &cancel).unwrap();
    assert_eq!(again.archive_version, 2);
    assert_eq!(again.screenshot_png.as_deref(), Some(screenshot.as_slice()));
    assert!(!again.capabilities.screenshots); // Bytes do not promote producer capabilities.
    let evidence = again.compose.unwrap();
    assert_eq!(evidence.frame_id, "captured-frame");
    assert_eq!(evidence.generation, 37);
    assert_eq!(evidence.declared_completeness, "PARTIAL");
    assert_eq!(evidence.capabilities[0].availability, "UNAVAILABLE");
    assert_eq!(evidence.truncation_count, 1);
    assert_eq!(evidence.coverage[0].total_elements, Some(3));
    assert_eq!(
        evidence.details[&-8].parameters[0].elements[0]
            .value
            .as_deref(),
        Some("secret")
    );
    assert_eq!(evidence.state_reads[&-9][0].recomposition_number, -1);
    assert_eq!(
        evidence.nodes[&-8].source.as_ref().unwrap().package_hash,
        -3
    );
}

#[test]
fn retained_payload_frame_mismatch_and_missing_evidence_fail_closed() {
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let cancel = CancellationToken::new();
    let mut archive = typed(&snapshot());
    archive.export_payload = ExportPayload::snapshot(&snapshot()).unwrap();
    archive.frame.captured_at_epoch_millis += 1;
    let output = temp.path().join("frame.zip");
    assert_eq!(
        archive.export_archive(&output, &cancel).unwrap_err(),
        InspectorError::FrameMismatch
    );
    archive.export_payload = ExportPayload::projection_only();
    assert_eq!(
        archive.export_archive(&output, &cancel).unwrap_err(),
        InspectorError::MissingEvidence
    );
    archive.export_payload = ExportPayload::default();
    archive.screenshot = Some(ScreenshotEvidence {
        sha256: SourceDigest::from_bytes([0; 32]),
        size_bytes: 1,
    });
    assert_eq!(
        archive.export_archive(&output, &cancel).unwrap_err(),
        InspectorError::MissingEvidence
    );
    assert!(!output.exists());
}

#[test]
fn rejects_limits_cancellation_bad_paths_and_leaves_existing_artifacts_untouched() {
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let archive = typed(&snapshot());
    let cancel = CancellationToken::new();
    let existing = temp.path().join("existing.zip");
    fs::write(&existing, b"user artifact").unwrap();
    assert_eq!(
        archive.export_archive(&existing, &cancel).unwrap_err(),
        InspectorError::Output
    );
    assert_eq!(fs::read(&existing).unwrap(), b"user artifact");
    for path in [
        temp.path().to_path_buf(),
        temp.path().join("missing/file.zip"),
        Path::new("").to_path_buf(),
    ] {
        assert_eq!(
            archive.export_archive(&path, &cancel).unwrap_err(),
            InspectorError::Output
        );
    }
    let output = temp.path().join("bounded.zip");
    assert_eq!(
        archive.export_with_limit(&output, &cancel, 16).unwrap_err(),
        InspectorError::Limit
    );
    assert!(!output.exists());
    assert_eq!(fs::read_dir(temp.path()).unwrap().count(), 1); // No staging leftovers.
    cancel.cancel();
    assert_eq!(
        archive.export_archive(&output, &cancel).unwrap_err(),
        InspectorError::Cancelled
    );
    assert!(!output.exists());
    let mut writer = BoundedFile {
        file: File::create(temp.path().join("writer")).unwrap(),
        cancel: &cancel,
        max_bytes: MAX_ARCHIVE,
        failure: &Cell::new(None),
    };
    assert!(writer.write_all(b"not written").is_err());
    assert_eq!(writer.file.metadata().unwrap().len(), 0);
    let mut unsafe_payload = typed(&snapshot());
    unsafe_payload
        .export_payload
        .entries
        .insert("../outside".into(), Arc::from(b"data".as_slice()));
    assert_eq!(
        unsafe_payload
            .export_archive(&output, &CancellationToken::new())
            .unwrap_err(),
        InspectorError::InvalidZip
    );
    let mut too_big = typed(&snapshot());
    too_big.screenshot = Some(ScreenshotEvidence {
        sha256: SourceDigest::from_bytes([0; 32]),
        size_bytes: entry_limit(SCREENSHOT) + 1,
    });
    too_big.screenshot_png = Some(vec![0; entry_limit(SCREENSHOT) as usize + 1]);
    assert_eq!(
        too_big
            .export_archive(&output, &CancellationToken::new())
            .unwrap_err(),
        InspectorError::Limit
    );
}

#[cfg(unix)]
#[test]
fn symlink_and_hardlink_destinations_are_never_replaced_or_followed() {
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let cancel = CancellationToken::new();
    let archive = typed(&snapshot());
    let target = temp.path().join("target.zip");
    let link = temp.path().join("symlink.zip");
    std::os::unix::fs::symlink(&target, &link).unwrap();
    assert_eq!(
        archive.export_archive(&link, &cancel).unwrap_err(),
        InspectorError::Output
    );
    assert!(!target.exists());
    fs::write(&target, b"keep").unwrap();
    fs::remove_file(&link).unwrap();
    fs::hard_link(&target, &link).unwrap();
    assert_eq!(
        archive.export_archive(&link, &cancel).unwrap_err(),
        InspectorError::Output
    );
    assert_eq!(fs::read(&target).unwrap(), b"keep");
}

#[test]
fn invalid_typed_ancestry_nonfinite_attributes_and_screenshot_integrity_are_rejected() {
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let cancel = CancellationToken::new();
    let output = temp.path().join("invalid.zip");
    let mut archive = typed(&snapshot());
    archive.windows[0].nodes[0].parent_index = Some(0);
    assert_eq!(
        archive.export_archive(&output, &cancel).unwrap_err(),
        InspectorError::InvalidSnapshot
    );
    let mut archive = typed(&snapshot());
    archive.windows[0].nodes[0].attributes.elevation = Some(f32::NAN);
    assert_eq!(
        archive.export_archive(&output, &cancel).unwrap_err(),
        InspectorError::InvalidSnapshot
    );
    let mut archive = typed(&snapshot());
    archive.screenshot = Some(ScreenshotEvidence {
        sha256: SourceDigest::from_bytes([0; 32]),
        size_bytes: 1,
    });
    archive.screenshot_png = Some(vec![1]);
    assert_eq!(
        archive.export_archive(&output, &cancel).unwrap_err(),
        InspectorError::Integrity
    );
    assert!(!output.exists());
}

#[test]
fn publication_race_and_late_cancellation_preserve_exact_destination_ownership() {
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let staged = temp.path().join("staged.zip");
    let destination = temp.path().join("destination.zip");
    fs::write(&staged, b"staged archive").unwrap();
    // A destination appearing after preflight must survive the final commit.
    fs::write(&destination, b"other writer").unwrap();
    let cancel = CancellationToken::new();
    assert_eq!(
        publish_new(&staged, &destination, &cancel),
        Err(InspectorError::Output)
    );
    assert_eq!(fs::read(&destination).unwrap(), b"other writer");
    fs::remove_file(&destination).unwrap();
    cancel.cancel();
    assert_eq!(
        publish_new(&staged, &destination, &cancel),
        Err(InspectorError::Cancelled)
    );
    assert!(!destination.exists());
    let active = CancellationToken::new();
    let failure = Cell::new(None);
    let mut writer = BoundedFile {
        file: OpenOptions::new().write(true).open(&staged).unwrap(),
        cancel: &active,
        max_bytes: MAX_ARCHIVE,
        failure: &failure,
    };
    writer.write_all(b"first").unwrap();
    active.cancel();
    assert!(writer.write_all(b"second").is_err());
    assert_eq!(failure.get(), Some(InspectorError::Cancelled));
}
