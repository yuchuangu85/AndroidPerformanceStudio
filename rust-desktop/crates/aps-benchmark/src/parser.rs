//! Port of the Compose `BenchmarkJsonParser` plus the Rust fail-closed bounds.
//!
//! A case that cannot be represented is skipped with a warning (Compose behaviour),
//! but a document with no valid case, more than 10 000 cases, or a duplicate
//! `class#test` identity is rejected instead of silently losing evidence.
use std::collections::BTreeSet;
use std::path::{Component, Path, PathBuf};

use aps_json::{Map, Value};

use crate::model::{
    BenchmarkBuild, BenchmarkCase, BenchmarkDevice, BenchmarkRun, Confidence, Metric,
    MetricDirection, ProfileEvidence, ProfileStatus, Scenario, new_run,
};

const KNOWN_TOP_LEVEL: &[&str] = &[
    "context",
    "device",
    "build",
    "benchmarks",
    "results",
    "tests",
    "version",
    "benchmarkDataVersion",
    "benchmarkLibraryVersion",
    "targetPackage",
    "versionName",
    "versionCode",
    "variant",
    "gitCommit",
    "gitBranch",
];

pub(crate) fn document(root: Value, base_dir: Option<&Path>) -> Result<BenchmarkRun, String> {
    let root = root
        .as_object()
        .ok_or("Benchmark JSON root must be an object")?;
    let context = object(root, &["context"]).or_else(|| object(root, &["device"]));
    let build =
        object(root, &["build"]).or_else(|| context.and_then(|value| object(value, &["build"])));
    let rows = array(root, &["benchmarks"])
        .or_else(|| array(root, &["results"]))
        .or_else(|| array(root, &["tests"]))
        .ok_or("No benchmarks/results/tests array found")?;
    if rows.len() > 10_000 {
        return Err("Benchmark exceeds the 10000 case limit".into());
    }
    let mut warnings = Vec::new();
    let mut cases = Vec::new();
    let mut identities = BTreeSet::new();
    for (index, element) in rows.iter().enumerate() {
        let Some(row) = element.as_object() else {
            warnings.push(format!(
                "Case #{index} skipped: benchmark case is not an object"
            ));
            continue;
        };
        match parse_case(row, base_dir) {
            Ok(case) => {
                let identity = case.identity();
                if !identities.insert(identity) {
                    return Err("Duplicate benchmark case identity".into());
                }
                cases.push(case);
            }
            Err(error) => warnings.push(format!("Case #{index} skipped: {error}")),
        }
    }
    if cases.is_empty() {
        return Err("No valid benchmark cases found".into());
    }
    warnings.extend(unknown_top_level_warnings(root));
    let device = parse_device(context, root);
    let build = parse_build(build, root);
    let data_version = integer(root, &["version", "benchmarkDataVersion"])
        .or_else(|| context.and_then(|value| integer(value, &["benchmarkDataVersion"])))
        .and_then(|value| u32::try_from(value).ok());
    let library_version = text(root, &["benchmarkLibraryVersion"])
        .or_else(|| context.and_then(|value| text(value, &["benchmarkLibraryVersion"])));
    Ok(new_run(
        PathBuf::new(),
        data_version,
        library_version,
        device,
        build,
        warnings,
        cases,
    ))
}

