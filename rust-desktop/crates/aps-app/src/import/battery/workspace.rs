//! Selected-run workspace. Derived report totals never become synthetic samples.
use super::*;
use gpui_kit::component::input::{Input, InputEvent, InputState};

#[derive(Default)]
pub(in crate::import) struct BatteryWorkspaceUi {
    search: Option<Entity<InputState>>,
    search_subscription: Option<Subscription>,
    context: Option<(String, ResourceTab)>,
    language: Option<Language>,
    window_offset: usize,
}

fn label(tab: ResourceTab) -> TextKey {
    match tab {
        ResourceTab::Wakelocks => TextKey::BatteryWorkspaceWakelocks,
        ResourceTab::Alarms => TextKey::BatteryWorkspaceAlarms,
        ResourceTab::Jobs => TextKey::BatteryWorkspaceJobs,
        ResourceTab::Sensors => TextKey::BatteryWorkspaceSensors,
        ResourceTab::Energy => TextKey::BatteryWorkspaceEnergy,
        ResourceTab::Network => TextKey::BatteryWorkspaceNetwork,
        ResourceTab::History => TextKey::BatteryWorkspaceHistory,
        ResourceTab::Warnings => TextKey::BatteryWorkspaceWarnings,
    }
}
fn small(text: impl Into<SharedString>, colors: &studio::StudioColors) -> Div {
    studio::supporting(text, colors)
        .line_height(px(15.))
        .flex_shrink_0()
}
fn body(text: impl Into<SharedString>, colors: &studio::StudioColors) -> Div {
    div()
        .flex_shrink_0()
        .text_size(px(13.))
        .line_height(px(18.))
        .text_color(colors.text)
        .child(crate::studio::selectable(text.into()))
}
fn detail(
    key: TextKey,
    value: impl Into<SharedString>,
    language: Language,
    colors: &studio::StudioColors,
) -> Div {
    div()
        .flex()
        .flex_col()
        .flex_shrink_0()
        .gap(px(4.))
        .child(small(language.text(key), colors))
        .child(body(value, colors))
}
fn unavailable_chart(
    title: TextKey,
    clock: TextKey,
    language: Language,
    colors: &studio::StudioColors,
) -> Div {
    studio::card(colors)
        .flex_1()
        .min_w_0()
        .flex_shrink_0()
        .child(studio::section_title(language.text(title), colors))
        .child(small(language.text(clock), colors))
        .child(
            div()
                .min_h(px(110.))
                .flex()
                .items_center()
                .p(px(8.))
                .child(small(
                    language.text(TextKey::BatteryWorkspaceRawMissing),
                    colors,
                )),
        )
}
fn cells(row: &EvidenceRow, language: Language) -> [String; 4] {
    let unknown = language.text(TextKey::BatteryWorkspaceUnknown);
    let absent = language.text(TextKey::BatteryWorkspaceUnavailable);
    match row {
        EvidenceRow::Timer {
            name,
            duration_ms,
            count,
            confidence,
            ..
        } => [
            name.clone(),
            format!("{duration_ms} ms"),
            count.to_string(),
            confidence.clone().unwrap_or_else(|| unknown.into()),
        ],
        EvidenceRow::Energy {
            component,
            energy_mah,
            energy_uws,
            confidence,
            ..
        } => [
            component.clone(),
            energy_mah
                .map(|v| format!("{v} mAh"))
                .or_else(|| energy_uws.map(|v| format!("{v} µWs")))
                .unwrap_or_else(|| absent.into()),
            "—".into(),
            confidence.clone(),
        ],
        EvidenceRow::Network { bytes, .. } => [
            language.text(TextKey::BatteryWorkspaceNetworkTotal).into(),
            bytes
                .map(|v| format!("{v} B"))
                .unwrap_or_else(|| absent.into()),
            "—".into(),
            unknown.into(),
        ],
        EvidenceRow::Warning { message, .. } => {
            [message.clone(), "—".into(), "—".into(), unknown.into()]
        }
    }
}
fn cell(text: String, index: usize, colors: &studio::StudioColors) -> Div {
    // Match the reference's 2.5 / 1.4 / 0.7 / 1 column proportions.
    let weight = [2.5, 1.4, 0.7, 1.][index];
    body(text, colors)
        .flex_basis(px(0.))
        .flex_grow(weight)
        .min_w_0()
        .whitespace_nowrap()
        .text_ellipsis()
}

