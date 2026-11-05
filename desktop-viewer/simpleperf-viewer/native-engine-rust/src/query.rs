//! Independent native query projection, not Kotlin function/node ID parity.
use crate::simpleperf::{FileRecord, Frame, Record, SimpleperfStream, MAX_CALLCHAIN_FRAMES};
use std::collections::{BTreeMap, BTreeSet};
use std::fmt::Write;

pub const MAX_PAGE_SIZE: usize = 512;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Direction {
    #[default]
    Forward,
    Inverted,
}

impl Direction {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Forward => "forward",
            Self::Inverted => "inverted",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum WeightType {
    #[default]
    Samples,
    EventCount,
}

impl WeightType {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Samples => "samples",
            Self::EventCount => "events",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Query {
    /// Empty sets select the entire profile's catalog, not an empty selection.
    pub thread_ids: BTreeSet<i32>,
    pub event_types: BTreeSet<String>,
    pub start: Option<u64>,
    pub end: Option<u64>,
    pub direction: Direction,
    pub weight_type: WeightType,
    pub offset: usize,
    pub limit: usize,
}

impl Default for Query {
    fn default() -> Self {
        Self {
            thread_ids: BTreeSet::new(),
            event_types: BTreeSet::new(),
            start: None,
            end: None,
            direction: Direction::Forward,
            weight_type: WeightType::Samples,
            offset: 0,
            limit: MAX_PAGE_SIZE,
        }
    }
}

impl Query {
    /// The eight arguments after the path in the one-shot query command.
    pub fn from_cli(args: &[String]) -> Result<Self, String> {
        let [threads, events, start, end, direction, weight, offset, limit] = args else {
            return Err("query requires eight arguments after the path".into());
        };
        let thread_ids = csv(threads)?
            .into_iter()
            .map(|id| {
                let digits = id.strip_prefix('-').unwrap_or(id);
                decimal(digits, "thread ID")?;
                id.parse::<i32>()
                    .map_err(|_| format!("invalid thread ID {id}"))
            })
            .collect::<Result<_, _>>()?;
        let query = Self {
            thread_ids,
            event_types: csv(events)?.into_iter().map(str::to_owned).collect(),
            start: bound(start, "start")?,
            end: bound(end, "end")?,
            direction: match direction.as_str() {
                "forward" => Direction::Forward,
                "inverted" => Direction::Inverted,
                _ => return Err("direction must be forward or inverted".into()),
            },
            weight_type: match weight.as_str() {
                "samples" => WeightType::Samples,
                "events" => WeightType::EventCount,
                _ => return Err("weight must be samples or events".into()),
            },
            offset: usize::try_from(decimal(offset, "offset")?)
                .map_err(|_| "offset exceeds platform size")?,
            limit: usize::try_from(decimal(limit, "limit")?)
                .map_err(|_| "limit exceeds platform size")?,
        };
        query.validate()?;
        Ok(query)
    }

