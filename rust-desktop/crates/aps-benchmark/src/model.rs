//! Evidence-bound AndroidX Benchmark model.
//!
//! Every field is the value the JSON actually declared: a missing value stays
//! missing, an imported value is never re-derived, and a local Path only
//! identifies declared evidence, it never authorizes opening it.
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use aps_contracts::SourceDigest;

use crate::MAX_IMPORT_BYTES;
use crate::parser;
use crate::util::now_rfc3339;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum MetricDirection {
    LowerIsBetter,
    HigherIsBetter,
    Unknown,
}

impl MetricDirection {
    pub fn label(self) -> &'static str {
        match self {
            Self::LowerIsBetter => "LOWER_IS_BETTER",
            Self::HigherIsBetter => "HIGHER_IS_BETTER",
            Self::Unknown => "UNKNOWN",
        }
    }
}

/// How strongly the imported numbers support the comparison.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum Confidence {
    Exact,
    Derived,
    Inferred,
    Partial,
    #[default]
    Unknown,
}

impl Confidence {
    /// Confidence ordering used to decide whether a regression may block a release.
    pub fn rank(self) -> u8 {
        match self {
            Self::Exact => 4,
            Self::Derived => 3,
            Self::Inferred => 2,
            Self::Partial => 1,
            Self::Unknown => 0,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Exact => "EXACT",
            Self::Derived => "DERIVED",
            Self::Inferred => "INFERRED",
            Self::Partial => "PARTIAL",
            Self::Unknown => "UNKNOWN",
        }
    }
}

/// Workload shape used to keep startup and interaction results comparable.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum Scenario {
    Startup,
    Scroll,
    Animation,
    PageSwitch,
    Custom,
    #[default]
    Unknown,
}

impl Scenario {
    pub fn label(self) -> &'static str {
        match self {
            Self::Startup => "STARTUP",
            Self::Scroll => "SCROLL",
            Self::Animation => "ANIMATION",
            Self::PageSwitch => "PAGE_SWITCH",
            Self::Custom => "CUSTOM",
            Self::Unknown => "UNKNOWN",
        }
    }

    /// Compose normalisation of an explicit scenario value.
    pub(crate) fn from_explicit(raw: &str) -> Option<Self> {
        let normalized = raw.trim().to_lowercase().replace(['-', ' '], "_");
        match normalized.as_str() {
            "startup" | "cold" | "warm" | "hot" => Some(Self::Startup),
            "scroll" | "scrolling" | "fling" => Some(Self::Scroll),
            "animation" | "animate" => Some(Self::Animation),
            "page_switch" | "page switch" | "navigation" | "navigate" => Some(Self::PageSwitch),
            "custom" => Some(Self::Custom),
            _ => None,
        }
    }

    /// Scenario inferred from a benchmark case name when no explicit value exists.
    pub(crate) fn infer_from_name(raw_name: &str) -> Self {
        let normalized = raw_name.to_lowercase();
        let contains = |words: &[&str]| words.iter().any(|word| normalized.contains(word));
        if contains(&["startup", "cold", "warm", "hot"]) {
            Self::Startup
        } else if contains(&["scroll", "fling"]) {
            Self::Scroll
        } else if contains(&["animation", "animate"]) {
            Self::Animation
        } else if contains(&["page", "navigation", "navigate"]) {
            Self::PageSwitch
        } else {
            Self::Unknown
        }
    }
}

/// Baseline Profile evidence declared by the run.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum ProfileStatus {
    #[default]
    NotRequested,
    Installed,
    Verified,
    Missing,
    Unknown,
}

