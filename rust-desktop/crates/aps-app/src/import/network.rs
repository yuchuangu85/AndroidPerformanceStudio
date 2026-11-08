//! Dedicated Network workspace, following the Compose NetworkProfilerScreen.
//! All publication is digest/generation bound. No raw HAR content enters UI state.
mod controls;
mod detail;

use super::*;
use aps_network::{
    DurationPercentiles, EntryPage, EntryQuery, EntrySort, HarSummary, StatusFilter,
};
use gpui_kit::component::input::{Input, InputEvent, InputState};

#[derive(Default)]
pub(super) struct NetworkUi {
    pub query: EntryQuery,
    pub published_query: EntryQuery,
    search: Option<Entity<InputState>>,
    search_subscription: Option<Subscription>,
    search_language: Option<Language>,
    pub total: u64,
    percentiles: Option<DurationPercentiles>,
    domains: bool,
    draft_needs_reset: bool,
    filters_expanded: bool,
}

impl NetworkUi {
    pub fn reset(&mut self) {
        self.query = EntryQuery::default();
        self.published_query = EntryQuery::default();
        self.total = 0;
        self.percentiles = None;
        self.domains = false;
        self.filters_expanded = false;
        self.draft_needs_reset = true;
    }
}

pub(super) struct NetworkPublication {
    pub(super) page: PageData,
    total: u64,
    percentiles: DurationPercentiles,
}

pub(super) fn load_page(
    session: &HarSession,
    offset: usize,
    query: &EntryQuery,
    timing: Option<DurationPercentiles>,
    cancel: &CancellationToken,
) -> Result<NetworkPublication, UiText> {
    let page = session
        .query_entries(offset, PAGE, query, cancel)
        .map_err(network_error)?;
    // Timings belong to the immutable session. Query validation above still
    // verifies the source digest on every page; don't re-sort unchanged timings.
    let percentiles = match timing {
        Some(timing) => timing,
        None => session
            .duration_percentiles(cancel)
            .map_err(network_error)?,
    };
    let total = page.total;
    Ok(NetworkPublication {
        page: projection(session.summary(), page),
        total,
        percentiles,
    })
}

pub(super) fn network_error(error: aps_network::NetworkError) -> UiText {
    use aps_network::NetworkError::*;
    match error {
        Cancelled => TextKey::ImportUiImportCancelled,
        SourceChanged => TextKey::NetworkSourceChanged,
        InvalidQuery => TextKey::NetworkInvalidQuery,
        _ => TextKey::NetworkImportFailed,
    }
    .into()
}

fn projection(summary: &HarSummary, page: EntryPage) -> PageData {
    PageData {
        ui: PageUi::default().warning(0, TextKey::ImportUiNetworkWarning.into()),
        summary: format!("{} matching requests / {} HAR entries · SHA-256 {} · {}",
            page.total, summary.entry_count, page.source_digest, page.minimization_policy),
        columns: ["#", "Method", "Status", "Duration ms", "Request bytes", "Response bytes", "URL"]
            .into_iter().map(str::to_owned).collect(),
        rows: page.rows.into_iter().map(|row| vec![
            row.index.to_string(), row.method.into(), row.status.to_string(),
            row.duration_ms.map_or_else(|| "unavailable".into(), |v| v.to_string()),
            row.request_bytes.map_or_else(|| "unavailable".into(), |v| v.to_string()),
            row.response_bytes.map_or_else(|| "unavailable".into(), |v| v.to_string()),
            row.minimized_url.into(),
        ]).collect(),
        offset: page.offset,
        has_more: (page.offset as u64).saturating_add(PAGE as u64) < page.total,
        warnings: vec!["Minimized HAR evidence; raw URLs, timestamps, headers, cookies and bodies excluded. Call outcome and capture completeness are unknown.".into()],
        row_actions: Vec::new(),
    }
}

impl ImportWorkspace {
    pub(super) fn publish_network(&mut self, publication: NetworkPublication, query: EntryQuery) {
        let selected = self.selected_network.as_ref().and_then(|row| row.first());
        self.selected_network = publication
            .page
            .rows
            .iter()
            .find(|row| selected.is_some() && row.first() == selected)
            .or_else(|| publication.page.rows.first())
            .cloned();
        self.network.total = publication.total;
        self.network.percentiles = Some(publication.percentiles);
        self.network.query = query.clone();
        self.network.published_query = query;
        self.page = Some(publication.page);
    }

