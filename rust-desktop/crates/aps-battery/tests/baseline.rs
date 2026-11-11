use aps_battery::{
    BaselineIssue, BatteryCaptureConfig, BatteryCaptureMode, BatteryReport, BatteryRunObservation,
    BatterySessionStore, BatterySnapshot, DeviceState, SnapshotIdentity, compare_reports,
};
use std::collections::BTreeMap;

fn snapshot(elapsed_ms: u64, bytes: Option<u64>) -> BatterySnapshot {
    let network_bytes = [
        "mobile_rx",
        "mobile_tx",
        "wifi_rx",
        "wifi_tx",
        "bluetooth_rx",
        "bluetooth_tx",
    ]
    .into_iter()
    .map(|key| (key.into(), if key == "wifi_rx" { bytes } else { Some(0) }))
    .collect();
    BatterySnapshot {
        identity: SnapshotIdentity {
            uid: 10001,
            shared_uid: false,
            device_id: "device".into(),
            boot_id: Some("boot".into()),
            elapsed_ms,
        },
        stats_period: Some("accumulator".into()),
        device: DeviceState {
            powered: Some(false),
            ..Default::default()
        },
        timers: BTreeMap::new(),
        network_bytes,
        modeled_energy_mah: BTreeMap::new(),
        uid_records_present: true,
        warnings: Vec::new(),
    }
}

fn report(id: &str, values: &[Option<u64>]) -> BatteryReport {
    let observations = values
        .iter()
        .enumerate()
        .map(|(index, value)| BatteryRunObservation {
            iteration: index as u32 + 1,
            before: snapshot(index as u64 * 10_000, Some(0)),
            after: snapshot(index as u64 * 10_000 + 5_000, *value),
            temperatures_tenths_celsius: vec![300, 301],
            thermal_statuses: vec![0],
            charging_states: vec![Some(false), Some(false)],
        })
        .collect::<Vec<_>>();
    let mode = if values.len() == 1 {
        BatteryCaptureMode::Timed
    } else {
        BatteryCaptureMode::Repeated
    };
    let mut report =
        BatteryReport::from_live_snapshots(id, "example.app", mode.as_str(), &observations)
            .unwrap();
    report.workload_id = Some("scroll-feed-v1".into());
    report.configuration = Some(BatteryCaptureConfig {
        mode,
        duration_seconds: 5,
        polling_seconds: 5,
        runs: values.len() as u32,
        cooldown_seconds: 0,
    });
    report.stopped_early = Some(false);
    report
}

fn imported(report: &BatteryReport) -> BatteryReport {
    BatteryReport::parse_json(&report.to_json(aps_battery::MAX_INPUT_BYTES).unwrap()).unwrap()
}

fn edit(report: &BatteryReport, modify: impl FnOnce(&mut aps_json::Value)) -> BatteryReport {
    let mut value = aps_json::parse_slice(
        &report.to_json(aps_battery::MAX_INPUT_BYTES).unwrap(),
        aps_json::Limits::default(),
    )
    .unwrap();
    modify(&mut value);
    BatteryReport::parse_json(&aps_json::to_vec(&value, aps_json::Limits::default()).unwrap())
        .unwrap()
}

#[test]
fn compatible_reports_compare_real_nearest_rank_medians_and_signed_deltas() {
    let baseline = report("baseline", &[Some(10), Some(40), Some(20), Some(30)]);
    let current = report("current", &[Some(30), Some(80), Some(50), Some(40)]);
    for (baseline, current) in [
        (&baseline, &current),
        (&imported(&baseline), &imported(&current)),
    ] {
        let comparison = compare_reports(baseline, current);
        assert!(comparison.comparable, "{:?}", comparison.reasons);
        assert_eq!(comparison.baseline_network_median, Some(20));
        assert_eq!(comparison.current_network_median, Some(40));
        assert_eq!(comparison.network_bytes_change, Some(20));
        assert_eq!(comparison.network_percent_change, Some(100.));
        assert!(comparison.reasons.is_empty());
        let reverse = compare_reports(current, baseline);
        assert_eq!(reverse.network_bytes_change, Some(-20));
        assert_eq!(reverse.network_percent_change, Some(-50.));
    }
}

