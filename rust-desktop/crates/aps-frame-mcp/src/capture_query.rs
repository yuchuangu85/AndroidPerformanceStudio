//! Projections of selected, receipt-bound capture evidence. No trace execution.
//! Keep probe encoding in sync with aps-app's frame::perfetto::attach_probes.
use crate::capture::CaptureMeta;
use aps_frame::{FrameAnalysisResult, FrameDeadlineVerdict, FrameSample, FrameSource};
use serde_json::{Value, json};
use std::collections::{BTreeMap, BTreeSet};

const PER_FRAME: usize = 8;
const MAX_QUERY_MATCHES: usize = 4096;
const MAX_MARKERS_PER_RUN: usize = 256;
const INVALID_PROBE: &str = "Invalid stored FrameTimeline probe evidence";
const INVALID_SCOPE: &str = "Frame rows do not match the selected complete target-scoped capture";

struct Hit<'a> {
    marker: &'a str,
    tid: i32,
    role: &'a str,
    overlap_ns: i64,
    scheduled_ns: Option<i64>,
}

// The fixed probe query joins positive-duration ACTUAL slices. An expected-only
// row (or a duration inferred from unrelated timestamps) is not a queried window.
fn actual_window_ns(frame: &FrameSample) -> Option<i64> {
    let start = frame.actual_vsync_ns.filter(|t| *t >= 0)?;
    let duration = frame.total_duration_ns.filter(|n| *n > 0)?;
    start.checked_add(duration)?;
    Some(duration)
}

fn decode_hit(raw: &str, duration: i64, pid: i32) -> Option<Hit<'_>> {
    let mut parts = raw.split(' ');
    let marker = parts.next()?;
    let id = marker.strip_prefix("APS.Frame/")?;
    if id.len() != 16 || !id.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f')) {
        return None;
    }
    let tid: i32 = parts.next()?.parse().ok()?;
    let overlap_ns = parts.next()?.parse().ok()?;
    let running = parts.next()?.parse().ok()?;
    let role = parts.next()?;
    if parts.next().is_some()
        || pid <= 0
        || tid <= 0
        || !matches!(role, "Main" | "RenderThread" | "Other")
        || ((role == "Main") != (pid == tid))
        || !(1..=duration).contains(&overlap_ns)
        || !(-1..=overlap_ns).contains(&running)
    {
        return None;
    }
    Some(Hit {
        marker,
        tid,
        role,
        overlap_ns,
        scheduled_ns: (running >= 0).then_some(running),
    })
}

fn probe_rows(frame: &FrameSample) -> Result<(Vec<Hit<'_>>, usize), String> {
    let mut hits = Vec::new();
    let mut omitted = 0;
    // BTreeMap range order is also numeric slot order for the eight producer
    // slots. A hole, over-limit slot, or invalid row is not an empty result.
    for (key, raw) in frame.states.range("aps.trace.".to_owned()..) {
        let Some(slot) = key.strip_prefix("aps.trace.") else {
            break;
        };
        if slot == "omitted" {
            omitted = raw
                .parse::<usize>()
                .ok()
                .filter(|n| (1..=MAX_QUERY_MATCHES - PER_FRAME).contains(n))
                .ok_or(INVALID_PROBE)?;
        } else {
            if hits.len() >= PER_FRAME
                || slot.len() != 1
                || slot.as_bytes()[0] != b'0' + hits.len() as u8
            {
                return Err(INVALID_PROBE.into());
            }
            hits.push(
                decode_hit(
                    raw,
                    actual_window_ns(frame).ok_or(INVALID_PROBE)?,
                    frame.process_id.ok_or(INVALID_PROBE)?,
                )
                .ok_or(INVALID_PROBE)?,
            );
        }
    }
    if omitted > 0 && hits.len() != PER_FRAME {
        return Err(INVALID_PROBE.into());
    }
    Ok((hits, omitted))
}

