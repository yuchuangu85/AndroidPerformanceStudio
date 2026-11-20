//! Preflight CLI options and destinations before producing any artifact or
//! requesting an independently approved model review.
use super::output::{available_destination, write_json};
use crate::{AnalyzeOptions, FrameScope, OutputFormat};
use std::path::Path;

// Both acquisition paths publish the same frozen envelope. For local or
// downloaded input the producer is deferred until output preflight passes.
pub(super) fn publish(
    mut args: impl Iterator<Item = String>,
    produce: impl FnOnce(AnalyzeOptions, Option<FrameScope>) -> Result<serde_json::Value, String>,
) -> Result<i32, String> {
    let mut opts = std::collections::BTreeMap::new();
    while let Some(flag) = args.next() {
        if !matches!(
            flag.as_str(),
            "--evidence-out"
                | "--format"
                | "--kind"
                | "--ai"
                | "--out"
                | "--question"
                | "--scope"
                | "--frame-id"
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
        Some(kind) if crate::DEFERRED_INPUT.contains(&kind) => {
            return Err(format!(
                "E_CAPABILITY_UNAVAILABLE: no standalone {kind} input adapter"
            ));
        }
        _ => return Err("E_USAGE: use --kind auto or trace".into()),
    };
    let frame_id = get("--frame-id")
        .map(aps_analysis::frame::parse_frame_id)
        .transpose()
        .map_err(|err| format!("E_ANALYSIS_SCOPE: {err}"))?;
    let frame_scope = crate::frame_scope(get("--scope"), frame_id)?;
    if frame_scope.is_some() && force_trace {
        return Err("E_ANALYSIS_SCOPE: Frame scope is incompatible with --kind trace".into());
    }
    let evidence = produce(
        AnalyzeOptions {
            format,
            force_trace,
        },
        frame_scope,
    )?;
    // Publish the deterministic result even if the optional provider is later
    // unavailable, the user declines, validation fails, or report writing fails.
    write_json(&evidence, dest)?;
    if let Some(report) = report {
        available_destination(report)?;
        super::ai::review(&evidence, get("--question"), report)?;
    }
    Ok(0)
}
