use aps_gpu::{
    AgiCapability, AgiLaunchMode, AgiToolchain, AgiToolchainError, GpuArtifact, GpuError,
};
use aps_platform::CancellationToken;

#[test]
fn cancelled_import_and_probe_never_publish_partial_results() {
    let cancel = CancellationToken::new();
    cancel.cancel();
    assert!(matches!(
        GpuArtifact::import(std::path::Path::new("/missing"), &cancel),
        Err(GpuError::Cancelled)
    ));
    assert!(matches!(
        AgiToolchain::probe(std::path::Path::new("/missing"), &cancel),
        Err(AgiToolchainError::Cancelled)
    ));
}

#[test]
fn unsupported_launch_is_rejected_without_starting_a_process() {
    let capability = AgiCapability {
        executable: "/missing/agi".into(),
        version: None,
        launch_mode: AgiLaunchMode::Unsupported,
        artifact_open_supported: false,
        stable_arguments: Vec::new(),
        warnings: Vec::new(),
    };
    assert!(matches!(
        AgiToolchain::launch(&capability),
        Err(AgiToolchainError::InvalidExecutable)
    ));
}

#[cfg(unix)]
mod unix {
    use super::*;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT: AtomicU64 = AtomicU64::new(0);
    struct Executable(PathBuf);
    impl Executable {
        fn new(script: &str) -> Self {
            use std::os::unix::fs::PermissionsExt;
            let path = std::env::temp_dir().join(format!(
                "aps-gpu-probe-safety-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::write(&path, script).unwrap();
            std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o700)).unwrap();
            Self(path)
        }
    }
    impl Drop for Executable {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    #[test]
    fn help_words_cannot_claim_verified_cli_or_artifact_open_contract() {
        let executable = Executable::new(
            "#!/bin/sh\ncase \"$1\" in\n--version) printf 'AGI fixture\\n';;\n--help) printf 'open artifact using undocumented words\\n';;\nesac\n",
        );
        let capability = AgiToolchain::probe(&executable.0, &CancellationToken::new()).unwrap();
        assert_eq!(capability.launch_mode, AgiLaunchMode::GuiOnly);
        assert!(!capability.artifact_open_supported);
        assert!(capability.stable_arguments.is_empty());
    }

    #[test]
    fn unsuccessful_version_probe_does_not_promote_stdout_to_a_capability() {
        let executable =
            Executable::new("#!/bin/sh\nprintf 'AGI misleading open artifact\\n'\nexit 1\n");
        let capability = AgiToolchain::probe(&executable.0, &CancellationToken::new()).unwrap();
        assert_eq!(capability.launch_mode, AgiLaunchMode::Unsupported);
        assert!(capability.version.is_none());
        assert!(!capability.artifact_open_supported);
    }

    #[test]
    fn cancellation_while_probing_is_not_swallowed_as_gui_only() {
        let executable = Executable::new("#!/bin/sh\nsleep 10\n");
        let cancel = CancellationToken::new();
        let trigger = cancel.clone();
        let thread = std::thread::spawn(move || {
            std::thread::sleep(std::time::Duration::from_millis(30));
            trigger.cancel();
        });
        assert!(matches!(
            AgiToolchain::probe(&executable.0, &cancel),
            Err(AgiToolchainError::Cancelled)
        ));
        thread.join().unwrap();
    }

    #[test]
    fn gui_launch_forwards_no_artifact_or_automation_arguments() {
        let executable = Executable::new("#!/bin/sh\nexit 0\n");
        let marker = executable.0.with_extension("launch-result");
        std::fs::write(
            &executable.0,
            format!("#!/bin/sh\nprintf '%s' \"$#\" > '{}'\n", marker.display()),
        )
        .unwrap();
        let capability = AgiCapability {
            executable: executable.0.clone(),
            version: Some("fixture".into()),
            launch_mode: AgiLaunchMode::GuiOnly,
            artifact_open_supported: true,
            stable_arguments: vec!["must-not-be-forwarded".into()],
            warnings: Vec::new(),
        };
        AgiToolchain::launch(&capability).unwrap();
        for _ in 0..100 {
            if std::fs::read(&marker).is_ok_and(|value| value == b"0") {
                std::fs::remove_file(marker).unwrap();
                return;
            }
            std::thread::sleep(std::time::Duration::from_millis(10));
        }
        panic!("GUI fixture did not record zero arguments");
    }
}