    pub(super) fn query_network(
        &mut self,
        offset: usize,
        query: EntryQuery,
        cx: &mut Context<Self>,
    ) {
        if self.busy {
            return;
        }
        let Some(Session::Network(session)) = self.session.clone() else {
            return;
        };
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        self.export_generation += 1;
        self.export_busy = false;
        let generation = self.generation;
        let cancel = self.cancel.clone();
        let timing = self.network.percentiles;
        self.network.query = query.clone();
        self.busy = true;
        self.error = None;
        self.notice = None;
        let task = cx.background_executor().spawn(async move {
            load_page(&session, offset, &query, timing, &cancel).map(|page| (page, query))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok((page, query)) => this.publish_network(page, query),
                    Err(error) => {
                        this.network.query = this.network.published_query.clone();
                        this.error = Some(error);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn network_search(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Entity<InputState> {
        if self.network.search.is_none() {
            let language = self.language;
            let search = cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::NetworkSearch))
                    .validate(|value, _| value.len() <= aps_network::MAX_QUERY_BYTES)
            });
            self.network.search_subscription =
                Some(cx.subscribe(&search, |this, search, event, cx| {
                    if matches!(event, InputEvent::PressEnter { .. }) && !this.busy {
                        let mut query = this.network.query.clone();
                        query.search = search.read(cx).value().to_string();
                        this.query_network(0, query, cx);
                    }
                }));
            self.network.search = Some(search);
        }
        let search = self.network.search.clone().expect("created search input");
        let reset = self.network.draft_needs_reset;
        let language_changed = self.network.search_language != Some(self.language);
        self.network.draft_needs_reset = false;
        self.network.search_language = Some(self.language);
        if reset || language_changed {
            search.update(cx, |input, cx| {
                if language_changed {
                    input.set_placeholder(self.language.text(TextKey::NetworkSearch), window, cx);
                }
                if reset {
                    input.set_value("", window, cx);
                }
            });
        }
        search
    }

    pub(super) fn render_network_profiler(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let search = self.network_search(window, cx);
        let entity = cx.entity();
        // One owner measures the actual workspace (not the whole native window).
        // Short windows scroll the whole content rather than reducing the two
        // independently scrollable evidence panes to unusable slivers.
        container_query(move |size, _, cx| {
            entity.update(cx, |this, cx| this.network_content(size, &search, cx))
        })
        .size_full()
        .into_any_element()
    }

    fn network_content(
        &mut self,
        size: Size<Pixels>,
        search: &Entity<InputState>,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let colors = studio::StudioColors::from_theme(cx);
        let summary = match &self.session {
            Some(Session::Network(session)) => Some(session.summary()),
            _ => None,
        };
        let loaded = summary.is_some();
        let layout = NetworkLayout::new(
            f32::from(size.width),
            f32::from(size.height),
            loaded,
            self.network.filters_expanded,
            usize::from(self.error.is_some())
                + usize::from(self.notice.is_some())
                + usize::from(self.busy),
        );
        let mut content = studio::page(&colors)
            .h_auto()
            .min_h(px(layout.viewport_height))
            .line_height(px(18.))
            .child(summary_cards(
                summary,
                self.network.percentiles,
                language,
                &colors,
            ));
        if let Some(summary) = summary {
            content = content.child(coverage_panel(summary, language, &colors));
        }
        if let Some(error) = &self.error {
            content = content.child(message_strip(error.render(language), colors.error, &colors));
        }
        if let Some(notice) = &self.notice {
            content = content.child(message_strip(notice.render(language), colors.info, &colors));
        }
        if self.busy {
            content = content.child(message_strip(
                language.text(TextKey::ImportLoadingQuerying).to_owned(),
                colors.info,
                &colors,
            ));
        }
        content = content
            .child(activity_panel(language, &colors))
            .child(self.network_controls(search, &colors, layout.narrow, cx));
        let table = self.network_table(&colors, cx);
        let detail = if self.busy && self.selected_network.is_none() {
            detail::request_detail_loading(language, &colors)
        } else {
            detail::request_detail(self.selected_network.as_deref(), language, &colors)
        };
        let mut panes = div()
            .id("network-evidence-panes")
            .flex()
            .h(px(layout.panes_height))
            .flex_shrink_0()
            .min_w_0()
            .min_h(px(layout.minimum_panes_height))
            .gap(px(4.));
        if layout.narrow {
            panes = panes
                .flex_col()
                .child(
                    div()
                        .flex_1()
                        .flex_basis(px(0.))
                        .min_h(px(180.))
                        .child(table),
                )
                .child(
                    div()
                        .flex_1()
                        .flex_grow(1.2)
                        .flex_basis(px(0.))
                        .min_h(px(216.))
                        .child(detail),
                );
        } else {
            panes = panes
                .child(
                    div()
                        .flex_1()
                        .flex_grow(1.8)
                        .flex_basis(px(0.))
                        .min_w_0()
                        .h_full()
                        .child(table),
                )
                .child(
                    div()
                        .flex_1()
                        .flex_basis(px(0.))
                        .min_w_0()
                        .h_full()
                        .child(detail),
                );
        }
        content = content.child(panes).child(self.network_footer(&colors, cx));
        div()
            .id("network-profiler-workspace")
            .size_full()
            .min_h_0()
            .min_w_0()
            .flex()
            .flex_col()
            .bg(colors.workspace)
            .text_color(colors.text)
            .text_size(px(studio::FONT_BODY))
            .child(self.network_toolbar(&colors, cx))
            .child(
                div()
                    .id("network-workspace-scroll")
                    .flex_1()
                    .min_h_0()
                    .min_w_0()
                    .overflow_y_scroll()
                    .child(content),
            )
            .into_any_element()
    }

    fn network_toolbar(&self, colors: &studio::StudioColors, cx: &mut Context<Self>) -> AnyElement {
        let language = self.language;
        let loaded = self.page.is_some();
        let ready = loaded && !self.busy && !self.export_busy;
        div()
            .id("network-toolbar")
            .flex()
            .items_center()
            .gap(px(6.))
            .h(px(40.))
            .px(px(8.))
            .border_b_1()
            .border_color(colors.border)
            .flex_shrink_0()
            .overflow_x_scrollbar()
            .child(
                studio::compact_text_button(
                    "network-import",
                    language.text(TextKey::NetworkImportHar),
                    !self.busy,
                    true,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.pick(cx))),
            )
            .child(
                studio::compact_text_button(
                    "network-live",
                    language.text(TextKey::NetworkLiveCapture),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::NetworkLiveUnavailableReason)),
            )
            .when(self.busy, |toolbar| {
                toolbar.child(
                    studio::compact_text_button(
                        "network-cancel",
                        language.text(TextKey::ImportCancelOperation),
                        self.busy,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.cancel_work(cx))),
                )
            })
            .child(
                studio::compact_text_button(
                    "network-save-json",
                    language.text(TextKey::NetworkSaveJson),
                    ready,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.save_page(export::Format::Json, cx))),
            )
            .child(
                studio::compact_text_button("network-har", "HAR", false, false, colors)
                    .tooltip(language.text(TextKey::NetworkHarUnavailableReason)),
            )
            .child(
                studio::compact_text_button(
                    "network-save-csv",
                    language.text(TextKey::NetworkSaveCsv),
                    ready,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.save_page(export::Format::Csv, cx))),
            )
            .child(
                studio::compact_text_button(
                    "network-raw-bundle",
                    language.text(TextKey::NetworkRawBundle),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::NetworkRawUnavailableReason)),
            )
            .child(
                studio::compact_text_button(
                    "network-ai",
                    language.text(TextKey::ShellPrepareAiAnalysis),
                    loaded && !self.busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|_, _, window, cx| {
                    window.dispatch_action(Box::new(crate::shell::PrepareCurrentAi), cx)
                })),
            )
            .child(div().flex_1())
            .child(
                studio::compact_text_button(
                    "network-close",
                    language.text(TextKey::CommonClose),
                    loaded || self.busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.close(cx))),
            )
            .into_any_element()
    }

    fn network_table(&self, colors: &studio::StudioColors, cx: &mut Context<Self>) -> AnyElement {
        let language = self.language;
        let mut table =
            panel(colors)
                .size_full()
                .min_h_0()
                .min_w_0()
                .p(px(8.))
                .gap(px(8.))
                .child(div().h(px(36.)).px(px(8.)).flex().items_center().child(
                    studio::section_title(
                        language.text(if self.network.domains {
                            TextKey::NetworkDomains
                        } else {
                            TextKey::ImportUiRequestList
                        }),
                        colors,
                    ),
                ));
        if self.network.domains {
            return table
                .child(
                    div()
                        .flex_1()
                        .min_h_0()
                        .overflow_y_scrollbar()
                        .child(
                            studio::table_header_row(colors).gap_0().children(
                                [
                                    TextKey::NetworkDomain,
                                    TextKey::ImportUiNetworkCalls,
                                    TextKey::ImportUiNetworkFailures,
                                    TextKey::ImportUiNetworkTransferred,
                                ]
                                .into_iter()
                                .enumerate()
                                .map(|(index, key)| {
                                    div()
                                        .flex_1()
                                        .flex_grow(if index == 0 { 2. } else { 1. })
                                        .child(studio::dense_label(
                                            language.text(key),
                                            colors.secondary_text,
                                        ))
                                }),
                            ),
                        )
                        .child(studio::supporting(
                            language.text(TextKey::NetworkDomainsUnavailable),
                            colors,
                        )),
                )
                .into_any_element();
        }
        let columns = [
            TextKey::ImportUiMethod,
            TextKey::ImportUiUrl,
            TextKey::ImportUiStatus,
            TextKey::ImportUiDurationMs,
            TextKey::NetworkTransferredBytes,
            TextKey::NetworkProtocol,
            TextKey::NetworkOutcome,
            TextKey::NetworkSource,
        ];
        let header =
            studio::table_header_row(colors)
                .gap_0()
                .children(columns.into_iter().enumerate().map(|(i, key)| {
                    table_cell(language.text(key).to_owned(), i, colors.secondary_text)
                        .font_weight(FontWeight::SEMIBOLD)
                }));
        let mut rows = div().flex_1().min_h_0().overflow_y_scrollbar();
        let page_rows = self
            .page
            .as_ref()
            .map(|page| page.rows.as_slice())
            .unwrap_or_default();
        if page_rows.is_empty() {
            rows = rows.child(div().p(px(12.)).child(studio::supporting(
                language.text(if self.session.is_some() {
                    TextKey::ImportUiNetworkNoRows
                } else {
                    TextKey::NetworkEmpty
                }),
                colors,
            )));
        }
        for (index, row) in page_rows.iter().enumerate() {
            let selected = self
                .selected_network
                .as_ref()
                .is_some_and(|s| s.first() == row.first());
            let cells = display_cells(row, language);
            let click_row = row.clone();
            let row_color =
                status_color(row.get(2).map(String::as_str).unwrap_or_default(), colors);
            rows = rows.child(
                Button::new(("network-request-row", index))
                    .ghost()
                    .selected(selected)
                    .w_full()
                    .justify_start()
                    .rounded_none()
                    .h(px(30.))
                    .flex_shrink_0()
                    .px(px(8.))
                    .border_b_1()
                    .border_color(colors.border)
                    .bg(if selected {
                        colors.selected_row
                    } else {
                        colors.panel
                    })
                    .accessibility_label(cells.join(" · "))
                    .child(div().flex().items_center().size_full().children(
                        cells.into_iter().enumerate().map(|(column, value)| {
                            table_cell(
                                value,
                                column,
                                if column == 2 { row_color } else { colors.text },
                            )
                        }),
                    ))
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.selected_network = Some(click_row.clone());
                        cx.notify();
                    })),
            );
        }
        // The table keeps the reference's dense eight-column structure. Small
        // widths scroll as one header/body surface, avoiding numeric overlap.
        table = table.child(
            div()
                .flex_1()
                .min_h_0()
                .min_w_0()
                .overflow_x_scrollbar()
                .child(
                    div()
                        .min_w(px(900.))
                        .w_full()
                        .h_full()
                        .flex()
                        .flex_col()
                        .child(header)
                        .child(rows),
                ),
        );
        table.into_any_element()
    }

    fn network_footer(&self, colors: &studio::StudioColors, cx: &mut Context<Self>) -> AnyElement {
        let language = self.language;
        let page = self.page.as_ref();
        let ready = page.is_some() && !self.busy;
        div()
            .flex()
            .items_center()
            .gap(px(6.))
            .flex_shrink_0()
            .min_h(px(36.))
            .flex_wrap()
            .child(studio::supporting(
                language.format(
                    TextKey::NetworkPageRange,
                    &[
                        (
                            "first",
                            &page.map_or(0, |p| p.offset + usize::from(!p.rows.is_empty())),
                        ),
                        ("last", &page.map_or(0, |p| p.offset + p.rows.len())),
                        ("total", &self.network.total),
                    ],
                ),
                colors,
            ))
            .child(
                studio::compact_text_button(
                    "network-prev",
                    language.text(TextKey::CommonPrevious),
                    ready && page.is_some_and(|p| p.offset > 0),
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.page(
                        this.page
                            .as_ref()
                            .map_or(0, |p| p.offset.saturating_sub(PAGE)),
                        cx,
                    )
                })),
            )
            .child(
                studio::compact_text_button(
                    "network-next",
                    language.text(TextKey::CommonNext),
                    ready && page.is_some_and(|p| p.has_more),
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.page(
                        this.page
                            .as_ref()
                            .map_or(0, |p| p.offset.saturating_add(PAGE)),
                        cx,
                    )
                })),
            )
            .child(div().flex_1())
            .child(
                studio::supporting(language.text(TextKey::NetworkExportScope), colors)
                    .line_height(px(15.))
                    .line_clamp(2),
            )
            .child(
                studio::compact_text_button(
                    "network-copy-csv",
                    language.text(TextKey::ImportCopyPageCsv),
                    ready,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.copy_page(export::Format::Csv, cx))),
            )
            .child(
                studio::compact_text_button(
                    "network-copy-json",
                    language.text(TextKey::ImportCopyPageJson),
                    ready,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.copy_page(export::Format::Json, cx))),
            )
            .into_any_element()
    }
}