fn parse_case(row: &Map<String, Value>, base_dir: Option<&Path>) -> Result<BenchmarkCase, String> {
    let raw_name =
        text(row, &["name", "testName", "benchmarkName"]).ok_or("Benchmark case lacks name")?;
    let class_name = text(row, &["className"]).unwrap_or_else(|| match raw_name.rsplit_once('.') {
        Some((class_name, _)) => class_name.to_string(),
        None => "UnknownBenchmark".to_string(),
    });
    let test_name = text(row, &["testName"]).unwrap_or_else(|| match raw_name.rsplit_once('.') {
        Some((_, test_name)) => test_name.to_string(),
        None => raw_name.clone(),
    });
    let metrics_object = object(row, &["metrics"])
        .or_else(|| object(row, &["measurements"]))
        .ok_or_else(|| format!("No metrics found for {raw_name}"))?;
    let mut metrics = Vec::new();
    for (name, element) in metrics_object {
        if let Some(metric) = parse_metric(name, element)? {
            metrics.push(metric);
        }
    }
    if metrics.is_empty() {
        return Err(format!("No metrics found for {raw_name}"));
    }
    let params = object(row, &["params"]);
    let scenario = text(row, &["scenario"])
        .or_else(|| params.and_then(|value| text(value, &["scenario"])))
        .and_then(|raw| Scenario::from_explicit(&raw))
        .unwrap_or_else(|| Scenario::infer_from_name(&raw_name));
    Ok(BenchmarkCase {
        class_name,
        test_name,
        package_name: text(row, &["packageName", "targetPackage"]),
        compilation_mode: text(row, &["compilationMode"])
            .or_else(|| params.and_then(|value| text(value, &["compilationMode"]))),
        startup_mode: text(row, &["startupMode"])
            .or_else(|| params.and_then(|value| text(value, &["startupMode"]))),
        iteration_count: integer(row, &["repeatIterations", "iterationCount"])
            .and_then(|value| u32::try_from(value).ok()),
        scenario,
        profile: parse_baseline_profile(row, base_dir),
        trace_artifacts: parse_traces(row, base_dir)?,
        metrics,
    })
}

fn parse_metric(name: &str, element: &Value) -> Result<Option<Metric>, String> {
    if let Some(value) = element.as_f64() {
        if !value.is_finite() {
            return Err("Nonfinite benchmark sample".into());
        }
        return Ok(Some(Metric {
            name: name.to_string(),
            unit: infer_unit(name),
            direction: infer_direction(name),
            samples: vec![value],
            minimum: Some(value),
            median: Some(value),
            maximum: Some(value),
            confidence: Confidence::Partial,
            source_fields: Default::default(),
        }));
    }
    let Some(object) = element.as_object() else {
        return Ok(None);
    };
    let mut samples = Vec::new();
    if let Some(array) = ["runs", "values", "samples", "measurements"]
        .iter()
        .find_map(|key| object.get(*key).and_then(Value::as_array))
    {
        for value in array {
            let sample = numeric_value(value)
                .filter(|value| value.is_finite())
                .ok_or("Invalid raw benchmark sample")?;
            samples.push(sample);
        }
    }
    let minimum =
        double(object, &["minimum", "min"]).or_else(|| samples.iter().copied().reduce(f64::min));
    let median =
        double(object, &["median", "p50"]).or_else(|| crate::uncertainty::median(&samples));
    let maximum =
        double(object, &["maximum", "max"]).or_else(|| samples.iter().copied().reduce(f64::max));
    if minimum.is_none() && median.is_none() && maximum.is_none() && samples.is_empty() {
        return Ok(None);
    }
    let unit = text(object, &["unit"]).unwrap_or_else(|| infer_unit(name));
    let confidence = if samples.is_empty() {
        Confidence::Partial
    } else {
        Confidence::Exact
    };
    Ok(Some(Metric {
        name: name.to_string(),
        unit,
        direction: infer_direction(name),
        samples,
        minimum,
        median,
        maximum,
        confidence,
        source_fields: object
            .iter()
            .map(|(key, value)| (key.clone(), display_value(value)))
            .collect(),
    }))
}

fn numeric_value(value: &Value) -> Option<f64> {
    value.as_f64().or_else(|| {
        value
            .as_object()
            .and_then(|object| double(object, &["value", "measurement", "ns"]))
    })
}

