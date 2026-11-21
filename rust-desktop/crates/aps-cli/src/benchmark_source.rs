//! Host-only selection and revalidation of a Benchmark artifact pair. Comparison
//! receives immutable byte slices, never a path from a report or a tool call.
use crate::analysis::{FrozenAnalysis, benchmark::Comparison};
use aps_benchmark::MAX_IMPORT_BYTES;
use aps_contracts::SourceDigest;
use std::{
    fs::File,
    io::Read,
    path::{Path, PathBuf},
};

const BAD_SOURCE: &str = "E_BENCHMARK_INPUT: select a nonempty regular local report within 64 MiB";
const CHANGED: &str = "E_BENCHMARK_SOURCE_CHANGED: reopen the selected report pair";

struct BoundFile {
    path: PathBuf,
    digest: SourceDigest,
    bytes: u64,
}

impl BoundFile {
    fn read(path: &Path) -> Result<Vec<u8>, String> {
        if path.to_string_lossy().contains("://") {
            return Err(BAD_SOURCE.into());
        }
        let regular = |meta: std::fs::Metadata| {
            meta.is_file() && (1..=MAX_IMPORT_BYTES).contains(&meta.len())
        };
        if !path.symlink_metadata().is_ok_and(regular) {
            return Err(BAD_SOURCE.into());
        }
        let file = File::open(path).map_err(|_| BAD_SOURCE)?;
        if !file.metadata().is_ok_and(regular) {
            return Err(BAD_SOURCE.into());
        }
        let mut bytes = Vec::new();
        file.take(MAX_IMPORT_BYTES + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| BAD_SOURCE)?;
        if bytes.is_empty() || bytes.len() as u64 > MAX_IMPORT_BYTES {
            return Err(BAD_SOURCE.into());
        }
        Ok(bytes)
    }

    fn new(path: &Path, bytes: &[u8], digest: SourceDigest) -> Self {
        Self {
            path: path.to_owned(),
            digest,
            bytes: bytes.len() as u64,
        }
    }

    fn verify(&self) -> bool {
        self.path
            .symlink_metadata()
            .is_ok_and(|meta| meta.is_file() && meta.len() == self.bytes)
            && SourceDigest::hash_file(&self.path, self.bytes).ok() == Some(self.digest)
    }
}

pub(crate) struct BoundPair {
    baseline: BoundFile,
    current: BoundFile,
    comparison: Comparison,
    invalidated: bool,
}

impl BoundPair {
    pub(crate) fn open(
        baseline: &Path,
        current: &Path,
        threshold: Option<f64>,
    ) -> Result<Self, String> {
        let before = BoundFile::read(baseline)?;
        let after = BoundFile::read(current)?;
        let comparison = Comparison::from_bytes(&before, &after, threshold)?;
        let mut pair = Self {
            baseline: BoundFile::new(baseline, &before, comparison.baseline_digest()),
            current: BoundFile::new(current, &after, comparison.current_digest()),
            comparison,
            invalidated: false,
        };
        pair.verify()?;
        Ok(pair)
    }

    pub(crate) fn current_analysis(&self) -> Result<FrozenAnalysis, String> {
        FrozenAnalysis::from_benchmark_projection(
            self.comparison.current_summary().clone(),
            self.current.digest,
            self.current.bytes,
        )
    }

    pub(crate) fn verify(&mut self) -> Result<(), &'static str> {
        if !self.invalidated {
            self.invalidated = !self.current.verify() || !self.baseline.verify();
        }
        if self.invalidated {
            Err(CHANGED)
        } else {
            Ok(())
        }
    }

    pub(crate) fn page(
        &self,
        offset: usize,
        limit: usize,
    ) -> Result<serde_json::Value, &'static str> {
        self.comparison.page(offset, limit)
    }

    pub(crate) fn exit_code(&self) -> i32 {
        self.comparison.exit_code()
    }
}

pub(crate) fn threshold(text: &str) -> Result<f64, &'static str> {
    text.parse::<f64>()
        .ok()
        .filter(|n| n.is_finite() && (0.0..=100.0).contains(n))
        .ok_or("E_BENCHMARK_POLICY: threshold percent must be between 0 and 100")
}
