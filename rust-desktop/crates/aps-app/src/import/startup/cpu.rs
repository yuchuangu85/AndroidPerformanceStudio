//! Render data from the selected live run only. Time spent on CPU comes from
//! scheduling; symbols and sample counts are observations, not call durations.
use super::*;
use aps_perfetto::StartupCpuEvidence;
use std::collections::BTreeMap;

pub(super) fn selected_evidence<'a>(
    run: Option<&'a StartupRun>,
    evidence: &'a BTreeMap<String, StartupCpuEvidence>,
) -> Option<(&'a StartupRun, &'a StartupCpuEvidence)> {
    let run = run.filter(|run| capture::is_live_run(run))?;
    Some((run, evidence.get(&run.id)?))
}

fn ms(nanos: u64) -> String {
    format!("{:.3}", nanos as f64 / 1_000_000.)
}

pub(super) fn panel(
    run: &StartupRun,
    evidence: &StartupCpuEvidence,
    language: Language,
    colors: &studio::StudioColors,
) -> AnyElement {
    let mut panel = studio::card(colors).child(studio::section_title(
        language.format(TextKey::StartupCpuPanel, &[("iteration", &run.iteration)]),
        colors,
    ));
    let window = match evidence {
        StartupCpuEvidence::Measured(window) => window,
        StartupCpuEvidence::Unavailable(reason) => {
            return panel
                .child(note(
                    language.format(
                        TextKey::StartupCpuUnavailable,
                        &[("reason", &bounded(reason, language))],
                    ),
                    colors.warning,
                ))
                .into_any_element();
        }
    };
    let elapsed = ms(window.end_ns.abs_diff(window.start_ns));
    panel = panel.child(note(
        language.format(
            TextKey::StartupCpuWindow,
            &[
                ("window", &elapsed),
                ("pid", &window.pid),
                ("tid", &window.tid),
            ],
        ),
        colors.text,
    ));
    if let Some(sched) = &window.scheduling {
        let coverage = sched.running_ns + sched.runnable_ns + sched.other_ns;
        panel = panel
            .child(note(
                language.format(
                    TextKey::StartupCpuSched,
                    &[
                        ("running", &ms(sched.running_ns)),
                        ("runnable", &ms(sched.runnable_ns)),
                        ("other", &ms(sched.other_ns)),
                    ],
                ),
                colors.text,
            ))
            .child(note(
                language.format(
                    TextKey::StartupCpuCoverage,
                    &[("covered", &ms(coverage)), ("window", &elapsed)],
                ),
                colors.secondary_text,
            ));
    } else {
        panel = panel.child(note(
            language.text(TextKey::StartupCpuNoSched),
            colors.secondary_text,
        ));
    }
    if window.trace_perf_lost_records > 0 {
        panel = panel.child(note(
            language.format(
                TextKey::StartupCpuLost,
                &[("count", &window.trace_perf_lost_records)],
            ),
            colors.warning,
        ));
    }
    if window.total_samples == 0 {
        return panel
            .child(note(
                language.text(TextKey::StartupCpuNoSamples),
                colors.secondary_text,
            ))
            .into_any_element();
    }
    panel = panel.child(note(
        language.format(
            TextKey::StartupCpuSamples,
            &[
                ("total", &window.total_samples),
                ("unresolved", &window.unresolved_samples),
            ],
        ),
        colors.secondary_text,
    ));
    if window.hotspots.is_empty() {
        panel = panel.child(note(
            language.text(TextKey::StartupCpuNoSymbols),
            colors.warning,
        ));
    }
    for row in &window.hotspots {
        let share = format!(
            "{:.1}",
            100. * row.samples as f64 / window.total_samples as f64
        );
        panel = panel.child(
            div()
                .flex()
                .items_center()
                .gap(px(8.))
                .child(
                    note(bounded(&row.symbol, language), colors.text)
                        .flex_1()
                        .min_w_0()
                        .text_ellipsis()
                        .whitespace_nowrap(),
                )
                .child(
                    note(
                        language.format(
                            TextKey::StartupCpuHotspotCount,
                            &[("count", &row.samples), ("share", &share)],
                        ),
                        colors.secondary_text,
                    )
                    .flex_shrink_0(),
                ),
        );
    }
    panel.into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_startup::StartupEvidenceOrigin;
    #[::core::prelude::v1::test]
    fn hotspot_selection_requires_published_live_run_id_not_an_import_or_neighbor() {
        let session = StartupSession::parse_csv(
            "iteration,runId,requestedType,observedType,displayedTimeMs,fullyDrawnTimeMs\n1,first,COLD,COLD,100,\n2,second,COLD,COLD,101,\n",
        ).unwrap();
        let evidence = BTreeMap::from([(
            "second".into(),
            StartupCpuEvidence::Unavailable("test".into()),
        )]);
        let mut first = session.runs()[0].clone();
        first.origin = StartupEvidenceOrigin::LiveCapture;
        let mut second = session.runs()[1].clone();
        assert!(selected_evidence(None, &evidence).is_none());
        assert!(selected_evidence(Some(&first), &evidence).is_none());
        assert!(selected_evidence(Some(&second), &evidence).is_none());
        second.origin = StartupEvidenceOrigin::LiveCapture;
        assert_eq!(
            selected_evidence(Some(&second), &evidence).unwrap().0.id,
            "second"
        );
    }
}
