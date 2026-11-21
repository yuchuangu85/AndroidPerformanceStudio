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

pub const MAX_TIMELINE_BUCKETS: usize = 128;
pub const MAX_TIMELINE_THREADS: usize = 100;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SampleCountBucket {
    pub start_ns: u64,
    /// Inclusive so the full domain, including u64::MAX, is representable.
    pub last_ns_inclusive: u64,
    pub sample_count: usize,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ThreadSampleCounts {
    pub thread_id: i32,
    pub sample_count: usize,
    pub first_ns: u64,
    pub last_ns: u64,
    /// Index in the shared summary buckets. Earliest wins when counts tie.
    pub peak_bucket_index: usize,
    pub peak_bucket_sample_count: usize,
}

#[derive(Debug, PartialEq, Eq)]
pub struct TimelineSummary {
    pub sample_count: usize,
    pub empty_stack_sample_count: usize,
    pub first_ns: Option<u64>,
    pub last_ns: Option<u64>,
    pub buckets: Vec<SampleCountBucket>,
    pub thread_count: usize,
    pub threads: Vec<ThreadSampleCounts>,
}

impl CpuSession {
    /// Exact counts of selected sample records (not scheduler time or chart
    /// representatives). The largest thread groups are retained; all matching
    /// samples, including those without a stack, contribute to the histogram.
    pub fn timeline_summary(
        &self,
        event: Option<&str>,
        start: Option<u64>,
        end: Option<u64>,
        bucket_count: usize,
        thread_limit: usize,
    ) -> Result<TimelineSummary, String> {
        if !(1..=MAX_TIMELINE_BUCKETS).contains(&bucket_count)
            || !(1..=MAX_TIMELINE_THREADS).contains(&thread_limit)
        {
            return Err(
                "timeline bucketCount or threadLimit is outside the supported range".into(),
            );
        }
        let query = Query {
            event_types: event.into_iter().map(str::to_owned).collect(),
            start,
            end,
            ..Query::default()
        };
        let selected = select(&self.stream, &query)?;
        let mut groups = BTreeMap::new();
        let mut empty_stack_sample_count = 0;
        for (_, sample) in &selected.samples {
            let group = groups
                .entry(sample.thread_id)
                .or_insert(ThreadSampleCounts {
                    thread_id: sample.thread_id,
                    sample_count: 0,
                    first_ns: sample.time,
                    last_ns: sample.time,
                    peak_bucket_index: 0,
                    peak_bucket_sample_count: 0,
                });
            group.sample_count += 1;
            group.first_ns = group.first_ns.min(sample.time);
            group.last_ns = group.last_ns.max(sample.time);
            empty_stack_sample_count += usize::from(sample.callchain.is_empty());
        }
        let first_ns = groups.values().map(|group| group.first_ns).min();
        let last_ns = groups.values().map(|group| group.last_ns).max();
        let thread_count = groups.len();
        let mut threads: Vec<_> = groups.into_values().collect();
        threads
            .sort_unstable_by_key(|group| (std::cmp::Reverse(group.sample_count), group.thread_id));
        threads.truncate(thread_limit);

        let sample_count = selected.samples.len();
        let mut buckets = Vec::new();
        // Compute the exclusive upper bound in u128, so a sample at u64::MAX
        // is counted even when the caller omits endNs. Empty profiles don't
        // supply an implicit start/end, but fully specified empty ranges do.
        let lower = start.or(first_ns).map(u128::from);
        let upper = end
            .map(u128::from)
            .or_else(|| last_ns.map(|ns| u128::from(ns) + 1));
        if let Some((lower, upper)) = lower.zip(upper) {
            let span = upper - lower;
            let n = span.min(bucket_count as u128);
            // No zero-width buckets. Using ceil for boundaries matches floor
            // for assignment even when the number of ns isn't divisible by n.
            let boundary = |i: u128| lower + (i * span).div_ceil(n);
            buckets = (0..n)
                .map(|i| SampleCountBucket {
                    start_ns: boundary(i) as u64,
                    last_ns_inclusive: (boundary(i + 1) - 1) as u64,
                    sample_count: 0,
                })
                .collect();
            // Only the returned threads need a peak. Bound this scratch space
            // by thread_limit * bucket_count, independent of input thread count.
            let slots: BTreeMap<_, _> = threads
                .iter()
                .enumerate()
                .map(|(i, thread)| (thread.thread_id, i))
                .collect();
            let mut counts = vec![0usize; threads.len() * buckets.len()];
            for (_, sample) in selected.samples {
                let index = ((u128::from(sample.time) - lower) * n / span) as usize;
                buckets[index].sample_count += 1;
                if let Some(&slot) = slots.get(&sample.thread_id) {
                    counts[slot * buckets.len() + index] += 1;
                }
            }
            for (thread, counts) in threads.iter_mut().zip(counts.chunks_exact(buckets.len())) {
                for (index, &count) in counts.iter().enumerate() {
                    if count > thread.peak_bucket_sample_count {
                        thread.peak_bucket_index = index;
                        thread.peak_bucket_sample_count = count;
                    }
                }
            }
        }
        Ok(TimelineSummary {
            sample_count,
            empty_stack_sample_count,
            first_ns,
            last_ns,
            buckets,
            thread_count,
            threads,
        })
    }
}

#[cfg(test)]
mod summary_tests {
    use super::*;
    use aps_simpleperf_engine::simpleperf::{Frame, Sample, SimpleperfStream};

    // Values are intentionally unordered; event counts must not affect density.
    fn session(samples: &[(u64, i32, u32, bool)]) -> CpuSession {
        let stream = SimpleperfStream {
            version: 1,
            records: samples
                .iter()
                .map(|&(time, thread_id, event_type_id, has_stack)| {
                    Record::Sample(Sample {
                        time,
                        thread_id,
                        event_type_id,
                        event_count: 17,
                        callchain: has_stack
                            .then_some(Frame {
                                file_id: 0,
                                symbol_id: 0,
                                virtual_address: 0,
                                execution_type: 0,
                            })
                            .into_iter()
                            .collect(),
                    })
                })
                .collect(),
        };
        CpuSession::from_stream(stream, Default::default(), crate::DEFAULT_INPUT_LIMIT).unwrap()
    }

    #[test]
    fn counts_include_coincident_empty_stacks_but_not_wrong_event_or_end_boundary() {
        let session = session(&[
            (5, 2, 0, false),
            (1, 1, 0, true),
            (8, 3, 0, true),
            (7, 2, 0, true),
            (3, 1, 0, false),
            (3, 1, 0, true),
            (2, 2, 1, true),
            (4, 3, 0, false),
        ]);
        let report = session
            .timeline_summary(Some("event#0"), Some(1), Some(8), 3, 2)
            .unwrap();
        assert_eq!((report.first_ns, report.last_ns), (Some(1), Some(7)));
        assert_eq!(
            (
                report.sample_count,
                report.empty_stack_sample_count,
                report.thread_count
            ),
            (6, 3, 3)
        );
        assert_eq!(
            report
                .buckets
                .iter()
                .map(|b| (b.start_ns, b.last_ns_inclusive, b.sample_count))
                .collect::<Vec<_>>(),
            [(1, 3, 3), (4, 5, 2), (6, 7, 1)]
        );
        assert_eq!(
            report
                .threads
                .iter()
                .map(|t| (t.thread_id, t.sample_count, t.first_ns, t.last_ns))
                .collect::<Vec<_>>(),
            [(1, 3, 1, 3), (2, 2, 5, 7)]
        );
        assert_eq!(
            report
                .threads
                .iter()
                .map(|t| (t.peak_bucket_index, t.peak_bucket_sample_count))
                .collect::<Vec<_>>(),
            [(0, 3), (1, 1)]
        );
    }

    #[test]
    fn spans_entire_u64_domain_and_breaks_equal_thread_counts_by_local_id() {
        let session = session(&[(u64::MAX, 2, 0, false), (0, 1, 0, false)]);
        let report = session.timeline_summary(None, None, None, 2, 1).unwrap();
        assert_eq!(report.threads[0].thread_id, 1);
        assert_eq!(report.buckets[0].last_ns_inclusive, i64::MAX as u64);
        assert_eq!(report.buckets[1].start_ns, 1u64 << 63);
        assert_eq!(report.buckets[1].last_ns_inclusive, u64::MAX);
        assert!(report.buckets.iter().all(|b| b.sample_count == 1));
        let last = session
            .timeline_summary(None, Some(u64::MAX), None, 128, 1)
            .unwrap();
        assert_eq!(last.buckets.len(), 1);
        assert_eq!(last.buckets[0].sample_count, 1);
    }

    #[test]
    fn empty_ranges_keep_null_observations_and_bounds_are_validated() {
        let session = session(&[]);
        let missing = session
            .timeline_summary(None, None, None, 128, 100)
            .unwrap();
        assert!(missing.buckets.is_empty());
        assert!(missing.threads.is_empty());
        assert_eq!((missing.first_ns, missing.last_ns), (None, None));
        let bounded = session
            .timeline_summary(None, Some(4), Some(6), 128, 100)
            .unwrap();
        assert_eq!(bounded.buckets.len(), 2);
        assert!(bounded.buckets.iter().all(|b| b.sample_count == 0));
        assert!(session
            .timeline_summary(None, Some(6), Some(6), 1, 1)
            .is_err());
        for (bins, threads) in [(0, 1), (129, 1), (1, 0), (1, 101)] {
            assert!(session
                .timeline_summary(None, None, None, bins, threads)
                .is_err());
        }
    }
}
