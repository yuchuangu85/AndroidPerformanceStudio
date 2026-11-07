//! Read-only, in-memory CPU sessions for SIMPLEPERF protobuf exports.
//!
//! An input byte budget limits encoded input, not peak memory. Decoded samples
//! and current complete query projections remain in memory until it closes.
//! This adapter does not read Linux `perf.data`, invoke ADB, or persist sessions.

mod charts;
mod export;
mod selection;
mod top;
mod transforms;

pub use charts::{
    flame_frame_from_rows, ChartBlock, ChartBlockKind, ChartDomain, ChartFrame, ChartViewport,
    TimelineViewport, MAX_CHART_BLOCKS, MAX_CHART_TEXT_BYTES, MAX_CHART_WIDTH_PX,
};
pub use export::{
    MAX_CSV_BYTES, MAX_FIREFOX_BYTES, MAX_FOLDED_BYTES, MAX_HTML_BYTES, MAX_HTML_CALL_TREE_ROWS,
    MAX_HTML_TOP_FUNCTIONS, MAX_PPROF_BYTES, MAX_REPORT_CALL_TREE_ROWS, MAX_REPORT_JSON_BYTES,
    MAX_REPORT_TOP_FUNCTIONS, MAX_SESSION_PACKAGE_BYTES,
};
pub use top::{TopFunction, TopFunctionsPage, TopSort};
pub use transforms::{TransformSet, MAX_TRANSFORM_NAMES, MAX_TRANSFORM_NAME_BYTES};

use aps_simpleperf_engine::{
    prepare,
    simpleperf::{parse_reader_with_limit, Record, SimpleperfStream, MAX_STREAM_BYTES},
    PreparedQuery,
};
use std::collections::{BTreeMap, BTreeSet};
use std::fs::File;
use std::io::{BufReader, Read};
use std::path::{Path, PathBuf};

pub use aps_simpleperf_engine::{
    Direction, ProfileSummary, Query, QueryPage, QueryRow, WeightType, MAX_PAGE_SIZE,
};

pub const DEFAULT_INPUT_LIMIT: usize = 128 * 1024 * 1024;
/// Hard row cap for one whole-tree expansion request. Sources larger than this
/// remain cache-only; the caller asks for a bounded window instead.
pub const MAX_FULL_TREE_ROWS: usize = 200_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ThreadInfo {
    pub id: i32,
    pub name: String,
}

/// The entire prepared preorder call-tree projection, or the caller's first
/// `max_rows` rows when that projection is larger. Truncation is explicit so a
/// partial tree is never mistaken for the complete one.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FullCallTree {
    pub rows: Vec<QueryRow>,
    pub truncated: bool,
}

/// Owns decoded records, one current call-tree projection and one current top-
/// functions projection. Changing a page does not parse or aggregate again.
#[derive(Debug)]
pub struct CpuSession {
    path: PathBuf,
    stream: SimpleperfStream,
    summary: ProfileSummary,
    threads: Vec<ThreadInfo>,
    events: Vec<String>,
    prepared: Option<(Query, PreparedQuery)>,
    top_prepared: Option<(Query, Vec<TopFunction>)>,
    input_limit: usize,
}

impl CpuSession {
    pub fn open(path: impl AsRef<Path>) -> Result<Self, String> {
        Self::open_with_limit(path, DEFAULT_INPUT_LIMIT)
    }

    pub fn open_with_limit(path: impl AsRef<Path>, limit: usize) -> Result<Self, String> {
        if limit == 0 || limit > MAX_STREAM_BYTES {
            return Err(format!(
                "input limit must be between 1 and {MAX_STREAM_BYTES} bytes"
            ));
        }
        let path = path.as_ref();
        let file = File::open(path).map_err(|error| format!("open CPU profile: {error}"))?;
        let metadata = file
            .metadata()
            .map_err(|error| format!("stat CPU profile: {error}"))?;
        if !metadata.is_file() {
            return Err("CPU profile must be a regular file".into());
        }
        if metadata.len() > limit as u64 {
            return Err(format!("CPU profile exceeds input limit {limit} bytes"));
        }
        let stream = Self::read_profile(file, limit)?;
        let (summary, threads, events) = catalog(&stream)?;
        Ok(Self {
            path: path.to_path_buf(),
            stream,
            summary,
            threads,
            events,
            prepared: None,
            top_prepared: None,
            input_limit: limit,
        })
    }

