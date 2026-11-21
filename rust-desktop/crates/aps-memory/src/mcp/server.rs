//! Owns the source-bound worker sessions and the validated A/B comparison cache.
use super::args::Options;
use super::comparison::{comparison_page, validate_deltas};
use aps_hprof_engine::cache::sha256_file;
use aps_memory::{MemorySession, SnapshotComparison, compare_snapshots};
use aps_platform::CancellationToken;
use serde_json::Value;

pub(super) struct McpServer {
    pub(super) current: MemorySession,
    baseline: Option<MemorySession>,
    comparison: Option<SnapshotComparison>,
}

impl McpServer {
    pub(super) fn open(options: &Options, cancel: &CancellationToken) -> Result<Self, String> {
        let digest = sha256_file(&options.hprof)?;
        let current = MemorySession::open(&options.worker, &options.hprof, &digest, cancel)
            .map_err(|error| error.to_string())?;
        let baseline = options
            .baseline
            .as_ref()
            .map(|path| {
                let digest = sha256_file(path)?;
                MemorySession::open(&options.worker, path, &digest, cancel)
                    .map_err(|error| error.to_string())
            })
            .transpose()?;
        Ok(Self {
            current,
            baseline,
            comparison: None,
        })
    }

    pub(super) fn snapshot_diff(
        &mut self,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<Value, String> {
        let result = (|| {
            let baseline = self
                .baseline
                .as_mut()
                .ok_or("Snapshot comparison requires --baseline-hprof at server startup")?;
            // Every page bypasses query caches, including a retry after failure.
            baseline
                .check_source(cancel)
                .map_err(|error| error.to_string())?;
            self.current
                .check_source(cancel)
                .map_err(|error| error.to_string())?;
            if self.comparison.is_none() {
                let before = baseline
                    .snapshot(cancel)
                    .map_err(|error| error.to_string())?;
                let after = self
                    .current
                    .snapshot(cancel)
                    .map_err(|error| error.to_string())?;
                let report = compare_snapshots(&before, &after, "A".into(), "B".into())
                    .map_err(|error| error.to_string())?;
                // A later page must not conceal an invalid delta elsewhere.
                for row in &report.rows {
                    validate_deltas(row)?;
                }
                // The first source may have changed while the second was read.
                baseline
                    .check_source(cancel)
                    .map_err(|error| error.to_string())?;
                self.current
                    .check_source(cancel)
                    .map_err(|error| error.to_string())?;
                self.comparison = Some(report);
            }
            comparison_page(
                self.comparison
                    .as_ref()
                    .ok_or("Comparison is unavailable")?,
                offset,
                limit,
            )
        })();
        if result.is_err() {
            self.comparison = None;
        }
        result
    }
}
