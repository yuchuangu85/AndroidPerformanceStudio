//! Startup workspace over explicit platform experiments and imported CSV evidence.
//! Missing events and correlated phase evidence are never invented.
mod capture;
pub(in crate::import) use capture::StartupCaptureUi;
mod model;
mod reports;
pub(in crate::import) use reports::open_session;
#[cfg(test)]
mod tests;
use super::*;
use aps_startup::{MetricSource, StartupPage, StartupRun, StartupSummary};
use model::*;

/// One publication owns the displayed cohort, rows, exports and AI projection.
/// Sorting/statistics run on the existing background executor, never in render.
pub(super) fn load_page(
    session: &StartupSession,
    query: &StartupQuery,
    cancel: &CancellationToken,
) -> Result<PageData, UiText> {
    if cancel.is_cancelled() {
        return Err(TextKey::ImportUiImportCancelled.into());
    }
    let page = session.page(query)?;
    let mad = if query.requested_mode.is_none() {
        median_absolute_deviation(session.runs())
    } else {
        filtered_mad(session.runs(), query.requested_mode)
    };
    if cancel.is_cancelled() {
        return Err(TextKey::ImportUiImportCancelled.into());
    }
    let summary = UiText::format(
        TextKey::ImportUiStartupSummary,
        [
            ("count", page.summary.runs.to_string().into()),
            (
                "unknown",
                page.summary.unknown_observed_modes.to_string().into(),
            ),
            ("ttid", page.summary.ttid.count.to_string().into()),
            ("ttfd", page.summary.ttfd.count.to_string().into()),
        ],
    );
    let mut ui = PageUi::new(
        summary,
        &[
            TextKey::ImportUiIteration,
            TextKey::ImportUiPackage,
            TextKey::ImportUiRequested,
            TextKey::ImportUiObserved,
            TextKey::ImportUiTtidMs,
            TextKey::ImportUiTtfdMs,
            TextKey::ImportUiTotalTimeMs,
        ],
    );
    ui.startup_page = Some(page.clone());
    ui.startup_query = Some(query.clone());
    ui.startup_mad = mad;
    Ok(PageData {
        summary: format!(
            "{} runs · {} unknown observed modes · TTID {} measured · TTFD {} measured · source {}",
            page.summary.runs,
            page.summary.unknown_observed_modes,
            page.summary.ttid.count,
            page.summary.ttfd.count,
            cohort_origin(session.runs())
        ),
        columns: [
            "Iteration",
            "Package",
            "Requested",
            "Observed",
            "TTID ms",
            "TTFD ms",
            "TotalTime ms",
            "Evidence source",
        ]
        .into_iter()
        .map(str::to_owned)
        .collect(),
        rows: page
            .rows
            .iter()
            .map(|run| {
                vec![
                    run.iteration.to_string(),
                    run.package.clone().unwrap_or_default(),
                    format!("{:?}", run.requested_mode),
                    format!("{:?}", run.observed_mode),
                    run.ttid
                        .milliseconds
                        .map_or_else(String::new, |v| v.to_string()),
                    run.ttfd
                        .milliseconds
                        .map_or_else(String::new, |v| v.to_string()),
                    run.total_time_ms
                        .map_or_else(String::new, |v| v.to_string()),
                    evidence_origin(run).to_owned(),
                ]
            })
            .collect(),
        offset: page.offset,
        has_more: page.offset.saturating_add(page.rows.len()) < page.total,
        warnings: session.warnings().to_vec(),
        row_actions: Vec::new(),
        ui,
    })
}

impl ImportWorkspace {
    fn startup_evidence_is_live(&self) -> bool {
        matches!(&self.session, Some(Session::Startup(session))
            if !session.runs().is_empty() && session.runs().iter().all(capture::is_live_run))
    }

    pub(super) fn publish_startup_selection(&mut self) {
        if let Some(Session::Startup(session)) = &self.session {
            self.startup_capture.reports.publish(session.clone());
        }
        if let Some(query) = self
            .page
            .as_ref()
            .and_then(|page| page.ui.startup_query.as_ref())
        {
            self.startup_mode_filter = query.requested_mode;
            self.startup_page_offset = query.offset;
        }
        if let Some(page) = self
            .page
            .as_ref()
            .and_then(|page| page.ui.startup_page.as_ref())
        {
            self.selected_startup_run_id =
                selected_run(&page.rows, self.selected_startup_run_id.as_deref())
                    .map(|run| run.id.clone());
        }
    }