pub(crate) fn overlaps(
    analysis: &FrameAnalysisResult,
    frame_id: u64,
    probe_query_complete: bool,
) -> Result<Value, String> {
    if !probe_query_complete {
        return Err(
            "The captured probe query did not complete; overlap evidence unavailable".into(),
        );
    }
    let frame = &analysis
        .frames
        .iter()
        .find(|f| f.sample.frame_id == frame_id)
        .ok_or("Frame ID is absent from the selected capture")?
        .sample;
    if frame.source != FrameSource::Perfetto || actual_window_ns(frame).is_none() {
        return Err("Frame does not have a measured actual FrameTimeline window".into());
    }
    let (hits, omitted) = probe_rows(frame)?;
    let items: Vec<_> = hits
        .iter()
        .map(|h| {
            json!({
                "marker": h.marker, "tid": h.tid, "threadRole": h.role,
                "overlapNs": h.overlap_ns, "observedScheduledNs": h.scheduled_ns
            })
        })
        .collect();
    Ok(json!({
        "frameId": frame_id.to_string(), "items":items,
        "coverage":{"returned":items.len(),"omitted":omitted,"complete":omitted == 0},
        "evidenceKind":"observed_trace_window_overlap_not_cause"
    }))
}

// These scope and percentile rules mirror the desktop's like-for-like run
// comparison (frame/probe_compare.rs). Rates are in millihertz, budgets in ns.
fn near(a: i64, b: i64) -> bool {
    a > 0 && b > 0 && a.abs_diff(b) <= (a.min(b) as u64 / 100).max(1)
}

fn signature(values: BTreeSet<i64>) -> Vec<i64> {
    let mut values_out = Vec::new();
    for value in values {
        if values_out.last().is_none_or(|&last| !near(last, value)) {
            values_out.push(value);
        }
    }
    values_out
}

fn compatible(left: &[i64], right: &[i64]) -> bool {
    left.len() == right.len() && left.iter().zip(right).all(|(&a, &b)| near(a, b))
}

fn rate_millihz(hz: f64) -> Result<i64, String> {
    if !hz.is_finite() || !(1.0..=1000.0).contains(&hz) {
        return Err("Invalid observed refresh rate in capture".into());
    }
    Ok((hz * 1000.0).round() as i64)
}

fn p95(values: &mut [i64]) -> Option<i64> {
    let index = (values.len() * 95).div_ceil(100).checked_sub(1)?;
    Some(*values.select_nth_unstable(index).1)
}

#[derive(Default)]
struct ProbeStats {
    windows: usize,
    overlaps: Vec<i64>,
    p95: Option<i64>,
}

struct Run<'a> {
    meta: &'a CaptureMeta,
    observed_rates: Vec<i64>,
    budgets: Vec<i64>,
    measured: usize,
    missed: usize,
    known: usize,
    p95: i64,
    actual_windows: usize,
    omitted: usize,
    probes: BTreeMap<&'a str, ProbeStats>,
}

impl<'a> Run<'a> {
    fn read(meta: &'a CaptureMeta, analysis: &'a FrameAnalysisResult) -> Result<Self, String> {
        if meta.frame_count != analysis.frames.len() || meta.pid <= 0 {
            return Err(INVALID_SCOPE.into());
        }
        let bound_session = format!("frame-{}", meta.report_digest);
        let mut rates = meta
            .refresh_rates_hz
            .iter()
            .map(|&hz| rate_millihz(hz))
            .collect::<Result<BTreeSet<_>, _>>()?;
        let mut budgets = BTreeSet::new();
        let mut durations = Vec::new();
        let mut probes = BTreeMap::<&str, ProbeStats>::new();
        let (mut missed, mut known, mut actual_windows, mut omitted, mut all_hits) =
            (0, 0, 0, 0, 0);
        for frame in &analysis.frames {
            let s = &frame.sample;
            if s.source != FrameSource::Perfetto
                || s.session_id != bound_session
                || s.package_name.as_deref() != Some(&meta.package_name)
                || s.process_id != Some(meta.pid)
                || s.states.get("aps.target.process") != Some(&meta.process_name)
            {
                return Err(INVALID_SCOPE.into());
            }
            actual_windows += usize::from(actual_window_ns(s).is_some());
            if meta.probe_query_complete {
                let (hits, dropped) = probe_rows(s)?;
                omitted += dropped;
                all_hits += hits.len() + dropped;
                if all_hits > MAX_QUERY_MATCHES {
                    return Err(
                        "Stored probe observations exceed the fixed-query coverage budget".into(),
                    );
                }
                let mut seen = BTreeSet::new();
                for h in hits {
                    let entry = probes.entry(h.marker).or_default();
                    entry.windows += usize::from(seen.insert(h.marker));
                    entry.overlaps.push(h.overlap_ns);
                }
                if probes.len() > MAX_MARKERS_PER_RUN {
                    return Err("Too many distinct probe markers in capture".into());
                }
            }
            let Some(duration) = s.resolved_duration_ns().filter(|n| *n > 0) else {
                continue;
            };
            durations.push(duration);
            if let Some(ns) = s.known_expected_duration_ns() {
                budgets.insert(ns);
            }
            if let Some(hz) = s.refresh_rate_hz {
                rates.insert(rate_millihz(hz)?);
            }
            match frame.deadline_verdict {
                FrameDeadlineVerdict::Missed => {
                    missed += 1;
                    known += 1;
                }
                FrameDeadlineVerdict::Met => known += 1,
                FrameDeadlineVerdict::Unknown => (),
            }
        }
        if durations.len() < 2 || known == 0 || budgets.is_empty() || rates.is_empty() {
            return Err("Comparison needs two measured frames, classified deadlines, observed budgets and refresh rates in each run".into());
        }
        if meta.probe_query_complete && omitted == 0 {
            for stats in probes.values_mut() {
                stats.p95 = p95(&mut stats.overlaps);
            }
        }
        Ok(Self {
            meta,
            observed_rates: signature(rates),
            budgets: signature(budgets),
            measured: durations.len(),
            missed,
            known,
            p95: p95(&mut durations).expect("nonempty durations"),
            actual_windows,
            omitted,
            probes,
        })
    }

