mod common;

use aps_simpleperf_engine::{
    project,
    simpleperf::{parse_stream, FileRecord, Record, SimpleperfStream},
    Direction, Query, WeightType,
};
use common::{encoded, profile, sample};
use std::collections::BTreeSet;

fn cycles() -> Query {
    Query {
        event_types: BTreeSet::from(["cpu-cycles".into()]),
        weight_type: WeightType::EventCount,
        ..Query::default()
    }
}

#[test]
fn thread_and_event_sets_intersect_with_half_open_time_range() {
    let query = Query {
        thread_ids: BTreeSet::from([11, 22]),
        event_types: BTreeSet::from(["cpu-cycles".into()]),
        start: Some(10),
        end: Some(50),
        ..cycles()
    };
    let page = project(&profile(), &query).unwrap();
    assert_eq!(page.total, 2);
    assert_eq!(page.rows[0].inclusive_weight, 8);
    assert_eq!(page.rows[0].sample_count, 2);
    assert_eq!(page.rows[0].self_weight, 0);
    assert_eq!(page.rows[1].self_weight, 8);
    assert_eq!(page.rows[1].self_sample_count, 2);
    for (start, end, expected) in [
        (Some(10), Some(20), 3),
        (None, Some(10), 0),
        (Some(50), None, 7),
        (Some(51), None, 0),
        (Some(0), Some(1), 0),
    ] {
        let page = project(
            &profile(),
            &Query {
                start,
                end,
                ..cycles()
            },
        )
        .unwrap();
        assert_eq!(
            page.rows.first().map_or(0, |n| n.inclusive_weight),
            expected
        );
    }
}

#[test]
fn sample_weights_combine_events_without_adding_incompatible_event_counts() {
    let page = project(&profile(), &Query::default()).unwrap();
    assert_eq!(page.total, 4);
    assert_eq!(page.rows[0].inclusive_weight, 4);
    assert_eq!(page.rows[0].sample_count, 4);
    assert_eq!(page.rows[0].self_weight, 1);
    assert_eq!(page.rows[1].self_weight, 3);
    assert_eq!(page.rows[2].inclusive_weight, 1);
    assert!(project(
        &profile(),
        &Query {
            weight_type: WeightType::EventCount,
            ..Query::default()
        }
    )
    .unwrap_err()
    .contains("single event"));
    let query = Query {
        event_types: BTreeSet::from(["cpu-cycles".into(), "instructions".into()]),
        start: Some(10),
        end: Some(20),
        ..cycles()
    };
    // Validate the selected catalog before time/thread filtering or pagination.
    assert!(project(&profile(), &query).is_err());
    assert!(project(&profile(), &Query { limit: 0, ..query }).is_err());
    let page = project(&profile(), &cycles()).unwrap();
    assert_eq!(page.rows[0].inclusive_weight, 15);
}

#[test]
fn inverted_roots_are_sampled_leaves_and_self_stays_on_the_sampled_leaf() {
    let page = project(
        &profile(),
        &Query {
            direction: Direction::Inverted,
            ..cycles()
        },
    )
    .unwrap();
    assert_eq!(page.total, 2);
    let (root, caller) = (&page.rows[0], &page.rows[1]);
    assert_eq!(
        (&*root.name, root.depth, root.parent_id.as_ref()),
        ("leaf", 0, None)
    );
    assert_eq!(
        (
            root.inclusive_weight,
            root.self_weight,
            root.self_sample_count
        ),
        (15, 15, 3)
    );
    assert_eq!((&*caller.name, caller.depth), ("root", 1));
    assert_eq!(caller.parent_id.as_ref(), Some(&root.id));
    assert_eq!(
        (
            caller.inclusive_weight,
            caller.self_weight,
            caller.self_sample_count
        ),
        (15, 0, 0)
    );
}

#[test]
fn zero_event_counts_still_record_inclusive_and_self_sample_counts_in_both_directions() {
    let stream = SimpleperfStream {
        version: 1,
        records: vec![sample(10, 11, 0, 0, &[1, 0]), sample(20, 22, 0, 0, &[1, 0])],
    };
    for direction in [Direction::Forward, Direction::Inverted] {
        for weight_type in [WeightType::Samples, WeightType::EventCount] {
            let page = project(
                &stream,
                &Query {
                    direction,
                    weight_type,
                    ..Query::default()
                },
            )
            .unwrap();
            assert_eq!(page.total, 2);
            let weight = if weight_type == WeightType::Samples {
                2
            } else {
                0
            };
            let self_index = if direction == Direction::Inverted {
                0
            } else {
                1
            };
            for (index, row) in page.rows.iter().enumerate() {
                assert_eq!(row.inclusive_weight, weight);
                assert_eq!(row.sample_count, 2);
                assert_eq!(
                    row.self_weight,
                    if index == self_index { weight } else { 0 }
                );
                assert_eq!(
                    row.self_sample_count,
                    if index == self_index { 2 } else { 0 }
                );
            }
        }
    }
}

