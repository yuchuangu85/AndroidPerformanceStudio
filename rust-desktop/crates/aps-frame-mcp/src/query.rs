use crate::ANALYSIS_POLICY_VERSION;
use aps_analysis::frame::{IMPORT_WARNING, coverage, labels, parse_source, summary};
use aps_frame::analysis::{
    AnalyzedFrame, FrameAnalysisResult, FrameDeadlineVerdict, FrameJankAnalyzer, is_problem_frame,
};
use aps_frame::model::{FrameSample, FrameSource};
use serde_json::{Map, Value, json};
use std::collections::BTreeMap;

const MAX_MEMBERS: usize = 20;
const DEFAULT_LIMIT: usize = 50;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct Filter {
    source: Option<FrameSource>,
    window: Option<String>,
    start: Option<i64>,
    end: Option<i64>,
    view: View,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
enum View {
    #[default]
    All,
    Problems,
    DeadlineMisses,
    PlatformJank,
    JankStats,
}

impl View {
    fn as_str(self) -> &'static str {
        match self {
            Self::All => "all",
            Self::Problems => "problems",
            Self::DeadlineMisses => "deadline_misses",
            Self::PlatformJank => "platform_jank",
            Self::JankStats => "jank_stats",
        }
    }
}

impl Filter {
    fn matches(&self, frame: &AnalyzedFrame) -> bool {
        let sample = &frame.sample;
        self.source.is_none_or(|source| sample.source == source)
            && self
                .window
                .as_ref()
                .is_none_or(|window| sample.window_id.as_ref() == Some(window))
            && self
                .start
                .is_none_or(|start| sample.intended_vsync_ns.is_some_and(|ns| ns >= start))
            && self
                .end
                .is_none_or(|end| sample.intended_vsync_ns.is_some_and(|ns| ns < end))
            && match self.view {
                View::All => true,
                View::Problems => is_problem_frame(frame),
                View::DeadlineMisses => frame.deadline_verdict == FrameDeadlineVerdict::Missed,
                View::PlatformJank => sample.platform_jank == Some(true),
                View::JankStats => sample.jank_stats_jank == Some(true),
            }
    }

    fn json(&self) -> Value {
        json!({
            "source": self.source.map(|source| source.as_name()),
            "windowId": self.window,
            "startNs": self.start,
            "endNs": self.end,
            "view": self.view.as_str()
        })
    }
}

pub(crate) struct Arguments {
    pub filter: Filter,
    pub offset: usize,
    pub limit: usize,
    member_offset: usize,
    pub frame_id: Option<u64>,
    pub session_id: Option<String>,
}

impl Arguments {
    pub(crate) fn parse(name: &str, value: &Value) -> Result<Self, String> {
        let paged = match name {
            "frame_session_summary" | "frame_detail" | "frame_trace_overlaps" => false,
            "frame_list"
            | "frame_problem_clusters"
            | "frame_attributions"
            | "frame_session_compare" => true,
            _ => return Err("Unknown frame tool".into()),
        };
        let object = value
            .as_object()
            .ok_or("Tool arguments must be an object")?;
        for key in object.keys() {
            let allowed = match key.as_str() {
                "source" | "windowId" | "startNs" | "endNs" | "view" => !is_capture_tool(name),
                "offset" | "limit" => paged,
                "memberOffset" => name == "frame_problem_clusters",
                "sessionId" | "frameId" => matches!(name, "frame_detail" | "frame_trace_overlaps"),
                _ => false,
            };
            if !allowed {
                return Err("Unknown argument for this frame tool".into());
            }
        }
        let source = text(object, "source", 32)?
            .map(|source| parse_source(source).ok_or("Invalid frame source"))
            .transpose()?;
        let view = match text(object, "view", 32)?.unwrap_or("all") {
            "all" => View::All,
            "problems" => View::Problems,
            "deadline_misses" => View::DeadlineMisses,
            "platform_jank" => View::PlatformJank,
            "jank_stats" => View::JankStats,
            _ => return Err("Invalid frame view".into()),
        };
        let start = timestamp(object, "startNs")?;
        let end = timestamp(object, "endNs")?;
        if start.zip(end).is_some_and(|(start, end)| start >= end) {
            return Err("startNs must be less than endNs for the half-open range".into());
        }
        let offset = integer(object, "offset", 0)?;
        let limit = integer(object, "limit", DEFAULT_LIMIT)?;
        let member_offset = integer(object, "memberOffset", 0)?;
        if !(1..=100).contains(&limit) {
            return Err("limit must be from 1 to 100".into());
        }
        let (session_id, frame_id) = if matches!(name, "frame_detail" | "frame_trace_overlaps") {
            let session = text(object, "sessionId", 128)?.ok_or("Missing sessionId")?;
            let frame = text(object, "frameId", 20)?.ok_or("Missing frameId")?;
            let frame_id = aps_analysis::frame::parse_frame_id(frame)?;
            (Some(session.to_owned()), Some(frame_id))
        } else {
            (None, None)
        };
        let window = text(object, "windowId", labels::MAX_LABEL_BYTES)?;
        if window.is_some_and(|value| !labels::public(value, true)) {
            return Err("windowId must be a public window label, not a path".into());
        }
        Ok(Self {
            filter: Filter {
                source,
                window: window.map(str::to_owned),
                start,
                end,
                view,
            },
            offset,
            limit,
            member_offset,
            frame_id,
            session_id,
        })
    }
}

