#[path = "../../../../desktop-viewer/simpleperf-viewer/native-engine-rust/tests/common/mod.rs"]
mod common;

use aps_cpu::{
    ChartBlockKind, ChartDomain, ChartViewport, CpuSession, Direction, Query, TimelineViewport,
    WeightType,
};
use aps_simpleperf_engine::simpleperf::{FileRecord, Record, SimpleperfStream};
use common::{encoded, profile, sample};
use std::collections::BTreeSet;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
static SEQUENCE: AtomicUsize = AtomicUsize::new(0);
struct Fixture(PathBuf);
impl Fixture {
    fn stream(stream: &SimpleperfStream) -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-cpu-charts-{}-{}.protobuf",
            std::process::id(),
            SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::write(&path, encoded(stream)).unwrap();
        Self(path)
    }
    fn session(&self) -> CpuSession {
        CpuSession::open(&self.0).unwrap()
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

#[test]
fn top_functions_count_recursive_frames_once_per_sample_and_self_at_original_leaf() {
    let mut stream = profile();
    stream
        .records
        .retain(|record| !matches!(record, Record::Sample(_)));
    stream.records.push(sample(10, 11, 0, 7, &[1, 1, 1, 0]));
    let fixture = Fixture::stream(&stream);
    let mut session = fixture.session();
    let query = Query::default();
    let forward = session.top_functions(&query, 0, 512).unwrap();
    let recursive = forward.rows.iter().find(|row| row.name == "leaf").unwrap();
    assert_eq!(
        (
            recursive.inclusive_weight,
            recursive.self_weight,
            recursive.sample_count,
            recursive.thread_count
        ),
        (1, 1, 1, 1)
    );
    assert_eq!(
        session
            .top_functions(
                &Query {
                    direction: Direction::Inverted,
                    ..query.clone()
                },
                0,
                512
            )
            .unwrap(),
        forward
    );
    let weighted = session
        .top_functions(
            &Query {
                event_types: BTreeSet::from(["cpu-cycles".into()]),
                weight_type: WeightType::EventCount,
                ..query
            },
            0,
            512,
        )
        .unwrap();
    assert_eq!(
        (
            weighted.rows[0].inclusive_weight,
            weighted.rows[0].self_weight
        ),
        (7, 7)
    );
}

#[test]
fn flame_children_use_actual_weights_self_gaps_and_half_open_hit_testing() {
    let fixture = Fixture::stream(&profile());
    let mut session = fixture.session();
    let frame = session
        .flame_graph(
            &Query::default(),
            ChartViewport {
                width_px: 100.0,
                ..ChartViewport::default()
            },
        )
        .unwrap();
    assert_eq!(frame.total_depth, 2);
    assert!(!frame.truncated);
    let root = frame
        .blocks
        .iter()
        .find(|block| block.depth == 0 && block.label == "root")
        .unwrap();
    assert_eq!((root.x, root.width), (0.0, 80.0));
    assert_eq!(frame.hit_test(79.9, 1.0).unwrap().id, root.id);
    assert_ne!(frame.hit_test(80.0, 1.0).unwrap().id, root.id);
    assert!(frame.hit_test(100.0, 1.0).is_none());
    assert!(frame.hit_test(f32::NAN, 1.0).is_none());
    let leaf = frame
        .blocks
        .iter()
        .find(|block| block.depth == 1 && block.label == "leaf")
        .unwrap();
    assert_eq!(leaf.width, 60.0);
    // A root's one self sample leaves 20 pixels empty at the child's depth.
    assert!(frame.hit_test(70.0, 21.0).is_none());
    assert_eq!(frame.selected(&leaf.id), Some(leaf));
    let zoom = session
        .flame_graph_in_range(
            &Query::default(),
            ChartViewport {
                width_px: 100.0,
                ..ChartViewport::default()
            },
            0.0,
            0.5,
        )
        .unwrap();
    assert_eq!(zoom.hit_test(99.9, 1.0).unwrap().label, "root");
}

#[test]
fn subpixel_flame_bins_and_output_limits_are_explicit() {
    let stream = SimpleperfStream {
        version: 1,
        records: (0..600)
            .map(|symbol| sample(symbol as u64, 1, 0, 1, &[symbol]))
            .collect(),
    };
    let fixture = Fixture::stream(&stream);
    let mut session = fixture.session();
    let frame = session
        .flame_graph(
            &Query::default(),
            ChartViewport {
                width_px: 2.0,
                ..ChartViewport::default()
            },
        )
        .unwrap();
    assert_eq!(frame.blocks.len(), 2);
    assert!(frame
        .blocks
        .iter()
        .all(|block| block.kind == ChartBlockKind::PixelAggregate));
    assert_eq!(
        frame
            .blocks
            .iter()
            .map(|block| block.sample_count)
            .sum::<u64>(),
        600
    );
    let limited = session
        .flame_graph(
            &Query::default(),
            ChartViewport {
                width_px: 1200.0,
                max_blocks: 3,
                ..ChartViewport::default()
            },
        )
        .unwrap();
    assert_eq!(limited.blocks.len(), 3);
    assert!(limited.truncated);
    assert_eq!(limited.omitted_blocks, 597);
}

#[test]
fn timeline_marks_points_not_fake_execution_durations_and_aggregates_pixel_collisions() {
    let fixture = Fixture::stream(&profile());
    let session = fixture.session();
    let frame = session
        .stack_chart(
            &Query::default(),
            TimelineViewport {
                chart: ChartViewport {
                    width_px: 100.0,
                    ..ChartViewport::default()
                },
                start_nanos: 10,
                end_nanos: 60,
            },
        )
        .unwrap();
    assert!(matches!(
        frame.domain,
        ChartDomain::SamplePoints {
            start_nanos: 10,
            end_nanos: 60
        }
    ));
    assert!(frame
        .blocks
        .iter()
        .all(|block| block.width <= 1.0 && block.kind == ChartBlockKind::SamplePoint));
    assert_eq!(
        frame.hit_test(0.0, 1.0).unwrap().first_timestamp_nanos,
        Some(10)
    );
    assert!(frame.hit_test(10.0, 1.0).is_none());
    let collision = session
        .stack_chart(
            &Query::default(),
            TimelineViewport {
                chart: ChartViewport {
                    width_px: 1.0,
                    ..ChartViewport::default()
                },
                start_nanos: 10,
                end_nanos: 61,
            },
        )
        .unwrap();
    assert_eq!(collision.blocks.len(), 2);
    let root = collision.hit_test(0.1, 0.1).unwrap();
    assert_eq!(root.kind, ChartBlockKind::PixelAggregate);
    assert_eq!(
        (
            root.source_count,
            root.first_timestamp_nanos,
            root.last_timestamp_nanos,
            root.thread_id
        ),
        (5, Some(10), Some(50), None)
    );
}

#[test]
fn invalid_viewports_filters_and_weight_modes_fail_without_a_partial_packet() {
    let fixture = Fixture::stream(&profile());
    let mut session = fixture.session();
    for width in [0.0, f32::NAN, f32::INFINITY, 16385.0] {
        assert!(session
            .flame_graph(
                &Query::default(),
                ChartViewport {
                    width_px: width,
                    ..ChartViewport::default()
                }
            )
            .is_err());
    }
    assert!(session
        .flame_graph(
            &Query::default(),
            ChartViewport {
                max_blocks: 0,
                ..ChartViewport::default()
            }
        )
        .is_err());
    assert!(session
        .flame_graph_in_range(&Query::default(), ChartViewport::default(), 0.9, 0.5)
        .is_err());
    assert!(session
        .stack_chart(
            &Query::default(),
            TimelineViewport {
                chart: ChartViewport::default(),
                start_nanos: 10,
                end_nanos: 10
            }
        )
        .is_err());
    assert!(session
        .stack_chart(
            &Query {
                weight_type: WeightType::EventCount,
                ..Query::default()
            },
            TimelineViewport {
                chart: ChartViewport::default(),
                start_nanos: 0,
                end_nanos: 100
            }
        )
        .is_err());
    assert!(session.top_functions(&Query::default(), 0, 513).is_err());
}

#[test]
fn csv_escapes_raw_names_and_documents_native_identity_without_invented_thread_counts() {
    let mut stream = profile();
    stream.records.push(Record::File(FileRecord {
        id: 7,
        path: "path,with\nnewline".into(),
        symbols: vec!["root\"quoted".into(), "leaf".into(), "other".into()],
    }));
    let fixture = Fixture::stream(&stream);
    let mut session = fixture.session();
    let csv = session
        .export_call_tree_csv(&Query {
            offset: 999,
            limit: 0,
            ..Query::default()
        })
        .unwrap();
    assert!(csv.starts_with("id,parent_id,depth,symbol,file,inclusive_weight,exclusive_weight,sample_count,self_sample_count,weight_type,direction\n"));
    assert!(csv.contains("\"root\"\"quoted\""));
    assert!(csv.contains("\"path,with\nnewline\""));
    assert!(csv.contains("aps-cpu-v1:forward/"));
    assert!(!csv.lines().next().unwrap().contains("thread_count"));
    let top = session.export_top_functions_csv(&Query::default()).unwrap();
    assert!(
        top.starts_with("symbol,file,inclusive_weight,exclusive_weight,sample_count,thread_count,")
    );
}
