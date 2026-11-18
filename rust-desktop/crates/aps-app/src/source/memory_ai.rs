//! Explicitly granted, immutable and bounded source context for HPROF findings.

use super::{permissions, service};
use aps_ai::{AnalysisRequest, Evidence, SourceCandidate};
use aps_contracts::SourceDigest;
use aps_json::{Limits, Value};
use aps_source::{
    BuildIdentityMatch, ContentAddressedSourceCache, IndexedSourceResolver, MAX_SOURCE_FILE_BYTES,
    PerformanceEvidenceId, ResolutionCandidate, ResolutionCandidateId, ResolutionConfidence,
    SourceContentState, SourceFile, SourceLanguage, SourceLocation, SourceProviderConfig,
    SourceProviderKind, SourceRange, SourceRepository, SourceResolutionEvidence, SourceSnapshot,
    SourceSymbol, SourceSymbolKind, SourceWorkspace, SourceWorkspacePhase, digest_text,
};
use aps_storage::Storage;
use std::collections::{BTreeMap, BTreeSet};
use std::fs::File;

const MAX_WORKSPACES: usize = 4;
const MAX_TYPES: usize = 64;
const MAX_CANDIDATES: usize = 6;
const MAX_LINES: usize = 80;
const MAX_SNIPPET_BYTES: usize = 3072;
const MAX_CONTEXT_BYTES: u64 = 16 * 1024;
const MAX_EVIDENCE: usize = 100;
const SCOPE: &str = "HPROF heuristic leak candidates with explicitly granted, bounded immutable class-source excerpts. Counts overlap; retained sizes describe representative objects, not class totals. Source resolution is probable with unverified build identity; stale excerpts are snapshot-only. Missing chains and omitted findings remain unknown; source context does not prove a leak.";

pub(crate) struct MemorySourcePlan {
    paths: service::SourcePaths,
    workspaces: Vec<SourceWorkspace>,
}

#[derive(Clone)]
struct FrozenWorkspace {
    config: SourceProviderConfig,
    snapshot: SourceSnapshot,
}

#[derive(Clone)]
struct BoundCandidate {
    candidate: ResolutionCandidate,
    file: SourceFile,
    symbol: SourceSymbol,
    dto: SourceCandidate,
    binding: Evidence,
    span: SourceRange,
    state: SourceContentState,
}

/// Retain this guard for both prepare and execute; never fall back after an error.
#[derive(Clone)]
pub(crate) struct MemorySourceGuard {
    paths: service::SourcePaths,
    workspaces: Vec<FrozenWorkspace>,
    candidates: Vec<BoundCandidate>,
    expected_context: Vec<u8>,
}

struct TypeLookup {
    memory_id: String,
    memory_ids: Vec<String>,
    qualified_name: String,
    package: String,
    class_names: Vec<String>,
}

impl MemorySourcePlan {
    // The parent owns selection priority. Preserve its order and freeze its IDs.
    pub(super) fn new(paths: service::SourcePaths, workspaces: Vec<SourceWorkspace>) -> Self {
        let mut seen = BTreeSet::new();
        let workspaces = workspaces
            .into_iter()
            .filter(|workspace| {
                workspace.allow_ai_source_upload && eligible(workspace) && seen.insert(workspace.id)
            })
            .take(MAX_WORKSPACES)
            .collect();
        Self { paths, workspaces }
    }

