//! Deferred filesystem-only lookup fixtures, not actual ADB or device probes.
use aps_adb::{AdbError, AdbLocationSource, AdbLocator};
use aps_platform::TempWorkspace;
use std::collections::BTreeMap;
use std::ffi::OsString;
use std::fs;
use std::path::{Path, PathBuf};

fn fake_adb(root: &Path, name: &str) -> PathBuf {
    let path = root.join(if cfg!(windows) {
        format!("{name}.exe")
    } else {
        name.into()
    });
    fs::write(&path, "not invoked").unwrap();
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).unwrap();
    }
    path
}

#[test]
fn invalid_explicit_path_does_not_silently_use_environment() {
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let executable = fake_adb(temp.path(), "adb");
    let environment = BTreeMap::from([(OsString::from("ADB_PATH"), executable.into_os_string())]);
    let locator = AdbLocator::with_environment(environment, None);
    assert!(matches!(
        locator.locate(Some(&temp.path().join("missing"))),
        Err(AdbError::ExplicitExecutableInvalid)
    ));
}

#[test]
fn explicit_and_adb_path_precedence_are_deterministic() {
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let first = fake_adb(temp.path(), "first");
    let second = fake_adb(temp.path(), "second");
    let environment = BTreeMap::from([
        (OsString::from("ADB_PATH"), first.clone().into_os_string()),
        (OsString::from("ADB"), second.clone().into_os_string()),
    ]);
    let locator = AdbLocator::with_environment(environment, None);
    let found = locator.locate(None).unwrap();
    assert_eq!(found.executable, first.canonicalize().unwrap());
    assert_eq!(found.source, AdbLocationSource::AdbEnvironment);
    assert_eq!(
        locator.locate(Some(&second)).unwrap().source,
        AdbLocationSource::ExplicitExecutable
    );
}

#[test]
fn empty_environment_does_not_probe_current_directory() {
    let locator = AdbLocator::with_environment(BTreeMap::new(), None);
    assert!(matches!(locator.locate(None), Err(AdbError::NotFound)));
}
