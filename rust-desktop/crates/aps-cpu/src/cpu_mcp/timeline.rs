//! Read-only sample-density evidence. No raw sample arrays or device actions.
use super::{read_text, read_u64, read_usize};
use aps_cpu::{CpuSession, MAX_TIMELINE_BUCKETS, MAX_TIMELINE_THREADS};
use serde_json::{json, Value};

const DEFAULT_BUCKETS: usize = 20;
const DEFAULT_THREADS: usize = 10;

pub(super) fn schema() -> Value {
    json!({
        "type":"object","additionalProperties":false,
        "properties":{
            "event":{"type":"string","minLength":1,"maxLength":2048},
            "startNs":{"type":"integer","minimum":0,"maximum":u64::MAX},
            "endNs":{"type":"integer","minimum":0,"maximum":u64::MAX},
            "bucketCount":{"type":"integer","minimum":1,"maximum":MAX_TIMELINE_BUCKETS,"default":DEFAULT_BUCKETS},
            "threadLimit":{"type":"integer","minimum":1,"maximum":MAX_TIMELINE_THREADS,"default":DEFAULT_THREADS}
        }
    })
}

pub(super) fn call(session: &CpuSession, args: &Value) -> Result<Value, (i32, String)> {
    let object = args
        .as_object()
        .ok_or((-32602, "timeline arguments must be an object".into()))?;
    if object.keys().any(|key| {
        !matches!(
            key.as_str(),
            "event" | "startNs" | "endNs" | "bucketCount" | "threadLimit"
        )
    }) {
        return Err((-32602, "unsupported timeline argument".into()));
    }
    let invalid = |e| (-32602, e);
    let event = read_text(object.get("event"), "event").map_err(invalid)?;
    if event.is_some_and(|e| !session.events().iter().any(|known| known == e)) {
        return Err((-32602, "event is absent from the bound CPU profile".into()));
    }
    let start = read_u64(object.get("startNs"), "startNs").map_err(invalid)?;
    let end = read_u64(object.get("endNs"), "endNs").map_err(invalid)?;
    let bucket_count =
        read_usize(args, "bucketCount", DEFAULT_BUCKETS, MAX_TIMELINE_BUCKETS).map_err(invalid)?;
    let thread_limit =
        read_usize(args, "threadLimit", DEFAULT_THREADS, MAX_TIMELINE_THREADS).map_err(invalid)?;
    let report = session
        .timeline_summary(event, start, end, bucket_count, thread_limit)
        .map_err(invalid)?;
    Ok(json!({
        "query":{"event":event,"startNs":start,"endNs":end,"bucketCount":bucket_count,"threadLimit":thread_limit},
        "sampleCount":report.sample_count,
        "emptyStackSampleCount":report.empty_stack_sample_count,
        "firstTimestampNs":report.first_ns, "lastTimestampNs":report.last_ns,
        "buckets":report.buckets.iter().map(|b| json!({
            "startNs":b.start_ns, "lastNsInclusive":b.last_ns_inclusive, "sampleCount":b.sample_count
        })).collect::<Vec<_>>(),
        "threadCount":report.thread_count, "omittedThreadCount":report.thread_count-report.threads.len(),
        "threads":report.threads.iter().map(|t| json!({
            "threadId":t.thread_id, "sampleCount":t.sample_count,
            "firstTimestampNs":t.first_ns,"lastTimestampNs":t.last_ns,
            "peakBucketIndex":t.peak_bucket_index,"peakBucketSampleCount":t.peak_bucket_sample_count
        })).collect::<Vec<_>>(),
        "metric":"observed_sample_count",
        "evidenceQuality":{"lossAttributionAvailable":false,"cpuUtilizationAvailable":false,"methodDurationAvailable":false,
            "deviceCaptureCompletenessVerified":false}
    }))
}
