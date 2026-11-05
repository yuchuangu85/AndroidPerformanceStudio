use crate::simpleperf::{file_path, symbol_name, FileRecord, Record, SimpleperfStream};
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProfileSummary {
    pub format_version: u16,
    pub record_count: usize,
    pub sample_count: u64,
    pub lost_sample_count: u64,
    pub lost_event_count: u64,
    pub total_event_count: u64,
    pub first_timestamp_nanos: Option<u64>,
    pub last_timestamp_nanos: Option<u64>,
    pub file_count: usize,
    pub thread_count: usize,
    pub event_types: Vec<String>,
    pub trace_offcpu: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CallTreeNode {
    pub name: String,
    pub resource: String,
    pub file_id: u32,
    pub symbol_id: i32,
    pub virtual_address: u64,
    pub inclusive_weight: u64,
    pub self_weight: u64,
    pub sample_count: u64,
    pub self_sample_count: u64,
    pub children: Vec<CallTreeNode>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnalysisResult {
    pub summary: ProfileSummary,
    pub call_tree: Vec<CallTreeNode>,
}

pub fn analyze(stream: &SimpleperfStream) -> AnalysisResult {
    let files: BTreeMap<u32, FileRecord> = stream
        .records
        .iter()
        .filter_map(|record| match record {
            Record::File(file) => Some((file.id, file.clone())),
            _ => None,
        })
        .collect();
    let threads: BTreeSet<u32> = stream
        .records
        .iter()
        .filter_map(|record| match record {
            Record::Thread(thread) => Some(thread.thread_id),
            _ => None,
        })
        .collect();
    let mut event_types = Vec::new();
    let mut trace_offcpu = false;
    for record in &stream.records {
        if let Record::MetaInfo(meta) = record {
            event_types = meta.event_types.clone();
            trace_offcpu = meta.trace_offcpu;
        }
    }

    let mut sample_count = 0;
    let mut lost_sample_count: u64 = 0;
    let mut lost_event_count: u64 = 0;
    let mut total_event_count: u64 = 0;
    let mut first_timestamp_nanos = None;
    let mut last_timestamp_nanos = None;
    let mut roots = BTreeMap::<NodeKey, MutableNode>::new();
    for record in &stream.records {
        match record {
            Record::Sample(sample) => {
                sample_count += 1;
                total_event_count = total_event_count.saturating_add(sample.event_count);
                first_timestamp_nanos = Some(
                    first_timestamp_nanos.map_or(sample.time, |value: u64| value.min(sample.time)),
                );
                last_timestamp_nanos = Some(
                    last_timestamp_nanos.map_or(sample.time, |value: u64| value.max(sample.time)),
                );
                add_stack(&mut roots, sample, &files);
            }
            Record::Lost {
                sample_count,
                lost_count,
            } => {
                lost_sample_count = lost_sample_count.saturating_add(*sample_count);
                lost_event_count = lost_event_count.saturating_add(*lost_count);
            }
            _ => {}
        }
    }
    let call_tree = roots.into_values().map(|node| node.finish()).collect();
    AnalysisResult {
        summary: ProfileSummary {
            format_version: stream.version,
            record_count: stream.records.len(),
            sample_count,
            lost_sample_count,
            lost_event_count,
            total_event_count,
            first_timestamp_nanos,
            last_timestamp_nanos,
            file_count: files.len(),
            thread_count: threads.len(),
            event_types,
            trace_offcpu,
        },
        call_tree,
    }
}

#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
struct NodeKey {
    file_id: u32,
    symbol_id: i32,
    virtual_address: u64,
    name: String,
    resource: String,
}

struct MutableNode {
    key: NodeKey,
    inclusive_weight: u64,
    self_weight: u64,
    sample_count: u64,
    self_sample_count: u64,
    children: BTreeMap<NodeKey, MutableNode>,
}

fn add_stack(
    roots: &mut BTreeMap<NodeKey, MutableNode>,
    sample: &crate::simpleperf::Sample,
    files: &BTreeMap<u32, FileRecord>,
) {
    if sample.callchain.is_empty() {
        return;
    }
    let weight = sample.event_count;
    let mut level = roots;
    // Simpleperf stores leaf-to-root; tree paths and self weights use root-to-leaf.
    for (index, frame) in sample.callchain.iter().rev().enumerate() {
        let name = symbol_name(files, frame);
        let resource = file_path(files, frame);
        let key = NodeKey {
            file_id: frame.file_id,
            symbol_id: frame.symbol_id,
            virtual_address: frame.virtual_address,
            name,
            resource,
        };
        let node = level.entry(key.clone()).or_insert_with(|| MutableNode {
            key,
            inclusive_weight: 0,
            self_weight: 0,
            sample_count: 0,
            self_sample_count: 0,
            children: BTreeMap::new(),
        });
        node.inclusive_weight = node.inclusive_weight.saturating_add(weight);
        node.sample_count = node.sample_count.saturating_add(1);
        if index + 1 == sample.callchain.len() {
            node.self_weight = node.self_weight.saturating_add(weight);
            node.self_sample_count = node.self_sample_count.saturating_add(1);
        }
        level = &mut node.children;
    }
}

impl MutableNode {
    fn finish(self) -> CallTreeNode {
        CallTreeNode {
            name: self.key.name,
            resource: self.key.resource,
            file_id: self.key.file_id,
            symbol_id: self.key.symbol_id,
            virtual_address: self.key.virtual_address,
            inclusive_weight: self.inclusive_weight,
            self_weight: self.self_weight,
            sample_count: self.sample_count,
            self_sample_count: self.self_sample_count,
            children: self.children.into_values().map(Self::finish).collect(),
        }
    }
}
