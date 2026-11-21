use super::*;

pub(super) struct PreparedFinding {
    pub index: usize,
    pub severity: FindingSeverity,
    pub problem: SharedString,
    pub description: SharedString,
    pub node_label: SharedString,
}

pub(crate) struct Snapshot {
    archive: Option<Arc<InspectorArchive>>,
    window_index: usize,
    report: Arc<LayoutAnalysis>,
    language: Language,
    hide_invisible: bool,
    pub rows: Vec<PreparedFinding>,
    tone_rows: [Vec<usize>; 4],
    pub empty: TextKey,
    pub bounded: Option<SharedString>,
}

impl Snapshot {
    pub fn indices(&self, tone: FindingsTone) -> &[usize] {
        &self.tone_rows[tone_index(tone)]
    }

    pub fn count(&self, tone: FindingsTone) -> usize {
        self.indices(tone).len()
    }

    fn build(
        archive: Option<&Arc<InspectorArchive>>,
        window_index: usize,
        report: &Arc<LayoutAnalysis>,
        language: Language,
        hide_invisible: bool,
    ) -> Self {
        let window = archive.and_then(|archive| archive.windows.get(window_index));
        let projected = project(report, window, hide_invisible);
        let numbers = window
            .map(|window| hierarchy_numbers(&window.nodes))
            .unwrap_or_default();
        let mut tone_rows: [Vec<usize>; 4] = Default::default();
        let rows = projected
            .into_iter()
            .map(|projected| {
                let node = &window.expect("projected findings require a window").nodes
                    [projected.finding.node_index];
                let (problem, description) = format_finding(language, projected.finding.rule, node);
                PreparedFinding {
                    index: projected.index,
                    severity: projected.finding.severity,
                    problem: problem.into(),
                    description: description.into(),
                    node_label: numbers[projected.finding.node_index].clone().into(),
                }
            })
            .collect::<Vec<_>>();
        for (index, row) in rows.iter().enumerate() {
            tone_rows[0].push(index);
            let tone = match row.severity {
                FindingSeverity::Info => FindingsTone::Info,
                FindingSeverity::Warning => FindingsTone::Warning,
                FindingSeverity::Error => FindingsTone::Error,
            };
            tone_rows[tone_index(tone)].push(index);
        }
        let bounded =
            (report.omitted_findings > 0 || report.overlap_parents_skipped > 0).then(|| {
                language
                    .format(
                        TextKey::InspectorFindingsBounded,
                        &[
                            ("omitted", &report.omitted_findings),
                            ("skipped", &report.overlap_parents_skipped),
                        ],
                    )
                    .into()
            });
        Self {
            archive: archive.cloned(),
            window_index,
            report: report.clone(),
            language,
            hide_invisible,
            rows,
            tone_rows,
            empty: empty_key(report, window.is_some()),
            bounded,
        }
    }
}

pub(crate) struct UiState {
    snapshot: Option<Arc<Snapshot>>,
    tone: FindingsTone,
    pub scroll: ListState,
}

impl Default for UiState {
    fn default() -> Self {
        Self {
            snapshot: None,
            tone: FindingsTone::All,
            scroll: ListState::new(0, ListAlignment::Top, px(64.))
                .with_uniform_item_height(px(ROW_MIN_HEIGHT)),
        }
    }
}

impl UiState {
    pub fn prepare(
        &mut self,
        archive: Option<&Arc<InspectorArchive>>,
        window_index: usize,
        report: &Arc<LayoutAnalysis>,
        language: Language,
        hide_invisible: bool,
        tone: FindingsTone,
    ) -> Arc<Snapshot> {
        let reuse = self.snapshot.as_ref().is_some_and(|snapshot| {
            let same_archive = match (snapshot.archive.as_ref(), archive) {
                (Some(previous), Some(current)) => Arc::ptr_eq(previous, current),
                (None, None) => true,
                _ => false,
            };
            same_archive
                && snapshot.window_index == window_index
                && Arc::ptr_eq(&snapshot.report, report)
                && snapshot.language == language
                && snapshot.hide_invisible == hide_invisible
        });
        if !reuse {
            self.snapshot = Some(Arc::new(Snapshot::build(
                archive,
                window_index,
                report,
                language,
                hide_invisible,
            )));
        }
        let snapshot = self.snapshot.as_ref().expect("prepared findings snapshot");
        if !reuse || self.tone != tone {
            self.scroll.reset_with_uniform_height(
                snapshot.count(tone).max(1) + usize::from(snapshot.bounded.is_some()),
                px(ROW_MIN_HEIGHT),
            );
            self.tone = tone;
        }
        snapshot.clone()
    }
}

fn tone_index(tone: FindingsTone) -> usize {
    match tone {
        FindingsTone::All => 0,
        FindingsTone::Info => 1,
        FindingsTone::Warning => 2,
        FindingsTone::Error => 3,
    }
}