/// Minimum pane space is a layout contract, not an invented data interval.
/// It keeps list and detail usable at short desktop window heights; the outer
/// content scroll remains accessible instead of squeezing evidence off-screen.
#[derive(Debug, Clone, Copy)]
struct NetworkLayout {
    narrow: bool,
    viewport_height: f32,
    panes_height: f32,
    minimum_panes_height: f32,
}
impl NetworkLayout {
    fn new(width: f32, height: f32, loaded: bool, expanded: bool, messages: usize) -> Self {
        let narrow = stacked_panes(width);
        let minimum_panes_height = if narrow { 400. } else { 220. };
        // Only the panes receive a bounded explicit height. The surrounding
        // page stays height:auto: wrapped controls, notices and localized text
        // increase its natural scroll extent rather than overflowing a guessed
        // fixed content height. Reserve is used only to allocate spare height.
        let reserve = if narrow { 400. } else { 340. }
            + if loaded { 116. } else { 0. }
            + if expanded {
                if width < 600. { 204. } else { 104. }
            } else {
                0.
            }
            + messages.min(3) as f32 * 42.;
        let viewport_height = (height - 40.).max(0.);
        Self {
            narrow,
            viewport_height,
            panes_height: (viewport_height - reserve).max(minimum_panes_height),
            minimum_panes_height,
        }
    }
}

