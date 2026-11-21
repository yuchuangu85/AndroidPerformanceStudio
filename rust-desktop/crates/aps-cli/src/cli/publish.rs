//! Preflight CLI options and destinations before producing any artifact or
//! requesting an independently approved model review.
use super::output::{available_destination, write_json};
use crate::{AnalyzeOptions, FrameScope, OutputFormat, analysis::startup};
use std::path::Path;

pub(super) enum Selection {
    Default,
    Frame(FrameScope),
    Startup(Option<startup::Query>),
}

// Both acquisition paths publish the same frozen envelope. For local or
// downloaded input the producer is deferred until output preflight passes.
pub(super) fn publish(
    mut args: impl Iterator<Item = String>,
    analyze_only: bool,
    produce: impl FnOnce(AnalyzeOptions, Selection) -> Result<serde_json::Value, String>,
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
                | "--requested-mode"
                | "--observed-mode"
                | "--missing-metric"
                | "--offset"
                | "--limit"
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
        Some("startup") if analyze_only => false,
        Some(kind) if crate::DEFERRED_INPUT.contains(&kind) => {
            return Err(format!(
                "E_CAPABILITY_UNAVAILABLE: no standalone {kind} input adapter"
            ));
        }
        _ => return Err("E_USAGE: use --kind auto, trace or startup".into()),
    };
    let startup_rows = get("--scope") == Some("startup-runs");
    let startup_kind = get("--kind") == Some("startup");
    let page_flags = [
        "--requested-mode",
        "--observed-mode",
        "--missing-metric",
        "--offset",
        "--limit",
    ];
    if !startup_rows && page_flags.iter().any(|flag| get(flag).is_some()) {
        return Err("E_ANALYSIS_SCOPE: Startup filters require --scope startup-runs".into());
    }
    let selection = if startup_kind || startup_rows {
        if !analyze_only
            || force_trace
            || get("--frame-id").is_some()
            || get("--scope").is_some() && !startup_rows
        {
            return Err(
                "E_ANALYSIS_SCOPE: Startup analysis is incompatible with this selection".into(),
            );
        }
        Selection::Startup(startup_rows.then(|| startup_query(&get)).transpose()?)
    } else {
        let frame_id = get("--frame-id")
            .map(aps_analysis::frame::parse_frame_id)
            .transpose()
            .map_err(|err| format!("E_ANALYSIS_SCOPE: {err}"))?;
        let scope = crate::frame_scope(get("--scope"), frame_id)?;
        if scope.is_some() && force_trace {
            return Err("E_ANALYSIS_SCOPE: Frame scope is incompatible with --kind trace".into());
        }
        scope.map_or(Selection::Default, Selection::Frame)
    };
    let evidence = produce(
        AnalyzeOptions {
            format,
            force_trace,
        },
        selection,
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

fn startup_query<'a>(get: &impl Fn(&str) -> Option<&'a str>) -> Result<startup::Query, String> {
    let option = |key| get(key).map(startup::mode).transpose();
    let number = |key, default, max| {
        get(key).map_or(Ok(default), |value| {
            value
                .parse::<usize>()
                .ok()
                .filter(|&n| n <= max && value.bytes().all(|b| b.is_ascii_digit()))
                .ok_or("offset or limit out of bounds")
        })
    };
    let parse = || -> Result<startup::Query, &'static str> {
        let limit = number("--limit", startup::DEFAULT_LIMIT, startup::MAX_LIMIT)?;
        if limit == 0 {
            return Err("startup run limit must be between 1 and 16");
        }
        Ok(startup::Query {
            requested: option("--requested-mode")?,
            observed: option("--observed-mode")?,
            missing: get("--missing-metric")
                .map(startup::missing_metric)
                .transpose()?,
            offset: number("--offset", 0, u32::MAX as usize)?,
            limit,
        })
    };
    parse().map_err(|e| format!("E_STARTUP_QUERY: {e}"))
}