fn parse_traces(row: &Map<String, Value>, base_dir: Option<&Path>) -> Result<Vec<PathBuf>, String> {
    let mut traces = Vec::new();
    if let Some(array) = row.get("tracePaths").and_then(Value::as_array) {
        for value in array {
            let raw = value.as_str().ok_or("Invalid benchmark trace path")?;
            push_distinct(&mut traces, resolve(base_dir, raw));
        }
    }
    if let Some(raw) = text(row, &["tracePath"]) {
        push_distinct(&mut traces, resolve(base_dir, &raw));
    }
    if let Some(array) = row.get("profilerOutputs").and_then(Value::as_array) {
        for output in array {
            let raw = output
                .as_object()
                .and_then(|object| text(object, &["filePath"]))
                .or_else(|| output.as_str().map(str::to_string));
            if let Some(raw) = raw {
                if [".trace", ".perfetto-trace", ".pftrace"]
                    .iter()
                    .any(|suffix| raw.ends_with(suffix))
                {
                    push_distinct(&mut traces, resolve(base_dir, &raw));
                }
            }
        }
    }
    Ok(traces)
}

fn parse_baseline_profile(
    row: &Map<String, Value>,
    base_dir: Option<&Path>,
) -> Option<ProfileEvidence> {
    let profile = object(row, &["baselineProfile"]).or_else(|| object(row, &["profile"]));
    let status_text = text(row, &["baselineProfileStatus"])
        .or_else(|| profile.and_then(|value| text(value, &["status"])));
    let requested = boolean(row, &["baselineProfileEnabled"])
        .unwrap_or(profile.is_some() || status_text.is_some());
    if !requested && profile.is_none() {
        return None;
    }
    let status = match status_text {
        Some(raw) => ProfileStatus::from_text(&raw),
        None if requested => ProfileStatus::Unknown,
        None => ProfileStatus::NotRequested,
    };
    let artifact = text(row, &["baselineProfilePath"])
        .or_else(|| profile.and_then(|value| text(value, &["path", "file"])))
        .map(|raw| resolve(base_dir, &raw));
    Some(ProfileEvidence {
        status,
        source: text(row, &["baselineProfileSource"])
            .or_else(|| profile.and_then(|value| text(value, &["source"]))),
        artifact,
        profile_hash: text(row, &["baselineProfileHash"])
            .or_else(|| profile.and_then(|value| text(value, &["sha256", "hash"]))),
    })
}

fn parse_device(
    context: Option<&Map<String, Value>>,
    root: &Map<String, Value>,
) -> BenchmarkDevice {
    let context_text = |names: &[&str]| context.and_then(|value| text(value, names));
    BenchmarkDevice {
        model: context_text(&["deviceModel", "model"]).or_else(|| text(root, &["deviceModel"])),
        brand: context_text(&["deviceBrand", "brand"]),
        api_level: context
            .and_then(|value| integer(value, &["apiLevel", "sdkVersion", "sdkInt"]))
            .and_then(|value| u32::try_from(value).ok()),
        os_version: context_text(&["osVersion", "buildVersion"]),
        abi: context_text(&["abi"]).or_else(|| {
            context
                .and_then(|value| array(value, &["supportedAbis"]))
                .and_then(|array| array.first())
                .and_then(content_text)
        }),
        fingerprint: context_text(&["fingerprint", "buildFingerprint"]),
        core_count: context
            .and_then(|value| integer(value, &["cpuCoreCount"]))
            .and_then(|value| u32::try_from(value).ok()),
        physical_device: context
            .and_then(|value| boolean(value, &["physicalDevice", "isPhysicalDevice"])),
    }
}

fn parse_build(build: Option<&Map<String, Value>>, root: &Map<String, Value>) -> BenchmarkBuild {
    let build_text = |names: &[&str]| {
        build
            .and_then(|value| text(value, names))
            .or_else(|| text(root, names))
    };
    let build_integer = |names: &[&str]| {
        build
            .and_then(|value| integer(value, names))
            .or_else(|| integer(root, names))
    };
    BenchmarkBuild {
        target_package: build_text(&["targetPackage"]),
        version_name: build_text(&["versionName"]),
        version_code: build_integer(&["versionCode"]),
        variant: build_text(&["variant"]),
        git_commit: build_text(&["gitCommit"]),
        git_branch: build_text(&["gitBranch"]),
    }
}

