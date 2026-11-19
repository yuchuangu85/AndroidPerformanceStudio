use aps_cli::{
    AnalyzeOptions, OutputFormat, analyze_path, capabilities, input::SelectedInput,
    run_macrobenchmark,
};
use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};

const USAGE: &str = "Android Performance Studio (headless)
  aps analyze FILE|HTTPS_URL [--kind auto|trace] [--format json] [--evidence-out FILE]
              [--ai review --out REPORT.md|REPORT.html [--question TEXT]]
  aps capture cpu --device SERIAL --pid PID --seconds N --adb /path/to/adb
                  --converter /path/to/simpleperf --artifact-dir DIR [--plan | analysis options]
  aps run SCENARIO.json --device SERIAL [--approve PLAN_DIGEST] [--ai review --out REPORT.md]
  aps run macro-plan|macro-run [aps-benchmark options]
  aps mcp [--input LOCAL_FILE | --artifact-url HTTPS_URL]
  aps capabilities

Without --ai review no model is called. Evidence defaults to JSON on stdout.
New output files only. Use aps capabilities for supported and unavailable inputs.";

fn main() {
    let args = std::env::args_os()
        .skip(1)
        .map(|arg| {
            arg.into_string()
                .map_err(|_| "E_USAGE: arguments must be valid UTF-8".to_owned())
        })
        .collect::<Result<Vec<_>, _>>();
    let code = match args.and_then(run) {
        Ok(code) => code,
        Err(error) => {
            eprintln!("aps: {error}");
            2
        }
    };
    std::process::exit(code);
}

fn run(args: Vec<String>) -> Result<i32, String> {
    let mut args = args.into_iter();
    match args.next().as_deref() {
        Some("analyze") => analyze(args.collect()),
        Some("capture") => match args.next().as_deref() {
            Some("cpu") => {
                let (cpu, opts) = aps_cli::cpu_capture::CpuCapture::parse(args)?;
                if cpu.preview {
                    write_json(&cpu.preview()?, None)?;
                    Ok(0)
                } else {
                    publish(opts.into_iter(), |_| cpu.execute())
                }
            }
            Some("--help" | "-h") => {
                println!("{USAGE}");
                Ok(0)
            }
            Some(kind) if aps_cli::DEFERRED_CAPTURE.contains(&kind) => Err(format!(
                "E_CAPABILITY_UNAVAILABLE: standalone {kind} capture is not enabled"
            )),
            _ => Err("E_CAPTURE_KIND: only the timed CPU capture adapter is available".into()),
        },
        Some("run") => {
            let mode = args
                .next()
                .ok_or("E_USAGE: expected a scenario file, macro-plan or macro-run")?;
            match mode.as_str() {
                "--help" | "-h" => {
                    println!("{USAGE}");
                    Ok(0)
                }
                "macro-plan" | "macro-run" => {
                    run_macrobenchmark(&std::iter::once(mode).chain(args).collect::<Vec<_>>())
                }
                _ => {
                    let (artifact, options) = aps_cli::scenario::run(&mode, args.collect())?;
                    match artifact {
                        None => Ok(0), // preview only
                        Some(evidence) => publish(options.into_iter(), |_| Ok(evidence)),
                    }
                }
            }
        }
        Some("capabilities") if args.next().is_none() => {
            write_json(&capabilities(), None)?;
            Ok(0)
        }
        Some("mcp") => {
            aps_cli::mcp::run(&args.collect::<Vec<_>>())?;
            Ok(0)
        }
        None | Some("--help" | "-h" | "help") => {
            println!("{USAGE}");
            Ok(0)
        }
        _ => Err("E_USAGE: see aps --help".into()),
    }
}

fn analyze(args: Vec<String>) -> Result<i32, String> {
    let mut args = args.into_iter();
    let input = args
        .next()
        .ok_or("E_USAGE: missing artifact (see aps --help)")?;
    if matches!(input.as_str(), "--help" | "-h") {
        println!("{USAGE}");
        return Ok(0);
    }
    publish(args, |options| {
        let source = SelectedInput::acquire(&input)?;
        analyze_path(source.path(), &options)
    })
}

