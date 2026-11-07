use crate::{
    digest_text, BuildIdentityMatch, PerformanceEvidenceId, ResolutionCandidate,
    ResolutionCandidateId, ResolutionConfidence, SourceFile, SourceLocation, SourceRange,
    SourceRepository, SourceResolutionEvidence, SourceSnapshot, SourceSnapshotId, SourceSymbol,
    SourceSymbolKind,
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
    let files = repository.files(snapshot_id)?;
    let symbols = repository.symbols(snapshot_id)?;
    match evidence {
        SourceResolutionEvidence::TypeName { id, qualified_name } => symbols
            .iter()
            .filter(|symbol| {
                symbol.kind == SourceSymbolKind::Type && symbol.qualified_name == *qualified_name
            })
            .map(|symbol| {
                symbol_candidate(
                    &snapshot,
                    &files,
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
            symbols
                .iter()
                .filter(|symbol| {
                    symbol.kind == SourceSymbolKind::Resource
                        && symbol.qualified_name == qualified_name
                })
                .map(|symbol| {
                    symbol_candidate(
                        &snapshot,
                        &files,
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
            for symbol in symbols.iter().filter(|symbol| {
                matches!(
                    symbol.kind,
                    SourceSymbolKind::Function | SourceSymbolKind::Method
                ) && after_last_dot(&symbol.qualified_name) == method_name
            }) {
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
                    &snapshot,
                    &files,
                    symbol,
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
                if let Some(file) = files
                    .iter()
                    .find(|file| file.relative_path.ends_with(suffix))
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
            symbols
                .iter()
                .filter(|symbol| {
                    symbol.kind == SourceSymbolKind::NativeSymbol
                        && after_last_native_separator(&symbol.qualified_name)
                            == after_last_native_separator(symbol_name)
                })
                .map(|symbol| {
                    symbol_candidate(
                        &snapshot,
                        &files,
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
            for file in files
                .iter()
                .filter(|file| file_name_of(&file.relative_path) == file_name)
            {
                let package_matched = symbols
                    .iter()
                    .filter(|symbol| symbol.relative_path == file.relative_path)
                    .flat_map(|symbol| package_prefixes(&symbol.qualified_name))
                    .any(|prefix| {
                        java_string_hash_code(&prefix).wrapping_abs() as u32 == *package_hash
                    });
                if package_matched {
                    candidates.push(file_candidate(
                        &snapshot,
                        file,
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

fn symbol_candidate(
    snapshot: &SourceSnapshot,
    files: &[SourceFile],
    symbol: &SourceSymbol,
    evidence_id: PerformanceEvidenceId,
    confidence: ResolutionConfidence,
    reason: &str,
) -> Result<ResolutionCandidate, String> {
    let file = files
        .iter()
        .find(|file| file.relative_path == symbol.relative_path)
        .ok_or_else(|| {
            format!(
                "source symbol references a file missing from snapshot: {}",
                symbol.relative_path
            )
        })?;
    Ok(file_candidate(
        snapshot,
        file,
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

fn file_name_of(relative_path: &str) -> &str {
    relative_path.rsplit('/').next().unwrap_or(relative_path)
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
