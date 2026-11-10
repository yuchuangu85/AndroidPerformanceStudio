//! Reference-shaped Battery workspace over immutable, imported derived evidence.
//! Capture, saved sessions and raw clocks are never invented from report totals.
mod model;
pub(super) mod workspace;
use super::*;
use gpui_kit::component::Sizable;
use gpui_kit::component::{
    WindowExt,
    checkbox::Checkbox,
    input::{Input, InputEvent, InputState},
};
pub(super) use model::*;

#[derive(Default)]
pub(super) struct BatteryShellUi {
    search: Option<Entity<InputState>>,
    subscription: Option<Subscription>,
    language: Option<Language>,
    query: String,
    pub(super) warnings_offset: usize,
}

const SESSION_BREAKPOINT: f32 = 1000.;
const SESSION_WIDTH: f32 = 230.;
const RUN_TABLE_WIDTH: f32 = 580.;
const RUN_WEIGHTS: [f32; 6] = [70., 100., 110., 90., 110., 100.];

fn metric_columns(width: f32, count: usize) -> usize {
    (((width.max(0.) + 8.) / 176.) as usize).clamp(1, count.max(1))
}
fn number(value: Option<f64>, unit: &str) -> String {
    value.map_or_else(
        || "—".into(),
        |value| {
            if unit.is_empty() {
                format!("{value:.2}")
            } else {
                format!("{value:.2} {unit}")
            }
        },
    )
}
fn optional_number(value: Option<u64>, unit: &str) -> String {
    value.map_or_else(
        || "—".into(),
        |value| {
            if unit.is_empty() {
                value.to_string()
            } else {
                format!("{value} {unit}")
            }
        },
    )
}
fn run_values(run: &aps_battery::BatteryReportRun, language: Language) -> [String; 6] {
    let energy = if run.energy.len() == 1 {
        run.energy[0].energy_mah
    } else {
        None
    };
    [
        run.iteration.to_string(),
        language.format(
            TextKey::BatterySeconds,
            &[("value", &format!("{:.2}", run.duration_ms as f64 / 1000.))],
        ),
        optional_number(
            run_stats(run, aps_battery::ResourceKind::Wakelock).map(|v| v.duration_ms),
            "ms",
        ),
        optional_number(
            run_stats(run, aps_battery::ResourceKind::Alarm).map(|v| v.count),
            "",
        ),
        optional_number(run.network_bytes, "B"),
        number(energy, "mAh"),
    ]
}
fn table_cell(text: String, column: usize, colors: &studio::StudioColors) -> Div {
    div()
        .flex_basis(px(0.))
        .flex_grow(RUN_WEIGHTS[column])
        .min_w_0()
        .text_size(px(studio::FONT_BODY))
        .text_color(colors.text)
        .whitespace_nowrap()
        .text_ellipsis()
        .child(crate::studio::selectable(text))
}
fn note(text: impl Into<SharedString>, colors: &studio::StudioColors) -> Div {
    studio::supporting(text, colors)
        .line_height(px(16.))
        .flex_shrink_0()
}
fn session_matches(report: &BatteryReport, query: &str) -> bool {
    let needle = query.trim().to_lowercase();
    needle.is_empty()
        || [
            report.session_id.as_str(),
            report.package_name.as_deref().unwrap_or(""),
            report.created_at.as_deref().unwrap_or(""),
        ]
        .iter()
        .any(|value| value.to_lowercase().contains(&needle))
}

impl BatteryUi {
    fn publish_run_page(&mut self, report: &BatteryReport, offset: usize) {
        self.run_offset = offset;
        let runs = report.runs().iter().skip(offset).take(EVIDENCE_PAGE_LIMIT);
        if !runs
            .clone()
            .any(|run| self.selected_run.as_deref() == Some(run.id.as_str()))
            && let Some(run) = runs.into_iter().next()
        {
            self.select_run(run.id.clone());
        }
    }
}

