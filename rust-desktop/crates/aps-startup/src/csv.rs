use crate::{DurationMetric, MAX_RUNS, MetricSource, StartupMode, StartupRun, parser};
use std::collections::{BTreeMap, HashSet};

pub fn parse(text: &str) -> Result<Vec<StartupRun>, String> {
    parser::validate_text(text)?;
    let mut rows = records(text)?;
    if rows.is_empty() {
        return Err("Startup CSV has no header".into());
    }
    let header = rows.remove(0);
    let unique: HashSet<_> = header.iter().collect();
    if unique.len() != header.len() || header.iter().any(String::is_empty) {
        return Err("Duplicate or empty Startup CSV column".into());
    }
    for required in [
        "iteration",
        "runId",
        "requestedType",
        "observedType",
        "displayedTimeMs",
        "fullyDrawnTimeMs",
    ] {
        if !header.iter().any(|value| value == required) {
            return Err(format!("Startup CSV lacks {required}"));
        }
    }
    let mut runs = Vec::new();
    let mut ids = HashSet::new();
    for row in rows {
        if row.len() != header.len() {
            return Err("Startup CSV row has inconsistent columns".into());
        }
        let fields: BTreeMap<_, _> = header.iter().cloned().zip(row).collect();
        let field = |key: &str| fields.get(key).map_or("", String::as_str);
        let iteration = field("iteration")
            .parse::<u32>()
            .ok()
            .filter(|iteration| *iteration > 0)
            .ok_or("Invalid startup iteration")?;
        let id = field("runId").to_owned();
        if id.is_empty() || id.len() > 4096 || !ids.insert(id.clone()) {
            return Err("Empty, duplicate or oversized startup run ID".into());
        }
        let metric = |name: &str, unavailable: &str| -> Result<DurationMetric, String> {
            if field(name).is_empty() {
                let reason = if field(unavailable).is_empty() {
                    "Metric absent from imported startup export"
                } else {
                    field(unavailable)
                };
                Ok(DurationMetric::missing(reason))
            } else {
                Ok(DurationMetric::observed(
                    parser::parse_duration(field(name))?,
                    MetricSource::ImportedCsv,
                ))
            }
        };
        let duration = |name: &str| -> Result<Option<u64>, String> {
            if field(name).is_empty() {
                Ok(None)
            } else {
                parser::parse_duration(field(name)).map(Some)
            }
        };
        let mut warnings = Vec::new();
        if !field("warnings").is_empty() {
            warnings.push(field("warnings").into());
        }
        let requested_mode = StartupMode::parse(field("requestedType"));
        let observed_mode = StartupMode::parse(field("observedType"));
        if observed_mode == StartupMode::Unknown {
            warnings
                .push("Observed launch mode is unknown; requested mode is not substituted.".into());
        }
        let ttid = metric("displayedTimeMs", "ttidUnavailableReason")?;
        let ttfd = metric("fullyDrawnTimeMs", "ttfdUnavailableReason")?;
        if let (Some(ttid), Some(ttfd)) = (ttid.milliseconds, ttfd.milliseconds)
            && ttfd < ttid
        {
            return Err("Imported fully drawn duration precedes displayed duration".into());
        }
        runs.push(StartupRun {
            iteration,
            id,
            package: None,
            requested_mode,
            observed_mode,
            status: None,
            activity: None,
            this_time_ms: duration("thisTimeMs")?,
            total_time_ms: duration("totalTimeMs")?,
            wait_time_ms: duration("waitTimeMs")?,
            complete: false,
            ttid,
            ttfd,
            warnings,
            imported_fields: fields,
        });
    }
    Ok(runs)
}

fn records(text: &str) -> Result<Vec<Vec<String>>, String> {
    let mut rows = Vec::new();
    let mut row = Vec::new();
    let mut field = String::new();
    let mut characters = text.trim_start_matches('\u{feff}').chars().peekable();
    let mut quoted = false;
    let mut closed_quote = false;
    let mut started = false;
    while let Some(character) = characters.next() {
        if quoted {
            if character == '"' {
                if characters.peek() == Some(&'"') {
                    characters.next();
                    field.push('"');
                } else {
                    quoted = false;
                    closed_quote = true;
                }
            } else {
                field.push(character);
            }
        } else {
            match character {
                '"' if field.is_empty() && !started && !closed_quote => {
                    quoted = true;
                    started = true;
                }
                '"' => return Err("Unexpected quote in Startup CSV".into()),
                ',' => {
                    row.push(std::mem::take(&mut field));
                    started = false;
                    closed_quote = false;
                }
                '\n' | '\r' => {
                    if character == '\r' && characters.peek() == Some(&'\n') {
                        characters.next();
                    }
                    if started || !field.is_empty() || !row.is_empty() || closed_quote {
                        row.push(std::mem::take(&mut field));
                        rows.push(std::mem::take(&mut row));
                    }
                    started = false;
                    closed_quote = false;
                }
                _ if closed_quote => return Err("Characters follow a closing CSV quote".into()),
                _ => {
                    field.push(character);
                    started = true;
                }
            }
        }
        if field.len() > 1024 * 1024 || row.len() > 256 || rows.len() > MAX_RUNS + 1 {
            return Err("Startup CSV exceeds field/row budget".into());
        }
    }
    if quoted {
        return Err("Unterminated quoted Startup CSV field".into());
    }
    if started || !field.is_empty() || !row.is_empty() || closed_quote {
        row.push(field);
        rows.push(row);
    }
    Ok(rows)
}
