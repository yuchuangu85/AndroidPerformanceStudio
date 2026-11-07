#[path = "../../../../desktop-viewer/simpleperf-viewer/native-engine-rust/tests/common/mod.rs"]
mod common;

use aps_cpu::{CpuSession, Direction, Query, WeightType, DEFAULT_INPUT_LIMIT};
use aps_simpleperf_engine::{
    project,
    simpleperf::{MetaInfo, Record, SimpleperfStream},
};
use common::{encoded, profile};
use std::collections::BTreeSet;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};

static SEQUENCE: AtomicUsize = AtomicUsize::new(0);

struct Fixture(PathBuf);
impl Fixture {
    fn bytes(bytes: &[u8]) -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-cpu-session-{}-{}.protobuf",
            std::process::id(),
            SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::write(&path, bytes).unwrap();
        Self(path)
    }
    fn profile() -> Self {
        Self::bytes(&encoded(&profile()))
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

#[test]
fn session_reads_once_and_returns_complete_catalog_without_declared_sample_threads() {
    let fixture = Fixture::profile();
    let mut session = CpuSession::open(&fixture.0).unwrap();
    assert_eq!(session.summary().sample_count, 6);
    assert_eq!(session.summary().thread_count, 4);
    assert_eq!(session.summary().first_timestamp_nanos, Some(10));
    assert_eq!(session.summary().last_timestamp_nanos, Some(60));
    assert_eq!(
        session
            .threads()
            .iter()
            .map(|thread| thread.id)
            .collect::<Vec<_>>(),
        [11, 22, 33, 44]
    );
    assert_eq!(session.threads()[3].name, "declared-without-samples");
    assert_eq!(session.events(), ["cpu-cycles", "instructions"]);
    assert_eq!(session.path(), fixture.0);
    // Session owns decoded records; pagination and changed filters need no file.
    std::fs::remove_file(&fixture.0).unwrap();
    for direction in [Direction::Forward, Direction::Inverted] {
        let query = Query {
            direction,
            limit: 1,
            ..Query::default()
        };
        let first = session.query(&query).unwrap();
        let second = session
            .query(&Query {
                offset: 1,
                ..query.clone()
            })
            .unwrap();
        assert_eq!(first, project(&profile(), &query).unwrap());
        assert_eq!(
            second,
            project(&profile(), &Query { offset: 1, ..query }).unwrap()
        );
    }
}

#[test]
fn filters_weight_bounds_and_clear_projection_preserve_engine_results() {
    let fixture = Fixture::profile();
    let mut session = CpuSession::open(&fixture.0).unwrap();
    let query = Query {
        thread_ids: BTreeSet::from([11, 22]),
        event_types: BTreeSet::from(["cpu-cycles".into()]),
        start: Some(10),
        end: Some(50),
        weight_type: WeightType::EventCount,
        ..Query::default()
    };
    assert_eq!(
        session.query(&query).unwrap(),
        project(&profile(), &query).unwrap()
    );
    assert!(session
        .query(&Query {
            limit: 513,
            ..query.clone()
        })
        .is_err());
    assert!(session
        .query(&Query {
            thread_ids: BTreeSet::from([999]),
            ..query.clone()
        })
        .is_err());
    assert!(session
        .query(&Query {
            weight_type: WeightType::EventCount,
            ..Query::default()
        })
        .is_err());
    assert_eq!(
        session.query(&query).unwrap(),
        project(&profile(), &query).unwrap()
    );
    session.clear_projection();
    assert_eq!(
        session.query(&query).unwrap(),
        project(&profile(), &query).unwrap()
    );
}

#[test]
fn malformed_truncated_trailing_perf_data_and_input_limits_are_rejected() {
    let bytes = encoded(&profile());
    for invalid in [
        b"PERFILE2not-protobuf".to_vec(),
        b"not-a-valid-protobuf".to_vec(),
        bytes[..bytes.len() - 1].to_vec(),
        [bytes.clone(), vec![1]].concat(),
    ] {
        let fixture = Fixture::bytes(&invalid);
        assert!(CpuSession::open(&fixture.0).is_err());
    }
    let perf = Fixture::bytes(b"PERFILE2not-protobuf");
    assert!(CpuSession::open(&perf.0).unwrap_err().contains("perf.data"));
    let fixture = Fixture::bytes(&bytes);
    assert!(CpuSession::open_with_limit(&fixture.0, bytes.len() - 1).is_err());
    assert!(CpuSession::open_with_limit(&fixture.0, bytes.len()).is_ok());
    assert!(CpuSession::open_with_limit(&fixture.0, 0).is_err());
    assert!(CpuSession::open_with_limit(&fixture.0, DEFAULT_INPUT_LIMIT * 3).is_err());
    assert!(CpuSession::open(std::env::temp_dir()).is_err());
}

#[test]
fn final_metadata_and_unknown_event_catalog_match_engine_filter_rules() {
    let mut stream = profile();
    stream.records.push(Record::MetaInfo(MetaInfo {
        event_types: vec!["renamed-cycles".into()],
        app_package_name: None,
        app_type: None,
        android_sdk_version: None,
        android_build_type: None,
        trace_offcpu: false,
    }));
    let fixture = Fixture::bytes(&encoded(&stream));
    let mut session = CpuSession::open(&fixture.0).unwrap();
    assert_eq!(session.events(), ["renamed-cycles", "event#1"]);
    let query = Query {
        event_types: BTreeSet::from(["event#1".into()]),
        ..Query::default()
    };
    assert_eq!(
        session.query(&query).unwrap(),
        project(&stream, &query).unwrap()
    );
}

#[test]
fn empty_profile_retains_valid_empty_query_semantics() {
    let fixture = Fixture::bytes(&encoded(&SimpleperfStream {
        version: 1,
        records: Vec::new(),
    }));
    let mut session = CpuSession::open(&fixture.0).unwrap();
    assert_eq!(session.summary().sample_count, 0);
    assert!(session.events().is_empty());
    assert!(session.threads().is_empty());
    assert_eq!(session.query(&Query::default()).unwrap().total, 0);
}
