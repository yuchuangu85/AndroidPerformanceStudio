use aps_analysis::frame::{FrameReport, FrameScope};
use serde_json::{Value, json};

#[test]
fn pages_match_frozen_analysis_order_and_distinguish_missed_from_unknown() {
    // Unsorted input; first frame in analysis order is platform jank, not a
    // deadline miss. Never interpret missing measurements as measured zero.
    let bytes = br#"{"schemaVersion":3,"frames":[
        {"frameId":9007199254740993,"intendedVsyncNs":3,"source":"FRAME_METRICS","totalDurationNs":50,"expectedDurationNs":10,"expectedDurationSource":"FRAME_INTERVAL","activityName":"Private Screen"},
        {"frameId":1,"intendedVsyncNs":1,"source":"FRAME_METRICS","platformJank":true},
        {"frameId":2,"intendedVsyncNs":2,"source":"FRAME_METRICS","totalDurationNs":8,"expectedDurationNs":10,"expectedDurationSource":"FRAME_INTERVAL"}
    ]}"#;
    let report = FrameReport::from_bytes(bytes).unwrap();
    let first = report.follow_up_rows(false, 0, 2).unwrap();
    assert_eq!(first["total"], 3);
    assert_eq!(first["nextOffset"], 2);
    assert_eq!(first["items"].as_array().unwrap().len(), 2);
    assert_eq!(first["items"][0]["value"]["frameId"], "1");
    assert_eq!(first["items"][0]["value"]["deadlineVerdict"], "UNKNOWN");
    assert_eq!(first["items"][0]["value"]["platformJank"], true);
    assert_eq!(
        first["items"][0]["value"].get("durationNs"),
        Some(&Value::Null)
    );
    assert_eq!(first["items"][1]["value"]["deadlineVerdict"], "MET");

    let last = report.follow_up_rows(false, 2, 16).unwrap();
    assert_eq!(last["offset"], 2);
    assert_eq!(last.get("nextOffset"), Some(&Value::Null));
    let misses = report.follow_up_rows(true, 0, 1).unwrap();
    assert_eq!(misses["items"], last["items"]);
    assert_eq!(misses["total"], 1);
    assert_eq!(misses.get("nextOffset"), Some(&Value::Null));
    let item = &last["items"][0];
    assert_eq!(item["value"]["frameId"], "9007199254740993");
    assert_eq!(item["value"]["deadlineVerdict"], "MISSED");
    assert_eq!(item["value"]["durationNs"], 50);
    assert!(!last.to_string().contains("Private Screen"));
    let selected = report
        .analysis_evidence(FrameScope::Selected(9_007_199_254_740_993))
        .unwrap();
    assert_ne!(item["id"], selected.id);
    assert_eq!(
        item["value"]["frameId"],
        selected.payload["frames"][0]["frameId"]
    );
    let different_source = FrameReport::from_bytes(&[&bytes[..], b" "].concat()).unwrap();
    let different = different_source.follow_up_rows(true, 0, 1).unwrap();
    assert_ne!(item["id"], different["items"][0]["id"]);
}

#[test]
fn page_bounds_apply_to_matching_rows_including_an_empty_match_set() {
    let doc = json!({"schemaVersion":3,"frames":[{"frameId":1,"source":"GFXINFO"}]});
    let report = FrameReport::from_bytes(doc.to_string().as_bytes()).unwrap();
    assert_eq!(
        report.follow_up_rows(true, 0, 16).unwrap(),
        json!({"items":[],"total":0,"offset":0,"nextOffset":null})
    );
    assert_eq!(
        report.follow_up_rows(false, 1, 1).unwrap()["items"],
        json!([])
    );
    for (filter, offset, limit) in [
        (true, 1, 1),
        (false, 2, 1),
        (false, usize::MAX, 1),
        (false, 0, 0),
        (false, 0, 17),
    ] {
        assert!(report.follow_up_rows(filter, offset, limit).is_err());
    }
}
