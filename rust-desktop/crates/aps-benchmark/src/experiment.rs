//! Baseline Profile experiment boundary (port of `BenchmarkExperimentAnalyzer.kt`).
//!
//! A profile comparison is still a benchmark regression report, but it also
//! carries the aggregated profile state and the trace pairs used to inspect a
//! change, so a faster result is never reported without its evidence.
use std::collections::{HashMap, HashSet};
use std::path::PathBuf;

use crate::compare::{RegressionPolicy, RegressionReport, compare};
use crate::model::{BenchmarkCase, BenchmarkRun, ProfileStatus};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TraceArtifactPair {
    pub case_identity: String,
    pub before: Vec<PathBuf>,
    pub after: Vec<PathBuf>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct BaselineProfileComparison {
    pub baseline_run_id: String,
    pub profiled_run_id: String,
    pub report: RegressionReport,
    pub baseline_status: ProfileStatus,
    pub profiled_status: ProfileStatus,
    pub trace_pairs: Vec<TraceArtifactPair>,
}

pub fn compare_baseline_profile(
    before_profile: &BenchmarkRun,
    after_profile: &BenchmarkRun,
    policy: &RegressionPolicy,
) -> Result<BaselineProfileComparison, String> {
    let report = compare(before_profile, after_profile, policy)?;
    Ok(BaselineProfileComparison {
        baseline_run_id: before_profile.id.clone(),
        profiled_run_id: after_profile.id.clone(),
        baseline_status: profile_status(before_profile),
        profiled_status: profile_status(after_profile),
        trace_pairs: trace_pairs(before_profile, after_profile),
        report,
    })
}

fn trace_pairs(before: &BenchmarkRun, after: &BenchmarkRun) -> Vec<TraceArtifactPair> {
    let after_by_identity: HashMap<String, &BenchmarkCase> = after
        .cases
        .iter()
        .map(|case| (case.identity(), case))
        .collect();
    before
        .cases
        .iter()
        .filter_map(|baseline_case| {
            let profiled_case = after_by_identity.get(&baseline_case.identity())?;
            if baseline_case.trace_artifacts.is_empty() && profiled_case.trace_artifacts.is_empty()
            {
                return None;
            }
            Some(TraceArtifactPair {
                case_identity: baseline_case.identity(),
                before: baseline_case.trace_artifacts.clone(),
                after: profiled_case.trace_artifacts.clone(),
            })
        })
        .collect()
}

fn profile_status(run: &BenchmarkRun) -> ProfileStatus {
    let statuses: HashSet<ProfileStatus> = run
        .cases
        .iter()
        .map(|case| {
            case.profile
                .as_ref()
                .map(|profile| profile.status)
                .unwrap_or(ProfileStatus::NotRequested)
        })
        .collect();
    let only = |expected: ProfileStatus| statuses.len() == 1 && statuses.contains(&expected);
    if only(ProfileStatus::Verified) {
        ProfileStatus::Verified
    } else if statuses
        .iter()
        .all(|status| matches!(status, ProfileStatus::Verified | ProfileStatus::Installed))
    {
        ProfileStatus::Installed
    } else if statuses.contains(&ProfileStatus::Missing) {
        ProfileStatus::Missing
    } else if only(ProfileStatus::NotRequested) {
        ProfileStatus::NotRequested
    } else {
        ProfileStatus::Unknown
    }
}