impl ImportWorkspace {
    fn battery_session_search(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Entity<InputState> {
        if self.battery_shell_ui.search.is_none() {
            let language = self.language;
            let search = cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::BatterySessionSearch))
                    .validate(|value, _| value.len() <= QUERY_LIMIT)
            });
            self.battery_shell_ui.subscription =
                Some(cx.subscribe(&search, |this, search, event, cx| {
                    if matches!(event, InputEvent::Change) {
                        this.battery_shell_ui.query = search.read(cx).value().to_string();
                        cx.notify();
                    }
                }));
            self.battery_shell_ui.search = Some(search);
        }
        let search = self
            .battery_shell_ui
            .search
            .clone()
            .expect("battery session search initialized");
        if self.battery_shell_ui.language != Some(self.language) {
            search.update(cx, |input, cx| {
                input.set_placeholder(
                    self.language.text(TextKey::BatterySessionSearch),
                    window,
                    cx,
                )
            });
            self.battery_shell_ui.language = Some(self.language);
        }
        search
    }

    pub(super) fn render_battery_profiler(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let search = self.battery_session_search(window, cx);
        let entity = cx.entity();
        container_query(move |size, window, cx| {
            entity.update(cx, |this, cx| {
                this.battery_content(size, &search, window, cx)
            })
        })
        .size_full()
        .into_any_element()
    }

    fn battery_content(
        &mut self,
        size: Size<Pixels>,
        search: &Entity<InputState>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = studio::StudioColors::from_theme(cx);
        let wide = f32::from(size.width) >= SESSION_BREAKPOINT;
        let content_width =
            (f32::from(size.width) - if wide { SESSION_WIDTH } else { 0. } - 8.).max(0.);
        let report = match &self.session {
            Some(Session::Battery(report)) => Some(report.clone()),
            _ => None,
        };
        let sessions = self.battery_sessions(report.as_deref(), search, &colors, cx);
        let mut content = studio::page(&colors)
            .h_auto()
            .min_h(px(180.))
            .flex_shrink_0()
            .line_height(px(18.));
        if let Some(report) = report {
            if let Some(metrics) = &self.battery_metrics {
                content = content.child(self.battery_metrics_grid(metrics, content_width, &colors));
            }
            let unknown = self.language.text(TextKey::BatteryUnknown);
            content = content.child(
                studio::card(&colors)
                    .gap(px(4.))
                    .child(note(
                        self.language.format(
                            TextKey::BatteryAttribution,
                            &[
                                (
                                    "capability",
                                    &report.capability_level.as_deref().unwrap_or(unknown),
                                ),
                                (
                                    "scope",
                                    &report.attribution_scope.as_deref().unwrap_or(unknown),
                                ),
                                (
                                    "uid",
                                    &report
                                        .uid
                                        .map_or_else(|| unknown.to_owned(), |v| v.to_string()),
                                ),
                            ],
                        ),
                        &colors,
                    ))
                    .child(note(
                        self.language.text(TextKey::BatteryDerivedNotice),
                        &colors,
                    )),
            );
            let mut provenance = div().flex().flex_wrap().gap(px(12.));
            for (key, value) in [
                (
                    TextKey::BatterySourceDevice,
                    report.device_local_id.as_deref(),
                ),
                (TextKey::BatterySourceMode, report.capture_mode.as_deref()),
            ] {
                if let Some(value) = value {
                    provenance = provenance.child(note(
                        format!("{}: {}", self.language.text(key), value),
                        &colors,
                    ));
                }
            }
            content = content
                .child(provenance)
                .child(note(
                    self.language.text(TextKey::BatteryBaselineUnavailable),
                    &colors,
                ))
                .child(self.battery_run_table(&report, &colors, cx));
            if let Some(run) = self.battery_ui.selected(&report) {
                content =
                    content.child(self.render_battery_workspace(run, content_width, window, cx));
            }
            content = content.child(self.battery_diagnostics(&report, &colors, cx));
        } else {
            content = content.child(
                studio::card(&colors)
                    .child(studio::section_title(
                        self.language.text(TextKey::BatteryEnergyEvidence),
                        &colors,
                    ))
                    .child(note(self.language.text(TextKey::BatteryEmpty), &colors))
                    .child(note(self.language.text(TextKey::BatteryEmptyHint), &colors)),
            );
        }
        let mut panes = div()
            .id("battery-session-workspace")
            .flex()
            .min_h_0()
            .min_w_0()
            .flex_1();
        if wide {
            panes = panes
                .child(
                    div()
                        .w(px(SESSION_WIDTH))
                        .h_full()
                        .flex_shrink_0()
                        .pl(px(4.))
                        .py(px(4.))
                        .child(sessions),
                )
                .child(
                    div()
                        .id("battery-results-scroll")
                        .flex_1()
                        .min_w_0()
                        .h_full()
                        .overflow_y_scroll()
                        .child(content),
                );
        } else {
            // One page scroller keeps results reachable even in very short windows.
            panes = panes
                .flex_col()
                .overflow_y_scroll()
                .child(
                    div()
                        .h(px(220.))
                        .w_full()
                        .flex_shrink_0()
                        .px(px(4.))
                        .pt(px(4.))
                        .child(sessions),
                )
                .child(content);
        }
        div()
            .id("battery-profiler")
            .size_full()
            .min_h_0()
            .min_w_0()
            .flex()
            .flex_col()
            .bg(colors.workspace)
            .text_color(colors.text)
            .text_size(px(studio::FONT_BODY))
            .child(self.battery_toolbar(&colors, cx))
            .child(panes)
            .child(self.battery_status(&colors, cx))
            .into_any_element()
    }

    fn battery_toolbar(&self, colors: &studio::StudioColors, cx: &mut Context<Self>) -> AnyElement {
        let language = self.language;
        let loaded = matches!(self.session, Some(Session::Battery(_)));
        div()
            .id("battery-toolbar")
            .h(px(40.))
            .flex_shrink_0()
            .flex()
            .items_center()
            .gap(px(6.))
            .px(px(8.))
            .border_b_1()
            .border_color(colors.border)
            .overflow_x_scrollbar()
            .child(
                studio::compact_text_button(
                    "battery-run",
                    language.text(TextKey::BatteryRunExperiment),
                    false,
                    true,
                    colors,
                )
                .tooltip(language.text(TextKey::BatteryCaptureUnavailable)),
            )
            .child(div().w(px(1.)).h(px(18.)).bg(colors.border))
            .child(
                studio::compact_text_button(
                    "battery-configuration",
                    language.text(TextKey::BatteryConfiguration),
                    true,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, window, cx| {
                    this.battery_configuration(window, cx);
                })),
            )
            .child(
                studio::compact_text_button(
                    "battery-ai",
                    language.text(TextKey::ShellPrepareAiAnalysis),
                    loaded && !self.busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|_, _, window, cx| {
                    window.dispatch_action(Box::new(crate::shell::PrepareCurrentAi), cx)
                })),
            )
            .child(
                studio::compact_text_button(
                    "battery-import",
                    language.text(TextKey::BatteryImportAnalysis),
                    !self.busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.pick(cx))),
            )
            .child(
                studio::compact_text_button(
                    "battery-json",
                    language.text(TextKey::BatterySavePageJson),
                    loaded && !self.busy && !self.export_busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.save_page(export::Format::Json, cx))),
            )
            .child(
                studio::compact_text_button(
                    "battery-csv",
                    language.text(TextKey::BatterySavePageCsv),
                    loaded && !self.busy && !self.export_busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.save_page(export::Format::Csv, cx))),
            )
            .child(
                studio::compact_text_button(
                    "battery-raw",
                    language.text(TextKey::BatteryRawEvidence),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::BatteryWorkspaceRawMissing)),
            )
            .child(
                studio::compact_text_button(
                    "battery-reset",
                    language.text(TextKey::BatteryResetGlobal),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::BatteryCaptureUnavailable)),
            )
            .when(self.busy, |toolbar| {
                toolbar.child(
                    studio::compact_text_button(
                        "battery-cancel",
                        language.text(TextKey::ImportCancelOperation),
                        true,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.cancel_work(cx))),
                )
            })
            .child(div().flex_1())
            .child(
                studio::compact_text_button(
                    "battery-historian",
                    language.text(TextKey::BatteryHistorian),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::BatteryHistorianUnavailable)),
            )
            .into_any_element()
    }

    fn battery_sessions(
        &self,
        report: Option<&BatteryReport>,
        search: &Entity<InputState>,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let mut pane = studio::card(colors)
            .size_full()
            .min_h_0()
            .min_w_0()
            .gap(px(8.))
            .child(
                div()
                    .flex()
                    .items_center()
                    .justify_between()
                    .child(studio::section_title(
                        language.text(TextKey::BatterySessions),
                        colors,
                    ))
                    .child(
                        studio::compact_text_button(
                            "battery-new",
                            language.text(TextKey::BatteryNew),
                            !self.busy,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.close(cx))),
                    ),
            )
            .child(Input::new(search).w_full())
            .child(
                div()
                    .flex()
                    .flex_wrap()
                    .items_center()
                    .justify_between()
                    .child(note(
                        language.format(
                            TextKey::BatteryImportedCount,
                            &[("count", &usize::from(report.is_some()))],
                        ),
                        colors,
                    ))
                    .child(
                        studio::compact_text_button(
                            "battery-refresh-sessions",
                            language.text(TextKey::BatteryRefresh),
                            false,
                            false,
                            colors,
                        )
                        .tooltip(language.text(TextKey::BatteryRefreshUnavailable)),
                    ),
            );
        let mut entries = div()
            .id("battery-sessions-list")
            .flex_1()
            .min_h_0()
            .overflow_y_scroll()
            .flex()
            .flex_col()
            .gap(px(4.));
        if let Some(report) =
            report.filter(|report| session_matches(report, &self.battery_shell_ui.query))
        {
            entries =
                entries.child(
                    div()
                        .id("battery-imported-session")
                        .flex()
                        .flex_col()
                        .gap(px(3.))
                        .p(px(8.))
                        .bg(colors.selected_row)
                        .child(
                            div()
                                .text_size(px(studio::FONT_BODY))
                                .font_weight(FontWeight::SEMIBOLD)
                                .child(report.package_name.clone().unwrap_or_else(|| {
                                    language.text(TextKey::BatteryImported).into()
                                })),
                        )
                        .child(note(
                            report.created_at.clone().unwrap_or_else(|| "—".into()),
                            colors,
                        ))
                        .child(note(report.session_id.clone(), colors))
                        .child(note(
                            language.format(
                                TextKey::BatterySessionRuns,
                                &[(
                                    "count",
                                    &self
                                        .battery_metrics
                                        .as_ref()
                                        .map_or(report.runs().len(), |metrics| metrics.runs),
                                )],
                            ),
                            colors,
                        )),
                );
        } else {
            entries = entries.child(note(
                language.text(if report.is_some() {
                    TextKey::BatteryNoSessionMatches
                } else {
                    TextKey::BatteryNoSessions
                }),
                colors,
            ));
        }
        pane = pane.child(entries).child(note(
            language.text(TextKey::BatterySessionStoreUnavailable),
            colors,
        ));
        pane.into_any_element()
    }

    fn battery_metrics_grid(
        &self,
        metrics: &ReportMetrics,
        width: f32,
        colors: &studio::StudioColors,
    ) -> AnyElement {
        let language = self.language;
        let mut items = vec![
            (
                TextKey::BatteryMedianWakelock,
                &metrics.wakelock_ms,
                "ms",
                colors.warning,
            ),
            (
                TextKey::BatteryMedianAlarms,
                &metrics.alarm_count,
                "",
                colors.accent,
            ),
            (
                TextKey::BatteryMedianNetwork,
                &metrics.network_bytes,
                "B",
                colors.info,
            ),
        ];
        if metrics.energy_mah.known_count > 0 {
            items.push((
                TextKey::BatteryEnergyMetric,
                &metrics.energy_mah,
                "mAh",
                colors.accent,
            ));
        }
        items.extend([
            (
                TextKey::BatteryPeakTemperature,
                &metrics.temperature_celsius,
                "°C",
                colors.warning,
            ),
            (
                TextKey::BatteryThermalStatus,
                &metrics.thermal_status,
                "",
                colors.info,
            ),
        ]);
        let columns = metric_columns(width, items.len());
        let mut grid = div().flex().flex_col().gap(px(4.)).flex_shrink_0();
        for (row_index, row) in items.chunks(columns).enumerate() {
            let mut line = div().flex().items_stretch().gap(px(4.));
            for (index, (key, stats, unit, tone)) in row.iter().enumerate() {
                line = line.child(
                    studio::metric_card(
                        ("battery-metric", row_index * columns + index),
                        language.text(*key),
                        number(stats.median, unit),
                        *tone,
                        colors,
                    )
                    .flex_1()
                    .min_w_0()
                    .child(note(
                        language.format(
                            TextKey::BatteryMetricSupport,
                            &[
                                ("p90", &number(stats.p90, unit)),
                                ("count", &stats.known_count),
                                ("missing", &stats.missing_count),
                            ],
                        ),
                        colors,
                    )),
                );
            }
            for _ in row.len()..columns {
                line = line.child(div().flex_1());
            }
            grid = grid.child(line);
        }
        if let Some(identity) = &metrics.energy_identity {
            grid = grid.child(note(
                language.format(
                    TextKey::BatteryEnergyIdentity,
                    &[
                        ("component", &identity.component),
                        ("source", &identity.source),
                        ("scope", &identity.scope),
                        ("confidence", &identity.confidence),
                    ],
                ),
                colors,
            ));
        }
        if metrics.energy_records > metrics.energy_mah.known_count {
            grid = grid.child(note(
                language.format(
                    TextKey::BatteryEnergyOmitted,
                    &[("count", &metrics.energy_records)],
                ),
                colors,
            ));
        }
        grid.into_any_element()
    }

    fn battery_run_table(
        &mut self,
        report: &BatteryReport,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let offset = self.page.as_ref().map_or(0, |page| page.offset);
        self.battery_ui.run_offset = offset;
        let page = run_page(report, offset, EVIDENCE_PAGE_LIMIT).expect("battery run page bounds");
        let selected = self.battery_ui.selected(report).map(|run| run.id.as_str());
        let mut rows = div()
            .flex()
            .flex_col()
            .min_w(px(RUN_TABLE_WIDTH))
            .w_full()
            .child(
                div()
                    .flex()
                    .px(px(8.))
                    .py(px(6.))
                    .gap(px(4.))
                    .bg(colors.section_background)
                    .children(
                        [
                            TextKey::BatteryRuns,
                            TextKey::BatteryDuration,
                            TextKey::BatteryWakelock,
                            TextKey::BatteryAlarm,
                            TextKey::BatteryNetwork,
                            TextKey::BatteryEnergy,
                        ]
                        .into_iter()
                        .enumerate()
                        .map(|(index, key)| {
                            table_cell(language.text(key).into(), index, colors)
                                .font_weight(FontWeight::SEMIBOLD)
                        }),
                    ),
            );
        for (index, run) in page.rows.iter().enumerate() {
            let id = run.id.clone();
            let values = run_values(run, language);
            rows = rows.child(
                Button::new(("battery-run", offset + index))
                    .ghost()
                    .selected(selected == Some(run.id.as_str()))
                    .disabled(self.busy)
                    .w_full()
                    .h(px(32.))
                    .flex_shrink_0()
                    .justify_start()
                    .rounded_none()
                    .px(px(8.))
                    .border_b_1()
                    .border_color(colors.border)
                    .bg(if selected == Some(run.id.as_str()) {
                        colors.selected_row
                    } else {
                        colors.panel
                    })
                    .accessibility_label(values.join(" · "))
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(px(4.))
                            .size_full()
                            .children(
                                values
                                    .into_iter()
                                    .enumerate()
                                    .map(|(index, value)| table_cell(value, index, colors)),
                            ),
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        if !this.busy {
                            this.battery_ui.select_run(id.clone());
                            cx.notify();
                        }
                    })),
            );
        }
        studio::card(colors)
            .min_w_0()
            .flex_shrink_0()
            .child(studio::section_title(
                language.text(TextKey::BatteryExperimentRuns),
                colors,
            ))
            .child(
                div()
                    .id("battery-runs-horizontal")
                    .w_full()
                    .overflow_x_scrollbar()
                    .child(rows),
            )
            .child(
                div()
                    .flex()
                    .flex_wrap()
                    .gap(px(8.))
                    .items_center()
                    .child(
                        studio::compact_text_button(
                            "battery-runs-prev",
                            language.text(TextKey::BatteryWorkspacePrevious),
                            offset > 0 && !self.busy,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.page(this.battery_ui.run_offset.saturating_sub(PAGE), cx)
                        })),
                    )
                    .child(note(
                        language.format(
                            TextKey::BatteryRunRange,
                            &[
                                ("first", &(page.offset + 1)),
                                ("last", &(page.offset + page.rows.len())),
                                ("total", &page.total),
                            ],
                        ),
                        colors,
                    ))
                    .child(
                        studio::compact_text_button(
                            "battery-runs-next",
                            language.text(TextKey::BatteryWorkspaceNext),
                            offset + page.rows.len() < page.total && !self.busy,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.page(this.battery_ui.run_offset.saturating_add(PAGE), cx)
                        })),
                    ),
            )
            .child(note(language.text(TextKey::BatteryExportScope), colors))
            .into_any_element()
    }

    pub(super) fn publish_battery_run_page(&mut self) {
        if let Some(Session::Battery(report)) = &self.session {
            self.battery_ui
                .publish_run_page(report, self.page.as_ref().map_or(0, |page| page.offset));
        }
    }

    fn battery_diagnostics(
        &mut self,
        report: &BatteryReport,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let offset = self
            .battery_shell_ui
            .warnings_offset
            .min(report.warnings.len().saturating_sub(1))
            / PAGE
            * PAGE;
        self.battery_shell_ui.warnings_offset = offset;
        let end = offset.saturating_add(PAGE).min(report.warnings.len());
        let mut panel = studio::card(colors)
            .child(studio::section_title(
                language.text(TextKey::BatteryDiagnostics),
                colors,
            ))
            .child(note(language.text(TextKey::BatteryWarningScope), colors));
        for warning in report.warnings.iter().skip(offset).take(PAGE) {
            panel = panel.child(note(warning.clone(), colors).text_color(colors.warning));
        }
        if report.warnings.len() > PAGE {
            panel = panel.child(
                div()
                    .flex()
                    .flex_wrap()
                    .items_center()
                    .gap(px(8.))
                    .child(
                        studio::compact_text_button(
                            "battery-diagnostic-prev",
                            language.text(TextKey::BatteryWorkspacePrevious),
                            offset > 0,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.battery_shell_ui.warnings_offset =
                                this.battery_shell_ui.warnings_offset.saturating_sub(PAGE);
                            cx.notify();
                        })),
                    )
                    .child(note(
                        language.format(
                            TextKey::BatteryWorkspacePage,
                            &[
                                ("start", &(offset + 1)),
                                ("end", &end),
                                ("total", &report.warnings.len()),
                            ],
                        ),
                        colors,
                    ))
                    .child(
                        studio::compact_text_button(
                            "battery-diagnostic-next",
                            language.text(TextKey::BatteryWorkspaceNext),
                            end < report.warnings.len(),
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.battery_shell_ui.warnings_offset =
                                this.battery_shell_ui.warnings_offset.saturating_add(PAGE);
                            cx.notify();
                        })),
                    ),
            );
        }
        panel.into_any_element()
    }

    fn battery_configuration(&self, window: &mut Window, cx: &mut Context<Self>) {
        let language = self.language;
        // The dialog presents the reference's controls, but makes no editable
        // preferences that a missing capture adapter would silently ignore.
        window.open_dialog(cx, move |dialog, _, cx| {
            let colors = studio::StudioColors::from_theme(cx);
            let mut controls = div()
                .flex()
                .flex_col()
                .gap(px(8.))
                .text_size(px(studio::FONT_BODY))
                .child(note(
                    language.text(TextKey::BatteryConfigurationUnavailable),
                    &colors,
                ))
                .child(note(language.text(TextKey::BatteryConfigReadOnly), &colors));
            for (index, key) in [
                TextKey::BatteryModes,
                TextKey::BatteryDurationOptions,
                TextKey::BatteryPollingOptions,
                TextKey::BatteryRunsOptions,
                TextKey::BatteryCooldownOptions,
            ]
            .into_iter()
            .enumerate()
            {
                controls = controls.child(
                    Button::new(("battery-config-option", index))
                        .ghost()
                        .disabled(true)
                        .w_full()
                        .h(px(28.))
                        .px(px(8.))
                        .border_1()
                        .border_color(colors.border)
                        .child(
                            div()
                                .flex()
                                .items_center()
                                .justify_between()
                                .w_full()
                                .child(crate::studio::selectable(language.text(key)))
                                .child(crate::studio::selectable("⌄")),
                        ),
                );
            }
            controls = controls.child(
                Checkbox::new("battery-config-launch")
                    .with_size(gpui_kit::component::Size::Small)
                    .label(language.text(TextKey::BatteryLaunchApp))
                    .checked(false)
                    .disabled(true),
            );
            dialog
                .title(language.text(TextKey::BatteryConfiguration))
                .w(px(480.))
                .centered()
                .footer(
                    div().flex().justify_end().child(
                        studio::compact_text_button(
                            "battery-config-done",
                            language.text(TextKey::BatteryDone),
                            true,
                            true,
                            &colors,
                        )
                        .on_click(|_, window, cx| window.close_dialog(cx)),
                    ),
                )
                .child(controls)
        });
    }

    fn battery_status(&self, colors: &studio::StudioColors, cx: &mut Context<Self>) -> AnyElement {
        let text = if let Some(error) = &self.error {
            error.render(self.language)
        } else if self.busy {
            self.language.text(TextKey::ImportLoadingQuerying).into()
        } else if let Some(notice) = &self.notice {
            notice.render(self.language)
        } else {
            self.language.text(TextKey::BatteryReady).into()
        };
        let copy = text.clone();
        div()
            .id("battery-status-bar")
            .flex()
            .items_center()
            .gap(px(8.))
            .px(px(8.))
            .py(px(3.))
            .flex_shrink_0()
            .border_t_1()
            .border_color(colors.border)
            .child(note(text, colors).flex_1().min_w_0())
            .children(self.import_duration_text().map(|value| {
                studio::supporting(value, colors)
                    .flex_shrink_0()
                    .whitespace_nowrap()
            }))
            .child(
                studio::compact_text_button(
                    "battery-copy-status",
                    self.language.text(TextKey::BatteryCopyStatus),
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
}

#[cfg(test)]
mod tests {
    use super::{
        RUN_TABLE_WIDTH, RUN_WEIGHTS, SESSION_BREAKPOINT, SESSION_WIDTH, metric_columns, number,
        run_values, session_matches,
    };
    use aps_battery::BatteryReport;
    use aps_ui_foundation::Language;
    #[test]
    fn reference_layout_thresholds_and_table_weights_are_stable() {
        assert_eq!(SESSION_BREAKPOINT, 1000.);
        assert_eq!(SESSION_WIDTH, 230.);
        assert_eq!(RUN_TABLE_WIDTH, 580.);
        assert_eq!(RUN_WEIGHTS, [70., 100., 110., 90., 110., 100.]);
        assert_eq!(metric_columns(167., 6), 1);
        assert_eq!(metric_columns(344., 6), 2);
        assert_eq!(metric_columns(2000., 6), 6);
        assert_eq!(metric_columns(100., 0), 1);
    }
    #[test]
    fn unknown_is_not_zero_and_session_search_uses_source_metadata() {
        let report = BatteryReport::parse_json(br#"{"schemaVersion":1,"sessionId":"id-A","packageName":"app.Example","createdAt":"2026-11-07T00:00:00Z","runs":[{"runId":"r","iteration":1,"durationMs":1000}]}"#).unwrap();
        assert!(session_matches(&report, "example"));
        assert!(session_matches(&report, "ID-a"));
        assert!(session_matches(&report, "2026-11-07"));
        assert!(!session_matches(&report, "unknown"));
        assert_eq!(
            run_values(&report.runs()[0], Language::English),
            ["1", "1.00 s", "—", "—", "—", "—"]
        );
        assert_eq!(number(Some(0.), "mAh"), "0.00 mAh");
        assert_eq!(number(Some(-5.), "°C"), "-5.00 °C");
    }
    #[test]
    fn new_run_page_binds_selection_and_resets_only_when_outside_page() {
        let report = BatteryReport::parse_json(br#"{"schemaVersion":1,"sessionId":"s","runs":[{"runId":"a","iteration":1,"durationMs":1},{"runId":"b","iteration":2,"durationMs":1}]}"#).unwrap();
        let mut ui = super::BatteryUi::default();
        ui.publish_run_page(&report, 0);
        assert_eq!(ui.selected_run.as_deref(), Some("a"));
        ui.tab = super::ResourceTab::Warnings;
        ui.query = "draft".into();
        ui.publish_run_page(&report, 0);
        assert_eq!(ui.query, "draft");
        ui.publish_run_page(&report, 1);
        assert_eq!(ui.selected_run.as_deref(), Some("b"));
        assert_eq!(ui.tab, super::ResourceTab::Wakelocks);
        assert!(ui.query.is_empty());
    }
}
