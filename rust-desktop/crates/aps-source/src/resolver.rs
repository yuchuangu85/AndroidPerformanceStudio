use crate::{
    digest_text, BuildIdentityMatch, PerformanceEvidenceId, ResolutionCandidate,
    ResolutionCandidateId, ResolutionConfidence, SourceFile, SourceLocation, SourceRange,
    SourceRepository, SourceResolutionEvidence, SourceSnapshot, SourceSnapshotId, SourceSymbol,
    SourceSymbolKind, SymbolNameSeparator, MAX_LOOKUP_CANDIDATES,
};
use std::collections::HashSet;

/// Compose IndexedSourceResolver: evidence is resolved against indexed
/// snapshots only. There is no AI path/line generation and no build-identity
/// verification in this desktop flow, so callers pass BuildIdentityMatch.
pub struct IndexedSourceResolver;

impl IndexedSourceResolver {
    #[allow(clippy::too_many_arguments)]
    pub fn resolve<R: SourceRepository + ?Sized>(
        &self,
        repository: &R,
        snapshot_ids: &[SourceSnapshotId],
        evidence: &[SourceResolutionEvidence],
        build_identity_match: BuildIdentityMatch,
        limit: usize,
    ) -> Result<Vec<ResolutionCandidate>, String> {
        let mut candidates: Vec<ResolutionCandidate> = Vec::new();
        let mut seen: HashSet<ResolutionCandidateId> = HashSet::new();
        if limit == 0 {
            return Ok(candidates);
        }
        for item in evidence {
            for snapshot_id in snapshot_ids {
                for candidate in resolve_for(repository, *snapshot_id, item)? {
                    if !seen.insert(candidate.id) {
                        continue;
                    }
                    candidates.push(apply_build_identity_ceiling(
                        candidate,
                        build_identity_match,
                    ));
                    if candidates.len() == limit {
                        return Ok(candidates);
                    }
                }
            }
        }
        Ok(candidates)
    }
}

fn resolve_for<R: SourceRepository + ?Sized>(
    repository: &R,
    snapshot_id: SourceSnapshotId,
    evidence: &SourceResolutionEvidence,
) -> Result<Vec<ResolutionCandidate>, String> {
    let Some(snapshot) = repository.snapshot(snapshot_id)? else {
        return Ok(Vec::new());
    };
    match evidence {
        SourceResolutionEvidence::TypeName { id, qualified_name } => repository
            .symbols_qualified(
                snapshot_id,
                SourceSymbolKind::Type,
                qualified_name,
                MAX_LOOKUP_CANDIDATES,
            )?
            .iter()
            .map(|symbol| {
                symbol_candidate(
                    repository,
                    &snapshot,
                    symbol,
                    *id,
                    ResolutionConfidence::Exact,
                    "Qualified type matched",
                )
            })
            .collect(),
        SourceResolutionEvidence::AndroidResource {
            id,
            resource_type,
            resource_name,
        } => {
            let qualified_name = format!("{resource_type}/{resource_name}");
            repository
                .symbols_qualified(
                    snapshot_id,
                    SourceSymbolKind::Resource,
                    &qualified_name,
                    MAX_LOOKUP_CANDIDATES,
                )?
                .iter()
                .map(|symbol| {
                    symbol_candidate(
                        repository,
                        &snapshot,
                        symbol,
                        *id,
                        ResolutionConfidence::Exact,
                        "Android resource matched",
                    )
                })
                .collect()
        }
        SourceResolutionEvidence::ManagedSymbol {
            id,
            class_name,
            method_name,
            signature,
            ..
        } => {
            let mut candidates = Vec::new();
            for symbol in repository.symbols_with_short_name(
                snapshot_id,
                &[SourceSymbolKind::Function, SourceSymbolKind::Method],
                method_name,
                SymbolNameSeparator::Dot,
                MAX_LOOKUP_CANDIDATES,
            )? {
                let class_matches = class_name.as_ref().is_none_or(|class_name| {
                    path_without_last_extension(&symbol.relative_path)
                        .ends_with(after_last_dot(class_name))
                });
                let signature_matches =
                    signature.is_some() && symbol.signature.as_ref() == signature.as_ref();
                let confidence = if class_matches && (signature.is_none() || signature_matches) {
                    ResolutionConfidence::Exact
                } else {
                    ResolutionConfidence::Probable
                };
                candidates.push(symbol_candidate(
                    repository,
                    &snapshot,
                    &symbol,
                    *id,
                    confidence,
                    if class_matches {
                        "Managed symbol matched"
                    } else {
                        "Method name matched"
                    },
                )?);
            }
            Ok(candidates)
        }
        SourceResolutionEvidence::NativeSymbol {
            id,
            symbol_name,
            source_path,
            source_line,
            ..
        } => {
            if let Some(source_path) = source_path {
                let suffix = source_path.strip_prefix('/').unwrap_or(source_path);
                if let Some(file) = repository
                    .files_with_suffix(snapshot_id, suffix, MAX_LOOKUP_CANDIDATES)?
                    .first()
                {
                    let confidence = if source_line.is_some() {
                        ResolutionConfidence::Exact
                    } else {
                        ResolutionConfidence::Probable
                    };
                    return Ok(vec![file_candidate(
                        &snapshot,
                        file,
                        *id,
                        confidence,
                        "Symbolizer source path matched",
                        *source_line,
                    )]);
                }
            }
            let short_name = after_last_native_separator(symbol_name);
            repository
                .symbols_with_short_name(
                    snapshot_id,
                    &[SourceSymbolKind::NativeSymbol],
                    short_name,
                    SymbolNameSeparator::DoubleColon,
                    MAX_LOOKUP_CANDIDATES,
                )?
                .iter()
                .map(|symbol| {
                    symbol_candidate(
                        repository,
                        &snapshot,
                        symbol,
                        *id,
                        ResolutionConfidence::Probable,
                        "Native symbol name matched",
                    )
                })
                .collect()
        }
        SourceResolutionEvidence::SourceFileLine {
            id,
            file_name,
            package_hash,
            line,
        } => {
            let mut candidates = Vec::new();
            for file in repository.files_named(snapshot_id, file_name, MAX_LOOKUP_CANDIDATES)? {
                let package_matched = repository
                    .symbols_in_file(snapshot_id, &file.relative_path, MAX_LOOKUP_CANDIDATES)?
                    .iter()
                    .flat_map(|symbol| package_prefixes(&symbol.qualified_name))
                    .any(|prefix| {
                        java_string_hash_code(&prefix).wrapping_abs() as u32 == *package_hash
                    });
                if package_matched {
                    candidates.push(file_candidate(
                        &snapshot,
                        &file,
                        *id,
                        ResolutionConfidence::Exact,
                        "Compose file and package hash matched",
                        Some(*line),
                    ));
                }
            }
            Ok(candidates)
        }
    }
}

