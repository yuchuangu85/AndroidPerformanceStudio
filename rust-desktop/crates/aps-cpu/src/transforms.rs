//! Firefox-style flame-graph transforms over the filtered CPU sample stream.
//!
//! [`TransformSet`] models the Firefox Profiler transform controls: focus,
//! merge, collapse, drop and collapse-recursion.
//! [`CpuSession::transformed_call_tree`] and
//! [`CpuSession::transformed_flame_frame`] apply the set to the samples
//! selected by a [`Query`] without touching the session's cached untransformed
//! projections.
//!
//! Every selected sample's natural root-to-leaf stack is transformed in this
//! order (the Simpleperf callchain is leaf-first, so the stack is the reversed
//! callchain):
//!
//! 1. `drop` discards the whole sample when any frame's symbol name is in the
//!    set.
//! 2. `collapse_recursion` removes a frame when its `(symbol name, resource)`
//!    pair equals the immediately previous kept frame's pair, so a directly
//!    recursive function collapses to a single node.
//! 3. `collapse` removes every frame whose symbol name is in the set; its
//!    children splice onto its parent.
//! 4. `focus` keeps only samples containing the focused name, re-roots the
//!    stack at the leaf-most matching frame and keeps that frame plus its
//!    callees.
//! 5. Frames whose symbol name is in `merge` aggregate by symbol name alone
//!    (one node per parent) and retain the first resource seen for display.
//!    Every other frame aggregates by frame identity: file, symbol, virtual
//!    address (zero for known symbols) and execution type.
//!
//! Weights follow the query weight type: one per sample, or the sample's event
//! count. Every kept node carries an inclusive weight and sample count; the
//! leaf of the transformed stack carries the self weight and self sample count.
//! The query direction is not applied: transforms always operate on the natural
//! root-to-leaf stack and rows are emitted in that order.
//!
//! Rows form a deterministic preorder list whose sibling order is the
//! aggregation-key order. Row ids are `aps-transform-v1/<index>`; parent ids
//! follow the built tree. The full tree is truncated to the requested row limit
//! and `truncated` is set when it is larger.

use crate::{
    charts::flame_frame_from_rows,
    selection::{add, labels, select, weight, Selection},
    ChartFrame, ChartViewport, CpuSession, FullCallTree, Query, QueryRow, WeightType,
    MAX_FULL_TREE_ROWS,
};
use aps_simpleperf_engine::simpleperf::{Frame, MAX_CALLCHAIN_FRAMES};
use std::collections::{BTreeMap, BTreeSet};

/// Upper bound on the number of names accepted in one transform set.
pub const MAX_TRANSFORM_NAMES: usize = 32;
/// Upper bound on one transform name in UTF-8 bytes.
pub const MAX_TRANSFORM_NAME_BYTES: usize = 512;

/// Firefox-style transform controls applied to one query projection.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct TransformSet {
    /// Keep only samples containing this symbol name and re-root at its
    /// leaf-most frame.
    pub focus: Option<String>,
    /// Symbol names aggregated by name alone, one node per parent.
    pub merge: BTreeSet<String>,
    /// Symbol names removed from the stack with their children spliced onto
    /// the parent.
    pub collapse: BTreeSet<String>,
    /// Symbol names that discard the whole containing sample.
    pub drop: BTreeSet<String>,
    /// Remove a frame that repeats the immediately previous kept frame's
    /// `(symbol name, resource)` pair.
    pub collapse_recursion: bool,
}

impl TransformSet {
    /// True when the set would leave every selected sample untouched.
    pub fn is_empty(&self) -> bool {
        self.focus.is_none()
            && self.merge.is_empty()
            && self.collapse.is_empty()
            && self.drop.is_empty()
            && !self.collapse_recursion
    }

