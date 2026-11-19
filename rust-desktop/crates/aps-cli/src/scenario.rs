//! Typed, local scenario execution. A manifest selects a trusted Gradle project;
//! it is never read from an artifact URL or an MCP tool call.
use aps_benchmark::{BenchmarkScenario, MacrobenchmarkPlan, build_command, run_macrobenchmark};
use aps_contracts::SourceDigest;
use serde_json::{Value, json};
use std::{
    collections::BTreeMap,
    fs,
    io::Read,
    path::{Component, Path},
    sync::{Arc, atomic::AtomicBool},
    time::Duration,
};

pub fn run(file: &str, args: Vec<String>) -> Result<(Option<Value>, Vec<String>), String> {
    if file.contains("://") {
        return Err("E_SCENARIO_LOCAL_ONLY".into());
    }
    let mut opts = BTreeMap::new();
    for pair in args.chunks(2) {
        if pair.len() != 2
            || !matches!(
                pair[0].as_str(),
                "--device"
                    | "--approve"
                    | "--ai"
                    | "--out"
                    | "--evidence-out"
                    | "--question"
                    | "--format"
            )
            || pair[1].is_empty()
            || pair[1].starts_with("--")
            || opts.insert(pair[0].as_str(), pair[1].as_str()).is_some()
        {
            return Err("E_SCENARIO_OPTIONS".into());
        }
    }
    let device = opts
        .remove("--device")
        .ok_or("E_SCENARIO_DEVICE_REQUIRED")?;
    let approval = opts.remove("--approve");
    let review = match opts.get("--ai").copied().unwrap_or("off") {
        "review" => true,
        "off" => false,
        _ => return Err("E_SCENARIO_AI_MODE".into()),
    };
    if (review != opts.contains_key("--out"))
        || (!review && opts.contains_key("--question"))
        || opts.get("--format").is_some_and(|f| *f != "json")
    {
        return Err(
            "E_SCENARIO_OPTIONS: --ai review requires --out; only JSON evidence is supported"
                .into(),
        );
    }
    let path = fs::canonicalize(file).map_err(|_| "E_SCENARIO_OPEN")?;
    if !path.is_file() {
        return Err("E_SCENARIO_OPEN".into());
    }
    let mut data = Vec::new();
    fs::File::open(&path)
        .map_err(|_| "E_SCENARIO_OPEN")?
        .take(16385)
        .read_to_end(&mut data)
        .map_err(|_| "E_SCENARIO_OPEN")?;
    let doc = aps_json::parse_slice(
        &data,
        aps_json::Limits {
            max_bytes: 16384,
            max_depth: 3,
            max_nodes: 32,
            max_string_bytes: 2048,
            max_collection_items: 16,
        },
    )
    .map_err(|_| "E_SCENARIO_JSON")?;
    let obj = doc.as_object().ok_or("E_SCENARIO_JSON")?;
    if obj.keys().any(|k| {
        !matches!(
            k.as_str(),
            "schemaVersion"
                | "kind"
                | "project"
                | "result"
                | "task"
                | "scenario"
                | "iterations"
                | "timeoutSeconds"
                | "filter"
        )
    }) || doc["schemaVersion"] != "aps-scenario-v1"
        || doc["kind"] != "macrobenchmark"
    {
        return Err("E_SCENARIO_UNSUPPORTED: expected local aps-scenario-v1 macrobenchmark".into());
    }
    let required = |key: &str| {
        doc[key]
            .as_str()
            .filter(|s| !s.is_empty() && !s.chars().any(char::is_control))
            .ok_or("E_SCENARIO_FIELD")
    };
    let num = |key: &str, default: u64, max: u64| -> Result<u64, String> {
        match obj.get(key) {
            None => Ok(default),
            Some(v) => v
                .as_u64()
                .filter(|n| (1..=max).contains(n))
                .ok_or_else(|| "E_SCENARIO_RANGE".into()),
        }
    };
    let project = path
        .parent()
        .ok_or("E_SCENARIO_PROJECT")?
        .join(required("project")?)
        .canonicalize()
        .map_err(|_| "E_SCENARIO_PROJECT")?;
    let rel = Path::new(required("result")?);
    if !rel.components().all(|c| matches!(c, Component::Normal(_)))
        || rel.extension().and_then(|s| s.to_str()) != Some("json")
    {
        return Err("E_SCENARIO_RESULT: expected a relative .json file under project".into());
    }
    let result = project.join(rel);
    let mut plan = MacrobenchmarkPlan {
        project_root: project.clone(),
        device_serial: Some(device.into()),
        iterations: num("iterations", 5, 100)? as u32,
        ..Default::default()
    };
    if obj.contains_key("task") {
        let task = required("task")?;
        if !task
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b":_-.".contains(&b))
        {
            return Err("E_SCENARIO_TASK".into());
        }
        plan.gradle_task = task.into();
    }
    if obj.contains_key("filter") {
        let filter = required("filter")?;
        if !filter
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._#$".contains(&b))
        {
            return Err("E_SCENARIO_FILTER".into());
        }
        plan.benchmark_filter = Some(filter.into());
    }
    plan.scenario = match doc.get("scenario").map(Value::as_str) {
        None | Some(Some("startup")) => BenchmarkScenario::Startup,
        Some(Some("scroll")) => BenchmarkScenario::Scroll,
        Some(Some("animation")) => BenchmarkScenario::Animation,
        Some(Some("page-switch")) => BenchmarkScenario::PageSwitch,
        _ => return Err("E_SCENARIO_KIND".into()),
    };
    let timeout = num("timeoutSeconds", 300, 3600)?;
    let command = build_command(&plan)?;
    let wrapper = fs::File::open(&command.executable).map_err(|_| "E_SCENARIO_WRAPPER")?;
    let view = json!({"kind":"trusted-local-gradle","project":project,"result":result,"device":device,
        "executable":command.executable,"arguments":command.arguments,"environment":command.environment,
        "wrapperDigest":SourceDigest::hash_reader(wrapper,4*1024*1024)?.to_hex(),
        "timeoutSeconds":timeout,"sideEffects":"Executes local project code and device tests; output must be new."});
    let hash = SourceDigest::hash_reader(
        serde_json::to_vec(&view).map_err(|_| "E_PLAN")?.as_slice(),
        32768,
    )?
    .to_hex();
    let Some(approved) = approval else {
        println!(
            "{}",
            json!({"schemaVersion":"aps-plan-v1","plan":view,"approvalDigest":hash,"executed":false})
        );
        return Ok((None, vec![]));
    };
    if approved != hash {
        return Err("E_SCENARIO_APPROVAL: preview the current plan".into());
    }
    // Never treat a stale result or a dangling symlink as this invocation's capture.
    let mut current = project.clone();
    for part in rel.components() {
        current.push(part.as_os_str());
        match fs::symlink_metadata(&current) {
            Ok(m) if m.file_type().is_symlink() || current == result || !m.is_dir() => {
                return Err("E_SCENARIO_RESULT_EXISTS_OR_UNSAFE".into());
            }
            Ok(_) => (),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => (),
            Err(_) => return Err("E_SCENARIO_RESULT".into()),
        }
    }
    validate_outputs(&opts, &result)?;
    let captured = run_macrobenchmark(
        &command,
        Duration::from_secs(timeout),
        Arc::new(AtomicBool::new(false)),
        &mut |_| {},
    );
    if !captured.succeeded() {
        return Err(format!(
            "E_SCENARIO_CAPTURE: exit={}, timeout={}",
            captured.exit_code, captured.timed_out
        ));
    }
    if result.canonicalize().ok().as_ref() != Some(&result) || !result.is_file() {
        return Err("E_SCENARIO_RESULT: fresh result file was not produced".into());
    }
    // A successful Gradle exit alone doesn't prove its artifact is a benchmark.
    let evidence = crate::analyze_path(&result, &Default::default())?;
    if evidence["evidence"]["kind"] != "benchmark" {
        return Err("E_SCENARIO_RESULT: not an AndroidX benchmark".into());
    }
    Ok((
        Some(evidence),
        opts.into_iter()
            .flat_map(|(k, v)| [k.to_string(), v.to_string()])
            .collect(),
    ))
}

fn validate_outputs(opts: &BTreeMap<&str, &str>, result: &Path) -> Result<(), String> {
    let mut paths = vec![result.to_path_buf()];
    for key in ["--out", "--evidence-out"] {
        let Some(s) = opts.get(key) else { continue };
        let p = Path::new(s);
        let ext = p.extension().and_then(|s| s.to_str());
        if key == "--out" && !matches!(ext, Some("md" | "html")) {
            return Err("E_REPORT_FORMAT".into());
        }
        let parent = p
            .parent()
            .filter(|d| !d.as_os_str().is_empty())
            .unwrap_or(Path::new("."))
            .canonicalize()
            .map_err(|_| "E_OUTPUT_PARENT")?;
        let dest = parent.join(p.file_name().ok_or("E_OUTPUT")?);
        if dest.symlink_metadata().is_ok() || paths.contains(&dest) {
            return Err("E_OUTPUT: require a distinct new output".into());
        }
        paths.push(dest);
    }
    Ok(())
}
