//! Fixed selectors over facts frozen at bind time. No paths, process handles,
//! provider configuration, prompts, or output destinations enter this module.
use super::{RpcError, source::Selected};
use crate::analysis::{FrozenAnalysis, startup};
use aps_contracts::SourceDigest;
use serde_json::{Map, Value, json};

pub(super) const NAME: &str = "aps_follow_up";
pub(super) const MAX_QUERIES: u8 = 8;
pub(super) const MAX_RESULT_BYTES: usize = 32 * 1024;
const DEFAULT_LIMIT: usize = 8;
const MAX_LIMIT: usize = 16;
const ITEM: &str = "evidence_item";
const FRAMES: &str = "frame_rows";
const SLOWEST: &str = "har_slowest";
const PHASES: &str = "har_phases";
const STARTUP: &str = "startup_runs";

/// The client discovers only queries available for this analyzer. The digest
/// comes from aps_analyze_file; it is a precondition, not a way to select input.
pub(super) fn definition(selected: &Selected) -> Value {
    let mut queries = vec![ITEM];
    let mut props = json!({
        "sourceDigest":{"type":"string","pattern":"^[0-9a-f]{64}$","description":"Exact input.sourceDigest returned by aps_analyze_file."},
        "evidenceId":{"type":"string","minLength":1,"maxLength":128,"description":"Required for evidence_item only (do not paginate it): exact section id from the unscoped aps_analyze_file summary."}
    });
    if selected.supports_frame_scope() {
        queries.push(FRAMES);
        props["view"] = json!({"type":"string","enum":["all","deadline_misses"],"default":"all"});
    }
    if selected.supports_har_details() {
        queries.extend([SLOWEST, PHASES]);
    }
    if selected.supports_startup_details() {
        queries.push(STARTUP);
        props["requestedMode"] = json!({"type":"string","enum":startup::MODES});
        props["observedMode"] = json!({"type":"string","enum":startup::MODES});
        props["missingMetric"] = json!({"type":"string","enum":startup::MISSING_METRICS});
    }
    if queries.len() > 1 {
        props["offset"] = json!({"type":"integer","minimum":0,"maximum":u32::MAX,"default":0});
        props["limit"] =
            json!({"type":"integer","minimum":1,"maximum":MAX_LIMIT,"default":DEFAULT_LIMIT});
    }
    props["query"] = json!({"type":"string","enum":queries,"description":"evidence_item: retrieve an existing summary section; frame_rows: find frame IDs for detail; har_slowest: slowest retained requests without URLs; har_phases: aggregate phase timings; startup_runs: page imported startup runs by requested mode, observed mode or missing metric."});
    json!({"name":NAME,"description":"Ask a bounded follow-up on the same artifact. First call aps_analyze_file. Cite sourceDigest and item IDs; stop paging at null nextOffset; stop follow-ups at zero remainingQueries. No new capture or model call.",
        "inputSchema":{"type":"object","properties":props,"required":["sourceDigest","query"],"additionalProperties":false},
        "annotations":{"readOnlyHint":true,"destructiveHint":false,"openWorldHint":false}})
}

pub(super) enum Query<'a> {
    Item(&'a str),
    Startup(startup::Query),
    Frames {
        misses: bool,
        offset: usize,
        limit: usize,
    },
    Har {
        phases: bool,
        offset: usize,
        limit: usize,
    },
}

