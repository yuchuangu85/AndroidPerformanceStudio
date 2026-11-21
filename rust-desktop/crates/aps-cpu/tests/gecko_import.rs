use aps_cpu::{ChartBlockKind, ChartViewport, CpuSession, Direction, Query, WeightType};
use aps_json::{Limits, Map, Value};
use std::io::Read;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};

static SEQUENCE: AtomicUsize = AtomicUsize::new(0);

struct Fixture(PathBuf);

impl Fixture {
    fn bytes(bytes: &[u8]) -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-cpu-gecko-{}-{}.json",
            std::process::id(),
            SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::write(&path, bytes).unwrap();
        Self(path)
    }

    fn profile(value: &Value) -> Self {
        Self::bytes(&aps_json::to_vec(value, Limits::default()).unwrap())
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

fn object<const N: usize>(entries: [(&str, Value); N]) -> Value {
    Value::Object(Map::from_iter(
        entries
            .into_iter()
            .map(|(key, value)| (key.to_owned(), value)),
    ))
}

fn table(columns: &[&str], rows: Vec<Vec<Value>>) -> Value {
    object([
        (
            "schema",
            Value::Object(
                columns
                    .iter()
                    .enumerate()
                    .map(|(index, name)| ((*name).to_owned(), Value::from(index)))
                    .collect(),
            ),
        ),
        (
            "data",
            Value::Array(rows.into_iter().map(Value::Array).collect()),
        ),
    ])
}

fn thread(tid: i32, name: &str, resource: &str, sample_count: usize) -> Value {
    // Every thread has a different string/frame order but the same call shape.
    // Local file/symbol IDs overlap, including different symbols in one resource.
    let locations = [
        format!("{name}_root (in {resource})"),
        "common (in /lib/shared.so)".to_owned(),
        format!("{name}_leaf (in {resource})"),
    ];
    let order = match tid {
        11 => [0, 1, 2],
        22 => [1, 2, 0],
        _ => [2, 0, 1],
    };
    let frame = |logical| order.iter().position(|index| *index == logical).unwrap();
    object([
        ("tid", Value::from(tid)),
        ("pid", Value::from(10)),
        ("name", Value::from(name)),
        (
            "stringTable",
            Value::Array(
                order
                    .iter()
                    .rev()
                    .map(|index| Value::from(locations[*index].clone()))
                    .collect(),
            ),
        ),
        (
            "frameTable",
            table(
                &["category", "location"],
                (0..3)
                    .map(|index| vec![Value::from(0), Value::from(2 - index)])
                    .collect(),
            ),
        ),
        (
            "stackTable",
            table(
                &["frame", "prefix"],
                vec![
                    vec![Value::from(frame(0)), Value::Null],
                    vec![Value::from(frame(1)), Value::from(0)],
                    vec![Value::from(frame(2)), Value::from(1)],
                ],
            ),
        ),
        (
            "samples",
            table(
                &["time", "stack"],
                (0..sample_count)
                    .map(|index| {
                        vec![
                            Value::from(tid as f64 + index as f64 + 0.25),
                            Value::from(2),
                        ]
                    })
                    .collect(),
            ),
        ),
    ])
}

fn profile() -> Value {
    object([
        ("meta", object([("version", Value::from(24))])),
        (
            "threads",
            Value::Array(vec![
                thread(11, "alpha", "/lib/alpha.so", 1),
                thread(22, "beta", "/lib/beta.so", 2),
                thread(33, "gamma", "/lib/alpha.so", 3),
            ]),
        ),
        ("processes", Value::Array(Vec::new())),
    ])
}

fn assert_views(session: &mut CpuSession) {
    assert_eq!(session.summary().sample_count, 6);
    assert_eq!(session.summary().first_timestamp_nanos, Some(11_250_000));
    assert_eq!(session.summary().last_timestamp_nanos, Some(35_250_000));
    assert_eq!(session.events(), ["samples"]);
    assert_eq!(
        session
            .threads()
            .iter()
            .map(|thread| (thread.id, thread.name.as_str()))
            .collect::<Vec<_>>(),
        [(11, "alpha"), (22, "beta"), (33, "gamma")]
    );
    let threads = [
        (11, "alpha", "/lib/alpha.so", 1),
        (22, "beta", "/lib/beta.so", 2),
        (33, "gamma", "/lib/alpha.so", 3),
    ];
    for selected in [None, Some(11), Some(22), Some(33)] {
        for direction in [Direction::Forward, Direction::Inverted] {
            for weight_type in [WeightType::Samples, WeightType::EventCount] {
                let query = Query {
                    thread_ids: selected.into_iter().collect(),
                    direction,
                    weight_type,
                    ..Query::default()
                };
                let active: Vec<_> = threads
                    .iter()
                    .filter(|(tid, ..)| selected.is_none_or(|id| id == *tid))
                    .collect();
                let tree = session.full_call_tree(&query, 100).unwrap();
                assert!(!tree.truncated);
                assert_eq!(tree.rows.len(), active.len() * 3);
                for (_, name, resource, count) in &active {
                    let root = format!("{name}_root");
                    let leaf = format!("{name}_leaf");
                    let names = match direction {
                        Direction::Forward => [root.as_str(), "common", leaf.as_str()],
                        Direction::Inverted => [leaf.as_str(), "common", root.as_str()],
                    };
                    let mut parent = None;
                    for (depth, name) in names.into_iter().enumerate() {
                        let row = tree
                            .rows
                            .iter()
                            .find(|row| row.name == name && row.parent_id == parent)
                            .unwrap_or_else(|| panic!("missing {name}, thread {selected:?}"));
                        assert_eq!(row.depth, depth);
                        assert_eq!(
                            row.resource,
                            if name == "common" {
                                "/lib/shared.so"
                            } else {
                                resource
                            }
                        );
                        assert_eq!(row.inclusive_weight, *count);
                        assert_eq!(row.sample_count, *count);
                        let leaf_depth = match direction {
                            Direction::Forward => 2,
                            Direction::Inverted => 0,
                        };
                        assert_eq!(
                            row.self_weight,
                            if depth == leaf_depth { *count } else { 0 }
                        );
                        parent = Some(row.id.clone());
                    }
                }
                let top = session.top_functions(&query, 0, 100).unwrap();
                assert_eq!(top.total, active.len() * 2 + 1);
                let common = top.rows.iter().find(|row| row.name == "common").unwrap();
                assert_eq!(common.resource, "/lib/shared.so");
                assert_eq!(common.thread_count, active.len());
                assert_eq!(
                    common.inclusive_weight,
                    active.iter().map(|(_, _, _, count)| *count).sum::<u64>()
                );
                assert_eq!(common.self_weight, 0);
                for (_, name, resource, count) in &active {
                    for (suffix, self_weight) in [("root", 0), ("leaf", *count)] {
                        let row = top
                            .rows
                            .iter()
                            .find(|row| row.name == format!("{name}_{suffix}"))
                            .unwrap();
                        assert_eq!(&row.resource, resource);
                        assert_eq!(row.inclusive_weight, *count);
                        assert_eq!(row.self_weight, self_weight);
                        assert_eq!(row.thread_count, 1);
                    }
                }
                let flame = session
                    .flame_graph(&query, ChartViewport::default())
                    .unwrap();
                assert!(!flame.truncated);
                assert_eq!(flame.blocks.len(), tree.rows.len());
                for row in &tree.rows {
                    let block = flame.selected(&row.id).unwrap();
                    assert_eq!(block.kind, ChartBlockKind::Function);
                    assert_eq!(block.label, row.name);
                    assert_eq!(block.resource, row.resource);
                    assert_eq!(block.depth, row.depth);
                    assert_eq!(block.inclusive_weight, row.inclusive_weight);
                    assert!(block.width > 0.0);
                }
            }
        }
    }
}

#[test]
fn gecko_thread_local_tables_preserve_tree_top_and_flame_contents() {
    let fixture = Fixture::profile(&profile());
    let mut session = CpuSession::open(&fixture.0).unwrap();
    assert_views(&mut session);
    assert_eq!(session.summary().file_count, 3);

    let mut reopened = CpuSession::open(&fixture.0).unwrap();
    let query = Query::default();
    assert_eq!(
        session.query(&query).unwrap(),
        reopened.query(&query).unwrap()
    );
    assert_eq!(
        session.top_functions(&query, 0, 100).unwrap(),
        reopened.top_functions(&query, 0, 100).unwrap()
    );
}

#[test]
fn gecko_multi_thread_firefox_roundtrip_preserves_local_symbols() {
    let fixture = Fixture::profile(&profile());
    let mut session = CpuSession::open(&fixture.0).unwrap();
    for _ in 0..2 {
        let archive = session.export_firefox_profile().unwrap();
        let mut bytes = Vec::new();
        flate2::read::MultiGzDecoder::new(&archive[..])
            .read_to_end(&mut bytes)
            .unwrap();
        let exported = Fixture::bytes(&bytes);
        session = CpuSession::open(&exported.0).unwrap();
        assert_views(&mut session);
    }
}

#[test]
fn gecko_nested_processes_are_flattened_with_their_thread_identity() {
    let mut document = profile();
    let grandchild = object([
        (
            "threads",
            Value::Array(vec![thread(55, "grandchild", "/lib/grandchild.so", 1)]),
        ),
        ("processes", Value::Array(Vec::new())),
    ]);
    let child = object([
        (
            "threads",
            Value::Array(vec![thread(44, "child", "/lib/child.so", 2)]),
        ),
        ("processes", Value::Array(vec![grandchild])),
    ]);
    document["processes"] = Value::Array(vec![child]);
    let fixture = Fixture::profile(&document);
    let mut session = CpuSession::open(&fixture.0).unwrap();

    assert_eq!(session.summary().thread_count, 5);
    assert_eq!(session.summary().sample_count, 9);
    assert_eq!(
        session
            .threads()
            .iter()
            .map(|thread| thread.id)
            .collect::<Vec<_>>(),
        vec![11, 22, 33, 44, 55]
    );
    for (thread_id, expected_name, expected_resource, expected_samples) in [
        (44, "child", "/lib/child.so", 2),
        (55, "grandchild", "/lib/grandchild.so", 1),
    ] {
        let query = Query {
            thread_ids: [thread_id].into(),
            ..Query::default()
        };
        let tree = session.full_call_tree(&query, 100).unwrap();
        assert_eq!(tree.rows.len(), 3);
        assert!(tree.rows.iter().any(|row| {
            row.name == format!("{expected_name}_root")
                && row.resource == expected_resource
                && row.sample_count == expected_samples
        }));
        assert_eq!(
            session.top_functions(&query, 0, 100).unwrap().rows[0].thread_count,
            1
        );
    }
}

#[test]
fn gecko_nested_process_errors_identify_the_process_path() {
    let mut document = profile();
    document["processes"] = Value::Array(vec![object([
        ("threads", Value::Array(vec![Value::Null])),
        ("processes", Value::Array(Vec::new())),
    ])]);
    let fixture = Fixture::profile(&document);
    let error = CpuSession::open(&fixture.0).unwrap_err();
    assert!(error.contains("profile.processes[0].threads[0]"), "{error}");
}

#[test]
fn gecko_malformed_later_thread_rejects_the_whole_import() {
    let mut invalid = profile();
    invalid["threads"][2]["frameTable"]["data"][0][1] = Value::from(999);
    let fixture = Fixture::profile(&invalid);
    let error = CpuSession::open(&fixture.0).unwrap_err();
    assert!(error.contains("profile.threads[2]"), "{error}");
    assert!(error.contains("stringTable range"), "{error}");
}
