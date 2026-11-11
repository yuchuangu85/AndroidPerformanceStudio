//! Import an AOSP Gecko / Firefox Profiler v24 JSON profile.
//!
//! The oracle is the Kotlin `GeckoProfileReader` and `GeckoProfileExportService`
//! under `desktop-viewer/simpleperf-viewer`: tables are per thread and addressed by
//! schema, sample times are milliseconds, frames are string-table locations
//! formatted as `symbol (in resource)`, and a sample's stackTable chain walks from
//! the sampled leaf to the root caller. The native engine's `Sample.callchain` is
//! also leaf-to-root, so the chain is preserved without a second reversal.
//!
//! A missing table, an out-of-range index, a non-finite or negative timestamp, an
//! unsupported version, or an over-budget profile fails closed: no partial stream
//! is returned. The encoded JSON is bounded by `MAX_GECKO_PROFILE_BYTES`, and the
//! decoded stream by the engine's record and callchain budgets.

use aps_json::{Limits, Value};
use aps_simpleperf_engine::simpleperf::{
    FileRecord, Frame, MetaInfo, Record, Sample, SimpleperfStream, ThreadRecord,
    MAX_CALLCHAIN_FRAMES, MAX_RECORDS,
};
use std::collections::HashMap;

/// Decompressed Gecko JSON budget. It matches the Firefox exporter output cap, so
/// any profile this application can export can be imported again.
pub const MAX_GECKO_PROFILE_BYTES: usize = 64 * 1024 * 1024;
const GECKO_PROFILE_VERSION: u64 = 24;
const GECKO_SAMPLE_EVENT: &str = "samples";
const GECKO_APP_TYPE: &str = "gecko-profile";
const NANOS_PER_MILLISECOND: f64 = 1_000_000.0;
const UNKNOWN_SYMBOL: &str = "<unknown-symbol>";
const UNKNOWN_FILE: &str = "<gecko-profile>";
const LOCATION_SEPARATOR: &str = " (in ";
/// Mirrors the JSON structure budget the other profile boundaries use; the node
/// budget is the hard ceiling for a 64 MiB profile with large tables.
const GECKO_JSON_LIMITS: Limits = Limits {
    max_bytes: MAX_GECKO_PROFILE_BYTES as u64,
    max_depth: 64,
    max_nodes: 4_000_000,
    max_string_bytes: 1024 * 1024,
    max_collection_items: 1_000_000,
};

/// Cheap content check used by callers that must classify a pick before the
/// expensive JSON parse. `prefix` is the first bytes of the (already decompressed)
/// payload. It never replaces the full structural validation in
/// `parse_gecko_profile`.
pub fn is_gecko_profile_prefix(prefix: &[u8]) -> bool {
    let text = match prefix.iter().position(|byte| !byte.is_ascii_whitespace()) {
        Some(start) => &prefix[start..],
        None => return false,
    };
    if text.first() != Some(&b'{') || !contains(text, b"\"meta\"") {
        return false;
    }
    // Firefox raw profiles put `threads` after the compact meta block; accepting
    // either marker keeps the check stable if a writer reorders the top level.
    contains(text, b"\"threads\"") || contains(text, b"\"version\"")
}

fn contains(haystack: &[u8], needle: &[u8]) -> bool {
    haystack
        .windows(needle.len())
        .any(|window| window == needle)
}

