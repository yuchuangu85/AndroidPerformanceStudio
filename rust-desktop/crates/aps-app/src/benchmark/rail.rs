//! Benchmark report rail: report summary, recent exports and the comparison inspector.
//!
//! Port of Compose `BenchmarkReportsScreen.kt` `BenchmarkReportColumn` / `BenchmarkInspector`:
//! the release decision stays explicit (chip, policy version, localized gate reasons, blocking
//! comparison count), the exported-report catalogue is a separate list, and the inspector shows
//! the selected comparison's evidence plus its compatibility issues and analyzer reasons.

use super::BenchmarkWorkspace;
use super::model::{self, BenchmarkReportsState};
use crate::studio::{self, FONT_BODY, FONT_SUPPORTING, StudioColors};
use aps_benchmark::{
    BenchmarkReportEntry, CompatibilityIssue, Confidence, GateDecision, GateReason,
};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{prelude::*, *};

/// Compose `BenchmarkReportColumn`: report summary plus the exported-report catalogue.
pub(in crate::benchmark) fn report_column(
    state: &BenchmarkReportsState,
    language: Language,
    cx: &mut Context<BenchmarkWorkspace>,
) -> AnyElement {
    let colors = StudioColors::from_theme(cx);
    let entity = cx.entity().downgrade();
    let mut panel = studio::card(&colors)
        .w_full()
        .min_w_0()
        .child(studio::section_title(
            language.text(TextKey::BenchmarkReportSummary),
            &colors,
        ));
    match state.gate.as_ref() {
        None => {
            panel = panel.child(body_text(
                language.text(TextKey::BenchmarkNoComparisons),
                colors.secondary_text,
            ));
        }
        Some(gate) => {
            panel = panel.child(
                div()
                    .w_full()
                    .flex()
                    .items_center()
                    .gap(px(studio::metrics::PANEL_GAP))
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .text_size(px(FONT_SUPPORTING))
                            .text_color(colors.secondary_text)
                            .child(studio::selectable(
                                language.text(TextKey::BenchmarkReleaseDecision),
                            )),
                    )
                    .child(chip(
                        language.text(gate_decision_key(gate.decision)).to_owned(),
                        gate_tone(gate.decision, &colors),
                    )),
            );
            panel = panel.child(caption_with(
                language.format(
                    TextKey::BenchmarkGatePolicyVersion,
                    &[("version", &gate.policy_version)],
                ),
                &colors,
            ));
            for reason in &gate.reasons {
                panel = panel.child(body_text(
                    language.text(gate_reason_key(*reason)),
                    colors.secondary_text,
                ));
            }
            if !gate.blocking_comparisons.is_empty() {
                panel = panel.child(body_text(
                    language.format(
                        TextKey::BenchmarkBlockingComparisons,
                        &[("count", &gate.blocking_comparisons.len())],
                    ),
                    colors.error,
                ));
            }
            panel = panel
                .child(studio::divider(&colors))
                .child(counts_row(state, language, &colors))
                .child(policy_summary(state, language, &colors));
        }
    }
    panel = panel.child(studio::divider(&colors)).child(
        div().w_full().flex().items_center().child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(FONT_SUPPORTING))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .child(studio::selectable(
                    language.text(TextKey::BenchmarkRecentReports),
                )),
        ),
    );
    if state.recent_reports.is_empty() {
        panel = panel.child(body_text(
            language.text(TextKey::BenchmarkNoReports),
            colors.muted_text,
        ));
    } else {
        for (index, entry) in state.recent_reports.iter().enumerate() {
            panel = panel.child(report_row(entry, index, language, &colors, &entity));
        }
    }
    panel
        .child(
            studio::compact_text_button(
                "benchmark-generate-report",
                language.text(TextKey::BenchmarkGenerateNewReport),
                state.report.is_some(),
                false,
                &colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.export_pdf(cx))),
        )
        .into_any_element()
}