    pub(crate) fn enrich(
        self,
        mut request: AnalysisRequest,
    ) -> Result<(AnalysisRequest, Option<MemorySourceGuard>), String> {
        if self.workspaces.is_empty() {
            return Ok((request, None));
        }
        let policy = load_policy(&self.paths)?;
        let allowed: Vec<_> = self
            .workspaces
            .iter()
            .filter(|workspace| policy.allowed(&workspace.id.to_hex()))
            .collect();
        if allowed.is_empty() {
            return Ok((request, None));
        }
        let storage = open(&self.paths)?;
        let mut frozen = Vec::new();
        for expected in allowed {
            let workspace = storage
                .workspace(expected.id)
                .map_err(|_| "E_MEMORY_SOURCE_DATABASE")?
                .ok_or("E_MEMORY_SOURCE_WORKSPACE_CHANGED")?;
            if !eligible(&workspace)
                || workspace.active_snapshot_id != expected.active_snapshot_id
                || workspace.config != expected.config
            {
                return Err("E_MEMORY_SOURCE_WORKSPACE_CHANGED".into());
            }
            let snapshot = storage
                .snapshot(workspace.active_snapshot_id.unwrap())
                .map_err(|_| "E_MEMORY_SOURCE_DATABASE")?
                .ok_or("E_MEMORY_SOURCE_SNAPSHOT_CHANGED")?;
            if snapshot.workspace_id != workspace.id {
                return Err("E_MEMORY_SOURCE_SNAPSHOT_CHANGED".into());
            }
            frozen.push(FrozenWorkspace {
                config: workspace.config,
                snapshot,
            });
        }
        if request.profiler != "memory"
            || !request.candidates.is_empty()
            || request.evidence.len() > MAX_EVIDENCE
            || !request
                .evidence
                .iter()
                .any(|item| item.kind == "memory-hprof-leak-findings")
        {
            return Err("E_MEMORY_SOURCE_REQUEST".into());
        }
        let lookups = type_lookups(&request);
        let snapshots: Vec<_> = frozen
            .iter()
            .map(|workspace| workspace.snapshot.id)
            .collect();
        let cache = cache(&self.paths)?;
        let mut candidates = Vec::new();
        let mut examined = 0;
        let mut seen = BTreeSet::new();
        for lookup in lookups {
            if examined == MAX_CANDIDATES
                || request.evidence.len() + candidates.len() == MAX_EVIDENCE
            {
                break;
            }
            let evidence_id = PerformanceEvidenceId::from_digest(digest_text(&lookup.memory_id));
            let matches = IndexedSourceResolver
                .resolve(
                    &storage,
                    &snapshots,
                    &[SourceResolutionEvidence::TypeName {
                        id: evidence_id,
                        qualified_name: lookup.qualified_name.clone(),
                    }],
                    BuildIdentityMatch::Unverified,
                    MAX_CANDIDATES - examined,
                )
                .map_err(|_| "E_MEMORY_SOURCE_RESOLUTION")?;
            for parent in matches {
                examined += 1;
                if parent.confidence == ResolutionConfidence::Weak || !seen.insert(parent.id) {
                    continue;
                }
                let workspace = frozen
                    .iter()
                    .find(|item| item.snapshot.id == parent.location.snapshot_id)
                    .ok_or("E_MEMORY_SOURCE_SNAPSHOT_CHANGED")?;
                let file = checked_file(&storage, &parent.location)?;
                let anchor = parent
                    .location
                    .range
                    .ok_or("E_MEMORY_SOURCE_RANGE")?
                    .start_line;
                let symbols = storage
                    .symbols_qualified(
                        file.snapshot_id,
                        SourceSymbolKind::Type,
                        &lookup.qualified_name,
                        256,
                    )
                    .map_err(|_| "E_MEMORY_SOURCE_DATABASE")?;
                let symbol = symbols
                    .into_iter()
                    .find(|symbol| {
                        symbol.relative_path == file.relative_path && symbol.start_line == anchor
                    })
                    .ok_or("E_MEMORY_SOURCE_SYMBOL_CHANGED")?;
                let text = immutable_text(&cache, &file)?;
                let Some(span) = class_span(&text, file.language, &symbol, &lookup) else {
                    continue;
                };
                let Some((range, snippet, truncated)) = excerpt(&text, span) else {
                    continue;
                };
                let mut candidate = parent.clone();
                candidate.location.range = Some(range);
                let snippet_digest = digest_text(&snippet);
                candidate.id = ResolutionCandidateId::from_digest(digest_text(&format!(
                    "memory-source-v1:{}:{}:{}:{}",
                    parent.id.to_hex(),
                    range.start_line,
                    range.end_line,
                    snippet_digest.to_hex(),
                )));
                let dto = SourceCandidate {
                    id: candidate.id.to_hex(),
                    workspace_id: candidate.location.workspace_id.to_hex(),
                    snapshot_id: candidate.location.snapshot_id.to_hex(),
                    content_digest: candidate.location.content_hash,
                    relative_path: candidate.location.relative_path.clone(),
                    start_line: Some(range.start_line as u32),
                    end_line: Some(range.end_line as u32),
                    snippet: Some(snippet),
                };
                let state = working_state(&workspace.config, &candidate.location);
                let binding = Evidence {
                    id: format!("memory-source-binding:{}", candidate.id.to_hex()),
                    kind: "memory-source-binding".into(),
                    summary: "Immutable source context; unverified build identity, not leak proof"
                        .into(),
                    payload: crate::evidence::object([
                        ("candidateId", dto.id.clone().into()),
                        (
                            "memoryEvidenceIds",
                            Value::Array(
                                lookup.memory_ids.iter().cloned().map(Value::from).collect(),
                            ),
                        ),
                        ("workspaceId", dto.workspace_id.clone().into()),
                        ("snapshotId", dto.snapshot_id.clone().into()),
                        ("contentDigest", dto.content_digest.to_hex().into()),
                        ("startLine", (range.start_line as u64).into()),
                        ("endLine", (range.end_line as u64).into()),
                        (
                            "resolutionConfidence",
                            confidence(candidate.confidence).into(),
                        ),
                        ("buildIdentity", "UNVERIFIED".into()),
                        ("sourceContentState", state_name(state).into()),
                        (
                            "immutableRevision",
                            workspace.snapshot.immutable_revision.clone().into(),
                        ),
                        ("indexComplete", workspace.snapshot.index_complete.into()),
                        ("snippetDigest", snippet_digest.to_hex().into()),
                        ("snippetTruncated", truncated.into()),
                    ]),
                };
                if request.evidence.iter().any(|item| item.id == binding.id) {
                    return Err("E_MEMORY_SOURCE_REQUEST".into());
                }
                candidates.push(BoundCandidate {
                    candidate,
                    file,
                    symbol,
                    dto,
                    binding,
                    span,
                    state,
                });
                if context_bytes(&candidates).is_err() {
                    candidates.pop();
                }
                if candidates.len() == MAX_CANDIDATES
                    || request.evidence.len() + candidates.len() == MAX_EVIDENCE
                {
                    break;
                }
            }
        }
        // No-match is also a job boundary: a refresh/revoke is not a quiet fallback.
        validate_workspaces(&self.paths, &storage, &frozen)?;
        if candidates.is_empty() {
            return Ok((request, None));
        }
        let persisted: Vec<_> = candidates
            .iter()
            .map(|item| item.candidate.clone())
            .collect();
        service::save_candidates(&self.paths, &persisted)
            .map_err(|_| "E_MEMORY_SOURCE_PERSISTENCE")?;
        let guard = MemorySourceGuard {
            expected_context: context_bytes(&candidates)?,
            paths: self.paths,
            workspaces: frozen,
            candidates,
        };
        guard.validate()?;
        request.scope = SCOPE.into();
        request.candidates = guard
            .candidates
            .iter()
            .map(|item| item.dto.clone())
            .collect();
        request
            .evidence
            .extend(guard.candidates.iter().map(|item| item.binding.clone()));
        Ok((request, Some(guard)))
    }
}

impl MemorySourceGuard {
    pub(crate) fn validate(&self) -> Result<(), String> {
        let storage = open(&self.paths)?;
        validate_workspaces(&self.paths, &storage, &self.workspaces)?;
        if context_bytes(&self.candidates)? != self.expected_context {
            return Err("E_MEMORY_SOURCE_BINDING_CHANGED".into());
        }
        let cache = cache(&self.paths)?;
        for item in &self.candidates {
            let candidate = &item.candidate;
            if checked_file(&storage, &candidate.location)? != item.file {
                return Err("E_MEMORY_SOURCE_MANIFEST_CHANGED".into());
            }
            let symbols = storage
                .symbols_qualified(
                    item.symbol.snapshot_id,
                    SourceSymbolKind::Type,
                    &item.symbol.qualified_name,
                    256,
                )
                .map_err(|_| "E_MEMORY_SOURCE_DATABASE")?;
            if !symbols.contains(&item.symbol) {
                return Err("E_MEMORY_SOURCE_SYMBOL_CHANGED".into());
            }
            if storage
                .candidate(candidate.id)
                .map_err(|_| "E_MEMORY_SOURCE_DATABASE")?
                .as_ref()
                != Some(candidate)
            {
                return Err("E_MEMORY_SOURCE_CANDIDATE_CHANGED".into());
            }
            let text = immutable_text(&cache, &item.file)?;
            let (range, snippet, truncated) =
                excerpt(&text, item.span).ok_or("E_MEMORY_SOURCE_RANGE")?;
            if candidate.location.range != Some(range)
                || item.dto.snippet.as_deref() != Some(snippet.as_str())
                || item.binding.payload["snippetDigest"].as_str()
                    != Some(digest_text(&snippet).to_hex().as_str())
                || item.binding.payload["snippetTruncated"].as_bool() != Some(truncated)
            {
                return Err("E_MEMORY_SOURCE_BINDING_CHANGED".into());
            }
            let workspace = self
                .workspaces
                .iter()
                .find(|workspace| workspace.snapshot.id == candidate.location.snapshot_id)
                .ok_or("E_MEMORY_SOURCE_SNAPSHOT_CHANGED")?;
            if working_state(&workspace.config, &candidate.location) != item.state {
                return Err("E_MEMORY_SOURCE_CONTENT_CHANGED".into());
            }
        }
        validate_workspaces(&self.paths, &storage, &self.workspaces)
    }
}

fn eligible(workspace: &SourceWorkspace) -> bool {
    workspace.phase == SourceWorkspacePhase::Ready
        && workspace.active_snapshot_id.is_some()
        && workspace.config.kind() == SourceProviderKind::Local
}

fn open(paths: &service::SourcePaths) -> Result<Storage, String> {
    let database = paths.database.as_ref().ok_or("E_MEMORY_SOURCE_DATABASE")?;
    if !database.is_file() {
        return Err("E_MEMORY_SOURCE_DATABASE".into());
    }
    Storage::open(database).map_err(|_| "E_MEMORY_SOURCE_DATABASE".into())
}

fn cache(paths: &service::SourcePaths) -> Result<ContentAddressedSourceCache, String> {
    Ok(ContentAddressedSourceCache::new(
        paths
            .cache_root()
            .map_err(|_| "E_MEMORY_SOURCE_CACHE_UNAVAILABLE")?,
    ))
}