    fn read_profile(file: File, limit: usize) -> Result<SimpleperfStream, String> {
        let mut reader = BufReader::new(file);
        let mut header = [0u8; 8];
        reader
            .read_exact(&mut header)
            .map_err(|error| format!("read CPU profile header: {error}"))?;
        if &header == b"PERFILE2" || &header == b"2ELIFREP" {
            return Err("perf.data is not supported here; convert with a compatible Simpleperf report-sample --protobuf --show-callchain command".into());
        }
        // Replay the small sniffed prefix without rereading or mapping the file.
        let mut replay = std::io::Cursor::new(header).chain(reader);
        parse_reader_with_limit(&mut replay, limit).map_err(|error| error.to_string())
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn summary(&self) -> &ProfileSummary {
        &self.summary
    }

    /// Includes both declared threads and IDs seen only in samples.
    pub fn threads(&self) -> &[ThreadInfo] {
        &self.threads
    }

    /// Event names follow the engine's final-metadata catalog and `event#ID`
    /// fallback rules. The names are passed verbatim to `Query::event_types`.
    pub fn events(&self) -> &[String] {
        &self.events
    }

    pub fn query(&mut self, query: &Query) -> Result<QueryPage, String> {
        self.projection(query)?.page(query.offset, query.limit)
    }

    /// Returns the complete prepared projection, not a page: `query.offset` and
    /// `query.limit` do not truncate it. `max_rows` is validated against the
    /// hard cap; a larger projection is truncated rather than failing. Filters,
    /// weight type and direction still come from `query`.
    pub fn full_call_tree(
        &mut self,
        query: &Query,
        max_rows: usize,
    ) -> Result<FullCallTree, String> {
        if max_rows == 0 || max_rows > MAX_FULL_TREE_ROWS {
            return Err(format!(
                "full call tree row limit must be between 1 and {MAX_FULL_TREE_ROWS}"
            ));
        }
        let rows = self.projection(query)?.rows();
        let truncated = rows.len() > max_rows;
        Ok(FullCallTree {
            rows: rows.iter().take(max_rows).cloned().collect(),
            truncated,
        })
    }

    fn projection(&mut self, query: &Query) -> Result<&PreparedQuery, String> {
        query.validate()?;
        let key = projection_key(query);
        if self
            .prepared
            .as_ref()
            .is_none_or(|(current, _)| current != &key)
        {
            // Do not replace the previous successful projection on failure.
            let projection = prepare(&self.stream, &key)?;
            self.prepared = Some((key, projection));
        }
        Ok(&self.prepared.as_ref().expect("projection initialized").1)
    }

    pub fn top_functions(
        &mut self,
        query: &Query,
        offset: usize,
        limit: usize,
    ) -> Result<TopFunctionsPage, String> {
        Query {
            offset,
            limit,
            ..query.clone()
        }
        .validate()?;
        let rows = self.top_projection(query)?;
        Ok(TopFunctionsPage {
            total: rows.len(),
            offset,
            rows: rows.iter().skip(offset).take(limit).cloned().collect(),
            weight_type: query.weight_type,
        })
    }

    /// Sorts the complete filtered list before applying `offset`/`limit`, so
    /// paging and sorting compose instead of sorting one page.
    pub fn top_functions_sorted(
        &mut self,
        query: &Query,
        sort: TopSort,
        descending: bool,
        offset: usize,
        limit: usize,
    ) -> Result<TopFunctionsPage, String> {
        Query {
            offset,
            limit,
            ..query.clone()
        }
        .validate()?;
        let mut rows = self.top_projection(query)?.to_vec();
        top::sort(&mut rows, sort, descending);
        Ok(TopFunctionsPage {
            total: rows.len(),
            offset,
            rows: rows.into_iter().skip(offset).take(limit).collect(),
            weight_type: query.weight_type,
        })
    }

    fn top_projection(&mut self, query: &Query) -> Result<&[TopFunction], String> {
        query.validate()?;
        let key = projection_key(query);
        if self
            .top_prepared
            .as_ref()
            .is_none_or(|(current, _)| current != &key)
        {
            let rows = top::aggregate(&self.stream, &key)?;
            self.top_prepared = Some((key, rows));
        }
        Ok(&self
            .top_prepared
            .as_ref()
            .expect("top projection initialized")
            .1)
    }

    pub fn flame_graph(
        &mut self,
        query: &Query,
        viewport: ChartViewport,
    ) -> Result<ChartFrame, String> {
        self.flame_graph_in_range(query, viewport, 0.0, 1.0)
    }

    /// Horizontal flame zoom is a normalized weight range, never a time range.
    pub fn flame_graph_in_range(
        &mut self,
        query: &Query,
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
        charts::flame(
            self.projection(query)?.rows(),
            query,
            viewport,
            start_fraction,
            end_fraction,
        )
    }

    /// Paints sampled timestamp points only. Marker width is a display pixel;
    /// no sample interval, scheduling span, or execution duration is invented.
    pub fn stack_chart(
        &self,
        query: &Query,
        viewport: TimelineViewport,
    ) -> Result<ChartFrame, String> {
        charts::stack(&self.stream, query, viewport)
    }

    /// Returns a bounded UTF-8 CSV, without writing or overwriting any file.
    pub fn export_call_tree_csv(&mut self, query: &Query) -> Result<String, String> {
        export::call_tree_csv(self.projection(query)?.rows(), query)
    }

    pub fn export_top_functions_csv(&mut self, query: &Query) -> Result<String, String> {
        export::top_functions_csv(self.top_projection(query)?, query)
    }

    /// Returns bounded folded-stack text (one `stack weight` line per
    /// self-weighting call-tree row) without writing or overwriting any file.
    pub fn export_folded_stacks(&mut self, query: &Query) -> Result<String, String> {
        export::folded_stacks(self.projection(query)?.rows())
    }

    /// Returns the bounded `aps-cpu-report-v1` JSON report without writing or
    /// overwriting any file.
    pub fn export_report_json(&mut self, query: &Query) -> Result<String, String> {
        self.prepare_exports(query)?;
        let summary = &self.summary;
        let top = self
            .top_prepared
            .as_ref()
            .map_or(&[][..], |(_, rows)| rows.as_slice());
        let tree = self
            .prepared
            .as_ref()
            .map_or(&[][..], |(_, projection)| projection.rows());
        export::report_json(summary, query, top, tree)
    }

    /// Returns a self-contained bounded HTML report without writing or
    /// overwriting any file.
    pub fn export_html_report(&mut self, query: &Query) -> Result<String, String> {
        self.prepare_exports(query)?;
        let summary = &self.summary;
        let top = self
            .top_prepared
            .as_ref()
            .map_or(&[][..], |(_, rows)| rows.as_slice());
        let tree = self
            .prepared
            .as_ref()
            .map_or(&[][..], |(_, projection)| projection.rows());
        export::html_report(summary, top, tree)
    }

    fn prepare_exports(&mut self, query: &Query) -> Result<(), String> {
        self.top_projection(query)?;
        self.projection(query)?;
        Ok(())
    }

    /// Returns a bounded gzip-compressed Firefox Profiler v24 profile for the
    /// complete parsed session. The export never writes or overwrites files.
    pub fn export_firefox_profile(&self) -> Result<Vec<u8>, String> {
        export::firefox_profile(&self.stream)
    }

    /// Returns a bounded gzip-compressed pprof protobuf for the prepared
    /// preorder call tree: one sample per self-weighting row, with its
    /// root-to-leaf location chain and self weight as the sample value. Pprof
    /// stores location ids leaf-first, so the emitted chain is reversed. The
    /// export never writes or overwrites files.
    pub fn export_pprof(&mut self, query: &Query) -> Result<Vec<u8>, String> {
        let rows = self.projection(query)?.rows();
        export::pprof(rows)
    }

    /// Returns a bounded, hand-written store-only zip package containing
    /// `manifest.json`, the raw profile bytes (`profile.simpleperf.pb`, re-read
    /// read-only within the session input limit) and `report.json`. The export
    /// never writes or overwrites files.
    pub fn export_session_package(&mut self, query: &Query) -> Result<Vec<u8>, String> {
        self.prepare_exports(query)?;
        let profile = export::read_profile_bytes(&self.path, self.input_limit)?;
        let summary = &self.summary;
        let top = self
            .top_prepared
            .as_ref()
            .map_or(&[][..], |(_, rows)| rows.as_slice());
        let tree = self
            .prepared
            .as_ref()
            .map_or(&[][..], |(_, projection)| projection.rows());
        export::session_package(summary, query, top, tree, &profile)
    }

    /// Releases the current aggregation while preserving parsed records.
    pub fn clear_projection(&mut self) {
        self.prepared = None;
        self.top_prepared = None;
    }
}

fn projection_key(query: &Query) -> Query {
    let mut key = query.clone();
    key.offset = 0;
    key.limit = MAX_PAGE_SIZE;
    key
}

fn catalog(
    stream: &SimpleperfStream,
) -> Result<(ProfileSummary, Vec<ThreadInfo>, Vec<String>), String> {
    let mut threads = BTreeMap::new();
    let mut events = BTreeMap::new();
    let mut files = BTreeSet::new();
    let mut summary = ProfileSummary {
        format_version: stream.version,
        record_count: stream.records.len(),
        sample_count: 0,
        lost_sample_count: 0,
        lost_event_count: 0,
        total_event_count: 0,
        first_timestamp_nanos: None,
        last_timestamp_nanos: None,
        file_count: 0,
        thread_count: 0,
        event_types: Vec::new(),
        trace_offcpu: false,
    };
    // Catalog records may follow samples. Final declarations win, like query().
    for record in &stream.records {
        match record {
            Record::Thread(thread) => {
                if let Ok(id) = i32::try_from(thread.thread_id) {
                    threads.insert(id, thread.name.clone());
                }
            }
            Record::File(file) => {
                files.insert(file.id);
            }
            Record::MetaInfo(meta) => {
                events = meta
                    .event_types
                    .iter()
                    .enumerate()
                    .map(|(id, name)| (id as u32, name.clone()))
                    .collect();
                summary.trace_offcpu = meta.trace_offcpu;
            }
            _ => {}
        }
    }
    for record in &stream.records {
        match record {
            Record::Sample(sample) => {
                summary.sample_count += 1;
                checked_add(&mut summary.total_event_count, sample.event_count)?;
                summary.first_timestamp_nanos = Some(
                    summary
                        .first_timestamp_nanos
                        .map_or(sample.time, |time| time.min(sample.time)),
                );
                summary.last_timestamp_nanos = Some(
                    summary
                        .last_timestamp_nanos
                        .map_or(sample.time, |time| time.max(sample.time)),
                );
                threads
                    .entry(sample.thread_id)
                    .or_insert_with(|| format!("thread#{}", sample.thread_id));
                events
                    .entry(sample.event_type_id)
                    .or_insert_with(|| format!("event#{}", sample.event_type_id));
            }
            Record::Lost {
                sample_count,
                lost_count,
            } => {
                checked_add(&mut summary.lost_sample_count, *sample_count)?;
                checked_add(&mut summary.lost_event_count, *lost_count)?;
            }
            _ => {}
        }
    }
    let threads: Vec<_> = threads
        .into_iter()
        .map(|(id, name)| ThreadInfo { id, name })
        .collect();
    let mut unique_names = BTreeSet::new();
    let events: Vec<_> = events
        .into_values()
        .filter(|name| unique_names.insert(name.clone()))
        .collect();
    summary.file_count = files.len();
    summary.thread_count = threads.len();
    summary.event_types = events.clone();
    Ok((summary, threads, events))
}

fn checked_add(target: &mut u64, value: u64) -> Result<(), String> {
    *target = target
        .checked_add(value)
        .ok_or("CPU summary metric exceeds u64")?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_simpleperf_engine::simpleperf::{Frame, Sample};

    #[test]
    fn unchanged_filters_page_without_reaggregating_records() {
        let stream = SimpleperfStream {
            version: 1,
            records: vec![Record::Sample(Sample {
                time: 1,
                thread_id: 1,
                event_count: 1,
                event_type_id: 0,
                callchain: vec![Frame {
                    virtual_address: 1,
                    file_id: 1,
                    symbol_id: 0,
                    execution_type: 0,
                }],
            })],
        };
        let (summary, threads, events) = catalog(&stream).unwrap();
        let mut session = CpuSession {
            path: PathBuf::from("unused"),
            stream,
            summary,
            threads,
            events,
            prepared: None,
            top_prepared: None,
            input_limit: DEFAULT_INPUT_LIMIT,
        };
        let first = session.query(&Query::default()).unwrap();
        // Deliberately invalidate only the backing stream to distinguish cached
        // pagination from an accidental second aggregation. Production keeps it
        // private and immutable for the full session.
        session.stream.records.clear();
        assert_eq!(
            session
                .query(&Query {
                    offset: 0,
                    limit: 1,
                    ..Query::default()
                })
                .unwrap(),
            first
        );
        assert!(session
            .query(&Query {
                direction: Direction::Inverted,
                ..Query::default()
            })
            .unwrap()
            .rows
            .is_empty());
    }
}