fn evidence_chip(label: &'static str, tone: Rgba) -> Div {
    div()
        .flex_shrink_0()
        .rounded(px(4.))
        .px(px(6.))
        .py(px(2.))
        .bg(tone.alpha(0.12))
        .border_1()
        .border_color(tone.alpha(0.4))
        .text_size(px(studio::FONT_SUPPORTING))
        .line_height(px(15.))
        .text_color(tone)
        .whitespace_nowrap()
        .child(crate::studio::selectable(label))
}

fn panel_header(title: &'static str, colors: &studio::StudioColors) -> Div {
    div()
        .h(px(studio::metrics::PANEL_HEADER_HEIGHT))
        .flex_shrink_0()
        .flex()
        .items_center()
        .child(studio::section_title(title, colors))
}

fn coverage_panel(
    summary: &HarSummary,
    language: Language,
    colors: &studio::StudioColors,
) -> Stateful<Div> {
    panel(colors)
        .id("network-evidence-coverage")
        .p(px(8.))
        .gap(px(8.))
        .child(
            div()
                .flex()
                .items_center()
                .gap(px(8.))
                .child(evidence_chip(
                    language.text(TextKey::NetworkUnknown),
                    colors.warning,
                ))
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(13.))
                        .line_height(px(17.))
                        .line_clamp(2)
                        .child(crate::studio::selectable(
                            language.text(TextKey::NetworkCoverage),
                        )),
                ),
        )
        .child(
            studio::supporting(language.text(TextKey::ImportUiNetworkHarNotice), colors)
                .line_height(px(15.))
                .text_color(colors.warning)
                .line_clamp(2),
        )
        .child(
            studio::supporting(format!("SHA-256 {}", summary.source_digest), colors)
                .line_height(px(15.))
                .whitespace_nowrap()
                .text_ellipsis(),
        )
}

