//! Deferred pure-data tests: these never invoke a host or device ADB executable.
use aps_adb::{
    AndroidPackage, AndroidProcess, DeviceState, TargetSnapshot, parse_devices, parse_packages,
    parse_processes, parse_properties, validate_serial,
};

#[test]
fn retains_all_device_states_and_known_metadata() {
    let devices = parse_devices("* daemon started successfully\nList of devices attached\nemulator-5554 device product:sdk model:Pixel_8 device:emu transport_id:1\nR58M unauthorized usb:1-2\n192.168.0.2:5555 offline\n???????????? no permissions (user in plugdev group)\nfuture reconnecting transport_id:9\n").unwrap();
    assert_eq!(devices.len(), 5);
    assert_eq!(devices[0].state, DeviceState::Device);
    assert_eq!(devices[0].model(), "Pixel_8");
    assert_eq!(devices[1].state, DeviceState::Unauthorized);
    assert_eq!(devices[2].state, DeviceState::Offline);
    assert_eq!(devices[3].state, DeviceState::NoPermissions);
    assert_eq!(devices[4].state, DeviceState::Unknown);
    assert_eq!(devices[4].raw_state, "reconnecting");
}

#[test]
fn malformed_devices_are_not_silently_dropped() {
    assert!(parse_devices("List of devices attached\nmissing-state").is_err());
    assert!(parse_devices("serial device\nserial offline").is_err());
    assert!(parse_devices("-s device").is_err());
}

#[test]
fn model_falls_back_to_product_device_then_serial() {
    let devices = parse_devices("first device model:unknown product:Pixel_9\nsecond device model:null product:unknown device:oriole\nthird device model:unknown product:unknown device:unknown\n").unwrap();
    assert_eq!(devices[0].model(), "Pixel_9");
    assert_eq!(devices[1].model(), "oriole");
    assert_eq!(devices[2].display_name(), "third");
}

#[test]
fn serial_injection_is_rejected_without_executing_a_command() {
    for serial in [
        "",
        "-s",
        "serial shell",
        "serial;id",
        "serial\n",
        "$(id)",
        "serial'",
        "serial\\foo",
    ] {
        assert!(validate_serial(serial).is_err(), "accepted {serial:?}");
    }
    for serial in ["emulator-5554", "ABC_123", "192.168.0.2:5555", "[::1]:5555"] {
        assert!(validate_serial(serial).is_ok());
    }
}

#[test]
fn properties_are_allowlisted_and_not_a_raw_property_dump() {
    let properties = parse_properties("[ro.product.manufacturer]: [Google]\n[ro.product.model]: [Pixel 9]\n[ro.build.version.release]: [16]\n[ro.build.version.sdk]: [36]\n[ro.product.cpu.abi]: [arm64-v8a]\n[sensitive.unrelated]: [not exposed]\n").unwrap();
    assert_eq!(properties.manufacturer.as_deref(), Some("Google"));
    assert_eq!(properties.sdk_level, Some(36));
    assert!(!format!("{properties:?}").contains("not exposed"));
    assert!(parse_properties("invalid property").is_err());
}

#[test]
fn process_columns_are_explicit_and_pid_is_not_process_identity() {
    let processes =
        parse_processes("PID PPID USER NAME\n80 1 u0_a1 com.example.app:worker\n20 0 root init\n")
            .unwrap();
    assert_eq!(processes[0].pid, 20);
    assert_eq!(processes[1].name, "com.example.app:worker");
    assert!(parse_processes("USER PID NAME\nroot 20 init\n").is_err());
    assert!(parse_processes("PID PPID USER NAME\n20 0 root init\n20 0 root init\n").is_err());
}

#[test]
fn process_name_preserves_spaces_after_fixed_columns() {
    let processes = parse_processes(
        "PID PPID USER NAME\n80 1 u0_a1 com.example.app:worker helper\n20 0 root init\n",
    )
    .unwrap();
    assert_eq!(processes[1].name, "com.example.app:worker helper");
}

#[test]
fn process_rows_accept_aligned_spaces_tabs_and_crlf() {
    let processes = parse_processes(
        "  PID  PPID USER         NAME\r\n   80     1 u0_a1        com.example.app:worker helper\r\n20\t\t0\troot\tinit\r\n",
    )
    .unwrap();
    assert_eq!(processes.len(), 2);
    assert_eq!(processes[0].parent_pid, 0);
    assert_eq!(processes[1].parent_pid, 1);
    assert_eq!(processes[1].user, "u0_a1");
    assert_eq!(processes[1].name, "com.example.app:worker helper");
    assert!(parse_processes("PID PPID USER NAME\n80 invalid u0_a1 com.example.app\n").is_err());
}

