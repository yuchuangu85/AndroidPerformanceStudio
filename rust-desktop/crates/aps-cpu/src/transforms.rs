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
//! order; query direction is applied to the retained stack afterward:
//!
//! 1. `drop` discards the whole sample when any frame's symbol name is in the
//!    set.
//! 2. `collapse_recursion` removes a frame when its function identity
//!    equals the immediately previous kept frame's identity, so a directly
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
//! The query direction is applied after transforms, so an empty transform set
//! has the same rows, IDs and metrics as the normal query projection. Self
//! weight remains attached to the original sampled leaf when that frame is
//! retained; if a transform removes it, the transformed leaf receives it.
//!
//! Rows form a deterministic preorder list whose sibling order is the
//! aggregation-key order. Row ids are `aps-transform-v1/<index>`; parent ids
//! follow the built tree. The full tree is truncated to the requested row limit
//! and `truncated` is set when it is larger.

use crate::{
    charts::flame_frame_from_rows,
    selection::{add, labels, select, weight, Selection},
    ChartFrame, ChartViewport, CpuSession, Direction, FullCallTree, Query, QueryRow, WeightType,
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
    /// Remove a frame that repeats the immediately previous kept function.
    /// Unknown symbols retain their address identity.
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
    /// `1..=MAX_FULL_TREE_ROWS`. Pagination is ignored; query direction is
    /// applied after the transforms. Cached normal rows are not modified.
    pub fn transformed_call_tree(
        &mut self,
        query: &Query,
        transforms: &TransformSet,
        max_rows: usize,
    ) -> Result<FullCallTree, String> {
        self.transformed_call_tree_selecting_sample(query, transforms, max_rows, None)
            .map(|(tree, _)| tree)
    }

    /// Returns the transformed tree and the materialized row receiving the
    /// selected sample's self metrics. `sample_ordinal` is the original stream
    /// record ordinal, not an index into the filtered samples. If transforms
    /// remove the original leaf, selection follows the retained self frame.
    /// Filtered, dropped, missing and row-capped selections return `None`.
    pub fn transformed_call_tree_selecting_sample(
        &mut self,
        query: &Query,
        transforms: &TransformSet,
        max_rows: usize,
        sample_ordinal: Option<usize>,
    ) -> Result<(FullCallTree, Option<String>), String> {
        transforms.validate()?;
        if max_rows == 0 || max_rows > MAX_FULL_TREE_ROWS {
            return Err(format!(
                "transformed call tree row limit must be between 1 and {MAX_FULL_TREE_ROWS}"
            ));
        }
        if transforms.is_empty() {
            let tree = self.full_call_tree(query, max_rows)?;
            let selected_id = match sample_ordinal {
                Some(ordinal) => self.sample_call_node_id(query, ordinal)?,
                None => None,
            }
            .filter(|id| tree.rows.iter().any(|row| row.id == *id));
            return Ok((tree, selected_id));
        }
        let selected = select(&self.stream, query)?;
        let (nodes, roots, selected_node) = aggregate(
            &selected,
            transforms,
            query.direction,
            query.weight_type,
            sample_ordinal,
        )?;
        let (rows, truncated, selected_id) = materialize(&nodes, &roots, max_rows, selected_node);
        Ok((FullCallTree { rows, truncated }, selected_id))
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
    direction: Direction,
    weight_type: WeightType,
    sample_ordinal: Option<usize>,
) -> Result<(Vec<Node>, BTreeMap<NodeKey, usize>, Option<usize>), String> {
    let mut roots = BTreeMap::new();
    let mut nodes: Vec<Node> = Vec::new();
    let mut selected_node = None;
    for (ordinal, sample) in &selected.samples {
        if sample.callchain.len() > MAX_CALLCHAIN_FRAMES {
            return Err("callchain exceeds 512 frames".into());
        }
        // Keep the source index so self weight remains attached to the original
        // sampled leaf after direction and transforms are applied.
        let mut chain: Vec<(&Frame, String, String, usize)> = sample
            .callchain
            .iter()
            .enumerate()
            .rev()
            .map(|(source, frame)| {
                let (name, resource) = labels(&selected.files, frame);
                (frame, name, resource, source)
            })
            .collect();
        if chain
            .iter()
            .any(|(_, name, _, _)| transforms.drop.contains(name))
        {
            continue;
        }
        if transforms.collapse_recursion {
            let mut kept: Vec<(&Frame, String, String, usize)> = Vec::with_capacity(chain.len());
            for frame in chain {
                let repeated = match kept.last() {
                    Some((previous, _, _, _)) => {
                        FrameKey::from(*previous) == FrameKey::from(frame.0)
                    }
                    None => false,
                };
                if !repeated {
                    kept.push(frame);
                }
            }
            chain = kept;
        }
        if !transforms.collapse.is_empty() {
            chain.retain(|(_, name, _, _)| !transforms.collapse.contains(name));
        }
        if let Some(focus) = &transforms.focus {
            let Some(index) = chain.iter().rposition(|(_, name, _, _)| name == focus) else {
                continue;
            };
            // Keep the focused function at the root of the transformed stack.
            chain = chain.split_off(index);
        }
        if chain.is_empty() {
            continue;
        }
        let self_source = chain
            .iter()
            .find(|(_, _, _, source)| *source == 0)
            .unwrap_or_else(|| chain.last().expect("nonempty retained stack"))
            .3;
        if direction == Direction::Inverted {
            chain.reverse();
        }
        let self_index = chain
            .iter()
            .position(|(_, _, _, source)| *source == self_source)
            .expect("retained self frame");
        let value = weight(sample, weight_type);
        let mut parent: Option<usize> = None;
        for (depth, (frame, name, resource, _)) in chain.iter().enumerate() {
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
            if depth == self_index {
                add(&mut node.self_weight, value)?;
                add(&mut node.self_sample_count, 1)?;
                if sample_ordinal == Some(*ordinal) {
                    selected_node = Some(index);
                }
            }
            parent = Some(index);
        }
    }
    Ok((nodes, roots, selected_node))
}

fn materialize(
    nodes: &[Node],
    roots: &BTreeMap<NodeKey, usize>,
    max_rows: usize,
    selected_node: Option<usize>,
) -> (Vec<QueryRow>, bool, Option<String>) {
    let truncated = nodes.len() > max_rows;
    let mut rows = Vec::with_capacity(nodes.len().min(max_rows));
    let mut preorder = vec![usize::MAX; nodes.len()];
    let mut pending: Vec<usize> = roots.values().rev().copied().collect();
    let mut selected_id = None;
    while let Some(node_index) = pending.pop() {
        if rows.len() == max_rows {
            break;
        }
        let node = &nodes[node_index];
        let index = rows.len();
        preorder[node_index] = index;
        let id = format!("aps-transform-v1/{index}");
        if selected_node == Some(node_index) {
            selected_id = Some(id.clone());
        }
        rows.push(QueryRow {
            id,
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
    (rows, truncated, selected_id)
}
