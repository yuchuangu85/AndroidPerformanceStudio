//! Paired Benchmark tools expose a frozen page, never policy execution or a new path.
use super::{RpcError, source::Selected};
use crate::{
    analysis::benchmark::{DEFAULT_LIMIT, MAX_LIMIT},
    benchmark_source::threshold,
};
use serde_json::{Map, Value, json};
use std::path::Path;

pub(super) const NAME: &str = "aps_benchmark_compare";

pub(super) fn open(args: &[String]) -> Result<Selected, String> {
    let bad =
        "E_BENCHMARK_BINDING: use --input CURRENT --baseline BASELINE [--threshold-percent P]";
    let (mut current, mut baseline, mut percent) = (None, None, None);
    for pair in args.chunks(2) {
        let [flag, value] = pair else {
            return Err(bad.into());
        };
        if value.is_empty() || value.starts_with("--") {
            return Err(bad.into());
        }
        let slot = match flag.as_str() {
            "--input" => &mut current,
            "--baseline" => &mut baseline,
            "--threshold-percent" => &mut percent,
            _ => return Err(bad.into()),
        };
        if slot.replace(value.as_str()).is_some() {
            return Err(bad.into());
        }
    }
    Selected::open_benchmark(
        Path::new(baseline.ok_or(bad)?),
        Path::new(current.ok_or(bad)?),
        percent.map(threshold).transpose()?,
    )
}

pub(super) fn definition() -> Value {
    json!({"name":NAME,
        "description":"Read a page of regression evidence for the immutable baseline/current pair, current minus baseline. The gate covers the full comparison, not just this page. Neither file nor policy can be changed by tools.",
        "inputSchema":{"type":"object","properties":{
            "offset":{"type":"integer","minimum":0,"maximum":u32::MAX,"default":0},
            "limit":{"type":"integer","minimum":1,"maximum":MAX_LIMIT,"default":DEFAULT_LIMIT}
        },"additionalProperties":false},
        "annotations":{"readOnlyHint":true,"destructiveHint":false,"openWorldHint":false}
    })
}

pub(super) fn call(
    selected: Option<&mut Selected>,
    args: &Map<String, Value>,
) -> Result<Value, RpcError> {
    if args
        .keys()
        .any(|key| !matches!(key.as_str(), "offset" | "limit"))
    {
        return Err((-32602, "Benchmark comparison only accepts offset and limit"));
    }
    let number = |key, default, min, max| {
        args.get(key).map_or(Ok(default), |v| {
            v.as_u64()
                .filter(|value| (min..=max).contains(value))
                .map(|value| value as usize)
                .ok_or((-32602, "Benchmark comparison offset or limit out of bounds"))
        })
    };
    let offset = number("offset", 0, 0, u32::MAX as u64)?;
    let limit = number("limit", DEFAULT_LIMIT, 1, MAX_LIMIT as u64)?;
    selected
        .ok_or((-32001, "no bound Benchmark pair"))?
        .benchmark_page(offset, limit)
}