    /// Rejects empty or overlong names and names containing control
    /// characters. Each set holds at most [`MAX_TRANSFORM_NAMES`] names and
    /// each name holds at most [`MAX_TRANSFORM_NAME_BYTES`] bytes.
    pub fn validate(&self) -> Result<(), String> {
        if let Some(focus) = &self.focus {
            validate_name(focus, "focus")?;
        }
        for (label, names) in [
            ("merge", &self.merge),
            ("collapse", &self.collapse),
            ("drop", &self.drop),
        ] {
            if names.len() > MAX_TRANSFORM_NAMES {
                return Err(format!(
                    "{label} transform set must contain at most {MAX_TRANSFORM_NAMES} names"
                ));
            }
            for name in names {
                validate_name(name, label)?;
            }
        }
        Ok(())
    }
}

fn validate_name(name: &str, label: &str) -> Result<(), String> {
    if name.is_empty() {
        return Err(format!("{label} transform name must not be empty"));
    }
    if name.len() > MAX_TRANSFORM_NAME_BYTES {
        return Err(format!(
            "{label} transform name must not exceed {MAX_TRANSFORM_NAME_BYTES} bytes"
        ));
    }
    if name.chars().any(char::is_control) {
        return Err(format!(
            "{label} transform name must not contain control characters"
        ));
    }
    Ok(())
}

/// Aggregation identity of a frame that is not merged by symbol name.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
struct FrameKey {
    file_id: u32,
    symbol_id: i32,
    address: u64,
    execution_type: u32,
}

impl From<&Frame> for FrameKey {
    fn from(frame: &Frame) -> Self {
        Self {
            file_id: frame.file_id,
            symbol_id: frame.symbol_id,
            // A known symbol identifies the function; an unresolved frame keeps
            // its address so distinct unknown functions do not merge.
            address: if frame.symbol_id >= 0 {
                0
            } else {
                frame.virtual_address
            },
            execution_type: frame.execution_type,
        }
    }
}

/// Sibling aggregation key: exact frame identity, or symbol name when merged.
#[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord)]
enum NodeKey {
    Frame(FrameKey),
    Merged(String),
}

struct Node {
    parent: Option<usize>,
    depth: usize,
    children: BTreeMap<NodeKey, usize>,
    name: String,
    resource: String,
    inclusive_weight: u64,
    self_weight: u64,
    sample_count: u64,
    self_sample_count: u64,
}

impl CpuSession {
    /// Applies `transforms` to the samples selected by `query` and returns the
    /// complete transformed preorder tree, or its first `max_rows` rows when
    /// the tree is larger. `max_rows` must be within
    /// `1..=MAX_FULL_TREE_ROWS`. Pagination, direction and the cached
    /// untransformed projection are not used or modified.
    pub fn transformed_call_tree(
        &mut self,
        query: &Query,
        transforms: &TransformSet,
        max_rows: usize,
    ) -> Result<FullCallTree, String> {
        transforms.validate()?;
        if max_rows == 0 || max_rows > MAX_FULL_TREE_ROWS {
            return Err(format!(
                "transformed call tree row limit must be between 1 and {MAX_FULL_TREE_ROWS}"
            ));
        }
        let selected = select(&self.stream, query)?;
        let (nodes, roots) = aggregate(&selected, transforms, query.weight_type)?;
        let (rows, truncated) = materialize(&nodes, &roots, max_rows);
        Ok(FullCallTree { rows, truncated })
    }

    /// Builds the transformed rows capped at [`MAX_FULL_TREE_ROWS`] and lays
    /// them out with [`crate::flame_frame_from_rows`] under the same viewport,
    /// weight range and weight type as the untransformed flame graph.
    pub fn transformed_flame_frame(
        &mut self,
        query: &Query,
        transforms: &TransformSet,
        viewport: ChartViewport,
        start_fraction: f64,
        end_fraction: f64,
    ) -> Result<ChartFrame, String> {
        let tree = self.transformed_call_tree(query, transforms, MAX_FULL_TREE_ROWS)?;
        flame_frame_from_rows(
            &tree.rows,
            query.weight_type,
            viewport,
            start_fraction,
            end_fraction,
        )
    }
}

