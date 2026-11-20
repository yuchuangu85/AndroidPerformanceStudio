//! Offline HAR projection. Keep only ranked numeric observations and the
//! parser's fixed phase dimensions; drop traffic strings at the parse boundary.
use aps_contracts::SourceDigest;
use aps_har_engine::PhaseSummary;
use serde_json::{Value, json};

struct SlowEntry {
    entry_index: u64,
    status: Option<u16>,
    duration_ms: f64,
}

pub(crate) struct HarDetails {
    slowest: Vec<SlowEntry>,
    phases: Vec<PhaseSummary>,
}

pub(super) fn parse(bytes: &[u8]) -> Result<(Value, HarDetails), String> {
    let report = aps_har_engine::summarize(bytes)
        .map_err(|_| "E_HAR_INPUT: malformed or over-budget HAR")?;
    let projection = json!({"kind":"network","inputFormat":"har","summary":{
        "entryCount":report.entry_count,"statusFamilies":report.status_families,
        "durationUnit":"ms","meanDurationMs":report.durations.mean_ms,"maxDurationMs":report.durations.max_ms,
        "knownDurationCount":report.durations.sample_count,"missingDurationCount":report.durations.missing_count,
        "invalidDurationCount":report.durations.invalid_count,
        "knownResponseBytes":report.response_bytes.total_bytes,"unknownResponseByteCount":report.response_bytes.unknown_count
    }});
    // The parser ranks at most 10 rows, but its private RequestSummary also
    // contains the method, protocol and start string. Do not keep it here.
    let slowest = report
        .slowest_requests
        .into_iter()
        .map(|entry| SlowEntry {
            entry_index: entry.entry_index,
            status: (entry.status != 0).then_some(entry.status),
            duration_ms: entry.duration_ms,
        })
        .collect();
    Ok((
        projection,
        HarDetails {
            slowest,
            phases: report.phases,
        },
    ))
}

impl HarDetails {
    pub(crate) fn page(
        &self,
        phases: bool,
        offset: usize,
        limit: usize,
        digest: SourceDigest,
    ) -> Result<Value, &'static str> {
        let total = if phases {
            self.phases.len()
        } else {
            self.slowest.len()
        };
        if !(1..=16).contains(&limit) || offset > total {
            return Err("invalid HAR follow-up page");
        }
        let end = (offset + limit).min(total);
        let items: Vec<_> = if phases {
            self.phases[offset..end].iter().map(|p| {
                let d = &p.durations;
                json!({
                    "id":format!("har-phase:{digest}:{}", p.kind),"label":"HAR phase timing",
                    "value":{"phase":p.kind,"parentPhase":p.parent_kind,"sampleCount":d.sample_count,
                        "missingCount":d.missing_count,"invalidCount":d.invalid_count,
                        "meanMs":d.mean_ms,"minMs":d.min_ms,"maxMs":d.max_ms}
                })
            }).collect()
        } else {
            self.slowest[offset..end].iter().map(|r| json!({
                "id":format!("har-slowest:{digest}:{}",r.entry_index),"label":"HAR slow request",
                "value":{"entryIndex":r.entry_index,"status":r.status,"durationMs":r.duration_ms}
            })).collect()
        };
        Ok(
            json!({"items":items,"total":total,"offset":offset,"nextOffset":(end < total).then_some(end)}),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ranked_pages_contain_only_source_bound_minimized_facts() {
        let rows: Vec<_> = (0..14).map(|i| json!({
            "startedDateTime":"private-date","time":if i < 2 { -1 } else { 100 },
            "request":{"method":"private-method","url":"https://private.example","headers":[{"name":"private-cookie"}]},
            "response":{"status":0,"httpVersion":"private-version","content":{"text":"private-body"}}
        })).collect();
        let bytes = serde_json::to_vec(&json!({"log":{"version":"1.2","entries":rows}})).unwrap();
        let digest = SourceDigest::hash_reader(&bytes[..], bytes.len() as u64).unwrap();
        let (summary, details) = parse(&bytes).unwrap();
        assert_eq!(summary["summary"]["knownDurationCount"], 12);
        let first = details.page(false, 0, 3, digest).unwrap();
        assert_eq!(first["total"], 10);
        assert_eq!(first["nextOffset"], 3);
        assert_eq!(first["items"][0]["id"], format!("har-slowest:{digest}:2"));
        assert_eq!(
            first["items"][0]["value"],
            json!({"entryIndex":2,"status":null,"durationMs":100.0})
        );
        assert!(!first.to_string().contains("private"));
        let final_page = details.page(false, 9, 16, digest).unwrap();
        assert_eq!(final_page["items"][0]["value"]["entryIndex"], 11);
        assert_eq!(final_page["nextOffset"], Value::Null);
        assert_eq!(
            details.page(false, 10, 1, digest).unwrap()["items"],
            json!([])
        );
        for (offset, limit) in [(11, 1), (0, 0), (0, 17), (usize::MAX, 1)] {
            assert!(details.page(false, offset, limit, digest).is_err());
        }
    }

    #[test]
    fn phase_stats_distinguish_missing_invalid_zero_and_include_parent() {
        let entries:Vec<_> = [json!({"ssl":3,"wait":0}),json!({"ssl":-2,"wait":-1}),json!({})].into_iter().map(|timings| json!({
            "startedDateTime":"x","request":{"method":"GET","url":"private"},"response":{"status":200},"timings":timings
        })).collect();
        let bytes =
            serde_json::to_vec(&json!({"log":{"version":"1.2","entries":entries}})).unwrap();
        let digest = SourceDigest::hash_reader(&bytes[..], bytes.len() as u64).unwrap();
        let (_, details) = parse(&bytes).unwrap();
        let page = details.page(true, 3, 3, digest).unwrap();
        assert_eq!(page["total"], 7);
        assert_eq!(
            page["items"][0]["value"],
            json!({"phase":"ssl","parentPhase":"connect",
            "sampleCount":1,"missingCount":1,"invalidCount":1,"meanMs":3.0,"minMs":3.0,"maxMs":3.0})
        );
        assert_eq!(page["items"][1]["value"]["meanMs"], Value::Null);
        assert_eq!(page["items"][2]["value"]["meanMs"], 0.0);
        assert!(
            details.page(false, 0, 1, digest).unwrap()["items"]
                .as_array()
                .unwrap()
                .is_empty()
        );
    }
}
