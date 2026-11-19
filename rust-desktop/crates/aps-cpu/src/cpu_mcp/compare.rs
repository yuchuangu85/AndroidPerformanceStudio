//! Whole-profile B-A comparison of *observed weights*, not a regression test.
//! Numeric IDs and timestamps are local to a capture. Match only unambiguous
//! producer labels, using the original resource string before redaction.
use super::{query_from_args, sanitize_resource, MAX_PAGE_LIMIT};
use aps_cpu::{CpuSession, Query, TopFunction, WeightType, MAX_PAGE_SIZE};
use serde_json::{json, Value};
use std::cmp::Reverse;
use std::collections::BTreeMap;

type Error = (i32, String);
const MAX_FUNCTIONS_PER_PROFILE: usize = 200_000;

pub(super) fn schema() -> Value {
    json!({
        "type":"object","additionalProperties":false,
        "properties":{
            "event":{"type":"string","minLength":1,"maxLength":2048},
            "weightType":{"type":"string","enum":["samples","events"],"default":"samples"},
            "offset":{"type":"integer","minimum":0},
            "limit":{"type":"integer","minimum":1,"maximum":MAX_PAGE_LIMIT,"default":50}
        }
    })
}

#[derive(Default)]
struct Side {
    count: usize,
    id: Option<String>,
    weight: u64,
    self_weight: u64,
    sample_count: u64,
}

impl Side {
    fn observe(&mut self, id: String, weight: u64, self_weight: u64, sample_count: u64) {
        self.count += 1;
        if self.count == 1 {
            self.id = Some(id);
            self.weight = weight;
            self.self_weight = self_weight;
            self.sample_count = sample_count;
        }
        // If multiple source-local functions have these exact labels, the key
        // is ambiguous. Never sum or arbitrarily choose one of those functions.
    }
}

// Key is (resource, symbol), never the sanitized basename or a profile-local ID.
type Groups = BTreeMap<(String, String), [Side; 2]>;

#[derive(Default)]
struct Coverage {
    total: usize,
    unidentifiable: usize,
    ambiguous: usize,
}

impl Coverage {
    fn json(&self) -> Value {
        json!({"totalFunctions":self.total,"unidentifiableFunctions":self.unidentifiable,
            "ambiguousFunctions":self.ambiguous,
            "includedFunctions":self.total - self.unidentifiable - self.ambiguous})
    }
}

fn usable(text: &str) -> bool {
    !text.trim().is_empty()
        && text.trim() == text
        && !text.chars().any(char::is_control)
        && ![
            "unknown",
            "<unknown>",
            "<unknown-symbol>",
            "<unknown-file>",
            "<gecko-profile>",
            "[unknown]",
            "??",
            "?",
        ]
        .iter()
        .any(|unknown| text.eq_ignore_ascii_case(unknown))
        && !text.starts_with("<unknown-file:")
        && !text.starts_with("[anon:")
        && !text
            .strip_prefix("0x")
            .or_else(|| text.strip_prefix("0X"))
            .is_some_and(|digits| {
                !digits.is_empty() && digits.bytes().all(|b| b.is_ascii_hexdigit())
            })
}

fn ingest(row: TopFunction, side: usize, groups: &mut Groups, coverage: &mut Coverage) {
    coverage.total += 1;
    if !usable(&row.name) || !usable(&row.resource) || row.resource.ends_with(['/', '\\']) {
        coverage.unidentifiable += 1;
        return;
    }
    groups.entry((row.resource, row.name)).or_default()[side].observe(
        row.id,
        row.inclusive_weight,
        row.self_weight,
        row.sample_count,
    );
}

fn collect(
    session: &mut CpuSession,
    query: &Query,
    side: usize,
    groups: &mut Groups,
    coverage: &mut Coverage,
) -> Result<(), Error> {
    let mut offset = 0;
    loop {
        // top_functions pages the same cached complete projection, without
        // cloning and resorting the whole profile for each page.
        let page = session
            .top_functions(query, offset, MAX_PAGE_SIZE)
            .map_err(|error| (-32001, error))?;
        if page.total > MAX_FUNCTIONS_PER_PROFILE {
            return Err((
                -32002,
                "Complete comparison exceeds the per-profile function budget".into(),
            ));
        }
        offset += page.rows.len();
        for row in page.rows {
            ingest(row, side, groups, coverage);
        }
        if offset == page.total {
            return Ok(());
        }
    }
}

