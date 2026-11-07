//! Deferred test suite. Do not run until the migration implementation is complete.
use aps_platform::{TempWorkspace, atomic_write};
use std::fs;

#[test]
fn atomic_write_replaces_complete_contents() {
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let path = temp.path().join("preferences");
    atomic_write(&path, b"before").unwrap();
    atomic_write(&path, b"after").unwrap();
    assert_eq!(fs::read(&path).unwrap(), b"after");
    assert_eq!(fs::read_dir(temp.path()).unwrap().count(), 1);
}

#[test]
fn failed_rename_preserves_destination_and_cleans_temporary() {
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let target = temp.path().join("target");
    fs::create_dir(&target).unwrap();
    fs::write(target.join("keep"), b"old").unwrap();
    assert!(atomic_write(&target, b"new").is_err());
    assert_eq!(fs::read(target.join("keep")).unwrap(), b"old");
    assert_eq!(fs::read_dir(temp.path()).unwrap().count(), 1);
}

#[test]
fn temporary_workspaces_are_exclusive_and_owned_cleanup_is_local() {
    let root = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let first = TempWorkspace::create(root.path()).unwrap();
    let second = TempWorkspace::create(root.path()).unwrap();
    assert_ne!(first.path(), second.path());
    fs::write(second.path().join("keep"), b"second").unwrap();
    let first_path = first.path().to_owned();
    drop(first);
    assert!(!first_path.exists());
    assert_eq!(fs::read(second.path().join("keep")).unwrap(), b"second");
}

#[cfg(unix)]
#[test]
fn an_application_directory_symlink_is_not_accepted() {
    use std::os::unix::fs::symlink;
    let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let link = temp.path().join("link");
    symlink(temp.path(), &link).unwrap();
    assert!(atomic_write(&link.join("no-write"), b"blocked").is_err());
    assert!(!temp.path().join("no-write").exists());
}
