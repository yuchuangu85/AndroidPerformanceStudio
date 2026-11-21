use super::*;

#[test]
fn accepts_one_positive_pid_without_guessing_on_invalid_or_ambiguous_output() {
    assert_eq!(
        parse_pid(b"1234\n", b"", "example.foreground").unwrap(),
        1234
    );
    assert_eq!(
        parse_pid(b" 1234\t1234\n", b"", "example.foreground").unwrap(),
        1234
    );
    for invalid in [
        "",
        "1234 5678",
        "0",
        "-1",
        "2147483648",
        "unknown",
        "1234 oops",
    ] {
        assert!(
            parse_pid(invalid.as_bytes(), b"", "example.foreground").is_err(),
            "{invalid}"
        );
    }
    assert!(parse_pid(&[0xff], b"", "example.foreground").is_err());
}

const PROFILE_ACTIVITIES: &str = "\
    * Hist #0: ActivityRecord{background u0 example.foreground/.Main t1}\n\
      app=ProcessRecord{a 1000:example.foreground/u0a123}\n\
    * Hist #0: ActivityRecord{focused u10 example.foreground/.Main t2}\n\
      app=ProcessRecord{b 1234:example.foreground/u10a123}\n\
    mFocusedApp=ActivityRecord{focused u10 example.foreground/.Main t2}\n";

#[test]
fn multiple_profiles_use_the_focused_activity_process_not_the_first_pid() {
    assert_eq!(
        parse_pid(
            b"1000 1234",
            PROFILE_ACTIVITIES.as_bytes(),
            "example.foreground"
        )
        .unwrap(),
        1234
    );
    assert!(
        parse_pid(
            b"1000 5678",
            PROFILE_ACTIVITIES.as_bytes(),
            "example.foreground"
        )
        .is_err()
    );
    assert!(
        parse_pid(
            b"1000 1234",
            PROFILE_ACTIVITIES
                .replace(
                    "mFocusedApp=ActivityRecord{focused",
                    "mFocusedApp=ActivityRecord{missing"
                )
                .as_bytes(),
            "example.foreground"
        )
        .is_err()
    );
    // Never use a remaining background-profile process when the focused PID
    // is absent from the helper-compatible candidates.
    assert!(parse_pid(b"1000", PROFILE_ACTIVITIES.as_bytes(), "example.foreground").is_err());
    assert_eq!(
        focused_activity_pid(
            PROFILE_ACTIVITIES
                .replace("mFocusedApp", "topResumedActivity")
                .replace("app=ProcessRecord", "mApp=ProcessRecord")
                .as_bytes(),
            "example.foreground"
        )
        .unwrap(),
        Some(1234)
    );
}

#[test]
fn a_focused_activity_without_an_owner_never_uses_a_background_profile() {
    for owner in [
        "app=null",
        "mApp=null",
        "app=ProcessRecord{b invalid}",
        "",
        "app=ProcessRecord{b 0:example.foreground/u10a123}",
    ] {
        let activities = PROFILE_ACTIVITIES.replace(
            "app=ProcessRecord{b 1234:example.foreground/u10a123}",
            owner,
        );
        assert!(
            parse_pid(b"1000", activities.as_bytes(), "example.foreground").is_err(),
            "{owner}"
        );
    }
}

#[test]
fn foreground_changes_and_restarts_require_new_authorization() {
    let target = Target {
        package: "example.foreground".into(),
        pid: 1234,
    };
    assert!(target.validate("example.foreground", 1234).is_ok());
    assert!(target.validate("example.background", 1234).is_err());
    assert!(target.validate("example.foreground", 5678).is_err());
}

#[cfg(unix)]
mod discovery {
    use super::*;
    use std::{
        fs,
        os::unix::fs::PermissionsExt,
        path::PathBuf,
        sync::atomic::{AtomicU64, Ordering},
    };

    static NEXT: AtomicU64 = AtomicU64::new(0);

    struct Fixture(PathBuf);