#[test]
fn explicit_multi_event_sample_set_excludes_other_events_and_deduplicates_names() {
    let mut stream = profile();
    stream.records.push(sample(70, 11, 9, 999, &[0]));
    let query = Query {
        event_types: BTreeSet::from(["cpu-cycles".into(), "instructions".into()]),
        ..Query::default()
    };
    assert_eq!(
        project(&stream, &query).unwrap().rows[0].inclusive_weight,
        4
    );
    assert_eq!(
        project(&stream, &Query::default()).unwrap().rows[0].inclusive_weight,
        5
    );
    let args: Vec<_> = [
        "11,11,22",
        "cpu-cycles,cpu-cycles",
        "-",
        "-",
        "forward",
        "events",
        "0",
        "512",
    ]
    .into_iter()
    .map(str::to_owned)
    .collect();
    let parsed = Query::from_cli(&args).unwrap();
    assert_eq!(parsed.thread_ids.len(), 2);
    assert_eq!(parsed.event_types.len(), 1);
    assert_eq!(
        project(&stream, &parsed).unwrap().rows[0].inclusive_weight,
        8
    );
}

#[test]
fn empty_profile_is_a_valid_empty_page_but_does_not_accept_unknown_filters() {
    let empty = SimpleperfStream {
        version: 1,
        records: vec![],
    };
    for weight_type in [WeightType::Samples, WeightType::EventCount] {
        let page = project(
            &empty,
            &Query {
                offset: 7,
                weight_type,
                ..Query::default()
            },
        )
        .unwrap();
        assert_eq!((page.total, page.offset, page.rows.len()), (0, 7, 0));
    }
    assert!(project(&empty, &cycles()).is_err());
}

#[test]
fn preorder_pages_keep_total_global_ancestry_and_requested_offset() {
    let whole = project(&profile(), &Query::default()).unwrap();
    let mut joined = Vec::new();
    for offset in 0..whole.total {
        let page = project(
            &profile(),
            &Query {
                offset,
                limit: 1,
                ..Query::default()
            },
        )
        .unwrap();
        assert_eq!((page.total, page.offset, page.rows.len()), (4, offset, 1));
        joined.extend(page.rows);
    }
    assert_eq!(joined, whole.rows);
    assert_eq!(joined[1].parent_id.as_ref(), Some(&joined[0].id));
    for (offset, limit) in [(0, 0), (4, 1), (123, 512)] {
        let page = project(
            &profile(),
            &Query {
                offset,
                limit,
                ..Query::default()
            },
        )
        .unwrap();
        assert_eq!((page.total, page.offset), (4, offset));
        assert!(page.rows.is_empty());
    }
    assert!(project(
        &profile(),
        &Query {
            limit: 513,
            ..Query::default()
        }
    )
    .is_err());
    assert!(project(
        &profile(),
        &Query {
            offset: usize::MAX,
            ..Query::default()
        }
    )
    .is_err());
}

#[test]
fn ids_are_ancestry_based_stable_across_order_filters_weights_and_pagination() {
    let original = profile();
    let all = project(&original, &Query::default()).unwrap();
    let filtered = project(
        &original,
        &Query {
            thread_ids: BTreeSet::from([11]),
            ..cycles()
        },
    )
    .unwrap();
    for row in &filtered.rows {
        assert!(all.rows.iter().any(|other| other.id == row.id));
        assert!(row.id.starts_with("aps-cpu-v1:forward/"));
    }
    // The same leaf under different callers must not alias.
    assert_ne!(all.rows[1].id, all.rows[3].id);
    let mut reordered = original.clone();
    reordered.records.reverse();
    assert_eq!(all, project(&reordered, &Query::default()).unwrap());
    reordered.records.push(sample(99, 11, 0, 1, &[0, 0]));
    let expanded = project(&reordered, &Query::default()).unwrap();
    assert!(all
        .rows
        .iter()
        .all(|row| expanded.rows.iter().any(|n| n.id == row.id)));
    let inverted = project(
        &original,
        &Query {
            direction: Direction::Inverted,
            ..cycles()
        },
    )
    .unwrap();
    assert!(inverted
        .rows
        .iter()
        .all(|n| !all.rows.iter().any(|row| row.id == n.id)));
}

