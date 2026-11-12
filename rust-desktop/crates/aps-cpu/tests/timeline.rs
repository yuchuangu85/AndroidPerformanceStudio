#[path = "../../../../desktop-viewer/simpleperf-viewer/native-engine-rust/tests/common/mod.rs"]
mod common;

use aps_cpu::{ChartViewport, CpuSession, Direction, Query, TimelineViewport};
use aps_simpleperf_engine::simpleperf::{Record, SimpleperfStream};
use common::{encoded, profile, sample};
use std::{
    path::PathBuf,
    sync::atomic::{AtomicUsize, Ordering},
};
static SEQUENCE: AtomicUsize = AtomicUsize::new(0);

struct Fixture(PathBuf);
impl Fixture {
    fn session(stream: SimpleperfStream) -> (Self, CpuSession) {
        let path = std::env::temp_dir().join(format!(
            "aps-timeline-{}-{}.pb",
            std::process::id(),
            SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::write(&path, encoded(&stream)).unwrap();
        let session = CpuSession::open(&path).unwrap();
        (Self(path), session)
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}
fn viewport() -> TimelineViewport {
    TimelineViewport {
        start_nanos: 0,
        end_nanos: 100,
        chart: ChartViewport {
            width_px: 100.,
            ..ChartViewport::default()
        },
    }
}
#[test]
fn timestamp_tracks_keep_real_stack_depth_and_exact_function_identity() {
    let (_fixture, mut session) = Fixture::session(profile());
    let timeline = session
        .sample_timeline(&Query::default(), viewport())
        .unwrap();
    assert_eq!(
        timeline
            .threads
            .iter()
            .map(|thread| thread.total_samples)
            .sum::<usize>(),
        5
    );
    let thread = timeline
        .threads
        .iter()
        .find(|thread| thread.thread_id == 11)
        .unwrap();
    assert_eq!(
        thread
            .samples
            .iter()
            .map(|point| (point.ordinal, point.time_nanos, point.stack_depth))
            .collect::<Vec<_>>(),
        [(0, 10, 2), (2, 30, 2)]
    );
    assert_eq!(thread.interval_nanos, Some(20));
    for direction in [Direction::Forward, Direction::Inverted] {
        let query = Query {
            direction,
            ..Query::default()
        };
        let id = session.sample_call_node_id(&query, 0).unwrap().unwrap();
        let tree = session.full_call_tree(&query, 100).unwrap();
        let row = tree.rows.iter().find(|row| row.id == id).unwrap();
        assert_eq!(row.name, "leaf");
        assert!(row.self_sample_count > 0);
        if direction == Direction::Inverted {
            assert_eq!(row.depth, 0);
        }
    }
    assert_eq!(
        session.sample_at_time(&Query::default(), 22, 31).unwrap(),
        Some(3)
    );
    assert_eq!(
        session.sample_at_time(&Query::default(), 44, 31).unwrap(),
        None
    );
}
#[test]
fn decimation_preserves_deepest_stack_but_click_uses_original_samples() {
    let mut stream = profile();
    stream
        .records
        .retain(|record| !matches!(record, Record::Sample(_)));
    let first = stream.records.len();
    stream.records.extend([
        sample(10, 11, 0, 1, &[1, 0]),
        sample(11, 11, 0, 1, &[2, 1, 0]),
        sample(12, 11, 0, 1, &[1, 0]),
    ]);
    let (_fixture, session) = Fixture::session(stream);
    let viewport = TimelineViewport {
        chart: ChartViewport {
            width_px: 1.,
            ..viewport().chart
        },
        ..viewport()
    };
    let timeline = session
        .sample_timeline(&Query::default(), viewport)
        .unwrap();
    assert!(timeline.truncated);
    assert_eq!(timeline.omitted_samples, 2);
    assert_eq!(timeline.threads[0].samples[0].ordinal, first + 1);
    assert_eq!(timeline.threads[0].samples[0].stack_depth, 3);
    assert_eq!(
        session.sample_at_time(&Query::default(), 11, 10).unwrap(),
        Some(first)
    );
    assert_eq!(
        session.sample_at_time(&Query::default(), 11, 12).unwrap(),
        Some(first + 2)
    );
}
#[test]
fn limits_filters_empty_stacks_and_invalid_domains_do_not_invent_hits() {
    let (_fixture, session) = Fixture::session(profile());
    let mut viewport = viewport();
    viewport.chart.max_blocks = 1;
    let timeline = session
        .sample_timeline(&Query::default(), viewport)
        .unwrap();
    assert_eq!(
        timeline
            .threads
            .iter()
            .map(|thread| thread.samples.len())
            .sum::<usize>(),
        1
    );
    assert!(timeline.truncated);
    let query = Query {
        thread_ids: [11].into(),
        start: Some(20),
        end: Some(40),
        ..Query::default()
    };
    assert_eq!(session.sample_call_node_id(&query, 0).unwrap(), None);
    assert_eq!(
        session.sample_call_node_id(&Query::default(), 5).unwrap(),
        None
    );
    assert_eq!(
        session
            .sample_call_node_id(&Query::default(), usize::MAX)
            .unwrap(),
        None
    );
    assert_eq!(session.sample_at_time(&query, 22, 30).unwrap(), None);
    assert!(session
        .sample_timeline(
            &Query::default(),
            TimelineViewport {
                end_nanos: 0,
                ..viewport
            }
        )
        .is_err());
}
#[test]
fn sampled_leaf_highlights_all_contributing_stacks_not_just_clicked_sample() {
    let (_fixture, session) = Fixture::session(profile());
    let timeline = session
        .sample_timeline(&Query::default(), viewport())
        .unwrap();
    assert_eq!(
        session.timeline_sample_highlights(&timeline, 0, Direction::Forward),
        [0, 1, 4]
    );
    assert_eq!(
        session.timeline_sample_highlights(&timeline, 0, Direction::Inverted),
        [0, 2, 1, 4]
    );
}
