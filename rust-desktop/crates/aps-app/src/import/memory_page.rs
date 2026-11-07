//! Offline HPROF workspace. Only published worker evidence is presented.
use super::*;
use aps_memory::{FindingKind, HistogramQuery, HistogramSort, MAX_HISTOGRAM_SEARCH_BYTES};
use gpui_kit::component::input::{Input, InputEvent, InputState};

#[derive(Default)]
pub(super) struct MemoryUi {
    pub evidence_open: bool,
    pub query: HistogramQuery,
    pub published_query: HistogramQuery,
    pub search: Option<Entity<InputState>>,
    pub search_subscription: Option<Subscription>,
    pub selected_class: Option<String>,
    pub selected_object: Option<u64>,
    classes: Option<PageData>,
    instances: Option<(String, PageData)>,
    detail: Option<(u64, PageData)>,
    dominators: Option<PageData>,
    findings: Option<PageData>,
    search_language: Option<Language>,
    reset_search: bool,
}

impl MemoryUi {
    pub(super) fn remember(&mut self, view: &MemoryView, page: &PageData) {
        match view {
            MemoryView::Histogram | MemoryView::FilteredHistogram(_) => {
                self.classes = Some(page.clone())
            }
            MemoryView::Instances(name) => self.instances = Some((name.clone(), page.clone())),
            MemoryView::Detail(id) => self.detail = Some((*id, page.clone())),
            MemoryView::Dominators(_) => self.dominators = Some(page.clone()),
            MemoryView::Findings | MemoryView::FindingCategory(_) => {
                self.findings = Some(page.clone())
            }
        }
    }
}

