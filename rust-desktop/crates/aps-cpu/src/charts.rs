//! Bounded drawing packets for a single canvas, not a widget per sampled frame.
use crate::{
    selection::{add, labels, select, weight},
    Direction, Query, QueryRow, WeightType,
};
use aps_simpleperf_engine::simpleperf::{Sample, SimpleperfStream};
use std::{
    collections::BTreeMap,
    hash::{Hash, Hasher},
};

pub const MAX_CHART_BLOCKS: usize = 20_000;
pub const MAX_CHART_WIDTH_PX: f32 = 16_384.0;
pub const MAX_CHART_TEXT_BYTES: usize = 16 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ChartViewport {
    pub width_px: f32,
    pub row_height_px: f32,
    pub first_depth: usize,
    pub depth_count: usize,
    pub max_blocks: usize,
}
impl Default for ChartViewport {
    fn default() -> Self {
        Self {
            width_px: 1000.0,
            row_height_px: 20.0,
            first_depth: 0,
            depth_count: 64,
            max_blocks: 4096,
        }
    }
}
impl ChartViewport {
    pub fn validate(&self) -> Result<(), String> {
        if !self.width_px.is_finite() || self.width_px < 1.0 || self.width_px > MAX_CHART_WIDTH_PX {
            return Err("chart width must be finite and between 1 and 16384 pixels".into());
        }
        if !self.row_height_px.is_finite() || self.row_height_px < 1.0 || self.row_height_px > 128.0
        {
            return Err("chart row height must be finite and between 1 and 128 pixels".into());
        }
        if self.first_depth >= 512
            || self.depth_count == 0
            || self.depth_count > 512
            || self
                .first_depth
                .checked_add(self.depth_count)
                .is_none_or(|end| end > 512)
        {
            return Err("chart depth window must be within 0..512 and nonempty".into());
        }
        if self.max_blocks == 0 || self.max_blocks > MAX_CHART_BLOCKS {
            return Err(format!(
                "chart output limit must be between 1 and {MAX_CHART_BLOCKS}"
            ));
        }
        Ok(())
    }
    fn visible(&self, depth: usize) -> bool {
        depth >= self.first_depth && depth < self.first_depth + self.depth_count
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct TimelineViewport {
    pub chart: ChartViewport,
    pub start_nanos: u64,
    pub end_nanos: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ChartBlockKind {
    Function,
    /// A one-pixel display marker at a sampled timestamp, NOT an execution span.
    SamplePoint,
    /// Multiple sources share a display pixel. No exact function is asserted.
    PixelAggregate,
}

#[derive(Debug, Clone, PartialEq)]
pub enum ChartDomain {
    /// Normalized weight range, not elapsed time. Useful for horizontal zoom.
    Weight {
        start_fraction: f64,
        end_fraction: f64,
        weight_type: WeightType,
    },
    /// Original Simpleperf sample timestamps. No cross-artifact clock mapping
    /// and no continuous duration between samples is inferred.
    SamplePoints { start_nanos: u64, end_nanos: u64 },
}

#[derive(Debug, Clone, PartialEq)]
pub struct ChartBlock {
    /// Exact function IDs are stable; pixel aggregate IDs are frame-local.
    pub id: String,
    pub label: String,
    pub resource: String,
    pub depth: usize,
    pub x: f32,
    pub y: f32,
    pub width: f32,
    pub height: f32,
    pub inclusive_weight: u64,
    pub self_weight: u64,
    pub sample_count: u64,
    pub kind: ChartBlockKind,
    pub source_count: usize,
    pub first_timestamp_nanos: Option<u64>,
    pub last_timestamp_nanos: Option<u64>,
    /// None means not applicable or aggregated across multiple threads.
    pub thread_id: Option<i32>,
    pub event_type: Option<String>,
}

#[derive(Debug, Clone)]
pub struct ChartFrame {
    pub blocks: Vec<ChartBlock>,
    pub width_px: f32,
    pub height_px: f32,
    pub first_depth: usize,
    pub row_height_px: f32,
    pub total_depth: usize,
    pub truncated: bool,
    /// Source blocks omitted by the output cap (not total offscreen blocks).
    pub omitted_blocks: usize,
    pub domain: ChartDomain,
    index: BTreeMap<usize, Vec<HitEntry>>,
}
#[derive(Debug, Clone)]
struct HitEntry {
    index: usize,
    maximum_end: f32,
}

impl ChartFrame {
    /// Half-open rectangles, indexed by depth then binary-searched by x.
    /// On the pixel-quantization fringe, exact blocks win over aggregate bins.
    pub fn hit_test(&self, x: f32, y: f32) -> Option<&ChartBlock> {
        if !x.is_finite()
            || !y.is_finite()
            || x < 0.0
            || y < 0.0
            || x >= self.width_px
            || y >= self.height_px
        {
            return None;
        }
        let depth = (y / self.row_height_px).floor() as usize + self.first_depth;
        let entries = self.index.get(&depth)?;
        let end = entries.partition_point(|entry| self.blocks[entry.index].x <= x);
        let mut found: Option<&ChartBlock> = None;
        for entry in entries[..end].iter().rev() {
            if entry.maximum_end <= x {
                break;
            }
            let block = &self.blocks[entry.index];
            if x < block.x + block.width
                && found.is_none_or(|current| {
                    current.kind == ChartBlockKind::PixelAggregate
                        && block.kind != ChartBlockKind::PixelAggregate
                })
            {
                found = Some(block);
            }
        }
        found
    }
    /// Selection details are the exact packet painted by this frame. Aggregate
    /// selections expose aggregate facts rather than fabricated source IDs.
    pub fn selected(&self, id: &str) -> Option<&ChartBlock> {
        self.blocks.iter().find(|block| block.id == id)
    }
}

struct Builder {
    viewport: ChartViewport,
    blocks: Vec<ChartBlock>,
    pixels: BTreeMap<(usize, u32, Option<i32>), ChartBlock>,
    omitted: usize,
    text_bytes: usize,
}
impl Builder {
    fn new(viewport: ChartViewport) -> Self {
        Self {
            viewport,
            blocks: Vec::new(),
            pixels: BTreeMap::new(),
            omitted: 0,
            text_bytes: 0,
        }
    }
    fn full(&self) -> bool {
        self.blocks.len() + self.pixels.len() >= self.viewport.max_blocks
    }
    fn reserve_text(&mut self, id: &str, label: &str, resource: &str, event: Option<&str>) -> bool {
        // Padding covers aggregate label/id growth without unbounded strings.
        let needed = 256usize
            .saturating_add(id.len())
            .saturating_add(label.len())
            .saturating_add(resource.len())
            .saturating_add(event.map_or(0, str::len));
        if needed > MAX_CHART_TEXT_BYTES.saturating_sub(self.text_bytes) {
            return false;
        }
        self.text_bytes += needed;
        true
    }
    fn add_timing(&mut self, block: ChartBlock) -> Result<(), String> {
        if self.full() || !self.reserve_text(&block.id, &block.label, &block.resource, None) {
            self.omitted += 1;
            return Ok(());
        }
        self.blocks.push(block);
        Ok(())
    }

    fn add_function(&mut self, row: &QueryRow, x: f32, width: f32) -> Result<(), String> {
        if width <= 0.0 {
            return Ok(());
        }
        if width < 1.0 {
            let pixel = x.floor() as u32;
            if let Some(block) = self.pixels.get_mut(&(row.depth, pixel, None)) {
                add(&mut block.inclusive_weight, row.inclusive_weight)?;
                add(&mut block.self_weight, row.self_weight)?;
                add(&mut block.sample_count, row.sample_count)?;
                block.source_count += 1;
                block.label = format!("{} subpixel functions", block.source_count);
            } else if self.full()
                || !self.reserve_text("flame-pixel", "subpixel functions", "", None)
            {
                self.omitted += 1;
            } else {
                self.pixels.insert(
                    (row.depth, pixel, None),
                    ChartBlock {
                        id: format!("flame-pixel:{}:{pixel}", row.depth),
                        label: "1 subpixel function".into(),
                        resource: String::new(),
                        depth: row.depth,
                        x: pixel as f32,
                        y: self.y(row.depth),
                        width: 1.0f32.min(self.viewport.width_px - pixel as f32),
                        height: self.viewport.row_height_px,
                        inclusive_weight: row.inclusive_weight,
                        self_weight: row.self_weight,
                        sample_count: row.sample_count,
                        kind: ChartBlockKind::PixelAggregate,
                        source_count: 1,
                        first_timestamp_nanos: None,
                        last_timestamp_nanos: None,
                        thread_id: None,
                        event_type: None,
                    },
                );
            }
        } else if self.full() || !self.reserve_text(&row.id, &row.name, &row.resource, None) {
            self.omitted += 1;
        } else {
            self.blocks.push(ChartBlock {
                id: row.id.clone(),
                label: row.name.clone(),
                resource: row.resource.clone(),
                depth: row.depth,
                x,
                y: self.y(row.depth),
                width,
                height: self.viewport.row_height_px,
                inclusive_weight: row.inclusive_weight,
                self_weight: row.self_weight,
                sample_count: row.sample_count,
                kind: ChartBlockKind::Function,
                source_count: 1,
                first_timestamp_nanos: None,
                last_timestamp_nanos: None,
                thread_id: None,
                event_type: None,
            });
        }
        Ok(())
    }
    fn y(&self, depth: usize) -> f32 {
        (depth - self.viewport.first_depth) as f32 * self.viewport.row_height_px
    }
    fn finish(mut self, total_depth: usize, domain: ChartDomain) -> ChartFrame {
        self.blocks.extend(self.pixels.into_values());
        self.blocks.sort_by(|a, b| {
            a.depth
                .cmp(&b.depth)
                .then_with(|| a.x.total_cmp(&b.x))
                .then_with(|| a.id.cmp(&b.id))
        });
        let mut index: BTreeMap<usize, Vec<HitEntry>> = BTreeMap::new();
        for (position, block) in self.blocks.iter().enumerate() {
            let entries = index.entry(block.depth).or_default();
            let previous = entries.last().map_or(0.0, |entry| entry.maximum_end);
            entries.push(HitEntry {
                index: position,
                maximum_end: previous.max(block.x + block.width),
            });
        }
        let visible_depths = total_depth
            .saturating_sub(self.viewport.first_depth)
            .min(self.viewport.depth_count);
        ChartFrame {
            blocks: self.blocks,
            width_px: self.viewport.width_px,
            height_px: visible_depths as f32 * self.viewport.row_height_px,
            first_depth: self.viewport.first_depth,
            row_height_px: self.viewport.row_height_px,
            total_depth,
            truncated: self.omitted != 0,
            omitted_blocks: self.omitted,
            domain,
            index,
        }
    }
}

/// Preorder rows are laid out within their parent's inclusive weight. Self
/// weight remains an unfilled gap, rather than inflating child proportions.
pub(crate) fn flame(
    rows: &[QueryRow],
    query: &Query,
    viewport: ChartViewport,
    start: f64,
    end: f64,
) -> Result<ChartFrame, String> {
    viewport.validate()?;
    if !start.is_finite() || !end.is_finite() || start < 0.0 || end > 1.0 || start >= end {
        return Err("flame weight range must satisfy 0 <= start < end <= 1".into());
    }
    let mut builder = Builder::new(viewport);
    let total_depth = rows.iter().map(|row| row.depth + 1).max().unwrap_or(0);
    let total_weight: f64 = rows
        .iter()
        .filter(|row| row.depth == 0)
        .map(|row| row.inclusive_weight as f64)
        .sum();
    // Each depth owns the next free child position in normalized weight space.
    let mut next_children = vec![0.0f64; total_depth + 1];
    let mut root_next = 0.0;
    for row in rows {
        if row.depth >= 512 {
            return Err("flame depth exceeds 512".into());
        }
        let width = if total_weight > 0.0 {
            row.inclusive_weight as f64 / total_weight
        } else {
            0.0
        };
        let left = if row.depth == 0 {
            let x = root_next;
            root_next += width;
            x
        } else {
            let x = next_children[row.depth - 1];
            next_children[row.depth - 1] += width;
            x
        };
        next_children[row.depth] = left;
        if !viewport.visible(row.depth) {
            continue;
        }
        let visible_left = left.max(start);
        let visible_right = (left + width).min(end);
        if visible_left >= visible_right {
            continue;
        }
        let x = ((visible_left - start) / (end - start) * f64::from(viewport.width_px)) as f32;
        let pixels =
            ((visible_right - visible_left) / (end - start) * f64::from(viewport.width_px)) as f32;
        builder.add_function(row, x, pixels.min(viewport.width_px - x))?;
    }
    Ok(builder.finish(
        total_depth,
        ChartDomain::Weight {
            start_fraction: start,
            end_fraction: end,
            weight_type: query.weight_type,
        },
    ))
}

/// Lays out arbitrary, already-transformed preorder rows with the same bounded
/// geometry, caps and domain as `CpuSession::flame_graph_in_range`, without a
/// session or stream. Filters, direction and pagination are already reflected
/// in the supplied rows; only `weight_type` is needed for the frame domain.
pub fn flame_frame_from_rows(
    rows: &[QueryRow],
    weight_type: WeightType,
    viewport: ChartViewport,
    start_fraction: f64,
    end_fraction: f64,
) -> Result<ChartFrame, String> {
    viewport.validate()?;
    if !start_fraction.is_finite()
        || !end_fraction.is_finite()
        || start_fraction < 0.0
        || end_fraction > 1.0
        || start_fraction >= end_fraction
    {
        return Err("flame weight range must satisfy 0 <= start < end <= 1".into());
    }
    let query = Query {
        weight_type,
        ..Query::default()
    };
    flame(rows, &query, viewport, start_fraction, end_fraction)
}

/// Stack-chart timing rows for one thread. Unlike the timeline pixel packet,
/// these blocks span real sample intervals so adjacent samples of the same call
/// node become one time-based box, matching Firefox Profiler's Stack Chart.
pub(crate) fn stack_chart(
    stream: &SimpleperfStream,
    query: &Query,
    viewport: TimelineViewport,
) -> Result<ChartFrame, String> {
    viewport.chart.validate()?;
    if viewport.start_nanos >= viewport.end_nanos {
        return Err("timeline requires start_nanos < end_nanos".into());
    }
    let selected = select(stream, query)?;
    let mut counts = BTreeMap::<i32, usize>::new();
    for (_, sample) in &selected.samples {
        *counts.entry(sample.thread_id).or_default() += 1;
    }
    let thread_id = query.thread_ids.iter().next().copied().or_else(|| {
        counts
            .iter()
            .max_by(|left, right| left.1.cmp(right.1).then_with(|| right.0.cmp(left.0)))
            .map(|(id, _)| *id)
    });
    let Some(thread_id) = thread_id else {
        return Ok(Builder::new(viewport.chart).finish(
            0,
            ChartDomain::SamplePoints {
                start_nanos: viewport.start_nanos,
                end_nanos: viewport.end_nanos,
            },
        ));
    };
    let mut samples: Vec<(usize, &Sample)> = selected
        .samples
        .iter()
        .filter(|(_, sample)| sample.thread_id == thread_id && !sample.callchain.is_empty())
        .map(|(ordinal, sample)| (*ordinal, *sample))
        .collect();
    samples.sort_by_key(|(ordinal, sample)| (sample.time, *ordinal));
    if samples.is_empty() {
        return Ok(Builder::new(viewport.chart).finish(
            0,
            ChartDomain::SamplePoints {
                start_nanos: viewport.start_nanos,
                end_nanos: viewport.end_nanos,
            },
        ));
    }
    let total_depth = samples
        .iter()
        .map(|(_, sample)| sample.callchain.len())
        .max()
        .unwrap_or(0)
        .min(viewport.chart.depth_count)
        .min(512);
    let mut deltas: Vec<u64> = samples
        .windows(2)
        .map(|pair| pair[1].1.time.saturating_sub(pair[0].1.time))
        .filter(|delta| *delta > 0)
        .collect();
    deltas.sort_unstable();
    let interval = deltas.get(deltas.len() / 2).copied().unwrap_or_else(|| {
        (viewport.end_nanos - viewport.start_nanos).max(1) / samples.len() as u64
    });
    let span = (viewport.end_nanos - viewport.start_nanos) as f64;
    let width_px = viewport.chart.width_px;
    let mut hashes: Vec<Vec<u64>> = Vec::with_capacity(samples.len());
    for (_, sample) in &samples {
        let mut path_hashes = Vec::with_capacity(sample.callchain.len().min(total_depth));
        let mut hash = 0u64;
        let mut push_hash = |frame: &aps_simpleperf_engine::simpleperf::Frame| {
            let mut hasher = std::collections::hash_map::DefaultHasher::new();
            frame.file_id.hash(&mut hasher);
            frame.symbol_id.hash(&mut hasher);
            frame.virtual_address.hash(&mut hasher);
            frame.execution_type.hash(&mut hasher);
            hash = hash.rotate_left(7) ^ hasher.finish();
            path_hashes.push(hash);
        };
        match query.direction {
            Direction::Forward => sample
                .callchain
                .iter()
                .rev()
                .take(total_depth)
                .for_each(&mut push_hash),
            Direction::Inverted => sample
                .callchain
                .iter()
                .take(total_depth)
                .for_each(&mut push_hash),
        }
        hashes.push(path_hashes);
    }
    let mut builder = Builder::new(viewport.chart);
    for depth in 0..total_depth {
        let mut open: Option<OpenTiming> = None;
        for (index, (_, sample)) in samples.iter().enumerate() {
            let end = samples
                .get(index + 1)
                .map_or_else(|| sample.time.saturating_add(interval), |next| next.1.time);
            let source = match query.direction {
                Direction::Forward => sample.callchain.len().checked_sub(depth + 1),
                Direction::Inverted => (depth < sample.callchain.len()).then_some(depth),
            };
            let Some(source) = source else {
                if let Some(current) = open.take() {
                    current.commit(&mut builder, depth, thread_id, viewport, width_px, span)?;
                }
                continue;
            };
            let key = hashes[index][depth];
            let frame = &sample.callchain[source];
            let (label, resource) = labels(&selected.files, frame);
            match &mut open {
                Some(current) if current.key == key => {
                    current.end = end;
                    add(&mut current.weight, weight(sample, query.weight_type))?;
                    current.samples += 1;
                }
                _ => {
                    if let Some(current) = open.take() {
                        current.commit(&mut builder, depth, thread_id, viewport, width_px, span)?;
                    }
                    open = Some(OpenTiming {
                        key,
                        start: sample.time,
                        end,
                        label,
                        resource,
                        weight: weight(sample, query.weight_type),
                        samples: 1,
                        leaf: source == 0,
                    });
                }
            }
        }
        if let Some(current) = open.take() {
            current.commit(&mut builder, depth, thread_id, viewport, width_px, span)?;
        }
    }
    Ok(builder.finish(
        total_depth,
        ChartDomain::SamplePoints {
            start_nanos: viewport.start_nanos,
            end_nanos: viewport.end_nanos,
        },
    ))
}

struct OpenTiming {
    key: u64,
    start: u64,
    end: u64,
    label: String,
    resource: String,
    weight: u64,
    samples: u64,
    leaf: bool,
}

impl OpenTiming {
    fn commit(
        self,
        builder: &mut Builder,
        depth: usize,
        thread_id: i32,
        viewport: TimelineViewport,
        width_px: f32,
        span: f64,
    ) -> Result<(), String> {
        let start = self.start.max(viewport.start_nanos);
        let end = self.end.min(viewport.end_nanos);
        if start >= end {
            return Ok(());
        }
        let x = ((start - viewport.start_nanos) as f64 / span) as f32 * width_px;
        let width = (((end - start) as f64 / span) as f32 * width_px).max(1.0);
        let y = builder.y(depth);
        builder.add_timing(ChartBlock {
            id: format!(
                "stack-v2:{}:{depth}:{}:{:x}",
                viewport.start_nanos, start, self.key
            ),
            label: self.label,
            resource: self.resource,
            depth,
            x,
            y,
            width,
            height: viewport.chart.row_height_px,
            inclusive_weight: self.weight,
            self_weight: if self.leaf { self.weight } else { 0 },
            sample_count: self.samples,
            kind: ChartBlockKind::Function,
            source_count: 1,
            first_timestamp_nanos: Some(start),
            last_timestamp_nanos: Some(end),
            thread_id: Some(thread_id),
            event_type: None,
        })
    }
}

pub(crate) fn stack(
    stream: &SimpleperfStream,
    query: &Query,
    viewport: TimelineViewport,
    separate_threads: bool,
) -> Result<ChartFrame, String> {
    viewport.chart.validate()?;
    if viewport.start_nanos >= viewport.end_nanos {
        return Err("timeline requires start_nanos < end_nanos".into());
    }
    let selected = select(stream, query)?;
    let mut builder = Builder::new(viewport.chart);
    let total_depth = selected
        .samples
        .iter()
        .map(|(_, sample)| sample.callchain.len())
        .max()
        .unwrap_or(0);
    let span = (viewport.end_nanos - viewport.start_nanos) as f64;
    for (ordinal, sample) in &selected.samples {
        if sample.time < viewport.start_nanos || sample.time >= viewport.end_nanos {
            continue;
        }
        if sample.callchain.len() > 512 {
            return Err("stack depth exceeds 512".into());
        }
        let x = (((sample.time - viewport.start_nanos) as f64 / span)
            * f64::from(viewport.chart.width_px))
        .floor() as u32;
        let value = weight(sample, query.weight_type);
        for depth in 0..sample.callchain.len() {
            if !viewport.chart.visible(depth) {
                continue;
            }
            let source = match query.direction {
                Direction::Forward => sample.callchain.len() - depth - 1,
                Direction::Inverted => depth,
            };
            let (label, resource) = labels(&selected.files, &sample.callchain[source]);
            let id = format!(
                "aps-sample-point-v1:{}:{ordinal}:{depth}",
                query.direction.as_str()
            );
            let key = (depth, x, separate_threads.then_some(sample.thread_id));
            if let Some(block) = builder.pixels.get_mut(&key) {
                add(&mut block.inclusive_weight, value)?;
                if source == 0 {
                    add(&mut block.self_weight, value)?;
                }
                add(&mut block.sample_count, 1)?;
                block.source_count += 1;
                block.kind = ChartBlockKind::PixelAggregate;
                block.id = if separate_threads {
                    format!("stack-thread-pixel:{}:{depth}:{x}", sample.thread_id)
                } else {
                    format!("stack-pixel:{depth}:{x}")
                };
                block.label = format!("{} sample points", block.source_count);
                block.resource.clear();
                block.first_timestamp_nanos =
                    Some(block.first_timestamp_nanos.unwrap().min(sample.time));
                block.last_timestamp_nanos =
                    Some(block.last_timestamp_nanos.unwrap().max(sample.time));
                if block.thread_id != Some(sample.thread_id) {
                    block.thread_id = None;
                }
                if block.event_type.as_ref() != selected.events.get(&sample.event_type_id) {
                    block.event_type = None;
                }
            } else if builder.full()
                || !builder.reserve_text(
                    &id,
                    &label,
                    &resource,
                    selected
                        .events
                        .get(&sample.event_type_id)
                        .map(String::as_str),
                )
            {
                builder.omitted += 1;
            } else {
                builder.pixels.insert(
                    key,
                    ChartBlock {
                        id,
                        label,
                        resource,
                        depth,
                        x: x as f32,
                        y: builder.y(depth),
                        width: 1.0f32.min(viewport.chart.width_px - x as f32),
                        height: viewport.chart.row_height_px,
                        inclusive_weight: value,
                        self_weight: if source == 0 { value } else { 0 },
                        sample_count: 1,
                        kind: ChartBlockKind::SamplePoint,
                        source_count: 1,
                        first_timestamp_nanos: Some(sample.time),
                        last_timestamp_nanos: Some(sample.time),
                        thread_id: Some(sample.thread_id),
                        event_type: selected.events.get(&sample.event_type_id).cloned(),
                    },
                );
            }
        }
    }
    Ok(builder.finish(
        total_depth,
        ChartDomain::SamplePoints {
            start_nanos: viewport.start_nanos,
            end_nanos: viewport.end_nanos,
        },
    ))
}
