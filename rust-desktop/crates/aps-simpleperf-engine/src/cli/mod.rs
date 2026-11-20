//! Argument grammar for the one-shot CLI. The public Query::from_cli
//! remains available; validation of the resulting query belongs to the domain.
use crate::query::{Direction, Query, WeightType};

impl Query {
    /// The eight arguments after the path in the one-shot query command.
    pub fn from_cli(args: &[String]) -> Result<Self, String> {
        let [threads, events, start, end, direction, weight, offset, limit] = args else {
            return Err("query requires eight arguments after the path".into());
        };
        let thread_ids = csv(threads)?
            .into_iter()
            .map(|id| {
                let digits = id.strip_prefix('-').unwrap_or(id);
                decimal(digits, "thread ID")?;
                id.parse::<i32>()
                    .map_err(|_| format!("invalid thread ID {id}"))
            })
            .collect::<Result<_, _>>()?;
        let query = Self {
            thread_ids,
            event_types: csv(events)?.into_iter().map(str::to_owned).collect(),
            start: bound(start, "start")?,
            end: bound(end, "end")?,
            direction: match direction.as_str() {
                "forward" => Direction::Forward,
                "inverted" => Direction::Inverted,
                _ => return Err("direction must be forward or inverted".into()),
            },
            weight_type: match weight.as_str() {
                "samples" => WeightType::Samples,
                "events" => WeightType::EventCount,
                _ => return Err("weight must be samples or events".into()),
            },
            offset: usize::try_from(decimal(offset, "offset")?)
                .map_err(|_| "offset exceeds platform size")?,
            limit: usize::try_from(decimal(limit, "limit")?)
                .map_err(|_| "limit exceeds platform size")?,
        };
        query.validate()?;
        Ok(query)
    }
}

fn csv(value: &str) -> Result<Vec<&str>, String> {
    if value == "-" {
        return Ok(Vec::new());
    }
    let values: Vec<_> = value.split(',').collect();
    if values.iter().any(|v| v.is_empty() || *v == "-") {
        return Err("filter CSV contains an empty or wildcard element".into());
    }
    Ok(values)
}

fn decimal(value: &str, label: &str) -> Result<u64, String> {
    if value.is_empty() || !value.bytes().all(|b| b.is_ascii_digit()) {
        return Err(format!("{label} must be an unsigned decimal integer"));
    }
    value.parse().map_err(|_| format!("{label} exceeds u64"))
}

fn bound(value: &str, label: &str) -> Result<Option<u64>, String> {
    if value == "-" {
        Ok(None)
    } else {
        decimal(value, label).map(Some)
    }
}
