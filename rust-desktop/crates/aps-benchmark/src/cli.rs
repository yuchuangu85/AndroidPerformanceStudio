//! Standalone Benchmark comparison and Macrobenchmark runner.
use aps_benchmark::{
    BenchmarkRun, BenchmarkScenario, Classification, CompilationMode, MAX_IMPORT_BYTES,
    MacrobenchmarkPlan, RegressionPolicy, StartupMode, build_command, compare,
    compare_baseline_profile, run_macrobenchmark, write_baseline_profile_json, write_json,
    write_junit, write_sarif,
};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, atomic::AtomicBool};
use std::time::Duration;

fn main() {
    let code = match run() {
        Ok(code) => code,
        Err(error) => {
            eprintln!("{error}");
            2
        }
    };
    std::process::exit(code);
}

fn options(args: &[String], allowed: &[&str]) -> Result<BTreeMap<String, String>, String> {
    let mut result = BTreeMap::new();
    for pair in args.chunks(2) {
        if pair.len() != 2 || !allowed.contains(&pair[0].as_str()) || pair[1].is_empty() {
            return Err(format!("Invalid or missing option: {}", pair[0]));
        }
        if result.insert(pair[0].clone(), pair[1].clone()).is_some() {
            return Err(format!("Duplicate option: {}", pair[0]));
        }
    }
    Ok(result)
}
fn required<'a>(options: &'a BTreeMap<String, String>, key: &str) -> Result<&'a str, String> {
    options
        .get(key)
        .map(String::as_str)
        .ok_or_else(|| format!("Missing {key}"))
}
fn load(path: &str) -> Result<BenchmarkRun, String> {
    BenchmarkRun::open(Path::new(path), MAX_IMPORT_BYTES)
}
fn policy(opts: &BTreeMap<String, String>) -> Result<RegressionPolicy, String> {
    let mut policy = RegressionPolicy::default();
    if let Some(raw) = opts.get("--threshold-percent") {
        let value: f64 = raw.parse().map_err(|_| "Invalid threshold percent")?;
        if !value.is_finite() || value < 0.0 {
            return Err("Invalid threshold percent".into());
        }
        policy.relative_threshold_percent = Some(value);
    }
    Ok(policy)
}
fn regression_count(report: &aps_benchmark::RegressionReport) -> usize {
    report
        .comparisons
        .iter()
        .filter(|row| row.classification == Classification::Regressed)
        .count()
}
fn comparison_exit_code(report: &aps_benchmark::RegressionReport) -> i32 {
    if regression_count(report) > 0 {
        1
    } else if report.comparisons.is_empty()
        || report.comparisons.iter().any(|row| {
            matches!(
                row.classification,
                Classification::Inconclusive | Classification::Incompatible
            )
        })
    {
        3
    } else {
        0
    }
}
fn run() -> Result<i32, String> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let mode = args.first().map(String::as_str).unwrap_or("");
    if mode == "macro-plan" || mode == "macro-run" {
        return macro_command(&args[1..], mode == "macro-run");
    }
    let args = if mode == "compare" {
        &args[1..]
    } else {
        &args[..]
    };
    let opts = options(
        args,
        &[
            "--baseline",
            "--current",
            "--threshold-percent",
            "--json",
            "--junit",
            "--sarif",
        ],
    )?;
    let report = compare(
        &load(required(&opts, "--baseline")?)?,
        &load(required(&opts, "--current")?)?,
        &policy(&opts)?,
    )?;
    if let Some(path) = opts.get("--json") {
        write_json(&report, None, Path::new(path))?;
    }
    if let Some(path) = opts.get("--junit") {
        write_junit(&report, Path::new(path))?;
    }
    if let Some(path) = opts.get("--sarif") {
        write_sarif(&report, Path::new(path))?;
    }
    for row in &report.comparisons {
        println!(
            "{}\t{}\t{}\t{}%",
            row.classification.label(),
            row.case_identity,
            row.metric_name,
            row.relative_delta_percent
                .map_or("n/a".into(), |v| v.to_string())
        );
    }
    Ok(comparison_exit_code(&report))
}
fn macro_command(args: &[String], execute: bool) -> Result<i32, String> {
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
