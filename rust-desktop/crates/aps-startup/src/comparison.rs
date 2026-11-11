//! Compose StartupAnalyzer contract. Comparisons use complete cohorts, not pages.
use crate::{StartupMode, StartupSession};

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, PartialOrd, Ord)]
pub enum Confidence {
    Exact,
    Estimated,
    Inferred,
    #[default]
    Unavailable,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct MetricEvidence {
    pub source: Option<String>,
    pub confidence: Confidence,
    pub unavailable_reason: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RunContext {
    /// Persisted device-local pseudonym, never a raw serial supplied by a report.
    pub device_local_id: String,
    pub package_name: String,
    pub component_name: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct CompilationEvidence {
    pub requested_mode: String,
    pub compiler_filter_after: Option<String>,
    pub profile_state_after: Option<String>,
    pub verified: bool,
    pub profile_source: String,
    pub profile_source_declared: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct EnvironmentEvidence {
    pub device_model: Option<String>,
    pub api_level: Option<u32>,
    pub emulator: Option<bool>,
    pub battery_percent: Option<u32>,
    pub charging: Option<bool>,
    pub thermal_status: Option<u32>,
    pub captured_at: Option<String>,
    pub failures: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Milestone {
    pub kind: String,
    pub elapsed_realtime_ns: Option<u64>,
    pub source: String,
    pub confidence: Confidence,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Phase {
    pub name: &'static str,
    pub start: &'static str,
    pub end: &'static str,
    pub duration_ns: u64,
    pub confidence: Confidence,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct RunEvidence {
    pub context: Option<RunContext>,
    pub compilation: Option<CompilationEvidence>,
    pub environment: Option<EnvironmentEvidence>,
    pub ttid: MetricEvidence,
    pub ttfd: MetricEvidence,
    pub milestones: Vec<Milestone>,
}

impl RunEvidence {
    /// Exported phase arrays are descriptive only; rebuild intervals from the
    /// first available same-source endpoints, just as Compose import does.
    pub fn phases(&self) -> Vec<Phase> {
        [
            ("Process bootstrap", "PROCESS_START", "INITIALIZER_ENTER"),
            ("Agent initialization", "INITIALIZER_ENTER", "AGENT_READY"),
            ("Activity create", "ACTIVITY_PRE_CREATE", "ACTIVITY_CREATED"),
            (
                "Activity to resumed",
                "ACTIVITY_CREATED",
                "ACTIVITY_RESUMED",
            ),
            ("Resumed to first frame", "ACTIVITY_RESUMED", "FIRST_FRAME"),
            ("First frame to fully drawn", "FIRST_FRAME", "FULLY_DRAWN"),
        ]
        .into_iter()
        .filter_map(|(name, start, end)| {
            let first = self
                .milestones
                .iter()
                .find(|m| m.kind == start && m.elapsed_realtime_ns.is_some())?;
            let last = self
                .milestones
                .iter()
                .find(|m| m.kind == end && m.elapsed_realtime_ns.is_some())?;
            if first.source != last.source {
                return None;
            }
            Some(Phase {
                name,
                start,
                end,
                duration_ns: last
                    .elapsed_realtime_ns?
                    .checked_sub(first.elapsed_realtime_ns?)?,
                confidence: first.confidence.max(last.confidence),
            })
        })
        .collect()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ComparisonStatus {
    Incompatible,
    Insufficient,
    Regression,
    Improvement,
    NoChange,
    Inconclusive,
}

#[derive(Debug, Clone, PartialEq)]
pub struct PhaseDifference {
    pub name: &'static str,
    pub median_difference_ms: f64,
    pub confidence: Confidence,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Comparison {
    pub status: ComparisonStatus,
    pub median_difference_ms: Option<f64>,
    pub percent_difference: Option<f64>,
    pub confidence_interval_ms: Option<(f64, f64)>,
    pub practical_threshold_ms: Option<f64>,
    pub reasons: Vec<String>,
    pub phase_differences: Vec<PhaseDifference>,
}

impl Comparison {
    fn unavailable(status: ComparisonStatus, reasons: Vec<String>) -> Self {
        Self {
            status,
            reasons,
            median_difference_ms: None,
            percent_difference: None,
            confidence_interval_ms: None,
            practical_threshold_ms: None,
            phase_differences: Vec::new(),
        }
    }
}

impl StartupSession {
    pub fn compare(
        &self,
        baseline: &Self,
        practical_threshold_percent: f64,
    ) -> Result<Comparison, String> {
        if !practical_threshold_percent.is_finite()
            || !(0.0..=100.0).contains(&practical_threshold_percent)
        {
            return Err("Practical threshold must be between 0 and 100 percent".into());
        }
        let reasons = self.comparability_reasons(baseline);
        if !reasons.is_empty() {
            return Ok(Comparison::unavailable(
                ComparisonStatus::Incompatible,
                reasons,
            ));
        }
        let current: Vec<_> = self
            .runs
            .iter()
            .filter_map(|r| r.ttid.milliseconds.map(|v| v as f64))
            .collect();
        let previous: Vec<_> = baseline
            .runs
            .iter()
            .filter_map(|r| r.ttid.milliseconds.map(|v| v as f64))
            .collect();
        if current.len() < 3 || previous.len() < 3 {
            return Ok(Comparison::unavailable(
                ComparisonStatus::Insufficient,
                vec!["At least 3 TTID samples are required in each cohort.".into()],
            ));
        }
        let center = median(&previous);
        if center <= 0.0 {
            return Ok(Comparison::unavailable(
                ComparisonStatus::Insufficient,
                vec!["Baseline TTID median must be positive.".into()],
            ));
        }
        let difference = median(&current) - center;
        let threshold = center * practical_threshold_percent / 100.0;
        let interval = bca(&current, &previous);
        let status = if interval.0 > threshold {
            ComparisonStatus::Regression
        } else if interval.1 < -threshold {
            ComparisonStatus::Improvement
        } else if interval.0 >= -threshold && interval.1 <= threshold {
            ComparisonStatus::NoChange
        } else {
            ComparisonStatus::Inconclusive
        };
        let current_phases: Vec<_> = self
            .runs
            .iter()
            .map(|r| self.evidence[&r.id].phases())
            .collect();
        let baseline_phases: Vec<_> = baseline
            .runs
            .iter()
            .map(|r| baseline.evidence[&r.id].phases())
            .collect();
        let mut phase_differences = Vec::new();
        for phase in &current_phases[0] {
            let left: Vec<_> = current_phases
                .iter()
                .filter_map(|p| p.iter().find(|p| p.name == phase.name))
                .collect();
            let right: Vec<_> = baseline_phases
                .iter()
                .filter_map(|p| p.iter().find(|p| p.name == phase.name))
                .collect();
            if left.len() != current.len() || right.len() != previous.len() {
                continue;
            }
            let confidence = left
                .iter()
                .chain(&right)
                .map(|p| p.confidence)
                .max()
                .unwrap();
            if confidence == Confidence::Unavailable {
                continue;
            }
            phase_differences.push(PhaseDifference {
                name: phase.name,
                confidence,
                median_difference_ms: median(
                    &left
                        .iter()
                        .map(|p| p.duration_ns as f64 / 1_000_000.0)
                        .collect::<Vec<_>>(),
                ) - median(
                    &right
                        .iter()
                        .map(|p| p.duration_ns as f64 / 1_000_000.0)
                        .collect::<Vec<_>>(),
                ),
            });
        }
        Ok(Comparison {
            status,
            median_difference_ms: Some(difference),
            percent_difference: Some(difference / center * 100.0),
            confidence_interval_ms: Some(interval),
            practical_threshold_ms: Some(threshold),
            reasons: Vec::new(),
            phase_differences,
        })
    }

    fn comparability_reasons(&self, baseline: &Self) -> Vec<String> {
        let runs: Vec<_> = self.runs.iter().chain(&baseline.runs).collect();
        let evidence: Vec<_> = self
            .runs
            .iter()
            .map(|r| self.evidence.get(&r.id))
            .chain(baseline.runs.iter().map(|r| baseline.evidence.get(&r.id)))
            .collect();
        let mut reasons = Vec::new();
        let contexts: Vec<_> = evidence
            .iter()
            .map(|e| e.and_then(|e| e.context.as_ref()))
            .collect();
        if contexts.iter().any(|c| {
            c.is_none_or(|c| {
                c.device_local_id.is_empty()
                    || c.package_name.is_empty()
                    || c.component_name.is_empty()
            })
        }) || contexts.windows(2).any(|c| c[0] != c[1])
        {
            reasons.push("Device or target identity differs or is missing.");
        }
        if runs
            .iter()
            .any(|r| r.observed_mode == StartupMode::Unknown || r.requested_mode != r.observed_mode)
            || runs.windows(2).any(|r| {
                r[0].requested_mode != r[1].requested_mode
                    || r[0].observed_mode != r[1].observed_mode
            })
        {
            reasons.push("Requested or observed startup mode differs.");
        }
        let compilation: Vec<_> = evidence
            .iter()
            .map(|e| e.and_then(|e| e.compilation.as_ref()))
            .collect();
        if compilation.iter().any(|c| {
            c.is_none_or(|c| {
                !c.verified
                    || !["CURRENT", "RESET", "VERIFY", "SPEED_PROFILE", "SPEED"]
                        .contains(&c.requested_mode.as_str())
                    || c.compiler_filter_after
                        .as_ref()
                        .is_none_or(|v| v.trim().is_empty())
                    || c.profile_state_after
                        .as_ref()
                        .is_none_or(|v| v.trim().is_empty())
                    || ![
                        "UNVERIFIED",
                        "BASELINE_PROFILE_PLUGIN",
                        "MACROBENCHMARK",
                        "BUILD_VARIANT",
                    ]
                    .contains(&c.profile_source.as_str())
                    || (c.requested_mode == "SPEED_PROFILE" && c.profile_source == "UNVERIFIED")
            })
        }) || compilation.windows(2).any(|c| {
            c[0].zip(c[1]).is_some_and(|(a, b)| {
                (
                    &a.requested_mode,
                    &a.compiler_filter_after,
                    &a.profile_state_after,
                    &a.profile_source,
                ) != (
                    &b.requested_mode,
                    &b.compiler_filter_after,
                    &b.profile_state_after,
                    &b.profile_source,
                )
            })
        }) {
            reasons.push("Verified startup compilation state differs or is missing.");
        }
        if compilation
            .iter()
            .flatten()
            .any(|c| c.requested_mode == "SPEED_PROFILE" && !c.profile_source_declared)
        {
            reasons.push("speed-profile samples do not have a declared Profile artifact source.");
        }
        let environment: Vec<_> = evidence
            .iter()
            .map(|e| e.and_then(|e| e.environment.as_ref()))
            .collect();
        if environment.iter().any(|e| {
            e.is_none_or(|e| {
                e.device_model.as_ref().is_none_or(|v| v.trim().is_empty())
                    || e.api_level.is_none_or(|v| v == 0)
                    || e.emulator.is_none()
                    || e.battery_percent.is_none_or(|v| v > 100)
                    || e.charging.is_none()
                    || e.thermal_status.is_none_or(|v| v > 6)
                    || e.captured_at.as_ref().is_none_or(|v| v.trim().is_empty())
                    || !e.failures.is_empty()
            })
        }) || environment.windows(2).any(|e| {
            e[0].zip(e[1]).is_some_and(|(a, b)| {
                (&a.device_model, a.api_level, a.emulator)
                    != (&b.device_model, b.api_level, b.emulator)
            })
        }) {
            reasons.push("Startup environment identity differs or is missing.");
        }
        if environment
            .iter()
            .flatten()
            .any(|e| e.emulator == Some(true))
        {
            reasons.push(
                "Emulator runs are diagnostic only and are excluded from regression decisions.",
            );
        }
        if environment
            .iter()
            .flatten()
            .any(|e| e.battery_percent.is_some_and(|v| v < 15) && e.charging != Some(true))
        {
            reasons.push("At least one run was captured on low battery while not charging.");
        }
        if environment
            .iter()
            .flatten()
            .any(|e| e.thermal_status.is_some_and(|v| v >= 3))
        {
            reasons.push("At least one run was thermally constrained.");
        }
        if runs.iter().any(|r| r.ttid.milliseconds.is_none()) {
            reasons.push("TTID availability differs or is incomplete.");
        }
        let metrics: Vec<_> = evidence.iter().map(|e| e.map(|e| &e.ttid)).collect();
        if metrics.iter().any(|m| {
            m.is_none_or(|m| {
                m.confidence == Confidence::Unavailable
                    || !m.source.as_deref().is_some_and(|s| {
                        ["AM_START", "AGENT", "EVENT_LOG", "PERFETTO"].contains(&s)
                    })
            })
        }) || metrics.windows(2).any(|m| {
            m[0].zip(m[1])
                .is_some_and(|(a, b)| (&a.source, a.confidence) != (&b.source, b.confidence))
        }) {
            reasons.push("TTID evidence source or confidence differs or is unavailable.");
        }
        reasons.into_iter().map(str::to_owned).collect()
    }
}

fn median(values: &[f64]) -> f64 {
    let mut values = values.to_vec();
    let mid = values.len() / 2;
    let even = values.len().is_multiple_of(2);
    let (lower, center, _) = values.select_nth_unstable_by(mid, f64::total_cmp);
    if even {
        (lower.iter().copied().max_by(f64::total_cmp).unwrap() + *center) / 2.0
    } else {
        *center
    }
}

fn bca(current: &[f64], baseline: &[f64]) -> (f64, f64) {
    let current_median = median(current);
    let baseline_median = median(baseline);
    let observed = current_median - baseline_median;
    let mut random = ComposeRandom::new();
    let mut bootstrap = Vec::with_capacity(2000);
    let mut left = vec![0.; current.len()];
    let mut right = vec![0.; baseline.len()];
    for _ in 0..2000 {
        for value in &mut left {
            *value = current[random.index(current.len())];
        }
        for value in &mut right {
            *value = baseline[random.index(baseline.len())];
        }
        bootstrap.push(median(&left) - median(&right));
    }
    bootstrap.sort_unstable_by(f64::total_cmp);
    let less =
        (bootstrap.iter().filter(|v| **v < observed).count() as f64).clamp(0.5, 1999.5) / 2000.;
    let z0 = inverse_normal(less);
    // Sorted leave-one-out medians avoid the quadratic allocation in Compose.
    let jackknife: Vec<_> = leave_one_out(current)
        .into_iter()
        .map(|v| v - baseline_median)
        .chain(
            leave_one_out(baseline)
                .into_iter()
                .map(|v| current_median - v),
        )
        .collect();
    let mean = jackknife.iter().sum::<f64>() / jackknife.len() as f64;
    let numerator = jackknife.iter().map(|v| (mean - v).powi(3)).sum::<f64>();
    let denominator = 6.
        * jackknife
            .iter()
            .map(|v| (mean - v).powi(2))
            .sum::<f64>()
            .powf(1.5);
    let acceleration = if denominator == 0. {
        0.
    } else {
        numerator / denominator
    };
    let adjusted = |alpha| {
        let z = inverse_normal(alpha);
        normal_cdf(z0 + (z0 + z) / (1. - acceleration * (z0 + z)))
    };
    let percentile = |q: f64| {
        let position = q.clamp(0., 1.) * 1999.;
        let lower = position as usize;
        let upper = (lower + 1).min(1999);
        bootstrap[lower] + (bootstrap[upper] - bootstrap[lower]) * (position - lower as f64)
    };
    (percentile(adjusted(0.025)), percentile(adjusted(0.975)))
}

fn leave_one_out(values: &[f64]) -> Vec<f64> {
    let mut indexed: Vec<_> = values.iter().copied().enumerate().collect();
    indexed.sort_by(|a, b| a.1.total_cmp(&b.1));
    let mut result = vec![0.; values.len()];
    for (removed, (index, _)) in indexed.iter().enumerate() {
        let get = |i: usize| indexed[if i >= removed { i + 1 } else { i }].1;
        let middle = (values.len() - 1) / 2;
        result[*index] = if (values.len() - 1).is_multiple_of(2) {
            (get(middle - 1) + get(middle)) / 2.
        } else {
            get(middle)
        };
    }
    result
}

// Kotlin Random(0), including its warm-up and bounded-int rejection sequence.
// This keeps BCa intervals reproducible against the actual Compose analyzer.
struct ComposeRandom {
    state: [u32; 5],
    addend: u32,
}
impl ComposeRandom {
    fn new() -> Self {
        let mut random = Self {
            state: [0, 0, 0, 0, u32::MAX],
            addend: 0,
        };
        for _ in 0..64 {
            random.next();
        }
        random
    }
    fn next(&mut self) -> u32 {
        let [x, y, z, w, v] = self.state;
        let t = x ^ (x >> 2);
        let t = t ^ (t << 1) ^ v ^ (v << 4);
        self.state = [y, z, w, v, t];
        self.addend = self.addend.wrapping_add(362437);
        t.wrapping_add(self.addend)
    }
    fn index(&mut self, bound: usize) -> usize {
        let n = bound as u32;
        if n.is_power_of_two() {
            let bits = n.trailing_zeros();
            let value = self.next();
            return if bits == 0 {
                0
            } else {
                (value >> (32 - bits)) as usize
            };
        }
        loop {
            let bits = self.next() >> 1;
            let value = bits % n;
            if (bits.wrapping_sub(value).wrapping_add(n - 1) as i32) >= 0 {
                return value as usize;
            }
        }
    }
}

fn inverse_normal(probability: f64) -> f64 {
    let p = probability.clamp(1e-12, 1. - 1e-12);
    let a = [
        -39.69683028665376,
        220.9460984245205,
        -275.9285104469687,
        138.357751867269,
        -30.66479806614716,
        2.506628277459239,
    ];
    let b = [
        -54.47609879822406,
        161.5858368580409,
        -155.6989798598866,
        66.80131188771972,
        -13.28068155288572,
    ];
    let c = [
        -0.007784894002430293,
        -0.3223964580411365,
        -2.400758277161838,
        -2.549732539343734,
        4.374664141464968,
        2.938163982698783,
    ];
    let d = [
        0.007784695709041462,
        0.3224671290700398,
        2.445134137142996,
        3.754408661907416,
    ];
    if p < 0.02425 {
        let q = (-2. * p.ln()).sqrt();
        return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
            / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.);
    }
    if p > 0.97575 {
        return -inverse_normal(1. - p);
    }
    let q = p - 0.5;
    let r = q * q;
    (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
        / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.)
}

fn normal_cdf(value: f64) -> f64 {
    let x = value.abs();
    let t = 1. / (1. + 0.2316419 * x);
    let density = (-x * x / 2.).exp() / (2. * std::f64::consts::PI).sqrt();
    let tail = density
        * t
        * (0.319381530
            + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
    if value >= 0. { 1. - tail } else { tail }
}