fn message_strip(message: String, tone: Rgba, colors: &studio::StudioColors) -> Div {
    panel(colors).px(px(8.)).py(px(5.)).child(
        div()
            .text_size(px(11.))
            .line_height(px(15.))
            .text_color(tone)
            .line_clamp(2)
            .child(crate::studio::selectable(message)),
    )
}

fn activity_panel(language: Language, colors: &studio::StudioColors) -> Stateful<Div> {
    panel(colors)
        .id("network-activity-panel")
        .p(px(8.))
        .gap(px(8.))
        .child(panel_header(
            language.text(TextKey::NetworkActivity),
            colors,
        ))
        .child(
            studio::supporting(language.text(TextKey::NetworkActivityUnavailable), colors)
                .line_height(px(15.))
                .line_clamp(2),
        )
        .child(
            studio::supporting(language.text(TextKey::NetworkActivityLegend), colors)
                .line_height(px(15.))
                .line_clamp(2),
        )
}

fn panel(colors: &studio::StudioColors) -> Div {
    div()
        .flex()
        .flex_col()
        .flex_shrink_0()
        .bg(colors.panel)
        .border_1()
        .border_color(colors.border)
        .rounded(px(studio::metrics::CARD_RADIUS))
}

fn summary_cards(
    summary: Option<&HarSummary>,
    timing: Option<DurationPercentiles>,
    language: Language,
    colors: &studio::StudioColors,
) -> AnyElement {
    let absent = || "—".to_owned();
    let cards = [
        (
            TextKey::ImportUiNetworkCalls,
            summary.map_or_else(absent, |s| s.entry_count.to_string()),
            language.text(TextKey::NetworkHarEntries).to_owned(),
            colors.info,
        ),
        (
            TextKey::ImportUiNetworkFailures,
            absent(),
            language.text(TextKey::NetworkOutcomeUnknown).to_owned(),
            colors.warning,
        ),
        {
            let (label, value, support) = transfer_metric(summary, language);
            (label, value, support, colors.accent)
        },
        (
            TextKey::NetworkMedianP95,
            timing.map_or_else(absent, |t| {
                format!("{} / {}", duration(t.median_ms), duration(t.p95_ms))
            }),
            timing.map_or_else(absent, |t| {
                language.format(
                    TextKey::NetworkTimingCoverage,
                    &[("known", &t.sample_count), ("missing", &t.missing_count)],
                )
            }),
            colors.warning,
        ),
        (
            TextKey::NetworkCompleteness,
            summary.map_or_else(absent, |_| {
                language.text(TextKey::NetworkUnknown).to_owned()
            }),
            language.text(TextKey::NetworkCoverageUnknown).to_owned(),
            colors.warning,
        ),
    ];
    div()
        .flex_shrink_0()
        .min_w_0()
        .overflow_x_scrollbar()
        .child(
            div().flex().w_full().min_w(px(920.)).gap(px(4.)).children(
                cards
                    .into_iter()
                    .enumerate()
                    .map(|(index, (key, value, support, tone))| {
                        studio::card(colors)
                            .id(("network-metric", index))
                            .gap(px(4.))
                            .border_color(tone.alpha(0.5))
                            .flex_1()
                            .flex_basis(px(0.))
                            .flex_grow([0.72, 0.72, 1.05, 1.18, 1.2][index])
                            .min_w_0()
                            .child(
                                div()
                                    .text_size(px(13.))
                                    .line_height(px(17.))
                                    .font_weight(FontWeight::SEMIBOLD)
                                    .text_color(tone)
                                    .line_clamp(2)
                                    .child(crate::studio::selectable(language.text(key))),
                            )
                            .child(
                                div()
                                    .text_size(px(16.))
                                    .line_height(px(21.))
                                    .font_weight(FontWeight::SEMIBOLD)
                                    .text_color(colors.text)
                                    .whitespace_nowrap()
                                    .text_ellipsis()
                                    .child(crate::studio::selectable(value)),
                            )
                            .child(
                                studio::supporting(support, colors)
                                    .text_size(px(13.))
                                    .line_height(px(16.))
                                    .line_clamp(2),
                            )
                    }),
            ),
        )
        .into_any_element()
}

