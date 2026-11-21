//! Bind exactly one source at startup and invalidate its evidence on replacement.
use super::{RpcError, follow_up};
use crate::{AnalyzeOptions, FrameScope, analysis::FrozenAnalysis, benchmark_source::BoundPair};
use aps_contracts::SourceDigest;
use aps_json::Value;
use std::path::{Path, PathBuf};

/// Parse and hash the selected source before discovery. Tools return only this
/// frozen projection, and any detected replacement permanently closes its read
/// authority. Reopening a different source requires restarting this process.
pub(super) struct Selected {
    path: PathBuf,
    analysis: FrozenAnalysis,
    invalidated: bool,
    benchmark_pair: Option<BoundPair>,
    remaining_queries: u8,
}

impl Selected {
    pub(super) fn open(path: &Path) -> Result<Self, String> {
        // URL support in the CLI must never imply download privileges here.
        if path.as_os_str().is_empty() || path.to_string_lossy().contains("://") {
            return Err("E_MCP_LOCAL_INPUT: bind a local artifact, not a URL".into());
        }
        if !path.symlink_metadata().is_ok_and(|meta| meta.is_file()) {
            return Err("E_MCP_LOCAL_INPUT: bind a regular file directly".into());
        }
        let analysis = FrozenAnalysis::open(path, &AnalyzeOptions::default(), None)
            .map_err(|_| "E_MCP_INPUT: cannot analyze selected local artifact")?;
        let mut result = Self {
            path: path.into(),
            analysis,
            invalidated: false,
            benchmark_pair: None,
            remaining_queries: follow_up::MAX_QUERIES,
        };
        result.verify().map_err(|(_, message)| message)?;
        Ok(result)
    }

    pub(super) fn open_benchmark(
        baseline: &Path,
        current: &Path,
        threshold: Option<f64>,
    ) -> Result<Self, String> {
        let pair = BoundPair::open(baseline, current, threshold)?;
        let analysis = pair.current_analysis()?;
        Ok(Self {
            path: current.into(),
            analysis,
            invalidated: false,
            benchmark_pair: Some(pair),
            remaining_queries: follow_up::MAX_QUERIES,
        })
    }

    pub(super) fn has_benchmark_pair(&self) -> bool {
        self.benchmark_pair.is_some()
    }

    pub(super) fn benchmark_page(
        &mut self,
        offset: usize,
        limit: usize,
    ) -> Result<Value, RpcError> {
        if self.invalidated {
            return Err((-32001, "current input binding is no longer valid"));
        }
        let pair = self
            .benchmark_pair
            .as_mut()
            .ok_or((-32001, "no bound Benchmark pair"))?;
        pair.verify().map_err(|msg| (-32001, msg))?;
        pair.page(offset, limit).map_err(|msg| (-32602, msg))
    }

    pub(super) fn supports_frame_scope(&self) -> bool {
        self.analysis.supports_frame_scope()
    }

    pub(super) fn analyzer_kind(&self) -> Option<&str> {
        self.analysis.evidence().pointer("/evidence/kind")?.as_str()
    }

    /// Discovery is metadata-only. Every read of actual evidence rechecks the
    /// selected bytes; a tool cannot fetch or rebind to any other artifact.
    pub(super) fn evidence(&mut self, scope: Option<FrameScope>) -> Result<Value, RpcError> {
        self.verify()?;
        match scope {
            None => Ok(self.analysis.evidence().clone()),
            Some(scope) => self.analysis.scoped(scope).map_err(|_| {
                (
                    -32001,
                    "E_MCP_FRAME_SCOPE: requested Frame evidence unavailable",
                )
            }),
        }
    }

    pub(super) fn remaining_queries(&self) -> u8 {
        self.remaining_queries
    }

    pub(super) fn supports_startup_details(&self) -> bool {
        self.analysis.startup().is_some()
    }

    pub(super) fn supports_har_details(&self) -> bool {
        self.analysis.har().is_some()
    }

    pub(super) fn follow_up(
        &mut self,
        expected: SourceDigest,
        query: follow_up::Query<'_>,
    ) -> Result<Value, RpcError> {
        if self.invalidated || expected != self.analysis.digest() {
            return Err((-32001, "unavailable or mismatched source binding"));
        }
        // Charge before file verification or a page scan. A bad offset or a
        // nonexistent item is not a way to obtain unbounded work.
        self.remaining_queries = self
            .remaining_queries
            .checked_sub(1)
            .ok_or((-32002, "follow-up budget exhausted for this binding"))?;
        self.verify()?;
        query.project(&self.analysis, self.remaining_queries)
    }

    fn verify(&mut self) -> Result<(), RpcError> {
        if !self.invalidated {
            self.invalidated = !self
                .path
                .symlink_metadata()
                .is_ok_and(|meta| meta.is_file() && meta.len() == self.analysis.byte_length())
                || SourceDigest::hash_file(&self.path, self.analysis.byte_length()).ok()
                    != Some(self.analysis.digest());
        }
        if self.invalidated {
            return Err((
                -32001,
                "E_MCP_SOURCE_CHANGED: restart with an explicitly selected input",
            ));
        }
        Ok(())
    }
}