fn load_policy(paths: &service::SourcePaths) -> Result<permissions::SourcePermissions, String> {
    let directory = paths.data_dir.as_ref().ok_or("E_MEMORY_SOURCE_POLICY")?;
    permissions::SourcePermissions::load(&directory.join(permissions::FILE_NAME))
        .map_err(|_| "E_MEMORY_SOURCE_POLICY".into())
}

fn validate_workspaces(
    paths: &service::SourcePaths,
    storage: &Storage,
    frozen: &[FrozenWorkspace],
) -> Result<(), String> {
    let policy = load_policy(paths)?;
    for expected in frozen {
        let snapshot = &expected.snapshot;
        if !policy.allowed(&snapshot.workspace_id.to_hex()) {
            return Err("E_MEMORY_SOURCE_PERMISSION_REVOKED".into());
        }
        let workspace = storage
            .workspace(snapshot.workspace_id)
            .map_err(|_| "E_MEMORY_SOURCE_DATABASE")?
            .ok_or("E_MEMORY_SOURCE_WORKSPACE_CHANGED")?;
        if !eligible(&workspace)
            || workspace.active_snapshot_id != Some(snapshot.id)
            || workspace.config != expected.config
        {
            return Err("E_MEMORY_SOURCE_WORKSPACE_CHANGED".into());
        }
        if storage
            .snapshot(snapshot.id)
            .map_err(|_| "E_MEMORY_SOURCE_DATABASE")?
            .as_ref()
            != Some(snapshot)
        {
            return Err("E_MEMORY_SOURCE_SNAPSHOT_CHANGED".into());
        }
    }
    Ok(())
}

fn checked_file(storage: &Storage, location: &SourceLocation) -> Result<SourceFile, String> {
    let file = storage
        .file(location.snapshot_id, &location.relative_path)
        .map_err(|_| "E_MEMORY_SOURCE_DATABASE")?
        .ok_or("E_MEMORY_SOURCE_MANIFEST_CHANGED")?;
    if file.content_hash != location.content_hash {
        return Err("E_MEMORY_SOURCE_MANIFEST_CHANGED".into());
    }
    if file.size_bytes > MAX_SOURCE_FILE_BYTES {
        return Err("E_MEMORY_SOURCE_CACHE_LIMIT".into());
    }
    Ok(file)
}

fn immutable_text(
    cache: &ContentAddressedSourceCache,
    file: &SourceFile,
) -> Result<String, String> {
    let bytes = cache.read_bounded(file.content_hash, MAX_SOURCE_FILE_BYTES)?;
    if bytes.len() as u64 != file.size_bytes {
        return Err("E_MEMORY_SOURCE_MANIFEST_CHANGED".into());
    }
    String::from_utf8(bytes).map_err(|_| "E_MEMORY_SOURCE_UTF8".into())
}

fn working_state(config: &SourceProviderConfig, location: &SourceLocation) -> SourceContentState {
    // Same current/stale semantics as service::read, but stream only a bounded
    // digest of the working file. Only immutable cache text enters the request.
    let SourceProviderConfig::Local { root } = config else {
        return SourceContentState::Stale;
    };
    let current = (|| {
        let path = root.join(&location.relative_path);
        let metadata = std::fs::symlink_metadata(&path).ok()?;
        if !metadata.is_file() || metadata.len() > MAX_SOURCE_FILE_BYTES {
            return None;
        }
        SourceDigest::hash_reader(File::open(path).ok()?, MAX_SOURCE_FILE_BYTES).ok()
    })();
    if current == Some(location.content_hash) {
        SourceContentState::Current
    } else {
        SourceContentState::Stale
    }
}

fn type_lookups(request: &AnalysisRequest) -> Vec<TypeLookup> {
    let mut result: Vec<TypeLookup> = Vec::new();
    let mut seen = BTreeMap::new();
    for evidence in &request.evidence {
        if !matches!(
            evidence.kind.as_str(),
            "memory-leak-suspect" | "memory-activity-candidate" | "memory-lifecycle-candidate"
        ) {
            continue;
        }
        let mut names = Vec::new();
        if let Some(name) = evidence.payload.get("className").and_then(Value::as_str) {
            names.push(name);
        }
        if let Some(chain) = evidence.payload.get("referenceChain") {
            for key in ["rootPathPrefix", "targetPathSuffix"] {
                if let Some(nodes) = chain.get(key).and_then(Value::as_array) {
                    for node in nodes.iter().take(MAX_TYPES) {
                        if let Some(name) = node.get("className").and_then(Value::as_str) {
                            names.push(name);
                        }
                    }
                }
            }
        }
        for name in names {
            let Some(lookup) = normalize_type(name, &evidence.id) else {
                continue;
            };
            let key = (lookup.package.clone(), lookup.class_names.clone());
            if let Some(&index) = seen.get(&key) {
                let existing: &mut TypeLookup = &mut result[index];
                if !existing.memory_ids.contains(&lookup.memory_id) {
                    existing.memory_ids.push(lookup.memory_id);
                }
            } else if result.len() < MAX_TYPES {
                seen.insert(key, result.len());
                result.push(lookup);
            }
        }
    }
    result
}

fn normalize_type(name: &str, memory_id: &str) -> Option<TypeLookup> {
    if name.len() > 2048 {
        return None;
    }
    let mut name = name;
    while let Some(rest) = name.strip_suffix("[]") {
        name = rest;
    }
    if name.starts_with('[') {
        name = name.trim_start_matches('[');
        if !name.starts_with('L') || !name.ends_with(';') {
            return None;
        }
    }
    if let Some(rest) = name
        .strip_prefix('L')
        .and_then(|value| value.strip_suffix(';'))
    {
        name = rest;
    }
    let name = name.replace('/', ".");
    let (package, class) = name.rsplit_once('.').unwrap_or(("", &name));
    let identifier = |value: &str| {
        value
            .as_bytes()
            .first()
            .is_some_and(|byte| byte.is_ascii_alphabetic() || *byte == b'_')
            && value
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
    };
    if !package.is_empty() && !package.split('.').all(identifier) {
        return None;
    }
    let class_names: Vec<_> = class.split('$').map(str::to_owned).collect();
    if !class_names.iter().all(|name| identifier(name)) || class_names.len() > 16 {
        return None;
    }
    let short = class_names.last()?;
    let qualified_name = if package.is_empty() {
        short.clone()
    } else {
        format!("{package}.{short}")
    };
    Some(TypeLookup {
        memory_id: memory_id.into(),
        memory_ids: vec![memory_id.into()],
        qualified_name,
        package: package.into(),
        class_names,
    })
}

fn class_name<'a>(node: tree_sitter::Node<'_>, text: &'a str) -> Option<&'a str> {
    if !matches!(
        node.kind(),
        "class_declaration"
            | "interface_declaration"
            | "enum_declaration"
            | "record_declaration"
            | "annotation_type_declaration"
            | "object_declaration"
    ) {
        return None;
    }
    node.child_by_field_name("name")?
        .utf8_text(text.as_bytes())
        .ok()
}

