use crate::{DurationMetric, MAX_INPUT_BYTES, MetricSource, StartupMode, StartupRun};
use std::collections::BTreeMap;

pub fn am_start(text: &str, package: &str, requested: StartupMode) -> Result<StartupRun, String> {
    validate_text(text)?;
    validate_package(package)?;
    let mut values = BTreeMap::new();
    let mut warnings = Vec::new();
    let mut complete = false;
    for line in text.lines().map(str::trim).filter(|line| !line.is_empty()) {
        if line.eq_ignore_ascii_case("Complete") {
            complete = true;
            continue;
        }
        if let Some((key, value)) = line.split_once(':') {
            let (key, value) = (key.trim(), value.trim());
            if key.eq_ignore_ascii_case("Warning") || key.eq_ignore_ascii_case("Error") {
                warnings.push(format!("{key}: {value}"));
            }
            if matches!(
                key,
                "Status"
                    | "LaunchState"
                    | "Activity"
                    | "ThisTime"
                    | "TotalTime"
                    | "WaitTime"
                    | "Complete"
            ) {
                if values.insert(key, value).is_some() {
                    return Err(
                        "Duplicate am-start launch fields; input may contain multiple launches"
                            .into(),
                    );
                }
            }
        } else if line.starts_with("Warning") || line.starts_with("Error") {
            warnings.push(line.into());
        }
    }
    if values.is_empty() && warnings.is_empty() {
        return Err("Input is not recognized am start -W output".into());
    }
    let duration = |key: &str| -> Result<Option<u64>, String> {
        values
            .get(key)
            .map(|value| parse_duration(value))
            .transpose()
    };
    let total_time_ms = duration("TotalTime")?;
    if total_time_ms.is_none() {
        warnings.push("am start -W did not report TotalTime.".into());
    }
    if values
        .get("Status")
        .is_some_and(|value| !value.eq_ignore_ascii_case("ok"))
    {
        warnings.push("Activity Manager reported non-ok status.".into());
    }
    let activity = values.get("Activity").map(|value| (*value).to_owned());
    if activity
        .as_ref()
        .is_some_and(|activity| !activity.starts_with(&format!("{package}/")))
    {
        return Err("Activity Manager result belongs to a different target package".into());
    }
    Ok(StartupRun {
        iteration: 1,
        id: "am-start-1".into(),
        package: Some(package.into()),
        requested_mode: requested,
        observed_mode: values
            .get("LaunchState")
            .map_or(StartupMode::Unknown, |value| StartupMode::parse(value)),
        status: values.get("Status").map(|value| (*value).to_owned()),
        activity,
        this_time_ms: duration("ThisTime")?,
        total_time_ms,
        wait_time_ms: duration("WaitTime")?,
        complete: complete
            || values
                .get("Complete")
                .is_some_and(|value| value.eq_ignore_ascii_case("true")),
        ttid: DurationMetric::missing(
            "No platform Displayed event was supplied; am-start TotalTime is not substituted.",
        ),
        ttfd: DurationMetric::missing("No platform Fully drawn event was supplied."),
        warnings,
        imported_fields: BTreeMap::new(),
    })
}