    pub(super) fn query_startup(
        &mut self,
        requested: Option<StartupMode>,
        offset: usize,
        cx: &mut Context<Self>,
    ) {
        if self.busy {
            return;
        }
        let Some(Session::Startup(session)) = self.session.clone() else {
            return;
        };
        self.cancel.cancel();
        self.startup_capture.cancel_export();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        self.export_generation += 1;
        self.export_busy = false;
        self.busy = true;
        self.error = None;
        self.notice = None;
        let generation = self.generation;
        let cancel = self.cancel.clone();
        let query = StartupQuery {
            requested_mode: requested,
            offset,
            limit: PAGE,
            ..Default::default()
        };
        let task = cx
            .background_executor()
            .spawn(async move { load_page(&session, &query, &cancel) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(page) => {
                        this.startup_mode_filter = requested;
                        this.startup_page_offset = offset;
                        this.page = Some(page);
                        this.publish_startup_selection();
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    pub(super) fn render_startup_workspace(&mut self, cx: &mut Context<Self>) -> AnyElement {
        self.schedule_startup_comparison(cx);
        let entity = cx.entity();
        container_query(move |size, _, cx| {
            entity.update(cx, |this, cx| this.startup_content(size, cx))
        })
        .size_full()
        .into_any_element()
    }

    fn startup_content(&mut self, size: Size<Pixels>, cx: &mut Context<Self>) -> AnyElement {
        let colors = studio::StudioColors::from_theme(cx);
        let wide = f32::from(size.width) - 8. >= MAIN_RAIL_BREAKPOINT;
        let width = (f32::from(size.width) - 8. - if wide { RAIL_WIDTH + 4. } else { 0. }).max(0.);
        let page = self
            .page
            .as_ref()
            .and_then(|page| page.ui.startup_page.as_ref());
        let selected =
            page.and_then(|page| selected_run(&page.rows, self.selected_startup_run_id.as_deref()));
        let mut main = div()
            .flex()
            .flex_col()
            .gap(px(4.))
            .min_w_0()
            .flex_shrink_0()
            .child(self.startup_modes(&colors, cx))
            .child(self.render_startup_capture_config(&colors, cx))
            .child(metrics(
                page.map(|page| &page.summary),
                self.page.as_ref().and_then(|page| page.ui.startup_mad),
                self.startup_evidence_is_live(),
                width,
                self.language,
                &colors,
            ));
        if let Some(error) = &self.error {
            main = main.child(note(error.render(self.language), colors.error));
        }
        if let Some(notice) = &self.notice {
            main = main.child(note(notice.render(self.language), colors.info));
        }
        if self.busy {
            main = main.child(note(
                self.language.text(TextKey::ImportLoadingQuerying),
                colors.info,
            ));
        }
        main = main
            .child(waterfall(
                selected,
                match (&self.session, selected) {
                    (Some(Session::Startup(session)), Some(run)) => session.evidence(&run.id),
                    _ => None,
                },
                self.language,
                &colors,
            ))
            .child(self.startup_table(page, width, &colors, cx))
            .child(self.startup_baseline(&colors));
        let rail = self.startup_rail(selected, page.map(|page| &page.summary), &colors, cx);
        let body = if wide {
            div()
                .id("startup-wide-workspace")
                .flex()
                .flex_1()
                .min_h_0()
                .gap(px(4.))
                .p(px(4.))
                .child(
                    div()
                        .id("startup-main-scroll")
                        .flex_1()
                        .min_w_0()
                        .h_full()
                        .overflow_y_scrollbar()
                        .child(main),
                )
                .child(
                    div()
                        .id("startup-evidence-scroll")
                        .w(px(RAIL_WIDTH))
                        .flex_shrink_0()
                        .h_full()
                        .overflow_y_scrollbar()
                        .child(rail),
                )
                .into_any_element()
        } else {
            div()
                .id("startup-stacked-workspace")
                .flex_1()
                .min_h_0()
                .overflow_y_scrollbar()
                .child(studio::page(&colors).h_auto().child(main).child(rail))
                .into_any_element()
        };
        let status = if self.busy {
            self.language
                .text(TextKey::ImportLoadingQuerying)
                .to_owned()
        } else if let Some(error) = &self.error {
            error.render(self.language)
        } else if let Some(notice) = &self.notice {
            notice.render(self.language)
        } else {
            self.page.as_ref().map_or_else(
                || self.language.text(TextKey::StartupStatusReady).to_owned(),
                |page| page.display_summary(self.language),
            )
        };
        div()
            .id("startup-profiler-workspace")
            .size_full()
            .min_h_0()
            .flex()
            .flex_col()
            .bg(colors.workspace)
            .text_color(colors.text)
            .text_size(px(studio::FONT_BODY))
            .line_height(px(18.))
            .child(self.startup_toolbar(&colors, cx))
            .child(body)
            .child(
                div()
                    .id("startup-status-bar")
                    .h(px(26.))
                    .flex_shrink_0()
                    .flex()
                    .items_center()
                    .gap(px(8.))
                    .px(px(8.))
                    .border_t_1()
                    .border_color(colors.border)
                    .child(
                        studio::supporting(
                            format!("{}: {status}", self.language.text(TextKey::StartupStatus)),
                            &colors,
                        )
                        .flex_1()
                        .min_w_0()
                        .text_ellipsis()
                        .whitespace_nowrap(),
                    )
                    .children(self.import_duration_text().map(|value| {
                        studio::supporting(value, &colors)
                            .flex_shrink_0()
                            .whitespace_nowrap()
                    })),
            )
            .into_any_element()
    }

    fn startup_toolbar(&self, colors: &studio::StudioColors, cx: &mut Context<Self>) -> AnyElement {
        let language = self.language;
        let ready = self.page.is_some() && !self.busy && !self.export_busy;
        div()
            .id("startup-command-bar")
            .h(px(40.))
            .flex_shrink_0()
            .flex()
            .items_center()
            .gap(px(4.))
            .px(px(8.))
            .border_b_1()
            .border_color(colors.border)
            .overflow_x_scrollbar()
            .child(
                studio::compact_text_button(
                    "startup-import",
                    language.text(TextKey::StartupImportReport),
                    !self.busy,
                    true,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.pick_startup_report(cx))),
            )
            .child(
                studio::compact_text_button(
                    "startup-save-json",
                    language.text(TextKey::StartupSaveJson),
                    ready,
                    false,
                    colors,
                )
                .on_click(
                    cx.listener(|this, _, _, cx| this.save_startup_page(export::Format::Json, cx)),
                ),
            )
            .child(
                studio::compact_text_button(
                    "startup-save-csv",
                    language.text(TextKey::StartupSaveCsv),
                    ready,
                    false,
                    colors,
                )
                .on_click(
                    cx.listener(|this, _, _, cx| this.save_startup_page(export::Format::Csv, cx)),
                ),
            )
            .child(
                studio::compact_text_button(
                    "startup-ai",
                    language.text(TextKey::ShellPrepareAiAnalysis),
                    ready,
                    false,
                    colors,
                )
                .on_click(cx.listener(|_, _, window, cx| {
                    window.dispatch_action(Box::new(crate::shell::PrepareCurrentAi), cx)
                })),
            )
            .child(div().flex_1())
            .when(self.busy, |bar| {
                bar.child(
                    studio::compact_text_button(
                        "startup-cancel",
                        language.text(TextKey::ImportCancelOperation),
                        true,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.cancel_work(cx))),
                )
            })
            .child(
                studio::compact_text_button(
                    "startup-close",
                    language.text(TextKey::CommonClose),
                    !self.busy && self.session.is_some(),
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.close(cx))),
            )
            .into_any_element()
    }

    fn startup_modes(&self, colors: &studio::StudioColors, cx: &mut Context<Self>) -> AnyElement {
        let language = self.language;
        div()
            .flex()
            .flex_col()
            .gap(px(4.))
            .child(
                div().flex().gap(px(8.)).children(
                    [
                        (
                            StartupMode::Cold,
                            TextKey::ImportUiStartupCold,
                            TextKey::StartupColdDescription,
                            colors.info,
                        ),
                        (
                            StartupMode::Warm,
                            TextKey::ImportUiStartupWarm,
                            TextKey::StartupWarmDescription,
                            colors.warning,
                        ),
                        (
                            StartupMode::Hot,
                            TextKey::ImportUiStartupHot,
                            TextKey::StartupHotDescription,
                            colors.success,
                        ),
                    ]
                    .into_iter()
                    .enumerate()
                    .map(|(index, (mode, title, description, tone))| {
                        let selected = self.startup_mode_filter == Some(mode);
                        Button::new(("startup-mode", index))
                            .ghost()
                            .flex_1()
                            .min_w_0()
                            .h(px(52.))
                            .px(px(8.))
                            .border_1()
                            .border_color(if selected {
                                colors.accent
                            } else {
                                colors.border
                            })
                            .rounded(px(6.))
                            .bg(if selected {
                                colors.selected_row
                            } else {
                                colors.panel
                            })
                            .selected(selected)
                            .disabled(self.busy || self.session.is_none())
                            .accessibility_label(language.text(title))
                            .child(
                                div()
                                    .flex()
                                    .flex_col()
                                    .min_w_0()
                                    .child(
                                        studio::section_title(language.text(title), colors)
                                            .text_color(tone),
                                    )
                                    .child(studio::supporting(language.text(description), colors)),
                            )
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.query_startup(Some(mode), 0, cx)
                            }))
                    }),
                ),
            )
            .child(note(
                language.text(TextKey::StartupFilterNotice),
                colors.secondary_text,
            ))
            .into_any_element()
    }

    fn startup_table(
        &self,
        page: Option<&StartupPage>,
        width: f32,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let selected_id = page
            .and_then(|page| selected_run(&page.rows, self.selected_startup_run_id.as_deref()))
            .map(|run| run.id.as_str());
        let mut heading = div()
            .flex()
            .flex_wrap()
            .items_center()
            .gap(px(4.))
            .child(studio::section_title(
                language.text(TextKey::StartupMeasuredRuns),
                colors,
            ))
            .child(
                studio::tab_button(
                    "startup-all-runs",
                    language.text(TextKey::ImportUiStartupAllRuns),
                    self.startup_mode_filter.is_none(),
                    colors,
                )
                .disabled(self.busy || self.session.is_none())
                .on_click(cx.listener(|this, _, _, cx| this.query_startup(None, 0, cx))),
            );
        if let Some(page) = page {
            heading = heading
                .child(div().flex_1())
                .child(studio::supporting(
                    language.format(
                        TextKey::StartupRange,
                        &[
                            (
                                "first",
                                &if page.rows.is_empty() {
                                    0
                                } else {
                                    page.offset + 1
                                },
                            ),
                            ("last", &(page.offset + page.rows.len())),
                            ("total", &page.total),
                        ],
                    ),
                    colors,
                ))
                .child(
                    studio::compact_text_button(
                        "startup-prev",
                        language.text(TextKey::CommonPrevious),
                        !self.busy && page.offset > 0,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.query_startup(
                            this.startup_mode_filter,
                            this.startup_page_offset.saturating_sub(PAGE),
                            cx,
                        )
                    })),
                )
                .child(
                    studio::compact_text_button(
                        "startup-next",
                        language.text(TextKey::CommonNext),
                        !self.busy && page.offset.saturating_add(page.rows.len()) < page.total,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.query_startup(
                            this.startup_mode_filter,
                            this.startup_page_offset.saturating_add(PAGE),
                            cx,
                        )
                    })),
                );
        }
        let columns = [
            TextKey::ImportUiIteration,
            TextKey::StartupRequested,
            TextKey::StartupObserved,
            TextKey::StartupTotal,
            TextKey::StartupTtid,
            TextKey::StartupTtfd,
            TextKey::StartupAgent,
        ];
        let mut table =
            div()
                .flex()
                .flex_col()
                .w(px((width - 16.).max(RUN_TABLE_MIN_WIDTH)))
                .child(studio::table_header_row(colors).children(
                    columns.into_iter().enumerate().map(|(index, key)| {
                        cell(language.text(key).to_owned(), index, colors)
                            .font_weight(FontWeight::SEMIBOLD)
                    }),
                ));
        if let Some(page) = page {
            for (index, run) in page.rows.iter().enumerate() {
                let id = run.id.clone();
                let values = [
                    run.iteration.to_string(),
                    mode_label(run.requested_mode, language).to_owned(),
                    mode_label(run.observed_mode, language).to_owned(),
                    metric_ms(run.total_time_ms),
                    metric_ms(run.ttid.milliseconds),
                    metric_ms(run.ttfd.milliseconds),
                    agent_label(run, language).to_owned(),
                ];
                table = table.child(
                    Button::new(("startup-run", index))
                        .ghost()
                        .w_full()
                        .h(px(32.))
                        .px(px(8.))
                        .selected(selected_id == Some(run.id.as_str()))
                        .disabled(self.busy)
                        .bg(if selected_id == Some(run.id.as_str()) {
                            colors.selected_row
                        } else {
                            colors.panel
                        })
                        .accessibility_label(format!(
                            "{} {} · {} → {}",
                            language.text(TextKey::StartupRunDetail),
                            run.iteration,
                            mode_label(run.requested_mode, language),
                            mode_label(run.observed_mode, language)
                        ))
                        .child(
                            div().w_full().flex().items_center().gap(px(4.)).children(
                                values
                                    .into_iter()
                                    .enumerate()
                                    .map(|(column, text)| cell(text, column, colors)),
                            ),
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.selected_startup_run_id = Some(id.clone());
                            cx.notify();
                        })),
                );
            }
        }
        let mut panel = studio::card(colors).child(heading).child(
            div()
                .id("startup-run-table-scroll")
                .min_w_0()
                .max_w(relative(1.))
                .overflow_x_scrollbar()
                .child(table),
        );
        if page.is_none_or(|page| page.rows.is_empty()) {
            panel = panel.child(note(
                language.text(if page.is_some() {
                    TextKey::StartupNoMatches
                } else {
                    TextKey::StartupEmpty
                }),
                colors.secondary_text,
            ));
        }
        panel.into_any_element()
    }

    fn startup_rail(
        &self,
        run: Option<&StartupRun>,
        summary: Option<&StartupSummary>,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let fields = run.map(evidence_fields).unwrap_or_default();
        let mut profile = studio::card(colors).child(studio::section_title(
            language.text(TextKey::StartupProfileSummary),
            colors,
        ));
        for (key, value) in fields.iter().filter(|(key, _)| {
            matches!(
                *key,
                "compilerFilter"
                    | "compilationVerified"
                    | "profileSource"
                    | "profileSourceDeclared"
                    | "baselineProfileArtifact"
            )
        }) {
            profile = profile.child(field_row(evidence_label(key), value, language, colors));
        }
        if !fields.iter().any(|(key, _)| {
            matches!(
                *key,
                "compilerFilter"
                    | "compilationVerified"
                    | "profileSource"
                    | "profileSourceDeclared"
                    | "baselineProfileArtifact"
            )
        }) {
            profile = profile.child(note(
                language.text(TextKey::StartupNoCompilation),
                colors.secondary_text,
            ));
        }
        profile = profile.child(note(
            language.text(if self.startup_evidence_is_live() {
                TextKey::StartupLiveNotice
            } else {
                TextKey::StartupImportedNotice
            }),
            colors.secondary_text,
        ));
        let mut findings = studio::card(colors).child(studio::section_title(
            language.text(TextKey::StartupOptimization),
            colors,
        ));
        if run.is_some_and(|run| run.ttfd.milliseconds.is_none()) {
            findings = findings.child(note(
                language.text(TextKey::StartupFullyDrawnHint),
                colors.warning,
            ));
        }
        if let Some(summary) = summary {
            findings = findings.child(note(
                language.format(
                    TextKey::StartupModeCounts,
                    &[
                        ("unknown", &summary.unknown_observed_modes),
                        ("mismatches", &summary.requested_observed_mismatches),
                    ],
                ),
                colors.secondary_text,
            ));
        }
        let warnings: Vec<&str> = match &self.session {
            Some(Session::Startup(session)) => {
                session.warnings().iter().map(String::as_str).collect()
            }
            _ => Vec::new(),
        }
        .into_iter()
        .chain(
            run.into_iter()
                .flat_map(|run| run.warnings.iter().map(String::as_str)),
        )
        .chain(
            run.and_then(|run| run.imported_fields.get("diagnostics"))
                .map(String::as_str),
        )
        .collect();
        if warnings.is_empty() {
            findings = findings.child(note(
                language.text(TextKey::StartupNoFindings),
                colors.secondary_text,
            ));
        }
        for warning in warnings.iter().take(24) {
            findings = findings.child(note(bounded(warning, language), colors.warning));
        }
        if warnings.len() > 24 {
            findings = findings.child(note(
                language.format(
                    TextKey::StartupMoreDiagnostics,
                    &[("count", &(warnings.len() - 24))],
                ),
                colors.secondary_text,
            ));
        }
        let ready = self.page.is_some() && !self.busy && !self.export_busy;
        let actions = studio::card(colors)
            .child(studio::section_title(
                language.text(TextKey::StartupRelated),
                colors,
            ))
            .child(
                studio::compact_text_button(
                    "startup-rail-import",
                    language.text(TextKey::StartupImportReport),
                    !self.busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.pick_startup_report(cx))),
            )
            .child(
                div()
                    .flex()
                    .flex_wrap()
                    .gap(px(4.))
                    .child(
                        studio::compact_text_button(
                            "startup-rail-csv",
                            language.text(TextKey::StartupSaveCsv),
                            ready,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.save_startup_page(export::Format::Csv, cx)
                        })),
                    )
                    .child(
                        studio::compact_text_button(
                            "startup-rail-json",
                            language.text(TextKey::StartupSaveJson),
                            ready,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.save_startup_page(export::Format::Json, cx)
                        })),
                    ),
            )
            .child(note(
                language.text(TextKey::StartupExportScope),
                colors.secondary_text,
            ));
        div()
            .flex()
            .flex_col()
            .gap(px(4.))
            .min_w_0()
            .child(profile)
            .child(findings)
            .child(actions)
            .child(run_detail(run, language, colors, cx))
            .into_any_element()
    }
}