    fn miss_rate(&self) -> f64 {
        self.missed as f64 / self.known as f64
    }

    fn summary(&self) -> Value {
        json!({
            "captureId":self.meta.capture_id, "reportDigest":self.meta.report_digest.to_hex(),
            "traceDigest":self.meta.trace_digest.to_hex(), "totalFrames":self.meta.frame_count,
            "measuredFrames":self.measured, "p95DurationNs":self.p95,
            "deadlineClassifiedFrames":self.known, "deadlineMissFrames":self.missed, "deadlineMissRate":self.miss_rate(),
            "probeEligibleActualFrames":self.actual_windows,
            "probeQueryComplete":self.meta.probe_query_complete,
            "probeCoverageComplete":self.meta.probe_query_complete && self.actual_windows > 0 && self.omitted == 0,
            "omittedProbeSpans":self.meta.probe_query_complete.then_some(self.omitted)
        })
    }

    fn marker(&self, name: &str) -> (Value, Option<i64>) {
        let entry = self.probes.get(name);
        let queried = self.meta.probe_query_complete && self.actual_windows > 0;
        let p95 = entry.and_then(|s| s.p95);
        (
            json!({
                "observedFrames":queried.then(|| entry.map_or(0,|s| s.windows)),
                "observedSpans":queried.then(|| entry.map_or(0,|s| s.overlaps.len())),
                "p95OverlapNs":p95
            }),
            p95,
        )
    }
}

