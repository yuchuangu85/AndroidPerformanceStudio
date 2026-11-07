use crate::{BenchmarkCase, BenchmarkRun, Cohort, Metric, MetricDirection};
use aps_json::Value;
use std::collections::{BTreeMap, HashSet};

pub fn document(root: Value) -> Result<BenchmarkRun, String> {
    let context = root
        .get("context")
        .or_else(|| root.get("device"))
        .unwrap_or(&Value::Null);
    let build = root
        .get("build")
        .or_else(|| context.get("build"))
        .unwrap_or(&Value::Null);
    let text = |value: &Value, names: &[&str]| {
        names
            .iter()
            .find_map(|name| value.get(*name).and_then(Value::as_str))
            .map(str::to_owned)
    };
    let integer = |value: &Value, names: &[&str]| {
        names
            .iter()
            .find_map(|name| value.get(*name).and_then(Value::as_u64))
            .and_then(|value| u32::try_from(value).ok())
    };
    let cohort = Cohort {
        device_model: text(context, &["deviceModel", "model"]),
        api_level: integer(context, &["apiLevel", "sdkInt"]),
        abi: text(context, &["abi", "cpuAbi"]),
        fingerprint: text(context, &["fingerprint", "buildFingerprint"]),
        physical_device: context
            .get("physicalDevice")
            .or_else(|| context.get("isPhysicalDevice"))
            .and_then(Value::as_bool),
        build_variant: text(build, &["variant"]).or_else(|| text(&root, &["variant"])),
        library_version: text(&root, &["benchmarkLibraryVersion"])
            .or_else(|| text(context, &["benchmarkLibraryVersion"])),
        data_version: integer(&root, &["version", "benchmarkDataVersion"])
            .or_else(|| integer(context, &["benchmarkDataVersion"])),
    };
    let rows = root
        .get("benchmarks")
        .or_else(|| root.get("results"))
        .or_else(|| root.get("tests"))
        .and_then(Value::as_array)
        .ok_or("No AndroidX benchmarks/results/tests array")?;
    if rows.is_empty() || rows.len() > 10_000 {
        return Err("Benchmark requires 1..10000 cases".into());
    }
    let mut cases = Vec::new();
    let mut identities = HashSet::new();
    for row in rows {
        let name =
            text(row, &["name", "testName", "benchmarkName"]).ok_or("Benchmark case lacks name")?;
        let class = text(row, &["className"])
            .or_else(|| name.rsplit_once('.').map(|(class, _)| class.into()))
            .unwrap_or_else(|| "UnknownBenchmark".into());
        let test = text(row, &["testName"])
            .unwrap_or_else(|| name.rsplit('.').next().unwrap_or(&name).into());
        let identity = format!("{class}#{test}");
        if !identities.insert(identity.clone()) {
            return Err("Duplicate benchmark case identity".into());
        }
        let params = row.get("params").unwrap_or(&Value::Null);
        let mut metrics = BTreeMap::new();
        let input = row
            .get("metrics")
            .or_else(|| row.get("measurements"))
            .and_then(Value::as_object)
            .ok_or("Benchmark case has no metric object")?;
        for (name, value) in input {
            let mut samples = Vec::new();
            if let Some(number) = value.as_f64() {
                samples.push(number);
            } else if let Some(array) = ["runs", "values", "samples", "measurements"]
                .iter()
                .find_map(|key| value.get(*key).and_then(Value::as_array))
            {
                if array.len() > 100_000 {
                    return Err("Benchmark metric exceeds raw sample budget".into());
                }
                for value in array {
                    let sample = value
                        .as_f64()
                        .or_else(|| value.get("value").and_then(Value::as_f64))
                        .filter(|value| value.is_finite())
                        .ok_or("Invalid raw benchmark sample")?;
                    samples.push(sample);
                }
            }
            if samples.iter().any(|value| !value.is_finite()) {
                return Err("Nonfinite benchmark sample".into());
            }
            let reported_median = value.as_f64().or_else(|| {
                value
                    .get("median")
                    .or_else(|| value.get("p50"))
                    .and_then(Value::as_f64)
            });
            if samples.is_empty() && reported_median.is_none() {
                continue;
            }
            let lower = name.to_ascii_lowercase();
            let unit = text(value, &["unit"]).unwrap_or_else(|| {
                if lower.ends_with("ms") || lower.contains("millisecond") {
                    "ms"
                } else if lower.ends_with("ns") || lower.contains("nanosecond") {
                    "ns"
                } else if lower.contains("byte") {
                    "bytes"
                } else if lower.ends_with("kb") {
                    "KiB"
                } else if lower.contains("percent") || lower.ends_with("pct") {
                    "%"
                } else {
                    "unit"
                }
                .into()
            });
            let direction = if ["throughput", "fps", "framespersecond"]
                .iter()
                .any(|word| lower.contains(word))
            {
                MetricDirection::HigherIsBetter
            } else if [
                "time", "duration", "latency", "overrun", "jank", "memory", "byte", "power",
                "energy",
            ]
            .iter()
            .any(|word| lower.contains(word))
            {
                MetricDirection::LowerIsBetter
            } else {
                MetricDirection::Unknown
            };
            metrics.insert(
                name.clone(),
                Metric {
                    name: name.clone(),
                    unit,
                    direction,
                    samples,
                    reported_median,
                },
            );
        }
        if metrics.is_empty() {
            return Err("Benchmark case contains no usable metrics".into());
        }
        let profile = row
            .get("baselineProfile")
            .or_else(|| row.get("profile"))
            .unwrap_or(&Value::Null);
        let scenario = text(row, &["scenario"])
            .or_else(|| text(params, &["scenario"]))
            .map(|value| value.to_ascii_uppercase().replace('-', "_"));
        let mut trace_paths = Vec::new();
        if let Some(path) = text(row, &["tracePath"]) {
            trace_paths.push(path);
        }
        if let Some(paths) = row.get("tracePaths").and_then(Value::as_array) {
            for path in paths {
                trace_paths.push(path.as_str().ok_or("Invalid benchmark trace path")?.into());
            }
        }
        cases.push(BenchmarkCase {
            identity,
            package: text(row, &["packageName", "targetPackage"])
                .or_else(|| text(build, &["targetPackage"]))
                .or_else(|| text(&root, &["targetPackage"])),
            compilation_mode: text(row, &["compilationMode"])
                .or_else(|| text(params, &["compilationMode"])),
            startup_mode: text(row, &["startupMode"]).or_else(|| text(params, &["startupMode"])),
            scenario,
            profile_status: text(row, &["baselineProfileStatus"])
                .or_else(|| text(profile, &["status"])),
            profile_hash: text(row, &["baselineProfileHash"])
                .or_else(|| text(profile, &["sha256", "hash"])),
            trace_paths,
            metrics,
        });
    }
    Ok(BenchmarkRun { cohort, cases, warnings: vec!["Imported device/build/Profile metadata is declared evidence, not independent hardware or compilation verification. Trace paths are metadata only and are never opened automatically.".into()] })
}