/// Parse a complete Gecko v24 JSON profile into the native stream model.
pub fn parse_gecko_profile(bytes: &[u8]) -> Result<SimpleperfStream, String> {
    if bytes.len() > MAX_GECKO_PROFILE_BYTES {
        return Err(format!(
            "Gecko profile exceeds the {MAX_GECKO_PROFILE_BYTES} byte JSON limit"
        ));
    }
    let profile = aps_json::parse_slice(bytes, GECKO_JSON_LIMITS)
        .map_err(|error| format!("Gecko profile JSON: {error}"))?;
    let object = profile
        .as_object()
        .ok_or_else(|| "Gecko profile must be a JSON object".to_owned())?;
    let meta = object
        .get("meta")
        .and_then(Value::as_object)
        .ok_or_else(|| "Gecko profile is missing meta".to_owned())?;
    let version = meta
        .get("version")
        .and_then(Value::as_u64)
        .ok_or_else(|| "Gecko profile meta is missing version".to_owned())?;
    if version != GECKO_PROFILE_VERSION {
        return Err(format!(
            "unsupported Gecko profile version {version}; only v24 JSON profiles are supported"
        ));
    }
    let threads = object
        .get("threads")
        .and_then(Value::as_array)
        .ok_or_else(|| "Gecko profile does not contain a threads array".to_owned())?;
    let mut records = Vec::new();
    let mut files = FileCatalog::default();
    for (index, thread) in threads.iter().enumerate() {
        records.extend(thread_records(thread, index, &mut files)?);
        if records.len() > MAX_RECORDS {
            return Err(format!(
                "Gecko profile exceeds the {MAX_RECORDS} record limit"
            ));
        }
    }
    if records
        .len()
        .checked_add(files.records.len())
        .is_none_or(|count| count > MAX_RECORDS)
    {
        return Err(format!(
            "Gecko profile exceeds the {MAX_RECORDS} record limit"
        ));
    }
    records.extend(files.records.into_iter().map(Record::File));
    // The final metadata record is the catalog, like the native stream.
    records.push(Record::MetaInfo(MetaInfo {
        event_types: vec![GECKO_SAMPLE_EVENT.to_owned()],
        app_package_name: None,
        app_type: Some(GECKO_APP_TYPE.to_owned()),
        android_sdk_version: None,
        android_build_type: None,
        trace_offcpu: false,
    }));
    Ok(SimpleperfStream {
        version: 1,
        records,
    })
}

fn thread_records(
    thread: &Value,
    index: usize,
    files: &mut FileCatalog,
) -> Result<Vec<Record>, String> {
    let label = format!("threads[{index}]");
    let object = thread
        .as_object()
        .ok_or_else(|| format!("Gecko {label} must be an object"))?;
    let tid = integer(object.get("tid"), &format!("{label}.tid"))?;
    let pid = integer(object.get("pid"), &format!("{label}.pid"))?;
    let thread_id = i32::try_from(tid)
        .map_err(|_| format!("Gecko {label}.tid does not fit a signed thread ID"))?;
    let name = object
        .get("name")
        .and_then(Value::as_str)
        .map(str::to_owned)
        .unwrap_or_else(|| format!("<unknown-thread:{thread_id}>"));
    let strings = read_strings(object.get("stringTable"), &label)?;
    let frame_table = GeckoTable::read(object.get("frameTable"), &format!("{label}.frameTable"))?;
    let stack_table = GeckoTable::read(object.get("stackTable"), &format!("{label}.stackTable"))?;
    let sample_table = GeckoTable::read(object.get("samples"), &format!("{label}.samples"))?;

    let frames = frame_rows(&frame_table, &strings, &label, files)?;
    let stacks = stack_rows(&stack_table, frames.len(), &label)?;
    let samples = sample_rows(&sample_table, &stacks, &frames, thread_id, &label)?;

    let mut records = Vec::with_capacity(1 + samples.len());
    records.push(Record::Thread(ThreadRecord {
        thread_id: tid as u32,
        process_id: pid,
        name,
    }));
    records.extend(samples.into_iter().map(Record::Sample));
    Ok(records)
}

/// The parsed Gecko tables are local to one thread. This catalog gives the
/// combined stream one file/symbol namespace so selection and projections do
/// not resolve a thread's local file id through another thread's record.
#[derive(Default)]
struct FileCatalog {
    records: Vec<FileRecord>,
    file_ids: HashMap<String, u32>,
    file_symbols: Vec<HashMap<String, i32>>,
}

