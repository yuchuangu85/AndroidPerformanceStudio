use aps_contracts::SourceDigest;
use std::path::PathBuf;

/// Legacy structural-index format marker. Snapshot index versions use the
/// snapshot creation timestamp, exactly like Compose.
pub const INDEX_VERSION: u32 = 1;
/// Compose LocalSourceProvider MAX_SOURCE_FILE_BYTES.
pub const MAX_SOURCE_FILE_BYTES: u64 = 5 * 1024 * 1024;
/// Compose ignores these directory names (and file names, as their extensions
/// are filtered separately) while walking a local source root.
pub const IGNORED_DIRECTORY_NAMES: &[&str] =
    &[".git", ".gradle", ".idea", "build", "out", "node_modules"];
/// Upper bound for one resolver call; the UI only ever navigates the first
/// usable candidate, so the tail is documentation for the caller.
pub const MAX_RESOLUTION_CANDIDATES: usize = 100;

macro_rules! source_id {
    ($name:ident, $label:literal) => {
        #[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
        pub struct $name(SourceDigest);

        impl $name {
            pub fn from_digest(digest: SourceDigest) -> Self {
                Self(digest)
            }

            pub fn to_hex(self) -> String {
                self.0.to_hex()
            }

            pub fn from_hex(value: &str) -> Result<Self, String> {
                let digest = SourceDigest::from_hex(value)?;
                if digest.to_hex() != value {
                    return Err(concat!($label, " must be canonical lowercase SHA-256 hex").into());
                }
                Ok(Self(digest))
            }
        }

        impl std::fmt::Display for $name {
            fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
                formatter.write_str(&self.0.to_hex())
            }
        }
    };
}

