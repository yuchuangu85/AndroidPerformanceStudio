mod common;

use aps_simpleperf_engine::{analyze, query_path, wire, Query};
use common::{encoded, profile, sample};
use std::fs;
use std::io::{Cursor, Write};
use std::path::{Path, PathBuf};
use std::process::{Command, Output, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};

struct Fixture(PathBuf);

impl Fixture {
    fn new(bytes: &[u8]) -> Self {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "aps-cpu-query-{}-{}.trace",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&path)
            .unwrap()
            .write_all(bytes)
            .unwrap();
        Self(path)
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

fn query(path: &Path, args: &[&str]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_aps-simpleperf-engine"))
        .arg("query")
        .arg(path)
        .args(args)
        .env_remove("APS_SIMPLEPERF_MAX_INPUT_BYTES")
        .output()
        .unwrap()
}

fn response(output: Output) -> wire::Value {
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(output.stderr.is_empty());
    let mut cursor = Cursor::new(output.stdout);
    let result = wire::decode(&wire::read_frame(&mut cursor).unwrap().unwrap()).unwrap();
    assert!(wire::read_frame(&mut cursor).unwrap().is_none());
    assert_eq!(result.get("version").unwrap().as_int().unwrap(), 1);
    assert_eq!(result.get("status").unwrap().as_str().unwrap(), "ok");
    assert_eq!(result.as_map().unwrap().len(), 3);
    result
}

#[test]
fn one_shot_query_writes_exactly_one_typed_frame_with_the_contract_fields() {
    let fixture = Fixture::new(&encoded(&profile()));
    let root_response = response(query(
        &fixture.0,
        &[
            "11,22",
            "cpu-cycles",
            "10",
            "50",
            "forward",
            "events",
            "0",
            "1",
        ],
    ));
    let payload = root_response.get("payload").unwrap();
    assert_eq!(payload.as_map().unwrap().len(), 5);
    assert_eq!(payload.get("total").unwrap().as_int().unwrap(), 2);
    assert_eq!(payload.get("offset").unwrap().as_int().unwrap(), 0);
    assert_eq!(
        payload.get("weightType").unwrap().as_str().unwrap(),
        "events"
    );
    assert_eq!(
        payload.get("direction").unwrap().as_str().unwrap(),
        "forward"
    );
    let rows = payload.get("rows").unwrap().as_list().unwrap();
    assert_eq!(rows.len(), 1);
    let root = &rows[0];
    assert_eq!(root.as_map().unwrap().len(), 9);
    assert!(matches!(root.get("parentId").unwrap(), wire::Value::Null));
    for (field, expected) in [
        ("depth", 0),
        ("inclusiveWeight", 8),
        ("selfWeight", 0),
        ("sampleCount", 2),
        ("selfSampleCount", 0),
    ] {
        assert_eq!(root.get(field).unwrap().as_int().unwrap(), expected);
    }
    assert_eq!(root.get("name").unwrap().as_str().unwrap(), "root");
    assert_eq!(
        root.get("resource").unwrap().as_str().unwrap(),
        "fixture.so"
    );
    let leaf_response = response(query(
        &fixture.0,
        &[
            "11,22",
            "cpu-cycles",
            "10",
            "50",
            "forward",
            "events",
            "1",
            "1",
        ],
    ));
    let leaf = &leaf_response
        .get("payload")
        .unwrap()
        .get("rows")
        .unwrap()
        .as_list()
        .unwrap()[0];
    assert_eq!(
        leaf.get("parentId").unwrap().as_str().unwrap(),
        root.get("id").unwrap().as_str().unwrap()
    );
    assert_eq!(leaf.get("selfWeight").unwrap().as_int().unwrap(), 8);
    let inverted = response(query(
        &fixture.0,
        &[
            "-",
            "cpu-cycles",
            "-",
            "-",
            "inverted",
            "samples",
            "0",
            "512",
        ],
    ));
    let payload = inverted.get("payload").unwrap();
    assert_eq!(
        payload.get("direction").unwrap().as_str().unwrap(),
        "inverted"
    );
    assert_eq!(
        payload.get("weightType").unwrap().as_str().unwrap(),
        "samples"
    );
    let leaf = &payload.get("rows").unwrap().as_list().unwrap()[0];
    assert_eq!(leaf.get("selfWeight").unwrap().as_int().unwrap(), 3);
}

#[test]
fn large_cli_pages_echo_modes_and_have_exact_length_and_nonnegative_i64_fields() {
    let stream = aps_simpleperf_engine::simpleperf::SimpleperfStream {
        version: 1,
        records: (0..600)
            .map(|symbol| sample(10, 11, 0, 0, &[symbol]))
            .collect(),
    };
    let fixture = Fixture::new(&encoded(&stream));
    for (direction, weight) in [("forward", "events"), ("inverted", "samples")] {
        for (offset, limit) in [
            (0usize, 512usize),
            (1, 512),
            (511, 512),
            (512, 7),
            (599, 512),
            (600, 512),
            (999, 1),
            (0, 0),
        ] {
            let result = response(query(
                &fixture.0,
                &[
                    "-",
                    "-",
                    "-",
                    "-",
                    direction,
                    weight,
                    &offset.to_string(),
                    &limit.to_string(),
                ],
            ));
            let payload = result.get("payload").unwrap();
            assert_eq!(
                payload.get("direction").unwrap().as_str().unwrap(),
                direction
            );
            assert_eq!(payload.get("weightType").unwrap().as_str().unwrap(), weight);
            assert_eq!(payload.get("total").unwrap().as_int().unwrap(), 600);
            assert_eq!(
                payload.get("offset").unwrap().as_int().unwrap(),
                offset as i64
            );
            let rows = payload.get("rows").unwrap().as_list().unwrap();
            assert_eq!(rows.len(), 600usize.saturating_sub(offset).min(limit));
            for row in rows {
                assert!(row
                    .get("id")
                    .unwrap()
                    .as_str()
                    .unwrap()
                    .starts_with("aps-cpu-v1:"));
                assert!(matches!(row.get("parentId").unwrap(), wire::Value::Null));
                for field in [
                    "depth",
                    "inclusiveWeight",
                    "selfWeight",
                    "sampleCount",
                    "selfSampleCount",
                ] {
                    assert!(row.get(field).unwrap().as_int().unwrap() >= 0);
                }
                assert_eq!(row.get("sampleCount").unwrap().as_int().unwrap(), 1);
                assert_eq!(row.get("selfSampleCount").unwrap().as_int().unwrap(), 1);
                assert_eq!(
                    row.get("selfWeight").unwrap().as_int().unwrap(),
                    if weight == "samples" { 1 } else { 0 }
                );
            }
        }
    }
}

#[test]
fn deep_calltree_is_flat_on_wire_without_hitting_typed_value_nesting_limit() {
    let stream = aps_simpleperf_engine::simpleperf::SimpleperfStream {
        version: 1,
        records: vec![sample(10, 11, 0, 9, &vec![0; 512])],
    };
    let fixture = Fixture::new(&encoded(&stream));
    for direction in ["forward", "inverted"] {
        let result = response(query(
            &fixture.0,
            &["-", "-", "-", "-", direction, "events", "0", "512"],
        ));
        let payload = result.get("payload").unwrap();
        let rows = payload.get("rows").unwrap().as_list().unwrap();
        assert_eq!(rows.len(), 512);
        for (index, row) in rows.iter().enumerate() {
            assert_eq!(row.get("depth").unwrap().as_int().unwrap(), index as i64);
            if index > 0 {
                assert_eq!(
                    row.get("parentId").unwrap().as_str().unwrap(),
                    rows[index - 1].get("id").unwrap().as_str().unwrap()
                );
            }
            let is_self = if direction == "inverted" {
                index == 0
            } else {
                index == 511
            };
            assert_eq!(
                row.get("selfSampleCount").unwrap().as_int().unwrap(),
                i64::from(is_self)
            );
            assert_eq!(
                row.get("selfWeight").unwrap().as_int().unwrap(),
                if is_self { 9 } else { 0 }
            );
        }
    }
}

#[test]
fn invalid_cli_filters_bounds_and_modes_fail_without_any_stdout() {
    let fixture = Fixture::new(&encoded(&profile()));
    let good = ["-", "-", "-", "-", "forward", "samples", "0", "512"];
    for (index, invalid) in [
        (0, "999"),
        (0, "11,999"),
        (0, ""),
        (0, "11,"),
        (0, "11,-"),
        (0, " 11"),
        (0, "2147483648"),
        (1, "missing"),
        (1, "cpu-cycles,missing"),
        (1, "cpu-cycles,"),
        (2, "-1"),
        (2, "18446744073709551616"),
        (2, "+1"),
        (3, "0.5"),
        (4, "sideways"),
        (5, "nanoseconds"),
        (5, "EVENT_COUNT"),
        (5, "events"),
        (6, "-1"),
        (6, "9223372036854775808"),
        (6, "+1"),
        (7, "513"),
        (7, "-1"),
    ] {
        let mut args = good;
        args[index] = invalid;
        let output = query(&fixture.0, &args);
        assert!(!output.status.success(), "accepted {args:?}");
        assert!(output.stdout.is_empty(), "partial response for {args:?}");
        assert!(!output.stderr.is_empty());
    }
    for (start, end) in [("20", "10"), ("10", "10")] {
        let mut args = good;
        args[2] = start;
        args[3] = end;
        assert!(!query(&fixture.0, &args).status.success());
    }
    assert!(!query(&fixture.0, &good[..7]).status.success());
}

#[test]
fn malformed_truncated_and_oversized_files_never_produce_success_frames() {
    let valid = encoded(&profile());
    for bytes in [
        &b"bad"[..],
        &valid[..valid.len() - 1],
        &valid[..valid.len() - 4],
    ] {
        let fixture = Fixture::new(bytes);
        let output = query(
            &fixture.0,
            &["-", "-", "-", "-", "forward", "samples", "0", "512"],
        );
        assert!(!output.status.success());
        assert!(output.stdout.is_empty());
    }
    let mut malformed = valid.clone();
    malformed.truncate(malformed.len() - 4);
    malformed.extend([1, 0, 0, 0, 0, 0, 0, 0, 0]);
    let fixture = Fixture::new(&malformed);
    let output = query(
        &fixture.0,
        &["-", "-", "-", "-", "forward", "samples", "0", "0"],
    );
    assert!(!output.status.success());
    assert!(output.stdout.is_empty());
    let fixture = Fixture::new(&valid);
    let output = Command::new(env!("CARGO_BIN_EXE_aps-simpleperf-engine"))
        .arg("query")
        .arg(&fixture.0)
        .args(["-", "-", "-", "-", "forward", "samples", "0", "512"])
        .env(
            "APS_SIMPLEPERF_MAX_INPUT_BYTES",
            (valid.len() - 1).to_string(),
        )
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(output.stdout.is_empty());
    assert!(query_path(&fixture.0, 0, &Query::default()).is_err());
    assert!(query_path(&fixture.0, valid.len() - 1, &Query::default()).is_err());
    assert!(query_path(&fixture.0, valid.len(), &Query::default()).is_ok());
    assert!(query_path(fixture.0.parent().unwrap(), valid.len(), &Query::default()).is_err());
}

#[test]
fn metric_and_wire_string_overflow_fail_without_partial_output() {
    use aps_simpleperf_engine::simpleperf::{FileRecord, Record, SimpleperfStream};
    let overflow = SimpleperfStream {
        version: 1,
        records: vec![
            sample(10, 11, 0, i64::MAX as u64, &[0]),
            sample(20, 11, 0, 1, &[0]),
        ],
    };
    let fixture = Fixture::new(&encoded(&overflow));
    for (offset, limit) in [("0", "512"), ("999", "0")] {
        let output = query(
            &fixture.0,
            &["-", "-", "-", "-", "forward", "events", offset, limit],
        );
        assert!(!output.status.success());
        assert!(output.stdout.is_empty());
        assert!(String::from_utf8_lossy(&output.stderr).contains("signed i64"));
    }
    let oversized_string = SimpleperfStream {
        version: 1,
        records: vec![
            sample(10, 11, 0, 1, &[0]),
            Record::File(FileRecord {
                id: 7,
                path: "fixture.so".into(),
                symbols: vec!["x".repeat(wire::MAX_STRING + 1)],
            }),
        ],
    };
    let fixture = Fixture::new(&encoded(&oversized_string));
    let output = query(
        &fixture.0,
        &["-", "-", "-", "-", "forward", "samples", "0", "1"],
    );
    assert!(!output.status.success());
    assert!(output.stdout.is_empty());
    assert!(String::from_utf8_lossy(&output.stderr).contains("string exceeds"));
}

#[test]
fn summarize_aliases_and_stdio_analyze_keep_the_existing_contract() {
    let fixture = Fixture::new(&encoded(&profile()));
    let mut outputs = Vec::new();
    for command in ["summarize", "summarize-tsv"] {
        let output = Command::new(env!("CARGO_BIN_EXE_aps-simpleperf-engine"))
            .arg(command)
            .arg(&fixture.0)
            .env_remove("APS_SIMPLEPERF_MAX_INPUT_BYTES")
            .output()
            .unwrap();
        assert!(output.status.success());
        let text = String::from_utf8(output.stdout.clone()).unwrap();
        assert_eq!(text.lines().count(), 8);
        assert!(text.contains("sampleCount\t6\n"));
        assert!(text.contains("totalEventCount\t230\n"));
        outputs.push(output.stdout);
    }
    assert_eq!(outputs[0], outputs[1]);
    let request = wire::Value::map([
        ("version", 1i64.into()),
        ("id", 7i64.into()),
        ("op", "analyze".into()),
        (
            "args",
            wire::Value::map([("path", fixture.0.to_str().unwrap().into())]),
        ),
    ]);
    let mut child = Command::new(env!("CARGO_BIN_EXE_aps-simpleperf-engine"))
        .arg("--binary")
        .env_remove("APS_SIMPLEPERF_MAX_INPUT_BYTES")
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    wire::write_frame(&mut child.stdin.take().unwrap(), &request).unwrap();
    let output = child.wait_with_output().unwrap();
    assert!(output.status.success());
    let mut cursor = Cursor::new(output.stdout);
    let result = wire::decode(&wire::read_frame(&mut cursor).unwrap().unwrap()).unwrap();
    assert_eq!(result.get("id").unwrap().as_int().unwrap(), 7);
    assert_eq!(result.get("status").unwrap().as_str().unwrap(), "ok");
    let payload = result.get("payload").unwrap();
    assert_eq!(
        payload
            .get("summary")
            .unwrap()
            .get("totalEventCount")
            .unwrap()
            .as_int()
            .unwrap(),
        230
    );
    let rows = payload.get("callTree").unwrap().as_list().unwrap();
    assert_eq!(rows.len(), 4);
    assert_eq!(rows[0].get("parentIndex").unwrap().as_int().unwrap(), -1);
    assert_eq!(rows[1].get("parentIndex").unwrap().as_int().unwrap(), 0);
    assert_eq!(
        rows[0].get("inclusiveWeight").unwrap().as_int().unwrap(),
        118
    );
    assert_eq!(analyze(&profile()).summary.total_event_count, 230);
}
