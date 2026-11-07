use crate::{model::*, validate_relative_source_path};
use aps_contracts::SourceDigest;

/// Frozen identity of a bounded set of individually verified file contents.
/// It is NOT an atomic Git tree snapshot or a retained content copy. Files may
/// become unavailable; a later viewer read must match their recorded hash.
#[derive(Debug)]
pub struct SourceSnapshot {
    id: SourceSnapshotId,
    workspace_id: SourceWorkspaceId,
    files: Vec<SourceFile>,
    symbols: Vec<SourceSymbol>,
    coverage: IndexCoverage,
    limits: SourceLimits,
}
impl SourceSnapshot {
    pub(crate) fn create(
        workspace_id: SourceWorkspaceId,
        files: Vec<SourceFile>,
        symbols: Vec<SourceSymbol>,
        coverage: IndexCoverage,
        limits: SourceLimits,
    ) -> Result<Self, String> {
        let mut manifest = Vec::new();
        manifest.extend_from_slice(b"aps-local-file-manifest-v1\0");
        manifest.extend_from_slice(workspace_id.0.as_bytes());
        manifest.extend_from_slice(&INDEX_VERSION.to_be_bytes());
        for file in &files {
            manifest.extend_from_slice(&(file.relative_path.len() as u32).to_be_bytes());
            manifest.extend_from_slice(file.relative_path.as_bytes());
            manifest.extend_from_slice(file.content_hash.as_bytes());
            manifest.extend_from_slice(&file.byte_length.to_be_bytes());
        }
        // Fixed-width fields keep snapshot identity independent of Debug output.
        for count in [
            coverage.visited_entries,
            coverage.indexed_files,
            coverage.indexed_symbols,
            coverage.excluded_entries,
            coverage.omitted_files,
            coverage.unreadable_entries,
            limits.max_files,
            limits.max_entries,
            limits.max_depth,
            limits.max_symbols,
        ] {
            manifest.extend_from_slice(&(count as u64).to_be_bytes());
        }
        for count in [
            coverage.indexed_bytes,
            limits.max_file_bytes,
            limits.max_total_bytes,
        ] {
            manifest.extend_from_slice(&count.to_be_bytes());
        }
        for flag in [
            coverage.file_budget_exhausted,
            coverage.byte_budget_exhausted,
            coverage.entry_budget_exhausted,
            coverage.depth_budget_exhausted,
            coverage.symbol_budget_exhausted,
        ] {
            manifest.push(u8::from(flag));
        }

        let id = SourceSnapshotId(SourceDigest::hash_reader(
            manifest.as_slice(),
            manifest.len() as u64,
        )?);
        Ok(Self {
            id,
            workspace_id,
            files,
            symbols,
            coverage,
            limits,
        })
    }
    pub fn id(&self) -> SourceSnapshotId {
        self.id
    }
    pub fn workspace_id(&self) -> SourceWorkspaceId {
        self.workspace_id
    }
    pub fn coverage(&self) -> &IndexCoverage {
        &self.coverage
    }
    pub fn limits(&self) -> SourceLimits {
        self.limits
    }
    pub fn symbols(&self) -> &[SourceSymbol] {
        &self.symbols
    }
    pub fn file_count(&self) -> usize {
        self.files.len()
    }
    pub fn files(&self, offset: usize, limit: usize) -> Result<SourceFilesPage, String> {
        if limit > MAX_FILE_PAGE || offset.checked_add(limit).is_none() {
            return Err("source file page exceeds supported bounds".into());
        }
        Ok(SourceFilesPage {
            total: self.files.len(),
            offset,
            rows: self
                .files
                .iter()
                .skip(offset)
                .take(limit)
                .cloned()
                .collect(),
        })
    }
    /// Look up immutable manifest metadata independently of the current UI page.
    pub fn file(&self, relative: &str) -> Result<&SourceFile, String> {
        self.files
            .binary_search_by(|file| file.relative_path.as_str().cmp(relative))
            .ok()
            .and_then(|index| self.files.get(index))
            .ok_or_else(|| "source path is not present in the bound snapshot manifest".into())
    }
    pub fn location(
        &self,
        relative: &str,
        start_line: usize,
        end_line: usize,
    ) -> Result<SourceLocation, String> {
        validate_relative_source_path(relative)?;
        let file = self.file(relative)?;
        let value = SourceLocation {
            workspace_id: self.workspace_id,
            snapshot_id: self.id,
            relative_path: relative.into(),
            content_hash: file.content_hash,
            start_line,
            end_line,
        };
        self.validate_location(&value)?;
        Ok(value)
    }
    pub fn validate_location(&self, location: &SourceLocation) -> Result<(), String> {
        if location.workspace_id != self.workspace_id || location.snapshot_id != self.id {
            return Err("source location workspace/snapshot identity mismatch".into());
        }
        validate_relative_source_path(&location.relative_path)?;
        let file = self.file(&location.relative_path)?;
        if location.content_hash != file.content_hash {
            return Err("source location content hash mismatch".into());
        }
        if location.start_line == 0
            || location.start_line > location.end_line
            || location.end_line > file.line_count
        {
            return Err("source location line range is outside the snapshot file".into());
        }
        Ok(())
    }
    /// Only index-owned files/lines produce candidates. Heuristic symbols and
    /// missing runtime/build mapping cap confidence below Exact in all cases.
    pub fn resolve(&self, query: &ResolutionQuery) -> Result<Vec<ResolutionCandidate>, String> {
        query.validate()?;
        let mut candidates = Vec::new();
        if let Some(symbol) = &query.symbol {
            for item in &self.symbols {
                if item.name != *symbol && item.qualified_name != *symbol {
                    continue;
                }
                let file = self.file(&item.relative_path)?;
                if !file_matches(file, query) {
                    continue;
                }
                let location = self.location(&item.relative_path, item.line, item.line)?;
                let candidate = self.candidate(
                    location,
                    ResolutionConfidence::Probable,
                    vec![
                        format!("Indexed declaration hint matched {}", item.qualified_name),
                        "Build identity and compiler-accurate symbol mapping are not verified"
                            .into(),
                    ],
                    query,
                )?;
                if !candidates
                    .iter()
                    .any(|existing: &ResolutionCandidate| existing.location == candidate.location)
                {
                    candidates.push(candidate);
                }
                if candidates.len() == query.limit {
                    break;
                }
            }
        } else {
            for file in &self.files {
                if !file_matches(file, query) {
                    continue;
                }
                candidates.push(self.candidate(
                    self.location(&file.relative_path, 1, 1)?,
                    ResolutionConfidence::Weak,
                    vec![
                            "Source filename matched; no verified runtime-to-source line evidence"
                                .into(),
                        ],
                    query,
                )?);
                if candidates.len() == query.limit {
                    break;
                }
            }
        }
        Ok(candidates)
    }
    fn candidate(
        &self,
        location: SourceLocation,
        confidence: ResolutionConfidence,
        reasons: Vec<String>,
        query: &ResolutionQuery,
    ) -> Result<ResolutionCandidate, String> {
        let identity = format!(
            "aps-candidate-v1\0{}\0{}\0{}\0{}\0{}\0{:?}\0{:?}",
            self.id.to_hex(),
            location.relative_path,
            location.content_hash.to_hex(),
            location.start_line,
            location.end_line,
            confidence,
            query
        );
        let id = ResolutionCandidateId(SourceDigest::hash_reader(
            identity.as_bytes(),
            identity.len() as u64,
        )?);
        Ok(ResolutionCandidate {
            id,
            location,
            confidence,
            reasons,
            index_version: INDEX_VERSION,
            index_complete: self.coverage.structurally_complete(),
            evidence: query.clone(),
        })
    }
    /// Re-resolves the candidate's private evidence against this immutable
    /// manifest/index and rejects altered IDs, paths, ranges, hashes or claims.
    pub fn validate_candidate(&self, candidate: &ResolutionCandidate) -> Result<(), String> {
        self.validate_location(&candidate.location)?;
        if self
            .resolve(&candidate.evidence)?
            .iter()
            .any(|expected| expected == candidate)
        {
            Ok(())
        } else {
            Err("candidate was not produced by the bound deterministic source index".into())
        }
    }
}
fn file_matches(file: &SourceFile, query: &ResolutionQuery) -> bool {
    query
        .file_name
        .as_ref()
        .is_none_or(|name| file.relative_path.rsplit('/').next() == Some(name.as_str()))
        && query
            .module
            .as_ref()
            .is_none_or(|module| file.module == *module)
}