#[test]
fn cross_session_boot_and_accumulator_need_not_match_but_each_interval_must() {
    let baseline = report("baseline", &[Some(100)]);
    let current = edit(&report("current", &[Some(200)]), |root| {
        for edge in ["before", "after"] {
            root["runs"][0]["snapshotInterval"][edge]["bootId"] = "new-boot".into();
            root["runs"][0]["snapshotInterval"][edge]["statisticsPeriod"] = "new-period".into();
        }
    });
    assert!(compare_reports(&baseline, &current).comparable);
    for field in ["bootId", "statisticsPeriod"] {
        let changed = edit(&current, |root| {
            root["runs"][0]["snapshotInterval"]["after"][field] = "changed".into()
        });
        let comparison = compare_reports(&baseline, &changed);
        assert!(!comparison.comparable);
        assert!(
            comparison
                .reasons
                .contains(&BaselineIssue::InvalidSnapshots)
        );
        assert_eq!(comparison.network_bytes_change, None);
    }
}

#[test]
fn identity_workload_configuration_and_charging_mismatches_have_specific_reasons() {
    let baseline = report("baseline", &[Some(100)]);
    for field in ["deviceLocalId", "packageName", "attributionScope", "uid"] {
        let changed = edit(&report("current", &[Some(200)]), |root| {
            root[field] = if field == "uid" {
                10002.into()
            } else {
                "different".into()
            };
        });
        let comparison = compare_reports(&baseline, &changed);
        assert!(!comparison.comparable, "{field}");
        assert!(
            comparison
                .reasons
                .contains(&BaselineIssue::DifferentIdentity)
        );
        assert_eq!(comparison.network_percent_change, None);
    }
    let mut current = report("current", &[Some(200)]);
    current.workload_id = Some("other-workload".into());
    assert!(
        compare_reports(&baseline, &current)
            .reasons
            .contains(&BaselineIssue::DifferentWorkload)
    );
    current.workload_id = baseline.workload_id.clone();
    current.configuration.as_mut().unwrap().duration_seconds = 10;
    assert!(
        compare_reports(&baseline, &current)
            .reasons
            .contains(&BaselineIssue::DifferentConfiguration)
    );
    let charged = edit(&report("current", &[Some(200)]), |root| {
        for edge in ["before", "after"] {
            root["runs"][0]["snapshotInterval"][edge]["powered"] = true.into();
        }
    });
    assert!(
        compare_reports(&baseline, &charged)
            .reasons
            .contains(&BaselineIssue::DifferentCharging)
    );
    let changing = edit(&baseline, |root| {
        root["runs"][0]["snapshotInterval"]["after"]["powered"] = true.into()
    });
    assert!(
        compare_reports(&changing, &changing)
            .reasons
            .contains(&BaselineIssue::DifferentCharging)
    );
    let reverted = edit(&baseline, |root| {
        root["runs"][0]["snapshotInterval"]["chargingStates"] = vec![false, true, false].into()
    });
    assert!(
        compare_reports(&reverted, &reverted)
            .reasons
            .contains(&BaselineIssue::DifferentCharging)
    );
}