fn note(text: impl Into<SharedString>, color: Rgba) -> Div {
    div()
        .text_size(px(studio::FONT_SUPPORTING))
        .text_color(color)
        .line_height(px(16.))
        .child(crate::studio::selectable(text.into()))
}

fn bounded(value: &str, language: Language) -> String {
    if value.chars().count() <= 2048 {
        value.to_owned()
    } else {
        language.format(
            TextKey::StartupTruncated,
            &[("value", &value.chars().take(2048).collect::<String>())],
        )
    }
}

fn mode_label(mode: StartupMode, language: Language) -> &'static str {
    language.text(match mode {
        StartupMode::Cold => TextKey::ImportUiStartupCold,
        StartupMode::Warm => TextKey::ImportUiStartupWarm,
        StartupMode::Hot => TextKey::ImportUiStartupHot,
        StartupMode::Unknown => TextKey::StartupUnknown,
    })
}

fn agent_label(run: &StartupRun, language: Language) -> &'static str {
    match run
        .imported_fields
        .get("agentAvailable")
        .map(String::as_str)
    {
        Some("true") => language.text(TextKey::StartupDeclaredAvailable),
        Some("false") => language.text(TextKey::StartupDeclaredUnavailable),
        _ => "—",
    }
}

fn metrics(
    summary: Option<&StartupSummary>,
    mad: Option<f64>,
    live: bool,
    width: f32,
    language: Language,
    colors: &studio::StudioColors,
) -> AnyElement {
    let empty = StartupSummary::default();
    let summary = summary.unwrap_or(&empty);
    let columns = if width >= 560. {
        4
    } else if width >= 300. {
        2
    } else {
        1
    };
    // Include both panel borders as well as its compact padding. Otherwise the
    // last weighted card wraps at ordinary desktop widths by two pixels.
    let tile_width = ((width - 18. - (columns - 1) as f32 * 4.) / columns as f32).max(0.);
    let tiles = [
        (
            TextKey::StartupTtid,
            metric_ms(summary.ttid.p50_ms),
            Some(&summary.ttid),
            colors.accent,
        ),
        (
            TextKey::StartupTtfd,
            metric_ms(summary.ttfd.p50_ms),
            Some(&summary.ttfd),
            colors.info,
        ),
        (
            TextKey::StartupFirstFrame,
            "—".to_owned(),
            None,
            colors.success,
        ),
        (
            TextKey::StartupP95,
            metric_ms(summary.ttid.p95_ms),
            Some(&summary.ttid),
            colors.warning,
        ),
    ];
    let mut panel = studio::card(colors)
        .child(studio::section_title(
            language.text(TextKey::StartupMetrics),
            colors,
        ))
        .child(
            div()
                .flex()
                .flex_wrap()
                .gap(px(4.))
                .children(tiles.into_iter().enumerate().map(
                    |(index, (key, value, stats, tone))| {
                        let support = stats.map_or_else(
                            || {
                                language
                                    .text(TextKey::StartupEvidenceUnavailable)
                                    .to_owned()
                            },
                            |stats| {
                                language.format(
                                    TextKey::StartupMetricSupport,
                                    &[("count", &stats.count), ("missing", &stats.missing)],
                                )
                            },
                        );
                        studio::metric_card(
                            ("startup-metric", index),
                            language.text(key),
                            value,
                            tone,
                            colors,
                        )
                        .w(px(tile_width))
                        .min_w_0()
                        .child(note(support, colors.secondary_text))
                    },
                )),
        )
        .child(note(
            language.format(
                TextKey::StartupStatistics,
                &[
                    ("total", &metric_ms(summary.total_time.p50_ms)),
                    ("p90", &metric_ms(summary.ttid.p90_ms)),
                    (
                        "mad",
                        &mad.map_or_else(|| "—".to_owned(), |value| format!("{value:.1} ms")),
                    ),
                ],
            ),
            colors.secondary_text,
        ));
    if summary.ttid.count > 0 && summary.ttid.count < 20 {
        panel = panel.child(note(
            language.text(TextKey::StartupLowSamples),
            colors.warning,
        ));
    }
    panel
        .child(note(
            language.text(if live {
                TextKey::StartupLiveNotice
            } else {
                TextKey::StartupImportedNotice
            }),
            colors.secondary_text,
        ))
        .into_any_element()
}