impl ProfileStatus {
    pub fn label(self) -> &'static str {
        match self {
            Self::NotRequested => "NOT_REQUESTED",
            Self::Installed => "INSTALLED",
            Self::Verified => "VERIFIED",
            Self::Missing => "MISSING",
            Self::Unknown => "UNKNOWN",
        }
    }

    pub(crate) fn from_text(raw: &str) -> Self {
        match raw.trim().to_lowercase().as_str() {
            "installed" | "applied" | "enabled" => Self::Installed,
            "verified" | "compiled" | "ready" => Self::Verified,
            "missing" | "not_found" | "not found" => Self::Missing,
            _ => Self::Unknown,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct BenchmarkDevice {
    pub model: Option<String>,
    pub brand: Option<String>,
    pub api_level: Option<u32>,
    pub os_version: Option<String>,
    pub abi: Option<String>,
    pub fingerprint: Option<String>,
    pub core_count: Option<u32>,
    pub physical_device: Option<bool>,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct BenchmarkBuild {
    pub target_package: Option<String>,
    pub version_name: Option<String>,
    pub version_code: Option<u64>,
    pub variant: Option<String>,
    pub git_commit: Option<String>,
    pub git_branch: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Metric {
    pub name: String,
    pub unit: String,
    pub direction: MetricDirection,
    pub samples: Vec<f64>,
    pub minimum: Option<f64>,
    pub median: Option<f64>,
    pub maximum: Option<f64>,
    pub confidence: Confidence,
    pub source_fields: BTreeMap<String, String>,
}

impl Metric {
    /// The declared median or the median of the declared raw samples; never a fabricated value.
    pub fn representative_value(&self) -> Option<f64> {
        self.median
            .filter(|value| value.is_finite())
            .or_else(|| crate::uncertainty::median(&self.samples))
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ProfileEvidence {
    pub status: ProfileStatus,
    pub source: Option<String>,
    pub artifact: Option<PathBuf>,
    pub profile_hash: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Default)]
pub struct BenchmarkCase {
    pub class_name: String,
    pub test_name: String,
    pub package_name: Option<String>,
    pub compilation_mode: Option<String>,
    pub startup_mode: Option<String>,
    pub iteration_count: Option<u32>,
    pub scenario: Scenario,
    pub profile: Option<ProfileEvidence>,
    pub trace_artifacts: Vec<PathBuf>,
    pub metrics: Vec<Metric>,
}

impl BenchmarkCase {
    /// Stable identity shared by both runs: `className#testName`.
    pub fn identity(&self) -> String {
        format!("{}#{}", self.class_name, self.test_name)
    }

    pub fn metric(&self, name: &str) -> Option<&Metric> {
        self.metrics.iter().find(|metric| metric.name == name)
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct BenchmarkRun {
    /// SHA-256 of the imported JSON bytes: the same bytes are the same run.
    pub id: String,
    pub source_path: PathBuf,
    /// RFC3339 UTC, fixed width so history sorting is stable.
    pub imported_at: String,
    pub data_version: Option<u32>,
    pub library_version: Option<String>,
    pub device: BenchmarkDevice,
    pub build: BenchmarkBuild,
    pub warnings: Vec<String>,
    pub cases: Vec<BenchmarkCase>,
}

impl BenchmarkRun {
    pub(crate) fn from_parser(run: Self, id: String, source_path: PathBuf) -> Self {
        Self {
            id,
            source_path,
            ..run
        }
    }

    pub fn open(path: &Path, max_bytes: u64) -> Result<Self, String> {
        let display = path.display();
        let metadata = std::fs::metadata(path)
            .map_err(|_| format!("Benchmark JSON does not exist: {display}"))?;
        if !metadata.is_file() {
            return Err(format!("Benchmark JSON is not a regular file: {display}"));
        }
        if metadata.len() > max_bytes {
            return Err(format!("Benchmark JSON exceeds {max_bytes} bytes"));
        }
        let bytes =
            std::fs::read(path).map_err(|_| format!("Cannot read benchmark JSON: {display}"))?;
        if bytes.len() as u64 > max_bytes {
            return Err(format!("Benchmark JSON exceeds {max_bytes} bytes"));
        }
        // The imported source and its resolved evidence paths are absolute, like the Compose parser.
        let source_path = crate::util::absolute_normalize(path);
        let base_dir = source_path.parent().map(Path::to_path_buf);
        Self::parse_bytes(&bytes, base_dir.as_deref(), Some(source_path))
    }

    pub fn parse_json(bytes: &[u8]) -> Result<Self, String> {
        Self::parse_bytes(bytes, None, None)
    }

    fn parse_bytes(
        bytes: &[u8],
        base_dir: Option<&Path>,
        source_path: Option<PathBuf>,
    ) -> Result<Self, String> {
        if bytes.len() as u64 > MAX_IMPORT_BYTES {
            return Err(format!("Benchmark JSON exceeds {MAX_IMPORT_BYTES} bytes"));
        }
        let digest = SourceDigest::hash_reader(bytes, MAX_IMPORT_BYTES)
            .map_err(|_| "Cannot hash benchmark JSON".to_string())?;
        let root = aps_json::parse_slice(bytes, aps_json::Limits::default())
            .map_err(|error| error.to_string())?;
        let run = parser::document(root, base_dir)?;
        Ok(Self::from_parser(
            run,
            digest.to_hex(),
            source_path.unwrap_or_default(),
        ))
    }

    pub fn cases(&self) -> &[BenchmarkCase] {
        &self.cases
    }

    pub fn case(&self, identity: &str) -> Option<&BenchmarkCase> {
        self.cases.iter().find(|case| case.identity() == identity)
    }
}

/// One run with a stable, sortable import timestamp.
pub(crate) fn new_run(
    source_path: PathBuf,
    data_version: Option<u32>,
    library_version: Option<String>,
    device: BenchmarkDevice,
    build: BenchmarkBuild,
    warnings: Vec<String>,
    cases: Vec<BenchmarkCase>,
) -> BenchmarkRun {
    BenchmarkRun {
        id: String::new(),
        source_path,
        imported_at: now_rfc3339(),
        data_version,
        library_version,
        device,
        build,
        warnings,
        cases,
    }
}
