//! Handle the macro-plan and macro-run CLI commands.
use super::{comparison_exit_code, load, options, policy, regression_count, required};
use aps_benchmark::{
    BenchmarkScenario, CompilationMode, MacrobenchmarkPlan, StartupMode, build_command,
    compare_baseline_profile, run_macrobenchmark, write_baseline_profile_json, write_json,
};
use std::path::{Path, PathBuf};
use std::sync::{Arc, atomic::AtomicBool};
use std::time::Duration;

pub(super) fn run(args: &[String], execute: bool) -> Result<i32, String> {
    let opts = options(
        args,
        &[
            "--project",
            "--task",
            "--filter",
            "--compilation-mode",
            "--startup-mode",
            "--scenario",
            "--warmups",
            "--iterations",
            "--trace-dir",
            "--timeout-seconds",
            "--result",
            "--baseline",
            "--report-json",
            "--threshold-percent",
            "--device",
        ],
    )?;
    let mut plan = MacrobenchmarkPlan {
        project_root: std::path::absolute(required(&opts, "--project")?)
            .map_err(|e| e.to_string())?,
        ..Default::default()
    };
    if let Some(v) = opts.get("--task") {
        plan.gradle_task = v.clone();
    }
    plan.device_serial = opts.get("--device").cloned();
    plan.benchmark_filter = opts.get("--filter").cloned();
    plan.trace_directory = opts.get("--trace-dir").map(PathBuf::from);
    if let Some(v) = opts.get("--compilation-mode") {
        plan.compilation_mode = match v.to_ascii_uppercase().as_str() {
            "NONE" => CompilationMode::None,
            "PARTIAL" => CompilationMode::Partial,
            "FULL" => CompilationMode::Full,
            "SPEED_PROFILE" => CompilationMode::SpeedProfile,
            _ => return Err("Invalid compilation mode".into()),
        };
    }
    if let Some(v) = opts.get("--startup-mode") {
        plan.startup_mode = match v.to_ascii_uppercase().as_str() {
            "COLD" => StartupMode::Cold,
            "WARM" => StartupMode::Warm,
            "HOT" => StartupMode::Hot,
            _ => return Err("Invalid startup mode".into()),
        };
    }
    if let Some(v) = opts.get("--scenario") {
        plan.scenario = match v.to_ascii_uppercase().as_str() {
            "STARTUP" => BenchmarkScenario::Startup,
            "SCROLL" => BenchmarkScenario::Scroll,
            "ANIMATION" => BenchmarkScenario::Animation,
            "PAGE_SWITCH" => BenchmarkScenario::PageSwitch,
            _ => return Err("Invalid scenario".into()),
        };
    }
    if let Some(v) = opts.get("--warmups") {
        plan.warmups = v.parse().map_err(|_| "Invalid warmups")?;
    }
    if let Some(v) = opts.get("--iterations") {
        plan.iterations = v.parse().map_err(|_| "Invalid iterations")?;
    }
    let timeout: u64 = opts
        .get("--timeout-seconds")
        .map_or(Ok(3600), |v| v.parse())
        .map_err(|_| "Invalid timeout")?;
    if timeout == 0 || timeout > 31_536_000 {
        return Err("Timeout must be between 1 and 31536000 seconds".into());
    }
    if opts.contains_key("--baseline") && !opts.contains_key("--result") {
        return Err("--baseline requires --result".into());
    }
    if opts.contains_key("--report-json") && !opts.contains_key("--baseline") {
        return Err("--report-json requires --baseline and --result".into());
    }
    let policy = policy(&opts)?;
    let command = build_command(&plan)?;
    if !execute {
        println!("{}", command.command_line());
        for (key, value) in &command.environment {
            println!("{key}={value}");
        }
        return Ok(0);
    }
    let result = run_macrobenchmark(
        &command,
        Duration::from_secs(timeout),
        Arc::new(AtomicBool::new(false)),
        &mut |chunk| print!("{chunk}"),
    );
    if result.timed_out {
        eprintln!("Macrobenchmark timed out");
        return Ok(124);
    }
    if !result.succeeded() {
        if result.exit_code < 0 {
            eprintln!(
                "Macrobenchmark failed to start or exited abnormally: {}",
                result.output
            );
        }
        return Ok(if result.exit_code > 0 {
            result.exit_code
        } else {
            2
        });
    }
    if let Some(path) = opts.get("--result") {
        let current = load(path)?;
        println!("macrobenchmark_cases={}", current.cases.len());
        if let Some(baseline) = opts.get("--baseline") {
            let baseline = load(baseline)?;
            let experiment = compare_baseline_profile(&baseline, &current, &policy)?;
            if let Some(path) = opts.get("--report-json") {
                if current
                    .cases
                    .iter()
                    .chain(&baseline.cases)
                    .any(|case| case.profile.is_some())
                {
                    write_baseline_profile_json(&experiment, Path::new(path))?;
                } else {
                    write_json(&experiment.report, None, Path::new(path))?;
                }
            }
            let count = regression_count(&experiment.report);
            println!("macrobenchmark_regressions={count}");
            return Ok(comparison_exit_code(&experiment.report));
        }
    }
    Ok(0)
}
