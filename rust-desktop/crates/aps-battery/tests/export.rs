//! Report projection for live history, thermal windows, capabilities and CSV.
use aps_battery::{
    BatteryCapabilities, BatteryCaptureConfig, BatteryCaptureMode, BatteryHistoryEvent,
    BatteryReport, BatteryRunObservation, SnapshotIdentity, ThermalSample, parse_snapshot,
};

fn snapshot(elapsed_ms: u64, temperature: &str) -> aps_battery::BatterySnapshot {
    parse_snapshot(
        "9,0,l,start,period-a\n9,10123,l,wl,lock,1000000,2,0,0,0\n9,10123,l,nt,100,200,300,400,5,6\n",
        "Uid u0a123: 1.25 ( cpu=0.50 )",
        &format!("level: 80\ntemperature: {temperature}\n"),
        SnapshotIdentity {
            uid: 10123,
            shared_uid: false,
            device_id: "device-a".into(),
            boot_id: Some("boot-a".into()),
            elapsed_ms,
        },
    )
    .unwrap()
}

fn history(uid: u32) -> BatteryHistoryEvent {
    BatteryHistoryEvent {
        elapsed_ms: Some(5_000),
        kind: "WAKELOCK".into(),
        active: Some(true),
        name: Some("lock".into()),
        uid: Some(uid),
        raw: format!("5000 +wake_lock lock (uid {uid})"),
        confidence: "EXACT".into(),
    }
}

fn report() -> BatteryReport {
    let before = snapshot(1_000, "300");
    let mut after = snapshot(6_000, "305");
    after.network_bytes.insert("wifi_rx".into(), Some(100));
    let mut report = BatteryReport::from_live_snapshots(
        "battery-fixture",
        "example.app",
        "TIMED",
        &[BatteryRunObservation {
            iteration: 1,
            before,
            after,
            temperatures_tenths_celsius: vec![300, 305],
            thermal_statuses: vec![0, 3],
            thermal_samples: vec![
                ThermalSample {
                    elapsed_ms: 1_000,
                    status: Some(0),
                    peak_celsius: Some(30.0),
                    throttled: false,
                },
                ThermalSample {
                    elapsed_ms: 3_000,
                    status: Some(2),
                    peak_celsius: Some(42.5),
                    throttled: true,
                },
                ThermalSample {
                    elapsed_ms: 6_000,
                    status: Some(3),
                    peak_celsius: Some(44.0),
                    throttled: true,
                },
            ],
            history: vec![history(10123), history(99_999)],
            charging_states: vec![Some(false), Some(false)],
        }],
        Some(BatteryCapabilities {
            level: "ENERGY_ENHANCED".into(),
            checkin: true,
            history: true,
            reset: true,
            energy: true,
            bugreport: true,
            missing_reasons: Vec::new(),
        }),
    )
    .unwrap();
    report.configuration = Some(BatteryCaptureConfig {
        mode: BatteryCaptureMode::Timed,
        duration_seconds: 5,
        polling_seconds: 5,
        runs: 1,
        cooldown_seconds: 0,
        launch_app: true,
    });
    report
}

#[test]
fn live_history_thermal_windows_and_capabilities_roundtrip() {
    let report = report();
    assert_eq!(report.capability_level.as_deref(), Some("ENERGY_ENHANCED"));
    assert!(report.capabilities.as_ref().unwrap().reset);
    let run = &report.runs()[0];
    assert_eq!(run.peak_temperature_celsius, Some(44.0));
    assert_eq!(run.max_thermal_status, Some(3));
    assert_eq!(run.throttled_samples, Some(2));
    let windows = run.performance_windows.as_ref().unwrap();
    assert_eq!(windows.len(), 2);
    assert_eq!(windows[0].duration_ms, Some(2_000));
    assert_eq!(windows[0].peak_temperature_celsius, Some(42.5));
    assert_eq!(windows[1].duration_ms, Some(3_000));
    assert_eq!(windows[1].throttled, Some(true));
    assert_eq!(run.history.len(), 1);
    assert_eq!(run.history[0].uid, Some(10123));

    let restored = BatteryReport::parse_json(&report.to_json(1 << 20).unwrap()).unwrap();
    assert_eq!(restored.capabilities, report.capabilities);
    assert!(restored.configuration.unwrap().launch_app);
    assert_eq!(restored.runs()[0].history.len(), 1);
    assert_eq!(
        restored.runs()[0]
            .performance_windows
            .as_ref()
            .unwrap()
            .len(),
        2
    );
}

#[test]
fn csv_export_includes_resources_network_thermal_and_unknowns() {
    let report = report();
    let csv = String::from_utf8(report.to_csv(1 << 20).unwrap()).unwrap();
    assert!(csv.starts_with("schema_version,run,resource_type,name,"));
    assert!(csv.contains("1,1,wakelock,\"lock\","), "{csv}");
    assert!(csv.contains(",UID_COUNTER,"), "{csv}");
    assert!(csv.contains("1,1,network,total,"), "{csv}");
    assert!(csv.contains("1,1,thermal,peak_temperature_c,"), "{csv}");
    assert!(csv.contains("1,1,thermal,status,"), "{csv}");
    assert!(csv.contains("1,1,energy,"), "{csv}");
    assert!(csv.lines().count() > 4);
}