/// Compose `BenchmarkInspector`: selected comparison evidence, compatibility and reasons.
pub(in crate::benchmark) fn inspector(
    state: &BenchmarkReportsState,
    language: Language,
    cx: &mut Context<BenchmarkWorkspace>,
) -> AnyElement {
    let colors = StudioColors::from_theme(cx);
    let mut panel = studio::card(&colors)
        .w_full()
        .min_w_0()
        .child(studio::section_title(
            language.text(TextKey::BenchmarkComparisonInspector),
            &colors,
        ));
    let Some(comparison) = state.selected_comparison() else {
        return panel
            .child(body_text(
                language.text(TextKey::BenchmarkInspectHint),
                colors.secondary_text,
            ))
            .into_any_element();
    };
    let samples = state
        .sample_count(comparison)
        .map_or_else(|| "—".to_owned(), |count| count.to_string());
    let traces = state.trace_artifacts(comparison).len();
    panel = panel
        .child(
            div()
                .w_full()
                .min_w_0()
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .child(studio::selectable(comparison.metric_name.clone())),
        )
        .child(
            div()
                .w_full()
                .min_w_0()
                .font_family("monospace")
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.muted_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(studio::selectable(comparison.case_identity.clone())),
        )
        .child(body_text(
            language.format(TextKey::BenchmarkInspectorSamples, &[("count", &samples)]),
            colors.secondary_text,
        ))
        .child(body_text(
            language.format(TextKey::BenchmarkInspectorTraces, &[("count", &traces)]),
            colors.secondary_text,
        ))
        .child(small_title(
            language.text(TextKey::BenchmarkInspectorCompatibility),
            &colors,
        ));
    let issues = state.compatibility_issues();
    if issues.is_empty() {
        panel = panel.child(body_text(
            language.text(TextKey::BenchmarkInspectorNoIssues),
            colors.secondary_text,
        ));
    } else {
        for issue in issues {
            panel = panel.child(body_text(compatibility_line(issue), colors.warning));
        }
    }
    panel = panel.child(small_title(
        language.text(TextKey::BenchmarkInspectorReasons),
        &colors,
    ));
    if comparison.reasons.is_empty() {
        panel = panel.child(body_text(
            language.text(TextKey::BenchmarkInspectorNoIssues),
            colors.secondary_text,
        ));
    } else {
        for reason in &comparison.reasons {
            panel = panel.child(body_text(reason.clone(), colors.secondary_text));
        }
    }
    panel.into_any_element()
}

fn chip(label: String, tone: Rgba) -> Div {
    div()
        .px(px(6.))
        .py(px(2.))
        .rounded(px(studio::metrics::SMALL_RADIUS))
        .bg(tone.alpha(0.12))
        .text_size(px(FONT_SUPPORTING))
        .line_height(px(15.))
        .text_color(tone)
        .whitespace_nowrap()
        .child(studio::selectable(label))
}

fn body_text(text: impl Into<SharedString>, color: Rgba) -> Div {
    div()
        .w_full()
        .min_w_0()
        .text_size(px(FONT_SUPPORTING))
        .line_height(px(15.))
        .text_color(color)
        .child(studio::selectable(text))
}

fn small_title(text: impl Into<SharedString>, colors: &StudioColors) -> Div {
    div()
        .w_full()
        .min_w_0()
        .text_size(px(FONT_SUPPORTING))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(colors.text)
        .child(studio::selectable(text))
}

fn count_cell(text: String, color: Rgba) -> Div {
    div()
        .flex_1()
        .min_w_0()
        .text_size(px(FONT_SUPPORTING))
        .line_height(px(15.))
        .text_color(color)
        .child(studio::selectable(text))
}

fn counts_row(state: &BenchmarkReportsState, language: Language, colors: &StudioColors) -> Div {
    div()
        .w_full()
        .flex()
        .gap(px(studio::metrics::PANEL_GAP))
        .child(count_cell(
            language.format(
                TextKey::BenchmarkCountRegressions,
                &[("count", &state.regression_count())],
            ),
            colors.error,
        ))
        .child(count_cell(
            language.format(
                TextKey::BenchmarkCountImprovements,
                &[("count", &state.improvement_count())],
            ),
            colors.success,
        ))
        .child(count_cell(
            language.format(
                TextKey::BenchmarkCountStable,
                &[("count", &state.stable_count())],
            ),
            colors.secondary_text,
        ))
}

fn policy_summary(state: &BenchmarkReportsState, language: Language, colors: &StudioColors) -> Div {
    let blocking = if state.gate_policy.blocking_metric_patterns.is_empty() {
        language.text(TextKey::BenchmarkPolicyAnyMetric).to_owned()
    } else {
        state.gate_policy.blocking_metric_patterns.join(", ")
    };
    div()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(2.))
        .child(caption_with(
            language.format(
                TextKey::BenchmarkPolicyBlockingPatterns,
                &[("patterns", &blocking)],
            ),
            colors,
        ))
        .child(caption_with(
            language.format(
                TextKey::BenchmarkPolicyMaxRegressions,
                &[("count", &state.gate_policy.max_allowed_regressions)],
            ),
            colors,
        ))
        .child(caption_with(
            language.format(
                TextKey::BenchmarkPolicyMinConfidence,
                &[(
                    "confidence",
                    &language
                        .text(confidence_key(state.gate_policy.minimum_confidence))
                        .to_owned(),
                )],
            ),
            colors,
        ))
}

fn caption_with(text: String, colors: &StudioColors) -> Div {
    div()
        .w_full()
        .min_w_0()
        .text_size(px(FONT_SUPPORTING))
        .line_height(px(15.))
        .text_color(colors.muted_text)
        .child(studio::selectable(text))
}