    impl Fixture {
        fn new() -> Self {
            let directory = std::env::temp_dir().join(format!(
                "aps-compose-foreground-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&directory).unwrap();
            fs::write(
                directory.join("activity"),
                "mFocusedApp=ActivityRecord{abc u0 example.foreground/.Main}\n",
            )
            .unwrap();
            fs::write(directory.join("pids"), "1234\n").unwrap();
            let executable = directory.join("adb");
            fs::write(
                &executable,
                r#"#!/bin/sh
cd "$(dirname "$0")" || exit 20
printf '%s\n' "$*" >> commands
[ "$1" = '-s' ] && [ "$2" = 'test-serial' ] || exit 21
shift 2
case "$*" in
 'shell dumpsys activity activities')
   if [ -f observed ] && [ -f activity-after ]; then cat activity-after; else cat activity; fi
   touch observed ;;
 'shell pidof example.foreground') cat pids ;;
 *) exit 22 ;;
esac
"#,
            )
            .unwrap();
            fs::set_permissions(&executable, fs::Permissions::from_mode(0o700)).unwrap();
            Self(directory)
        }

        fn resolve(&self, cancel: &CancellationToken) -> Result<Target, String> {
            Target::resolve(&self.0.join("adb"), "test-serial", cancel)
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn resolves_foreground_without_a_selected_process_and_only_reads_device_state() {
        let fixture = Fixture::new();
        let target = fixture.resolve(&CancellationToken::new()).unwrap();
        assert_eq!(target.package, "example.foreground");
        assert_eq!(target.pid, 1234);
        assert_eq!(
            fs::read_to_string(fixture.0.join("commands")).unwrap(),
            "-s test-serial shell dumpsys activity activities\n-s test-serial shell pidof example.foreground\n-s test-serial shell dumpsys activity activities\n"
        );
    }

    #[test]
    fn resolves_the_foreground_profile_without_manual_pid_selection() {
        let fixture = Fixture::new();
        fs::write(fixture.0.join("activity"), PROFILE_ACTIVITIES).unwrap();
        fs::write(fixture.0.join("pids"), "1000 1234").unwrap();
        let target = fixture.resolve(&CancellationToken::new()).unwrap();
        assert_eq!(target.pid, 1234);
    }

    #[test]
    fn rejects_missing_foreground_pid_and_foreground_switches() {
        let missing = Fixture::new();
        fs::write(missing.0.join("activity"), "mFocusedApp=null\n").unwrap();
        assert!(
            missing
                .resolve(&CancellationToken::new())
                .unwrap_err()
                .contains("Cannot determine foreground app")
        );

        let stopped = Fixture::new();
        fs::write(stopped.0.join("pids"), "").unwrap();
        assert!(stopped.resolve(&CancellationToken::new()).is_err());

        let changed = Fixture::new();
        fs::write(
            changed.0.join("activity-after"),
            "mFocusedApp=ActivityRecord{def u0 example.other/.Main}\n",
        )
        .unwrap();
        assert!(
            changed
                .resolve(&CancellationToken::new())
                .unwrap_err()
                .contains("Foreground app changed")
        );
    }

    #[test]
    fn rejects_a_profile_switch_even_when_the_package_name_stays_the_same() {
        let fixture = Fixture::new();
        fs::write(fixture.0.join("activity"), PROFILE_ACTIVITIES).unwrap();
        fs::write(fixture.0.join("pids"), "1000 1234").unwrap();
        fs::write(
            fixture.0.join("activity-after"),
            PROFILE_ACTIVITIES.replace(
                "mFocusedApp=ActivityRecord{focused u10",
                "mFocusedApp=ActivityRecord{background u0",
            ),
        )
        .unwrap();
        assert!(
            fixture
                .resolve(&CancellationToken::new())
                .unwrap_err()
                .contains("Foreground app changed")
        );
    }

    #[test]
    fn fails_closed_on_invalid_device_cancel_and_adb_failure() {
        let fixture = Fixture::new();
        assert!(
            Target::resolve(&fixture.0.join("adb"), "-unsafe", &CancellationToken::new()).is_err()
        );
        assert!(!fixture.0.join("commands").exists());
        let cancel = CancellationToken::new();
        cancel.cancel();
        assert!(fixture.resolve(&cancel).is_err());
        assert!(!fixture.0.join("commands").exists());
        fs::write(fixture.0.join("adb"), "#!/bin/sh\nexit 42\n").unwrap();
        assert!(
            fixture
                .resolve(&CancellationToken::new())
                .unwrap_err()
                .contains("exit Some(42)")
        );
    }
}
