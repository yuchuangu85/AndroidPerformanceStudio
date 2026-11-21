#![cfg(unix)]
//! Explicit battery device actions use a bounded argv path and clean up failures.
use aps_battery::{generate_bugreport, reset_batterystats};
use aps_platform::{CancellationToken, TempWorkspace};
use std::fs;
use std::os::unix::fs::PermissionsExt;

fn fake_adb(root: &std::path::Path, script: &str) -> std::path::PathBuf {
    let path = root.join("adb");
    fs::write(&path, script).unwrap();
    fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
    path
}

#[test]
fn reset_and_bugreport_issue_explicit_bounded_commands() {
    let workspace = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let adb = fake_adb(
        workspace.path(),
        "#!/bin/sh\ncase \"$*\" in\n*bugreport*) printf 'zipdata' > \"$4\" ;;\n*--reset*) printf 'reset' ;;\n*) exit 1 ;;\nesac\n",
    );
    let cancel = CancellationToken::new();
    reset_batterystats(&adb, "serial-1", &cancel).unwrap();
    let destination = workspace.path().join("bugreport.zip");
    let size = generate_bugreport(&adb, "serial-1", &destination, &cancel).unwrap();
    assert_eq!(size, 7);
    assert_eq!(fs::read(&destination).unwrap(), b"zipdata");
}

#[test]
fn failed_bugreport_is_removed_and_existing_destination_is_never_overwritten() {
    let workspace = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let adb = fake_adb(workspace.path(), "#!/bin/sh\nexit 1\n");
    let cancel = CancellationToken::new();
    let destination = workspace.path().join("bugreport.zip");
    assert!(generate_bugreport(&adb, "serial-1", &destination, &cancel).is_err());
    assert!(!destination.exists());

    let existing = workspace.path().join("existing.zip");
    fs::write(&existing, b"original").unwrap();
    assert!(generate_bugreport(&adb, "serial-1", &existing, &cancel).is_err());
    assert_eq!(fs::read(&existing).unwrap(), b"original");
    assert!(reset_batterystats(&adb, "serial-1", &cancel).is_err());
}