impl ImportWorkspace {
    fn memory_search(&mut self, window: &mut Window, cx: &mut Context<Self>) -> Entity<InputState> {
        if self.memory_ui.search.is_none() {
            let language = self.language;
            let input = cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::MemoryPageSearchHint))
                    .validate(|text, _| text.len() <= MAX_HISTOGRAM_SEARCH_BYTES)
            });
            self.memory_ui.search_subscription =
                Some(cx.subscribe(&input, |this, input, event, cx| {
                    if matches!(event, InputEvent::PressEnter { .. }) && !this.busy {
                        let mut query = this.memory_ui.query.clone();
                        query.search = input.read(cx).value().to_string();
                        this.query_memory_histogram(query, 0, cx);
                    }
                }));
            self.memory_ui.search = Some(input);
        }
        let input = self.memory_ui.search.clone().expect("memory search exists");
        let reset = self.memory_ui.reset_search;
        let language_changed = self.memory_ui.search_language != Some(self.language);
        self.memory_ui.search_language = Some(self.language);
        self.memory_ui.reset_search = false;
        if reset || language_changed {
            input.update(cx, |state, cx| {
                if language_changed {
                    state.set_placeholder(
                        self.language.text(TextKey::MemoryPageSearchHint),
                        window,
                        cx,
                    );
                }
                if reset {
                    state.set_value("", window, cx);
                }
            });
        }
        input
    }

    pub(super) fn render_memory_workspace(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = studio::StudioColors::from_theme(cx);
        let language = self.language;
        let loaded = matches!(self.session, Some(Session::Memory(_)));
        if let Some(page) = self.page.as_ref() {
            self.memory_ui.remember(&self.published_memory_view, page);
        }
        let mut root = studio::page(&colors)
            .id("memory-profiler-workspace")
            .min_h_0()
            .child(self.memory_toolbar(&colors, cx))
            .child(self.memory_tabs(&colors, cx))
            .child(self.memory_modes(&colors, cx));
        if matches!(
            self.memory_panel,
            MemoryPanel::Dashboard | MemoryPanel::Classes
        ) {
            let search = self.memory_search(window, cx);
            root = root
                .child(self.memory_class_filters(search, loaded, &colors, cx))
                .child(self.memory_summary(&colors))
                .child(self.memory_distribution(&colors));
        }
        root = if !loaded {
            root.child(self.memory_unavailable(language.text(TextKey::MemoryPageEmpty), &colors))
        } else {
            match self.memory_panel {
                MemoryPanel::Dashboard | MemoryPanel::Classes => {
                    root.child(self.memory_class_analysis(&colors, cx))
                }
                MemoryPanel::Dominators | MemoryPanel::Findings => {
                    root.child(self.memory_three_pane(&colors, cx))
                }
                MemoryPanel::Diff => root.child(self.memory_diff(&colors, cx)),
                MemoryPanel::NativeHeap | MemoryPanel::BitmapDump => {
                    root.child(self.memory_unavailable(
                        language.text(TextKey::MemoryPageArtifactUnavailable),
                        &colors,
                    ))
                }
            }
        };
        root.child(
            div()
                .flex()
                .items_center()
                .gap_1()
                .child(studio::supporting(
                    language.text(TextKey::MemoryPageOfflineEvidence),
                    &colors,
                ))
                .child(
                    studio::compact_text_button(
                        "memory-evidence-toggle",
                        language.text(TextKey::MemoryPageEvidence),
                        true,
                        false,
                        &colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.memory_ui.evidence_open = !this.memory_ui.evidence_open;
                        cx.notify();
                    })),
                ),
        )
        .children(self.memory_ui.evidence_open.then(|| {
            studio::card(&colors).child(studio::supporting(
                language.text(TextKey::MemoryPageEvidenceBoundary),
                &colors,
            ))
        }))
        .into_any_element()
    }

    fn memory_toolbar(
        &mut self,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let loaded = matches!(self.session, Some(Session::Memory(_)));
        div()
            .id("memory-profiler-toolbar")
            .flex()
            .items_center()
            .gap_1()
            .h(px(40.))
            .overflow_x_scrollbar()
            .border_b_1()
            .border_color(colors.border)
            .child(
                studio::compact_text_button(
                    "memory-import",
                    language.text(TextKey::MemoryPageImport),
                    !self.busy,
                    true,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.pick(cx))),
            )
            .child(
                studio::compact_text_button(
                    "memory-back",
                    language.text(TextKey::CommonBack),
                    loaded && !self.busy && !self.memory_history.is_empty(),
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.memory_back(cx))),
            )
            .child(
                studio::compact_text_button(
                    "memory-prev",
                    language.text(TextKey::CommonPrevious),
                    loaded && !self.busy && self.page.as_ref().is_some_and(|p| p.offset > 0),
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.memory_page_step(false, cx))),
            )
            .child(
                studio::compact_text_button(
                    "memory-next",
                    language.text(TextKey::CommonNext),
                    loaded && !self.busy && self.page.as_ref().is_some_and(|p| p.has_more),
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.memory_page_step(true, cx))),
            )
            .child(
                studio::compact_text_button(
                    "memory-cancel",
                    language.text(TextKey::ImportCancelOperation),
                    self.busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.cancel_work(cx))),
            )
            .child(
                studio::compact_text_button(
                    "memory-close",
                    language.text(TextKey::CommonClose),
                    loaded,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.close(cx))),
            )
            .child(studio::supporting(
                language.text(TextKey::MemoryPageToolbarUnavailable),
                colors,
            ))
            .into_any_element()
    }

    fn memory_page_step(&mut self, next: bool, cx: &mut Context<Self>) {
        let Some(page) = self.page.as_ref() else {
            return;
        };
        let offset = if next {
            page.offset.saturating_add(PAGE)
        } else {
            page.offset.saturating_sub(PAGE)
        };
        if matches!(
            self.memory_view,
            MemoryView::Histogram | MemoryView::FilteredHistogram(_)
        ) {
            self.query_memory_histogram(self.memory_ui.published_query.clone(), offset, cx);
        } else {
            self.page(offset, cx);
        }
    }

    fn memory_tabs(&mut self, colors: &studio::StudioColors, cx: &mut Context<Self>) -> AnyElement {
        let language = self.language;
        let mut row = div()
            .id("memory-snapshot-tabs")
            .flex()
            .items_center()
            .gap_2()
            .h(px(32.))
            .overflow_x_scrollbar()
            .child(studio::supporting(
                language.text(TextKey::MemoryPageSnapshots),
                colors,
            ));
        for (id, label, active) in self.memory_snapshot_tabs() {
            row = row.child(
                div()
                    .flex()
                    .items_center()
                    .gap_1()
                    .h(px(28.))
                    .min_w(px(120.))
                    .max_w(px(260.))
                    .border_1()
                    .border_color(colors.border)
                    .bg(if active {
                        colors.selected_row
                    } else {
                        colors.panel
                    })
                    .child(
                        studio::compact_text_button(
                            ("memory-tab", id),
                            label,
                            !self.busy,
                            false,
                            colors,
                        )
                        .selected(active)
                        .on_click(
                            cx.listener(move |this, _, _, cx| this.select_memory_snapshot(id, cx)),
                        ),
                    )
                    .child(
                        studio::compact_text_button(
                            ("memory-tab-close", id),
                            "×",
                            !self.busy,
                            false,
                            colors,
                        )
                        .on_click(
                            cx.listener(move |this, _, _, cx| this.close_memory_snapshot(id, cx)),
                        ),
                    ),
            );
        }
        row.into_any_element()
    }

    fn memory_modes(
        &mut self,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let loaded = matches!(self.session, Some(Session::Memory(_)));
        let mut row = div()
            .id("memory-mode-tabs")
            .flex()
            .items_center()
            .gap_1()
            .overflow_x_scrollbar()
            .h(px(32.));
        for panel in MemoryPanel::ALL {
            row = row.child(
                studio::compact_text_button(
                    format!("memory-mode-{panel:?}"),
                    panel.label(language),
                    loaded && !self.busy,
                    false,
                    colors,
                )
                .selected(self.memory_panel == panel)
                .on_click(cx.listener(move |this, _, _, cx| this.memory_panel(panel, cx))),
            );
        }
        row.child(studio::compact_text_button(
            "memory-sampled-art",
            language.text(TextKey::MemoryPageSampledArt),
            false,
            false,
            colors,
        ))
        .child(studio::compact_text_button(
            "memory-leakcanary",
            language.text(TextKey::MemoryPageLeakcanary),
            false,
            false,
            colors,
        ))
        .into_any_element()
    }

    fn memory_class_filters(
        &mut self,
        search: Entity<InputState>,
        loaded: bool,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let mut row = div()
            .id("memory-primary-filter-bar")
            .flex()
            .items_center()
            .gap_1()
            .overflow_x_scrollbar()
            .child(studio::supporting(
                language.text(TextKey::MemoryPageHeapAll),
                colors,
            ))
            .child(studio::supporting(
                language.text(TextKey::MemoryPageScopeAll),
                colors,
            ))
            .child(div().w(px(210.)).child(Input::new(&search).h(px(24.))))
            .child(
                studio::compact_text_button(
                    "memory-search-apply",
                    language.text(TextKey::MemoryPageApply),
                    loaded && !self.busy,
                    true,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    let mut query = this.memory_ui.query.clone();
                    query.search = this
                        .memory_ui
                        .search
                        .as_ref()
                        .map(|s| s.read(cx).value().to_string())
                        .unwrap_or_default();
                    this.query_memory_histogram(query, 0, cx);
                })),
            )
            .child(
                studio::compact_text_button(
                    "memory-search-reset",
                    language.text(TextKey::MemoryPageReset),
                    loaded && !self.busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.memory_ui.reset_search = true;
                    this.query_memory_histogram(HistogramQuery::default(), 0, cx);
                })),
            )
            .child(
                studio::compact_text_button(
                    "memory-match-case",
                    language.text(TextKey::MemoryPageMatchCase),
                    loaded && !self.busy,
                    false,
                    colors,
                )
                .selected(self.memory_ui.query.match_case)
                .on_click(cx.listener(|this, _, _, cx| {
                    this.memory_ui.query.match_case = !this.memory_ui.query.match_case;
                    cx.notify();
                })),
            );
        for (sort, key) in [
            (HistogramSort::CountDescending, TextKey::MemoryPageSortCount),
            (
                HistogramSort::ShallowDescending,
                TextKey::MemoryPageSortShallow,
            ),
            (
                HistogramSort::RetainedDescending,
                TextKey::MemoryPageSortRetained,
            ),
            (
                HistogramSort::ClassNameAscending,
                TextKey::MemoryPageSortClass,
            ),
        ] {
            row = row.child(
                studio::compact_text_button(
                    format!("memory-sort-{sort:?}"),
                    language.text(key),
                    loaded && !self.busy,
                    false,
                    colors,
                )
                .selected(self.memory_ui.query.sort == sort)
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.memory_ui.query.sort = sort;
                    let mut query = this.memory_ui.query.clone();
                    query.search = this
                        .memory_ui
                        .search
                        .as_ref()
                        .map(|s| s.read(cx).value().to_string())
                        .unwrap_or_default();
                    this.query_memory_histogram(query, 0, cx);
                })),
            );
        }
        row.into_any_element()
    }

    fn memory_summary(&self, colors: &studio::StudioColors) -> AnyElement {
        let language = self.language;
        let summary = self
            .memory_ui
            .classes
            .as_ref()
            .and_then(|page| page.ui.memory_summary.as_ref());
        let count =
            |value: Option<u64>| value.map_or_else(|| "—".to_owned(), |value| value.to_string());
        let values = [
            (TextKey::MemoryPageLifecycleLeaks, "—".to_owned()),
            (TextKey::MemoryPageDuplicateBitmaps, "—".to_owned()),
            (
                TextKey::MemoryPageClasses,
                count(summary.and_then(|s| s.class_count)),
            ),
            (
                TextKey::MemoryPageCount,
                count(summary.and_then(|s| s.object_count)),
            ),
            (TextKey::MemoryPageNativeSize, "—".to_owned()),
            (
                TextKey::MemoryPageShallow,
                count(summary.and_then(|s| s.shallow_size)),
            ),
            (TextKey::MemoryPageRetainedNative, "—".to_owned()),
            (TextKey::MemoryPageRetained, "—".to_owned()),
        ];
        div()
            .id("memory-summary")
            .flex()
            .flex_wrap()
            .gap_1()
            .children(values.into_iter().map(|(key, value)| {
                studio::card(colors)
                    .min_w(px(130.))
                    .flex_1()
                    .child(studio::supporting(language.text(key), colors))
                    .child(div().text_size(px(typography::TITLE)).child(value))
            }))
            .child(studio::supporting(
                language.text(TextKey::MemoryPageSummaryScope),
                colors,
            ))
            .into_any_element()
    }

    fn memory_distribution(&self, colors: &studio::StudioColors) -> AnyElement {
        let language = self.language;
        let segments = self
            .memory_ui
            .classes
            .as_ref()
            .and_then(|page| page.ui.memory_summary.as_ref())
            .map(|summary| summary.heap_segments.as_slice())
            .unwrap_or(&[]);
        let mut card =
            studio::card(colors)
                .id("memory-distribution-card")
                .child(studio::section_title(
                    language.text(TextKey::MemoryPageHeapDistribution),
                    colors,
                ));
        if segments.is_empty() {
            card = card.child(studio::supporting(
                language.text(TextKey::MemoryPageDistributionUnavailable),
                colors,
            ));
        } else {
            let total = segments
                .iter()
                .fold(0_u64, |sum, (_, bytes, _)| sum.saturating_add(*bytes));
            for (name, bytes, objects) in segments {
                let width = if total == 0 {
                    0.
                } else {
                    (*bytes as f64 / total as f64 * 300.).max(1.) as f32
                };
                card = card.child(
                    div()
                        .flex()
                        .items_center()
                        .gap_2()
                        .child(
                            div()
                                .w(px(300.))
                                .h(px(8.))
                                .border_1()
                                .border_color(colors.border)
                                .child(div().w(px(width)).h(px(6.)).bg(colors.accent)),
                        )
                        .child(studio::supporting(
                            format!(
                                "{name} · {bytes} B · {objects} {}",
                                language.text(TextKey::MemoryPageObjects)
                            ),
                            colors,
                        )),
                );
            }
        }
        card.into_any_element()
    }

    fn memory_class_analysis(
        &mut self,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let classes = self.memory_ui.classes.clone();
        let selected = self.memory_ui.selected_class.clone();
        let instances = self
            .memory_ui
            .instances
            .as_ref()
            .filter(|(name, _)| selected.as_deref() == Some(name.as_str()))
            .map(|(_, page)| page.clone());
        let mut card = studio::card(colors)
            .id("memory-class-analysis-card")
            .min_h_0()
            .flex_1()
            .child(studio::section_title(
                language.text(TextKey::MemoryPageClasses),
                colors,
            ))
            .child(self.memory_table(classes.as_ref(), colors, cx))
            .child(studio::divider(colors))
            .child(studio::section_title(
                language.text(TextKey::MemoryPageInstanceList),
                colors,
            ))
            .child(match selected {
                Some(class) => div().child(class).into_any_element(),
                None => studio::supporting(language.text(TextKey::MemoryPageSelectClass), colors)
                    .into_any_element(),
            })
            .child(self.memory_table(instances.as_ref(), colors, cx));
        if matches!(self.published_memory_view, MemoryView::Detail(_)) {
            card = card
                .child(studio::divider(colors))
                .child(self.memory_inspector(colors, cx));
        }
        card.into_any_element()
    }

    fn memory_three_pane(
        &mut self,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let page = if self.memory_panel == MemoryPanel::Dominators {
            self.memory_ui.dominators.clone()
        } else {
            self.memory_ui.findings.clone()
        };
        let key = if self.memory_panel == MemoryPanel::Dominators {
            TextKey::MemoryPageDominators
        } else {
            TextKey::MemoryPageLeakSuspects
        };
        let mut filters = studio::card(colors)
            .w(px(190.))
            .flex_shrink_0()
            .child(studio::section_title(
                language.text(TextKey::MemoryPageFilters),
                colors,
            ))
            .child(studio::supporting(
                language.text(TextKey::MemoryPageHeapAll),
                colors,
            ));
        if self.memory_panel == MemoryPanel::Findings {
            for (kind, key) in [
                (FindingKind::LeakSuspects, TextKey::MemoryPageFindingAll),
                (
                    FindingKind::BitmapInstances,
                    TextKey::MemoryPageFindingBitmap,
                ),
                (
                    FindingKind::ActivityLeaks,
                    TextKey::MemoryPageFindingActivity,
                ),
                (
                    FindingKind::LifecycleLeakObjects,
                    TextKey::MemoryPageFindingLifecycle,
                ),
            ] {
                let selected = matches!(&self.memory_view,
                    MemoryView::FindingCategory(current) if *current == kind)
                    || kind == FindingKind::LeakSuspects
                        && matches!(self.memory_view, MemoryView::Findings);
                filters = filters.child(
                    studio::compact_text_button(
                        format!("memory-finding-{kind:?}"),
                        language.text(key),
                        !self.busy,
                        false,
                        colors,
                    )
                    .selected(selected)
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.memory_view(MemoryView::FindingCategory(kind), cx)
                    })),
                );
            }
        } else {
            filters = filters.child(studio::supporting(
                language.text(TextKey::MemoryPageFiltersUnavailable),
                colors,
            ));
        }
        div()
            .id("memory-analysis-horizontal-scroll")
            .flex()
            .min_h_0()
            .flex_1()
            .overflow_x_scrollbar()
            .child(filters)
            .child(
                studio::card(colors)
                    .min_w(px(590.))
                    .flex_1()
                    .min_h_0()
                    .child(studio::section_title(language.text(key), colors))
                    .child(self.memory_table(page.as_ref(), colors, cx)),
            )
            .child(
                studio::card(colors)
                    .w(px(330.))
                    .flex_shrink_0()
                    .child(self.memory_inspector(colors, cx)),
            )
            .into_any_element()
    }

    fn memory_diff(&mut self, colors: &studio::StudioColors, cx: &mut Context<Self>) -> AnyElement {
        let language = self.language;
        let page = self.memory_diff_page.clone();
        let mut panel = studio::card(colors)
            .id("memory-diff-analysis")
            .min_h_0()
            .flex_1()
            .child(studio::section_title(
                language.text(TextKey::MemoryPageDiff),
                colors,
            ));
        if let Some(page) = page {
            let offset = page.offset;
            let has_more = page.has_more;
            panel = panel.child(studio::supporting(
                language.text(TextKey::MemoryPageDiffBoundary),
                colors,
            ));
            if let Some(report) = self.memory_diff_report.as_ref() {
                panel = panel.child(studio::supporting(
                    format!(
                        "A SHA-256 {} · B SHA-256 {}",
                        report.before_digest, report.after_digest
                    ),
                    colors,
                ));
            }
            panel = panel
                .child(self.memory_table(Some(&page), colors, cx))
                .child(
                    div()
                        .flex()
                        .gap_1()
                        .child(
                            studio::compact_text_button(
                                "memory-diff-prev",
                                language.text(TextKey::CommonPrevious),
                                offset > 0 && !self.busy,
                                false,
                                colors,
                            )
                            .on_click(cx.listener(
                                move |this, _, _, cx| {
                                    this.memory_diff_page(offset.saturating_sub(PAGE), cx)
                                },
                            )),
                        )
                        .child(
                            studio::compact_text_button(
                                "memory-diff-next",
                                language.text(TextKey::CommonNext),
                                has_more && !self.busy,
                                false,
                                colors,
                            )
                            .on_click(cx.listener(
                                move |this, _, _, cx| {
                                    this.memory_diff_page(offset.saturating_add(PAGE), cx)
                                },
                            )),
                        ),
                );
        } else {
            panel = panel.child(studio::supporting(
                language.text(TextKey::MemoryPageDiffUnavailable),
                colors,
            ));
        }
        panel.into_any_element()
    }

    fn memory_table(
        &mut self,
        page: Option<&PageData>,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let Some(page) = page else {
            return studio::supporting(language.text(TextKey::MemoryPageNoRows), colors)
                .into_any_element();
        };
        let mut table = div()
            .flex()
            .flex_col()
            .min_h(px(90.))
            .flex_1()
            .overflow_y_scrollbar()
            .child(studio::supporting(page.display_summary(language), colors))
            .child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .font_weight(FontWeight::SEMIBOLD)
                    .child(page.display_columns(language)),
            );
        for index in 0..page.rows.len() {
            let action = page.row_actions.get(index).cloned().flatten();
            let mut row = div()
                .flex()
                .items_center()
                .gap_1()
                .py(px(3.))
                .border_b_1()
                .border_color(colors.border)
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .child(page.display_row(index, language)),
                );
            if let Some(view) = action {
                if let MemoryView::Dominators(Some(id)) = &view {
                    let id = *id;
                    row = row.child(
                        studio::compact_text_button(
                            format!("memory-dominator-object-{id}"),
                            language.text(TextKey::MemoryPageOpenObject),
                            !self.busy,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.memory_select_action(MemoryView::Detail(id), cx)
                        })),
                    );
                }
                row = row.child(
                    studio::compact_text_button(
                        format!(
                            "memory-row-{}-{index}-{}",
                            self.memory_panel.label(language),
                            page.offset
                        ),
                        language.text(TextKey::ImportInspect),
                        !self.busy,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.memory_select_action(view.clone(), cx)
                    })),
                );
            }
            table = table.child(row);
        }
        table.into_any_element()
    }

    fn memory_select_action(&mut self, view: MemoryView, cx: &mut Context<Self>) {
        match &view {
            MemoryView::Instances(name) => {
                self.memory_panel = MemoryPanel::Classes;
                self.memory_ui.selected_class = Some(name.clone());
                self.memory_ui.selected_object = None;
                self.memory_ui.instances = None;
            }
            MemoryView::Detail(id) => self.memory_ui.selected_object = Some(*id),
            _ => {}
        }
        self.memory_view(view, cx);
    }

    fn memory_inspector(
        &mut self,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let mut panel = div().flex().flex_col().gap_1().child(studio::section_title(
            language.text(TextKey::MemoryPageInspector),
            colors,
        ));
        let page = self
            .memory_ui
            .detail
            .as_ref()
            .filter(|(id, _)| Some(*id) == self.memory_ui.selected_object)
            .map(|(_, page)| page.clone());
        let Some(page) = page else {
            return panel
                .child(studio::supporting(
                    language.text(TextKey::MemoryPageSelectObject),
                    colors,
                ))
                .into_any_element();
        };
        if let Some(detail) = page.ui.memory_detail.as_ref() {
            if let Some(id) = self.memory_ui.selected_object {
                panel = panel.child(
                    studio::compact_text_button(
                        "memory-copy-object-id",
                        language.text(TextKey::MemoryPageCopyObjectId),
                        true,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(move |_, _, _, cx| {
                        cx.write_to_clipboard(ClipboardItem::new_string(format!("0x{id:x}")));
                    })),
                );
            }
            panel = panel
                .child(studio::supporting(
                    format!("{} · {}", detail.object_id, detail.class_name),
                    colors,
                ))
                .child(studio::supporting(
                    format!(
                        "{}: {} · {}: {} · {}: {}",
                        language.text(TextKey::MemoryPageShallow),
                        detail.shallow_size,
                        language.text(TextKey::MemoryPageRetained),
                        detail.retained_size,
                        language.text(TextKey::MemoryPageDepth),
                        detail.depth
                    ),
                    colors,
                ))
                .child(studio::supporting(
                    format!(
                        "{}: {}",
                        language.text(TextKey::MemoryPageValueKind),
                        detail.value_kind
                    ),
                    colors,
                ));
            if let Some(preview) = detail.string_preview.as_ref() {
                panel = panel.child(studio::supporting(preview.clone(), colors));
            }
            if detail.omitted {
                panel = panel.child(studio::supporting(
                    language.text(TextKey::MemoryPageOmitted),
                    colors,
                ));
            }
            panel = panel.child(studio::section_title(
                language.text(TextKey::MemoryPageGcPath),
                colors,
            ));
            if detail.gc_chain.is_empty() {
                panel = panel.child(studio::supporting(
                    language.text(TextKey::MemoryPageNoGcPath),
                    colors,
                ));
            }
            for (name, class, target) in &detail.gc_chain {
                panel = panel.child(self.memory_reference(name, class, *target, colors, cx));
            }
            panel = panel.child(studio::section_title(
                language.text(TextKey::MemoryPageInbound),
                colors,
            ));
            if detail.inbound.is_empty() {
                panel = panel.child(studio::supporting(
                    language.text(TextKey::MemoryPageNoInbound),
                    colors,
                ));
            }
            for (name, class, target) in &detail.inbound {
                panel = panel.child(self.memory_reference(name, class, *target, colors, cx));
            }
            panel = panel.child(studio::supporting(
                language.text(TextKey::MemoryPagePinForwardUnavailable),
                colors,
            ));
        }
        panel
            .child(studio::section_title(
                language.text(TextKey::MemoryPageFields),
                colors,
            ))
            .child(self.memory_table(Some(&page), colors, cx))
            .into_any_element()
    }

    fn memory_reference(
        &self,
        name: &str,
        class: &str,
        target: Option<u64>,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let label = format!("{name} · {class}");
        if let Some(id) = target {
            studio::compact_text_button(("memory-reference", id), label, !self.busy, false, colors)
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.memory_select_action(MemoryView::Detail(id), cx)
                }))
                .into_any_element()
        } else {
            studio::supporting(label, colors).into_any_element()
        }
    }

    fn memory_unavailable(&self, message: &str, colors: &studio::StudioColors) -> AnyElement {
        studio::card(colors)
            .min_h(px(180.))
            .child(studio::supporting(message.to_owned(), colors))
            .into_any_element()
    }
}
