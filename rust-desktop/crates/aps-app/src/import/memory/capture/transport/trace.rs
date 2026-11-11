//! Process-bound heapprofd recording owned by one detached-session key. Never
//! uses root, a global tracing stop, application restart or numeric PID kills.
use super::*;
use aps_perfetto::{
    heap_profile::HeapProfileKind,
    recording::{RecordingRequest, RecordingResult, record_bounded_checked},
};

pub(in crate::import::memory) struct CapturedTrace {
    pub path: PathBuf,
    pub target: HeapCaptureTarget,
    pub boot_id: String,
    pub start_ticks: u64,
    pub sdk: u32,
    // Keep the private trace alive through queries, exports and page changes.
    // Dropping a cancelled/stale result removes only its owned local files.
    _recording: RecordingResult,
}

fn heap_config(pid: u32, kind: HeapProfileKind) -> String {
    let heaps = match kind {
        HeapProfileKind::Native => "",
        HeapProfileKind::Art => "heaps: \"com.android.art\"",
    };
    format!(
        "buffers {{ size_kb: 8192 }}\n\
         data_sources {{ config {{ name: \"android.heapprofd\" target_buffer: 0 \
         heapprofd_config {{ sampling_interval_bytes: 4096 pid: {pid} {heaps} }} }} }}\n"
    )
}

pub(in crate::import::memory) fn capture_trace(
    target: &HeapCaptureTarget,
    root: &Path,
    kind: HeapProfileKind,
    cancel: &CancellationToken,
) -> Result<CapturedTrace, String> {
    validate_target(target, root)?;
    check_cancelled(cancel)?;
    let location = AdbLocator::new().locate(None).map_err(|e| e.to_string())?;
    let executable = location.executable.clone();
    let executor = AndroidExecutor {
        client: AdbClient::new(location).with_timeout(COMMAND_TIMEOUT),
    };
    verify_descriptor(target, &executor.target_snapshot(&target.serial, cancel)?)?;
    let identity = read_identity(&executor, &executable, target, cancel)?;
    let sdk = run_checked(
        &executor,
        &request(&executable, &target.serial, COMMAND_TIMEOUT).args([
            "shell",
            "getprop",
            "ro.build.version.sdk",
        ]),
        cancel,
        "Read heapprofd API level",
    )?;
    let sdk = String::from_utf8_lossy(&sdk.stdout)
        .trim()
        .parse::<u32>()
        .unwrap_or(0);
    let minimum = if kind == HeapProfileKind::Art { 31 } else { 29 };
    if sdk < minimum {
        return Err(format!(
            "This heap profiling mode requires Android API {minimum} or newer."
        ));
    }
    let config = heap_config(target.pid, kind);
    let mut recheck = || verify_identity(&executor, &executable, target, &identity, cancel);
    let recording = record_bounded_checked(
        &RecordingRequest {
            adb_executable: &executable,
            serial: &target.serial,
            output_root: root,
            config_body: &config,
            duration: Duration::from_secs(10),
        },
        cancel,
        &mut recheck,
    )
    .map_err(|e| {
        format!(
            "{e} Heap profiling requires a debuggable/profileable target and shell profiling permission; no root or permission changes were attempted."
        )
    })?;
    // Transport completion alone is not authority to attribute data to a PID
    // which was reused, or to publish after device/target cancellation.
    verify_identity(&executor, &executable, target, &identity, cancel)?;
    check_cancelled(cancel)?;
    Ok(CapturedTrace {
        path: recording.paths.local_trace.clone(),
        target: target.clone(),
        boot_id: identity.boot_id,
        start_ticks: identity.start_ticks,
        sdk,
        _recording: recording,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn native_config_is_pid_scoped_and_leaves_bounds_to_the_owned_recorder() {
        let config = heap_config(42, HeapProfileKind::Native);
        assert!(config.contains("name: \"android.heapprofd\""));
        assert!(config.contains("sampling_interval_bytes: 4096 pid: 42"));
        assert!(!config.contains("com.android.art"));
        assert!(!config.contains("process_cmdline"));
        assert!(!config.contains("duration_ms"));
        assert!(!config.contains("max_file_size_bytes"));
    }

    #[test]
    fn art_config_selects_only_the_platform_art_sampling_heap() {
        let config = heap_config(314, HeapProfileKind::Art);
        assert!(config.contains("pid: 314 heaps: \"com.android.art\""));
        assert!(!config.contains("process_cmdline"));
        assert!(!config.contains("all_heaps"));
    }
}
