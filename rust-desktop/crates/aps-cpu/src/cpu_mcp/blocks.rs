//! Structured projections of the existing chart builders. Layout coordinates are
//! not execution measurements; in particular a stack box's end is synthesized.
use super::{query_from_args, read_usize, sanitize_resource, MAX_PAGE_LIMIT};
use aps_cpu::{
    ChartBlock, ChartBlockKind, ChartDomain, ChartViewport, CpuSession, TimelineViewport,
};
use serde_json::{json, Value};

type Error = (i32, String);
const MAX_BLOCKS: usize = 512;
const DEFAULT_BLOCKS: usize = 128;
const MAX_DEPTH: usize = 64;
const DEFAULT_DEPTH: usize = 32;
const MAX_BINS: usize = 4096;
const DEFAULT_BINS: usize = 1024;

pub(super) fn schema(flame: bool) -> Value {
    let mut schema = json!({
        "type":"object","additionalProperties":false,
        "properties":{
            "event":{"type":"string","minLength":1,"maxLength":2048},
            "startNs":{"type":"integer","minimum":0,"maximum":u64::MAX},
            "endNs":{"type":"integer","minimum":0,"maximum":u64::MAX},
            "weightType":{"type":"string","enum":["samples","events"],"default":"samples"},
            "direction":{"type":"string","enum":["forward","inverted"],"default":"forward"},
            "depthCount":{"type":"integer","minimum":1,"maximum":MAX_DEPTH,"default":DEFAULT_DEPTH},
            "maxBlocks":{"type":"integer","minimum":1,"maximum":MAX_BLOCKS,"default":DEFAULT_BLOCKS}
        }
    });
    if flame {
        schema["properties"]["resolutionBins"] =
            json!({"type":"integer","minimum":1,"maximum":MAX_BINS,"default":DEFAULT_BINS});
        schema["properties"]["startFraction"] =
            json!({"type":"number","minimum":0,"exclusiveMaximum":1,"default":0});
        schema["properties"]["endFraction"] =
            json!({"type":"number","exclusiveMinimum":0,"maximum":1,"default":1});
    } else {
        schema["required"] = json!(["startNs", "endNs"]);
        schema["properties"]["threadId"] =
            json!({"type":"integer","minimum":i32::MIN,"maximum":i32::MAX});
    }
    schema
}

fn invalid(message: impl Into<String>) -> Error {
    (-32602, message.into())
}

fn positive(args: &Value, key: &str, default: usize, max: usize) -> Result<usize, Error> {
    let value = read_usize(args, key, default, max).map_err(invalid)?;
    if value == 0 {
        return Err(invalid(format!("{key} must be positive")));
    }
    Ok(value)
}

fn fraction(args: &Value, key: &str, default: f64) -> Result<f64, Error> {
    match args.get(key) {
        None => Ok(default),
        Some(value) => value
            .as_f64()
            .filter(|v| (0.0..=1.0).contains(v))
            .ok_or_else(|| invalid(format!("{key} must be a number in [0,1]"))),
    }
}