source_id!(SourceWorkspaceId, "source workspace id");
source_id!(SourceSnapshotId, "source snapshot id");
source_id!(ResolutionCandidateId, "resolution candidate id");
source_id!(PerformanceEvidenceId, "performance evidence id");

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum SourceProviderKind {
    Local,
    GitHub,
    Aosp,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SourceProviderConfig {
    Local {
        root: PathBuf,
    },
    GitHub {
        owner: String,
        repository: String,
        git_ref: String,
        credential_key: Option<String>,
    },
    Aosp {
        project: String,
        git_ref: String,
    },
}

impl SourceProviderConfig {
    pub fn kind(&self) -> SourceProviderKind {
        match self {
            Self::Local { .. } => SourceProviderKind::Local,
            Self::GitHub { .. } => SourceProviderKind::GitHub,
            Self::Aosp { .. } => SourceProviderKind::Aosp,
        }
    }

    /// Remote provider identity is validated even though the UI only exposes
    /// local workspaces, so persisted remote rows cannot contain unbounded text.
    pub fn validate(&self) -> Result<(), String> {
        match self {
            Self::Local { root } => {
                if root.as_os_str().is_empty() {
                    return Err("local source root must not be empty".into());
                }
            }
            Self::GitHub {
                owner,
                repository,
                git_ref,
                credential_key,
            } => {
                aps_contracts::validate_text(owner, "GitHub owner", 256)?;
                aps_contracts::validate_text(repository, "GitHub repository", 256)?;
                aps_contracts::validate_text(git_ref, "GitHub ref", 256)?;
                if let Some(key) = credential_key {
                    aps_contracts::validate_text(key, "GitHub credential key", 256)?;
                }
            }
            Self::Aosp { project, git_ref } => {
                aps_contracts::validate_text(project, "AOSP project", 512)?;
                aps_contracts::validate_text(git_ref, "AOSP ref", 256)?;
            }
        }
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SourceWorkspacePhase {
    Registering,
    ResolvingRevision,
    BuildingManifest,
    Indexing,
    Ready,
    Partial,
    Failed,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SourceWorkspace {
    pub id: SourceWorkspaceId,
    pub display_name: String,
    pub config: SourceProviderConfig,
    pub active_snapshot_id: Option<SourceSnapshotId>,
    pub phase: SourceWorkspacePhase,
    pub progress: f32,
    pub message: Option<String>,
    pub allow_ai_source_upload: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceSnapshot {
    pub id: SourceSnapshotId,
    pub workspace_id: SourceWorkspaceId,
    pub immutable_revision: String,
    pub dirty_content_digest: Option<SourceDigest>,
    pub manifest_hash: SourceDigest,
    pub created_unix_ms: u64,
    pub index_version: u64,
    pub index_complete: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum SourceLanguage {
    Kotlin,
    Java,
    Xml,
    C,
    Cpp,
    Other,
}

const SOURCE_EXTENSIONS: &[&str] = &[
    "kt", "kts", "java", "xml", "c", "cc", "cpp", "cxx", "h", "hh", "hpp",
];

impl SourceLanguage {
    /// Compose sourceLanguage(relativePath): extension mapping, unknown -> Other.
    pub fn from_path(path: &str) -> Self {
        let extension = path
            .rsplit_once('.')
            .map(|(_, extension)| extension.to_ascii_lowercase())
            .unwrap_or_default();
        match extension.as_str() {
            "kt" | "kts" => Self::Kotlin,
            "java" => Self::Java,
            "xml" => Self::Xml,
            "c" | "h" => Self::C,
            "cc" | "cpp" | "cxx" | "hh" | "hpp" => Self::Cpp,
            _ => Self::Other,
        }
    }

    /// Provider allowlist, in Compose declaration order.
    pub fn extension_list() -> &'static [&'static str] {
        SOURCE_EXTENSIONS
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceFile {
    pub snapshot_id: SourceSnapshotId,
    pub relative_path: String,
    pub language: SourceLanguage,
    pub content_hash: SourceDigest,
    pub size_bytes: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub enum SourceSymbolKind {
    Package,
    Type,
    Function,
    Method,
    Resource,
    NativeSymbol,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceSymbol {
    pub snapshot_id: SourceSnapshotId,
    pub relative_path: String,
    pub kind: SourceSymbolKind,
    pub qualified_name: String,
    pub signature: Option<String>,
    pub start_line: usize,
    pub end_line: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct SourceRange {
    pub start_line: usize,
    pub end_line: usize,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SourceLocation {
    pub workspace_id: SourceWorkspaceId,
    pub snapshot_id: SourceSnapshotId,
    pub relative_path: String,
    pub range: Option<SourceRange>,
    pub content_hash: SourceDigest,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResolutionConfidence {
    Exact,
    Probable,
    Weak,
}

/// Compose BuildIdentityMatch: an unverified build cannot claim Exact
/// navigation, so the resolver downgrades Exact candidates to Probable.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BuildIdentityMatch {
    Verified,
    Unverified,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolutionCandidate {
    pub id: ResolutionCandidateId,
    pub evidence_id: PerformanceEvidenceId,
    pub location: SourceLocation,
    pub confidence: ResolutionConfidence,
    pub reasons: Vec<String>,
    pub index_version: u64,
    pub index_complete: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SourceResolutionEvidence {
    ManagedSymbol {
        id: PerformanceEvidenceId,
        class_name: Option<String>,
        method_name: String,
        signature: Option<String>,
        resource_path: Option<String>,
    },
    NativeSymbol {
        id: PerformanceEvidenceId,
        symbol_name: String,
        library_path: Option<String>,
        build_id: Option<String>,
        source_path: Option<String>,
        source_line: Option<usize>,
    },
    AndroidResource {
        id: PerformanceEvidenceId,
        resource_type: String,
        resource_name: String,
    },
    TypeName {
        id: PerformanceEvidenceId,
        qualified_name: String,
    },
    SourceFileLine {
        id: PerformanceEvidenceId,
        file_name: String,
        package_hash: u32,
        line: usize,
    },
}

impl SourceResolutionEvidence {
    pub fn id(&self) -> PerformanceEvidenceId {
        match self {
            Self::ManagedSymbol { id, .. }
            | Self::NativeSymbol { id, .. }
            | Self::AndroidResource { id, .. }
            | Self::TypeName { id, .. }
            | Self::SourceFileLine { id, .. } => *id,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SourceContentState {
    Current,
    Stale,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VerifiedSourceContent {
    pub location: SourceLocation,
    pub text: String,
    pub state: SourceContentState,
}

/// Compose ProviderSourceFile: local listings do not pre-hash content.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProviderSourceFile {
    pub relative_path: String,
    pub size_bytes: u64,
    pub content_hash: Option<SourceDigest>,
}