fn class_span(
    text: &str,
    language: SourceLanguage,
    symbol: &SourceSymbol,
    lookup: &TypeLookup,
) -> Option<SourceRange> {
    let language = match language {
        SourceLanguage::Java => tree_sitter_java::LANGUAGE.into(),
        SourceLanguage::Kotlin => tree_sitter_kotlin_ng::LANGUAGE.into(),
        _ => return None,
    };
    let mut parser = tree_sitter::Parser::new();
    parser.set_language(&language).ok()?;
    let tree = parser.parse(text, None)?;
    let root = tree.root_node();
    if root.has_error() {
        return None;
    }
    let mut cursor = root.walk();
    let package = root
        .named_children(&mut cursor)
        .find(|node| matches!(node.kind(), "package_declaration" | "package_header"));
    let actual_package = match package {
        Some(node) => node
            .utf8_text(text.as_bytes())
            .ok()?
            .strip_prefix("package")?
            .trim()
            .trim_end_matches(';')
            .trim(),
        None => "",
    };
    if actual_package != lookup.package {
        return None;
    }
    let anchor = symbol.start_line.checked_sub(1)?;
    let mut stack = vec![root];
    let mut visited = 0;
    while let Some(node) = stack.pop() {
        visited += 1;
        if visited > 20_000 {
            return None;
        }
        if node.start_position().row > anchor || node.end_position().row < anchor {
            continue;
        }
        if class_name(node, text) == lookup.class_names.last().map(String::as_str) {
            let name = node.child_by_field_name("name")?;
            if name.end_position().row >= anchor {
                let mut names = Vec::new();
                let mut parent = Some(node);
                let mut valid = true;
                while let Some(item) = parent {
                    if let Some(name) = class_name(item, text) {
                        names.push(name);
                    } else if matches!(
                        item.kind(),
                        "method_declaration"
                            | "function_declaration"
                            | "constructor_declaration"
                            | "lambda_literal"
                            | "object_literal"
                            | "object_creation_expression"
                            | "enum_constant"
                            | "companion_object"
                            | "block"
                            | "static_initializer"
                            | "anonymous_initializer"
                            | "secondary_constructor"
                    ) {
                        valid = false;
                    }
                    parent = item.parent();
                }
                names.reverse();
                if valid
                    && names
                        .iter()
                        .copied()
                        .eq(lookup.class_names.iter().map(String::as_str))
                {
                    let end = if node.end_position().column == 0 {
                        node.end_position().row
                    } else {
                        node.end_position().row + 1
                    };
                    // The structural index currently uses start=end as a span
                    // placeholder. Recover that body only from the parsed class.
                    let end_line = if symbol.end_line == symbol.start_line {
                        end
                    } else {
                        symbol.end_line
                    };
                    // A line-based snippet cannot safely isolate sibling types
                    // sharing the boundary line. Omit rather than include them.
                    let line_start = text[..node.start_byte()].rfind('\n').map_or(0, |at| at + 1);
                    let leading_is_class = node.start_position().row < anchor
                        || text[line_start..node.start_byte()].trim().is_empty();
                    let trailing_is_class = text[node.end_byte()..]
                        .split('\n')
                        .next()?
                        .trim()
                        .is_empty();
                    if end_line >= symbol.start_line
                        && end_line == end
                        && leading_is_class
                        && trailing_is_class
                    {
                        return Some(SourceRange {
                            start_line: symbol.start_line,
                            end_line,
                        });
                    }
                }
            }
        }
        let mut cursor = node.walk();
        for child in node.named_children(&mut cursor) {
            if child.start_position().row <= anchor && child.end_position().row >= anchor {
                stack.push(child);
            }
        }
    }
    None
}

fn excerpt(text: &str, span: SourceRange) -> Option<(SourceRange, String, bool)> {
    if span.start_line == 0 || span.end_line < span.start_line {
        return None;
    }
    let mut snippet = String::new();
    let mut end_line = span.start_line - 1;
    for (index, line) in text
        .split_inclusive('\n')
        .enumerate()
        .skip(span.start_line - 1)
    {
        let number = index + 1;
        if number > span.end_line
            || number - span.start_line >= MAX_LINES
            || snippet.len() + line.len() > MAX_SNIPPET_BYTES
        {
            break;
        }
        snippet.push_str(line);
        end_line = number;
    }
    if end_line < span.start_line {
        return None;
    }
    Some((
        SourceRange {
            start_line: span.start_line,
            end_line,
        },
        snippet,
        end_line < span.end_line,
    ))
}

fn confidence(confidence: ResolutionConfidence) -> &'static str {
    match confidence {
        ResolutionConfidence::Exact => "EXACT",
        _ => "PROBABLE",
    }
}

fn state_name(state: SourceContentState) -> &'static str {
    match state {
        SourceContentState::Current => "CURRENT",
        SourceContentState::Stale => "STALE",
    }
}

