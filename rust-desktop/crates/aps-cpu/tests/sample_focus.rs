#[path = "../../../../desktop-viewer/simpleperf-viewer/native-engine-rust/tests/common/mod.rs"]
mod common;

use aps_cpu::{CpuSession, Direction, FullCallTree, Query, QueryRow, TransformSet, WeightType};
use aps_simpleperf_engine::simpleperf::{FileRecord, Record, Sample, SimpleperfStream};
use common::{encoded, frame, profile, sample};
use std::collections::BTreeSet;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};

static SEQUENCE: AtomicUsize = AtomicUsize::new(0);

struct Fixture {
    path: PathBuf,
    ordinals: Vec<usize>,
}

impl Fixture {
    fn new(stream: &SimpleperfStream) -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-cpu-sample-focus-{}-{}.protobuf",
            std::process::id(),
            SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::write(&path, encoded(stream)).unwrap();
        Self {
            path,
            ordinals: stream
                .records
                .iter()
                .enumerate()
                .filter_map(|(ordinal, record)| {
                    matches!(record, Record::Sample(_)).then_some(ordinal)
                })
                .collect(),
        }
    }

    fn session(&self) -> CpuSession {
        CpuSession::open(&self.path).unwrap()
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.path);
    }
}

fn stream(samples: Vec<Record>) -> SimpleperfStream {
    let mut stream = profile();
    stream
        .records
        .retain(|record| !matches!(record, Record::Sample(_)));
    stream.records.extend(samples);
    stream
}

fn query(direction: Direction, weight_type: WeightType) -> Query {
    Query {
        direction,
        weight_type,
        event_types: BTreeSet::from(["cpu-cycles".into()]),
        offset: 99,
        limit: 1,
        ..Query::default()
    }
}

fn selected_row<'a>(tree: &'a FullCallTree, id: &Option<String>) -> &'a QueryRow {
    tree.rows
        .iter()
        .find(|row| Some(&row.id) == id.as_ref())
        .unwrap()
}

#[test]
fn empty_transforms_preserve_normal_rows_ids_metrics_and_caps() {
    let fixture = Fixture::new(&stream(vec![
        sample(10, 11, 0, 3, &[1, 0]),
        sample(20, 22, 0, 5, &[1, 2]),
    ]));
    let mut session = fixture.session();
    let transforms = TransformSet::default();
    for direction in [Direction::Forward, Direction::Inverted] {
        for weight_type in [WeightType::Samples, WeightType::EventCount] {
            let query = query(direction, weight_type);
            let expected = session.full_call_tree(&query, 100).unwrap();
            let (tree, id) = session
                .transformed_call_tree_selecting_sample(
                    &query,
                    &transforms,
                    100,
                    Some(fixture.ordinals[1]),
                )
                .unwrap();
            assert_eq!(tree, expected);
            let row = selected_row(&tree, &id);
            assert_eq!(row.name, "leaf");
            let (count, value) = if direction == Direction::Forward {
                (1, 5)
            } else {
                (2, 8)
            };
            assert_eq!(row.self_sample_count, count);
            assert_eq!(
                row.self_weight,
                if weight_type == WeightType::Samples {
                    count
                } else {
                    value
                }
            );
            let index = tree
                .rows
                .iter()
                .position(|row| Some(&row.id) == id.as_ref())
                .unwrap();
            let (capped, selected) = session
                .transformed_call_tree_selecting_sample(
                    &query,
                    &transforms,
                    index + 1,
                    Some(fixture.ordinals[1]),
                )
                .unwrap();
            assert_eq!(selected, id);
            assert_eq!(capped, session.full_call_tree(&query, index + 1).unwrap());
            if index > 0 {
                let (capped, selected) = session
                    .transformed_call_tree_selecting_sample(
                        &query,
                        &transforms,
                        index,
                        Some(fixture.ordinals[1]),
                    )
                    .unwrap();
                assert!(capped.truncated);
                assert_eq!(selected, None);
            }
        }
    }
}