impl ImportWorkspace {
    fn battery_search(
        &mut self,
        run: &aps_battery::BatteryReportRun,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Entity<InputState> {
        let identity = (run.id.clone(), self.battery_ui.tab);
        if self.battery_workspace_ui.context.as_ref() != Some(&identity) {
            self.battery_workspace_ui = BatteryWorkspaceUi::default();
            self.battery_workspace_ui.context = Some(identity);
        }
        if self.battery_workspace_ui.search.is_none() {
            let language = self.language;
            let query = self.battery_ui.query.clone();
            let search = cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::BatteryWorkspaceSearch))
                    .default_value(query)
                    .validate(|value, _| value.len() <= QUERY_LIMIT)
            });
            self.battery_workspace_ui.search_subscription =
                Some(cx.subscribe(&search, |this, search, event, cx| {
                    if matches!(event, InputEvent::Change) {
                        this.battery_ui.query = search.read(cx).value().to_string();
                        this.battery_ui.evidence_offset = 0;
                        this.battery_ui.selected_evidence = None;
                        cx.notify();
                    }
                }));
            self.battery_workspace_ui.search = Some(search);
        }
        let search = self
            .battery_workspace_ui
            .search
            .clone()
            .expect("battery search initialized");
        if self.battery_workspace_ui.language != Some(self.language) {
            search.update(cx, |input, cx| {
                input.set_placeholder(
                    self.language.text(TextKey::BatteryWorkspaceSearch),
                    window,
                    cx,
                )
            });
            self.battery_workspace_ui.language = Some(self.language);
        }
        search
    }

    pub(super) fn render_battery_workspace(
        &mut self,
        run: &aps_battery::BatteryReportRun,
        width: f32,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let colors = studio::StudioColors::from_theme(cx);
        let search = self.battery_search(run, window, cx);
        let wide = wide_workspace(width);
        let mut charts = div()
            .flex()
            .gap(px(4.))
            .flex_shrink_0()
            .when(!wide, |s| s.flex_col());
        charts = charts
            .child(unavailable_chart(
                TextKey::BatteryWorkspaceSnapshots,
                TextKey::BatteryWorkspaceSnapshotClock,
                language,
                &colors,
            ))
            .child(unavailable_chart(
                TextKey::BatteryWorkspaceHistoryChart,
                TextKey::BatteryWorkspaceHistoryClock,
                language,
                &colors,
            ));
        let mut tabs = div().flex().flex_wrap().gap(px(4.));
        for (index, tab) in ResourceTab::ALL.into_iter().enumerate() {
            tabs = tabs.child(
                studio::tab_button(
                    ("battery-resource-tab", index),
                    language.text(label(tab)),
                    self.battery_ui.tab == tab,
                    &colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.battery_ui.select_tab(tab);
                    cx.notify();
                })),
            );
        }
        let page = evidence_page(
            run,
            self.battery_ui.tab,
            &self.battery_ui.query,
            self.battery_ui.evidence_offset,
            EVIDENCE_PAGE_LIMIT,
        );
        let mut resources = studio::card(&colors)
            .flex_shrink_0()
            .child(studio::section_title(
                language.format(
                    TextKey::BatteryWorkspaceResources,
                    &[("iteration", &run.iteration.to_string())],
                ),
                &colors,
            ))
            .child(tabs)
            .child(Input::new(&search).w_full());
        match page {
            Err(error) => resources = resources.child(body(error, &colors)),
            Ok(page) => {
                resources = resources.child(small(
                    language.format(
                        TextKey::BatteryWorkspaceMatches,
                        &[
                            ("visible", &page.rows.len().to_string()),
                            ("total", &page.total.to_string()),
                            ("all", &page.unfiltered_total.to_string()),
                        ],
                    ),
                    &colors,
                ));
                let selected = page.selected(self.battery_ui.selected_evidence);
                let table =
                    self.battery_evidence_table(&page, selected.map(EvidenceRow::id), &colors, cx);
                let inspector = self.battery_evidence_inspector(selected, &colors, cx);
                resources = resources.child(
                    div()
                        .flex()
                        .gap(px(4.))
                        .min_w_0()
                        .when(!wide, |s| s.flex_col())
                        .child(div().flex_1().min_w_0().child(table))
                        .child(
                            div()
                                .when(wide, |s| s.w(px(290.)).flex_shrink_0())
                                .when(!wide, |s| s.w_full())
                                .child(inspector),
                        ),
                );
                let start = if page.total == 0 { 0 } else { page.offset + 1 };
                let end = page.offset.saturating_add(page.rows.len()).min(page.total);
                resources = resources.child(
                    div()
                        .flex()
                        .items_center()
                        .gap(px(8.))
                        .flex_wrap()
                        .child(
                            studio::compact_text_button(
                                "battery-evidence-prev",
                                language.text(TextKey::BatteryWorkspacePrevious),
                                page.offset > 0,
                                false,
                                &colors,
                            )
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.battery_ui.evidence_offset = this
                                    .battery_ui
                                    .evidence_offset
                                    .saturating_sub(EVIDENCE_PAGE_LIMIT);
                                this.battery_ui.selected_evidence = None;
                                cx.notify();
                            })),
                        )
                        .child(small(
                            language.format(
                                TextKey::BatteryWorkspacePage,
                                &[
                                    ("start", &start.to_string()),
                                    ("end", &end.to_string()),
                                    ("total", &page.total.to_string()),
                                ],
                            ),
                            &colors,
                        ))
                        .child(
                            studio::compact_text_button(
                                "battery-evidence-next",
                                language.text(TextKey::BatteryWorkspaceNext),
                                end < page.total,
                                false,
                                &colors,
                            )
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.battery_ui.evidence_offset = this
                                    .battery_ui
                                    .evidence_offset
                                    .saturating_add(EVIDENCE_PAGE_LIMIT);
                                this.battery_ui.selected_evidence = None;
                                cx.notify();
                            })),
                        ),
                );
            }
        }
        let window_count = run.performance_windows.as_ref().map_or(0, Vec::len);
        let offset = self.battery_workspace_ui.window_offset;
        let mut thermal = thermal_summary(run, language, &colors, offset);
        if window_count > 100 {
            let end = offset.saturating_add(100).min(window_count);
            thermal = thermal.child(
                div()
                    .flex()
                    .flex_wrap()
                    .items_center()
                    .gap(px(8.))
                    .child(
                        studio::compact_text_button(
                            "battery-windows-prev",
                            language.text(TextKey::BatteryWorkspacePrevious),
                            offset > 0,
                            false,
                            &colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.battery_workspace_ui.window_offset =
                                this.battery_workspace_ui.window_offset.saturating_sub(100);
                            cx.notify();
                        })),
                    )
                    .child(small(
                        language.format(
                            TextKey::BatteryWorkspacePage,
                            &[
                                ("start", &(offset + 1).to_string()),
                                ("end", &end.to_string()),
                                ("total", &window_count.to_string()),
                            ],
                        ),
                        &colors,
                    ))
                    .child(
                        studio::compact_text_button(
                            "battery-windows-next",
                            language.text(TextKey::BatteryWorkspaceNext),
                            end < window_count,
                            false,
                            &colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.battery_workspace_ui.window_offset =
                                this.battery_workspace_ui.window_offset.saturating_add(100);
                            cx.notify();
                        })),
                    ),
            );
        }
        div()
            .flex()
            .flex_col()
            .gap(px(4.))
            .flex_shrink_0()
            .min_w_0()
            .child(charts)
            .child(resources)
            .child(thermal)
            .into_any_element()
    }

    fn battery_evidence_table(
        &self,
        page: &EvidencePage,
        selected: Option<EvidenceId>,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let header = div()
            .flex()
            .gap(px(4.))
            .h(px(28.))
            .items_center()
            .px(px(8.))
            .bg(colors.section_background)
            .children(
                [
                    TextKey::BatteryWorkspaceName,
                    TextKey::BatteryWorkspaceValue,
                    TextKey::BatteryWorkspaceCount,
                    TextKey::BatteryWorkspaceConfidence,
                ]
                .into_iter()
                .enumerate()
                .map(|(index, key)| cell(language.text(key).into(), index, colors)),
            );
        let mut rows = div()
            .flex()
            .flex_col()
            .max_h(px(280.))
            .min_h(px(80.))
            .overflow_y_scrollbar();
        if page.rows.is_empty() {
            rows = rows.child(div().p(px(8.)).child(small(
                language.text(if page.unavailable {
                    TextKey::BatteryWorkspaceEvidenceMissing
                } else {
                    TextKey::BatteryWorkspaceEmpty
                }),
                colors,
            )));
        }
        for row in &page.rows {
            let id = row.id();
            let values = cells(row, language);
            rows = rows.child(
                Button::new(("battery-evidence-row", id.source_index))
                    .ghost()
                    .selected(selected == Some(id))
                    .w_full()
                    .h(px(36.))
                    .flex_shrink_0()
                    .justify_start()
                    .rounded_none()
                    .px(px(8.))
                    .bg(if selected == Some(id) {
                        colors.selected_row
                    } else {
                        colors.panel
                    })
                    .border_b_1()
                    .border_color(colors.border)
                    .accessibility_label(values.join(" · "))
                    .child(
                        div()
                            .flex()
                            .gap(px(4.))
                            .items_center()
                            .size_full()
                            .children(
                                values
                                    .into_iter()
                                    .enumerate()
                                    .map(|(index, value)| cell(value, index, colors)),
                            ),
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.battery_ui.selected_evidence = Some(id);
                        cx.notify();
                    })),
            );
        }
        div()
            .min_w_0()
            .overflow_x_scrollbar()
            .child(div().min_w(px(530.)).w_full().child(header).child(rows))
            .into_any_element()
    }

    fn battery_evidence_inspector(
        &self,
        row: Option<&EvidenceRow>,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let absent = language.text(TextKey::BatteryWorkspaceUnavailable);
        let mut content = div()
            .flex()
            .flex_col()
            .gap(px(8.))
            .max_h(px(340.))
            .min_h(px(100.))
            .overflow_y_scrollbar();
        if let Some(row) = row {
            let values = cells(row, language);
            content =
                content.child(body(values[0].clone(), colors).font_weight(FontWeight::SEMIBOLD));
            match row {
                EvidenceRow::Timer {
                    duration_ms,
                    count,
                    confidence,
                    ..
                } => {
                    content = content
                        .child(detail(
                            TextKey::BatteryWorkspaceDuration,
                            format!("{duration_ms} ms"),
                            language,
                            colors,
                        ))
                        .child(detail(
                            TextKey::BatteryWorkspaceCount,
                            count.to_string(),
                            language,
                            colors,
                        ))
                        .child(detail(
                            TextKey::BatteryWorkspaceSource,
                            language.text(TextKey::BatteryWorkspaceDerived),
                            language,
                            colors,
                        ))
                        .child(detail(
                            TextKey::BatteryWorkspaceConfidence,
                            confidence.clone().unwrap_or_else(|| {
                                language.text(TextKey::BatteryWorkspaceUnknown).into()
                            }),
                            language,
                            colors,
                        ))
                        .child(small(
                            language.text(TextKey::BatteryWorkspaceTimerNote),
                            colors,
                        ));
                }
                EvidenceRow::Energy {
                    energy_mah,
                    energy_uws,
                    source,
                    scope,
                    confidence,
                    ..
                } => {
                    let amounts = energy_mah
                        .map(|v| format!("{v} mAh"))
                        .into_iter()
                        .chain(energy_uws.map(|v| format!("{v} µWs")))
                        .collect::<Vec<_>>()
                        .join(" · ");
                    content = content
                        .child(detail(
                            TextKey::BatteryWorkspaceValue,
                            if amounts.is_empty() {
                                absent.to_owned()
                            } else {
                                amounts
                            },
                            language,
                            colors,
                        ))
                        .child(detail(
                            TextKey::BatteryWorkspaceSource,
                            source.clone(),
                            language,
                            colors,
                        ))
                        .child(detail(
                            TextKey::BatteryWorkspaceScope,
                            scope.clone(),
                            language,
                            colors,
                        ))
                        .child(detail(
                            TextKey::BatteryWorkspaceConfidence,
                            confidence.clone(),
                            language,
                            colors,
                        ))
                        .child(small(
                            language.text(TextKey::BatteryWorkspaceConfidenceNote),
                            colors,
                        ));
                }
                EvidenceRow::Network { .. } => {
                    content = content
                        .child(detail(
                            TextKey::BatteryWorkspaceValue,
                            values[1].clone(),
                            language,
                            colors,
                        ))
                        .child(detail(
                            TextKey::BatteryWorkspaceSource,
                            language.text(TextKey::BatteryWorkspaceDerived),
                            language,
                            colors,
                        ))
                        .child(small(
                            language.text(TextKey::BatteryWorkspaceNetworkNote),
                            colors,
                        ))
                }
                EvidenceRow::Warning { message, .. } => {
                    content = content.child(detail(
                        TextKey::BatteryWorkspaceRaw,
                        message.clone(),
                        language,
                        colors,
                    ))
                }
            }
            content = content.child(small(
                language.text(TextKey::BatteryWorkspaceDerivedNote),
                colors,
            ));
            let copy = match row {
                EvidenceRow::Energy {
                    source,
                    scope,
                    energy_mah,
                    energy_uws,
                    ..
                } => format!(
                    "{}\n{}: {} mAh / {} µWs\n{}: {}\n{}: {}\n{}: {}",
                    values[0],
                    language.text(TextKey::BatteryWorkspaceValue),
                    energy_mah
                        .map(|v| v.to_string())
                        .unwrap_or_else(|| absent.into()),
                    energy_uws
                        .map(|v| v.to_string())
                        .unwrap_or_else(|| absent.into()),
                    language.text(TextKey::BatteryWorkspaceSource),
                    source,
                    language.text(TextKey::BatteryWorkspaceScope),
                    scope,
                    language.text(TextKey::BatteryWorkspaceConfidence),
                    values[3]
                ),
                EvidenceRow::Warning { message, .. } => message.clone(),
                _ => values.join("\n"),
            };
            content = content.child(
                studio::compact_text_button(
                    "battery-evidence-copy",
                    language.text(TextKey::BatteryWorkspaceCopy),
                    true,
                    false,
                    colors,
                )
                .on_click(cx.listener(move |_, _, _, cx| {
                    cx.write_to_clipboard(ClipboardItem::new_string(copy.clone()))
                })),
            );
        } else {
            content = content.child(small(
                language.text(TextKey::BatteryWorkspaceSelect),
                colors,
            ));
        }
        studio::card(colors)
            .child(studio::section_title(
                language.text(TextKey::BatteryWorkspaceInspector),
                colors,
            ))
            .child(content)
            .into_any_element()
    }
}