impl FileCatalog {
    fn intern(&mut self, symbol: &str, resource: &str, label: &str) -> Result<Frame, String> {
        let file_id = match self.file_ids.get(resource).copied() {
            Some(id) => id,
            None => {
                if self.records.len() >= MAX_RECORDS {
                    return Err(format!("Gecko {label} has too many files"));
                }
                let id = u32::try_from(self.records.len() + 1)
                    .map_err(|_| format!("Gecko {label} has too many files"))?;
                self.records.push(FileRecord {
                    id,
                    path: resource.to_owned(),
                    symbols: Vec::new(),
                });
                self.file_symbols.push(HashMap::new());
                self.file_ids.insert(resource.to_owned(), id);
                id
            }
        };
        let slot = usize::try_from(file_id - 1)
            .map_err(|_| format!("Gecko {label} file id does not fit a usize"))?;
        let symbols = &mut self.file_symbols[slot];
        let symbol_id = match symbols.get(symbol).copied() {
            Some(id) => id,
            None => {
                let id = i32::try_from(symbols.len())
                    .map_err(|_| format!("Gecko {label} has too many symbols"))?;
                symbols.insert(symbol.to_owned(), id);
                self.records[slot].symbols.push(symbol.to_owned());
                id
            }
        };
        Ok(Frame {
            virtual_address: 0,
            file_id,
            symbol_id,
            execution_type: 0,
        })
    }
}

/// Frame id -> interned file/symbol identity. Frame rows keep one global
/// identity even though each Gecko thread has its own local tables.
fn frame_rows(
    table: &GeckoTable<'_>,
    strings: &[&str],
    label: &str,
    files: &mut FileCatalog,
) -> Result<Vec<Frame>, String> {
    let location = table.required("location")?;
    let mut frames = Vec::with_capacity(table.rows.len());
    for (index, row) in table.rows.iter().enumerate() {
        let cells = cells(row, label, index)?;
        let string_id = cell_index(cells, location, label, index)?;
        let location = strings.get(string_id).copied().ok_or_else(|| {
            format!("Gecko {label} frameTable[{index}].location is out of the stringTable range")
        })?;
        let (symbol, resource) = parse_location(location);
        frames.push(files.intern(symbol, resource, label)?);
    }
    Ok(frames)
}

/// Stack rows are (prefix, frame); a prefix must point at an earlier row so the
/// chain can never cycle, exactly like the Kotlin reader.
fn stack_rows(
    table: &GeckoTable<'_>,
    frame_count: usize,
    label: &str,
) -> Result<Vec<(Option<usize>, usize)>, String> {
    let prefix = table.required("prefix")?;
    let frame = table.required("frame")?;
    let mut stacks = Vec::with_capacity(table.rows.len());
    for (index, row) in table.rows.iter().enumerate() {
        let cells = cells(row, label, index)?;
        let prefix_id = optional_index(cells, prefix, label, index)?;
        if let Some(prefix_id) = prefix_id {
            if prefix_id >= index {
                return Err(format!(
                    "Gecko {label} stackTable[{index}].prefix points forward or at itself"
                ));
            }
        }
        let frame_id = cell_index(cells, frame, label, index)?;
        if frame_id >= frame_count {
            return Err(format!(
                "Gecko {label} stackTable[{index}].frame is out of the frameTable range"
            ));
        }
        stacks.push((prefix_id, frame_id));
    }
    Ok(stacks)
}