fn symbol_candidate<R: SourceRepository + ?Sized>(
    repository: &R,
    snapshot: &SourceSnapshot,
    symbol: &SourceSymbol,
    evidence_id: PerformanceEvidenceId,
    confidence: ResolutionConfidence,
    reason: &str,
) -> Result<ResolutionCandidate, String> {
    let file = repository
        .file(snapshot.id, &symbol.relative_path)?
        .ok_or_else(|| {
            format!(
                "source symbol references a file missing from snapshot: {}",
                symbol.relative_path
            )
        })?;
    Ok(file_candidate(
        snapshot,
        &file,
        evidence_id,
        confidence,
        reason,
        Some(symbol.start_line),
    ))
}

fn file_candidate(
    snapshot: &SourceSnapshot,
    file: &SourceFile,
    evidence_id: PerformanceEvidenceId,
    confidence: ResolutionConfidence,
    reason: &str,
    line: Option<usize>,
) -> ResolutionCandidate {
    let identity = format!(
        "{}:{}:{}:{}",
        snapshot.id.to_hex(),
        file.relative_path,
        line.unwrap_or(0),
        evidence_id.to_hex()
    );
    ResolutionCandidate {
        id: ResolutionCandidateId::from_digest(digest_text(&identity)),
        evidence_id,
        location: SourceLocation {
            workspace_id: snapshot.workspace_id,
            snapshot_id: snapshot.id,
            relative_path: file.relative_path.clone(),
            range: line.map(|line| SourceRange {
                start_line: line,
                end_line: line,
            }),
            content_hash: file.content_hash,
        },
        confidence,
        reasons: vec![reason.to_owned()],
        index_version: snapshot.index_version,
        index_complete: snapshot.index_complete,
    }
}

fn apply_build_identity_ceiling(
    mut candidate: ResolutionCandidate,
    build_identity_match: BuildIdentityMatch,
) -> ResolutionCandidate {
    if build_identity_match == BuildIdentityMatch::Unverified
        && candidate.confidence == ResolutionConfidence::Exact
    {
        candidate.confidence = ResolutionConfidence::Probable;
        candidate
            .reasons
            .push("Build identity not verified".to_owned());
    }
    candidate
}

fn path_without_last_extension(relative_path: &str) -> &str {
    relative_path
        .rsplit_once('.')
        .map(|(prefix, _)| prefix)
        .unwrap_or(relative_path)
}

fn after_last_dot(value: &str) -> &str {
    value.rsplit('.').next().unwrap_or(value)
}

fn after_last_native_separator(value: &str) -> &str {
    value.rsplit("::").next().unwrap_or(value)
}

fn package_prefixes(qualified_name: &str) -> Vec<String> {
    let parts: Vec<&str> = qualified_name.split('.').collect();
    (1..parts.len())
        .map(|count| parts[..count].join("."))
        .collect()
}

/// Java/Kotlin String.hashCode over UTF-16 code units with wrapping i32 math.
pub fn java_string_hash_code(value: &str) -> i32 {
    let mut hash: i32 = 0;
    for unit in value.encode_utf16() {
        hash = hash.wrapping_mul(31).wrapping_add(i32::from(unit));
    }
    hash
}
