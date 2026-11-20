//! Standalone Benchmark comparison and Macrobenchmark runner.
mod macrobenchmark;

use aps_benchmark::{
    BenchmarkRun, Classification, MAX_IMPORT_BYTES, RegressionPolicy, compare, write_json,
    write_junit, write_sarif,
};
use std::collections::BTreeMap;
use std::path::Path;

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
        return macrobenchmark::run(&args[1..], mode == "macro-run");
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