fn waterfall(
    run: Option<&StartupRun>,
    evidence: Option<&aps_startup::RunEvidence>,
    language: Language,
    colors: &studio::StudioColors,
) -> AnyElement {
    let mut panel = studio::card(colors).child(studio::section_title(
        language.text(TextKey::StartupWaterfall),
        colors,
    ));
    if run.is_none() {
        panel = panel.child(note(
            language.text(TextKey::StartupEmpty),
            colors.secondary_text,
        ));
    }
    let phases = evidence.map(|e| e.phases()).unwrap_or_default();
    if !phases.is_empty() {
        for phase in phases {
            panel = panel.child(note(
                format!(
                    "{}: {:.3} ms · {:?}",
                    phase.name,
                    phase.duration_ns as f64 / 1_000_000.,
                    phase.confidence
                ),
                colors.text,
            ));
        }
        return panel.into_any_element();
    }
    panel = panel.child(note(
        language.text(TextKey::StartupPhasesUnavailable),
        colors.secondary_text,
    ));
    for key in [
        TextKey::StartupPhaseProcess,
        TextKey::StartupPhaseInitializer,
        TextKey::StartupPhaseCreate,
        TextKey::StartupPhaseResume,
        TextKey::StartupPhaseFrame,
        TextKey::StartupPhaseDrawn,
    ] {
        panel = panel.child(
            div()
                .flex()
                .items_center()
                .gap(px(8.))
                .child(
                    div()
                        .flex_1()
                        .child(crate::studio::selectable(language.text(key))),
                )
                .child(note("—", colors.muted_text)),
        );
    }
    panel.into_any_element()
}