fn aggregate(
    selected: &Selection<'_>,
    transforms: &TransformSet,
    weight_type: WeightType,
) -> Result<(Vec<Node>, BTreeMap<NodeKey, usize>), String> {
    let mut roots = BTreeMap::new();
    let mut nodes: Vec<Node> = Vec::new();
    for (_, sample) in &selected.samples {
        if sample.callchain.len() > MAX_CALLCHAIN_FRAMES {
            return Err("callchain exceeds 512 frames".into());
        }
        // Leaf-first profile order reversed into the natural root-to-leaf stack.
        let mut chain: Vec<(&Frame, String, String)> = sample
            .callchain
            .iter()
            .rev()
            .map(|frame| {
                let (name, resource) = labels(&selected.files, frame);
                (frame, name, resource)
            })
            .collect();
        if chain
            .iter()
            .any(|(_, name, _)| transforms.drop.contains(name))
        {
            continue;
        }
        if transforms.collapse_recursion {
            let mut kept: Vec<(&Frame, String, String)> = Vec::with_capacity(chain.len());
            for frame in chain {
                let repeated = match kept.last() {
                    Some((_, name, resource)) => *name == frame.1 && *resource == frame.2,
                    None => false,
                };
                if !repeated {
                    kept.push(frame);
                }
            }
            chain = kept;
        }
        if !transforms.collapse.is_empty() {
            chain.retain(|(_, name, _)| !transforms.collapse.contains(name));
        }
        if let Some(focus) = &transforms.focus {
            let Some(index) = chain.iter().rposition(|(_, name, _)| name == focus) else {
                continue;
            };
            // Keep the focused function at the root of the transformed stack.
            chain = chain.split_off(index);
        }
        if chain.is_empty() {
            continue;
        }
        let value = weight(sample, weight_type);
        let leaf = chain.len() - 1;
        let mut parent: Option<usize> = None;
        for (depth, (frame, name, resource)) in chain.iter().enumerate() {
            let key = if transforms.merge.contains(name) {
                NodeKey::Merged(name.clone())
            } else {
                NodeKey::Frame(FrameKey::from(*frame))
            };
            let next = nodes.len();
            let children = match parent {
                Some(index) => &mut nodes[index].children,
                None => &mut roots,
            };
            let index = *children.entry(key).or_insert(next);
            if index == next {
                nodes.push(Node {
                    parent,
                    depth,
                    children: BTreeMap::new(),
                    name: name.clone(),
                    resource: resource.clone(),
                    inclusive_weight: 0,
                    self_weight: 0,
                    sample_count: 0,
                    self_sample_count: 0,
                });
            }
            let node = &mut nodes[index];
            add(&mut node.inclusive_weight, value)?;
            add(&mut node.sample_count, 1)?;
            if depth == leaf {
                add(&mut node.self_weight, value)?;
                add(&mut node.self_sample_count, 1)?;
            }
            parent = Some(index);
        }
    }
    Ok((nodes, roots))
}

fn materialize(
    nodes: &[Node],
    roots: &BTreeMap<NodeKey, usize>,
    max_rows: usize,
) -> (Vec<QueryRow>, bool) {
    let truncated = nodes.len() > max_rows;
    let mut rows = Vec::with_capacity(nodes.len().min(max_rows));
    let mut preorder = vec![usize::MAX; nodes.len()];
    let mut pending: Vec<usize> = roots.values().rev().copied().collect();
    while let Some(node_index) = pending.pop() {
        if rows.len() == max_rows {
            break;
        }
        let node = &nodes[node_index];
        let index = rows.len();
        preorder[node_index] = index;
        rows.push(QueryRow {
            id: format!("aps-transform-v1/{index}"),
            parent_id: node
                .parent
                .map(|parent| format!("aps-transform-v1/{}", preorder[parent])),
            depth: node.depth,
            name: node.name.clone(),
            resource: node.resource.clone(),
            inclusive_weight: node.inclusive_weight,
            self_weight: node.self_weight,
            sample_count: node.sample_count,
            self_sample_count: node.self_sample_count,
        });
        pending.extend(node.children.values().rev().copied());
    }
    (rows, truncated)
}