fn thermal_summary(
    run: &aps_battery::BatteryReportRun,
    language: Language,
    colors: &studio::StudioColors,
    offset: usize,
) -> Div {
    let absent = language.text(TextKey::BatteryWorkspaceUnavailable);
    let temperature = run
        .peak_temperature_celsius
        .map(|v| format!("{v} °C"))
        .unwrap_or_else(|| absent.into());
    let status = run
        .max_thermal_status
        .map(|v| v.to_string())
        .unwrap_or_else(|| absent.into());
    let throttled = run
        .throttled_samples
        .map(|v| v.to_string())
        .unwrap_or_else(|| absent.into());
    let mut panel = studio::card(colors)
        .flex_shrink_0()
        .child(studio::section_title(
            language.text(TextKey::BatteryWorkspaceWindows),
            colors,
        ))
        .child(
            div()
                .flex()
                .flex_wrap()
                .gap(px(16.))
                .child(detail(
                    TextKey::BatteryWorkspaceTemperature,
                    temperature,
                    language,
                    colors,
                ))
                .child(detail(
                    TextKey::BatteryWorkspaceThermal,
                    status,
                    language,
                    colors,
                ))
                .child(detail(
                    TextKey::BatteryWorkspaceThrottledSamples,
                    throttled,
                    language,
                    colors,
                )),
        );
    if let Some(windows) = &run.performance_windows {
        // Bound UI work independently of artifact size; retained windows stay in source.
        for (index, window) in windows.iter().enumerate().skip(offset).take(100) {
            let duration = window
                .duration_ms
                .map(|v| format!("{v} ms"))
                .unwrap_or_else(|| absent.into());
            let temperature = window
                .peak_temperature_celsius
                .map(|v| format!("{v} °C"))
                .unwrap_or_else(|| absent.into());
            let status = window
                .max_thermal_status
                .map(|v| v.to_string())
                .unwrap_or_else(|| absent.into());
            let throttled = window
                .throttled
                .map(|v| {
                    language.text(if v {
                        TextKey::BatteryWorkspaceYes
                    } else {
                        TextKey::BatteryWorkspaceNo
                    })
                })
                .unwrap_or(absent);
            panel = panel
                .child(body(
                    language.format(
                        TextKey::BatteryWorkspaceWindow,
                        &[
                            ("index", &(index + 1).to_string()),
                            ("duration", &duration),
                            ("temperature", &temperature),
                            ("status", &status),
                            ("throttled", &throttled),
                        ],
                    ),
                    colors,
                ))
                .child(small(
                    language.format(
                        TextKey::BatteryWorkspaceWindowBounds,
                        &[
                            ("start", &window.started_at.as_deref().unwrap_or(absent)),
                            ("end", &window.ended_at.as_deref().unwrap_or(absent)),
                        ],
                    ),
                    colors,
                ));
        }
        if windows.is_empty() {
            panel = panel.child(small(language.text(TextKey::BatteryWorkspaceEmpty), colors));
        }
    } else {
        panel = panel.child(small(
            language.text(TextKey::BatteryWorkspaceWindowsMissing),
            colors,
        ));
    }
    panel.child(small(
        language.text(TextKey::BatteryWorkspaceDerivedNote),
        colors,
    ))
}