pub(super) fn call(
    before: &mut CpuSession,
    after: &mut CpuSession,
    args: &Value,
) -> Result<Value, Error> {
    let object = args
        .as_object()
        .ok_or_else(|| (-32602, "Comparison arguments must be an object".into()))?;
    if object
        .keys()
        .any(|key| !matches!(key.as_str(), "event" | "weightType" | "limit" | "offset"))
    {
        return Err((-32602, "Unknown comparison argument".into()));
    }
    let query = query_from_args(args, MAX_PAGE_LIMIT).map_err(|error| (-32602, error))?;
    let event = query.event_types.iter().next();
    if let Some(event) = event {
        // event#N is the fallback for a numeric ID in *one* capture.
        if event
            .strip_prefix("event#")
            .is_some_and(|n| !n.is_empty() && n.bytes().all(|b| b.is_ascii_digit()))
            || !before.events().contains(event)
            || !after.events().contains(event)
        {
            return Err((
                -32602,
                "Comparison requires the same named event in both profiles".into(),
            ));
        }
    }
    if before.summary().trace_offcpu != after.summary().trace_offcpu {
        return Err((
            -32001,
            "Cannot compare profiles with different trace-offcpu modes".into(),
        ));
    }
    let mut groups = Groups::new();
    let mut coverage: [Coverage; 2] = Default::default();
    collect(before, &query, 0, &mut groups, &mut coverage[0])?;
    collect(after, &query, 1, &mut groups, &mut coverage[1])?;
    let mut rows = Vec::with_capacity(groups.len());
    for ((resource, name), [a, b]) in groups {
        if a.count > 1 || b.count > 1 {
            coverage[0].ambiguous += a.count;
            coverage[1].ambiguous += b.count;
        } else {
            rows.push((resource, name, a, b));
        }
    }
    rows.sort_unstable_by(|(r1, n1, a1, b1), (r2, n2, a2, b2)| {
        (Reverse(a1.weight.abs_diff(b1.weight)), r1, n1).cmp(&(
            Reverse(a2.weight.abs_diff(b2.weight)),
            r2,
            n2,
        ))
    });
    let total = rows.len();
    let rows = rows
        .into_iter()
        .skip(query.offset)
        .take(query.limit)
        .map(|(resource, name, a, b)| project_row(&resource, &name, a, b))
        .collect::<Result<Vec<_>, _>>()?;
    Ok(json!({
        "query":{"event":event,"weightType":if query.weight_type == WeightType::Samples {"samples"} else {"events"},
            "offset":query.offset,"limit":query.limit},
        "total":total,"offset":query.offset,"limit":query.limit,"hasMore":query.offset.saturating_add(rows.len())<total,
        "rows":rows,"coverage":{"before":coverage[0].json(),"after":coverage[1].json()},
        "direction":"current_minus_baseline","resourcePathsRedacted":true,
        "eventScope":if event.is_some() {"selected_named_event"} else {"all_recorded_samples_in_each_profile"},
        "alignment":"unique_exact_reported_resource_and_symbol","measurementComparabilityVerified":false,
        "limitations":["Raw observed weights and sample counts; no normalization by recording duration, sampling frequency or lost samples.",
            "Without an event filter, samples pool all recorded event types separately in each profile; these populations may differ.",
            "A one-sided label means not observed under that label, not code added or removed. Label matches do not prove identical binaries, code, or device conditions."]
    }))
}

fn project_row(resource: &str, name: &str, a: Side, b: Side) -> Result<Value, Error> {
    let status = match (a.id.is_some(), b.id.is_some()) {
        (true, true) => "matched",
        (false, true) => "after_only",
        _ => "before_only",
    };
    // Domain projections bound each weight and count to signed i64.
    let delta = |before, after| {
        i64::try_from(i128::from(after) - i128::from(before))
            .map_err(|_| (-32001, "CPU comparison delta exceeds signed i64".to_owned()))
    };
    Ok(json!({
        "name":if name.contains(['/', '\\']) {"<path-like symbol>"} else {name},
        "resource":sanitize_resource(resource),"matchStatus":status,
        "beforeFunctionId":a.id,"afterFunctionId":b.id,
        "beforeInclusiveWeight":a.weight,"afterInclusiveWeight":b.weight,"deltaInclusiveWeight":delta(a.weight, b.weight)?,
        "beforeSelfWeight":a.self_weight,"afterSelfWeight":b.self_weight,"deltaSelfWeight":delta(a.self_weight, b.self_weight)?,
        "beforeSampleCount":a.sample_count,"afterSampleCount":b.sample_count,"deltaSampleCount":delta(a.sample_count, b.sample_count)?
    }))
}

