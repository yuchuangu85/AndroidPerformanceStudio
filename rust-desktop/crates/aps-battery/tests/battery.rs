//! Deferred tests: do not run before complete migration implementation.
use aps_battery::{
    BatteryDelta, DeltaAvailability, ResourceKind, SnapshotIdentity, parse_device_state,
    parse_snapshot,
};

fn identity(uid: u32, boot: Option<&str>, _period: Option<&str>) -> SnapshotIdentity {
    SnapshotIdentity {
        uid,
        shared_uid: false,
        device_id: "device-a".into(),
        boot_id: boot.map(str::to_owned),
        elapsed_ms: 1000,
    }
}

fn checkin(value: u64, uid: u32) -> String {
    format!(
        "9,0,l,start,period-a\n9,{uid},l,wl,lock,{value},2,0,0,0\n9,{uid},l,nt,100,200,300,400,5,6\n"
    )
}

#[test]
fn same_boot_period_device_uid_produces_real_uid_counter_deltas() {
    let before = parse_snapshot(
        &checkin(1_000_000, 10123),
        "Uid u0a123: 1.25 ( cpu=0.50 )",
        "level: 80\ntemperature: 300\n",
        SnapshotIdentity {
            elapsed_ms: 1000,
            ..identity(10123, Some("boot-a"), Some("period-a"))
        },
    )
    .unwrap();
    let after = parse_snapshot(
        &checkin(3_000_000, 10123),
        "Uid u0a123: 2.00 ( cpu=0.75 )",
        "level: 79\ntemperature: 301\n",
        SnapshotIdentity {
            elapsed_ms: 5000,
            ..identity(10123, Some("boot-a"), Some("period-a"))
        },
    )
    .unwrap();
    let delta = BatteryDelta::between(&before, &after);
    assert_eq!(delta.availability, DeltaAvailability::Available);
    assert_eq!(delta.duration_ms, Some(4000));
    assert_eq!(delta.timers[0].kind, ResourceKind::Wakelock);
    assert_eq!(delta.timers[0].duration_ms, Some(2000));
    assert_eq!(delta.network_total_bytes, Some(0));
    assert_eq!(delta.device_level_change, Some(-1));
}

#[test]
fn missing_identity_or_stats_period_is_unknown_not_zero() {
    let before = parse_snapshot(
        &checkin(1_000_000, 10123),
        "",
        "",
        identity(10123, None, None),
    )
    .unwrap();
    let after = parse_snapshot(
        &checkin(3_000_000, 10123),
        "",
        "",
        SnapshotIdentity {
            elapsed_ms: 5000,
            ..identity(10123, None, None)
        },
    )
    .unwrap();
    let delta = BatteryDelta::between(&before, &after);
    assert_eq!(delta.availability, DeltaAvailability::UnknownIdentity);
    assert_eq!(delta.timers[0].duration_ms, None);
    assert!(
        delta
            .warnings
            .iter()
            .any(|warning| warning.contains("unavailable"))
    );
}

#[test]
fn reboot_uid_period_or_counter_reset_never_becomes_negative_usage() {
    let before = parse_snapshot(
        &checkin(3_000_000, 10123),
        "",
        "",
        identity(10123, Some("boot-a"), Some("period-a")),
    )
    .unwrap();
    let after = parse_snapshot(
        &checkin(1_000_000, 10123),
        "",
        "",
        SnapshotIdentity {
            elapsed_ms: 5000,
            ..identity(10123, Some("boot-b"), Some("period-b"))
        },
    )
    .unwrap();
    let delta = BatteryDelta::between(&before, &after);
    assert_eq!(delta.availability, DeltaAvailability::IncompatibleIdentity);
    assert!(delta.timers.iter().all(|timer| timer.duration_ms.is_none()));
}

#[test]
fn parser_retains_device_unknowns_and_shared_uid_warning() {
    let state = parse_device_state("level: 87\ntemperature: 321\nUSB powered: true\n").unwrap();
    assert_eq!(state.level_percent, Some(87));
    assert_eq!(state.powered, Some(true));
    let snapshot = parse_snapshot(
        &checkin(1_000_000, 10123),
        "",
        "",
        SnapshotIdentity {
            shared_uid: true,
            ..identity(10123, Some("boot"), Some("period-a"))
        },
    )
    .unwrap();
    assert!(
        snapshot
            .warnings
            .iter()
            .any(|warning| warning.contains("Shared UID"))
    );
}

#[test]
fn parser_rejects_duplicate_or_oversized_ambiguous_counters() {
    assert!(
        parse_snapshot(
            "9,0,l,start,p1\n9,10123,l,nt,1,2,3,4,5,6\n9,10123,l,nt,1,2,3,4,5,6\n",
            "",
            "",
            identity(10123, Some("boot"), Some("p1"))
        )
        .is_err()
    );
    assert!(
        parse_snapshot(
            "9,0,l,start,p1\n9,10123,l,wl,lock,not-a-number,2,0,0,0\n",
            "",
            "",
            identity(10123, Some("boot"), Some("p1"))
        )
        .unwrap()
        .warnings
        .iter()
        .any(|warning| warning.contains("layout"))
    );
}