// Both acquisition paths publish the same frozen envelope. For local or
// downloaded input the producer is deferred until output preflight passes.
fn publish(
    mut args: impl Iterator<Item = String>,
    produce: impl FnOnce(AnalyzeOptions) -> Result<serde_json::Value, String>,
) -> Result<i32, String> {
    let mut opts = std::collections::BTreeMap::new();
    while let Some(flag) = args.next() {
        if !matches!(
            flag.as_str(),
            "--evidence-out" | "--format" | "--kind" | "--ai" | "--out" | "--question"
        ) {
            return Err("E_USAGE: unknown analyze option".into());
        }
        let val = args
            .next()
            .filter(|v| !v.is_empty() && !v.starts_with("--"))
            .ok_or("E_USAGE: missing option value")?;
        if opts.insert(flag, val).is_some() {
            return Err("E_USAGE: duplicate analyze option".into());
        }
    }
    let get = |key: &str| opts.get(key).map(String::as_str);
    let ai = match get("--ai").unwrap_or("off") {
        "off" => false,
        "review" => true,
        _ => return Err("E_USAGE: use --ai off or --ai review".into()),
    };
    let format = OutputFormat::parse(get("--format").unwrap_or("json"))?;
    let dest = get("--evidence-out").map(Path::new);
    let report = get("--out").map(Path::new);
    if ai != report.is_some() || !ai && get("--question").is_some() {
        return Err(
            "E_USAGE: --ai review requires --out; --question/--out require --ai review".into(),
        );
    }
    // Validate destinations before fetching data or sending evidence to an AI.
    let evidence_location = dest.map(available_destination).transpose()?;
    if let Some(path) = report {
        let output = available_destination(path)?;
        if evidence_location.as_ref() == Some(&output) {
            return Err("E_OUTPUT: report and evidence must select distinct destinations".into());
        }
        if !matches!(
            path.extension().and_then(|s| s.to_str()),
            Some("md" | "html")
        ) {
            return Err("E_REPORT_FORMAT: use a .md or .html report path".into());
        }
    }
    let force_trace = match get("--kind") {
        None | Some("auto") => false,
        Some("trace") => true,
        Some(kind) if aps_cli::DEFERRED_INPUT.contains(&kind) => {
            return Err(format!(
                "E_CAPABILITY_UNAVAILABLE: no standalone {kind} input adapter"
            ));
        }
        _ => return Err("E_USAGE: use --kind auto or trace".into()),
    };
    let evidence = produce(AnalyzeOptions {
        format,
        force_trace,
    })?;
    // Publish the deterministic result even if the optional provider is later
    // unavailable, the user declines, validation fails, or report writing fails.
    write_json(&evidence, dest)?;
    if let Some(report) = report {
        available_destination(report)?;
        aps_cli::ai::review(&evidence, get("--question"), report)?;
    }
    Ok(0)
}

fn available_destination(path: &Path) -> Result<PathBuf, String> {
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => (),
        _ => {
            return Err(
                "E_OUTPUT: refusing to overwrite an existing or inaccessible destination".into(),
            );
        }
    }
    let name = path.file_name().ok_or("E_OUTPUT: invalid filename")?;
    let parent = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    let dir = fs::canonicalize(parent).map_err(|_| "E_OUTPUT: inaccessible output directory")?;
    if !dir.is_dir() {
        return Err("E_OUTPUT: expected directory".into());
    }
    Ok(dir.join(name))
}

fn write_json(value: &serde_json::Value, dest: Option<&Path>) -> Result<(), String> {
    let mut bytes = serde_json::to_vec(value).map_err(|_| "E_EVIDENCE_ENCODING")?;
    bytes.push(b'\n');
    if let Some(path) = dest {
        return aps_cli::write_new(path, &bytes);
    }
    let mut out = io::stdout().lock();
    out.write_all(&bytes)
        .and_then(|_| out.flush())
        .map_err(|_| "E_OUTPUT: cannot write stdout".into())
}
