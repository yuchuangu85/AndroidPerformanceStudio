#[path = "../../../../desktop-viewer/simpleperf-viewer/native-engine-rust/tests/common/mod.rs"]
mod common;

use aps_cpu::{
    CpuSession, Direction, Query, WeightType, MAX_HTML_CALL_TREE_ROWS, MAX_HTML_TOP_FUNCTIONS,
    MAX_REPORT_CALL_TREE_ROWS, MAX_REPORT_TOP_FUNCTIONS,
};
use aps_json::{Limits, Value};
use aps_simpleperf_engine::simpleperf::{Record, SimpleperfStream, ThreadRecord};
use common::{encoded, profile, sample};
use std::collections::{BTreeMap, BTreeSet};
use std::io::Read;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};

static SEQUENCE: AtomicUsize = AtomicUsize::new(0);

struct Fixture(PathBuf);

impl Fixture {
    fn bytes(bytes: &[u8]) -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-cpu-exports-{}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos(),
            SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&path).unwrap();
        std::fs::write(path.join("input"), bytes).unwrap();
        Self(path)
    }

    fn stream(stream: &SimpleperfStream) -> Self {
        Self::bytes(&encoded(stream))
    }

    fn session(&self) -> CpuSession {
        CpuSession::open(self.0.join("input")).unwrap()
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn json(bytes: &[u8]) -> Value {
    aps_json::parse_slice(bytes, Limits::default()).unwrap()
}

fn gunzip(bytes: &[u8]) -> Vec<u8> {
    assert_eq!(&bytes[..3], &[0x1f, 0x8b, 8]);
    let mut output = Vec::new();
    flate2::read::MultiGzDecoder::new(bytes)
        .read_to_end(&mut output)
        .unwrap();
    output
}

fn cycles(direction: Direction, weight_type: WeightType) -> Query {
    Query {
        direction,
        weight_type,
        event_types: BTreeSet::from(["cpu-cycles".into()]),
        ..Query::default()
    }
}

#[test]
fn report_json_preserves_filters_whole_session_summary_and_selected_counts() {
    let mut source = profile();
    source.records.push(Record::Lost {
        sample_count: 100,
        lost_count: 4,
    });
    let fixture = Fixture::stream(&source);
    let mut session = fixture.session();
    for direction in [Direction::Forward, Direction::Inverted] {
        for weight_type in [WeightType::Samples, WeightType::EventCount] {
            let query = Query {
                thread_ids: BTreeSet::from([11, 22]),
                start: Some(10),
                end: Some(50),
                offset: 999,
                limit: 0,
                ..cycles(direction, weight_type)
            };
            let text = session.export_report_json(&query).unwrap();
            let report = json(text.as_bytes());
            assert_eq!(report["kind"], "aps-cpu-report-v1");
            let summary = &report["summary"];
            assert_eq!(summary["samples"], 6);
            assert_eq!(summary["reportedSamples"], 100);
            assert_eq!(summary["lostSamples"], 4);
            assert_eq!(summary["totalEvents"], 230);
            assert_eq!(summary["threads"], 4);
            assert_eq!(summary["files"], 1);
            assert_eq!(summary["firstTimestampNanos"], 10);
            assert_eq!(summary["lastTimestampNanos"], 60);
            assert_eq!(report["query"]["direction"], direction.as_str());
            assert_eq!(report["query"]["weightType"], weight_type.as_str());
            assert_eq!(report["query"]["threadIds"], Value::from(vec![11, 22]));
            assert_eq!(
                report["query"]["eventTypes"],
                Value::from(vec!["cpu-cycles"])
            );
            assert_eq!(report["query"]["startNanos"], 10);
            assert_eq!(report["query"]["endNanos"], 50);
            let expected_weight = if weight_type == WeightType::Samples {
                2
            } else {
                8
            };
            let top = report["topFunctions"].as_array().unwrap();
            assert_eq!(top.len(), 2, "pagination must not truncate exports");
            let leaf = top.iter().find(|r| r["symbol"] == "leaf").unwrap();
            assert_eq!(leaf["inclusive"], expected_weight);
            assert_eq!(leaf["self"], expected_weight);
            assert_eq!(leaf["samples"], 2);
            assert_eq!(leaf["threads"], 2);
            let rows = report["callTree"].as_array().unwrap();
            assert_eq!(rows.len(), 2);
            assert_eq!(
                rows[0]["symbol"],
                if direction == Direction::Forward {
                    "root"
                } else {
                    "leaf"
                }
            );
            assert_eq!(
                rows.iter()
                    .map(|r| r["self"].as_u64().unwrap())
                    .sum::<u64>(),
                expected_weight
            );
            assert_eq!(rows[0]["samples"], 2);
        }
    }
    let report = json(
        session
            .export_report_json(&Query {
                start: Some(61),
                ..Query::default()
            })
            .unwrap()
            .as_bytes(),
    );
    assert!(report["topFunctions"].as_array().unwrap().is_empty());
    assert!(report["callTree"].as_array().unwrap().is_empty());
    assert_eq!(report["summary"]["samples"], 6);
}

#[test]
fn reports_preserve_raw_unicode_and_escape_json_html_and_folded_delimiters() {
    let root = "root;<script>&\"'\r\n\t\0\u{96ea}";
    let leaf = "leaf;line\nnext\r";
    let library = "<lib&\"'\\path.so>";
    let mut source = profile();
    for record in &mut source.records {
        if let Record::File(file) = record {
            file.path = library.into();
            file.symbols = vec![root.into(), leaf.into(), "other".into()];
        }
    }
    let fixture = Fixture::stream(&source);
    let mut session = fixture.session();
    let query = cycles(Direction::Forward, WeightType::EventCount);
    let report_text = session.export_report_json(&query).unwrap();
    let report = json(report_text.as_bytes());
    assert_eq!(report["callTree"][0]["symbol"], root);
    assert_eq!(report["callTree"][1]["symbol"], leaf);
    assert_eq!(report["callTree"][0]["library"], library);
    assert!(report_text.contains("\\u0000"));
    let html = session.export_html_report(&query).unwrap();
    assert!(html.starts_with("<!DOCTYPE html>"));
    assert!(html.ends_with("</html>\n"));
    assert!(html.contains("&lt;script&gt;&amp;&quot;&#39;"));
    assert!(html.contains("&lt;lib&amp;&quot;&#39;\\path.so&gt;"));
    assert!(!html.contains("<script>"));
    assert!(!html.contains("<lib&"));
    let folded = session.export_folded_stacks(&query).unwrap();
    let expected = format!(
        "{};{} 15\n",
        root.replace([';', '\n', '\r'], " "),
        leaf.replace([';', '\n', '\r'], " ")
    );
    assert_eq!(folded, expected);
}

#[test]
fn report_row_caps_explicitly_disclose_omitted_data() {
    let count = MAX_REPORT_CALL_TREE_ROWS + 1;
    let source = SimpleperfStream {
        version: 1,
        records: (0..count)
            .map(|n| sample(n as u64, 11, 0, 1, &[n as i32]))
            .collect(),
    };
    let fixture = Fixture::stream(&source);
    let mut session = fixture.session();
    let report = json(
        session
            .export_report_json(&Query::default())
            .unwrap()
            .as_bytes(),
    );
    assert_eq!(
        report["topFunctions"].as_array().unwrap().len(),
        MAX_REPORT_TOP_FUNCTIONS
    );
    assert_eq!(
        report["callTree"].as_array().unwrap().len(),
        MAX_REPORT_CALL_TREE_ROWS
    );
    assert_eq!(report["topFunctionsTotal"], count);
    assert_eq!(report["callTreeTotal"], count);
    assert_eq!(report["topFunctionsTruncated"], true);
    assert_eq!(report["callTreeTruncated"], true);
    let html = session.export_html_report(&Query::default()).unwrap();
    assert!(html.contains(&format!(
        "Top functions truncated: showing {MAX_HTML_TOP_FUNCTIONS} of {count} rows."
    )));
    assert!(html.contains(&format!(
        "Call tree truncated: showing {MAX_HTML_CALL_TREE_ROWS} of {count} rows."
    )));
}

#[derive(Debug)]
enum Field<'a> {
    Varint(u32, u64),
    Bytes(u32, &'a [u8]),
}

fn varint(bytes: &mut &[u8]) -> u64 {
    let mut value = 0;
    for shift in (0..70).step_by(7) {
        let (&byte, rest) = bytes.split_first().expect("truncated protobuf varint");
        *bytes = rest;
        assert!(shift < 63 || byte <= 1, "protobuf varint overflow");
        value |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return value;
        }
    }
    panic!("unterminated protobuf varint");
}

