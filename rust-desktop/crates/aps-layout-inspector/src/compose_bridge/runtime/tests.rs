use super::*;
use std::{
    fs,
    sync::atomic::{AtomicU64, Ordering},
};

static NEXT: AtomicU64 = AtomicU64::new(0);

struct Fixture(PathBuf);

impl Fixture {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!(
            "aps compose runtime {} {}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&root).unwrap();
        Self(root)
    }

    fn file(&self, path: &str, executable: bool) -> PathBuf {
        let path = self.0.join(path);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, "fixture").unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            fs::set_permissions(
                &path,
                fs::Permissions::from_mode(if executable { 0o700 } else { 0o600 }),
            )
            .unwrap();
        }
        path
    }

    fn packaged(&self) -> (PathBuf, PathBuf) {
        let helper = self.file(helper_relative_path(), true);
        self.file("compose-inspection-host/lib/helper.jar", false);
        self.file(&format!("java-runtime/{}", java_relative_path()), true);
        self.file("compose-agent-bundle/manifest.properties", false);
        (helper, self.0.join("compose-agent-bundle"))
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[test]
fn packaged_runtime_works_without_a_checkout_or_environment_overrides() {
    let fixture = Fixture::new();
    let (helper, bundle) = fixture.packaged();
    let runtime = Runtime::resolve(&fixture.0, None, None, None).unwrap();
    assert_eq!(runtime.helper, helper);
    assert_eq!(runtime.bundle, bundle);
}

#[test]
fn packaged_java_is_resolved_without_path_or_java_home() {
    for nested in [false, true] {
        let fixture = Fixture::new();
        fixture.packaged();
        let home = if nested {
            "java-runtime/Contents/Home"
        } else {
            "java-runtime"
        };
        if nested {
            fs::remove_dir_all(fixture.0.join("java-runtime")).unwrap();
        }
        let executable = if cfg!(windows) { "java.exe" } else { "java" };
        fixture.file(&format!("{home}/bin/{executable}"), true);
        let runtime = Runtime::resolve(&fixture.0, None, None, None).unwrap();
        assert_eq!(runtime.java_home, Some(fixture.0.join(home)));
    }
}

#[test]
fn explicit_paths_override_packaged_paths_but_invalid_overrides_do_not_fall_back() {
    let fixture = Fixture::new();
    fixture.packaged();
    let helper = fixture.file("custom/bin/helper", true);
    fixture.file("custom/agent/manifest.properties", false);
    let bundle = fixture.0.join("custom/agent");
    let runtime =
        Runtime::resolve(&fixture.0, None, Some(helper.clone()), Some(bundle.clone())).unwrap();
    assert_eq!(runtime.helper, helper);
    assert_eq!(runtime.bundle, bundle);
    assert!(Runtime::resolve(&fixture.0, None, Some(fixture.0.join("missing")), None).is_err());
    assert!(Runtime::resolve(&fixture.0, None, None, Some(fixture.0.join("missing"))).is_err());
}

#[test]
fn missing_or_partial_resources_fail_with_actionable_errors() {
    let fixture = Fixture::new();
    assert!(
        Runtime::resolve(&fixture.0, None, None, None)
            .err()
            .unwrap()
            .contains("Compose helper missing")
    );
    fixture.file(helper_relative_path(), true);
    assert!(
        Runtime::resolve(&fixture.0, None, None, None)
            .err()
            .unwrap()
            .contains("Compose agent bundle missing")
    );
    fixture.file("compose-agent-bundle/manifest.properties", false);
    fs::create_dir(fixture.0.join("java-runtime")).unwrap();
    assert!(
        Runtime::resolve(&fixture.0, None, None, None)
            .err()
            .unwrap()
            .contains("Java runtime is incomplete")
    );
}

#[cfg(unix)]
#[test]
fn non_executable_helper_is_not_reported_as_ready() {
    let fixture = Fixture::new();
    fixture.packaged();
    fixture.file(helper_relative_path(), false);
    assert!(Runtime::resolve(&fixture.0, None, None, None).is_err());
}

#[test]
fn development_checkout_is_only_used_when_explicitly_supplied() {
    let fixture = Fixture::new();
    let helper = fixture.file("desktop-viewer/layout-inspector/compose-inspection-host/build/install/aps-compose-inspection/bin/aps-compose-inspection", true);
    fixture.file("desktop-viewer/layout-inspector/compose-agent-build/build/compose-agent-bundle/manifest.properties", false);
    let resources = fixture.0.join("resources");
    assert!(Runtime::resolve(&resources, None, None, None).is_err());
    let runtime = Runtime::resolve(&resources, Some(&fixture.0), None, None).unwrap();
    assert_eq!(runtime.helper, helper);
    fixture.packaged();
    let runtime = Runtime::resolve(&fixture.0, Some(&fixture.0), None, None).unwrap();
    assert_eq!(runtime.helper, fixture.0.join(helper_relative_path()));
}

#[test]
fn packaged_command_uses_absolute_java_and_a_single_classpath_argument() {
    let fixture = Fixture::new();
    fixture.packaged();
    let runtime = Runtime::resolve(&fixture.0, None, None, None).unwrap();
    let request = runtime.command();
    assert_eq!(
        request.program,
        fixture.0.join("java-runtime").join(java_relative_path())
    );
    assert_eq!(
        request.arguments,
        vec![
            std::ffi::OsString::from("-cp"),
            fixture
                .0
                .join("compose-inspection-host/lib/*")
                .into_os_string(),
            std::ffi::OsString::from(MAIN_CLASS),
        ]
    );
    assert_eq!(
        request.environment.get(std::ffi::OsStr::new("JAVA_HOME")),
        Some(&fixture.0.join("java-runtime").into_os_string())
    );
}

#[test]
fn packaged_command_never_falls_back_to_system_java_or_a_partial_classpath() {
    let fixture = Fixture::new();
    fixture.packaged();
    fs::remove_dir_all(fixture.0.join("java-runtime")).unwrap();
    assert!(
        Runtime::resolve(&fixture.0, None, None, None)
            .err()
            .unwrap()
            .contains("Java runtime is missing")
    );
    fixture.file(&format!("java-runtime/{}", java_relative_path()), true);
    fs::remove_dir_all(fixture.0.join("compose-inspection-host/lib")).unwrap();
    assert!(
        Runtime::resolve(&fixture.0, None, None, None)
            .err()
            .unwrap()
            .contains("helper libraries are missing")
    );
}

#[cfg(unix)]
#[test]
fn packaged_process_runs_with_an_empty_environment_and_no_path_tools() {
    use aps_platform::{CancellationToken, ProcessRunner};

    let fixture = Fixture::new();
    fixture.packaged();
    fs::write(
        fixture.0.join("java-runtime/bin/java"),
        "#!/bin/sh\nprintf '%s\\n' \"$JAVA_HOME\" \"$@\"\n",
    )
    .unwrap();
    let runtime = Runtime::resolve(&fixture.0, None, None, None).unwrap();
    let mut request = runtime.command().arg("preflight");
    request.env_clear = true;
    request
        .environment
        .insert("PATH".into(), "/nonexistent".into());
    let result = ProcessRunner
        .run(&request, &CancellationToken::new())
        .unwrap();
    assert!(result.status.success());
    let expected = format!(
        "{}\n-cp\n{}\n{MAIN_CLASS}\npreflight\n",
        fixture.0.join("java-runtime").display(),
        fixture.0.join("compose-inspection-host/lib/*").display(),
    );
    assert_eq!(result.stdout, expected.as_bytes());
}