pub(crate) fn compare(
    after: (&CaptureMeta, &FrameAnalysisResult),
    before: (&CaptureMeta, &FrameAnalysisResult),
    offset: usize,
    limit: usize,
) -> Result<Value, String> {
    if !(1..=100).contains(&limit) {
        return Err("Comparison page limit must be between 1 and 100".into());
    }
    let (a, b) = (before.0, after.0);
    if a.capture_id == b.capture_id
        || a.report_digest == b.report_digest
        || a.trace_digest == b.trace_digest
    {
        return Err("Baseline and after must be distinct captures, reports and traces".into());
    }
    // Android PIDs can change between launches; within each run exactly one
    // target PID must match its receipt. Only stable scope fields cross runs.
    if a.device_digest != b.device_digest
        || a.android_api != b.android_api
        || a.package_name != b.package_name
        || a.process_name != b.process_name
    {
        return Err("Baseline and after differ in device, API, package or process".into());
    }
    let baseline = Run::read(a, before.1)?;
    let after = Run::read(b, after.1)?;
    if !compatible(&baseline.observed_rates, &after.observed_rates)
        || !compatible(&baseline.budgets, &after.budgets)
    {
        return Err("Baseline and after differ in observed refresh rates or frame budgets".into());
    }
    let markers: BTreeSet<_> = baseline
        .probes
        .keys()
        .chain(after.probes.keys())
        .copied()
        .collect();
    let items: Vec<_> = markers.iter().skip(offset).take(limit).map(|name| {
        let (first, x) = baseline.marker(name);
        let (last, y) = after.marker(name);
        json!({"marker":name,"baseline": first,"after":last,"deltaP95OverlapNs": x.zip(y).map(|(x,y)| y-x)})
    }).collect();
    let next = offset.saturating_add(items.len());
    Ok(json!({
        "direction":"after_minus_baseline",
        "baseline":baseline.summary(), "after":after.summary(),
        "delta":{"p95DurationNs":after.p95-baseline.p95,
                 "deadlineMissPercentagePoints":100.0*(after.miss_rate()-baseline.miss_rate())},
        "items":items, "page":{"offset":offset,"limit":limit,"total":markers.len(),"returned":items.len(),
                              "nextOffset":(next<markers.len()).then_some(next),"hasMore":next<markers.len()},
        "evidenceKind":"paired_capture_observations_not_cause"
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_contracts::SourceDigest;
    use aps_frame::{FrameJankAnalyzer, FrameTimelineRow, map_timeline_rows};

    const MARKER: &str = "APS.Frame/0000000000000001";
    const OTHER: &str = "APS.Frame/0000000000000002";

    fn run(id: u8, duration: i64) -> (CaptureMeta, FrameAnalysisResult) {
        let report_digest = SourceDigest::from_bytes([id; 32]);
        let meta = CaptureMeta {
            capture_id: format!("capture-{id}"),
            report_digest,
            trace_digest: SourceDigest::from_bytes([id + 20; 32]),
            device_digest: SourceDigest::from_bytes([0xBB; 32]),
            android_api: 35,
            package_name: "fixture.app".into(),
            process_name: "fixture.app:ui".into(),
            pid: i32::from(id),
            refresh_rates_hz: vec![60.0],
            frame_count: 2,
            probe_query_complete: true,
        };
        let mut frames = map_timeline_rows(
            (0..2)
                .map(|id| FrameTimelineRow {
                    frame_id: id,
                    expected_ts: Some(100),
                    expected_dur: Some(25),
                    actual_ts: Some(100),
                    actual_dur: Some(duration),
                    surface_token: None,
                    layer_name: None,
                    jank_type: None,
                    jank_tag: None,
                    present_type: None,
                    process_name: None,
                    surface_flinger_jank_type: None,
                })
                .collect(),
        )
        .frames;
        for frame in &mut frames {
            frame.session_id = format!("frame-{report_digest}");
            frame.package_name = Some(meta.package_name.clone());
            frame.process_id = Some(meta.pid);
            frame
                .states
                .insert("aps.target.process".into(), meta.process_name.clone());
        }
        (meta, FrameJankAnalyzer::new().analyze(&frames))
    }

    fn hit(id: u8, marker: &str, ns: i64) -> String {
        format!("{marker} {id} {ns} -1 Main")
    }

    #[test]
    fn missing_query_or_missing_actual_window_is_not_zero_overlap() {
        let (_, mut run) = run(1, 20);
        assert!(overlaps(&run, 0, false).is_err());
        assert!(overlaps(&run, 2, true).is_err());
        let valid_empty = overlaps(&run, 0, true).unwrap();
        assert_eq!(
            valid_empty["coverage"],
            json!({"returned":0,"omitted":0,"complete":true})
        );
        run.frames[0].sample.actual_vsync_ns = None;
        assert!(overlaps(&run, 0, true).is_err());
        run.frames[0].sample.actual_vsync_ns = Some(i64::MAX);
        assert!(overlaps(&run, 0, true).is_err());
    }

    #[test]
    fn malformed_rows_holes_and_unknown_roles_never_project_empty_or_partial_success() {
        let (_, mut run) = run(1, 20);
        for (k, v) in [
            ("aps.trace.1", hit(1, MARKER, 10)),
            ("aps.trace.0", hit(1, "private/file", 10)),
            ("aps.trace.0", hit(2, MARKER, 10)),
            ("aps.trace.0", format!("{MARKER} 1 10 11 Main")),
            ("aps.trace.0", format!("{MARKER} 1 10 -2 Main")),
            ("aps.trace.0", format!("{MARKER} 2 10 5 thread-name")),
            ("aps.trace.0", hit(1, MARKER, 21)),
            ("aps.trace.omitted", "1".into()),
        ] {
            let sample = &mut run.frames[0].sample;
            sample.states.insert(k.into(), v);
            assert!(overlaps(&run, 0, true).is_err(), "{k}");
            run.frames[0].sample.states.remove(k);
        }
        let sample = &mut run.frames[0].sample;
        sample
            .states
            .insert("aps.trace.0".into(), hit(1, MARKER, 10));
        sample.states.insert(
            "aps.trace.1".into(),
            format!("{MARKER} 17 4 0 RenderThread"),
        );
        sample
            .states
            .insert("private-field".into(), "private/file".into());
        let result = overlaps(&run, 0, true).unwrap();
        assert!(result["items"][0]["observedScheduledNs"].is_null());
        assert_eq!(result["items"][1]["observedScheduledNs"], 0);
        assert!(!result.to_string().contains("private"));
    }

    #[test]
    fn comparison_paginates_marker_union_and_handles_incomplete_coverage() {
        let (before, mut a) = run(1, 30);
        let (mut after, mut b) = run(2, 20);
        let call =
            |after: &CaptureMeta, b: &FrameAnalysisResult, a: &FrameAnalysisResult, offset| {
                compare((after, b), (&before, a), offset, 1).unwrap()
            };
        a.frames[0]
            .sample
            .states
            .insert("aps.trace.0".into(), hit(1, MARKER, 12));
        a.frames[0]
            .sample
            .states
            .insert("aps.trace.1".into(), hit(1, MARKER, 13));
        b.frames[1]
            .sample
            .states
            .insert("aps.trace.0".into(), hit(2, MARKER, 5));
        b.frames[1]
            .sample
            .states
            .insert("aps.trace.1".into(), hit(2, OTHER, 3));
        let result = call(&after, &b, &a, 0);
        assert_eq!(
            result["delta"],
            json!({"p95DurationNs":-10,"deadlineMissPercentagePoints":-100.0})
        );
        assert_eq!(
            result["items"][0]["baseline"],
            json!({"observedFrames":1,"observedSpans":2,"p95OverlapNs":13})
        );
        assert_eq!(result["items"][0]["deltaP95OverlapNs"], -8);
        assert_eq!(result["page"]["nextOffset"], 1);
        let next = call(&after, &b, &a, 1);
        assert_eq!(next["items"][0]["marker"], OTHER);
        assert_eq!(next["items"][0]["baseline"]["observedFrames"], 0);
        assert!(next["items"][0]["deltaP95OverlapNs"].is_null());
        assert!(next["page"]["nextOffset"].is_null());
        let sample = &mut b.frames[1].sample;
        for i in 2..8 {
            sample
                .states
                .insert(format!("aps.trace.{i}"), hit(2, OTHER, 1));
        }
        sample.states.insert("aps.trace.omitted".into(), "4".into());
        let result = call(&after, &b, &a, 0);
        assert_eq!(result["after"]["omittedProbeSpans"], 4);
        assert_eq!(result["after"]["probeCoverageComplete"], false);
        assert_eq!(result["items"][0]["after"]["observedSpans"], 1);
        assert!(result["items"][0]["after"]["p95OverlapNs"].is_null());
        assert!(result["items"][0]["deltaP95OverlapNs"].is_null());

        after.probe_query_complete = false;
        let result = call(&after, &b, &a, 0);
        assert_eq!(result["delta"]["p95DurationNs"], -10);
        assert_eq!(result["after"]["probeQueryComplete"], false);
        assert!(result["items"][0]["after"]["observedFrames"].is_null());
        assert!(result["items"][0]["after"]["p95OverlapNs"].is_null());
    }

    #[test]
    fn comparison_refuses_unlike_runs_and_lost_classification() {
        let (before, a) = run(1, 30);
        for change in [
            "device", "api", "process", "scope", "trace", "id", "report", "budget", "refresh",
            "count", "unknown",
        ] {
            let (mut after, mut b) = run(2, 20);
            match change {
                "device" => after.device_digest = SourceDigest::from_bytes([33; 32]),
                "api" => after.android_api += 1,
                "process" => after.process_name = "other".into(),
                "scope" => b.frames[0].sample.process_id = Some(3),
                "trace" => after.trace_digest = before.trace_digest,
                "id" => after.capture_id = before.capture_id.clone(),
                "report" => after.report_digest = before.report_digest,
                "budget" => b.frames[1].sample.expected_duration_ns = Some(12),
                "refresh" => after.refresh_rates_hz = vec![90.0],
                "count" => after.frame_count = 1,
                "unknown" => b
                    .frames
                    .iter_mut()
                    .for_each(|f| f.deadline_verdict = FrameDeadlineVerdict::Unknown),
                _ => unreachable!(),
            }
            assert!(
                compare((&after, &b), (&before, &a), 0, 50).is_err(),
                "{change}"
            );
        }
    }
}