/// Samples become leaf-to-root engine samples. A null stack is a valid sample
/// without a callchain; its counters still exist, like the native parser.
fn sample_rows(
    table: &GeckoTable<'_>,
    stacks: &[(Option<usize>, usize)],
    frames: &[Frame],
    thread_id: i32,
    label: &str,
) -> Result<Vec<Sample>, String> {
    let stack = table.required("stack")?;
    let time = table.required("time")?;
    let mut samples = Vec::with_capacity(table.rows.len());
    for (index, row) in table.rows.iter().enumerate() {
        let cells = cells(row, label, index)?;
        let stack_id = optional_index(cells, stack, label, index)?;
        if stack_id.is_some_and(|id| id >= stacks.len()) {
            return Err(format!(
                "Gecko {label} samples[{index}].stack is out of the stackTable range"
            ));
        }
        let millis = cells
            .get(time)
            .and_then(Value::as_f64)
            .filter(|value| value.is_finite() && *value >= 0.0)
            .ok_or_else(|| {
                format!("Gecko {label} samples[{index}].time must be a finite non-negative millisecond value")
            })?;
        let nanos = millis * NANOS_PER_MILLISECOND;
        if !nanos.is_finite() || nanos > u64::MAX as f64 {
            return Err(format!(
                "Gecko {label} samples[{index}].time exceeds the nanosecond range"
            ));
        }
        let mut callchain = Vec::new();
        let mut cursor = stack_id;
        while let Some(id) = cursor {
            let (prefix, frame) = stacks[id];
            callchain.push(frames[frame].clone());
            if callchain.len() > MAX_CALLCHAIN_FRAMES {
                return Err(format!(
                    "Gecko {label} samples[{index}] callchain exceeds {MAX_CALLCHAIN_FRAMES} frames"
                ));
            }
            cursor = prefix;
        }
        samples.push(Sample {
            time: nanos.round() as u64,
            thread_id,
            event_count: 1,
            event_type_id: 0,
            callchain,
        });
    }
    Ok(samples)
}

/// A schema-addressed Gecko table: {"schema": {name: column}, "data": [[...]]}.
struct GeckoTable<'a> {
    label: String,
    schema: HashMap<&'a str, usize>,
    rows: &'a [Value],
}

impl<'a> GeckoTable<'a> {
    fn read(value: Option<&'a Value>, label: &str) -> Result<Self, String> {
        let object = value
            .and_then(Value::as_object)
            .ok_or_else(|| format!("Gecko {label} is missing"))?;
        let schema_object = object
            .get("schema")
            .and_then(Value::as_object)
            .ok_or_else(|| format!("Gecko {label}.schema is missing"))?;
        let mut schema = HashMap::with_capacity(schema_object.len());
        for (name, index) in schema_object {
            let index = index
                .as_u64()
                .and_then(|index| usize::try_from(index).ok())
                .ok_or_else(|| format!("Gecko {label}.schema.{name} must be a column index"))?;
            schema.insert(name.as_str(), index);
        }
        let rows = object
            .get("data")
            .and_then(Value::as_array)
            .map(Vec::as_slice)
            .ok_or_else(|| format!("Gecko {label}.data is missing"))?;
        Ok(Self {
            label: label.to_owned(),
            schema,
            rows,
        })
    }

    fn column(&self, name: &str) -> Option<usize> {
        self.schema.get(name).copied()
    }

    fn required(&self, name: &str) -> Result<usize, String> {
        self.column(name)
            .ok_or_else(|| format!("Gecko {} schema is missing {name}", self.label))
    }
}

fn cells<'a>(row: &'a Value, label: &str, index: usize) -> Result<&'a [Value], String> {
    row.as_array()
        .map(Vec::as_slice)
        .ok_or_else(|| format!("Gecko {label} row {index} must be an array"))
}

fn integer(value: Option<&Value>, label: &str) -> Result<u32, String> {
    value
        .and_then(Value::as_u64)
        .and_then(|value| u32::try_from(value).ok())
        .ok_or_else(|| format!("Gecko {label} must be an unsigned 32-bit integer"))
}

fn cell_index(cells: &[Value], column: usize, label: &str, index: usize) -> Result<usize, String> {
    cells
        .get(column)
        .and_then(Value::as_u64)
        .and_then(|value| usize::try_from(value).ok())
        .ok_or_else(|| format!("Gecko {label} row {index} column {column} must be an index"))
}