#[test]
fn late_file_thread_and_event_lookups_resolve_after_stream_parsing() {
    let parsed = parse_stream(&encoded(&profile())).unwrap();
    assert_eq!(
        project(&parsed, &cycles()).unwrap(),
        project(&profile(), &cycles()).unwrap()
    );
    let page = project(&parsed, &cycles()).unwrap();
    assert_eq!(
        (&*page.rows[0].name, &*page.rows[0].resource),
        ("root", "fixture.so")
    );
    // Metadata-only thread IDs are valid selections, not unknown filters.
    assert!(project(
        &parsed,
        &Query {
            thread_ids: BTreeSet::from([44]),
            ..cycles()
        }
    )
    .unwrap()
    .rows
    .is_empty());
}

#[test]
fn unknown_filters_and_invalid_bounds_fail_even_for_empty_windows_and_pages() {
    for query in [
        Query {
            thread_ids: BTreeSet::from([11, 999]),
            ..Query::default()
        },
        Query {
            event_types: BTreeSet::from(["cpu-cycles".into(), "typo".into()]),
            ..Query::default()
        },
        Query {
            start: Some(20),
            end: Some(10),
            ..Query::default()
        },
        Query {
            start: Some(10),
            end: Some(10),
            ..Query::default()
        },
    ] {
        assert!(project(&profile(), &query).is_err());
        assert!(project(
            &profile(),
            &Query {
                offset: 999,
                limit: 0,
                ..query
            }
        )
        .is_err());
    }
}

#[test]
fn missing_metadata_uses_distinct_event_id_fallbacks_and_preserves_frame_identity() {
    let mut stream = SimpleperfStream {
        version: 1,
        records: vec![sample(10, 11, 7, 3, &[1, 0]), sample(20, 22, 9, 5, &[1, 0])],
    };
    let all = project(&stream, &Query::default()).unwrap();
    assert_eq!(all.rows[0].resource, "<unknown-file:7>");
    assert_eq!(all.rows[0].name, "<unknown-symbol>");
    assert!(project(
        &stream,
        &Query {
            weight_type: WeightType::EventCount,
            ..Query::default()
        }
    )
    .is_err());
    let query = Query {
        event_types: BTreeSet::from(["event#9".into()]),
        ..cycles()
    };
    assert_eq!(
        project(&stream, &query).unwrap().rows[0].inclusive_weight,
        5
    );
    stream.records.push(Record::File(FileRecord {
        id: 7,
        path: "resolved.so".into(),
        symbols: vec!["root".into(), "leaf".into()],
    }));
    let resolved = project(&stream, &Query::default()).unwrap();
    assert_eq!(all.rows[0].id, resolved.rows[0].id);
    assert_eq!(all.rows[1].id, resolved.rows[1].id);
    if let Record::Sample(sample) = &mut stream.records[1] {
        sample.callchain[0].execution_type = 1;
    }
    let separated = project(&stream, &Query::default()).unwrap();
    assert_eq!(separated.total, 3);
    assert_ne!(separated.rows[1].id, separated.rows[2].id);
}

#[test]
fn metrics_overflow_errors_even_if_bad_rows_are_outside_the_page() {
    let stream = SimpleperfStream {
        version: 1,
        records: vec![
            sample(10, 11, 0, i64::MAX as u64, &[0]),
            sample(20, 11, 0, 1, &[0]),
        ],
    };
    assert!(project(
        &stream,
        &Query {
            weight_type: WeightType::EventCount,
            offset: 999,
            limit: 0,
            ..Query::default()
        }
    )
    .unwrap_err()
    .contains("signed i64"));
    assert_eq!(
        project(&stream, &Query::default()).unwrap().rows[0].inclusive_weight,
        2
    );
}

#[test]
fn deep_recursive_paths_are_iterative_and_paginate_at_the_512_frame_boundary() {
    let stream = SimpleperfStream {
        version: 1,
        records: vec![sample(10, 11, 0, 9, &vec![0; 512])],
    };
    for direction in [Direction::Forward, Direction::Inverted] {
        let page = project(
            &stream,
            &Query {
                direction,
                offset: 511,
                limit: 1,
                weight_type: WeightType::EventCount,
                ..Query::default()
            },
        )
        .unwrap();
        assert_eq!(page.total, 512);
        assert_eq!(page.rows[0].depth, 511);
        assert_eq!(page.rows[0].inclusive_weight, 9);
        assert_eq!(
            page.rows[0].self_weight,
            if direction == Direction::Forward {
                9
            } else {
                0
            }
        );
        assert_eq!(page.rows[0].id.matches('/').count(), 512);
    }
    let invalid = SimpleperfStream {
        version: 1,
        records: vec![sample(10, 11, 0, 9, &vec![0; 513])],
    };
    assert!(project(&invalid, &Query::default()).is_err());
}
