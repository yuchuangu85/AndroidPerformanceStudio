//! Terminal-only argument and publication policy for offline benchmark comparison.
use super::output::{available_destination, write_json};
use crate::analysis::benchmark::{DEFAULT_LIMIT, MAX_LIMIT};
use crate::benchmark_source::{BoundPair, threshold};
use std::{collections::BTreeMap, path::Path};

const USAGE: &str = "usage: aps benchmark compare --baseline BEFORE.json --current AFTER.json
              [--threshold-percent 0..100] [--offset N] [--limit 1..16]
              [--evidence-out NEW.json]";

pub(super) fn run(mut args: impl Iterator<Item = String>) -> Result<i32, String> {
    match args.next().as_deref() {
        None | Some("--help" | "-h") => {
            println!("{USAGE}");
            return Ok(0);
        }
        Some("compare") => (),
        _ => return Err(USAGE.into()),
    }
    let mut opts = BTreeMap::new();
    while let Some(flag) = args.next() {
        if matches!(flag.as_str(), "--help" | "-h") && opts.is_empty() && args.next().is_none() {
            println!("{USAGE}");
            return Ok(0);
        }
        if !matches!(
            flag.as_str(),
            "--baseline"
                | "--current"
                | "--threshold-percent"
                | "--offset"
                | "--limit"
                | "--evidence-out"
        ) {
            return Err("E_BENCHMARK_USAGE: unknown comparison option".into());
        }
        let value = args
            .next()
            .filter(|value| !value.is_empty() && !value.starts_with("--"))
            .ok_or("E_BENCHMARK_USAGE: missing option value")?;
        if opts.insert(flag, value).is_some() {
            return Err("E_BENCHMARK_USAGE: duplicate comparison option".into());
        }
    }
    let get = |flag: &str| opts.get(flag).map(String::as_str);
    let before = Path::new(get("--baseline").ok_or("E_BENCHMARK_USAGE: missing --baseline")?);
    let after = Path::new(get("--current").ok_or("E_BENCHMARK_USAGE: missing --current")?);
    let destination = get("--evidence-out").map(Path::new);
    destination.map(available_destination).transpose()?;
    let threshold = get("--threshold-percent").map(threshold).transpose()?;
    let number = |key, default, min, max| {
        get(key).map_or(Ok(default), |text| {
            text.parse::<usize>()
                .ok()
                .filter(|value| {
                    (min..=max).contains(value) && text.bytes().all(|b| b.is_ascii_digit())
                })
                .ok_or("E_BENCHMARK_PAGE: invalid offset or limit")
        })
    };
    let offset = number("--offset", 0, 0, u32::MAX as usize)?;
    let limit = number("--limit", DEFAULT_LIMIT, 1, MAX_LIMIT)?;
    let pair = BoundPair::open(before, after, threshold)?;
    let page = pair.page(offset, limit)?;
    if let Some(destination) = destination {
        write_json(&page, Some(destination))?;
    }
    write_json(&page, None)?;
    Ok(pair.exit_code())
}