fn optional_index(
    cells: &[Value],
    column: usize,
    label: &str,
    index: usize,
) -> Result<Option<usize>, String> {
    match cells.get(column) {
        Some(Value::Null) | None => Ok(None),
        Some(value) => value
            .as_u64()
            .and_then(|value| usize::try_from(value).ok())
            .map(Some)
            .ok_or_else(|| format!("Gecko {label} row {index} column {column} must be an index")),
    }
}

fn read_strings<'a>(value: Option<&'a Value>, label: &str) -> Result<Vec<&'a str>, String> {
    value
        .and_then(Value::as_array)
        .ok_or_else(|| format!("Gecko {label}.stringTable is missing"))?
        .iter()
        .map(|entry| {
            entry
                .as_str()
                .ok_or_else(|| format!("Gecko {label}.stringTable entries must be strings"))
        })
        .collect()
}

/// Split a Gecko location into symbol and resource, the inverse of the Firefox
/// exporter's `symbol (in resource)` formatting. Unknown symbols keep the whole
/// location as their symbol, matching the Kotlin reader.
fn parse_location(location: &str) -> (&str, &str) {
    match location.rfind(LOCATION_SEPARATOR) {
        Some(index) if index > 0 && location.ends_with(')') => {
            let symbol = &location[..index];
            let resource = &location[index + LOCATION_SEPARATOR.len()..location.len() - 1];
            (
                if symbol.is_empty() {
                    UNKNOWN_SYMBOL
                } else {
                    symbol
                },
                if resource.is_empty() {
                    "<unknown-file>"
                } else {
                    resource
                },
            )
        }
        _ => (
            if location.is_empty() {
                UNKNOWN_SYMBOL
            } else {
                location
            },
            UNKNOWN_FILE,
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::{is_gecko_profile_prefix, parse_gecko_profile};
    use aps_simpleperf_engine::simpleperf::{Record, MAX_CALLCHAIN_FRAMES};

    fn thread(
        name: &str,
        tid: u32,
        pid: u32,
        samples: &str,
        frames: &str,
        stacks: &str,
        strings: &str,
    ) -> String {
        format!(
            "{{\"tid\":{tid},\"pid\":{pid},\"name\":\"{name}\",\"markers\":{{}},\"samples\":{{\"schema\":{{\"stack\":0,\"time\":1,\"responsiveness\":2}},\"data\":[{samples}]}},\"frameTable\":{{\"schema\":{{\"location\":0,\"category\":1}},\"data\":[{frames}]}},\"stackTable\":{{\"schema\":{{\"prefix\":0,\"frame\":1}},\"data\":[{stacks}]}},\"stringTable\":[{strings}]}}"
        )
    }

    fn profile(threads: &str) -> String {
        format!(
            "{{\"meta\":{{\"interval\":1,\"product\":\"Android Performance Studio\",\"version\":24}},\"libs\":[],\"threads\":[{threads}],\"processes\":[]}}"
        )
    }

    fn leaf_mid_root() -> String {
        thread(
            "main",
            42,
            42,
            r#"[2,10.5,0],[2,20.25,0],[null,30,0],[0,40,0]"#,
            r#"[0,2],[1,2],[2,2]"#,
            r#"[null,0],[0,1],[1,2]"#,
            r#""root (in /lib/liba.so)","mid (in /lib/liba.so)","leaf (in /lib/liba.so)""#,
        )
    }

    #[test]
    fn tables_become_leaf_to_root_engine_samples() {
        let stream = parse_gecko_profile(profile(&leaf_mid_root()).as_bytes()).unwrap();
        let samples: Vec<_> = stream
            .records
            .iter()
            .filter_map(|record| match record {
                Record::Sample(sample) => Some(sample),
                _ => None,
            })
            .collect();
        assert_eq!(samples.len(), 4);
        assert_eq!(samples[0].time, 10_500_000);
        assert_eq!(samples[1].time, 20_250_000);
        // Leaf first, root last: the engine's documented callchain order.
        assert_eq!(
            samples[0]
                .callchain
                .iter()
                .map(|frame| frame.symbol_id)
                .collect::<Vec<_>>(),
            vec![2, 1, 0]
        );
        assert!(samples[2].callchain.is_empty());
        assert_eq!(samples[3].callchain.len(), 1);
        let files: Vec<_> = stream
            .records
            .iter()
            .filter_map(|record| match record {
                Record::File(file) => Some(file),
                _ => None,
            })
            .collect();
        assert_eq!(files.len(), 1);
        assert_eq!(files[0].path, "/lib/liba.so");
        assert_eq!(files[0].symbols, vec!["root", "mid", "leaf"]);
    }

    #[test]
    fn unknown_locations_keep_the_whole_string_and_default_resource() {
        let thread = self::thread(
            "t",
            1,
            1,
            r#"[1,1.5,0]"#,
            r#"[0,0],[1,0]"#,
            r#"[null,0],[0,1]"#,
            r#""plain_symbol","file+0x10 (in /system/lib64/libb.so)""#,
        );
        let stream = parse_gecko_profile(profile(&thread).as_bytes()).unwrap();
        let sample = stream
            .records
            .iter()
            .find_map(|record| match record {
                Record::Sample(sample) => Some(sample),
                _ => None,
            })
            .unwrap();
        assert_eq!(sample.callchain.len(), 2);
        let files: Vec<_> = stream
            .records
            .iter()
            .filter_map(|record| match record {
                Record::File(file) => Some(file),
                _ => None,
            })
            .collect();
        assert_eq!(files.len(), 2);
        assert_eq!(files[0].path, "<gecko-profile>");
        assert_eq!(files[0].symbols, vec!["plain_symbol"]);
    }

    #[test]
    fn classification_prefix_is_cheap_and_strict_enough() {
        assert!(is_gecko_profile_prefix(
            br#"{"meta":{"version":24},"threads":[]}"#
        ));
        assert!(is_gecko_profile_prefix(br#"  {"meta":{},"version":24}"#));
        assert!(!is_gecko_profile_prefix(br#"{"cells":[]}"#));
        assert!(!is_gecko_profile_prefix(b"SIMPLEPERF\x01\x00"));
        assert!(!is_gecko_profile_prefix(b""));
    }

    #[test]
    fn malformed_profiles_fail_closed() {
        let cases = [
            (
                "unsupported version",
                profile(&leaf_mid_root()).replace("\"version\":24", "\"version\":23"),
            ),
            ("missing threads", r#"{"meta":{"version":24}}"#.to_owned()),
            (
                "forward prefix",
                profile(&thread(
                    "t",
                    1,
                    1,
                    r#"[1,1,0]"#,
                    r#"[0,0]"#,
                    r#"[1,0]"#,
                    r#""a""#,
                )),
            ),
            (
                "stack frame out of range",
                profile(&thread(
                    "t",
                    1,
                    1,
                    r#"[1,1,0]"#,
                    r#"[0,0]"#,
                    r#"[null,2]"#,
                    r#""a""#,
                )),
            ),
            (
                "sample stack out of range",
                profile(&thread(
                    "t",
                    1,
                    1,
                    r#"[3,1,0]"#,
                    r#"[0,0]"#,
                    r#"[null,0]"#,
                    r#""a""#,
                )),
            ),
            (
                "negative time",
                profile(&thread(
                    "t",
                    1,
                    1,
                    r#"[null,-1,0]"#,
                    r#"[0,0]"#,
                    r#"[null,0]"#,
                    r#""a""#,
                )),
            ),
        ];
        for (label, document) in cases {
            assert!(
                parse_gecko_profile(document.as_bytes()).is_err(),
                "{label} must be rejected"
            );
        }
    }

    #[test]
    fn firefox_export_round_trips_through_the_reader() {
        use crate::CpuSession;
        use aps_simpleperf_engine::simpleperf::{
            FileRecord, Frame, MetaInfo, Record, Sample, SimpleperfStream, ThreadRecord,
        };
        use std::io::Read;
        let frame = |file_id: u32, symbol_id: i32| Frame {
            virtual_address: 0,
            file_id,
            symbol_id,
            execution_type: 0,
        };
        let stream = SimpleperfStream {
            version: 1,
            records: vec![
                Record::File(FileRecord {
                    id: 1,
                    path: "/lib/liba.so".into(),
                    symbols: vec!["root".into(), "mid".into(), "leaf".into()],
                }),
                Record::Thread(ThreadRecord {
                    thread_id: 7,
                    process_id: 3,
                    name: "main".into(),
                }),
                Record::Sample(Sample {
                    time: 10_500_000,
                    thread_id: 7,
                    event_count: 1,
                    event_type_id: 0,
                    callchain: vec![frame(1, 2), frame(1, 1), frame(1, 0)],
                }),
                Record::Sample(Sample {
                    time: 20_000_000,
                    thread_id: 7,
                    event_count: 1,
                    event_type_id: 0,
                    callchain: Vec::new(),
                }),
                Record::MetaInfo(MetaInfo {
                    event_types: vec!["cpu-clock".into()],
                    app_package_name: None,
                    app_type: None,
                    android_sdk_version: None,
                    android_build_type: None,
                    trace_offcpu: false,
                }),
            ],
        };
        let mut session =
            CpuSession::from_stream(stream, std::path::PathBuf::from("unused"), 1 << 20).unwrap();
        let archive = session.export_firefox_profile().unwrap();
        let mut json = Vec::new();
        flate2::read::MultiGzDecoder::new(&archive[..])
            .read_to_end(&mut json)
            .unwrap();
        let parsed = parse_gecko_profile(&json).unwrap();
        let files: std::collections::HashMap<u32, &FileRecord> = parsed
            .records
            .iter()
            .filter_map(|record| match record {
                Record::File(file) => Some((file.id, file)),
                _ => None,
            })
            .collect();
        let samples: Vec<_> = parsed
            .records
            .iter()
            .filter_map(|record| match record {
                Record::Sample(sample) => Some(sample),
                _ => None,
            })
            .collect();
        assert_eq!(samples.len(), 2);
        assert_eq!(samples[0].time, 10_500_000);
        assert_eq!(samples[1].time, 20_000_000);
        let names: Vec<&str> = samples[0]
            .callchain
            .iter()
            .map(|frame| {
                files[&frame.file_id].symbols[usize::try_from(frame.symbol_id).unwrap()].as_str()
            })
            .collect();
        // The Firefox exporter and the engine both store leaf-to-root callchains,
        // so an export/import cycle must preserve orientation and names.
        assert_eq!(names, vec!["leaf", "mid", "root"]);
        assert!(samples[1].callchain.is_empty());
        let threads: Vec<_> = parsed
            .records
            .iter()
            .filter_map(|record| match record {
                Record::Thread(thread) => Some(thread),
                _ => None,
            })
            .collect();
        assert_eq!(threads.len(), 1);
        assert_eq!(threads[0].thread_id, 7);
        assert_eq!(threads[0].process_id, 3);
        assert_eq!(threads[0].name, "main");
    }

    #[test]
    fn callchains_are_bounded() {
        // A chain longer than the engine budget is rejected instead of truncated.
        let depth = MAX_CALLCHAIN_FRAMES + 1;
        let frames: Vec<String> = (0..depth).map(|_| "[0,0]".to_owned()).collect();
        let stacks: Vec<String> = (0..depth)
            .map(|index| {
                if index == 0 {
                    "[null,0]".to_owned()
                } else {
                    format!("[{},0]", index - 1)
                }
            })
            .collect();
        let thread = self::thread(
            "t",
            1,
            1,
            &format!("[{},{depth},0]", depth - 1),
            &frames.join(","),
            &stacks.join(","),
            r#""a""#,
        );
        assert!(parse_gecko_profile(profile(&thread).as_bytes()).is_err());
    }
}