fn fields(mut bytes: &[u8]) -> Vec<Field<'_>> {
    let mut result = Vec::new();
    while !bytes.is_empty() {
        let tag = varint(&mut bytes);
        let number = u32::try_from(tag >> 3).unwrap();
        assert!((1..(1 << 29)).contains(&number));
        match tag & 7 {
            0 => result.push(Field::Varint(number, varint(&mut bytes))),
            2 => {
                let length = usize::try_from(varint(&mut bytes)).unwrap();
                let (value, rest) = bytes.split_at(length);
                bytes = rest;
                result.push(Field::Bytes(number, value));
            }
            wire => panic!("unexpected exported protobuf wire type {wire}"),
        }
    }
    result
}

fn numbers(fields: &[Field<'_>], number: u32) -> Vec<u64> {
    fields
        .iter()
        .filter_map(|field| match field {
            Field::Varint(n, value) if *n == number => Some(*value),
            _ => None,
        })
        .collect()
}

fn messages<'a>(fields: &[Field<'a>], number: u32) -> Vec<&'a [u8]> {
    fields
        .iter()
        .filter_map(|field| match field {
            Field::Bytes(n, value) if *n == number => Some(*value),
            _ => None,
        })
        .collect()
}

fn packed(mut bytes: &[u8]) -> Vec<u64> {
    let mut result = Vec::new();
    while !bytes.is_empty() {
        result.push(varint(&mut bytes));
    }
    result
}