#[test]
fn dropped_and_focus_excluded_samples_do_not_select_another_matching_name() {
    let fixture = Fixture::new(&stream(vec![
        sample(10, 11, 0, 3, &[1, 2, 0]),
        sample(20, 22, 0, 5, &[1, 0]),
    ]));
    let mut session = fixture.session();
    for direction in [Direction::Forward, Direction::Inverted] {
        for (transforms, ordinal) in [
            (
                TransformSet {
                    drop: BTreeSet::from(["other".into()]),
                    ..TransformSet::default()
                },
                fixture.ordinals[0],
            ),
            (
                TransformSet {
                    focus: Some("other".into()),
                    ..TransformSet::default()
                },
                fixture.ordinals[1],
            ),
        ] {
            let query = query(direction, WeightType::EventCount);
            let expected = session
                .transformed_call_tree(&query, &transforms, 100)
                .unwrap();
            let (tree, id) = session
                .transformed_call_tree_selecting_sample(&query, &transforms, 100, Some(ordinal))
                .unwrap();
            assert_eq!(tree, expected);
            assert!(tree.rows.iter().any(|row| row.name == "leaf"));
            assert_eq!(id, None);
        }
        let (tree, id) = session
            .transformed_call_tree_selecting_sample(
                &query(direction, WeightType::EventCount),
                &TransformSet {
                    collapse: BTreeSet::from(["root".into(), "leaf".into(), "other".into()]),
                    ..TransformSet::default()
                },
                100,
                Some(fixture.ordinals[0]),
            )
            .unwrap();
        assert!(tree.rows.is_empty());
        assert_eq!(id, None);
    }
}

#[test]
fn filters_and_missing_ordinals_return_none_without_reindexing_samples() {
    let fixture = Fixture::new(&stream(vec![
        sample(10, 11, 0, 3, &[1, 0]),
        sample(20, 22, 0, 5, &[1, 0]),
        sample(30, 22, 1, 7, &[1, 0]),
        sample(40, 11, 0, 11, &[]),
    ]));
    assert!(fixture.ordinals[0] > 0);
    let mut session = fixture.session();
    for transforms in [
        TransformSet::default(),
        TransformSet {
            collapse_recursion: true,
            ..TransformSet::default()
        },
    ] {
        for direction in [Direction::Forward, Direction::Inverted] {
            let base = query(direction, WeightType::EventCount);
            for filtered in [
                Query {
                    thread_ids: BTreeSet::from([22]),
                    ..base.clone()
                },
                Query {
                    event_types: BTreeSet::from(["instructions".into()]),
                    ..base.clone()
                },
                Query {
                    start: Some(20),
                    end: Some(30),
                    ..base.clone()
                },
            ] {
                let (tree, id) = session
                    .transformed_call_tree_selecting_sample(
                        &filtered,
                        &transforms,
                        100,
                        Some(fixture.ordinals[0]),
                    )
                    .unwrap();
                assert!(!tree.rows.is_empty());
                assert_eq!(id, None);
            }
            let filtered = Query {
                start: Some(20),
                end: Some(30),
                ..base.clone()
            };
            let (tree, id) = session
                .transformed_call_tree_selecting_sample(
                    &filtered,
                    &transforms,
                    100,
                    Some(fixture.ordinals[1]),
                )
                .unwrap();
            assert_eq!(selected_row(&tree, &id).self_weight, 5);
            let (_, excluded_end) = session
                .transformed_call_tree_selecting_sample(
                    &Query {
                        end: Some(20),
                        ..base.clone()
                    },
                    &transforms,
                    100,
                    Some(fixture.ordinals[1]),
                )
                .unwrap();
            assert_eq!(excluded_end, None);
            for ordinal in [None, Some(0), Some(usize::MAX), Some(fixture.ordinals[3])] {
                let (_, id) = session
                    .transformed_call_tree_selecting_sample(&base, &transforms, 100, ordinal)
                    .unwrap();
                assert_eq!(id, None);
            }
        }
    }
}

