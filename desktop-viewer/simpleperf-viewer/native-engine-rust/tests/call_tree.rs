use aps_simpleperf_engine::{
    analyze,
    simpleperf::{FileRecord, Frame, Record, Sample, SimpleperfStream, ThreadRecord},
};

fn frame(symbol: i32, address: u64) -> Frame {
    Frame {
        virtual_address: address,
        file_id: 7,
        symbol_id: symbol,
        execution_type: 0,
    }
}

fn sample(time: u64, weight: u64, frames: Vec<Frame>) -> Record {
    Record::Sample(Sample {
        time,
        thread_id: 42,
        event_count: weight,
        event_type_id: 0,
        callchain: frames,
    })
}

fn profile(mut records: Vec<Record>) -> SimpleperfStream {
    // Lookup records deliberately follow the samples.
    records.push(Record::File(FileRecord {
        id: 7,
        path: "fixture.so".into(),
        symbols: vec!["root".into(), "leaf".into(), "other".into()],
    }));
    records.push(Record::Thread(ThreadRecord {
        thread_id: 42,
        process_id: 1,
        name: "main".into(),
    }));
    SimpleperfStream {
        version: 1,
        records,
    }
}

#[test]
fn late_lookup_resolves_leaf_to_root_input_and_self_inclusive_weights() {
    let result = analyze(&profile(vec![
        sample(20, 3, vec![frame(1, 20), frame(0, 10)]),
        sample(10, 7, vec![frame(0, 10)]),
    ]));
    assert_eq!(result.call_tree.len(), 1);
    let root = &result.call_tree[0];
    assert_eq!((&*root.name, &*root.resource), ("root", "fixture.so"));
    assert_eq!(
        (
            root.inclusive_weight,
            root.self_weight,
            root.sample_count,
            root.self_sample_count
        ),
        (10, 7, 2, 1)
    );
    let leaf = &root.children[0];
    assert_eq!(&*leaf.name, "leaf");
    assert_eq!(
        (
            leaf.inclusive_weight,
            leaf.self_weight,
            leaf.sample_count,
            leaf.self_sample_count
        ),
        (3, 3, 1, 1)
    );
    assert!(leaf.children.is_empty());
    assert_eq!(result.summary.first_timestamp_nanos, Some(10));
    assert_eq!(result.summary.last_timestamp_nanos, Some(20));
    assert_eq!(
        (result.summary.file_count, result.summary.thread_count),
        (1, 1)
    );
}

#[test]
fn identical_ancestry_aggregates_independently_of_sample_order() {
    let a = sample(10, 3, vec![frame(1, 20), frame(0, 10)]);
    let b = sample(20, 5, vec![frame(1, 20), frame(0, 10)]);
    let first = analyze(&profile(vec![a.clone(), b.clone()]));
    assert_eq!(first, analyze(&profile(vec![b, a])));
    let root = &first.call_tree[0];
    let leaf = &root.children[0];
    assert_eq!(
        (root.inclusive_weight, root.self_weight, root.sample_count),
        (8, 0, 2)
    );
    assert_eq!(
        (leaf.inclusive_weight, leaf.self_weight, leaf.sample_count),
        (8, 8, 2)
    );
    assert_eq!(leaf.self_sample_count, 2);
}

#[test]
fn different_addresses_and_parents_do_not_merge() {
    let result = analyze(&profile(vec![
        sample(10, 2, vec![frame(1, 20), frame(0, 10)]),
        sample(20, 3, vec![frame(1, 21), frame(0, 10)]),
        sample(30, 5, vec![frame(1, 20), frame(2, 30)]),
    ]));
    assert_eq!(result.call_tree.len(), 2);
    let root = result
        .call_tree
        .iter()
        .find(|node| node.name == "root")
        .unwrap();
    assert_eq!(root.children.len(), 2);
    assert_eq!(root.inclusive_weight, 5);
    assert_eq!(
        root.children
            .iter()
            .map(|n| (n.virtual_address, n.self_weight))
            .collect::<Vec<_>>(),
        vec![(20, 2), (21, 3)]
    );
    let other = result
        .call_tree
        .iter()
        .find(|node| node.name == "other")
        .unwrap();
    assert_eq!(other.children[0].virtual_address, 20);
    assert_eq!(other.children[0].self_weight, 5);
}

#[test]
fn empty_stacks_count_in_summary_but_not_tree_and_lost_counts_remain_separate() {
    let result = analyze(&profile(vec![
        sample(10, 11, vec![]),
        Record::Lost {
            sample_count: 3,
            lost_count: 5,
        },
        Record::Lost {
            sample_count: 7,
            lost_count: 13,
        },
    ]));
    assert!(result.call_tree.is_empty());
    assert_eq!(result.summary.sample_count, 1);
    assert_eq!(result.summary.total_event_count, 11);
    assert_eq!(
        (
            result.summary.lost_sample_count,
            result.summary.lost_event_count
        ),
        (10, 18)
    );
    assert_eq!(result.summary.record_count, 5);
    let empty = analyze(&SimpleperfStream {
        version: 1,
        records: vec![],
    });
    assert!(empty.call_tree.is_empty());
    assert_eq!(
        (
            empty.summary.first_timestamp_nanos,
            empty.summary.last_timestamp_nanos
        ),
        (None, None)
    );
}

#[test]
fn recursive_paths_preserve_every_frame_at_the_512_frame_parser_budget() {
    let result = analyze(&profile(vec![sample(10, 9, vec![frame(0, 10); 512])]));
    let mut nodes = result.call_tree.as_slice();
    for depth in 0..512 {
        assert_eq!(nodes.len(), 1);
        let node = &nodes[0];
        assert_eq!(node.inclusive_weight, 9);
        assert_eq!(node.sample_count, 1);
        assert_eq!(node.self_weight, if depth == 511 { 9 } else { 0 });
        assert_eq!(node.self_sample_count, if depth == 511 { 1 } else { 0 });
        nodes = &node.children;
    }
    assert!(nodes.is_empty());
}