#[cfg(test)]
#[path = "../../tests/engine-fixtures/mod.rs"]
mod fixtures;

#[cfg(test)]
mod tests {
    use super::*;

    fn row(id: &str, name: &str, resource: &str, weight: u64) -> TopFunction {
        TopFunction {
            id: id.into(),
            name: name.into(),
            resource: resource.into(),
            inclusive_weight: weight,
            self_weight: 0,
            sample_count: 0,
            self_sample_count: 0,
            thread_count: 0,
        }
    }

    #[test]
    fn ambiguous_same_labels_never_collapse_into_a_match() {
        let mut groups = Groups::new();
        let mut coverage = Coverage::default();
        for (id, resource, side) in [
            ("1", "/a/libx", 0),
            ("2", "/a/libx", 0),
            ("3", "/a/libx", 1),
            ("1", "/b/libx", 1),
        ] {
            ingest(
                row(id, "foo", resource, 1),
                side,
                &mut groups,
                &mut coverage,
            );
        }
        assert_eq!(groups.len(), 2);
        let counts: Vec<_> = groups.values().map(|[a, b]| (a.count, b.count)).collect();
        assert_eq!(counts, [(2, 1), (0, 1)]);
    }

    #[test]
    fn only_unique_full_labels_survive_comparison_and_coverage_accounts_for_every_function() {
        use aps_simpleperf_engine::simpleperf::{FileRecord, Record, SimpleperfStream};
        let make = |definitions: &[(u32, &str, &str)]| {
            let mut records = Vec::new();
            for &(id, path, name) in definitions {
                records.push(Record::File(FileRecord {
                    id,
                    path: path.into(),
                    symbols: vec![name.into()],
                }));
                let mut rec = fixtures::sample(5, 3, 0, 1, &[0]);
                if let Record::Sample(s) = &mut rec {
                    s.callchain[0].file_id = id;
                }
                records.push(rec);
            }
            CpuSession::from_bytes(&fixtures::encoded(&SimpleperfStream {
                version: 1,
                records,
            }))
            .unwrap()
        };
        let mut a = make(&[
            (1, "/a/libx", "foo"),
            (2, "/a/libx", "foo"),
            (3, "/b/libx", "foo"),
            (4, "/a/libx", "<unknown-symbol>"),
            (5, "/a/ok", "same"),
        ]);
        let mut b = make(&[
            (3, "/a/libx", "foo"),
            (1, "/c/libx", "foo"),
            (77, "/a/ok", "same"),
        ]);
        let result = call(&mut a, &mut b, &json!({})).unwrap();
        assert_eq!(
            result["coverage"]["before"],
            json!({"totalFunctions":5,"includedFunctions":2,"ambiguousFunctions":2,"unidentifiableFunctions":1})
        );
        assert_eq!(
            result["coverage"]["after"],
            json!({"totalFunctions":3,"includedFunctions":2,"ambiguousFunctions":1,"unidentifiableFunctions":0})
        );
        assert_eq!(result["total"], 3);
        let rows = result["rows"].as_array().unwrap();
        assert_eq!(rows[0]["matchStatus"], "before_only");
        assert_eq!(rows[1]["matchStatus"], "after_only");
        assert_eq!(rows[0]["resource"], rows[1]["resource"]);
        assert_eq!(rows[0]["afterFunctionId"], Value::Null);
        assert_eq!(rows[0]["deltaInclusiveWeight"], -1);
        assert_eq!(rows[1]["deltaInclusiveWeight"], 1);
        assert_eq!(rows[2]["deltaInclusiveWeight"], 0);
        assert_ne!(rows[2]["beforeFunctionId"], rows[2]["afterFunctionId"]);
        assert!(!result.to_string().contains("/a/"));
        assert_eq!(
            call(
                &mut a,
                &mut b,
                &json!({"weightType":"events","event":"event#0"})
            )
            .unwrap_err()
            .0,
            -32602
        );
    }

    #[test]
    fn unresolved_and_synthetic_labels_are_not_alignment_keys() {
        let mut groups = Groups::new();
        let mut coverage = Coverage::default();
        for (symbol, resource) in [
            ("", "lib"),
            ("<unknown-symbol>", "/a"),
            ("foo", "<unknown-file:7>"),
            ("foo", "<gecko-profile>"),
            ("0x123", "lib"),
            ("??", "lib"),
            ("foo", "/"),
        ] {
            ingest(
                row("1", symbol, resource, 100),
                0,
                &mut groups,
                &mut coverage,
            );
        }
        assert_eq!(coverage.total, coverage.unidentifiable);
        assert!(groups.is_empty());
    }
}