    pub fn validate(&self) -> Result<(), String> {
        if self.start.zip(self.end).is_some_and(|(a, b)| a >= b) {
            return Err("time bounds require start < end ([start,end))".into());
        }
        if self.limit > MAX_PAGE_SIZE {
            return Err("page limit must be between 0 and 512".into());
        }
        if self.offset > i64::MAX as usize || self.offset.checked_add(self.limit).is_none() {
            return Err("page offset exceeds supported bounds".into());
        }
        Ok(())
    }
}

fn csv(value: &str) -> Result<Vec<&str>, String> {
    if value == "-" {
        return Ok(Vec::new());
    }
    let values: Vec<_> = value.split(',').collect();
    if values.iter().any(|v| v.is_empty() || *v == "-") {
        return Err("filter CSV contains an empty or wildcard element".into());
    }
    Ok(values)
}

fn decimal(value: &str, label: &str) -> Result<u64, String> {
    if value.is_empty() || !value.bytes().all(|b| b.is_ascii_digit()) {
        return Err(format!("{label} must be an unsigned decimal integer"));
    }
    value.parse().map_err(|_| format!("{label} exceeds u64"))
}

fn bound(value: &str, label: &str) -> Result<Option<u64>, String> {
    if value == "-" {
        Ok(None)
    } else {
        decimal(value, label).map(Some)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueryRow {
    pub id: String,
    pub parent_id: Option<String>,
    pub depth: usize,
    pub name: String,
    pub resource: String,
    pub inclusive_weight: u64,
    pub self_weight: u64,
    pub sample_count: u64,
    pub self_sample_count: u64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueryPage {
    pub total: usize,
    pub offset: usize,
    pub rows: Vec<QueryRow>,
    pub weight_type: WeightType,
    pub direction: Direction,
}

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
            address: frame.virtual_address,
            execution_type: frame.execution_type,
        }
    }
}

struct Node {
    key: FrameKey,
    parent: Option<usize>,
    depth: usize,
    children: BTreeMap<FrameKey, usize>,
    inclusive_weight: u64,
    self_weight: u64,
    sample_count: u64,
    self_sample_count: u64,
}

/// Aggregation is in-memory; only encoded input buffering and output page size
/// are bounded independently of profile size. Nothing is published on failure.
pub fn project(stream: &SimpleperfStream, query: &Query) -> Result<QueryPage, String> {
    query.validate()?;
    let mut files = BTreeMap::<u32, &FileRecord>::new();
    let mut threads = BTreeSet::new();
    let mut events = BTreeMap::new();
    for record in &stream.records {
        match record {
            Record::File(file) => {
                files.insert(file.id, file);
            }
            Record::Thread(thread) => {
                if let Ok(id) = i32::try_from(thread.thread_id) {
                    threads.insert(id);
                }
            }
            // Like analyze, the final metadata record defines the catalog.
            Record::MetaInfo(meta) => {
                events = meta
                    .event_types
                    .iter()
                    .enumerate()
                    .map(|(id, name)| (id as u32, name.clone()))
                    .collect();
            }
            Record::Sample(sample) => {
                threads.insert(sample.thread_id);
            }
            _ => {}
        }
    }
    for record in &stream.records {
        if let Record::Sample(sample) = record {
            events
                .entry(sample.event_type_id)
                .or_insert_with(|| format!("event#{}", sample.event_type_id));
        }
    }
    for id in &query.thread_ids {
        if !threads.contains(id) {
            return Err(format!("unknown thread filter {id}"));
        }
    }
    for name in &query.event_types {
        if !events.values().any(|event| event == name) {
            return Err(format!("unknown event filter {name}"));
        }
    }
    let selected_events: BTreeSet<_> = events
        .iter()
        .filter(|(_, name)| query.event_types.is_empty() || query.event_types.contains(*name))
        .map(|(id, _)| *id)
        .collect();
    if query.weight_type == WeightType::EventCount && selected_events.len() > 1 {
        return Err(
            "EVENT_COUNT requires a single event type; use samples for multiple events".into(),
        );
    }

    let mut roots = BTreeMap::new();
    let mut nodes: Vec<Node> = Vec::new();
    for record in &stream.records {
        let Record::Sample(sample) = record else {
            continue;
        };
        if (!query.thread_ids.is_empty() && !query.thread_ids.contains(&sample.thread_id))
            || !selected_events.contains(&sample.event_type_id)
            || query.start.is_some_and(|start| sample.time < start)
            || query.end.is_some_and(|end| sample.time >= end)
        {
            continue;
        }
        if sample.callchain.len() > MAX_CALLCHAIN_FRAMES {
            return Err("callchain exceeds 512 frames".into());
        }
        let weight = match query.weight_type {
            WeightType::Samples => 1,
            WeightType::EventCount => sample.event_count,
        };
        let mut parent: Option<usize> = None;
        for depth in 0..sample.callchain.len() {
            let source = match query.direction {
                Direction::Forward => sample.callchain.len() - depth - 1,
                Direction::Inverted => depth,
            };
            let key = FrameKey::from(&sample.callchain[source]);
            let next = nodes.len();
            let siblings = match parent {
                Some(index) => &mut nodes[index].children,
                None => &mut roots,
            };
            let index = *siblings.entry(key).or_insert(next);
            if index == next {
                nodes.push(Node {
                    key,
                    parent,
                    depth,
                    children: BTreeMap::new(),
                    inclusive_weight: 0,
                    self_weight: 0,
                    sample_count: 0,
                    self_sample_count: 0,
                });
            }
            let node = &mut nodes[index];
            add(&mut node.inclusive_weight, weight)?;
            add(&mut node.sample_count, 1)?;
            // Self always belongs to the original sampled leaf, even inverted.
            if source == 0 {
                add(&mut node.self_weight, weight)?;
                add(&mut node.self_sample_count, 1)?;
            }
            parent = Some(index);
        }
    }

    let mut page = QueryPage {
        total: nodes.len(),
        offset: query.offset,
        rows: Vec::new(),
        weight_type: query.weight_type,
        direction: query.direction,
    };
    if query.offset >= nodes.len() || query.limit == 0 {
        return Ok(page);
    }
    let mut pending: Vec<_> = roots.into_values().rev().collect();
    let mut position = 0;
    while let Some(index) = pending.pop() {
        let node = &nodes[index];
        if position >= query.offset {
            let id = node_id(&nodes, index, query.direction);
            let parent_id = node
                .parent
                .map(|_| id.rsplit_once('/').unwrap().0.to_owned());
            // Borrowed lookup map avoids cloning the entire symbol catalog.
            let name = files
                .get(&node.key.file_id)
                .and_then(|file| {
                    usize::try_from(node.key.symbol_id)
                        .ok()
                        .and_then(|i| file.symbols.get(i))
                })
                .cloned()
                .unwrap_or_else(|| "<unknown-symbol>".into());
            let resource = files
                .get(&node.key.file_id)
                .map(|file| file.path.clone())
                .unwrap_or_else(|| format!("<unknown-file:{}>", node.key.file_id));
            page.rows.push(QueryRow {
                id,
                parent_id,
                depth: node.depth,
                name,
                resource,
                inclusive_weight: node.inclusive_weight,
                self_weight: node.self_weight,
                sample_count: node.sample_count,
                self_sample_count: node.self_sample_count,
            });
            if page.rows.len() == query.limit {
                break;
            }
        }
        position += 1;
        pending.extend(node.children.values().rev().copied());
    }
    Ok(page)
}

fn add(target: &mut u64, value: u64) -> Result<(), String> {
    *target = target
        .checked_add(value)
        .filter(|sum| *sum <= i64::MAX as u64)
        .ok_or("query metric exceeds signed i64")?;
    Ok(())
}

fn node_id(nodes: &[Node], mut index: usize, direction: Direction) -> String {
    let mut path = Vec::with_capacity(nodes[index].depth + 1);
    loop {
        path.push(nodes[index].key);
        match nodes[index].parent {
            Some(parent) => index = parent,
            None => break,
        }
    }
    // Full numeric ancestry is injective: no hash collisions or sibling ranks.
    // Display labels, weights, filters and pagination do not affect identity.
    let mut id = format!("aps-cpu-v1:{}", direction.as_str());
    for key in path.into_iter().rev() {
        write!(
            id,
            "/{:x}:{:x}:{:x}:{:x}",
            key.file_id, key.symbol_id as u32, key.address, key.execution_type
        )
        .unwrap();
    }
    id
}