fn cell(text: String, column: usize, colors: &studio::StudioColors) -> Div {
    div()
        .flex_basis(px(0.))
        .flex_grow(RUN_COLUMN_WEIGHTS[column])
        .min_w_0()
        .text_size(px(studio::FONT_BODY))
        .text_color(colors.text)
        .whitespace_nowrap()
        .text_ellipsis()
        .child(crate::studio::selectable(text))
}

fn evidence_label(key: &str) -> TextKey {
    match key {
        "compilerFilter" => TextKey::StartupEvidenceCompilerFilter,
        "compilationVerified" => TextKey::StartupEvidenceCompilationVerified,
        "profileSource" => TextKey::StartupEvidenceProfileSource,
        "profileSourceDeclared" => TextKey::StartupEvidenceProfileSourceDeclared,
        "baselineProfileArtifact" => TextKey::StartupEvidenceBaselineProfileArtifact,
        "deviceModel" => TextKey::StartupEvidenceDeviceModel,
        "apiLevel" => TextKey::StartupEvidenceApiLevel,
        "emulator" => TextKey::StartupEvidenceEmulator,
        "batteryPercent" => TextKey::StartupEvidenceBatteryPercent,
        "charging" => TextKey::StartupEvidenceCharging,
        "thermalStatus" => TextKey::StartupEvidenceThermalStatus,
        "traceFile" => TextKey::StartupEvidenceTraceFile,
        "traceCaptured" => TextKey::StartupEvidenceTraceCaptured,
        "traceTruncated" => TextKey::StartupEvidenceTraceTruncated,
        _ => TextKey::StartupEvidenceTraceFailure,
    }
}

