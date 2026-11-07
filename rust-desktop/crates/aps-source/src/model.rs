use aps_contracts::SourceDigest;

pub const INDEX_VERSION: u32 = 1;
pub const MAX_FILE_PAGE: usize = 512;
pub const MAX_RESOLUTION_CANDIDATES: usize = 100;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SourceLimits {
    pub max_files: usize,
    pub max_file_bytes: u64,
    pub max_total_bytes: u64,
    pub max_entries: usize,
    pub max_depth: usize,
    pub max_symbols: usize,
}
impl Default for SourceLimits {
    fn default() -> Self {
        Self {
            max_files: 5000,
            max_file_bytes: 1024 * 1024,
            max_total_bytes: 64 * 1024 * 1024,
            max_entries: 50_000,
            max_depth: 32,
            max_symbols: 50_000,
        }
    }
}
impl SourceLimits {
    pub fn validate(&self) -> Result<(), String> {
        if self.max_files == 0
            || self.max_files > 20_000
            || self.max_file_bytes == 0
            || self.max_file_bytes > 5 * 1024 * 1024
            || self.max_total_bytes == 0
            || self.max_total_bytes > 256 * 1024 * 1024
            || self.max_entries == 0
            || self.max_entries > 200_000
            || self.max_depth == 0
            || self.max_depth > 64
            || self.max_symbols == 0
            || self.max_symbols > 200_000
        {
            return Err("source index limits exceed supported nonzero bounds".into());
        }
        Ok(())
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SourceLanguage {
    Kotlin,
    Java,
    Xml,
    C,
    Cpp,
    Rust,
}
impl SourceLanguage {
    pub fn from_path(path: &str) -> Option<Self> {
        match path.rsplit('.').next()?.to_ascii_lowercase().as_str() {
            "kt" | "kts" => Some(Self::Kotlin),
            "java" => Some(Self::Java),
            "xml" => Some(Self::Xml),
            "c" | "h" => Some(Self::C),
            "cc" | "cpp" | "cxx" | "hh" | "hpp" => Some(Self::Cpp),
            "rs" => Some(Self::Rust),
            _ => None,
        }
    }
}
macro_rules! source_id {
    ($name:ident) => {
        #[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
        pub struct $name(pub(crate) SourceDigest);
        impl $name {
            pub fn to_hex(self) -> String {
                self.0.to_hex()
            }
        }
    };
}
source_id!(SourceWorkspaceId);
source_id!(SourceSnapshotId);
source_id!(ResolutionCandidateId);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceFile {
    pub relative_path: String,
    pub content_hash: SourceDigest,
    pub language: SourceLanguage,
    pub byte_length: u64,
    pub line_count: usize,
    /// Directory segment preceding src/, or top directory as a discovery hint.
    /// This is not a verified Gradle/CMake module identity.
    pub module: String,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceFilesPage {
    pub total: usize,
    pub offset: usize,
    pub rows: Vec<SourceFile>,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SourceSymbolKind {
    Type,
    Function,
    Method,
    Resource,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceSymbol {
    pub relative_path: String,
    pub name: String,
    pub qualified_name: String,
    pub kind: SourceSymbolKind,
    pub line: usize,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceLocation {
    pub workspace_id: SourceWorkspaceId,
    pub snapshot_id: SourceSnapshotId,
    pub relative_path: String,
    pub content_hash: SourceDigest,
    pub start_line: usize,
    pub end_line: usize,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResolutionConfidence {
    Probable,
    Weak,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolutionCandidate {
    pub id: ResolutionCandidateId,
    pub location: SourceLocation,
    pub confidence: ResolutionConfidence,
    pub reasons: Vec<String>,
    pub index_version: u32,
    pub index_complete: bool,
    pub(crate) evidence: ResolutionQuery,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolutionQuery {
    pub symbol: Option<String>,
    pub file_name: Option<String>,
    pub module: Option<String>,
    pub limit: usize,
}
impl Default for ResolutionQuery {
    fn default() -> Self {
        Self {
            symbol: None,
            file_name: None,
            module: None,
            limit: 20,
        }
    }
}
impl ResolutionQuery {
    pub fn validate(&self) -> Result<(), String> {
        if self.limit == 0 || self.limit > MAX_RESOLUTION_CANDIDATES {
            return Err("resolution limit must be between 1 and 100".into());
        }
        if self.symbol.is_none() && self.file_name.is_none() {
            return Err("resolution requires explicit symbol or filename evidence".into());
        }
        for value in [&self.symbol, &self.file_name, &self.module]
            .into_iter()
            .flatten()
        {
            aps_contracts::validate_text(value, "resolution evidence", 512)?;
        }
        if self
            .file_name
            .as_ref()
            .is_some_and(|name| name.contains(['/', '\\']))
        {
            return Err("filename evidence must be a basename, not a path".into());
        }
        Ok(())
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SnippetLimits {
    pub max_lines: usize,
    pub max_bytes: usize,
}
impl Default for SnippetLimits {
    fn default() -> Self {
        Self {
            max_lines: 200,
            max_bytes: 64 * 1024,
        }
    }
}
impl SnippetLimits {
    pub fn validate(&self) -> Result<(), String> {
        if self.max_lines == 0
            || self.max_lines > 1000
            || self.max_bytes == 0
            || self.max_bytes > 256 * 1024
        {
            return Err("source snippet limits exceed supported nonzero bounds".into());
        }
        Ok(())
    }
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceSnippet {
    pub location: SourceLocation,
    pub text: String,
    pub total_file_lines: usize,
    pub start_line: usize,
    pub end_line: usize,
    pub truncated: bool,
}
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct IndexCoverage {
    pub visited_entries: usize,
    pub indexed_files: usize,
    pub indexed_bytes: u64,
    pub indexed_symbols: usize,
    pub excluded_entries: usize,
    pub omitted_files: usize,
    pub unreadable_entries: usize,
    pub file_budget_exhausted: bool,
    pub byte_budget_exhausted: bool,
    pub entry_budget_exhausted: bool,
    pub depth_budget_exhausted: bool,
    pub symbol_budget_exhausted: bool,
}
impl IndexCoverage {
    pub fn manifest_complete(&self) -> bool {
        !self.file_budget_exhausted
            && !self.byte_budget_exhausted
            && !self.entry_budget_exhausted
            && !self.depth_budget_exhausted
            && self.unreadable_entries == 0
            && self.omitted_files == 0
    }
    /// Heuristic symbol discovery is never a compiler-complete structural index.
    pub fn structurally_complete(&self) -> bool {
        false
    }
}
