//! CPU-on runs own a separate trace per measured launch. No trace is shared
//! with warm/hot preparation or matched by a reused numeric PID.
use super::*;
use aps_perfetto::recording::{self, RecordingRequest};

fn config_body(package: &str) -> Result<String, String> {
    // Match the bounded evidence-query contract (also within traced_perf's
    // target_cmdline limit). Never truncate or broaden the target filter.
    if !valid_package(package) || package.len() > 255 {
        return Err(
            "The package cannot be represented by an exact Perfetto sampling scope.".into(),
        );
    }
    Ok(format!(
        r#"
buffers {{ size_kb: 16384 fill_policy: RING_BUFFER }}
buffers {{ size_kb: 8192 fill_policy: RING_BUFFER }}
data_sources {{ config {{ name: "linux.ftrace" ftrace_config {{
    ftrace_events: "sched/sched_switch" ftrace_events: "sched/sched_waking"
    ftrace_events: "sched/sched_process_exit" ftrace_events: "sched/sched_process_free"
    ftrace_events: "task/task_newtask" ftrace_events: "task/task_rename"
    atrace_categories: "am" atrace_categories: "wm"
    atrace_categories: "gfx" atrace_categories: "view" atrace_apps: "{package}"
}} }} }}
data_sources {{ config {{ name: "linux.process_stats" process_stats_config {{
    scan_all_processes_on_start: true record_thread_names: true
}} }} }}
data_sources {{ config {{ name: "android.packages_list" packages_list_config {{ package_name_filter: "{package}" }} }} }}
data_sources {{ config {{ name: "android.surfaceflinger.frametimeline" }} }}
data_sources {{ config {{ name: "linux.perf" target_buffer: 1 perf_event_config {{
    timebase {{ frequency: 100 }}
    ring_buffer_read_period_ms: 500
    ring_buffer_pages: 512
    remote_descriptor_timeout_ms: 1500
    callstack_sampling {{ scope {{ target_cmdline: "{package}" }} }}
}} }} }}
"#
    ))
}

pub(super) fn preflight(runner: &AndroidRunner<'_>) -> Result<(), String> {
    config_body(&runner.target.package_name)?;
    let api = runner.shell(&["getprop", "ro.build.version.sdk"], COMMAND_TIMEOUT)?;
    if !api.trim().parse::<u32>().is_ok_and(|api| api >= 30) {
        return Err("Startup CPU capture requires Android 11+ service-owned trace output. No startup was initiated.".into());
    }
    runner.check()?;
    let result = recording::check_action_recording_service(
        &runner.client.location().executable,
        &runner.target.serial,
        runner.deadline.saturating_duration_since(Instant::now()),
        runner.cancel,
    );
    runner.check()?;
    result.map_err(|error| error.to_string())
}

pub(super) fn capture<T>(
    runner: &AndroidRunner<'_>,
    root: &Path,
    trace_destination: &Path,
    action: impl FnOnce() -> Result<T, String>,
) -> Result<(T, bool), String> {
    let body = config_body(&runner.target.package_name)?;
    // Sibling, NOT child, of the temporary experiment. Any recorder recovery
    // receipts for an unconfirmed stop survive a failed experiment's Drop.
    let request = RecordingRequest {
        adb_executable: &runner.client.location().executable,
        serial: &runner.target.serial,
        output_root: root,
        config_body: &body,
        duration: Duration::from_secs(12),
    };
    let mut action = Some(action);
    let mut measured = None;
    let result = recording::record_around_action(&request, runner.cancel, &mut || {
        runner.check()?;
        let value = action
            .take()
            .ok_or("Measured startup action must not be repeated")?()?;
        measured = Some(value);
        Ok(())
    });
    // Unknown cleanup always carries the exact paths, including on cancel.
    if let Err(error) = &result
        && (!error.stop_confirmed || error.preserved_paths.is_some())
    {
        return Err(error.to_string());
    }
    runner.check()?;
    match (measured, result) {
        (Some(measured), Ok(recording)) => {
            // The destination is generated once within this private workspace;
            // hard_link refuses existing destinations and shares the same inode.
            std::fs::hard_link(&recording.paths.local_trace, trace_destination).map_err(|e| {
                format!(
                    "Cannot retain the verified startup CPU trace ({:?}).",
                    e.kind()
                )
            })?;
            Ok((measured, true))
        }
        (Some(measured), Err(_)) => Ok((measured, false)),
        (None, Err(error)) => Err(error.to_string()),
        (None, Ok(_)) => Err("The recorder returned without measuring a startup.".into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn samples_are_scoped_to_the_exact_new_process_not_an_old_pid_or_wildcard() {
        let body = config_body("dev.sample").unwrap();
        assert!(body.contains("target_cmdline: \"dev.sample\""));
        assert!(body.contains("atrace_apps: \"dev.sample\""));
        assert!(body.contains("android.surfaceflinger.frametimeline"));
        assert!(body.contains("sched/sched_waking"));
        assert!(body.contains("ring_buffer_read_period_ms: 500"));
        assert!(body.contains("ring_buffer_pages: 512"));
        assert!(body.contains("remote_descriptor_timeout_ms: 1500"));
        assert!(!body.contains("target_pid"));
        for invalid in ["", "other.*", "a\" b", "a\n", &"a".repeat(256)] {
            assert!(config_body(invalid).is_err());
        }
        assert!(!StartupCaptureConfig::default().cpu_hotspots);
    }
}
