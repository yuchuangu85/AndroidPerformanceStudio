#[path = "../../../../desktop-viewer/simpleperf-viewer/native-engine-rust/tests/common/mod.rs"]
mod common;

use aps_cpu::{
    ChartViewport, CpuSession, Direction, Query, QueryRow, TransformSet, WeightType,
    MAX_FULL_TREE_ROWS, MAX_TRANSFORM_NAMES, MAX_TRANSFORM_NAME_BYTES,
};
use aps_simpleperf_engine::simpleperf::{FileRecord, Frame, Record, Sample, SimpleperfStream};
use common::{encoded, frame, profile, sample};
use std::collections::BTreeSet;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};

static SEQUENCE: AtomicUsize = AtomicUsize::new(0);

struct Fixture(PathBuf);
impl Fixture {
    fn new(stream: &SimpleperfStream) -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-cpu-transforms-{}-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos(),
            SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&path).unwrap();
        std::fs::write(path.join("input.pb"), encoded(stream)).unwrap();
        Self(path)
    }

    fn session(&self) -> CpuSession {
        CpuSession::open(self.0.join("input.pb")).unwrap()
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn stream(samples: Vec<Record>) -> SimpleperfStream {
    let mut stream = profile();
    stream.records.retain(|r| !matches!(r, Record::Sample(_)));
    stream.records.extend(samples);
    stream
}

fn cycles(direction: Direction) -> Query {
    Query {
        direction,
        weight_type: WeightType::EventCount,
        event_types: BTreeSet::from(["cpu-cycles".into()]),
        ..Query::default()
    }
}

fn assert_tree(rows: &[QueryRow]) {
    for (index, row) in rows.iter().enumerate() {
        if let Some(parent) = &row.parent_id {
            let parent = rows[..index].iter().find(|r| &r.id == parent).unwrap();
            assert_eq!(row.depth, parent.depth + 1);
            assert!(row.inclusive_weight <= parent.inclusive_weight);
        } else {
            assert_eq!(row.depth, 0);
        }
    }
}

fn paths(rows: &[QueryRow]) -> Vec<(Vec<String>, u64, u64)> {
    rows.iter()
        .map(|row| {
            let mut names = vec![row.name.clone()];
            let mut parent = row.parent_id.as_ref();
            while let Some(id) = parent {
                let node = rows.iter().find(|node| &node.id == id).unwrap();
                names.push(node.name.clone());
                parent = node.parent_id.as_ref();
            }
            names.reverse();
            (names, row.inclusive_weight, row.self_weight)
        })
        .collect()
}

#[test]
fn empty_transforms_are_exact_full_tree_parity_in_both_directions_and_weights() {
    let fixture = Fixture::new(&stream(vec![
        sample(10, 11, 0, 3, &[1, 1, 0]),
        sample(20, 22, 0, 5, &[2, 0]),
        Record::Sample(Sample {
            time: 30,
            thread_id: 11,
            event_type_id: 0,
            event_count: 7,
            callchain: vec![
                Frame {
                    virtual_address: 99,
                    ..frame(1)
                },
                frame(0),
            ],
        }),
    ]));
    let mut session = fixture.session();
    for direction in [Direction::Forward, Direction::Inverted] {
        for weight_type in [WeightType::Samples, WeightType::EventCount] {
            let query = Query {
                weight_type,
                offset: 100,
                limit: 1,
                ..cycles(direction)
            };
            for max_rows in [1, 100] {
                let expected = session.full_call_tree(&query, max_rows).unwrap();
                assert_eq!(
                    session
                        .transformed_call_tree(&query, &TransformSet::default(), max_rows)
                        .unwrap(),
                    expected,
                    "{direction:?} {weight_type:?} {max_rows}"
                );
            }
        }
    }
}

#[test]
fn focus_selects_leafmost_match_before_direction_and_preserves_transformed_self() {
    let fixture = Fixture::new(&stream(vec![
        sample(10, 11, 0, 3, &[2, 1, 1, 0]),
        sample(20, 22, 0, 5, &[1, 0]),
        sample(30, 11, 0, 7, &[2, 0]),
    ]));
    let mut session = fixture.session();
    let transforms = TransformSet {
        focus: Some("leaf".into()),
        ..TransformSet::default()
    };
    for direction in [Direction::Forward, Direction::Inverted] {
        let query = cycles(direction);
        let original = session.full_call_tree(&query, 100).unwrap();
        let tree = session
            .transformed_call_tree(&query, &transforms, 100)
            .unwrap();
        assert_tree(&tree.rows);
        let expected = match direction {
            Direction::Forward => vec![
                (vec!["leaf".into()], 8, 5),
                (vec!["leaf".into(), "other".into()], 3, 3),
            ],
            Direction::Inverted => vec![
                (vec!["leaf".into()], 5, 5),
                (vec!["other".into()], 3, 3),
                (vec!["other".into(), "leaf".into()], 3, 0),
            ],
        };
        assert_eq!(paths(&tree.rows), expected);
        assert_eq!(session.full_call_tree(&query, 100).unwrap(), original);
    }
}

#[test]
fn collapse_splices_nodes_and_transfers_leaf_self_weight_without_dropping_samples() {
    let fixture = Fixture::new(&stream(vec![
        sample(10, 11, 0, 3, &[2, 1, 0]),
        sample(20, 22, 0, 5, &[1, 0]),
    ]));
    let mut session = fixture.session();
    for direction in [Direction::Forward, Direction::Inverted] {
        let tree = session
            .transformed_call_tree(
                &cycles(direction),
                &TransformSet {
                    collapse: BTreeSet::from(["leaf".into()]),
                    ..TransformSet::default()
                },
                100,
            )
            .unwrap();
        assert_tree(&tree.rows);
        assert!(!tree.rows.iter().any(|r| r.name == "leaf"));
        assert_eq!(tree.rows.iter().map(|r| r.self_weight).sum::<u64>(), 8);
        assert_eq!(
            tree.rows.iter().map(|r| r.self_sample_count).sum::<u64>(),
            2
        );
        let root = tree.rows.iter().find(|r| r.name == "root").unwrap();
        assert_eq!(root.self_weight, 5);
        let other = tree.rows.iter().find(|r| r.name == "other").unwrap();
        assert_eq!(other.self_weight, 3);
        if direction == Direction::Inverted {
            assert_eq!(other.depth, 0);
        }
    }
}

#[test]
fn drop_precedes_focus_collapse_and_recursion_and_filters_are_half_open() {
    let fixture = Fixture::new(&stream(vec![
        sample(10, 11, 0, 3, &[2, 1, 1, 0]),
        sample(20, 22, 0, 5, &[1, 0]),
        sample(30, 11, 1, 9, &[1, 0]),
        sample(40, 11, 0, 11, &[1, 0]),
        sample(50, 11, 0, 13, &[1, 0]),
    ]));
    let mut session = fixture.session();
    let query = Query {
        thread_ids: BTreeSet::from([11]),
        start: Some(10),
        end: Some(50),
        ..cycles(Direction::Forward)
    };
    let transforms = TransformSet {
        focus: Some("leaf".into()),
        collapse: BTreeSet::from(["other".into()]),
        drop: BTreeSet::from(["other".into()]),
        collapse_recursion: true,
        ..TransformSet::default()
    };
    let rows = session
        .transformed_call_tree(&query, &transforms, 100)
        .unwrap()
        .rows;
    assert_eq!(rows.len(), 1);
    assert_eq!(
        (
            rows[0].name.as_str(),
            rows[0].self_weight,
            rows[0].self_sample_count
        ),
        ("leaf", 11, 1)
    );
    for transforms in [
        TransformSet {
            focus: Some("absent".into()),
            ..TransformSet::default()
        },
        TransformSet {
            drop: BTreeSet::from(["root".into()]),
            ..TransformSet::default()
        },
        TransformSet {
            collapse: BTreeSet::from(["root".into(), "leaf".into(), "other".into()]),
            ..TransformSet::default()
        },
    ] {
        assert!(session
            .transformed_call_tree(&query, &transforms, 100)
            .unwrap()
            .rows
            .is_empty());
    }
}

#[test]
fn recursion_collapse_uses_function_identity_not_duplicate_display_names() {
    let mut source = stream(vec![Record::Sample(Sample {
        time: 10,
        thread_id: 11,
        event_type_id: 0,
        event_count: 7,
        callchain: vec![
            Frame {
                virtual_address: 22,
                ..frame(1)
            },
            frame(1),
            frame(2),
            Frame {
                symbol_id: -1,
                virtual_address: 91,
                ..frame(0)
            },
            Frame {
                symbol_id: -1,
                virtual_address: 90,
                ..frame(0)
            },
            frame(0),
        ],
    })]);
    for record in &mut source.records {
        if let Record::File(file) = record {
            file.symbols[2] = "leaf".into();
        }
    }
    let fixture = Fixture::new(&source);
    let mut session = fixture.session();
    let rows = session
        .transformed_call_tree(
            &cycles(Direction::Forward),
            &TransformSet {
                collapse_recursion: true,
                ..TransformSet::default()
            },
            100,
        )
        .unwrap()
        .rows;
    assert_eq!(
        rows.len(),
        5,
        "only the two frames of symbol ID 1 are recursive"
    );
    assert_eq!(
        rows.iter().filter(|r| r.name == "<unknown-symbol>").count(),
        2
    );
    assert_eq!(rows.iter().filter(|r| r.name == "leaf").count(), 2);
    assert_eq!(rows.last().unwrap().self_weight, 7);
}

#[test]
fn merge_is_explicit_per_parent_and_other_frames_keep_exact_addresses() {
    let mut source = stream(vec![
        sample(10, 11, 0, 3, &[1, 0]),
        Record::Sample(Sample {
            time: 20,
            thread_id: 22,
            event_type_id: 0,
            event_count: 5,
            callchain: vec![
                Frame {
                    file_id: 8,
                    ..frame(1)
                },
                frame(0),
            ],
        }),
        Record::Sample(Sample {
            time: 30,
            thread_id: 11,
            event_type_id: 0,
            event_count: 7,
            callchain: vec![
                Frame {
                    virtual_address: 21,
                    ..frame(1)
                },
                frame(0),
            ],
        }),
        sample(40, 22, 0, 11, &[1, 2]),
    ]);
    source.records.push(Record::File(FileRecord {
        id: 8,
        path: "second.so".into(),
        symbols: vec!["root".into(), "leaf".into()],
    }));
    let fixture = Fixture::new(&source);
    let mut session = fixture.session();
    let query = cycles(Direction::Forward);
    let untouched = session
        .transformed_call_tree(
            &query,
            &TransformSet {
                drop: BTreeSet::from(["absent".into()]),
                ..TransformSet::default()
            },
            100,
        )
        .unwrap();
    assert_eq!(
        untouched.rows.len(),
        5,
        "known symbols with the same file/symbol/execution identity aggregate"
    );
    let merged = session
        .transformed_call_tree(
            &query,
            &TransformSet {
                merge: BTreeSet::from(["leaf".into()]),
                ..TransformSet::default()
            },
            100,
        )
        .unwrap();
    assert_tree(&merged.rows);
    assert_eq!(merged.rows.len(), 4);
    assert_eq!(
        merged
            .rows
            .iter()
            .filter(|r| r.name == "leaf")
            .map(|r| r.self_weight)
            .collect::<Vec<_>>(),
        [15, 11]
    );
    assert_eq!(merged.rows[1].resource, "fixture.so");
}

#[test]
fn transformed_limits_validation_overflow_and_zero_weights_fail_or_preserve_counts() {
    let fixture = Fixture::new(&stream(vec![sample(10, 11, 0, 0, &[1, 1, 0])]));
    let mut session = fixture.session();
    let query = cycles(Direction::Inverted);
    let transforms = TransformSet {
        collapse_recursion: true,
        ..TransformSet::default()
    };
    let tree = session
        .transformed_call_tree(&query, &transforms, 1)
        .unwrap();
    assert!(tree.truncated);
    assert_eq!(
        (
            tree.rows[0].name.as_str(),
            tree.rows[0].self_weight,
            tree.rows[0].self_sample_count
        ),
        ("leaf", 0, 1)
    );
    for max_rows in [0, MAX_FULL_TREE_ROWS + 1] {
        assert!(session
            .transformed_call_tree(&query, &transforms, max_rows)
            .is_err());
    }
    for invalid in [
        Query {
            start: Some(10),
            end: Some(10),
            ..query.clone()
        },
        Query {
            thread_ids: BTreeSet::from([999]),
            ..query.clone()
        },
        Query {
            event_types: BTreeSet::from(["missing".into()]),
            ..query.clone()
        },
        Query {
            event_types: BTreeSet::new(),
            ..query.clone()
        },
        Query {
            limit: 513,
            ..query.clone()
        },
    ] {
        assert!(session
            .transformed_call_tree(&invalid, &transforms, 100)
            .is_err());
    }
    let fixture = Fixture::new(&stream(vec![
        sample(10, 11, 0, i64::MAX as u64, &[1, 0]),
        sample(20, 22, 0, 1, &[1, 0]),
    ]));
    assert!(fixture
        .session()
        .transformed_call_tree(&cycles(Direction::Forward), &transforms, 100)
        .unwrap_err()
        .contains("signed i64"));
}

#[test]
fn transform_names_validate_all_sets_and_flame_geometry_matches_the_rows() {
    for name in [
        String::new(),
        "x\n".into(),
        "x".repeat(MAX_TRANSFORM_NAME_BYTES + 1),
    ] {
        for transforms in [
            TransformSet {
                focus: Some(name.clone()),
                ..TransformSet::default()
            },
            TransformSet {
                merge: BTreeSet::from([name.clone()]),
                ..TransformSet::default()
            },
            TransformSet {
                collapse: BTreeSet::from([name.clone()]),
                ..TransformSet::default()
            },
            TransformSet {
                drop: BTreeSet::from([name.clone()]),
                ..TransformSet::default()
            },
        ] {
            assert!(transforms.validate().is_err());
        }
    }
    let names: BTreeSet<_> = (0..=MAX_TRANSFORM_NAMES)
        .map(|n| format!("name{n}"))
        .collect();
    for transforms in [
        TransformSet {
            merge: names.clone(),
            ..TransformSet::default()
        },
        TransformSet {
            collapse: names.clone(),
            ..TransformSet::default()
        },
        TransformSet {
            drop: names,
            ..TransformSet::default()
        },
    ] {
        assert!(transforms.validate().is_err());
    }
    assert!(TransformSet {
        focus: Some("x".repeat(MAX_TRANSFORM_NAME_BYTES)),
        ..TransformSet::default()
    }
    .validate()
    .is_ok());
    let fixture = Fixture::new(&profile());
    let mut session = fixture.session();
    for direction in [Direction::Forward, Direction::Inverted] {
        let query = Query {
            direction,
            ..Query::default()
        };
        let transforms = TransformSet {
            focus: Some("leaf".into()),
            ..TransformSet::default()
        };
        let rows = session
            .transformed_call_tree(&query, &transforms, 100)
            .unwrap()
            .rows;
        let viewport = ChartViewport::default();
        let expected =
            aps_cpu::flame_frame_from_rows(&rows, query.weight_type, viewport, 0.25, 0.75).unwrap();
        let actual = session
            .transformed_flame_frame(&query, &transforms, viewport, 0.25, 0.75)
            .unwrap();
        assert_eq!(actual.domain, expected.domain);
        assert_eq!(actual.blocks, expected.blocks);
        assert_eq!(actual.total_depth, expected.total_depth);
        assert_eq!(actual.truncated, expected.truncated);
        assert_eq!(actual.omitted_blocks, expected.omitted_blocks);
        for (start, end) in [(0.5, 0.5), (f64::NAN, 1.0), (-0.1, 1.0)] {
            assert!(session
                .transformed_flame_frame(&query, &transforms, viewport, start, end)
                .is_err());
        }
    }
}