#[test]
fn legacy_reports_and_missing_evidence_fail_closed_without_losing_display_medians() {
    let legacy = BatteryReport::parse_json(br#"{"schemaVersion":1,"sessionId":"legacy","runs":[{"runId":"r","iteration":1,"durationMs":5000,"networkBytes":0}]}"#).unwrap();
    let comparison = compare_reports(&legacy, &report("current", &[Some(200)]));
    assert!(!comparison.comparable);
    assert_eq!(comparison.baseline_network_median, Some(0));
    assert_eq!(comparison.current_network_median, Some(200));
    assert_eq!(comparison.network_bytes_change, None);
    for reason in [
        BaselineIssue::MissingIdentity,
        BaselineIssue::MissingWorkload,
        BaselineIssue::MissingConfiguration,
        BaselineIssue::MissingCompletion,
        BaselineIssue::MissingSnapshots,
    ] {
        assert!(comparison.reasons.contains(&reason), "{reason:?}");
    }
    let baseline = report("baseline", &[Some(100)]);
    let missing_charge = edit(&baseline, |root| {
        root["runs"][0]["snapshotInterval"]["before"]["powered"] = aps_json::Value::Null
    });
    assert!(
        compare_reports(&missing_charge, &missing_charge)
            .reasons
            .contains(&BaselineIssue::MissingCharging)
    );
    let missing_network = report("missing-network", &[None]);
    let comparison = compare_reports(&baseline, &missing_network);
    assert!(!comparison.comparable);
    assert_eq!(comparison.current_network_median, None);
    assert!(comparison.reasons.contains(&BaselineIssue::MissingNetwork));
}

#[test]
fn zero_baseline_keeps_absolute_change_but_never_invents_percentage() {
    let baseline = report("baseline", &[Some(0)]);
    for (bytes, delta) in [(0, 0), (100, 100)] {
        let comparison = compare_reports(&baseline, &report("current", &[Some(bytes)]));
        assert!(comparison.comparable);
        assert_eq!(comparison.network_bytes_change, Some(delta));
        assert_eq!(comparison.network_percent_change, None);
        assert_eq!(comparison.reasons, [BaselineIssue::ZeroBaseline]);
    }
    let comparison = compare_reports(&report("baseline", &[Some(100)]), &baseline);
    assert_eq!(comparison.network_percent_change, Some(-100.));
}

#[test]
fn byte_delta_is_exact_even_above_float_integer_precision() {
    let baseline = report("baseline", &[Some(u64::MAX - 1)]);
    let current = report("current", &[Some(u64::MAX)]);
    let comparison = compare_reports(&baseline, &current);
    assert!(comparison.comparable);
    assert_eq!(comparison.baseline_network_median, Some(u64::MAX - 1));
    assert_eq!(comparison.current_network_median, Some(u64::MAX));
    assert_eq!(comparison.network_bytes_change, Some(1));
    assert!(comparison.network_percent_change.unwrap() > 0.);
    assert_eq!(
        compare_reports(&current, &baseline).network_bytes_change,
        Some(-1)
    );
}

#[test]
fn truncated_stopped_mismatched_and_reset_snapshot_evidence_is_not_comparable() {
    let baseline = report("baseline", &[Some(100)]);
    for (field, value) in [
        ("stoppedEarly", true.into()),
        ("stoppedEarly", aps_json::Value::Null),
    ] {
        let changed = edit(&baseline, |root| root[field] = value);
        assert!(!compare_reports(&baseline, &changed).comparable);
    }
    let repeated = report("repeated", &[Some(100), Some(100)]);
    let incomplete = edit(&repeated, |root| {
        root["configuration"]["requestedRuns"] = 3.into()
    });
    assert!(
        compare_reports(&repeated, &incomplete)
            .reasons
            .contains(&BaselineIssue::IncompleteRuns)
    );
    let unordered = edit(&repeated, |root| {
        root["runs"][1]["snapshotInterval"]["before"]["elapsedMs"] = 4000.into();
        root["runs"][1]["snapshotInterval"]["after"]["elapsedMs"] = 9000.into();
    });
    assert!(
        compare_reports(&repeated, &unordered)
            .reasons
            .contains(&BaselineIssue::InvalidSnapshots)
    );
}

#[test]
fn present_but_malformed_evidence_is_rejected_instead_of_becoming_absent() {
    let baseline = report("baseline", &[Some(100)]);
    for (field, value) in [
        ("configuration", false.into()),
        ("workloadId", 7.into()),
        ("stoppedEarly", "false".into()),
    ] {
        let mut root = aps_json::parse_slice(
            &baseline.to_json(aps_battery::MAX_INPUT_BYTES).unwrap(),
            aps_json::Limits::default(),
        )
        .unwrap();
        root[field] = value;
        assert!(
            BatteryReport::parse_json(
                &aps_json::to_vec(&root, aps_json::Limits::default()).unwrap()
            )
            .is_err(),
            "{field}"
        );
    }
    for field in ["requestedRuns", "pollingSeconds", "durationCeilingSeconds"] {
        let mut root = aps_json::parse_slice(
            &baseline.to_json(aps_battery::MAX_INPUT_BYTES).unwrap(),
            aps_json::Limits::default(),
        )
        .unwrap();
        root["configuration"][field] = 0.into();
        assert!(
            BatteryReport::parse_json(
                &aps_json::to_vec(&root, aps_json::Limits::default()).unwrap()
            )
            .is_err(),
            "{field}"
        );
    }
}

#[test]
fn snapshot_counters_must_match_report_and_keep_reset_or_missing_values_unknown() {
    let baseline = report("baseline", &[Some(100)]);
    for (field, value) in [
        ("elapsedMs", 1.into()),
        ("uid", 10002.into()),
        ("uidRecordsPresent", false.into()),
        ("bootId", "".into()),
    ] {
        let mut root = aps_json::parse_slice(
            &baseline.to_json(aps_battery::MAX_INPUT_BYTES).unwrap(),
            aps_json::Limits::default(),
        )
        .unwrap();
        root["runs"][0]["snapshotInterval"]["after"][field] = value;
        let parsed = BatteryReport::parse_json(
            &aps_json::to_vec(&root, aps_json::Limits::default()).unwrap(),
        );
        if field == "bootId" {
            assert!(parsed.is_err());
            continue;
        }
        let comparison = compare_reports(&baseline, &parsed.unwrap());
        assert!(!comparison.comparable, "{field}");
        assert!(
            comparison
                .reasons
                .contains(&BaselineIssue::InvalidSnapshots)
        );
    }
    for value in [aps_json::Value::Null, 99.into(), 101.into()] {
        let changed = edit(&baseline, |root| {
            root["runs"][0]["snapshotInterval"]["after"]["networkBytes"]["wifi_rx"] = value
        });
        assert!(!compare_reports(&baseline, &changed).comparable);
    }
    let reset = edit(&baseline, |root| {
        root["runs"][0]["snapshotInterval"]["before"]["networkBytes"]["wifi_rx"] = 101.into()
    });
    assert!(!compare_reports(&baseline, &reset).comparable);
}

#[test]
fn saved_reports_retain_comparison_evidence_without_acquiring_live_provenance() {
    let root = std::env::temp_dir().join(format!(
        "aps-battery-baseline-{}-{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap()
            .as_nanos()
    ));
    let store = BatterySessionStore::new(root.clone());
    let baseline = imported(&report("baseline", &[Some(100)]));
    let current = imported(&report("current", &[Some(150)]));
    let saved_baseline = store.save(&baseline, None).unwrap();
    let saved_current = store.save(&current, None).unwrap();
    let baseline = store.load(&saved_baseline.key).unwrap();
    let current = store.load(&saved_current.key).unwrap();
    assert!(!baseline.report.live_capture && !current.report.live_capture);
    assert!(baseline.artifact.is_none() && current.artifact.is_none());
    let comparison = compare_reports(&baseline.report, &current.report);
    assert!(comparison.comparable, "{:?}", comparison.reasons);
    assert_eq!(comparison.network_percent_change, Some(50.));
    assert_eq!(store.list().unwrap().len(), 2);
    std::fs::remove_dir_all(root).unwrap();
}
