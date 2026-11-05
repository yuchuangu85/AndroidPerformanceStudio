//! Dependency-free streaming HAR summaries with bounded per-entry parsing.
mod entries;
mod json;
pub use entries::stream_entries;
use json::{Parser, Value};
use std::collections::{BTreeMap, BTreeSet};
use std::fmt::Write;
use std::io::Read;

pub const PROTOCOL_VERSION: u32 = 1;
pub const SLOWEST_LIMIT: usize = 10;
pub const MAX_ENTRIES: u64 = 1_000_000;
const PHASE_NAMES: [&str; 7] = [
    "blocked", "dns", "connect", "ssl", "send", "wait", "receive",
];

#[derive(Debug, Default)]
pub struct ByteSummary {
    pub known_count: u64,
    pub unknown_count: u64,
    pub total_bytes: u64,
}
impl ByteSummary {
    fn add(&mut self, value: Option<i64>) -> Result<(), String> {
        match value {
            Some(value) if value >= 0 => {
                self.total_bytes = self
                    .total_bytes
                    .checked_add(value as u64)
                    .ok_or("byte total overflow")?;
                self.known_count += 1;
            }
            None | Some(-1) => self.unknown_count += 1,
            _ => return Err("invalid byte size".into()),
        }
        Ok(())
    }
    fn json(&self) -> String {
        format!(
            "{{\"knownCount\":{},\"unknownCount\":{},\"totalBytes\":{}}}",
            self.known_count, self.unknown_count, self.total_bytes
        )
    }
}

#[derive(Debug, Default)]
pub struct DurationSummary {
    pub sample_count: u64,
    pub missing_count: u64,
    pub invalid_count: u64,
    pub mean_ms: Option<f64>,
    pub min_ms: Option<f64>,
    pub max_ms: Option<f64>,
}
impl DurationSummary {
    fn add(&mut self, value: Option<f64>) {
        match value {
            None | Some(-1.0) => self.missing_count += 1,
            Some(value) if value >= 0.0 => {
                self.sample_count += 1;
                let mean = self.mean_ms.unwrap_or(value);
                self.mean_ms = Some(mean + (value - mean) / self.sample_count as f64);
                self.min_ms = Some(self.min_ms.map_or(value, |old| old.min(value)));
                self.max_ms = Some(self.max_ms.map_or(value, |old| old.max(value)));
            }
            Some(_) => self.invalid_count += 1,
        }
    }
    fn fields(&self) -> String {
        let number = |value: Option<f64>| value.map_or_else(|| "null".into(), |v| v.to_string());
        format!("\"sampleCount\":{},\"missingCount\":{},\"invalidCount\":{},\"meanMs\":{},\"minMs\":{},\"maxMs\":{}", self.sample_count, self.missing_count, self.invalid_count, number(self.mean_ms), number(self.min_ms), number(self.max_ms))
    }
}

#[derive(Debug)]
pub struct PhaseSummary {
    pub kind: &'static str,
    pub parent_kind: Option<&'static str>,
    pub durations: DurationSummary,
}
#[derive(Debug)]
pub struct RequestSummary {
    pub entry_index: u64,
    pub method: String,
    pub started_date_time: String,
    pub status: u16,
    pub protocol: Option<String>,
    pub duration_ms: f64,
}
#[derive(Debug)]
pub struct Summary {
    pub protocol_version: u32,
    pub har_version: String,
    pub entry_count: u64,
    pub status_families: BTreeMap<String, u64>,
    pub request_bytes: ByteSummary,
    pub response_bytes: ByteSummary,
    pub decoded_response_bytes: ByteSummary,
    pub durations: DurationSummary,
    pub phases: Vec<PhaseSummary>,
    pub slowest_requests: Vec<RequestSummary>,
}

fn required<'a>(map: &'a BTreeMap<String, Value>, key: &str) -> Result<&'a Value, String> {
    map.get(key)
        .ok_or_else(|| format!("missing HAR field {key}"))
}
fn optional<'a>(map: &'a BTreeMap<String, Value>, key: &str) -> Option<&'a Value> {
    map.get(key).filter(|value| !matches!(value, Value::Null))
}
fn milliseconds(map: &BTreeMap<String, Value>, key: &str) -> Result<Option<f64>, String> {
    optional(map, key).map(Value::number).transpose()
}
fn bytes(map: &BTreeMap<String, Value>, key: &str) -> Result<Option<i64>, String> {
    optional(map, key).map(Value::integer).transpose()
}