fn unknown_top_level_warnings(root: &Map<String, Value>) -> Vec<String> {
    let mut unknown: Vec<&str> = root
        .keys()
        .map(String::as_str)
        .filter(|key| !KNOWN_TOP_LEVEL.contains(key))
        .collect();
    unknown.sort_unstable();
    if unknown.is_empty() {
        Vec::new()
    } else {
        vec![format!(
            "Preserved unknown top-level fields: {}",
            unknown.join(", ")
        )]
    }
}

fn infer_unit(name: &str) -> String {
    let lower = name.to_lowercase();
    if lower.ends_with("ms") || lower.contains("millisecond") {
        "ms".into()
    } else if lower.ends_with("ns") || lower.contains("nanosecond") {
        "ns".into()
    } else if lower.contains("byte") || lower.ends_with("kb") {
        "bytes".into()
    } else if lower.contains("percent") || lower.ends_with("pct") {
        "%".into()
    } else {
        "unit".into()
    }
}

fn infer_direction(name: &str) -> MetricDirection {
    let higher_is_better = ["throughput", "fps", "framespersecond"];
    let lower_is_better = [
        "time", "duration", "latency", "overrun", "jank", "memory", "byte", "power", "energy",
    ];
    let normalized = name.to_lowercase();
    if higher_is_better
        .iter()
        .any(|word| normalized.contains(word))
    {
        MetricDirection::HigherIsBetter
    } else if lower_is_better.iter().any(|word| normalized.contains(word)) {
        MetricDirection::LowerIsBetter
    } else {
        MetricDirection::Unknown
    }
}

fn resolve(base_dir: Option<&Path>, raw: &str) -> PathBuf {
    let path = Path::new(raw);
    if path.is_absolute() || base_dir.is_none() {
        normalize_lexical(path)
    } else {
        normalize_lexical(&base_dir.unwrap_or(Path::new("")).join(path))
    }
}

fn normalize_lexical(path: &Path) -> PathBuf {
    let mut normalized = PathBuf::new();
    for component in path.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                if !normalized.pop() {
                    normalized.push("..");
                }
            }
            other => normalized.push(other.as_os_str()),
        }
    }
    normalized
}

fn push_distinct(paths: &mut Vec<PathBuf>, path: PathBuf) {
    if !paths.contains(&path) {
        paths.push(path);
    }
}

fn object<'a>(map: &'a Map<String, Value>, names: &[&str]) -> Option<&'a Map<String, Value>> {
    names
        .iter()
        .find_map(|name| map.get(*name).and_then(Value::as_object))
}

fn array<'a>(map: &'a Map<String, Value>, names: &[&str]) -> Option<&'a Vec<Value>> {
    names
        .iter()
        .find_map(|name| map.get(*name).and_then(Value::as_array))
}

fn content_text(value: &Value) -> Option<String> {
    match value {
        Value::String(text) => Some(text.clone()),
        Value::Number(number) => Some(number.to_string()),
        Value::Bool(flag) => Some(flag.to_string()),
        _ => None,
    }
}

fn text(map: &Map<String, Value>, names: &[&str]) -> Option<String> {
    names
        .iter()
        .find_map(|name| map.get(*name).and_then(content_text))
}

fn integer(map: &Map<String, Value>, names: &[&str]) -> Option<u64> {
    names.iter().find_map(|name| {
        map.get(*name)
            .and_then(Value::as_i64)
            .filter(|value| *value >= 0)
            .map(|value| value as u64)
    })
}

fn double(map: &Map<String, Value>, names: &[&str]) -> Option<f64> {
    names.iter().find_map(|name| {
        map.get(*name)
            .and_then(Value::as_f64)
            .filter(|value| value.is_finite())
    })
}

fn boolean(map: &Map<String, Value>, names: &[&str]) -> Option<bool> {
    names
        .iter()
        .find_map(|name| map.get(*name).and_then(Value::as_bool))
}

fn display_value(value: &Value) -> String {
    match value {
        Value::String(text) => text.clone(),
        Value::Number(number) => number.to_string(),
        Value::Bool(flag) => flag.to_string(),
        Value::Null => "null".to_string(),
        other => serde_json::to_string(other).unwrap_or_default(),
    }
}