impl<'a> Query<'a> {
    pub(super) fn parse(
        args: &'a Map<String, Value>,
        selected: &Selected,
    ) -> Result<(SourceDigest, Self), RpcError> {
        const BAD_DIGEST: RpcError = (
            -32602,
            "sourceDigest must be 64 lowercase hexadecimal characters",
        );
        let text = args
            .get("sourceDigest")
            .and_then(Value::as_str)
            .filter(|s| {
                s.bytes()
                    .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
            })
            .ok_or(BAD_DIGEST)?;
        let digest = SourceDigest::from_hex(text).map_err(|_| BAD_DIGEST)?;
        let name = args.get("query").and_then(Value::as_str);
        let allowed: &[&str] = match name {
            Some(ITEM) => &["sourceDigest", "query", "evidenceId"],
            Some(FRAMES) if selected.supports_frame_scope() => {
                &["sourceDigest", "query", "view", "offset", "limit"]
            }
            Some(STARTUP) if selected.supports_startup_details() => &[
                "sourceDigest",
                "query",
                "requestedMode",
                "observedMode",
                "missingMetric",
                "offset",
                "limit",
            ],
            Some(SLOWEST | PHASES) if selected.supports_har_details() => {
                &["sourceDigest", "query", "offset", "limit"]
            }
            _ => return Err((-32602, "unsupported query for this bound analyzer")),
        };
        if args.keys().any(|key| !allowed.contains(&key.as_str())) {
            return Err((-32602, "unexpected follow-up argument"));
        }
        if name == Some(ITEM) {
            let id = args
                .get("evidenceId")
                .and_then(Value::as_str)
                .filter(|s| !s.is_empty() && s.len() <= 128)
                .ok_or((-32602, "expected a summary evidenceId"))?;
            return Ok((digest, Self::Item(id)));
        }
        let number = |key, default, min, max| match args.get(key) {
            None => Ok(default),
            Some(value) => value
                .as_u64()
                .filter(|v| (min..=max).contains(v))
                .map(|v| v as usize)
                .ok_or((-32602, "offset or limit out of bounds")),
        };
        let offset = number("offset", 0, 0, u32::MAX as u64)?;
        let limit = number("limit", DEFAULT_LIMIT, 1, MAX_LIMIT as u64)?;
        let query = if name == Some(FRAMES) {
            let misses = match args.get("view") {
                None => false,
                Some(v) if v == "all" => false,
                Some(v) if v == "deadline_misses" => true,
                _ => return Err((-32602, "invalid frame view")),
            };
            Self::Frames {
                misses,
                offset,
                limit,
            }
        } else if name == Some(STARTUP) {
            let filter = |key| {
                args.get(key)
                    .map(|v| {
                        v.as_str()
                            .ok_or((-32602, "startup filters must be strings"))
                    })
                    .transpose()
            };
            let mode = |key| {
                filter(key)?
                    .map(startup::mode)
                    .transpose()
                    .map_err(|m| (-32602, m))
            };
            Self::Startup(startup::Query {
                requested: mode("requestedMode")?,
                observed: mode("observedMode")?,
                missing: filter("missingMetric")?
                    .map(startup::missing_metric)
                    .transpose()
                    .map_err(|m| (-32602, m))?,
                offset,
                limit,
            })
        } else {
            Self::Har {
                phases: name == Some(PHASES),
                offset,
                limit,
            }
        };
        Ok((digest, query))
    }

    pub(super) fn project(
        self,
        analysis: &FrozenAnalysis,
        remaining: u8,
    ) -> Result<Value, RpcError> {
        let digest = analysis.digest();
        let (name, mut page) = match self {
            Self::Item(id) => {
                let item = analysis
                    .evidence()
                    .pointer("/evidence/items")
                    .and_then(Value::as_array)
                    .and_then(|items| items.iter().find(|i| i["id"].as_str() == Some(id)))
                    .ok_or((-32602, "no such evidenceId in the bound summary"))?;
                (
                    ITEM,
                    json!({"items":[item],"total":1,"offset":0,"nextOffset":null}),
                )
            }
            Self::Frames {
                misses,
                offset,
                limit,
            } => {
                let report = analysis
                    .frame()
                    .ok_or((-32602, "frame query unavailable"))?;
                (
                    FRAMES,
                    report
                        .follow_up_rows(misses, offset, limit)
                        .map_err(|m| (-32602, m))?,
                )
            }
            Self::Startup(query) => {
                let startup = analysis
                    .startup()
                    .ok_or((-32602, "Startup query unavailable"))?;
                (
                    STARTUP,
                    startup.page(&query, digest).map_err(|m| (-32602, m))?,
                )
            }
            Self::Har {
                phases,
                offset,
                limit,
            } => {
                let har = analysis.har().ok_or((-32602, "HAR query unavailable"))?;
                (
                    if phases { PHASES } else { SLOWEST },
                    har.page(phases, offset, limit, digest)
                        .map_err(|m| (-32602, m))?,
                )
            }
        };
        page["sourceDigest"] = digest.to_hex().into();
        page["query"] = name.into();
        page["remainingQueries"] = remaining.into();
        Ok(page)
    }
}