pub(super) fn call(session: &mut CpuSession, args: &Value, flame: bool) -> Result<Value, Error> {
    let args_obj = args
        .as_object()
        .ok_or_else(|| invalid("expected an arguments object"))?;
    let properties = schema(flame);
    if args_obj
        .keys()
        .any(|key| properties["properties"].get(key).is_none())
    {
        return Err(invalid("unsupported block argument"));
    }
    let mut filters = json!({});
    for key in ["event", "startNs", "endNs", "direction", "weightType"] {
        if let Some(value) = args_obj.get(key) {
            filters[key] = value.clone();
        }
    }
    let mut query = query_from_args(&filters, MAX_PAGE_LIMIT).map_err(invalid)?;
    if query
        .event_types
        .iter()
        .any(|e| !session.events().contains(e))
    {
        return Err(invalid("event is absent from the bound CPU profile"));
    }
    let depth = positive(args, "depthCount", DEFAULT_DEPTH, MAX_DEPTH)?;
    let max = positive(args, "maxBlocks", DEFAULT_BLOCKS, MAX_BLOCKS)?;
    let mut viewport = ChartViewport {
        depth_count: depth,
        max_blocks: max,
        first_depth: 0,
        ..ChartViewport::default()
    };
    let mut applied = json!({"event":query.event_types.iter().next(),"startNs":query.start,"endNs":query.end,
        "direction":query.direction.as_str(),"weightType":query.weight_type.as_str(),"depthCount":depth,"maxBlocks":max});
    let (frame, domain) = if flame {
        let bins = positive(args, "resolutionBins", DEFAULT_BINS, MAX_BINS)?;
        let start = fraction(args, "startFraction", 0.)?;
        let end = fraction(args, "endFraction", 1.)?;
        if start >= end {
            return Err(invalid("flame zoom requires startFraction < endFraction"));
        }
        viewport.width_px = bins as f32;
        applied["resolutionBins"] = json!(bins);
        applied["startFraction"] = json!(start);
        applied["endFraction"] = json!(end);
        let frame = session
            .flame_graph_in_range(&query, viewport, start, end)
            .map_err(|e| (-32001, e))?;
        (
            frame,
            json!({"kind":"normalizedWeight","startFraction":start,"endFraction":end,"resolutionBins":bins}),
        )
    } else {
        let (start, end) = query
            .start
            .zip(query.end)
            .ok_or_else(|| invalid("stack blocks require a half-open startNs/endNs range"))?;
        let thread = args
            .get("threadId")
            .map(|value| {
                value
                    .as_i64()
                    .and_then(|n| i32::try_from(n).ok())
                    .ok_or_else(|| invalid("threadId must be a source-local signed 32-bit integer"))
            })
            .transpose()?;
        if let Some(tid) = thread {
            if !session.threads().iter().any(|t| t.id == tid) {
                return Err(invalid("threadId is absent from the bound CPU profile"));
            }
            query.thread_ids.insert(tid);
        }
        applied["threadId"] = json!(thread);
        let frame = session
            .stack_chart(
                &query,
                TimelineViewport {
                    chart: viewport,
                    start_nanos: start,
                    end_nanos: end,
                },
            )
            .map_err(|e| (-32001, e))?;
        (
            frame,
            json!({"kind":"sampledStackLayout","startNs":start,"endNsExclusive":end,
            "threadSelection":if thread.is_some() {"explicit"} else {"highest_filtered_sample_count_then_lowest_id"}}),
        )
    };
    let blocks: Vec<_> = frame
        .blocks
        .iter()
        .map(|block| match frame.domain {
            ChartDomain::Weight {
                start_fraction,
                end_fraction,
                ..
            } => flame_block(block, start_fraction, end_fraction, frame.width_px),
            ChartDomain::SamplePoints { .. } => stack_block(block),
        })
        .collect();
    Ok(
        json!({"query":applied,"domain":domain,"returnedBlocks":blocks.len(),"blocks":blocks,
        "truncated":frame.truncated,"omittedBlocks":frame.omitted_blocks,
        "coverage":"requested_depth_and_horizontal_window_only",
        "evidenceQuality":{"methodDurationAvailable":false,"deviceCaptureCompletenessVerified":false,
            "layoutIsMeasurement":false,"resourcePathsRedacted":true}}),
    )
}

fn symbol_name(label: &str) -> &str {
    if label.contains(['/', '\\']) {
        "<path-like symbol>"
    } else {
        label
    }
}

fn flame_block(block: &ChartBlock, start: f64, end: f64, width: f32) -> Value {
    let aggregate = block.kind == ChartBlockKind::PixelAggregate;
    let fraction = |x: f64| (start + x / f64::from(width) * (end - start)).clamp(start, end);
    json!({"kind":if aggregate {"aggregate"} else {"function"},
        "callNodeId":(!aggregate).then_some(&block.id),
        "name":(!aggregate).then(|| symbol_name(&block.label)),
        "resource":(!aggregate).then(|| sanitize_resource(&block.resource)),
        "depth":block.depth,"inclusiveWeight":block.inclusive_weight,"selfWeight":block.self_weight,
        "sampleCount":block.sample_count,"sourceCount":block.source_count,
        "layoutStartFraction":fraction(f64::from(block.x)),
        "layoutEndFraction":fraction(f64::from(block.x)+f64::from(block.width))})
}