fn field_row(key: TextKey, value: &str, language: Language, colors: &studio::StudioColors) -> Div {
    div()
        .flex()
        .flex_col()
        .gap(px(1.))
        .child(note(language.text(key), colors.secondary_text))
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .child(bounded(value, language)),
        )
}

fn run_detail(
    run: Option<&StartupRun>,
    language: Language,
    colors: &studio::StudioColors,
    cx: &mut Context<ImportWorkspace>,
) -> AnyElement {
    let mut panel = studio::card(colors).child(studio::section_title(
        language.text(TextKey::StartupRunDetail),
        colors,
    ));
    let Some(run) = run else {
        return panel
            .child(note(
                language.text(TextKey::StartupSelectRun),
                colors.secondary_text,
            ))
            .into_any_element();
    };
    panel = panel.child(note(
        language.format(
            TextKey::StartupRunLabel,
            &[
                ("iteration", &run.iteration),
                ("id", &bounded(&run.id, language)),
            ],
        ),
        colors.text,
    ));
    for (key, value) in [
        (
            TextKey::StartupRequested,
            mode_label(run.requested_mode, language).to_owned(),
        ),
        (
            TextKey::StartupObserved,
            mode_label(run.observed_mode, language).to_owned(),
        ),
        (TextKey::StartupTotal, metric_ms(run.total_time_ms)),
        (TextKey::StartupThisTime, metric_ms(run.this_time_ms)),
        (TextKey::StartupWait, metric_ms(run.wait_time_ms)),
    ] {
        panel = panel.child(field_row(key, &value, language, colors));
    }
    panel = panel.child(studio::section_title(
        language.text(TextKey::StartupMetricEvidence),
        colors,
    ));
    for (key, metric) in [
        (TextKey::StartupTtid, &run.ttid),
        (TextKey::StartupTtfd, &run.ttfd),
    ] {
        panel = panel.child(field_row(
            key,
            &metric_ms(metric.milliseconds),
            language,
            colors,
        ));
        let source = match metric.source {
            Some(MetricSource::ImportedCsv) => TextKey::StartupImportedSource,
            Some(MetricSource::EventLog) => TextKey::StartupEventSource,
            Some(MetricSource::ImportedJson) => TextKey::StartupJsonSource,
            None => TextKey::StartupEvidenceUnavailable,
        };
        panel = panel.child(note(language.text(source), colors.secondary_text));
        if let Some(reason) = &metric.unavailable_reason {
            panel = panel.child(note(bounded(reason, language), colors.warning));
        }
    }
    let mut fields = evidence_fields(run);
    let provenance = capture::provenance_fields(run);
    fields.extend(provenance.iter().cloned());
    panel = panel.child(studio::section_title(
        language.text(TextKey::StartupEnvironment),
        colors,
    ));
    let environment: Vec<_> = fields
        .iter()
        .filter(|(key, _)| {
            matches!(
                *key,
                "deviceModel"
                    | "apiLevel"
                    | "emulator"
                    | "batteryPercent"
                    | "charging"
                    | "thermalStatus"
            )
        })
        .collect();
    if environment.is_empty() {
        panel = panel.child(note(
            language.text(TextKey::StartupEnvironmentMissing),
            colors.secondary_text,
        ));
    }
    for (key, value) in environment {
        panel = panel.child(field_row(evidence_label(key), value, language, colors));
    }
    for (key, value) in provenance {
        panel = panel.child(note(
            format!("{key}: {}", bounded(&value, language)),
            colors.secondary_text,
        ));
    }
    panel = panel.child(studio::section_title(
        language.text(TextKey::StartupTrace),
        colors,
    ));
    for (key, value) in fields.iter().filter(|(key, _)| key.starts_with("trace")) {
        panel = panel.child(field_row(evidence_label(key), value, language, colors));
    }
    let copy = format!(
        "runId={}\nevidenceSource={}\niteration={}\nrequested={:?}\nobserved={:?}\nTotalTime={}\nTTID={}\nTTFD={}\n{}",
        bounded(&run.id, language),
        evidence_origin(run),
        run.iteration,
        run.requested_mode,
        run.observed_mode,
        metric_ms(run.total_time_ms),
        metric_ms(run.ttid.milliseconds),
        metric_ms(run.ttfd.milliseconds),
        fields
            .iter()
            .map(|(key, value)| format!("{key}={}", bounded(value, language)))
            .collect::<Vec<_>>()
            .join("\n")
    );
    panel
        .child(note(
            language.text(TextKey::StartupTraceUnavailable),
            colors.secondary_text,
        ))
        .child(
            studio::compact_text_button(
                "startup-open-trace",
                language.text(TextKey::StartupOpenTrace),
                false,
                false,
                colors,
            )
            .tooltip(language.text(TextKey::StartupTraceUnavailable)),
        )
        .child(
            studio::compact_text_button(
                "startup-copy-run",
                language.text(TextKey::StartupCopyDetail),
                true,
                false,
                colors,
            )
            .on_click(cx.listener(move |_, _, _, cx| {
                cx.write_to_clipboard(ClipboardItem::new_string(copy.clone()))
            })),
        )
        .into_any_element()
}