fn text<'a>(
    object: &'a Map<String, Value>,
    key: &str,
    max: usize,
) -> Result<Option<&'a str>, String> {
    object
        .get(key)
        .map(|value| {
            value
                .as_str()
                .filter(|value| {
                    !value.is_empty() && value.len() <= max && !value.chars().any(char::is_control)
                })
                .ok_or_else(|| {
                    format!("{key} must be nonempty, control-free text within {max} bytes")
                })
        })
        .transpose()
}

fn timestamp(object: &Map<String, Value>, key: &str) -> Result<Option<i64>, String> {
    object
        .get(key)
        .map(|value| {
            value
                .as_i64()
                .filter(|&value| value >= 0)
                .ok_or_else(|| format!("{key} must be a nonnegative i64 integer"))
        })
        .transpose()
}

fn integer(object: &Map<String, Value>, key: &str, default: usize) -> Result<usize, String> {
    match object.get(key) {
        None => Ok(default),
        Some(value) => value
            .as_u64()
            .and_then(|value| usize::try_from(value).ok())
            .ok_or_else(|| format!("{key} must be a nonnegative integer")),
    }
}

pub(crate) fn is_capture_tool(name: &str) -> bool {
    matches!(name, "frame_trace_overlaps" | "frame_session_compare")
}

