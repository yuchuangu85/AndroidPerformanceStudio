//! Terminal command routing. Side-effecting host adapters stay here, outside MCP.
pub mod ai;
mod companion;
pub mod cpu_capture;
mod output;
mod publish;
pub mod scenario;
use crate::{analyze_path_with_scope, capabilities, input::SelectedInput};
pub use companion::{benchmark_binary, run_macrobenchmark};
use output::write_json;
pub use output::write_new;
use publish::publish;

const USAGE: &str = "Android Performance Studio (headless)
  aps analyze FILE|HTTPS_URL [--kind auto|trace] [--format json] [--evidence-out FILE]
              [--scope report | --frame-id ID] (Frame reports only)
              [--ai review --out REPORT.md|REPORT.html [--question TEXT]]
  aps capture cpu --device SERIAL --pid PID --seconds N --adb /path/to/adb
                  --converter /path/to/simpleperf --artifact-dir DIR [--plan | analysis options]
  aps run SCENARIO.json --device SERIAL [--approve PLAN_DIGEST] [--ai review --out REPORT.md]
  aps run macro-plan|macro-run [aps-benchmark options]
  aps mcp [--input LOCAL_FILE | --artifact-url HTTPS_URL]
  aps capabilities

Without --ai review no model is called. Evidence defaults to JSON on stdout.
New output files only. Use aps capabilities for supported and unavailable inputs.";

pub fn run(args: Vec<String>) -> Result<i32, String> {
    let mut args = args.into_iter();
    match args.next().as_deref() {
        Some("analyze") => analyze(args.collect()),
        Some("capture") => match args.next().as_deref() {
            Some("cpu") => {
                let (cpu, opts) = cpu_capture::CpuCapture::parse(args)?;
                if cpu.preview {
                    write_json(&cpu.preview()?, None)?;
                    Ok(0)
                } else {
                    publish(opts.into_iter(), |_, _| cpu.execute())
                }
            }
            Some("--help" | "-h") => {
                println!("{USAGE}");
                Ok(0)
            }
            Some(kind) if crate::DEFERRED_CAPTURE.contains(&kind) => Err(format!(
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
                    let (artifact, options) = scenario::run(&mode, args.collect())?;
                    match artifact {
                        None => Ok(0), // preview only
                        Some(evidence) => publish(options.into_iter(), |_, _| Ok(evidence)),
                    }
                }
            }
        }
        Some("capabilities") if args.next().is_none() => {
            write_json(&capabilities(), None)?;
            Ok(0)
        }
        Some("mcp") => {
            crate::mcp::run(&args.collect::<Vec<_>>())?;
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
    publish(args, |options, scope| {
        let source = SelectedInput::acquire(&input)?;
        analyze_path_with_scope(source.path(), &options, scope)
    })
}