#[test]
fn transformed_selection_uses_frame_identity_and_materialized_preorder() {
    let mut source = stream(vec![
        sample(10, 11, 0, 5, &[2, 0]),
        sample(20, 22, 0, 3, &[1, 0]),
    ]);
    for record in &mut source.records {
        if let Record::File(file) = record {
            file.symbols[2] = "leaf".into();
        }
    }
    let fixture = Fixture::new(&source);
    let mut session = fixture.session();
    let transforms = TransformSet {
        collapse_recursion: true,
        ..TransformSet::default()
    };
    for direction in [Direction::Forward, Direction::Inverted] {
        let query = query(direction, WeightType::EventCount);
        let expected = session
            .transformed_call_tree(&query, &transforms, 100)
            .unwrap();
        let (tree, id) = session
            .transformed_call_tree_selecting_sample(
                &query,
                &transforms,
                100,
                Some(fixture.ordinals[0]),
            )
            .unwrap();
        assert_eq!(tree, expected);
        assert_eq!(id.as_deref(), Some("aps-transform-v1/2"));
        assert_eq!(selected_row(&tree, &id).self_weight, 5);
        assert_eq!(tree.rows.iter().filter(|row| row.name == "leaf").count(), 2);
        let (_, id) = session
            .transformed_call_tree_selecting_sample(
                &query,
                &transforms,
                2,
                Some(fixture.ordinals[0]),
            )
            .unwrap();
        assert_eq!(id, None);
        let (capped, id) = session
            .transformed_call_tree_selecting_sample(
                &query,
                &transforms,
                3,
                Some(fixture.ordinals[0]),
            )
            .unwrap();
        assert_eq!(selected_row(&capped, &id).self_weight, 5);
    }
}

#[test]
fn merge_selects_the_exact_shared_node_per_parent_and_keeps_metrics() {
    let mut source = stream(vec![
        sample(10, 11, 0, 3, &[1, 0]),
        Record::Sample(Sample {
            time: 20,
            thread_id: 22,
            event_type_id: 0,
            event_count: 5,
            callchain: vec![
                aps_simpleperf_engine::simpleperf::Frame {
                    file_id: 8,
                    ..frame(1)
                },
                frame(0),
            ],
        }),
        sample(30, 11, 0, 7, &[1, 2]),
    ]);
    source.records.push(Record::File(FileRecord {
        id: 8,
        path: "second.so".into(),
        symbols: vec!["root".into(), "leaf".into()],
    }));
    let fixture = Fixture::new(&source);
    let mut session = fixture.session();
    let transforms = TransformSet {
        merge: BTreeSet::from(["leaf".into()]),
        ..TransformSet::default()
    };
    for direction in [Direction::Forward, Direction::Inverted] {
        for weight_type in [WeightType::Samples, WeightType::EventCount] {
            let query = query(direction, weight_type);
            let expected = session
                .transformed_call_tree(&query, &transforms, 100)
                .unwrap();
            let mut ids = Vec::new();
            for ordinal in &fixture.ordinals {
                let (tree, id) = session
                    .transformed_call_tree_selecting_sample(
                        &query,
                        &transforms,
                        100,
                        Some(*ordinal),
                    )
                    .unwrap();
                assert_eq!(tree, expected);
                ids.push(id.unwrap());
            }
            assert_eq!(ids[0], ids[1]);
            let row = expected.rows.iter().find(|row| row.id == ids[0]).unwrap();
            let (count, value) = if direction == Direction::Forward {
                assert_ne!(ids[0], ids[2]);
                (2, 8)
            } else {
                assert_eq!(ids[0], ids[2]);
                (3, 15)
            };
            assert_eq!(row.self_sample_count, count);
            assert_eq!(
                row.self_weight,
                if weight_type == WeightType::Samples {
                    count
                } else {
                    value
                }
            );
        }
    }
}