/// The source aggregates only known sizes. Unknown is not zero, and a
/// partial subtotal must not be presented as the total transferred bytes.
fn transfer_metric(summary: Option<&HarSummary>, language: Language) -> (TextKey, String, String) {
    let Some(summary) = summary else {
        return (TextKey::ImportUiNetworkTransferred, "—".into(), "—".into());
    };
    let request = &summary.request_bytes;
    let response = &summary.response_bytes;
    let partial = request.unknown_count > 0 || response.unknown_count > 0;
    let any_known = request.known_count > 0 || response.known_count > 0;
    let value = if any_known || summary.entry_count == 0 {
        bytes(request.total_bytes.saturating_add(response.total_bytes))
    } else {
        "—".into()
    };
    let support = if partial {
        language.format(
            TextKey::NetworkByteCoverage,
            &[
                ("request", &request.unknown_count),
                ("response", &response.unknown_count),
            ],
        )
    } else {
        language.format(
            TextKey::NetworkTransferredSupport,
            &[
                ("request", &bytes(request.total_bytes)),
                ("response", &bytes(response.total_bytes)),
            ],
        )
    };
    (
        if partial && any_known {
            TextKey::NetworkKnownTransferred
        } else {
            TextKey::ImportUiNetworkTransferred
        },
        value,
        support,
    )
}

const REQUEST_COLUMN_WEIGHTS: [f32; 8] = [0.65, 2.35, 0.58, 0.78, 0.72, 0.68, 0.82, 0.95];

fn table_cell(value: String, column: usize, color: Rgba) -> Div {
    div()
        .flex_1()
        .flex_basis(px(0.))
        .flex_grow(REQUEST_COLUMN_WEIGHTS[column])
        .min_w_0()
        .text_size(px(studio::FONT_SUPPORTING))
        .line_height(px(15.))
        .font_weight(if column == 0 {
            FontWeight::SEMIBOLD
        } else {
            FontWeight::NORMAL
        })
        .text_color(color)
        .whitespace_nowrap()
        .text_ellipsis()
        .pr(px(6.))
        .child(crate::studio::selectable(value))
}

