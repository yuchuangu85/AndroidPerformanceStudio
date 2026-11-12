//! Timestamp samples for thread tracks. Decimation retains original ordinals;
//! selecting a function always queries the original stream, not a display bin.
use crate::{
    export::firefox_category,
    selection::{labels, select},
    CpuSession, Direction, Query, TimelineViewport,
};
use aps_simpleperf_engine::sample_leaf_node_id;
use aps_simpleperf_engine::simpleperf::Record;
use std::collections::BTreeMap;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TimelineSample {
    pub ordinal: usize,
    pub time_nanos: u64,
    pub stack_depth: usize,
    /// Firefox Profiler category index for the sampled leaf frame. The bundled
    /// exporter writes the same index to its frame table, so the native track
    /// and the official viewer color a sample identically.
    pub category: u8,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TimelineThread {
    pub thread_id: i32,
    pub samples: Vec<TimelineSample>,
    pub total_samples: usize,
    pub max_stack_depth: usize,
    /// Median positive timestamp delta, only for the visual width of markers.
    /// It is not a measured execution or scheduling duration.
    pub interval_nanos: Option<u64>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SampleTimeline {
    pub start_nanos: u64,
    pub end_nanos: u64,
    pub threads: Vec<TimelineThread>,
    pub truncated: bool,
    pub omitted_samples: usize,
}

impl CpuSession {
    /// Display ordinals whose original stacks contain the clicked call node.
    /// The output is limited to the already bounded timeline drawing packet.
    pub fn timeline_sample_highlights(
        &self,
        timeline: &SampleTimeline,
        ordinal: usize,
        direction: Direction,
    ) -> Vec<usize> {
        let Some(Record::Sample(selected)) = self.stream.records.get(ordinal) else {
            return Vec::new();
        };
        timeline
            .threads
            .iter()
            .flat_map(|thread| &thread.samples)
            .filter_map(|point| {
                let Record::Sample(sample) = self.stream.records.get(point.ordinal)? else {
                    return None;
                };
                let matches = match direction {
                    Direction::Forward => sample.callchain.ends_with(&selected.callchain),
                    Direction::Inverted => sample.callchain.first() == selected.callchain.first(),
                };
                matches.then_some(point.ordinal)
            })
            .collect()
    }

    /// At most four representatives per logical pixel per thread, bounded by
    /// the chart output limit. The first sample in a quarter-pixel bin wins,
    /// matching the official viewer's minimum sample spacing.
    pub fn sample_timeline(
        &self,
        query: &Query,
        viewport: TimelineViewport,
    ) -> Result<SampleTimeline, String> {
        viewport.chart.validate()?;
        if viewport.start_nanos >= viewport.end_nanos {
            return Err("timeline requires start_nanos < end_nanos".into());
        }
        let selected = select(&self.stream, query)?;
        let mut threads = BTreeMap::<i32, Vec<TimelineSample>>::new();
        for (ordinal, sample) in selected.samples {
            let category = sample.callchain.first().map_or(6, |frame| {
                let (symbol, resource) = labels(&selected.files, frame);
                firefox_category(&symbol, &resource)
            });
            if sample.time < viewport.start_nanos || sample.time >= viewport.end_nanos {
                continue;
            }
            if sample.callchain.len() > 512 {
                return Err("stack depth exceeds 512".into());
            }
            if sample.callchain.is_empty() {
                continue;
            }
            threads
                .entry(sample.thread_id)
                .or_default()
                .push(TimelineSample {
                    ordinal,
                    time_nanos: sample.time,
                    stack_depth: sample.callchain.len(),
                    category,
                });
        }
        let span = (viewport.end_nanos - viewport.start_nanos) as f64;
        let mut remaining = viewport.chart.max_blocks;
        let mut omitted_samples = 0usize;
        let mut output = Vec::with_capacity(threads.len());
        for (thread_id, mut samples) in threads {
            samples.sort_by_key(|sample| (sample.time_nanos, sample.ordinal));
            let total_samples = samples.len();
            let max_stack_depth = samples
                .iter()
                .map(|sample| sample.stack_depth)
                .max()
                .unwrap_or(1);
            let mut deltas: Vec<_> = samples
                .windows(2)
                .map(|pair| pair[1].time_nanos - pair[0].time_nanos)
                .filter(|delta| *delta > 0)
                .collect();
            deltas.sort_unstable();
            let interval_nanos = deltas.get(deltas.len() / 2).copied();
            let mut bins = BTreeMap::<u32, TimelineSample>::new();
            for sample in samples {
                let pixel = (((sample.time_nanos - viewport.start_nanos) as f64 / span)
                    * f64::from(viewport.chart.width_px)
                    * 4.) as u32;
                // Firefox keeps the first sample inside a 0.25 px window for the
                // sample strip; the deepest stack is no longer a display concern.
                bins.entry(pixel).or_insert(sample);
            }
            let samples: Vec<_> = bins.into_values().take(remaining).collect();
            remaining -= samples.len();
            omitted_samples += total_samples - samples.len();
            output.push(TimelineThread {
                thread_id,
                samples,
                total_samples,
                max_stack_depth,
                interval_nanos,
            });
        }
        Ok(SampleTimeline {
            start_nanos: viewport.start_nanos,
            end_nanos: viewport.end_nanos,
            threads: output,
            truncated: omitted_samples > 0,
            omitted_samples,
        })
    }

    /// Resolve the nearest real sample in this thread, including samples that
    /// were decimated out of the drawing packet. Timestamp ties use input order.
    pub fn sample_at_time(
        &self,
        query: &Query,
        thread_id: i32,
        time_nanos: u64,
    ) -> Result<Option<usize>, String> {
        Ok(select(&self.stream, query)?
            .samples
            .into_iter()
            .filter(|(_, sample)| sample.thread_id == thread_id && !sample.callchain.is_empty())
            .min_by_key(|(ordinal, sample)| (sample.time.abs_diff(time_nanos), *ordinal))
            .map(|(ordinal, _)| ordinal))
    }

    /// Resolve the original sampled leaf using numeric ancestry, not its label.
    pub fn sample_call_node_id(
        &self,
        query: &Query,
        ordinal: usize,
    ) -> Result<Option<String>, String> {
        Ok(select(&self.stream, query)?
            .samples
            .into_iter()
            .find(|(candidate, _)| *candidate == ordinal)
            .and_then(|(_, sample)| sample_leaf_node_id(sample, query.direction)))
    }
}