fn pprof_samples(bytes: &[u8]) -> Vec<(Vec<String>, u64)> {
    let decoded = gunzip(bytes);
    let profile = fields(&decoded);
    let strings: Vec<_> = messages(&profile, 6)
        .into_iter()
        .map(|s| std::str::from_utf8(s).unwrap())
        .collect();
    assert_eq!(strings.first(), Some(&""));
    let sample_types = messages(&profile, 1);
    assert_eq!(sample_types.len(), 1);
    let sample_type = fields(sample_types[0]);
    assert_eq!(strings[numbers(&sample_type, 1)[0] as usize], "samples");
    assert_eq!(strings[numbers(&sample_type, 2)[0] as usize], "count");
    let mut functions = BTreeMap::new();
    for function in messages(&profile, 5) {
        let function = fields(function);
        let id = numbers(&function, 1);
        assert_eq!(id.len(), 1);
        assert_ne!(id[0], 0);
        let name = numbers(&function, 2);
        assert_eq!(name.len(), 1);
        for n in [3, 4] {
            for index in numbers(&function, n) {
                assert!((index as usize) < strings.len());
            }
        }
        assert!(functions.insert(id[0], strings[name[0] as usize]).is_none());
    }
    let mut locations = BTreeMap::new();
    for location in messages(&profile, 4) {
        let location = fields(location);
        let id = numbers(&location, 1);
        assert_eq!(id.len(), 1);
        assert_ne!(id[0], 0);
        let lines = messages(&location, 4);
        assert_eq!(lines.len(), 1);
        let function_id = numbers(&fields(lines[0]), 1);
        assert_eq!(function_id.len(), 1);
        assert!(locations
            .insert(id[0], *functions.get(&function_id[0]).unwrap())
            .is_none());
    }
    messages(&profile, 2)
        .into_iter()
        .map(|sample| {
            let sample = fields(sample);
            let chains = messages(&sample, 1);
            assert_eq!(chains.len(), 1);
            let ids = packed(chains[0]);
            assert!(!ids.is_empty());
            let names = ids
                .iter()
                .map(|id| locations.get(id).unwrap().to_string())
                .collect();
            let values = messages(&sample, 2);
            assert_eq!(values.len(), 1);
            let values = packed(values[0]);
            assert_eq!(values.len(), sample_types.len());
            assert!(values[0] <= i64::MAX as u64);
            (names, values[0])
        })
        .collect()
}