fn context_bytes(candidates: &[BoundCandidate]) -> Result<Vec<u8>, String> {
    use crate::evidence::object;
    let source = candidates
        .iter()
        .map(|item| {
            let dto = &item.dto;
            object([
                ("id", dto.id.clone().into()),
                ("workspaceId", dto.workspace_id.clone().into()),
                ("snapshotId", dto.snapshot_id.clone().into()),
                ("contentDigest", dto.content_digest.to_hex().into()),
                ("relativePath", dto.relative_path.clone().into()),
                ("startLine", dto.start_line.map_or(Value::Null, Value::from)),
                ("endLine", dto.end_line.map_or(Value::Null, Value::from)),
                (
                    "sourceSnippet",
                    dto.snippet.clone().map_or(Value::Null, Value::from),
                ),
            ])
        })
        .collect();
    let bindings = candidates
        .iter()
        .map(|item| {
            object([
                ("id", item.binding.id.clone().into()),
                ("kind", item.binding.kind.clone().into()),
                ("summary", item.binding.summary.clone().into()),
                ("payload", item.binding.payload.clone()),
            ])
        })
        .collect();
    aps_json::to_vec(
        &object([
            ("sourceCandidates", Value::Array(source)),
            ("performanceEvidence", Value::Array(bindings)),
        ]),
        Limits {
            max_bytes: MAX_CONTEXT_BYTES,
            max_depth: 8,
            max_nodes: 2048,
            max_string_bytes: 4096,
            max_collection_items: MAX_EVIDENCE,
        },
    )
    .map_err(|_| "E_MEMORY_SOURCE_CONTEXT_LIMIT".into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::evidence::object;
    use aps_source::{SourceSnapshotId, SourceWorkspaceId};
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT: AtomicU64 = AtomicU64::new(0);
    const SOURCE: &str = "package sample\n\nclass Widget {\n    val retained = 1\n}\n\nclass Unrelated {\n    val privateValue = 2\n}\n";

    struct Fixture {
        root: PathBuf,
    }
    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!(
                "aps-memory-source-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir(&root).unwrap();
            Self { root }
        }
        fn paths(&self) -> service::SourcePaths {
            service::SourcePaths {
                database: Some(self.root.join("aps-metadata.sqlite3")),
                data_dir: Some(self.root.clone()),
            }
        }
        fn write(&self, text: impl AsRef<[u8]>) {
            let path = self.root.join("project/src/Widget.kt");
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(path, text).unwrap();
        }
        fn workspace(&self, text: impl AsRef<[u8]>) -> SourceWorkspace {
            self.write(text);
            let mut workspace = service::add_local(
                &self.paths(),
                self.root.join("project"),
                "Fixture".into(),
                &mut |_| {},
            )
            .unwrap();
            assert_eq!(workspace.phase, SourceWorkspacePhase::Ready);
            // Real grants are deliberately not the legacy database boolean.
            workspace.allow_ai_source_upload = true;
            workspace
        }
        fn grant(&self, workspace: &SourceWorkspace, allowed: bool) {
            let path = self.root.join(permissions::FILE_NAME);
            load_policy(&self.paths())
                .unwrap()
                .with_allowed(&workspace.id.to_hex(), allowed)
                .unwrap()
                .save(&path)
                .unwrap();
        }
        fn plan(&self, workspace: &SourceWorkspace) -> MemorySourcePlan {
            MemorySourcePlan::new(self.paths(), vec![workspace.clone()])
        }
        fn enriched(&self, workspace: &SourceWorkspace) -> (AnalysisRequest, MemorySourceGuard) {
            let (request, guard) = self
                .plan(workspace)
                .enrich(request("sample.Widget"))
                .unwrap();
            (request, guard.unwrap())
        }
        fn cache_path(&self, hash: SourceDigest) -> PathBuf {
            let hex = hash.to_hex();
            self.paths()
                .cache_root()
                .unwrap()
                .join(&hex[..2])
                .join(&hex[2..])
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }

    fn row(id: &str, class: &str) -> Evidence {
        Evidence {
            id: id.into(),
            kind: "memory-leak-suspect".into(),
            summary: "HPROF heuristic".into(),
            payload: object([
                ("className", class.into()),
                ("reason", "untrusted sample.Unrelated".into()),
            ]),
        }
    }
    fn request(class: &str) -> AnalysisRequest {
        AnalysisRequest {
            session_id: "memory-session".into(),
            run_id: "memory-run".into(),
            generation: 1,
            profiler: "memory".into(),
            scope: "HPROF candidates only; no source or raw heap".into(),
            evidence: vec![
                Evidence {
                    id: "coverage".into(),
                    kind: "memory-hprof-leak-findings".into(),
                    summary: "Heuristic findings".into(),
                    payload: Value::Null,
                },
                row("memory-leak-suspect:original-id", class),
            ],
            candidates: Vec::new(),
            user_question: Some("Explain retention".into()),
        }
    }
    fn request_bytes(request: &AnalysisRequest) -> Vec<u8> {
        let evidence = request
            .evidence
            .iter()
            .map(|item| {
                object([
                    ("id", item.id.clone().into()),
                    ("kind", item.kind.clone().into()),
                    ("summary", item.summary.clone().into()),
                    ("payload", item.payload.clone()),
                ])
            })
            .collect();
        aps_json::to_vec(
            &object([
                ("session", request.session_id.clone().into()),
                ("run", request.run_id.clone().into()),
                ("generation", request.generation.into()),
                ("profiler", request.profiler.clone().into()),
                ("scope", request.scope.clone().into()),
                ("evidence", Value::Array(evidence)),
                ("candidateCount", (request.candidates.len() as u64).into()),
                (
                    "question",
                    request
                        .user_question
                        .clone()
                        .map_or(Value::Null, Value::from),
                ),
            ]),
            Limits::default(),
        )
        .unwrap()
    }

    #[test]
    fn missing_grant_blocked_ui_and_no_match_preserve_original_request() {
        let fixture = Fixture::new();
        let workspace = fixture.workspace(SOURCE);
        let original = request("sample.Widget");
        let bytes = request_bytes(&original);
        let (unchanged, guard) = fixture.plan(&workspace).enrich(original.clone()).unwrap();
        assert_eq!(request_bytes(&unchanged), bytes);
        assert!(guard.is_none());
        fixture.grant(&workspace, true);
        let mut blocked = workspace.clone();
        blocked.allow_ai_source_upload = false;
        let (unchanged, guard) = fixture.plan(&blocked).enrich(original).unwrap();
        assert_eq!(request_bytes(&unchanged), bytes);
        assert!(guard.is_none());
        let unmatched = request("sample.Absent");
        let bytes = request_bytes(&unmatched);
        let (unchanged, guard) = fixture.plan(&workspace).enrich(unmatched).unwrap();
        assert_eq!(request_bytes(&unchanged), bytes);
        assert!(guard.is_none());
        let empty = MemorySourcePlan::new(
            service::SourcePaths {
                database: None,
                data_dir: None,
            },
            Vec::new(),
        );
        assert!(empty.enrich(request("sample.Widget")).unwrap().1.is_none());
    }

    #[test]
    fn binding_exact_keys_original_id_and_clipped_navigation_are_persisted() {
        let fixture = Fixture::new();
        let workspace = fixture.workspace(SOURCE);
        fixture.grant(&workspace, true);
        let (request, guard) = fixture.enriched(&workspace);
        guard.clone().validate().unwrap();
        assert_eq!(request.scope, SCOPE);
        assert_eq!(request.candidates.len(), 1);
        let dto = &request.candidates[0];
        assert_eq!(dto.start_line, Some(3));
        assert_eq!(dto.end_line, Some(5));
        assert_eq!(
            dto.snippet.as_deref(),
            Some("class Widget {\n    val retained = 1\n}\n")
        );
        let binding = request.evidence.last().unwrap();
        let expected: BTreeSet<_> = [
            "candidateId",
            "memoryEvidenceIds",
            "workspaceId",
            "snapshotId",
            "contentDigest",
            "startLine",
            "endLine",
            "resolutionConfidence",
            "buildIdentity",
            "sourceContentState",
            "immutableRevision",
            "indexComplete",
            "snippetDigest",
            "snippetTruncated",
        ]
        .into_iter()
        .collect();
        assert_eq!(
            binding
                .payload
                .as_object()
                .unwrap()
                .keys()
                .map(String::as_str)
                .collect::<BTreeSet<_>>(),
            expected
        );
        assert_eq!(
            binding.payload["memoryEvidenceIds"],
            Value::Array(vec!["memory-leak-suspect:original-id".into()])
        );
        assert_eq!(binding.payload["resolutionConfidence"], "PROBABLE");
        assert_eq!(binding.payload["buildIdentity"], "UNVERIFIED");
        assert_eq!(binding.payload["sourceContentState"], "CURRENT");
        assert_eq!(binding.payload["snippetTruncated"], false);
        assert_eq!(binding.payload["candidateId"], dto.id);
        let persisted = service::candidate(
            &fixture.paths(),
            ResolutionCandidateId::from_hex(&dto.id).unwrap(),
        )
        .unwrap()
        .unwrap();
        assert_eq!(
            persisted.evidence_id,
            PerformanceEvidenceId::from_digest(digest_text("memory-leak-suspect:original-id"))
        );
        assert_eq!(
            persisted.location.range,
            Some(SourceRange {
                start_line: 3,
                end_line: 5
            })
        );
        let read = service::read(&fixture.paths(), &persisted.location).unwrap();
        assert_eq!(
            excerpt(&read.text, persisted.location.range.unwrap())
                .unwrap()
                .1,
            dto.snippet.clone().unwrap()
        );
        let (again, _) = fixture.enriched(&workspace);
        assert_eq!(again.candidates[0].id, dto.id);
        let binding_bytes = aps_json::to_vec(&binding.payload, Limits::default()).unwrap();
        let binding_text = String::from_utf8(binding_bytes).unwrap();
        assert!(!binding_text.contains("Widget.kt"));
        assert!(!binding_text.contains("class Widget"));
    }

    #[test]
    fn mapped_names_and_chain_endpoints_only_not_reason_or_other_payloads() {
        let fixture = Fixture::new();
        let workspace = fixture.workspace(SOURCE);
        fixture.grant(&workspace, true);
        let mut input = request("a.Obfuscated");
        input.evidence[1].payload["referenceChain"] = object([
            (
                "rootPathPrefix",
                Value::Array(vec![object([("className", "sample/Widget".into())])]),
            ),
            (
                "targetPathSuffix",
                Value::Array(vec![object([("className", "[Lsample/Widget;".into())])]),
            ),
            ("reason", "sample.Unrelated".into()),
        ]);
        input.evidence[0].payload = object([("className", "sample.Unrelated".into())]);
        let (enriched, _) = fixture.plan(&workspace).enrich(input).unwrap();
        assert_eq!(enriched.candidates.len(), 1);
        assert!(
            enriched.candidates[0]
                .snippet
                .as_deref()
                .unwrap()
                .contains("retained")
        );
        assert!(
            !enriched.candidates[0]
                .snippet
                .as_deref()
                .unwrap()
                .contains("Unrelated")
        );
    }

    #[test]
    fn jvm_nested_names_require_verified_named_ancestry_not_flat_short_name() {
        let fixture = Fixture::new();
        let workspace = fixture.workspace("package sample\nclass Outer {\n    class Inner {\n        val retained = 1\n    }\n}\nclass Inner {\n    val unrelated = 2\n}\n");
        fixture.grant(&workspace, true);
        let (enriched, guard) = fixture
            .plan(&workspace)
            .enrich(request("sample.Outer$Inner"))
            .unwrap();
        assert_eq!(enriched.candidates.len(), 1);
        assert_eq!(enriched.candidates[0].start_line, Some(3));
        assert_eq!(enriched.candidates[0].end_line, Some(5));
        guard.unwrap().validate().unwrap();
        let (unmatched, guard) = fixture
            .plan(&workspace)
            .enrich(request("sample.Missing$Inner"))
            .unwrap();
        assert!(unmatched.candidates.is_empty());
        assert!(guard.is_none());
        for name in [
            "sample.Outer$1",
            "sample.Outer$$Lambda",
            "sample.Outer$",
            "sample.Bad name",
            "sample.Foo;ignored",
        ] {
            assert!(normalize_type(name, "memory-id").is_none());
        }
    }

    #[test]
    fn snapshot_refresh_before_enrich_and_after_enrich_fail_closed() {
        let fixture = Fixture::new();
        let workspace = fixture.workspace(SOURCE);
        fixture.grant(&workspace, true);
        let (_, guard) = fixture.enriched(&workspace);
        let plan = fixture.plan(&workspace);
        fixture.write(SOURCE.replace("retained = 1", "retained = 3"));
        let refreshed = service::refresh(&fixture.paths(), workspace.id, &mut |_| {}).unwrap();
        assert_ne!(refreshed.active_snapshot_id, workspace.active_snapshot_id);
        assert_eq!(
            plan.enrich(request("sample.Widget")).err().unwrap(),
            "E_MEMORY_SOURCE_WORKSPACE_CHANGED"
        );
        assert_eq!(
            guard.validate().unwrap_err(),
            "E_MEMORY_SOURCE_WORKSPACE_CHANGED"
        );
    }

    #[test]
    fn revoke_malformed_policy_and_workspace_state_are_guarded() {
        let fixture = Fixture::new();
        let workspace = fixture.workspace(SOURCE);
        fixture.grant(&workspace, true);
        let (_, guard) = fixture.enriched(&workspace);
        fixture.grant(&workspace, false);
        assert_eq!(
            guard.validate().unwrap_err(),
            "E_MEMORY_SOURCE_PERMISSION_REVOKED"
        );
        fixture.grant(&workspace, true);
        let mut storage = open(&fixture.paths()).unwrap();
        let mut changed = workspace.clone();
        changed.phase = SourceWorkspacePhase::Indexing;
        storage.save_workspace(&changed).unwrap();
        assert_eq!(
            guard.validate().unwrap_err(),
            "E_MEMORY_SOURCE_WORKSPACE_CHANGED"
        );
        storage.save_workspace(&workspace).unwrap();
        changed.config = SourceProviderConfig::GitHub {
            owner: "owner".into(),
            repository: "repo".into(),
            git_ref: "main".into(),
            credential_key: None,
        };
        storage.save_workspace(&changed).unwrap();
        assert_eq!(
            guard.validate().unwrap_err(),
            "E_MEMORY_SOURCE_WORKSPACE_CHANGED"
        );
        std::fs::write(fixture.root.join(permissions::FILE_NAME), b"not json").unwrap();
        assert_eq!(guard.validate().unwrap_err(), "E_MEMORY_SOURCE_POLICY");
        assert_eq!(
            fixture
                .plan(&workspace)
                .enrich(request("sample.Widget"))
                .err()
                .unwrap(),
            "E_MEMORY_SOURCE_POLICY"
        );
    }

    #[test]
    fn cache_tamper_and_candidate_rebinding_never_become_unguarded_fallback() {
        let fixture = Fixture::new();
        let workspace = fixture.workspace(SOURCE);
        fixture.grant(&workspace, true);
        let (_, guard) = fixture.enriched(&workspace);
        let mut changed = guard.candidates[0].candidate.clone();
        changed.location.range.as_mut().unwrap().end_line -= 1;
        service::save_candidates(&fixture.paths(), &[changed]).unwrap();
        assert_eq!(
            guard.validate().unwrap_err(),
            "E_MEMORY_SOURCE_CANDIDATE_CHANGED"
        );
        service::save_candidates(&fixture.paths(), &[guard.candidates[0].candidate.clone()])
            .unwrap();
        let hash = guard.candidates[0].file.content_hash;
        let bytes = SOURCE.replace("retained = 1", "retained = 9");
        std::fs::write(fixture.cache_path(hash), bytes).unwrap();
        assert_eq!(guard.validate().unwrap_err(), "E_MEMORY_SOURCE_CACHE_HASH");
        assert_eq!(
            fixture
                .plan(&workspace)
                .enrich(request("sample.Widget"))
                .err()
                .unwrap(),
            "E_MEMORY_SOURCE_CACHE_HASH"
        );
    }

    #[test]
    fn working_tree_changes_only_state_never_the_immutable_excerpt() {
        let fixture = Fixture::new();
        let workspace = fixture.workspace(SOURCE);
        fixture.grant(&workspace, true);
        let (_, current_guard) = fixture.enriched(&workspace);
        fixture.write(SOURCE.replace("retained = 1", "workingSecret = 777"));
        assert_eq!(
            current_guard.validate().unwrap_err(),
            "E_MEMORY_SOURCE_CONTENT_CHANGED"
        );
        let (stale, guard) = fixture.enriched(&workspace);
        assert_eq!(
            stale.evidence.last().unwrap().payload["sourceContentState"],
            "STALE"
        );
        assert!(
            stale.candidates[0]
                .snippet
                .as_ref()
                .unwrap()
                .contains("retained = 1")
        );
        assert!(
            !stale.candidates[0]
                .snippet
                .as_ref()
                .unwrap()
                .contains("workingSecret")
        );
        guard.validate().unwrap();
        fixture.write(SOURCE);
        assert_eq!(
            guard.validate().unwrap_err(),
            "E_MEMORY_SOURCE_CONTENT_CHANGED"
        );
    }

    #[test]
    fn invalid_utf8_in_immutable_cache_is_an_error_not_lossy_text() {
        let fixture = Fixture::new();
        let workspace = fixture.workspace(b"package sample\nclass Widget {\n// \xff\n}\n");
        fixture.grant(&workspace, true);
        assert_eq!(
            fixture
                .plan(&workspace)
                .enrich(request("sample.Widget"))
                .err()
                .unwrap(),
            "E_MEMORY_SOURCE_UTF8"
        );
    }

    #[test]
    fn excerpts_obey_line_byte_and_complete_line_bounds() {
        let fixture = Fixture::new();
        let mut text = "package sample\nclass Widget {\n".to_owned();
        for index in 0..120 {
            text.push_str(&format!("    val value{index} = {index}\n"));
        }
        text.push_str("}\nclass Secret {}\n");
        let workspace = fixture.workspace(&text);
        fixture.grant(&workspace, true);
        let (request, guard) = fixture.enriched(&workspace);
        let dto = &request.candidates[0];
        assert_eq!(dto.start_line, Some(2));
        assert_eq!(dto.end_line, Some(81));
        assert_eq!(dto.snippet.as_ref().unwrap().lines().count(), MAX_LINES);
        assert_eq!(
            request.evidence.last().unwrap().payload["snippetTruncated"],
            true
        );
        assert!(dto.snippet.as_ref().unwrap().len() <= MAX_SNIPPET_BYTES);
        assert!(guard.expected_context.len() <= MAX_CONTEXT_BYTES as usize);
        guard.validate().unwrap();
        let unicode = format!("class Widget {{\n// {}\n}}\n", "\u{e9}".repeat(2000));
        let (range, snippet, truncated) = excerpt(
            &unicode,
            SourceRange {
                start_line: 1,
                end_line: 3,
            },
        )
        .unwrap();
        assert_eq!(range.end_line, 1);
        assert_eq!(snippet, "class Widget {\n");
        assert!(truncated);
        assert!(
            excerpt(
                &"x".repeat(3073),
                SourceRange {
                    start_line: 1,
                    end_line: 1
                }
            )
            .is_none()
        );
    }

    #[test]
    fn bounded_symbol_end_line_is_used_instead_of_resolver_single_line() {
        let fixture = Fixture::new();
        let workspace = fixture.workspace(SOURCE);
        fixture.grant(&workspace, true);
        let mut storage = open(&fixture.paths()).unwrap();
        let snapshot = storage
            .snapshot(workspace.active_snapshot_id.unwrap())
            .unwrap()
            .unwrap();
        let file = storage.file(snapshot.id, "src/Widget.kt").unwrap().unwrap();
        let mut symbols = storage
            .symbols_qualified(snapshot.id, SourceSymbolKind::Type, "sample.Widget", 1)
            .unwrap();
        symbols[0].end_line = 5;
        storage.save_snapshot(&snapshot, &[file], &symbols).unwrap();
        let (request, guard) = fixture.enriched(&workspace);
        assert_eq!(request.candidates[0].end_line, Some(5));
        guard.validate().unwrap();
    }

    #[test]
    fn candidate_count_serialized_budget_and_total_evidence_are_bounded() {
        let fixture = Fixture::new();
        let mut source = "package sample\n".to_owned();
        for index in 0..8 {
            source.push_str(&format!(
                "class Type{index} {{\n    val value = \"{}\"\n}}\n",
                "x".repeat(2000)
            ));
        }
        let workspace = fixture.workspace(source);
        fixture.grant(&workspace, true);
        let mut input = request("sample.Type0");
        input.evidence.extend(
            (1..8).map(|index| row(&format!("row:{index}"), &format!("sample.Type{index}"))),
        );
        let (request, guard) = fixture.plan(&workspace).enrich(input.clone()).unwrap();
        let guard = guard.unwrap();
        assert!(!request.candidates.is_empty());
        assert!(request.candidates.len() <= MAX_CANDIDATES);
        assert!(guard.expected_context.len() <= MAX_CONTEXT_BYTES as usize);
        assert_eq!(
            context_bytes(&guard.candidates).unwrap(),
            guard.expected_context
        );
        assert!(guard.candidates.len() < MAX_CANDIDATES);
        while input.evidence.len() < 99 {
            let index = input.evidence.len();
            input.evidence.push(Evidence {
                id: format!("padding:{index}"),
                kind: "metadata".into(),
                summary: "metadata".into(),
                payload: Value::Null,
            });
        }
        let (limited, _) = fixture.plan(&workspace).enrich(input.clone()).unwrap();
        assert_eq!(limited.evidence.len(), MAX_EVIDENCE);
        assert_eq!(limited.candidates.len(), 1);
        input.evidence.push(Evidence {
            id: "last-padding".into(),
            kind: "metadata".into(),
            summary: "metadata".into(),
            payload: Value::Null,
        });
        let bytes = request_bytes(&input);
        let (unchanged, guard) = fixture.plan(&workspace).enrich(input).unwrap();
        assert!(guard.is_none());
        assert_eq!(request_bytes(&unchanged), bytes);
    }

    #[test]
    fn plan_preserves_priority_caps_workspaces_and_gathering_caps_type_names() {
        let fixture = Fixture::new();
        let base = fixture.workspace(SOURCE);
        let workspaces: Vec<_> = (0..7)
            .map(|index| {
                let mut workspace = base.clone();
                workspace.id =
                    SourceWorkspaceId::from_digest(digest_text(&format!("workspace-{index}")));
                workspace.active_snapshot_id = Some(SourceSnapshotId::from_digest(digest_text(
                    &format!("snapshot-{index}"),
                )));
                workspace
            })
            .collect();
        let plan = MemorySourcePlan::new(fixture.paths(), workspaces.clone());
        assert_eq!(plan.workspaces.len(), MAX_WORKSPACES);
        assert_eq!(
            plan.workspaces
                .iter()
                .map(|item| item.id)
                .collect::<Vec<_>>(),
            workspaces[..4]
                .iter()
                .map(|item| item.id)
                .collect::<Vec<_>>()
        );
        let mut input = request("sample.Widget");
        input.evidence.extend(
            (0..99).map(|index| row(&format!("row-{index}"), &format!("sample.Type{index}"))),
        );
        assert_eq!(type_lookups(&input).len(), MAX_TYPES);
    }

    #[test]
    fn guard_retains_exact_binding_and_candidate_bytes() {
        let fixture = Fixture::new();
        let workspace = fixture.workspace(SOURCE);
        fixture.grant(&workspace, true);
        let (_, guard) = fixture.enriched(&workspace);
        let mut changed = guard.clone();
        changed.candidates[0].dto.snippet = Some("replacement".into());
        assert_eq!(
            changed.validate().unwrap_err(),
            "E_MEMORY_SOURCE_BINDING_CHANGED"
        );
        let mut changed = guard;
        changed.candidates[0].binding.payload["memoryEvidenceIds"] =
            Value::Array(vec!["different-memory-id".into()]);
        assert_eq!(
            changed.validate().unwrap_err(),
            "E_MEMORY_SOURCE_BINDING_CHANGED"
        );
    }

    #[test]
    fn verified_java_class_span_excludes_sibling_types_and_ambiguous_boundary_lines() {
        let symbol = SourceSymbol {
            snapshot_id: SourceSnapshotId::from_digest(digest_text("snapshot")),
            relative_path: "Widget.java".into(),
            kind: SourceSymbolKind::Type,
            qualified_name: "sample.Widget".into(),
            signature: None,
            start_line: 2,
            end_line: 4,
        };
        let lookup = normalize_type("sample.Widget", "memory-id").unwrap();
        let text = "package sample;\nclass Widget {\n    int retained = 1;\n}\nclass Secret {}\n";
        let span = class_span(text, SourceLanguage::Java, &symbol, &lookup).unwrap();
        assert_eq!(
            span,
            SourceRange {
                start_line: 2,
                end_line: 4
            }
        );
        assert!(!excerpt(text, span).unwrap().1.contains("Secret"));
        let mut placeholder = symbol.clone();
        placeholder.end_line = 2;
        assert_eq!(
            class_span(text, SourceLanguage::Java, &placeholder, &lookup),
            Some(span)
        );
        let shared = "package sample;\nclass Widget {} class Secret {}\n";
        assert!(class_span(shared, SourceLanguage::Java, &placeholder, &lookup).is_none());
        for descriptor in ["[I", "[[Z", "[Lsample/Widget", "[sample.Widget"] {
            assert!(normalize_type(descriptor, "memory-id").is_none());
        }
    }

    #[test]
    fn guard_rechecks_manifest_identity_and_snapshot_metadata() {
        let fixture = Fixture::new();
        let workspace = fixture.workspace(SOURCE);
        fixture.grant(&workspace, true);
        let (_, guard) = fixture.enriched(&workspace);
        let mut storage = open(&fixture.paths()).unwrap();
        let snapshot = guard.workspaces[0].snapshot.clone();
        let mut file = guard.candidates[0].file.clone();
        file.size_bytes += 1;
        storage.append_files(snapshot.id, &[file.clone()]).unwrap();
        assert_eq!(
            guard.validate().unwrap_err(),
            "E_MEMORY_SOURCE_MANIFEST_CHANGED"
        );
        storage
            .append_files(snapshot.id, &[guard.candidates[0].file.clone()])
            .unwrap();
        let mut changed = snapshot;
        changed.manifest_hash = digest_text("changed-manifest");
        storage
            .save_snapshot(
                &changed,
                &[guard.candidates[0].file.clone()],
                &[guard.candidates[0].symbol.clone()],
            )
            .unwrap();
        assert_eq!(
            guard.validate().unwrap_err(),
            "E_MEMORY_SOURCE_SNAPSHOT_CHANGED"
        );
    }

    #[test]
    fn actual_resolver_output_passes_ai_protocol_and_frozen_preview_with_explicit_scope() {
        use aps_ai::platform::{ProviderKind, ReportFormat, ReportLanguage};
        let fixture = Fixture::new();
        let workspace = fixture.workspace(SOURCE);
        fixture.grant(&workspace, true);
        let (request, guard) = fixture.enriched(&workspace);
        guard.validate().unwrap();
        let provider = aps_ai::ProviderConfig::new(
            "test",
            "http://127.0.0.1:12345/v1",
            "test-model",
            aps_ai::CredentialReference::new("keychain:test").unwrap(),
        )
        .unwrap();
        let prepared = aps_ai::PreparedRun::new(request.clone(), provider, false).unwrap();
        assert_eq!(prepared.source_candidates().len(), 1);
        let runtime = aps_ai::runtime::AiRuntime::open(&fixture.root).unwrap();
        runtime
            .update_preferences(|preferences| {
                preferences.active_provider = ProviderKind::Custom;
                preferences.model = "test-model".into();
                preferences.connection_mut(ProviderKind::Custom).model = "test-model".into();
                preferences.connection_mut(ProviderKind::Custom).endpoint =
                    "https://example.com/v1/responses".into();
            })
            .unwrap();
        for include_source in [false, true] {
            let frozen = runtime
                .prepare(aps_ai::runtime::PrepareOptions {
                    conversation_id: "source-test".into(),
                    context: Some(request.clone()),
                    question: Some("Explain the retention evidence".into()),
                    include_source,
                    direct_chat: false,
                    parent_turn: None,
                    parent_conversation: None,
                    report_format: ReportFormat::Markdown,
                    report_language: ReportLanguage::English,
                    agent_id: "aps.main".into(),
                })
                .unwrap();
            assert!(frozen.view.history.is_empty());
            assert_eq!(frozen.view.candidate_count, usize::from(include_source));
            assert_eq!(frozen.view.sources.len(), usize::from(include_source));
            assert_eq!(
                frozen
                    .view
                    .permissions
                    .iter()
                    .any(|permission| permission == "SEND_SOURCE_SNIPPETS"),
                include_source
            );
            if include_source {
                let source = &frozen.view.sources[0];
                let dto = &request.candidates[0];
                assert_eq!(source.id, dto.id);
                assert_eq!(source.start_line, dto.start_line);
                assert_eq!(source.end_line, dto.end_line);
                assert_eq!(source.content_digest, Some(dto.content_digest.to_hex()));
                assert_eq!(source.snippet, dto.snippet);
                assert_eq!(source.resolution_confidence.as_deref(), Some("PROBABLE"));
                assert_eq!(source.content_state.as_deref(), Some("CURRENT"));
            } else {
                assert_eq!(
                    frozen.view.evidence_count,
                    request.evidence.len() - request.candidates.len()
                );
            }
            runtime.cancel();
        }
        fixture.grant(&workspace, false);
        assert_eq!(
            guard.validate().unwrap_err(),
            "E_MEMORY_SOURCE_PERMISSION_REVOKED"
        );
    }

    #[test]
    fn repeated_types_share_one_excerpt_with_all_original_memory_evidence_ids() {
        let fixture = Fixture::new();
        let workspace = fixture.workspace(SOURCE);
        fixture.grant(&workspace, true);
        let mut input = request("sample.Widget");
        let mut other = input.evidence[1].clone();
        other.id = "memory-activity-candidate:second".into();
        other.kind = "memory-activity-candidate".into();
        input.evidence.push(other);
        let (request, guard) = fixture.plan(&workspace).enrich(input).unwrap();
        assert_eq!(request.candidates.len(), 1);
        assert_eq!(
            request.evidence.last().unwrap().payload["memoryEvidenceIds"],
            Value::Array(vec![
                "memory-leak-suspect:original-id".into(),
                "memory-activity-candidate:second".into(),
            ])
        );
        guard.unwrap().validate().unwrap();
        let provider = aps_ai::ProviderConfig::new(
            "test",
            "http://127.0.0.1:12345/v1",
            "test-model",
            aps_ai::CredentialReference::new("keychain:test").unwrap(),
        )
        .unwrap();
        assert!(aps_ai::PreparedRun::new(request, provider, false).is_ok());
    }
}