#[test]
fn online_state_is_derived_from_device_state() {
    let devices =
        parse_devices("List of devices attached\nemulator-5554 device\nR58M unauthorized\n")
            .unwrap();
    assert!(devices[0].is_online());
    assert!(!devices[1].is_online());
}

#[test]
fn packages_are_prefixed_rows_only_and_sorted() {
    let packages = parse_packages(
        "package:com.example.zeta\r\npackage:com.example.alpha\r\nignored row\r\n\r\npackage:com.example.alpha\r\npackage:\r\n package: com.example.mid \r\n",
    )
    .unwrap();
    let names: Vec<_> = packages
        .iter()
        .map(|package| package.package_name.as_str())
        .collect();
    assert_eq!(
        names,
        ["com.example.alpha", "com.example.mid", "com.example.zeta"]
    );
    assert_eq!(
        packages[0],
        AndroidPackage {
            package_name: "com.example.alpha".into()
        }
    );
}

#[test]
fn non_package_rows_are_ignored_rather_than_fatal() {
    let packages = parse_packages(
        "Warning: linker: /data/app/lib\nSome daemon chatter\npackage:com.example.app\n",
    )
    .unwrap();
    assert_eq!(packages.len(), 1);
    assert!(parse_packages("").unwrap().is_empty());
    assert!(parse_packages("com.example.app\n").unwrap().is_empty());
}

#[test]
fn package_output_beyond_parser_budget_is_rejected() {
    let mut rows = String::new();
    for _ in 0..70_000 {
        rows.push_str("package:com.example.app\n");
    }
    assert!(parse_packages(&rows).is_err());
    let mut oversized = String::from("package:");
    oversized.push_str(&"a".repeat(4 * 1024 * 1024));
    assert!(parse_packages(&oversized).is_err());
}

fn target_snapshot(packages: &[&str], processes: &[(u32, u32, &str, &str)]) -> TargetSnapshot {
    TargetSnapshot {
        packages: packages
            .iter()
            .map(|package_name| AndroidPackage {
                package_name: (*package_name).to_owned(),
            })
            .collect(),
        processes: processes
            .iter()
            .map(|(pid, parent_pid, user, name)| AndroidProcess {
                pid: *pid,
                parent_pid: *parent_pid,
                user: (*user).to_owned(),
                name: (*name).to_owned(),
            })
            .collect(),
    }
}

#[test]
fn application_processes_match_exact_and_remote_process_names() {
    let snapshot = target_snapshot(
        &["com.example.app", "com.example.other"],
        &[
            (900, 1, "u0_a100", "com.example.app"),
            (950, 900, "u0_a100", "com.example.app:worker"),
            (800, 1, "u0_a200", "com.example.other:remote"),
            (1, 0, "root", "init"),
            (700, 1, "system", "com.example.application"),
            (650, 1, "u0_a300", "com.example.apps"),
        ],
    );
    let resolved = snapshot.application_processes();
    let summary: Vec<_> = resolved
        .iter()
        .map(|process| (process.pid, process.package_name.as_str()))
        .collect();
    assert_eq!(
        summary,
        [
            (800, "com.example.other"),
            (900, "com.example.app"),
            (950, "com.example.app"),
        ]
    );
    assert_eq!(resolved[2].parent_pid, 900);
    assert_eq!(resolved[2].user, "u0_a100");
    assert_eq!(resolved[2].name, "com.example.app:worker");
}

#[test]
fn application_processes_require_a_known_package_and_are_pid_ordered() {
    let empty_packages = target_snapshot(
        &[],
        &[(20, 1, "u0_a1", "com.example.app"), (10, 0, "root", "init")],
    );
    assert!(empty_packages.application_processes().is_empty());

    let mut snapshot = target_snapshot(
        &["com.example.app"],
        &[(30, 1, "u0_a1", "com.example.app:worker")],
    );
    snapshot.processes.push(AndroidProcess {
        pid: 12,
        parent_pid: 1,
        user: "u0_a1".into(),
        name: "com.example.app".into(),
    });
    let pids: Vec<_> = snapshot
        .application_processes()
        .iter()
        .map(|process| process.pid)
        .collect();
    assert_eq!(pids, [12, 30]);
}