fn wide_workspace(width: f32) -> bool {
    width >= 860.
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn reference_breakpoint_includes_860() {
        assert!(!wide_workspace(859.));
        assert!(wide_workspace(860.));
    }

    #[::core::prelude::v1::test]
    fn missing_network_and_measured_zero_remain_distinct() {
        let id = EvidenceId {
            tab: ResourceTab::Network,
            source_index: 0,
        };
        let missing = cells(&EvidenceRow::Network { id, bytes: None }, Language::English);
        let zero = cells(
            &EvidenceRow::Network { id, bytes: Some(0) },
            Language::English,
        );
        assert_eq!(missing[1], "Unavailable");
        assert_eq!(zero[1], "0 B");
        assert_eq!(zero[3], "Unknown");
    }

    #[::core::prelude::v1::test]
    fn source_warning_and_confidence_are_not_localized() {
        let id = EvidenceId {
            tab: ResourceTab::Warnings,
            source_index: 4,
        };
        let warning = cells(
            &EvidenceRow::Warning {
                id,
                message: "source raw text 123".into(),
            },
            Language::SimplifiedChinese,
        );
        assert_eq!(warning[0], "source raw text 123");
        let energy = cells(
            &EvidenceRow::Energy {
                id: EvidenceId {
                    tab: ResourceTab::Energy,
                    source_index: 2,
                },
                component: "CPU-source-label".into(),
                energy_mah: Some(0.),
                energy_uws: Some(3),
                source: "unrecognized-source".into(),
                scope: "UID".into(),
                confidence: "custom-confidence".into(),
            },
            Language::SimplifiedChinese,
        );
        assert_eq!(energy[0], "CPU-source-label");
        assert_eq!(energy[1], "0 mAh");
        assert_eq!(energy[3], "custom-confidence");
    }
}