fn stack_block(block: &ChartBlock) -> Value {
    // Adjacent sampled stacks may share a prefix without sharing the leaf.
    // The renderer's self_weight comes from the run's *first* leaf flag; it
    // does not establish exact self weight for the whole sampled run.
    json!({"kind":"sampledRun","name":symbol_name(&block.label),"resource":sanitize_resource(&block.resource),
        "depth":block.depth,"threadId":block.thread_id,"inclusiveWeight":block.inclusive_weight,
        "selfWeight":Value::Null,"sampleCount":block.sample_count,
        "layoutStartNs":block.first_timestamp_nanos,"layoutEndNsExclusive":block.last_timestamp_nanos})
}

#[cfg(test)]
mod tests {
    use super::*;

    fn session() -> CpuSession {
        let profile = json!({"meta":{"version":24},"processes":[],"threads":[{
            "tid":3,"pid":1,"name":"main",
            "stringTable":["/private/sym (in /private/libsame.so)", "leaf (in C:\\private\\libsame.so)"],
            "frameTable":{"schema":{"location":0},"data":[[0],[1]]},
            "stackTable":{"schema":{"prefix":0,"frame":1},"data":[[null,0],[0,1]]},
            "samples":{"schema":{"stack":0,"time":1},"data":[[0,1],[1,2],[1,3]]}
        }]});
        CpuSession::from_bytes(&serde_json::to_vec(&profile).unwrap()).unwrap()
    }

    #[test]
    fn flame_zoom_uses_weight_not_time_and_never_identifies_an_aggregate() {
        let mut s = session();
        let value = call(
            &mut s,
            &json!({"resolutionBins":1,"startFraction":0.1,"endFraction":0.95}),
            true,
        )
        .unwrap();
        assert_eq!(value["domain"]["kind"], "normalizedWeight");
        let blocks = value["blocks"].as_array().unwrap();
        assert!(blocks
            .iter()
            .any(|b| b["kind"] == "function" && b["name"] == "<path-like symbol>"));
        assert!(blocks.iter().any(|b| b["kind"] == "aggregate"
            && b["callNodeId"].is_null()
            && b["name"].is_null()
            && b["resource"].is_null()
            && b["sampleCount"] == 2));
        assert!(!value.to_string().contains("private"));
        let value = call(&mut s, &json!({"maxBlocks":1}), true).unwrap();
        assert_eq!(value["truncated"], true);
        assert_eq!(value["omittedBlocks"], 1);
    }

    #[test]
    fn stack_emits_one_thread_interpolated_runs_not_self_weight_or_method_duration() {
        let mut s = session();
        let value = call(
            &mut s,
            &json!({"threadId":3,"startNs":1_000_000,"endNs":5_000_000}),
            false,
        )
        .unwrap();
        assert_eq!(value["evidenceQuality"]["methodDurationAvailable"], false);
        let run = &value["blocks"][0];
        assert_eq!(run["kind"], "sampledRun");
        assert_eq!(run["threadId"], 3);
        assert_eq!(run["layoutStartNs"], 1_000_000);
        assert_eq!(run["layoutEndNsExclusive"], 4_000_000);
        assert_eq!(run["sampleCount"], 3);
        assert!(run["selfWeight"].is_null());
        assert!(!value.to_string().contains("private"));
    }

    #[test]
    fn rejects_wrong_mode_unknown_range_and_invalid_limits() {
        let mut s = session();
        for (flame, args) in [
            (true, json!({"threadId":3})),
            (true, json!({"resolutionBins":0})),
            (true, json!({"endFraction":"1"})),
            (true, json!({"startFraction":0.9,"endFraction":0.3})),
            (true, json!({"maxBlocks":513})),
            (true, json!({"event":"absent"})),
            (false, json!({})),
            (false, json!({"startNs":0,"endNs":5,"threadId":99})),
            (false, json!({"startNs":0,"endNs":5,"resolutionBins":1})),
            (false, json!({"startNs":2,"endNs":1})),
            (false, json!({"startNs":1,"endNs":2,"weightType":"events"})),
        ] {
            assert_eq!(call(&mut s, &args, flame).unwrap_err().0, -32602, "{args}");
        }
    }
}