fn number(row: &[String], column: usize) -> Option<f64> {
    row.get(column)?
        .trim_start_matches("Some(")
        .trim_end_matches(')')
        .parse::<f64>()
        .ok()
        .filter(|v| v.is_finite() && *v >= 0.)
}

fn byte_value(row: &[String], column: usize) -> Option<u64> {
    row.get(column)?
        .trim_start_matches("Some(")
        .trim_end_matches(')')
        .parse::<u64>()
        .ok()
}

fn display_cells(row: &[String], language: Language) -> [String; 8] {
    let status = row
        .get(2)
        .filter(|s| s.as_str() != "0")
        .cloned()
        .unwrap_or_else(|| "—".into());
    let transferred = byte_value(row, 4)
        .zip(byte_value(row, 5))
        .map(|(a, b)| a.saturating_add(b));
    [
        row.get(1).cloned().unwrap_or_default(),
        row.get(6).cloned().unwrap_or_default(),
        status,
        duration(number(row, 3)),
        transferred.map_or_else(|| "—".into(), bytes),
        "—".into(),
        language.text(TextKey::NetworkUnknown).into(),
        "HAR".into(),
    ]
}

fn status_color(status: &str, colors: &studio::StudioColors) -> Rgba {
    match status.parse::<u16>() {
        Ok(400..=599) => colors.error,
        Ok(200..=399) => colors.success,
        _ => colors.secondary_text,
    }
}

