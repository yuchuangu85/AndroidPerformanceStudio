use aps_startup::{
    MetricSource, StartupEvidenceOrigin,
    report::{ORIGINAL_RUN_FIELD, looks_like_json, parse_json},
};

const EXPORTED_REPORT: &[u8] = include_bytes!("fixtures/compose-oracle/regression-current.json");

#[test]
fn shared_report_reader_preserves_existing_export_and_missing_metrics() {
    let document = aps_json::parse_slice(EXPORTED_REPORT, aps_json::Limits::default()).unwrap();
    assert!(looks_like_json(&document));
    let session = parse_json(EXPORTED_REPORT).unwrap();
    assert_eq!(session.summary().ttid.p50_ms, Some(130));
    assert_eq!(session.summary().ttfd.count, 0);
    let first = &session.runs()[0];
    assert_eq!(first.origin, StartupEvidenceOrigin::ImportedJson);
    assert_eq!(first.ttid.source, Some(MetricSource::ImportedJson));
    assert_eq!(first.ttfd.milliseconds, None);
    assert!(first.ttfd.unavailable_reason.is_some());
    let original = aps_json::parse_slice(
        first.imported_fields[ORIGINAL_RUN_FIELD].as_bytes(),
        aps_json::Limits::default(),
    )
    .unwrap();
    assert_eq!(original, document["runs"][0]);
}

#[test]
fn startup_report_hint_distinguishes_other_versioned_runs_and_does_not_validate() {
    let parse = |bytes: &[u8]| aps_json::parse_slice(bytes, aps_json::Limits::default()).unwrap();
    let battery =
        parse(br#"{"schemaVersion":1,"sessionId":"battery","runs":[{"energy":[],"timers":[]}]}"#);
    let miscellaneous = parse(br#"{"schemaVersion":1,"runs":[{"iteration":1}]}"#);
    assert!(!looks_like_json(&battery));
    assert!(!looks_like_json(&miscellaneous));

    let malformed = br#"{"schemaVersion":2,"runs":[{"requestedType":"COLD","observedType":"WARM","rawEvidence":{}}]}"#;
    assert!(looks_like_json(&parse(malformed)));
    assert!(
        parse_json(malformed)
            .err()
            .unwrap()
            .contains("schema version")
    );
    assert!(parse_json(br#"{"schemaVersion":1,"schemaVersion":1,"runs":[]}"#).is_err());
}
