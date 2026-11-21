use crate::{
    selection::{add, labels, select, weight},
    Query, WeightType,
};
use aps_simpleperf_engine::simpleperf::{Frame, SimpleperfStream};
use std::cmp::Ordering;
use std::collections::{BTreeMap, BTreeSet};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TopFunction {
    pub id: String,
    pub name: String,
    pub resource: String,
    /// Per-sample inclusive weight, counted once for a recursive function.
    pub inclusive_weight: u64,
    /// Weight at the original sampled leaf, regardless of query direction.
    pub self_weight: u64,
    pub sample_count: u64,
    pub self_sample_count: u64,
    pub thread_count: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TopFunctionsPage {
    pub total: usize,
    pub offset: usize,
    pub rows: Vec<TopFunction>,
    pub weight_type: WeightType,
}

/// Ordering applied to the complete top-function list before pagination.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum TopSort {
    /// Per-sample weight at the original sampled leaf.
    #[default]
    SelfWeight,
    /// Per-sample weight of the whole filtered callchain.
    InclusiveWeight,
    SampleCount,
    ThreadCount,
    SymbolName,
    FilePath,
}

impl TopSort {
    pub const ALL: [Self; 6] = [
        Self::SelfWeight,
        Self::InclusiveWeight,
        Self::SampleCount,
        Self::ThreadCount,
        Self::SymbolName,
        Self::FilePath,
    ];
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
struct FunctionKey {
    file: u32,
    symbol: i32,
    address: u64,
    execution: u32,
}
impl From<&Frame> for FunctionKey {
    fn from(frame: &Frame) -> Self {
        Self {
            file: frame.file_id,
            symbol: frame.symbol_id,
            // Known symbol IDs identify the function, not individual addresses.
            // Unknown symbols must retain addresses to avoid false merging.
            address: if frame.symbol_id >= 0 {
                0
            } else {
                frame.virtual_address
            },
            execution: frame.execution_type,
        }
    }
}

pub(crate) fn aggregate(
    stream: &SimpleperfStream,
    query: &Query,
) -> Result<Vec<TopFunction>, String> {
    let selected = select(stream, query)?;
    let mut functions: BTreeMap<FunctionKey, (TopFunction, BTreeSet<i32>)> = BTreeMap::new();
    for (_, sample) in &selected.samples {
        let mut seen = BTreeSet::new();
        let value = weight(sample, query.weight_type);
        for (depth, frame) in sample.callchain.iter().enumerate() {
            let key = FunctionKey::from(frame);
            let entry = functions.entry(key).or_insert_with(|| {
                let (name, resource) = labels(&selected.files, frame);
                (
                    TopFunction {
                        id: format!(
                            "aps-function-v1/{:x}:{:x}:{:x}:{:x}",
                            key.file, key.symbol as u32, key.address, key.execution
                        ),
                        name,
                        resource,
                        inclusive_weight: 0,
                        self_weight: 0,
                        sample_count: 0,
                        self_sample_count: 0,
                        thread_count: 0,
                    },
                    BTreeSet::new(),
                )
            });
            if seen.insert(key) {
                add(&mut entry.0.inclusive_weight, value)?;
                add(&mut entry.0.sample_count, 1)?;
                entry.1.insert(sample.thread_id);
            }
            if depth == 0 {
                add(&mut entry.0.self_weight, value)?;
                add(&mut entry.0.self_sample_count, 1)?;
            }
        }
    }
    let mut rows: Vec<_> = functions
        .into_values()
        .map(|(mut row, threads)| {
            row.thread_count = threads.len();
            row
        })
        .collect();
    sort(&mut rows, TopSort::SelfWeight, true);
    Ok(rows)
}

/// Sorts the complete row list. `descending` flips only the primary key; the
/// deterministic tie-breakers stay ascending so equal keys never depend on the
/// aggregation or input order. Only the default self-weight order keeps the
/// legacy descending inclusive-weight tie-break, so it stays byte-identical to
/// `top_functions`.
pub(crate) fn sort(rows: &mut [TopFunction], sort: TopSort, descending: bool) {
    rows.sort_by(|a, b| {
        let primary = match sort {
            TopSort::SelfWeight => a.self_weight.cmp(&b.self_weight),
            TopSort::InclusiveWeight => a.inclusive_weight.cmp(&b.inclusive_weight),
            TopSort::SampleCount => a.sample_count.cmp(&b.sample_count),
            TopSort::ThreadCount => a.thread_count.cmp(&b.thread_count),
            TopSort::SymbolName => case_insensitive_then_exact(&a.name, &b.name),
            TopSort::FilePath => case_insensitive_then_exact(&a.resource, &b.resource),
        };
        let primary = if descending {
            primary.reverse()
        } else {
            primary
        };
        let legacy_weight_tie_break = if sort == TopSort::SelfWeight && descending {
            b.inclusive_weight.cmp(&a.inclusive_weight)
        } else {
            Ordering::Equal
        };
        primary
            .then(legacy_weight_tie_break)
            .then_with(|| a.name.cmp(&b.name))
            .then_with(|| a.resource.cmp(&b.resource))
            .then_with(|| a.id.cmp(&b.id))
    });
}

fn case_insensitive_then_exact(a: &str, b: &str) -> Ordering {
    a.to_lowercase()
        .cmp(&b.to_lowercase())
        .then_with(|| a.cmp(b))
}