fn duration(value: Option<f64>) -> String {
    value.map_or_else(|| "—".into(), |v| format!("{v:.1} ms"))
}
fn stacked_panes(width: f32) -> bool {
    width < 1000.
}
fn bytes(value: u64) -> String {
    if value >= 1024 * 1024 {
        format!("{:.1} MiB", value as f64 / 1048576.)
    } else if value >= 1024 {
        format!("{:.1} KiB", value as f64 / 1024.)
    } else {
        format!("{value} B")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[::core::prelude::v1::test]
    fn short_windows_preserve_list_and_detail_space_under_one_scroll_owner() {
        let short = NetworkLayout::new(980., 550., true, false, 0);
        assert!(short.narrow);
        assert_eq!(short.viewport_height, 510.);
        assert_eq!(short.minimum_panes_height, 400.);
        assert_eq!(short.panes_height, 400.);
        let expanded = NetworkLayout::new(980., 550., true, true, 0);
        assert_eq!(expanded.panes_height, 400.);
        let messages = NetworkLayout::new(980., 550., true, false, 3);
        assert_eq!(messages.panes_height, 400.);
        let tall = NetworkLayout::new(1280., 1400., true, false, 0);
        assert!(!tall.narrow);
        assert_eq!(tall.viewport_height, 1360.);
        assert!(tall.panes_height > tall.minimum_panes_height);
        assert_eq!(tall.minimum_panes_height, 220.);
        assert!(!NetworkLayout::new(1000., 900., false, false, 0).narrow);
        assert_eq!(
            NetworkLayout::new(320., 100., true, true, 3).panes_height,
            400.
        );
        let source = include_str!("network.rs");
        let page = source
            .split("let mut content = studio::page(&colors)")
            .nth(1)
            .unwrap()
            .split(".child(summary_cards")
            .next()
            .unwrap();
        assert!(page.contains(".h_auto()"));
        assert!(!page.contains(".h(px("));
    }

    #[::core::prelude::v1::test]
    fn table_weights_and_http_status_tones_match_reference_without_implying_outcome() {
        assert_eq!(
            REQUEST_COLUMN_WEIGHTS,
            [0.65, 2.35, 0.58, 0.78, 0.72, 0.68, 0.82, 0.95]
        );
        let colors = studio::StudioColors::light();
        assert_eq!(status_color("404", &colors), colors.error);
        assert_eq!(status_color("503", &colors), colors.error);
        assert_eq!(status_color("200", &colors), colors.success);
        assert_eq!(status_color("0", &colors), colors.secondary_text);
    }

    #[::core::prelude::v1::test]
    fn transfer_summary_distinguishes_unknown_zero_and_partial_subtotals() {
        use aps_network::ByteTotals;
        let empty = || ByteTotals {
            known_count: 0,
            unknown_count: 0,
            total_bytes: 0,
        };
        let mut summary = HarSummary {
            entry_count: 1,
            status_families: Default::default(),
            request_bytes: ByteTotals {
                known_count: 0,
                unknown_count: 1,
                total_bytes: 0,
            },
            response_bytes: ByteTotals {
                known_count: 0,
                unknown_count: 1,
                total_bytes: 0,
            },
            decoded_response_bytes: empty(),
            mean_duration_ms: None,
            max_duration_ms: None,
            valid_duration_count: 0,
            missing_duration_count: 1,
            invalid_duration_count: 0,
            minimization_policy: aps_network::MINIMIZATION_POLICY,
            source_digest: aps_contracts::SourceDigest::from_bytes([0; 32]),
        };
        assert_eq!(transfer_metric(None, Language::English).1, "—");
        assert_eq!(transfer_metric(Some(&summary), Language::English).1, "—");
        summary.response_bytes = ByteTotals {
            known_count: 1,
            unknown_count: 0,
            total_bytes: 1024,
        };
        let partial = transfer_metric(Some(&summary), Language::English);
        assert_eq!(partial.0, TextKey::NetworkKnownTransferred);
        assert_eq!(partial.1, "1.0 KiB");
        assert_eq!(partial.2, "Unknown sizes: 1 request / 0 response");
        summary.request_bytes = ByteTotals {
            known_count: 1,
            unknown_count: 0,
            total_bytes: 0,
        };
        assert_eq!(
            transfer_metric(Some(&summary), Language::English).0,
            TextKey::ImportUiNetworkTransferred
        );
        summary.response_bytes.total_bytes = 0;
        assert_eq!(transfer_metric(Some(&summary), Language::English).1, "0 B");
        summary.entry_count = 0;
        summary.request_bytes = empty();
        summary.response_bytes = empty();
        assert_eq!(transfer_metric(Some(&summary), Language::English).1, "0 B");
    }

    #[::core::prelude::v1::test]
    fn missing_bytes_and_http_error_do_not_fabricate_outcome_or_transfer() {
        let row = [
            "2",
            "GET",
            "503",
            "unavailable",
            "unavailable",
            "10",
            "redacted://minimized-url",
        ]
        .map(str::to_owned);
        let cells = display_cells(&row, Language::English);
        assert_eq!(cells[2], "503");
        assert_eq!(cells[3], "—");
        assert_eq!(cells[4], "—");
        assert_eq!(cells[6], Language::English.text(TextKey::NetworkUnknown));
    }
    #[::core::prelude::v1::test]
    fn reference_panes_stack_only_below_page_breakpoint() {
        assert!(stacked_panes(0.));
        assert!(stacked_panes(999.));
        assert!(!stacked_panes(1000.));
        assert!(!stacked_panes(1600.));
    }
    #[::core::prelude::v1::test]
    fn known_byte_sizes_use_real_totals_and_status_zero_stays_unknown() {
        let row = [
            "0",
            "GET",
            "0",
            "12.5",
            "1024",
            "1024",
            "redacted://minimized-url",
        ]
        .map(str::to_owned);
        let cells = display_cells(&row, Language::English);
        assert_eq!(cells[2], "—");
        assert_eq!(cells[3], "12.5 ms");
        assert_eq!(cells[4], "2.0 KiB");
        assert_eq!(number(&row, 3), Some(12.5));
    }

    #[::core::prelude::v1::test]
    fn publication_preserves_identity_not_sorted_position_and_drops_absent_selection() {
        let mut workspace = ImportWorkspace::new(Destination::Network, Language::English);
        let publication = |ids: &[u64]| NetworkPublication {
            page: PageData {
                summary: "Minimized fixture".into(),
                columns: Vec::new(),
                rows: ids.iter().map(|id| vec![id.to_string()]).collect(),
                offset: 0,
                has_more: false,
                warnings: Vec::new(),
                row_actions: Vec::new(),
                ui: PageUi::default(),
            },
            total: ids.len() as u64,
            percentiles: DurationPercentiles {
                sample_count: 0,
                missing_count: ids.len() as u64,
                median_ms: None,
                p95_ms: None,
            },
        };
        workspace.selected_network = Some(vec!["42".into()]);
        let query = EntryQuery {
            sort: EntrySort::DurationDescending,
            ..EntryQuery::default()
        };
        workspace.publish_network(publication(&[3, 42, 1]), query.clone());
        assert_eq!(workspace.selected_network.as_ref().unwrap()[0], "42");
        assert_eq!(workspace.network.published_query, query);
        workspace.publish_network(publication(&[7, 8]), EntryQuery::default());
        assert_eq!(workspace.selected_network.as_ref().unwrap()[0], "7");
        workspace.publish_network(publication(&[]), EntryQuery::default());
        assert!(workspace.selected_network.is_none());
        assert_eq!(workspace.network.total, 0);
    }
}