impl Summary {
    fn new() -> Self {
        Self {
            protocol_version: PROTOCOL_VERSION,
            har_version: String::new(),
            entry_count: 0,
            status_families: BTreeMap::new(),
            request_bytes: ByteSummary::default(),
            response_bytes: ByteSummary::default(),
            decoded_response_bytes: ByteSummary::default(),
            durations: DurationSummary::default(),
            slowest_requests: Vec::new(),
            phases: PHASE_NAMES
                .into_iter()
                .map(|kind| PhaseSummary {
                    kind,
                    parent_kind: (kind == "ssl").then_some("connect"),
                    durations: DurationSummary::default(),
                })
                .collect(),
        }
    }
    fn add(&mut self, entry: Value) -> Result<(), String> {
        let entry = entry.object()?;
        let request = required(entry, "request")?.object()?;
        let response = required(entry, "response")?.object()?;
        let start = required(entry, "startedDateTime")?.text()?;
        let method = required(request, "method")?.text()?;
        let url = required(request, "url")?.text()?;
        if start.is_empty() || method.is_empty() || url.is_empty() {
            return Err("empty required HAR string".into());
        }
        let status = required(response, "status")?.integer()?;
        if status != 0 && !(100..=599).contains(&status) {
            return Err("invalid HTTP status".into());
        }
        let protocol = optional(response, "httpVersion")
            .map(Value::text)
            .transpose()?;
        self.request_bytes.add(bytes(request, "bodySize")?)?;
        self.response_bytes.add(bytes(response, "bodySize")?)?;
        let content = optional(response, "content")
            .map(Value::object)
            .transpose()?;
        self.decoded_response_bytes.add(
            content
                .map(|content| bytes(content, "size"))
                .transpose()?
                .flatten(),
        )?;
        let duration = milliseconds(entry, "time")?;
        self.durations.add(duration);
        let timings = optional(entry, "timings").map(Value::object).transpose()?;
        for phase in &mut self.phases {
            phase.durations.add(
                timings
                    .map(|timings| milliseconds(timings, phase.kind))
                    .transpose()?
                    .flatten(),
            );
        }
        let family = if status == 0 {
            "unknown".into()
        } else {
            format!("{}xx", status / 100)
        };
        *self.status_families.entry(family).or_default() += 1;
        if let Some(duration_ms) = duration.filter(|value| *value >= 0.0) {
            self.slowest_requests.push(RequestSummary {
                entry_index: self.entry_count,
                method: method.into(),
                started_date_time: start.into(),
                status: status as u16,
                protocol: protocol.map(str::to_owned),
                duration_ms,
            });
            self.slowest_requests.sort_by(|a, b| {
                b.duration_ms
                    .total_cmp(&a.duration_ms)
                    .then(a.entry_index.cmp(&b.entry_index))
            });
            self.slowest_requests.truncate(SLOWEST_LIMIT);
        }
        self.entry_count += 1;
        Ok(())
    }
    /// Stable v1 JSON response; URLs, headers, bodies and producer strings are omitted.
    pub fn to_json(&self) -> String {
        let mut output = format!(
            "{{\"protocolVersion\":{},\"harVersion\":",
            self.protocol_version
        );
        json::quote(&self.har_version, &mut output);
        write!(
            output,
            ",\"entryCount\":{},\"statusFamilies\":{{",
            self.entry_count
        )
        .unwrap();
        for (index, (family, count)) in self.status_families.iter().enumerate() {
            if index > 0 {
                output.push(',');
            }
            json::quote(family, &mut output);
            write!(output, ":{count}").unwrap();
        }
        write!(output, "}},\"requestBytes\":{},\"responseBytes\":{},\"decodedResponseBytes\":{},\"durations\":{{{}}},\"phases\":[", self.request_bytes.json(), self.response_bytes.json(), self.decoded_response_bytes.json(), self.durations.fields()).unwrap();
        for (index, phase) in self.phases.iter().enumerate() {
            if index > 0 {
                output.push(',');
            }
            output.push_str("{\"kind\":");
            json::quote(phase.kind, &mut output);
            output.push_str(",\"parentKind\":");
            match phase.parent_kind {
                Some(parent) => json::quote(parent, &mut output),
                None => output.push_str("null"),
            }
            write!(output, ",{}}}", phase.durations.fields()).unwrap();
        }
        output.push_str("],\"slowestRequests\":[");
        for (index, request) in self.slowest_requests.iter().enumerate() {
            if index > 0 {
                output.push(',');
            }
            write!(
                output,
                "{{\"entryIndex\":{},\"method\":",
                request.entry_index
            )
            .unwrap();
            json::quote(&request.method, &mut output);
            output.push_str(",\"startedDateTime\":");
            json::quote(&request.started_date_time, &mut output);
            write!(output, ",\"status\":{},\"protocol\":", request.status).unwrap();
            match &request.protocol {
                Some(protocol) => json::quote(protocol, &mut output),
                None => output.push_str("null"),
            }
            write!(output, ",\"durationMs\":{}}}", request.duration_ms).unwrap();
        }
        output.push_str("]}");
        output
    }
}