#[test]
fn collapse_and_focus_follow_retained_self_frames_and_preserve_all_metrics() {
    let fixture = Fixture::new(&stream(vec![
        sample(10, 11, 0, 3, &[1, 2, 0]),
        sample(20, 22, 0, 5, &[2, 0]),
    ]));
    let mut session = fixture.session();
    for direction in [Direction::Forward, Direction::Inverted] {
        for weight_type in [WeightType::Samples, WeightType::EventCount] {
            let query = query(direction, weight_type);
            for (transforms, names) in [
                (
                    TransformSet {
                        collapse: BTreeSet::from(["other".into()]),
                        ..TransformSet::default()
                    },
                    ["leaf", "root"],
                ),
                (
                    TransformSet {
                        collapse: BTreeSet::from(["leaf".into()]),
                        ..TransformSet::default()
                    },
                    ["other", "other"],
                ),
                (
                    TransformSet {
                        focus: Some("other".into()),
                        ..TransformSet::default()
                    },
                    ["leaf", "other"],
                ),
                (
                    TransformSet {
                        focus: Some("root".into()),
                        collapse: BTreeSet::from(["other".into()]),
                        ..TransformSet::default()
                    },
                    ["leaf", "root"],
                ),
            ] {
                let expected = session
                    .transformed_call_tree(&query, &transforms, 100)
                    .unwrap();
                for (sample, name) in names.into_iter().enumerate() {
                    let (tree, id) = session
                        .transformed_call_tree_selecting_sample(
                            &query,
                            &transforms,
                            100,
                            Some(fixture.ordinals[sample]),
                        )
                        .unwrap();
                    assert_eq!(tree, expected);
                    assert_eq!(selected_row(&tree, &id).name, name);
                    assert_eq!(
                        tree.rows
                            .iter()
                            .map(|row| row.self_sample_count)
                            .sum::<u64>(),
                        2
                    );
                    assert_eq!(
                        tree.rows.iter().map(|row| row.self_weight).sum::<u64>(),
                        if weight_type == WeightType::Samples {
                            2
                        } else {
                            8
                        }
                    );
                }
            }
        }
    }
}

#[test]
fn recursion_selection_tracks_original_self_or_its_retained_replacement() {
    let fixture = Fixture::new(&stream(vec![
        sample(10, 11, 0, 3, &[1, 2, 1, 0]),
        sample(20, 22, 0, 5, &[1, 1, 1, 0]),
        sample(30, 11, 0, 0, &[1, 0]),
    ]));
    let mut session = fixture.session();
    for direction in [Direction::Forward, Direction::Inverted] {
        let query = query(direction, WeightType::EventCount);
        let transforms = TransformSet {
            collapse_recursion: true,
            ..TransformSet::default()
        };
        let expected = session
            .transformed_call_tree(&query, &transforms, 100)
            .unwrap();
        let mut ids = Vec::new();
        for ordinal in &fixture.ordinals {
            let (tree, id) = session
                .transformed_call_tree_selecting_sample(&query, &transforms, 100, Some(*ordinal))
                .unwrap();
            assert_eq!(tree, expected);
            ids.push(id.unwrap());
        }
        assert_eq!(ids[1], ids[2]);
        if direction == Direction::Forward {
            assert_ne!(ids[0], ids[1]);
            assert_eq!(
                selected_row(&expected, &Some(ids[0].clone())).self_weight,
                3
            );
            assert_eq!(
                selected_row(&expected, &Some(ids[1].clone())).self_weight,
                5
            );
        } else {
            assert_eq!(ids[0], ids[1]);
            assert_eq!(
                selected_row(&expected, &Some(ids[0].clone())).self_weight,
                8
            );
        }
    }
}

#[test]
fn selection_does_not_bypass_transform_or_query_validation() {
    let fixture = Fixture::new(&profile());
    let mut session = fixture.session();
    let query = Query::default();
    for transforms in [
        TransformSet::default(),
        TransformSet {
            collapse_recursion: true,
            ..TransformSet::default()
        },
    ] {
        for max_rows in [0, aps_cpu::MAX_FULL_TREE_ROWS + 1] {
            assert!(session
                .transformed_call_tree_selecting_sample(
                    &query,
                    &transforms,
                    max_rows,
                    Some(usize::MAX)
                )
                .is_err());
        }
        assert!(session
            .transformed_call_tree_selecting_sample(
                &Query {
                    start: Some(10),
                    end: Some(10),
                    ..query.clone()
                },
                &transforms,
                100,
                Some(usize::MAX),
            )
            .is_err());
    }
    assert!(session
        .transformed_call_tree_selecting_sample(
            &query,
            &TransformSet {
                focus: Some(String::new()),
                ..TransformSet::default()
            },
            100,
            None,
        )
        .is_err());
}