#[test]
fn pprof_is_valid_gzip_and_protobuf_with_filtered_recursive_self_totals() {
    let mut source = profile();
    source.records.push(sample(25, 11, 0, 7, &[1, 1, 0]));
    let fixture = Fixture::stream(&source);
    let mut session = fixture.session();
    for direction in [Direction::Forward, Direction::Inverted] {
        for weight_type in [WeightType::Samples, WeightType::EventCount] {
            let query = Query {
                start: Some(10),
                end: Some(50),
                offset: 999,
                limit: 0,
                ..cycles(direction, weight_type)
            };
            let archive = session.export_pprof(&query).unwrap();
            assert_eq!(session.export_pprof(&query).unwrap(), archive);
            let samples = pprof_samples(&archive);
            let self_total: u64 = session
                .full_call_tree(&query, 100)
                .unwrap()
                .rows
                .iter()
                .map(|r| r.self_weight)
                .sum();
            assert_eq!(
                samples.iter().map(|(_, weight)| *weight).sum::<u64>(),
                self_total
            );
            assert_eq!(
                self_total,
                if weight_type == WeightType::Samples {
                    3
                } else {
                    15
                }
            );
            if direction == Direction::Forward {
                assert!(samples
                    .iter()
                    .any(|(names, _)| names == &["leaf", "leaf", "root"]));
            }
        }
    }
    let query = Query {
        start: Some(100),
        ..Query::default()
    };
    assert!(pprof_samples(&session.export_pprof(&query).unwrap()).is_empty());
}

#[test]
fn firefox_export_keeps_declared_threads_without_samples() {
    let source = SimpleperfStream {
        version: 1,
        records: vec![Record::Thread(ThreadRecord {
            thread_id: 44,
            process_id: 1,
            name: "declared-without-samples".into(),
        })],
    };
    let fixture = Fixture::stream(&source);
    let session = fixture.session();
    let bytes = gunzip(&session.export_firefox_profile().unwrap());
    let exported = Fixture::bytes(&bytes);
    assert_eq!(exported.session().threads(), session.threads());
    assert_eq!(exported.session().summary().sample_count, 0);
}

#[test]
fn firefox_export_sorts_samples_stably_and_reimports_all_thread_metadata() {
    let mut source = profile();
    source.records.retain(|r| !matches!(r, Record::Sample(_)));
    source.records.extend([
        sample(30_000_000, 11, 0, 3, &[1, 0]),
        sample(20_000_000, 22, 0, 5, &[2, 0]),
        sample(10_000_000, 11, 0, 7, &[2, 0]),
        sample(10_000_000, 11, 0, 11, &[1, 1, 0]),
        sample(40_000_000, 22, 0, 13, &[]),
        Record::Thread(ThreadRecord {
            thread_id: 11,
            process_id: 1,
            name: "main\"\\\n".into(),
        }),
        Record::Thread(ThreadRecord {
            thread_id: 22,
            process_id: 1,
            name: "worker".into(),
        }),
    ]);
    let fixture = Fixture::stream(&source);
    let session = fixture.session();
    let archive = session.export_firefox_profile().unwrap();
    assert_eq!(session.export_firefox_profile().unwrap(), archive);
    let bytes = gunzip(&archive);
    let document = json(&bytes);
    assert_eq!(document["meta"]["version"], 24);
    let threads = document["threads"].as_array().unwrap();
    let main = threads.iter().find(|t| t["tid"] == 11).unwrap();
    let samples = main["samples"]["data"].as_array().unwrap();
    assert_eq!(
        samples
            .iter()
            .map(|r| r[1].as_f64().unwrap())
            .collect::<Vec<_>>(),
        [10.0, 10.0, 30.0]
    );
    let stack_names = |row: &Value| {
        let mut stack = row[0].as_u64().map(|v| v as usize);
        let mut names = Vec::new();
        while let Some(index) = stack {
            let row = &main["stackTable"]["data"][index];
            let frame = row[1].as_u64().unwrap() as usize;
            let string = main["frameTable"]["data"][frame][0].as_u64().unwrap() as usize;
            names.push(main["stringTable"][string].as_str().unwrap().to_owned());
            stack = row[0].as_u64().map(|v| v as usize);
        }
        names
    };
    assert_eq!(
        stack_names(&samples[0]),
        ["other (in fixture.so)", "root (in fixture.so)"]
    );
    assert_eq!(
        stack_names(&samples[1]),
        [
            "leaf (in fixture.so)",
            "leaf (in fixture.so)",
            "root (in fixture.so)"
        ]
    );
    let exported = Fixture::bytes(&bytes);
    let mut reopened = exported.session();
    assert_eq!(reopened.summary().sample_count, 5);
    assert_eq!(reopened.threads(), session.threads());
    assert_eq!(reopened.summary().first_timestamp_nanos, Some(10_000_000));
    assert_eq!(reopened.summary().last_timestamp_nanos, Some(40_000_000));
    for thread in [11, 22, 44] {
        let query = Query {
            thread_ids: BTreeSet::from([thread]),
            ..Query::default()
        };
        let top = reopened.top_functions(&query, 0, 100).unwrap();
        assert_eq!(
            top.rows.iter().map(|r| r.self_sample_count).sum::<u64>(),
            match thread {
                11 => 3,
                22 => 1,
                _ => 0,
            }
        );
    }
    let mut corrupt = archive;
    let crc_offset = corrupt.len() - 8;
    corrupt[crc_offset] ^= 1;
    assert!(flate2::read::MultiGzDecoder::new(&corrupt[..])
        .read_to_end(&mut Vec::new())
        .is_err());
}

