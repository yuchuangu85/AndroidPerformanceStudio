mod common;

use aps_simpleperf_engine::{prepare, project, Direction, Query, WeightType, MAX_PAGE_SIZE};
use common::{profile, sample};
use std::collections::BTreeSet;

#[test]
fn prepared_pages_match_one_shot_for_each_direction_weight_and_filter() {
    for direction in [Direction::Forward, Direction::Inverted] {
        for weight_type in [WeightType::Samples, WeightType::EventCount] {
            let query = Query {
                direction,
                weight_type,
                thread_ids: BTreeSet::from([11, 22]),
                event_types: BTreeSet::from(["cpu-cycles".into()]),
                start: Some(10),
                end: Some(50),
                ..Query::default()
            };
            let prepared = prepare(&profile(), &query).unwrap();
            for offset in 0..=prepared.total() + 1 {
                for limit in [0, 1, 2, MAX_PAGE_SIZE] {
                    assert_eq!(
                        prepared.page(offset, limit).unwrap(),
                        project(
                            &profile(),
                            &Query {
                                offset,
                                limit,
                                ..query.clone()
                            }
                        )
                        .unwrap()
                    );
                }
            }
        }
    }
}

#[test]
fn preparation_retains_all_rows_even_when_requested_page_is_empty() {
    let stream = profile();
    let prepared = prepare(
        &stream,
        &Query {
            offset: 10_000,
            limit: 0,
            ..Query::default()
        },
    )
    .unwrap();
    assert_eq!(prepared.total(), 4);
    assert_eq!(
        prepared.page(0, 512).unwrap(),
        project(&stream, &Query::default()).unwrap()
    );
}

#[test]
fn prepared_page_bounds_match_query_validation() {
    let prepared = prepare(&profile(), &Query::default()).unwrap();
    assert!(prepared.page(0, 513).is_err());
    assert!(prepared.page(usize::MAX, 1).is_err());
    assert!(prepared.page(i64::MAX as usize, 0).unwrap().rows.is_empty());
    assert!(prepare(
        &profile(),
        &Query {
            start: Some(10),
            end: Some(10),
            ..Query::default()
        }
    )
    .is_err());
}

#[test]
fn invalid_filter_and_metric_overflow_never_publish_partial_preparation() {
    assert!(prepare(
        &profile(),
        &Query {
            thread_ids: BTreeSet::from([999]),
            ..Query::default()
        }
    )
    .is_err());
    let mut stream = profile();
    stream
        .records
        .push(sample(70, 11, 0, i64::MAX as u64, &[1, 0]));
    assert!(prepare(
        &stream,
        &Query {
            weight_type: WeightType::EventCount,
            event_types: BTreeSet::from(["cpu-cycles".into()]),
            limit: 0,
            ..Query::default()
        }
    )
    .is_err());
}

#[test]
fn prepared_projection_preserves_more_than_one_maximum_page() {
    let mut stream = profile();
    stream.records = (0..600)
        .map(|symbol| sample(symbol as u64, 1, 0, 1, &[symbol]))
        .collect();
    let prepared = prepare(&stream, &Query::default()).unwrap();
    assert_eq!(prepared.total(), 600);
    assert_eq!(prepared.page(0, 512).unwrap().rows.len(), 512);
    assert_eq!(prepared.page(512, 512).unwrap().rows.len(), 88);
    for offset in [0, 510, 512, 599, 600, 700] {
        assert_eq!(
            prepared.page(offset, 512).unwrap(),
            project(
                &stream,
                &Query {
                    offset,
                    ..Query::default()
                }
            )
            .unwrap()
        );
    }
}