pub(crate) fn tools(capture: bool) -> Value {
    let definitions = [
        (
            "frame_session_summary",
            "Source-bound summary. Deadline, platform and JankStats rates have independent denominators; unavailable metrics remain null.",
        ),
        (
            "frame_list",
            "Deterministic bounded frame page, ordered by intended vsync (frame ID fallback).",
        ),
        (
            "frame_detail",
            "One session-bound frame with numeric timing and reported stage evidence; not a causal diagnosis.",
        ),
        (
            "frame_problem_clusters",
            "Bounded deadline-miss clusters from the original analysis, trimmed to the selected scope; never a platform-jank causal classification.",
        ),
        (
            "frame_attributions",
            "Bounded Activity/window/UI context groups; statistical grouping is not causal attribution.",
        ),
        (
            "frame_trace_overlaps",
            "Reported APS probe overlaps for one actual frame of the explicitly pinned capture; not causal attribution.",
        ),
        (
            "frame_session_compare",
            "After minus baseline for two distinct, comparable full captures. Requires a baseline receipt, report and trace at startup.",
        ),
    ];
    json!({"tools": definitions.into_iter().filter(|(name, _)| capture || !is_capture_tool(name)).map(|(name, description)| {
        let mut properties = if is_capture_tool(name) { json!({}) } else { json!({
            "source":{"type":"string","enum":["GFXINFO","PERFETTO","FRAME_METRICS","JANK_STATS"]},
            "windowId":{"type":"string","minLength":1,"maxLength":512,"description":"Public window label returned by frame_list; paths and omitted labels cannot be selected."},
            "startNs":{"type":"integer","minimum":0,"maximum":i64::MAX},
            "endNs":{"type":"integer","minimum":0,"maximum":i64::MAX},
            "view":{"type":"string","enum":["all","problems","deadline_misses","platform_jank","jank_stats"],"default":"all"}
        }) };
        let required = if matches!(name, "frame_detail" | "frame_trace_overlaps") {
            properties["sessionId"] = json!({"type":"string","minLength":1,"maxLength":128});
            properties["frameId"] = json!({"type":"string","pattern":"^(0|[1-9][0-9]{0,19})$"});
            json!(["sessionId","frameId"])
        } else {
            if name != "frame_session_summary" {
                properties["offset"] = json!({"type":"integer","minimum":0});
                properties["limit"] = json!({"type":"integer","minimum":1,"maximum":100,"default":50});
                if name == "frame_problem_clusters" {
                    properties["memberOffset"] = json!({"type":"integer","minimum":0,"default":0});
                }
            }
            json!([])
        };
        json!({"name":name,"description":description,"inputSchema":{"type":"object","properties":properties,"required":required,"additionalProperties":false},
            "annotations":{"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false}})
    }).collect::<Vec<_>>()})
}

pub(crate) fn filtered(base: &FrameAnalysisResult, filter: &Filter) -> FrameAnalysisResult {
    let samples: Vec<_> = base
        .frames
        .iter()
        .filter(|frame| filter.matches(frame))
        .map(|frame| frame.sample.clone())
        .collect();
    let mut result = FrameJankAnalyzer::new().analyze(&samples);
    let visible: BTreeMap<_, _> = result
        .frames
        .iter()
        .map(|frame| (frame.sample.frame_id, frame))
        .collect();
    // Filtering must not connect originally separate clusters across excluded frames.
    result.clusters = base
        .clusters
        .iter()
        .filter_map(|cluster| {
            let members: Vec<_> = cluster
                .deadline_miss_frame_ids
                .iter()
                .filter_map(|id| visible.get(id).copied())
                .collect();
            let first = members.first()?;
            let last = members.last()?;
            let mut cluster = cluster.clone();
            cluster.first_frame_id = first.sample.frame_id;
            cluster.last_frame_id = last.sample.frame_id;
            if members.len() == cluster.deadline_miss_frame_ids.len() {
                return Some(cluster);
            }
            cluster.deadline_miss_frame_ids =
                members.iter().map(|frame| frame.sample.frame_id).collect();
            cluster.worst_severity = members.iter().map(|frame| frame.severity).max()?;
            let mut stages = BTreeMap::new();
            for (index, stage) in members
                .iter()
                .filter_map(|frame| frame.largest_reported_stage.as_ref())
                .enumerate()
            {
                stages
                    .entry(stage)
                    .or_insert((0usize, std::cmp::Reverse(index)))
                    .0 += 1;
            }
            cluster.dominant_reported_stage = stages
                .into_iter()
                .max_by_key(|(_, rank)| *rank)
                .map(|(stage, _)| stage.clone());
            cluster.duration_ns = cluster_span(&first.sample, &last.sample)
                .2
                .unwrap_or_default();
            Some(cluster)
        })
        .collect();
    result
}

pub(crate) fn project(
    name: &str,
    args: &Arguments,
    analysis: &FrameAnalysisResult,
    input_frames: usize,
    digest: &str,
    input_format: &str,
) -> Result<Value, String> {
    let session_id = format!("frame-{digest}");
    let mut output = json!({
        "sourceDigest":digest,
        "sessionId":session_id,
        "analysisPolicyVersion":ANALYSIS_POLICY_VERSION,
        "inputFormat":input_format,
        "provenanceComplete":false,
        "provenanceWarnings":[IMPORT_WARNING],
        "filter":args.filter.json(),
        "coverage":coverage(analysis,input_frames)
    });
    match name {
        "frame_session_summary" => {
            output["summary"] = summary(&analysis.summary);
            output["observedCapabilities"] = json!({
                "stageBreakdown":analysis.frames.iter().any(|frame| !frame.sample.stages.values().is_empty()),
                "expectedDuration":analysis.frames.iter().any(|frame| frame.sample.known_expected_duration_ns().is_some()),
                "platformJank":analysis.summary.platform_classified_frames > 0,
                "jankStats":analysis.summary.jank_stats_classified_frames > 0,
                "contextLabels":analysis.frames.iter().any(|frame| {
                    let sample = &frame.sample;
                    sample.activity_name.as_deref().and_then(labels::visible_context).is_some()
                        || sample.window_id.as_deref().and_then(labels::visible_context).is_some()
                        || sample.fragment_name.is_some() || sample.page_name.is_some()
                        || sample.interaction_state.is_some() || ["screen","screenName","uiState","fragment","page","interaction"].iter().any(|k| sample.states.contains_key(*k))
                }),
                "rawTraceQueries":false
            });
        }
        "frame_detail" => {
            if args.session_id.as_deref() != Some(&session_id) {
                return Err("Stale or unrelated frame sessionId".into());
            }
            let frame = analysis
                .frames
                .iter()
                .find(|frame| Some(frame.sample.frame_id) == args.frame_id)
                .ok_or("Frame ID is absent from the selected session or filter")?;
            output["frame"] = frame_detail(frame);
        }
        "frame_list" => {
            output["items"] = analysis
                .frames
                .iter()
                .skip(args.offset)
                .take(args.limit)
                .map(frame_row)
                .collect();
            output["page"] = page(args.offset, args.limit, analysis.frames.len());
        }
        "frame_problem_clusters" => {
            let by_id: BTreeMap<_, _> = analysis
                .frames
                .iter()
                .map(|frame| (frame.sample.frame_id, &frame.sample))
                .collect();
            output["items"] = analysis.clusters.iter().skip(args.offset).take(args.limit).map(|cluster| {
                let (start, end, duration) = cluster_span(
                    by_id[&cluster.first_frame_id],
                    by_id[&cluster.last_frame_id],
                );
                let members = &cluster.deadline_miss_frame_ids;
                let member_page = page(args.member_offset, MAX_MEMBERS, members.len());
                json!({
                    "clusterId":cluster.id,
                    "firstFrameId":cluster.first_frame_id.to_string(),
                    "lastFrameId":cluster.last_frame_id.to_string(),
                    "deadlineMissFrameIds":members.iter().skip(args.member_offset).take(MAX_MEMBERS).map(u64::to_string).collect::<Vec<_>>(),
                    "memberCount":members.len(),
                    "membersTruncated":args.member_offset != 0 || members.len() > MAX_MEMBERS,
                    "memberPage":member_page,
                    "startNs":start,
                    "endNs":end,
                    "durationNs":duration,
                    "worstSeverity":cluster.worst_severity.as_name(),
                    "windowId":cluster.window_id.as_deref().and_then(labels::visible_context),
                    "activityName":cluster.activity_name.as_deref().and_then(labels::visible_context),
                    "dominantReportedStage":cluster.dominant_reported_stage,
                    "evidenceKind":"deadline_miss_cluster_not_cause"
                })
            }).collect();
            output["page"] = page(args.offset, args.limit, analysis.clusters.len());
        }
        "frame_attributions" => {
            let mut rows: Vec<_> = analysis.attributions.iter().collect();
            rows.sort_by(|left, right| {
                right
                    .platform_jank_frames
                    .cmp(&left.platform_jank_frames)
                    .then_with(|| right.worst_duration_ns.cmp(&left.worst_duration_ns))
                    .then_with(|| {
                        (
                            &left.activity_name,
                            &left.window_id,
                            &left.ui_state,
                            &left.fragment_name,
                            &left.page_name,
                            &left.interaction_state,
                        )
                            .cmp(&(
                                &right.activity_name,
                                &right.window_id,
                                &right.ui_state,
                                &right.fragment_name,
                                &right.page_name,
                                &right.interaction_state,
                            ))
                    })
            });
            output["items"] = rows.iter().skip(args.offset).take(args.limit).map(|row| json!({
                "activityName":labels::visible_context(&row.activity_name),
                "windowId":labels::visible_context(&row.window_id),
                "uiState":row.ui_state,
                "fragmentName":row.fragment_name,
                "pageName":row.page_name,
                "interactionState":row.interaction_state,
                "totalFrames":row.total_frames,
                "deadlineMissFrames":row.deadline_miss_frames,
                "platformJankFrames":row.platform_jank_frames,
                "jankStatsJankFrames":row.jank_stats_frames,
                "averageDurationNs":row.average_duration_ns,
                "p95DurationNs":row.p95_duration_ns,
                "worstDurationNs":row.worst_duration_ns,
                "jankTypes":row.jank_types,
                "renderThreadNames":row.render_thread_names.iter().take(MAX_MEMBERS).collect::<Vec<_>>(),
                "renderThreadNamesTruncated":row.render_thread_names.len() > MAX_MEMBERS,
                "surfaceFlingerJankTypes":row.surface_flinger_jank_types.iter().take(MAX_MEMBERS).collect::<Vec<_>>(),
                "surfaceFlingerJankTypesTruncated":row.surface_flinger_jank_types.len() > MAX_MEMBERS,
                "evidenceKind":"context_group_not_cause"
            })).collect();
            output["page"] = page(args.offset, args.limit, rows.len());
        }
        _ => return Err("Unknown frame tool".into()),
    }
    Ok(output)
}

fn page(offset: usize, limit: usize, total: usize) -> Value {
    let returned = total.saturating_sub(offset).min(limit);
    let next = offset.saturating_add(returned);
    json!({"offset":offset,"limit":limit,"returned":returned,"total":total,
        "nextOffset":if next < total { Some(next) } else { None },"hasMore":next < total})
}

fn frame_row(frame: &AnalyzedFrame) -> Value {
    let sample = &frame.sample;
    json!({
        "frameId":sample.frame_id.to_string(),
        "source":sample.source.as_name(),
        "windowId":sample.window_id.as_deref().and_then(labels::visible_context),
        "activityName":sample.activity_name.as_deref().and_then(labels::visible_context),
        "intendedVsyncNs":sample.intended_vsync_ns,
        "durationNs":sample.resolved_duration_ns(),
        "expectedDurationNs":sample.known_expected_duration_ns(),
        "expectedDurationSource":sample.expected_duration_source.as_name(),
        "deadlineVerdict":frame.deadline_verdict.as_name(),
        "severity":frame.severity.as_name(),
        "missedVsyncCount":frame.missed_vsync_count,
        "platformJank":sample.platform_jank,
        "jankStatsJank":sample.jank_stats_jank,
        "largestReportedStage":frame.largest_reported_stage
    })
}

fn cluster_span(
    first: &FrameSample,
    last: &FrameSample,
) -> (Option<i64>, Option<i64>, Option<i64>) {
    let start = first.intended_vsync_ns;
    let end = last.present_ns.or(last.frame_completed_ns);
    let duration = start
        .zip(end)
        .and_then(|(start, end)| end.checked_sub(start).filter(|&ns| ns >= 0))
        .or_else(|| {
            if first.frame_id == last.frame_id {
                last.resolved_duration_ns()
            } else {
                None
            }
        });
    (start, end, duration)
}

fn frame_detail(frame: &AnalyzedFrame) -> Value {
    let sample = &frame.sample;
    let mut output = frame_row(frame);
    let stages: Map<_, _> = [
        ("input", sample.stages.input_ns),
        ("animation", sample.stages.animation_ns),
        ("layoutMeasure", sample.stages.layout_measure_ns),
        ("draw", sample.stages.draw_ns),
        ("sync", sample.stages.sync_ns),
        ("commandIssue", sample.stages.command_issue_ns),
        ("swapBuffers", sample.stages.swap_buffers_ns),
        ("gpu", sample.stages.gpu_ns),
    ]
    .into_iter()
    .map(|(key, value)| (key.to_owned(), json!(value)))
    .collect();
    output["stagesNs"] = Value::Object(stages);
    let extra = json!({
        "actualVsyncNs":sample.actual_vsync_ns,
        "frameCompletedNs":sample.frame_completed_ns,
        "presentNs":sample.present_ns,
        "refreshRateHz":sample.refresh_rate_hz,
        "frameTimelineVsyncId":sample.frame_timeline_vsync_id.map(|id| id.to_string()),
        "eligibleForJank":sample.eligible_for_jank,
        "droppedBeforeSample":sample.dropped_before_sample,
        "platformJankTypes":frame.platform_jank_types.iter().map(|kind| kind.as_name()).collect::<Vec<_>>(),
        "platformJankRuleId":sample.platform_jank_rule_id,
        "platformJankRuleVersion":sample.platform_jank_rule_version,
        "jankStatsReasons":sample.jank_stats_reasons.iter().filter(|reason| !reason.is_empty()).collect::<Vec<_>>(),
        "jankStatsReasonsRedacted":sample.jank_stats_reasons.contains(labels::REDACTED_REASON),
        "jankStatsRuleId":sample.jank_stats_rule_id,
        "jankStatsRuleVersion":sample.jank_stats_rule_version,
        "fragmentName":sample.fragment_name,
        "pageName":sample.page_name,
        "interactionState":sample.interaction_state,
        "renderThreadName":sample.render_thread_name,
        "surfaceFlingerJankType":sample.surface_flinger_jank_type,
        "evidenceKind":"reported_frame_evidence_not_cause"
    });
    output
        .as_object_mut()
        .expect("frame projection is an object")
        .extend(extra.as_object().expect("extra is an object").clone());
    output
}