fn u16_at(bytes: &[u8], offset: usize) -> u16 {
    u16::from_le_bytes(bytes[offset..offset + 2].try_into().unwrap())
}

fn u32_at(bytes: &[u8], offset: usize) -> u32 {
    u32::from_le_bytes(bytes[offset..offset + 4].try_into().unwrap())
}

fn crc32(bytes: &[u8]) -> u32 {
    let mut crc = !0u32;
    for byte in bytes {
        crc ^= u32::from(*byte);
        for _ in 0..8 {
            crc = (crc >> 1) ^ (0xedb8_8320 & 0u32.wrapping_sub(crc & 1));
        }
    }
    !crc
}

fn zip_entries(bytes: &[u8]) -> BTreeMap<String, &[u8]> {
    assert_eq!(crc32(b"123456789"), 0xcbf4_3926);
    let end = bytes.len() - 22;
    assert_eq!(u32_at(bytes, end), 0x0605_4b50);
    assert_eq!(u16_at(bytes, end + 4), 0);
    assert_eq!(u16_at(bytes, end + 6), 0);
    let count = u16_at(bytes, end + 8);
    assert_eq!(u16_at(bytes, end + 10), count);
    assert_eq!(u16_at(bytes, end + 20), 0);
    let mut cursor = u32_at(bytes, end + 16) as usize;
    assert_eq!(cursor + u32_at(bytes, end + 12) as usize, end);
    let central_start = cursor;
    let mut local_end = 0;
    let mut result = BTreeMap::new();
    for _ in 0..count {
        assert_eq!(u32_at(bytes, cursor), 0x0201_4b50);
        assert_eq!(u16_at(bytes, cursor + 8), 0);
        assert_eq!(u16_at(bytes, cursor + 10), 0);
        let crc = u32_at(bytes, cursor + 16);
        let size = u32_at(bytes, cursor + 20) as usize;
        assert_eq!(u32_at(bytes, cursor + 24) as usize, size);
        let name_len = u16_at(bytes, cursor + 28) as usize;
        assert_eq!(u16_at(bytes, cursor + 30), 0);
        assert_eq!(u16_at(bytes, cursor + 32), 0);
        let local = u32_at(bytes, cursor + 42) as usize;
        assert_eq!(local, local_end);
        let name = &bytes[cursor + 46..cursor + 46 + name_len];
        assert_eq!(u32_at(bytes, local), 0x0403_4b50);
        assert_eq!(u16_at(bytes, local + 6), 0);
        assert_eq!(u16_at(bytes, local + 8), 0);
        assert_eq!(u32_at(bytes, local + 14), crc);
        assert_eq!(u32_at(bytes, local + 18) as usize, size);
        assert_eq!(u32_at(bytes, local + 22) as usize, size);
        assert_eq!(u16_at(bytes, local + 26) as usize, name_len);
        assert_eq!(u16_at(bytes, local + 28), 0);
        assert_eq!(&bytes[local + 30..local + 30 + name_len], name);
        local_end = local + 30 + name_len + size;
        let content = &bytes[local + 30 + name_len..local_end];
        assert_eq!(crc32(content), crc);
        assert!(result
            .insert(std::str::from_utf8(name).unwrap().to_owned(), content)
            .is_none());
        cursor += 46 + name_len;
    }
    assert_eq!(cursor, end);
    assert_eq!(local_end, central_start);
    result
}

