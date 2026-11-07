//! Read-only projections of a derived Battery report. Missing counter fields
//! never establish measured zero; strings from the artifact remain verbatim.
use aps_battery::{BatteryReport, BatteryReportRun, ResourceKind};

pub const EVIDENCE_PAGE_LIMIT: usize = 100;
pub const QUERY_LIMIT: usize = 128;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum ResourceTab {
    #[default]
    Wakelocks,
    Alarms,
    Jobs,
    Sensors,
    Energy,
    Network,
    History,
    Warnings,
}
impl ResourceTab {
    pub const ALL: [Self; 8] = [
        Self::Wakelocks,
        Self::Alarms,
        Self::Jobs,
        Self::Sensors,
        Self::Energy,
        Self::Network,
        Self::History,
        Self::Warnings,
    ];
    pub fn timer_kind(self) -> Option<ResourceKind> {
        match self {
            Self::Wakelocks => Some(ResourceKind::Wakelock),
            Self::Alarms => Some(ResourceKind::Alarm),
            Self::Jobs => Some(ResourceKind::Job),
            Self::Sensors => Some(ResourceKind::Sensor),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EvidenceId {
    pub tab: ResourceTab,
    pub source_index: usize,
}

#[derive(Debug, Default)]
pub struct BatteryUi {
    pub tab: ResourceTab,
    pub selected_run: Option<String>,
    pub selected_evidence: Option<EvidenceId>,
    pub run_offset: usize,
    pub evidence_offset: usize,
    pub query: String,
}
impl BatteryUi {
    pub fn reset(&mut self) {
        *self = Self::default();
    }
    pub fn select_run(&mut self, id: String) {
        if self.selected_run.as_deref() != Some(id.as_str()) {
            self.selected_run = Some(id);
            self.tab = ResourceTab::Wakelocks;
            self.query.clear();
            self.evidence_offset = 0;
            self.selected_evidence = None;
        }
    }
    pub fn select_tab(&mut self, tab: ResourceTab) {
        self.tab = tab;
        self.query.clear();
        self.evidence_offset = 0;
        self.selected_evidence = None;
    }
    pub fn selected<'a>(&self, report: &'a BatteryReport) -> Option<&'a BatteryReportRun> {
        self.selected_run
            .as_deref()
            .and_then(|id| report.runs().iter().find(|run| run.id == id))
            .or_else(|| report.runs().first())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TimerTotals {
    pub records: usize,
    pub duration_ms: u64,
    pub count: u64,
}
/// These are sums of the timer rows actually present, not total UID residency.
/// Omitted arrays stay unknown; an explicitly present empty array represents
/// zero reported timer records, not independently verified hardware measurement.
pub fn run_stats(run: &BatteryReportRun, kind: ResourceKind) -> Option<TimerTotals> {
    let mut totals = TimerTotals {
        records: 0,
        duration_ms: 0,
        count: 0,
    };
    for timer in run.timers.iter().filter(|timer| timer.kind == kind) {
        totals.records += 1;
        totals.duration_ms = totals.duration_ms.checked_add(timer.duration_ms)?;
        totals.count = totals.count.checked_add(timer.count)?;
    }
    (totals.records > 0 || run.has_timer_evidence(kind)).then_some(totals)
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct MetricStats {
    pub known_count: usize,
    pub missing_count: usize,
    pub median: Option<f64>,
    pub p90: Option<f64>,
    pub maximum: Option<f64>,
}
fn statistics(values: impl Iterator<Item = Option<f64>>) -> MetricStats {
    statistics_finite(values.map(|value| value.filter(|value| *value >= 0.)))
}
fn statistics_finite(values: impl Iterator<Item = Option<f64>>) -> MetricStats {
    let mut samples = Vec::new();
    let mut missing_count = 0;
    for value in values {
        if let Some(value) = value.filter(|v| v.is_finite()) {
            samples.push(value);
        } else {
            missing_count += 1;
        }
    }
    samples.sort_by(f64::total_cmp);
    let quantile = |p: f64| {
        if samples.is_empty() {
            return None;
        }
        let rank = (p * samples.len() as f64).ceil() as usize;
        Some(samples[rank.saturating_sub(1).min(samples.len() - 1)])
    };
    MetricStats {
        known_count: samples.len(),
        missing_count,
        median: quantile(0.5),
        p90: quantile(0.9),
        maximum: samples.last().copied(),
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EnergyIdentity {
    pub component: String,
    pub source: String,
    pub scope: String,
    pub confidence: String,
}
#[derive(Debug, Clone)]
pub struct ReportMetrics {
    pub runs: usize,
    pub wakelock_ms: MetricStats,
    /// All alarm records, never relabeled as wakeup-only alarms.
    pub alarm_count: MetricStats,
    pub network_bytes: MetricStats,
    pub energy_mah: MetricStats,
    /// A scalar energy metric exists only for one unambiguous record per run
    /// with the same component/source/scope/confidence. Components are not summed.
    pub energy_identity: Option<EnergyIdentity>,
    pub energy_records: usize,
    pub temperature_celsius: MetricStats,
    pub thermal_status: MetricStats,
}
pub fn report_metrics(report: &BatteryReport) -> ReportMetrics {
    let runs = report.runs();
    let identity = runs
        .iter()
        .find_map(|run| run.energy.first())
        .map(|energy| EnergyIdentity {
            component: energy.component.clone(),
            source: energy.source.clone(),
            scope: energy.scope.clone(),
            confidence: energy.confidence.clone(),
        });
    let energy_mah = statistics(runs.iter().map(|run| {
        if run.energy.len() != 1 {
            return None;
        }
        let energy = &run.energy[0];
        let identity = identity.as_ref()?;
        (energy.component == identity.component
            && energy.source == identity.source
            && energy.scope == identity.scope
            && energy.confidence == identity.confidence)
            .then_some(energy.energy_mah)
            .flatten()
    }));
    let energy_identity = (energy_mah.known_count > 0).then_some(identity).flatten();
    ReportMetrics {
        runs: runs.len(),
        wakelock_ms: statistics(
            runs.iter()
                .map(|run| run_stats(run, ResourceKind::Wakelock).map(|v| v.duration_ms as f64)),
        ),
        alarm_count: statistics(
            runs.iter()
                .map(|run| run_stats(run, ResourceKind::Alarm).map(|v| v.count as f64)),
        ),
        network_bytes: statistics(runs.iter().map(|run| run.network_bytes.map(|v| v as f64))),
        energy_mah,
        energy_identity,
        energy_records: runs.iter().map(|run| run.energy.len()).sum(),
        temperature_celsius: statistics_finite(runs.iter().map(|run| run.peak_temperature_celsius)),
        thermal_status: statistics(
            runs.iter()
                .map(|run| run.max_thermal_status.map(|v| v as f64)),
        ),
    }
}

pub struct RunPage<'a> {
    pub total: usize,
    pub offset: usize,
    pub rows: Vec<&'a BatteryReportRun>,
}
pub fn run_page(
    report: &BatteryReport,
    offset: usize,
    limit: usize,
) -> Result<RunPage<'_>, String> {
    validate_page(offset, limit)?;
    Ok(RunPage {
        total: report.runs().len(),
        offset,
        rows: report.runs().iter().skip(offset).take(limit).collect(),
    })
}

#[derive(Debug, Clone, PartialEq)]
pub enum EvidenceRow {
    Timer {
        id: EvidenceId,
        kind: ResourceKind,
        name: String,
        duration_ms: u64,
        count: u64,
        confidence: Option<String>,
    },
    Energy {
        id: EvidenceId,
        component: String,
        energy_mah: Option<f64>,
        energy_uws: Option<u64>,
        source: String,
        scope: String,
        confidence: String,
    },
    /// A report aggregate, not a request event or a reconstructed radio counter.
    Network {
        id: EvidenceId,
        bytes: Option<u64>,
    },
    Warning {
        id: EvidenceId,
        message: String,
    },
}
impl EvidenceRow {
    pub fn id(&self) -> EvidenceId {
        match self {
            Self::Timer { id, .. }
            | Self::Energy { id, .. }
            | Self::Network { id, .. }
            | Self::Warning { id, .. } => *id,
        }
    }
}
#[derive(Debug, Clone)]
pub struct EvidencePage {
    pub total: usize,
    pub unfiltered_total: usize,
    pub offset: usize,
    pub rows: Vec<EvidenceRow>,
    /// History and omitted timer arrays are unavailable, not empty measured
    /// tracks or zero activity. Explicit empty arrays remain available/empty.
    pub unavailable: bool,
}
impl EvidencePage {
    pub fn selected(&self, id: Option<EvidenceId>) -> Option<&EvidenceRow> {
        id.and_then(|id| self.rows.iter().find(|row| row.id() == id))
            .or_else(|| self.rows.first())
    }
}
fn validate_page(offset: usize, limit: usize) -> Result<(), String> {
    if limit == 0 || limit > EVIDENCE_PAGE_LIMIT || offset.checked_add(limit).is_none() {
        Err("Battery UI page exceeds supported bounds".into())
    } else {
        Ok(())
    }
}
/// Only a published-page row invokes its constructor. Skipped/nonmatching rows
/// do not clone resource strings; empty queries do not format search documents.
struct EvidenceCollector {
    page: EvidencePage,
    limit: usize,
}
impl EvidenceCollector {
    fn add(&mut self, matches: bool, row: impl FnOnce() -> EvidenceRow) {
        self.page.unfiltered_total += 1;
        if matches {
            if self.page.total >= self.page.offset && self.page.rows.len() < self.limit {
                self.page.rows.push(row());
            }
            self.page.total += 1;
        }
    }
}

/// Filters the entire run's selected resource before bounded materialization;
/// original source indices survive filtering/paging and duplicate display names.
pub fn evidence_page(
    run: &BatteryReportRun,
    tab: ResourceTab,
    query: &str,
    offset: usize,
    limit: usize,
) -> Result<EvidencePage, String> {
    validate_page(offset, limit)?;
    if query.len() > QUERY_LIMIT {
        return Err("Battery evidence search exceeds 128 UTF-8 bytes".into());
    }
    let query = query.trim().to_lowercase();
    let mut output = EvidenceCollector {
        limit,
        page: EvidencePage {
            total: 0,
            unfiltered_total: 0,
            offset,
            rows: Vec::with_capacity(limit),
            unavailable: tab == ResourceTab::History
                || (tab == ResourceTab::Energy && run.energy.is_empty())
                || tab
                    .timer_kind()
                    .is_some_and(|kind| !run.has_timer_evidence(kind)),
        },
    };
    if let Some(kind) = tab.timer_kind() {
        for (index, timer) in run
            .timers
            .iter()
            .enumerate()
            .filter(|(_, timer)| timer.kind == kind)
        {
            let matches = query.is_empty()
                || format!(
                    "{} {} {} {}",
                    timer.name,
                    timer.duration_ms,
                    timer.count,
                    run.timer_confidence
                        .get(index)
                        .and_then(Option::as_deref)
                        .unwrap_or("")
                )
                .to_lowercase()
                .contains(&query);
            output.add(matches, || EvidenceRow::Timer {
                id: EvidenceId {
                    tab,
                    source_index: index,
                },
                kind,
                name: timer.name.clone(),
                duration_ms: timer.duration_ms,
                count: timer.count,
                confidence: run.timer_confidence.get(index).cloned().flatten(),
            });
        }
    } else {
        match tab {
            ResourceTab::Energy => {
                for (index, energy) in run.energy.iter().enumerate() {
                    let matches = query.is_empty()
                        || format!(
                            "{} {} {} {} {:?} {:?}",
                            energy.component,
                            energy.source,
                            energy.scope,
                            energy.confidence,
                            energy.energy_mah,
                            energy.energy_uws
                        )
                        .to_lowercase()
                        .contains(&query);
                    output.add(matches, || EvidenceRow::Energy {
                        id: EvidenceId {
                            tab,
                            source_index: index,
                        },
                        component: energy.component.clone(),
                        energy_mah: energy.energy_mah,
                        energy_uws: energy.energy_uws,
                        source: energy.source.clone(),
                        scope: energy.scope.clone(),
                        confidence: energy.confidence.clone(),
                    });
                }
            }
            ResourceTab::Network => {
                let matches = query.is_empty()
                    || format!("network total bytes {:?}", run.network_bytes)
                        .to_lowercase()
                        .contains(&query);
                output.add(matches, || EvidenceRow::Network {
                    id: EvidenceId {
                        tab,
                        source_index: 0,
                    },
                    bytes: run.network_bytes,
                });
            }
            ResourceTab::Warnings => {
                for (index, warning) in run.warnings.iter().enumerate() {
                    let matches = query.is_empty() || warning.to_lowercase().contains(&query);
                    output.add(matches, || EvidenceRow::Warning {
                        id: EvidenceId {
                            tab,
                            source_index: index,
                        },
                        message: warning.clone(),
                    });
                }
            }
            _ => {}
        }
    }
    Ok(output.page)
}

#[cfg(test)]
mod tests {
    use super::{
        BatteryUi, EVIDENCE_PAGE_LIMIT, EvidenceRow, QUERY_LIMIT, ResourceKind, ResourceTab,
        evidence_page, report_metrics, run_page, run_stats,
    };
    use aps_battery::BatteryReport;
    fn fixture_report(runs: &str) -> BatteryReport {
        BatteryReport::parse_json(
            format!(r#"{{"schemaVersion":1,"sessionId":"report","uid":123,"runs":[{runs}]}}"#)
                .as_bytes(),
        )
        .unwrap()
    }
    #[::core::prelude::v1::test]
    fn omitted_timers_network_energy_and_thermal_are_not_measured_zero() {
        let report = fixture_report(r#"{"runId":"r1","iteration":1,"durationMs":1000}"#);
        let metrics = report_metrics(&report);
        assert_eq!(metrics.runs, 1);
        assert_eq!(metrics.wakelock_ms.median, None);
        assert_eq!(metrics.alarm_count.median, None);
        assert_eq!(metrics.network_bytes.median, None);
        assert_eq!(metrics.energy_mah.median, None);
        assert_eq!(metrics.temperature_celsius.median, None);
        assert_eq!(metrics.thermal_status.median, None);
        assert_eq!(metrics.network_bytes.missing_count, 1);
        assert!(run_stats(&report.runs()[0], ResourceKind::Wakelock).is_none());
        let page = evidence_page(&report.runs()[0], ResourceTab::Network, "", 0, 10).unwrap();
        assert!(matches!(
            page.rows[0],
            EvidenceRow::Network { bytes: None, .. }
        ));
    }
    #[::core::prelude::v1::test]
    fn explicit_zero_is_preserved_and_missing_runs_are_not_in_the_quantile() {
        let report = fixture_report(
            r#"{"runId":"r1","iteration":1,"durationMs":1000,"networkBytes":0,"wakelocks":[{"name":"record","durationMs":0,"count":0}]},{"runId":"r2","iteration":2,"durationMs":1000}"#,
        );
        let metrics = report_metrics(&report);
        assert_eq!(metrics.network_bytes.median, Some(0.));
        assert_eq!(metrics.network_bytes.known_count, 1);
        assert_eq!(metrics.network_bytes.missing_count, 1);
        assert_eq!(metrics.wakelock_ms.median, Some(0.));
    }
    #[::core::prelude::v1::test]
    fn energy_components_and_scope_source_confidence_are_not_blindly_aggregated() {
        let report = fixture_report(
            r#"{"runId":"r1","iteration":1,"durationMs":1000,"energy":[{"component":"total","energyMah":4,"source":"SYSTEM_MODEL","scope":"UID","confidence":"MODELED"},{"component":"cpu","energyMah":2,"source":"SYSTEM_MODEL","scope":"UID","confidence":"MODELED"}]}"#,
        );
        let metrics = report_metrics(&report);
        assert_eq!(metrics.energy_records, 2);
        assert_eq!(metrics.energy_mah.median, None);
        assert!(metrics.energy_identity.is_none());
        let report = fixture_report(
            r#"{"runId":"r1","iteration":1,"durationMs":1000,"energy":[{"component":"cpu","energyMah":4,"source":"alien-source","scope":"unknown-scope","confidence":"EXACT"}]}"#,
        );
        let page = evidence_page(&report.runs()[0], ResourceTab::Energy, "", 0, 10).unwrap();
        assert!(
            matches!(&page.rows[0],EvidenceRow::Energy{source,scope,confidence,..}if source=="alien-source"&&scope=="unknown-scope"&&confidence=="EXACT")
        );
    }
    #[::core::prelude::v1::test]
    fn filtering_happens_before_paging_and_duplicate_names_keep_source_identity() {
        let report = fixture_report(
            r#"{"runId":"r1","iteration":1,"durationMs":1000,"wakelocks":[{"name":"same-symbol","durationMs":5,"count":1},{"name":"other","durationMs":9,"count":4},{"name":"same-symbol","durationMs":10,"count":2}]}"#,
        );
        let page = evidence_page(
            &report.runs()[0],
            ResourceTab::Wakelocks,
            "same-symbol",
            1,
            1,
        )
        .unwrap();
        assert_eq!(page.total, 2);
        assert_eq!(page.unfiltered_total, 3);
        assert_eq!(page.rows.len(), 1);
        assert_eq!(page.rows[0].id().source_index, 2);
    }
    #[::core::prelude::v1::test]
    fn energy_resource_remains_accessible_without_scalar_evidence() {
        let report = fixture_report(r#"{"runId":"r","iteration":1,"durationMs":1000}"#);
        assert!(ResourceTab::ALL.contains(&ResourceTab::Energy));
        let page = evidence_page(&report.runs()[0], ResourceTab::Energy, "", 0, 100).unwrap();
        assert!(page.unavailable);
        assert!(page.rows.is_empty());
        assert_eq!(report_metrics(&report).energy_mah.known_count, 0);
    }

    #[::core::prelude::v1::test]
    fn history_is_explicitly_unavailable_without_source_evidence() {
        let report =
            fixture_report(r#"{"runId":"r1","iteration":1,"durationMs":1000,"networkBytes":0}"#);
        let page = evidence_page(&report.runs()[0], ResourceTab::History, "", 0, 10).unwrap();
        assert!(page.unavailable);
        assert!(page.rows.is_empty());
    }
    #[::core::prelude::v1::test]
    fn nearest_rank_statistics_match_reference_and_keep_signed_temperatures() {
        let report = fixture_report(
            r#"{"runId":"r1","iteration":1,"durationMs":1000,"networkBytes":10,"peakTemperatureCelsius":-10},{"runId":"r2","iteration":2,"durationMs":1000,"networkBytes":20,"peakTemperatureCelsius":-2}"#,
        );
        let metrics = report_metrics(&report);
        assert_eq!(metrics.network_bytes.median, Some(10.));
        assert_eq!(metrics.network_bytes.p90, Some(20.));
        assert_eq!(metrics.temperature_celsius.median, Some(-10.));
        assert_eq!(metrics.temperature_celsius.maximum, Some(-2.));
    }
    #[::core::prelude::v1::test]
    fn explicit_empty_timer_array_differs_from_missing_and_confidence_is_verbatim() {
        let report = fixture_report(
            r#"{"runId":"r1","iteration":1,"durationMs":1000,"wakelocks":[],"alarms":[{"name":"source-symbol","durationMs":3,"count":2,"confidence":"unknown-confidence"}]}"#,
        );
        let run = &report.runs()[0];
        assert_eq!(
            run_stats(run, ResourceKind::Wakelock).unwrap().duration_ms,
            0
        );
        assert!(run_stats(run, ResourceKind::Job).is_none());
        let page = evidence_page(run, ResourceTab::Alarms, "unknown-confidence", 0, 10).unwrap();
        assert_eq!(page.rows.len(), 1);
        assert!(
            matches!(&page.rows[0], EvidenceRow::Timer{confidence:Some(confidence),..} if confidence=="unknown-confidence")
        );
    }

    #[::core::prelude::v1::test]
    fn skipped_and_nonmatching_evidence_never_construct_owned_rows() {
        use super::{EvidenceCollector, EvidenceId, EvidencePage};
        let mut collector = EvidenceCollector {
            limit: 1,
            page: EvidencePage {
                total: 0,
                unfiltered_total: 0,
                offset: 1,
                rows: Vec::new(),
                unavailable: false,
            },
        };
        collector.add(false, || panic!("nonmatching row must not be cloned"));
        collector.add(true, || panic!("row before offset must not be cloned"));
        collector.add(true, || EvidenceRow::Warning {
            id: EvidenceId {
                tab: ResourceTab::Warnings,
                source_index: 2,
            },
            message: "visible".into(),
        });
        collector.add(true, || panic!("row after page limit must not be cloned"));
        assert_eq!(collector.page.total, 3);
        assert_eq!(collector.page.unfiltered_total, 4);
        assert_eq!(collector.page.rows.len(), 1);
        assert_eq!(collector.page.rows[0].id().source_index, 2);
    }

    #[::core::prelude::v1::test]
    fn pages_search_and_run_changes_have_explicit_bounds_and_selection_reset() {
        let report = fixture_report(
            r#"{"runId":"r1","iteration":1,"durationMs":1000},{"runId":"r2","iteration":2,"durationMs":1000}"#,
        );
        for (offset, limit) in [(0, 0), (0, EVIDENCE_PAGE_LIMIT + 1), (usize::MAX, 1)] {
            assert!(run_page(&report, offset, limit).is_err());
            assert!(
                evidence_page(&report.runs()[0], ResourceTab::Warnings, "", offset, limit).is_err()
            );
        }
        assert!(
            evidence_page(
                &report.runs()[0],
                ResourceTab::Warnings,
                &"x".repeat(QUERY_LIMIT + 1),
                0,
                10
            )
            .is_err()
        );
        let mut state = BatteryUi {
            query: "old query".into(),
            evidence_offset: 100,
            tab: ResourceTab::Warnings,
            ..Default::default()
        };
        state.select_run("r2".into());
        assert_eq!(state.selected(&report).unwrap().id, "r2");
        assert!(state.query.is_empty());
        assert_eq!(state.evidence_offset, 0);
        assert_eq!(state.tab, ResourceTab::Wakelocks);
    }
}
