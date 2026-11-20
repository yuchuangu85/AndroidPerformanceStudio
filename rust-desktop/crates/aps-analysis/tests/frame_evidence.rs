use aps_analysis::frame::{FrameReport, FrameScope};

const FIXTURE: &[u8] = br#"{"schemaVersion":3,"frames":[{"frameId":9007199254740993,"source":"FRAME_METRICS","stagesNs":{"Input":100,"Draw":101}}]}"#;

/// File identity is SHA-256("frame-" + source SHA-256 hex + NUL + compact
/// recursively key-sorted UTF-8 JSON). This golden must match with either
/// serde_json map backend; nested stages intentionally arrive out of key order.
#[test]
fn file_selection_has_a_backend_independent_canonical_id() {
    let item = FrameReport::from_bytes(FIXTURE)
        .unwrap()
        .analysis_evidence(FrameScope::Selected(9_007_199_254_740_993))
        .unwrap();
    assert_eq!(item.payload["frames"][0]["frameId"], "9007199254740993");
    assert_eq!(item.payload["analysisPolicyVersion"], "aps-frame-v2");
    assert_eq!(
        item.id,
        "frame-selection:5b25eb7517e062083cfc16e80fddbc2b5cf215859863c8debd262f4be0941cd5"
    );
}

#[test]
fn live_selection_retains_legacy_wire_order_and_numeric_id() {
    // Previously approved GUI evidence is session-bound. Do not sort it or
    // retrofit the report-only policy and wire types.
    let report = FrameReport::from_bytes(FIXTURE).unwrap();
    let live = aps_analysis::frame::project(
        report.analysis(),
        FrameScope::Selected(9_007_199_254_740_993),
    )
    .unwrap();
    let old_payload: serde_json::Value = serde_json::from_str(
        r#"{
        "timeUnit":"nanoseconds", "totalFrames":1, "deadlineMissFrames":0,
        "deadlineUnknownFrames":1, "platformJankFrames":0, "platformUnknownFrames":1,
        "p95DurationNs":null, "includedFrames":1, "omittedFrames":0, "frames":[{
            "frameId":9007199254740993, "timelineStatus":"UNKNOWN",
            "reportedStageNs":{"Input":100,"Draw":101}, "appTraceOverlaps":[],
            "durationNs":null, "expectedDurationNs":null, "deadlineVerdict":"UNKNOWN",
            "platformJank":null, "droppedBeforeSample":0, "source":"FRAME_METRICS"
        }]
    }"#,
    )
    .unwrap();
    let bytes = serde_json::to_vec(&old_payload).unwrap();
    assert_eq!(serde_json::to_vec(&live.payload).unwrap(), bytes);
    let mut identity = format!("frame-{}\0", report.digest()).into_bytes();
    identity.extend_from_slice(&bytes);
    let hash = aps_contracts::SourceDigest::hash_reader(identity.as_slice(), 16384).unwrap();
    assert_eq!(live.id, format!("frame-selection:{hash}"));
}