pub fn event_log(text: &str, package: &str) -> Result<(DurationMetric, DurationMetric), String> {
    validate_text(text)?;
    validate_package(package)?;
    let mut displayed = None;
    let mut fully_drawn = None;
    let target = format!("{package}/");
    for line in text.lines() {
        // Exact package component boundary avoids dev.app matching dev.app.other.
        let matching_component = line.match_indices(&target).any(|(index, _)| {
            index == 0
                || !line.as_bytes()[index - 1].is_ascii_alphanumeric()
                    && !matches!(line.as_bytes()[index - 1], b'.' | b'_')
        });
        if !matching_component {
            continue;
        }
        let lower = line.to_ascii_lowercase();
        let is_fully = lower.contains("wm_fully_drawn_time") || lower.contains("fully drawn");
        let is_displayed = lower.contains("wm_activity_launch_time") || lower.contains("displayed");
        if !is_fully && !is_displayed {
            continue;
        }
        let duration =
            if lower.contains("wm_fully_drawn_time") || lower.contains("wm_activity_launch_time") {
                let payload = line
                    .rsplit_once('[')
                    .and_then(|(_, payload)| payload.split_once(']').map(|(payload, _)| payload))
                    .ok_or("Malformed startup event payload")?;
                let value = payload
                    .rsplit(',')
                    .next()
                    .ok_or("Missing startup event duration")?
                    .trim();
                parse_duration(value)?
            } else {
                let value = line
                    .rsplit_once('+')
                    .map(|(_, duration)| duration.split_whitespace().next().unwrap_or(""))
                    .ok_or("Missing startup displayed duration")?;
                parse_human_duration(value)?
            };
        let slot = if is_fully {
            &mut fully_drawn
        } else {
            &mut displayed
        };
        if slot.replace(duration).is_some() {
            return Err(
                "Multiple target startup events; provide a log scoped to one launch".into(),
            );
        }
    }
    Ok((
        displayed.map_or_else(
            || DurationMetric::missing("No target Displayed event was observed."),
            |value| DurationMetric::observed(value, MetricSource::EventLog),
        ),
        fully_drawn.map_or_else(
            || DurationMetric::missing("No target Fully drawn event was observed."),
            |value| DurationMetric::observed(value, MetricSource::EventLog),
        ),
    ))
}

pub fn parse_duration(value: &str) -> Result<u64, String> {
    let value = value
        .trim()
        .strip_suffix("ms")
        .unwrap_or(value.trim())
        .trim();
    if value.is_empty() || !value.bytes().all(|value| value.is_ascii_digit()) {
        return Err("Startup duration must be an unsigned integer in milliseconds".into());
    }
    value
        .parse()
        .map_err(|_| "Startup duration exceeds u64".into())
}

fn parse_human_duration(value: &str) -> Result<u64, String> {
    let mut remaining = value;
    let mut total = 0u64;
    let mut previous_multiplier = u64::MAX;
    if remaining.is_empty() {
        return Err("Empty startup duration".into());
    }
    while !remaining.is_empty() {
        let digits = remaining.bytes().take_while(u8::is_ascii_digit).count();
        if digits == 0 {
            return Err("Invalid startup duration".into());
        }
        let number = parse_duration(&remaining[..digits])?;
        remaining = &remaining[digits..];
        let (unit, multiplier) = if remaining.starts_with("ms") {
            (2, 1u64)
        } else if remaining.starts_with('s') {
            (1, 1000u64)
        } else if remaining.starts_with('m') {
            (1, 60_000u64)
        } else {
            return Err("Unsupported startup duration unit".into());
        };
        if multiplier >= previous_multiplier {
            return Err("Startup duration units are duplicated or out of order".into());
        }
        previous_multiplier = multiplier;
        remaining = &remaining[unit..];
        total = total
            .checked_add(
                number
                    .checked_mul(multiplier)
                    .ok_or("Startup duration overflows")?,
            )
            .ok_or("Startup duration overflows")?;
    }
    Ok(total)
}

pub fn validate_text(text: &str) -> Result<(), String> {
    if text.len() as u64 > MAX_INPUT_BYTES || text.lines().any(|line| line.len() > 1024 * 1024) {
        return Err("Startup text exceeds parser budget".into());
    }
    if text
        .chars()
        .any(|value| value.is_control() && !matches!(value, '\n' | '\r' | '\t'))
    {
        return Err("Startup text contains control characters".into());
    }
    Ok(())
}

fn validate_package(package: &str) -> Result<(), String> {
    if package.is_empty()
        || package.len() > 1024
        || !package
            .bytes()
            .all(|value| value.is_ascii_alphanumeric() || matches!(value, b'.' | b'_'))
    {
        return Err("Invalid target package".into());
    }
    Ok(())
}