#[test]
fn session_zip_entries_crc_manifest_raw_bytes_and_report_are_consistent() {
    let source = profile();
    let raw = encoded(&source);
    let fixture = Fixture::bytes(&raw);
    let mut session = fixture.session();
    let query = Query {
        thread_ids: BTreeSet::from([22]),
        start: Some(20),
        end: Some(40),
        ..cycles(Direction::Inverted, WeightType::EventCount)
    };
    let archive = session.export_session_package(&query).unwrap();
    assert_eq!(session.export_session_package(&query).unwrap(), archive);
    let entries = zip_entries(&archive);
    assert_eq!(
        entries.keys().map(String::as_str).collect::<Vec<_>>(),
        ["manifest.json", "profile.simpleperf.pb", "report.json"]
    );
    assert_eq!(entries["profile.simpleperf.pb"], raw);
    let manifest = json(entries["manifest.json"]);
    assert_eq!(manifest["kind"], "aps-cpu-session-v1");
    assert_eq!(manifest["profileBytes"], raw.len());
    assert_eq!(manifest["profileFileName"], "profile.simpleperf.pb");
    assert_eq!(manifest["sampleCount"], 6);
    assert_eq!(manifest["threadCount"], 4);
    let report = json(entries["report.json"]);
    assert_eq!(manifest["query"], report["query"]);
    assert_eq!(
        entries["report.json"],
        session.export_report_json(&query).unwrap().as_bytes()
    );
    assert_eq!(report["topFunctions"][0]["self"], 5);
    let reopened = Fixture::bytes(entries["profile.simpleperf.pb"]);
    assert_eq!(
        reopened.session().query(&query).unwrap(),
        session.query(&query).unwrap()
    );
}

#[test]
fn empty_session_exports_remain_valid_and_invalid_queries_publish_nothing() {
    let fixture = Fixture::stream(&SimpleperfStream {
        version: 1,
        records: Vec::new(),
    });
    let mut session = fixture.session();
    let query = Query::default();
    assert_eq!(session.export_folded_stacks(&query).unwrap(), "");
    assert!(pprof_samples(&session.export_pprof(&query).unwrap()).is_empty());
    let report = json(session.export_report_json(&query).unwrap().as_bytes());
    assert!(report["summary"]["firstTimestampNanos"].is_null());
    assert!(report["summary"]["lastTimestampNanos"].is_null());
    let firefox = json(&gunzip(&session.export_firefox_profile().unwrap()));
    assert!(firefox["threads"].as_array().unwrap().is_empty());
    let archive = session.export_session_package(&query).unwrap();
    assert_eq!(zip_entries(&archive).len(), 3);
    for invalid in [
        Query {
            thread_ids: BTreeSet::from([999]),
            ..Query::default()
        },
        Query {
            start: Some(20),
            end: Some(10),
            ..Query::default()
        },
        Query {
            limit: 513,
            ..Query::default()
        },
    ] {
        assert!(session.export_report_json(&invalid).is_err());
        assert!(session.export_html_report(&invalid).is_err());
        assert!(session.export_folded_stacks(&invalid).is_err());
        assert!(session.export_pprof(&invalid).is_err());
        assert!(session.export_session_package(&invalid).is_err());
    }
}