// Only root/log are streamed containers; every other value has an independent byte/node budget.
fn envelope<R: Read>(
    parser: &mut Parser<R>,
    summary: &mut Summary,
    log: bool,
    max_entries: u64,
) -> Result<(), String> {
    parser.expect(b'{')?;
    let mut keys = BTreeSet::new();
    let mut key_bytes = 0;
    let mut child_seen = false;
    let mut version_seen = false;
    if !parser.eat(b'}')? {
        loop {
            if keys.len() >= json::MAX_MEMBERS {
                return Err("HAR member budget exceeded".into());
            }
            let key = parser.key()?;
            key_bytes += key.len();
            if key_bytes > json::MAX_VALUE_BYTES as usize {
                return Err("HAR key byte budget exceeded".into());
            }
            if !keys.insert(key.clone()) {
                return Err("duplicate JSON key".into());
            }
            parser.expect(b':')?;
            match (log, key.as_str()) {
                (false, "log") => {
                    envelope(parser, summary, true, max_entries)?;
                    child_seen = true;
                }
                (true, "version") => {
                    let value = parser.bounded_value(2)?;
                    let version = value.text()?;
                    if version != "1.1" && version != "1.2" {
                        return Err("unsupported HAR version".into());
                    }
                    summary.har_version = version.into();
                    version_seen = true;
                }
                (true, "entries") => {
                    parser.expect(b'[')?;
                    if !parser.eat(b']')? {
                        loop {
                            if summary.entry_count >= max_entries {
                                return Err("HAR entry count budget exceeded".into());
                            }
                            summary.add(parser.bounded_value(3)?)?;
                            if parser.eat(b']')? {
                                break;
                            }
                            parser.expect(b',')?;
                        }
                    }
                    child_seen = true;
                }
                _ => {
                    parser.bounded_value(if log { 2 } else { 1 })?;
                }
            }
            if parser.eat(b'}')? {
                break;
            }
            parser.expect(b',')?;
        }
    }
    if !child_seen || (log && !version_seen) {
        return Err("missing HAR container or version".into());
    }
    Ok(())
}

pub fn summarize(reader: impl Read) -> Result<Summary, String> {
    summarize_with_entry_limit(reader, MAX_ENTRIES)
}

/// Callers may lower, but not raise, the hard entry limit. Errors discard all results.
pub fn summarize_with_entry_limit(reader: impl Read, max_entries: u64) -> Result<Summary, String> {
    if max_entries == 0 || max_entries > MAX_ENTRIES {
        return Err("invalid HAR entry limit".into());
    }
    let mut parser = Parser::new(reader);
    let mut summary = Summary::new();
    envelope(&mut parser, &mut summary, false, max_entries)?;
    parser.whitespace()?;
    if parser.peek()?.is_some() {
        return Err("trailing JSON data".into());
    }
    Ok(summary)
}