fn report_row(
    entry: &BenchmarkReportEntry,
    index: usize,
    language: Language,
    colors: &StudioColors,
    entity: &WeakEntity<BenchmarkWorkspace>,
) -> Div {
    let entity = entity.clone();
    let path = entry.path.clone();
    let meta = format!(
        "{} · {}",
        report_date(&entry.created_at),
        model::format_bytes(entry.size_bytes)
    );
    div()
        .w_full()
        .flex()
        .items_center()
        .gap(px(studio::metrics::PANEL_GAP))
        .child(chip(entry.format.to_uppercase(), colors.info))
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .gap(px(1.))
                .child(
                    div()
                        .w_full()
                        .min_w_0()
                        .text_size(px(FONT_SUPPORTING))
                        .line_height(px(15.))
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(studio::selectable(entry.title.clone())),
                )
                .child(caption_with(meta, colors)),
        )
        .child(
            studio::compact_text_button(
                ("benchmark-report-view", index),
                language.text(TextKey::BenchmarkViewAll),
                true,
                false,
                colors,
            )
            .on_click(move |_, _, cx| {
                let path = path.clone();
                let _ = entity.update(cx, |this, cx| this.open_report(path, cx));
            }),
        )
}

fn gate_decision_key(decision: GateDecision) -> TextKey {
    match decision {
        GateDecision::Pass => TextKey::BenchmarkGatePass,
        GateDecision::Fail => TextKey::BenchmarkGateFail,
        GateDecision::Inconclusive => TextKey::BenchmarkGateInconclusive,
    }
}

fn gate_tone(decision: GateDecision, colors: &StudioColors) -> Rgba {
    match decision {
        GateDecision::Pass => colors.success,
        GateDecision::Fail => colors.error,
        GateDecision::Inconclusive => colors.warning,
    }
}

fn gate_reason_key(reason: GateReason) -> TextKey {
    match reason {
        GateReason::IncompatibleEvidence => TextKey::BenchmarkGateReasonIncompatibleEvidence,
        GateReason::BlockingRegressions => TextKey::BenchmarkGateReasonBlockingRegressions,
        GateReason::InconclusiveEvidence => TextKey::BenchmarkGateReasonInconclusiveEvidence,
        GateReason::InconclusiveDisallowed => TextKey::BenchmarkGateReasonInconclusiveDisallowed,
        GateReason::NoBlockingRegression => TextKey::BenchmarkGateReasonNoBlockingRegression,
    }
}

fn confidence_key(confidence: Confidence) -> TextKey {
    match confidence {
        Confidence::Exact => TextKey::BenchmarkConfidenceExact,
        Confidence::Derived => TextKey::BenchmarkConfidenceDerived,
        Confidence::Inferred => TextKey::BenchmarkConfidenceInferred,
        Confidence::Partial => TextKey::BenchmarkConfidencePartial,
        Confidence::Unknown => TextKey::BenchmarkConfidenceUnknown,
    }
}

/// Compose `CompatibilityIssue.format`.
fn compatibility_line(issue: &CompatibilityIssue) -> String {
    format!(
        "{}: {} → {}",
        issue.field,
        issue.baseline.as_deref().unwrap_or("—"),
        issue.current.as_deref().unwrap_or("—")
    )
}

/// Compose `REPORT_DATE_FORMAT` over the RFC3339 instant the report index stores.
fn report_date(created_at: &str) -> String {
    if created_at.as_bytes().get(10) == Some(&b'T')
        && let (Some(date), Some(time)) = (created_at.get(..10), created_at.get(11..16))
    {
        return format!("{date} {time}");
    }
    created_at.to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn compatibility_line_renders_verbatim_fields_and_missing_values() {
        let issue = CompatibilityIssue {
            field: "device.apiLevel".into(),
            baseline: Some("34".into()),
            current: Some("35".into()),
            hard: true,
        };
        assert_eq!(compatibility_line(&issue), "device.apiLevel: 34 → 35");
        let issue = CompatibilityIssue {
            field: "case.dev.Example#scroll.scenario".into(),
            baseline: None,
            current: Some("SCROLL".into()),
            hard: true,
        };
        assert_eq!(
            compatibility_line(&issue),
            "case.dev.Example#scroll.scenario: — → SCROLL"
        );
    }

    #[::core::prelude::v1::test]
    fn report_date_uses_the_recorded_minute_and_falls_back_to_raw_text() {
        assert_eq!(report_date("2026-11-08T07:38:49Z"), "2026-11-08 07:38");
        assert_eq!(report_date("2026-11-08"), "2026-11-08");
        assert_eq!(report_date("yesterday"), "yesterday");
    }
}
