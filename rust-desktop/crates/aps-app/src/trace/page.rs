//! Trace Analyzer page UI.
//!
//! TraceAnalyzerWorkspace.kt, TraceAnalyzerHeaderActions.kt,
//! TraceAnalyzerDetails.kt and TraceAnalyzerBookmarks.kt are the behavior
//! oracles: a header action row (device selector, capture/export/open viewer,
//! trace search, preset run, AI analysis), a wide three-column workspace
//! (sessions rail, viewer or capture panel with diagnostic detail tabs,
//! insights rail) and a narrow stacked alternative, all fed by the runtime's
//! verified state. This module owns presentation-only state.

use super::TraceWorkspace;
use super::capture_page;
use super::model::{
    self, TraceBookmarkRequest, TraceDiagnosticPresetOption, TraceInsightCategory,
    TraceInsightEvidence, TraceInsightFilter, TraceInsightSeverity, TraceResultTable,
    TraceWorkspaceBookmark, TraceWorkspaceSession, TraceWorkspaceSummary,
};
use crate::studio::{self, FONT_BODY, FONT_SUPPORTING, FONT_TITLE, StudioColors};
use aps_ui_foundation::{Destination, Language, TextKey};
use gpui_kit::{
    component::{
        button::Button,
        input::{Input, InputEvent, InputState},
        menu::{DropdownMenu, PopupMenuItem},
        scroll::{ScrollableElement, Scrollbar, ScrollbarAxis},
    },
    prelude::*,
    *,
};
use std::rc::Rc;

const TOOLBAR_HEIGHT: f32 = 36.;
const TOOLBAR_PADDING: f32 = 16.;
const TOOLBAR_GAP: f32 = 8.;
const SEARCH_INPUT_HEIGHT: f32 = 22.;
const SEARCH_INPUT_WIDTH: f32 = 220.;
const DEVICE_SELECTOR_WIDTH: f32 = 190.;
const PRESET_SELECTOR_WIDTH: f32 = 190.;
const WIDE_WORKSPACE_BREAKPOINT: f32 = 1100.;
const SESSIONS_RAIL_WIDTH: f32 = 232.;
const INSIGHTS_RAIL_WIDTH: f32 = 312.;
const DETAIL_PANEL_HEIGHT: f32 = 230.;
const NARROW_DETAIL_PANEL_HEIGHT: f32 = 300.;
const BOOKMARKS_PANEL_HEIGHT: f32 = 180.;
const NARROW_SESSIONS_HEIGHT: f32 = 280.;
const NARROW_VIEWER_HEIGHT: f32 = 360.;
const NARROW_INSIGHTS_HEIGHT: f32 = 420.;
const SESSION_ROW_HEIGHT: f32 = 72.;
const RESULT_ROW_HEIGHT: f32 = 22.;
const TABLE_HEADER_HEIGHT: f32 = 24.;
const TABLE_ACTIONS_WIDTH: f32 = 116.;
const SUMMARY_CARD_HEIGHT: f32 = 72.;
const STATUS_DOT: f32 = 8.;
const STATUS_BAR_HEIGHT: f32 = 25.;

/// Presentation-only state owned by the page.
pub(in crate::trace) struct PageUi {
    language: Option<Language>,
    session_search: Entity<InputState>,
    trace_search: Entity<InputState>,
    _session_search_subscription: Subscription,
    _trace_search_subscription: Subscription,
    session_scroll: UniformListScrollHandle,
    result_scroll: UniformListScrollHandle,
    hits_scroll: UniformListScrollHandle,
}

impl PageUi {
    pub(in crate::trace) fn new(window: &mut Window, cx: &mut Context<TraceWorkspace>) -> Self {
        let session_search = cx.new(|cx| InputState::new(window, cx));
        let session_search_subscription =
            cx.subscribe(&session_search, |this, input, event, cx| {
                if matches!(event, InputEvent::Change) {
                    let value = input.read(cx).value().to_string();
                    this.session_search_changed(value, cx);
                    cx.notify();
                }
            });
        let trace_search = cx.new(|cx| InputState::new(window, cx));
        let trace_search_subscription = cx.subscribe(&trace_search, |this, input, event, cx| {
            if matches!(event, InputEvent::Change) {
                let value = input.read(cx).value().to_string();
                this.trace_search_changed(value, cx);
                cx.notify();
            }
        });
        Self {
            language: None,
            session_search,
            trace_search,
            _session_search_subscription: session_search_subscription,
            _trace_search_subscription: trace_search_subscription,
            session_scroll: UniformListScrollHandle::new(),
            result_scroll: UniformListScrollHandle::new(),
            hits_scroll: UniformListScrollHandle::new(),
        }
    }

    pub(in crate::trace) fn set_language(
        &mut self,
        language: Language,
        window: &mut Window,
        cx: &mut App,
    ) {
        self.language = Some(language);
        let session_placeholder = language.text(TextKey::TraceWorkspaceSearchSessions);
        self.session_search.update(cx, |state, cx| {
            state.set_placeholder(session_placeholder, window, cx);
        });
        let trace_placeholder = language.text(TextKey::TraceWorkspaceSearchTrace);
        self.trace_search.update(cx, |state, cx| {
            state.set_placeholder(trace_placeholder, window, cx);
        });
    }
}

pub(in crate::trace) fn render(
    this: &mut TraceWorkspace,
    window: &mut Window,
    cx: &mut Context<TraceWorkspace>,
) -> AnyElement {
    let colors = StudioColors::from_theme(cx);
    if this.ui.language != Some(this.language) {
        let language = this.language;
        this.ui.set_language(language, window, cx);
    }

    let mut root = div()
        .id("trace-analyzer-page")
        .size_full()
        .min_h_0()
        .flex()
        .flex_col()
        .bg(colors.workspace)
        .text_color(colors.text)
        .text_size(px(FONT_BODY))
        .child(header(this, &colors, cx))
        .child(studio::divider(&colors));
    let language = this.language;
    if let Some(error) = this.capture.refresh_error() {
        root = root.child(message_row(
            "trace-device-refresh-error",
            error.to_owned(),
            colors.error,
            language,
            &colors,
            cx,
        ));
    }
    if let Some(notice) = this.notice.clone() {
        root = root.child(message_row(
            "trace-workspace-notice",
            notice,
            colors.secondary_text,
            language,
            &colors,
            cx,
        ));
    }
    if let Some(error) = this.tool_error.clone() {
        root = root.child(message_row(
            "trace-tool-error",
            error,
            colors.error,
            language,
            &colors,
            cx,
        ));
    }

    let entity = cx.entity().downgrade();
    let colors_for_body = colors;
    root.child(
        div()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(container_query(move |size, window, cx| {
                let Some(entity) = entity.upgrade() else {
                    return div().into_any_element();
                };
                let wide = f32::from(size.width) >= WIDE_WORKSPACE_BREAKPOINT;
                entity.update(cx, |this, cx| {
                    workspace(this, wide, window, &colors_for_body, cx)
                })
            })),
    )
    .child(status_bar(this, &colors))
    .into_any_element()
}

fn toolbar_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    enabled: bool,
    selected: bool,
    colors: &StudioColors,
) -> Button {
    studio::compact_text_button(id, label, enabled, selected, colors).flex_shrink_0()
}

fn header(
    this: &TraceWorkspace,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let active = this.active_trace.is_some() && !this.importing;
    let viewer_available = this
        .workspace_artifact
        .as_ref()
        .is_some_and(|artifact| artifact.viewer_available);
    let run_preset_label = if this.preset_running {
        language.text(TextKey::TraceWorkspaceRunning)
    } else {
        language.text(TextKey::TraceWorkspaceRunPreset)
    };
    let search_enabled =
        active && !this.trace_search_query.trim().is_empty() && !this.search_running;

    div()
        .id("trace-analyzer-toolbar")
        .h(px(TOOLBAR_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(TOOLBAR_GAP))
        .px(px(TOOLBAR_PADDING))
        .overflow_x_scroll()
        .children(this.capture_visible.then(|| {
            div()
                .flex()
                .items_center()
                .gap(px(TOOLBAR_GAP))
                .child(device_selector(this, colors, cx))
                .child(
                    toolbar_button(
                        "trace-refresh-devices",
                        language.text(TextKey::Refresh),
                        !this.capture.refresh_in_progress(),
                        false,
                        colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.refresh_devices(cx))),
                )
                .child(div().w(px(8.)).flex_shrink_0())
        }))
        .child(
            toolbar_button(
                "trace-toggle-capture",
                language.text(TextKey::TraceWorkspaceCapture),
                this.capture.device_ready(),
                this.capture_visible,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.toggle_capture(cx))),
        )
        .child(
            toolbar_button(
                "trace-export",
                language.text(TextKey::TraceWorkspaceExport),
                active,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.export_raw_trace(cx))),
        )
        .child(
            toolbar_button(
                "trace-open-viewer",
                language.text(TextKey::TraceWorkspaceOpenViewer),
                active && viewer_available,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.open_viewer(cx))),
        )
        .child(
            div()
                .w(px(SEARCH_INPUT_WIDTH))
                .h(px(SEARCH_INPUT_HEIGHT))
                .flex_shrink_0()
                .child(
                    Input::new(&this.ui.trace_search)
                        .h(px(SEARCH_INPUT_HEIGHT))
                        .rounded(px(3.))
                        .disabled(!active),
                ),
        )
        .child(
            toolbar_button(
                "trace-run-search",
                language.text(TextKey::TraceWorkspaceSearch),
                search_enabled,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.run_trace_search(cx))),
        )
        .child(preset_selector(this, colors, cx))
        .child(
            toolbar_button(
                "trace-run-preset",
                run_preset_label,
                active && !this.preset_running && !this.presets.is_empty(),
                this.preset_running,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.run_preset(cx))),
        )
        .child(div().flex_1())
        .child(
            toolbar_button(
                "trace-ai-analysis",
                language.text(TextKey::FoundationAiAnalysis),
                this.can_analyze() && !this.importing,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, window, cx| {
                let request = this.analysis_request();
                if let Some(studio) = this.studio.clone() {
                    studio
                        .update(cx, |studio, cx| {
                            studio.present_ai_request(Destination::Trace, request, window, cx)
                        })
                        .ok();
                }
            })),
        )
        .into_any_element()
}

fn device_selector(
    this: &TraceWorkspace,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let devices = this.capture.devices();
    let selected = this
        .capture
        .selected_serial()
        .and_then(|serial| devices.iter().find(|device| device.serial == serial));
    let label = selected
        .map(|device| device.display_name.clone())
        .unwrap_or_else(|| language.text(TextKey::SelectDevice).to_owned());
    let items: Vec<(String, String, bool)> = devices
        .iter()
        .map(|device| {
            (
                device.serial.clone(),
                device.display_name.clone(),
                device.online,
            )
        })
        .collect();
    let entity = cx.entity().downgrade();
    toolbar_button("trace-device-selector", label, true, false, colors)
        .w(px(DEVICE_SELECTOR_WIDTH))
        .justify_start()
        .dropdown_menu(move |menu, _, _| {
            let mut menu = menu;
            for (serial, display_name, online) in &items {
                let serial = serial.clone();
                let entity = entity.clone();
                menu = menu.item(
                    PopupMenuItem::new(display_name.clone())
                        .disabled(!online)
                        .on_click(move |_, _, cx| {
                            let serial = serial.clone();
                            let _ = entity.update(cx, |this, cx| {
                                this.capture.select_device(serial, cx);
                                cx.notify();
                            });
                        }),
                );
            }
            menu
        })
        .into_any_element()
}

fn preset_selector(
    this: &TraceWorkspace,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let selected = this
        .presets
        .iter()
        .find(|preset| preset.id == this.selected_preset);
    let label = selected
        .map(|preset| preset.title.clone())
        .unwrap_or_else(|| language.text(TextKey::TraceWorkspacePreset).to_owned());
    let items: Vec<TraceDiagnosticPresetOption> = this.presets.clone();
    let running = this.preset_running;
    let entity = cx.entity().downgrade();
    toolbar_button(
        "trace-preset-selector",
        label,
        !items.is_empty() && !running,
        false,
        colors,
    )
    .w(px(PRESET_SELECTOR_WIDTH))
    .justify_start()
    .dropdown_menu(move |menu, _, _| {
        let mut menu = menu;
        for preset in &items {
            let id = preset.id.clone();
            let entity = entity.clone();
            menu = menu.item(
                PopupMenuItem::new(preset.title.clone())
                    .disabled(running)
                    .on_click(move |_, _, cx| {
                        let id = id.clone();
                        let _ = entity.update(cx, |this, cx| this.select_preset(id, cx));
                    }),
            );
        }
        menu
    })
    .into_any_element()
}

fn message_row(
    id: &'static str,
    text: String,
    color: Rgba,
    language: Language,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    let copy = text.clone();
    div()
        .id(id)
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(6.))
        .px(px(TOOLBAR_PADDING))
        .py(px(3.))
        .child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(FONT_SUPPORTING))
                .text_color(color)
                .child(text),
        )
        .child(
            studio::compact_text_button(
                format!("{id}-copy"),
                language.text(TextKey::TraceWorkspaceCopy),
                true,
                false,
                colors,
            )
            .on_click(cx.listener(move |_, _, _, cx| {
                cx.write_to_clipboard(ClipboardItem::new_string(copy.clone()));
            })),
        )
        .into_any_element()
}

fn workspace(
    this: &mut TraceWorkspace,
    wide: bool,
    window: &mut Window,
    colors: &StudioColors,
    cx: &mut Context<TraceWorkspace>,
) -> AnyElement {
    let summaries = model::build_trace_workspace_summaries(&this.insights);
    if wide {
        let left = sessions_rail(this, colors, cx);
        let center = center_column(this, window, colors, cx);
        let right = div()
            .w(px(INSIGHTS_RAIL_WIDTH))
            .h_full()
            .flex_shrink_0()
            .child(insights_panel(this, colors, cx))
            .into_any_element();
        div()
            .id("trace-analyzer-wide")
            .size_full()
            .flex()
            .flex_col()
            .gap(px(studio::metrics::CARD_GAP))
            .p(px(studio::metrics::WORKSPACE_PADDING))
            .child(summary_grid(&summaries, true, this.language, colors))
            .child(
                div()
                    .flex_1()
                    .min_h_0()
                    .w_full()
                    .flex()
                    .gap(px(studio::metrics::CARD_GAP))
                    .child(left)
                    .child(center)
                    .child(right),
            )
            .child(workspace_status(this, colors))
            .into_any_element()
    } else {
        let sessions = sessions_panel(this, colors, cx);
        let bookmarks = bookmarks_panel(this, colors, cx);
        let viewer = viewer_or_capture(this, window, colors, cx);
        let show_detail = !this.capture_visible;
        let detail = detail_panel(this, colors, cx);
        let insights = insights_panel(this, colors, cx);
        let mut stacked = div()
            .id("trace-analyzer-narrow-scroll")
            .size_full()
            .flex()
            .flex_col()
            .gap(px(studio::metrics::CARD_GAP))
            .p(px(studio::metrics::WORKSPACE_PADDING))
            .overflow_y_scroll()
            .child(summary_grid(&summaries, false, this.language, colors))
            .child(
                div()
                    .h(px(NARROW_SESSIONS_HEIGHT))
                    .w_full()
                    .flex_shrink_0()
                    .child(sessions),
            )
            .child(
                div()
                    .h(px(BOOKMARKS_PANEL_HEIGHT))
                    .w_full()
                    .flex_shrink_0()
                    .child(bookmarks),
            )
            .child(
                div()
                    .h(px(NARROW_VIEWER_HEIGHT))
                    .w_full()
                    .flex_shrink_0()
                    .child(viewer),
            );
        if show_detail {
            stacked = stacked.child(
                div()
                    .h(px(NARROW_DETAIL_PANEL_HEIGHT))
                    .w_full()
                    .flex_shrink_0()
                    .child(detail),
            );
        }
        stacked
            .child(
                div()
                    .h(px(NARROW_INSIGHTS_HEIGHT))
                    .w_full()
                    .flex_shrink_0()
                    .child(insights),
            )
            .child(workspace_status(this, colors))
            .into_any_element()
    }
}

fn summary_grid(
    summaries: &[TraceWorkspaceSummary],
    wide: bool,
    language: Language,
    colors: &StudioColors,
) -> AnyElement {
    if wide {
        let mut row = div()
            .id("trace-summary-grid")
            .w_full()
            .flex_shrink_0()
            .flex()
            .gap(px(studio::metrics::CARD_GAP));
        for summary in summaries {
            row = row.child(
                div()
                    .flex_1()
                    .min_w_0()
                    .child(summary_card(summary, language, colors)),
            );
        }
        row.into_any_element()
    } else {
        let mut column = div()
            .id("trace-summary-grid")
            .w_full()
            .flex_shrink_0()
            .flex()
            .flex_col()
            .gap(px(studio::metrics::CARD_GAP));
        for chunk in summaries.chunks(2) {
            let mut row = div().w_full().flex().gap(px(studio::metrics::CARD_GAP));
            for summary in chunk {
                row = row.child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .child(summary_card(summary, language, colors)),
                );
            }
            column = column.child(row);
        }
        column.into_any_element()
    }
}

fn summary_card(
    summary: &TraceWorkspaceSummary,
    language: Language,
    colors: &StudioColors,
) -> AnyElement {
    let accent = summary
        .severity
        .map(|severity| severity_color(severity, colors))
        .unwrap_or(colors.secondary_text);
    let label = summary_label(summary.category, language);
    let value = summary
        .value
        .clone()
        .unwrap_or_else(|| language.text(TextKey::TraceWorkspaceUnavailable).to_owned());
    let value_color = if summary.value.is_none() {
        colors.secondary_text
    } else {
        accent
    };
    let mut card = div()
        .id(format!("trace-summary-{}", category_id(summary.category)))
        .h(px(SUMMARY_CARD_HEIGHT))
        .w_full()
        .flex()
        .flex_col()
        .justify_between()
        .gap(px(2.))
        .p(px(studio::metrics::COMPACT_PADDING))
        .rounded(px(studio::metrics::CARD_RADIUS))
        .border_1()
        .border_color(if summary.severity.is_some() {
            accent.alpha(0.5)
        } else {
            colors.border
        })
        .bg(if summary.severity.is_some() {
            colors.panel.blend(accent.alpha(0.10))
        } else {
            colors.panel
        })
        .child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(label),
        )
        .child(
            div()
                .w_full()
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::BOLD)
                .text_color(value_color)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(value),
        );
    if let Some(source) = summary.source_title.clone() {
        card = card.child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(source),
        );
    }
    card.into_any_element()
}

fn sessions_rail(
    this: &TraceWorkspace,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    div()
        .w(px(SESSIONS_RAIL_WIDTH))
        .h_full()
        .flex_shrink_0()
        .flex()
        .flex_col()
        .gap(px(studio::metrics::CARD_GAP))
        .child(
            div()
                .flex_1()
                .min_h_0()
                .w_full()
                .child(sessions_panel(this, colors, cx)),
        )
        .child(
            div()
                .h(px(BOOKMARKS_PANEL_HEIGHT))
                .w_full()
                .flex_shrink_0()
                .child(bookmarks_panel(this, colors, cx)),
        )
        .into_any_element()
}

fn center_column(
    this: &mut TraceWorkspace,
    window: &mut Window,
    colors: &StudioColors,
    cx: &mut Context<TraceWorkspace>,
) -> AnyElement {
    let viewer = viewer_or_capture(this, window, colors, cx);
    let show_detail = !this.capture_visible;
    let mut column = div()
        .flex_1()
        .min_w_0()
        .h_full()
        .flex()
        .flex_col()
        .gap(px(studio::metrics::CARD_GAP))
        .child(div().flex_1().min_h_0().w_full().child(viewer));
    if show_detail {
        let detail = detail_panel(this, colors, cx);
        column = column.child(
            div()
                .h(px(DETAIL_PANEL_HEIGHT))
                .w_full()
                .flex_shrink_0()
                .child(detail),
        );
    }
    column.into_any_element()
}

fn sessions_panel(
    this: &TraceWorkspace,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let filtered =
        model::filter_trace_workspace_sessions(&this.workspace_sessions, &this.session_search);
    let selected_session = this.selected_session.clone();
    let selected_artifact = this
        .workspace_artifact
        .as_ref()
        .map(|artifact| artifact.artifact_id.clone());
    let rows: Rc<Vec<TraceWorkspaceSession>> = Rc::new(filtered);
    let entity = cx.entity();
    let colors_copy = *colors;
    let list = uniform_list("trace-session-list", rows.len(), {
        let rows = rows.clone();
        let selected_session = selected_session.clone();
        let selected_artifact = selected_artifact.clone();
        let entity = entity.clone();
        move |range, _, _| {
            let mut items = Vec::with_capacity(range.len());
            for index in range {
                if let Some(session) = rows.get(index) {
                    let selected = selected_session.as_deref() == Some(session.session_id.as_str())
                        || session
                            .artifact
                            .as_ref()
                            .map(|artifact| artifact.artifact_id.as_str())
                            == selected_artifact.as_deref();
                    items.push(session_row(
                        index,
                        session,
                        selected,
                        &entity,
                        language,
                        &colors_copy,
                    ));
                }
            }
            items
        }
    })
    .h_full()
    .w_full()
    .track_scroll(&this.ui.session_scroll);

    let search_row = div()
        .h(px(28.))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .px(px(4.))
        .child(
            div().flex_1().min_w_0().child(
                Input::new(&this.ui.session_search)
                    .h(px(SEARCH_INPUT_HEIGHT))
                    .rounded(px(3.)),
            ),
        );

    let mut body = div()
        .flex_1()
        .min_h_0()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(6.))
        .p(px(studio::metrics::WORKSPACE_PADDING))
        .child(search_row);
    if let Some(error) = this.sessions_error.clone() {
        body = body.child(
            div()
                .flex_shrink_0()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.error)
                .child(error),
        );
    }
    let list_area = if rows.is_empty() {
        div()
            .id("trace-session-list")
            .flex_1()
            .min_h_0()
            .w_full()
            .child(empty_message(
                language.text(TextKey::TraceWorkspaceEmptySessions),
                colors,
            ))
            .into_any_element()
    } else {
        div()
            .relative()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(list)
            .child(vertical_scrollbar(
                "trace-session-scrollbar",
                &this.ui.session_scroll,
            ))
            .into_any_element()
    };
    body = body.child(list_area).child(
        studio::text_button(
            "toggle-trace-capture",
            language.text(TextKey::TraceWorkspaceCapture),
            this.capture.device_ready(),
            this.capture_visible,
            colors,
        )
        .w_full()
        .flex_shrink_0()
        .on_click(cx.listener(|this, _, _, cx| this.toggle_capture(cx))),
    );

    workspace_card(
        "trace-sessions-panel",
        language.text(TextKey::TraceWorkspaceSessions),
        None,
        colors,
        body.into_any_element(),
    )
}

fn session_row(
    index: usize,
    session: &TraceWorkspaceSession,
    selected: bool,
    entity: &Entity<TraceWorkspace>,
    language: Language,
    colors: &StudioColors,
) -> AnyElement {
    let session_id = session.session_id.clone();
    let entity_click = entity.clone();
    let open_entity = entity.clone();
    let delete_entity = entity.clone();
    let open_id = session_id.clone();
    let delete_id = session_id.clone();
    div()
        .id(("trace-session-row", index))
        .h(px(SESSION_ROW_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .flex_col()
        .justify_between()
        .gap(px(3.))
        .p(px(studio::metrics::COMPACT_PADDING))
        .rounded(px(studio::metrics::CARD_RADIUS))
        .border_1()
        .border_color(if selected {
            colors.accent
        } else {
            colors.border
        })
        .bg(if selected {
            selected_row_color(colors)
        } else {
            colors.panel
        })
        .cursor_pointer()
        .on_click(move |_, _, cx| {
            let session_id = session_id.clone();
            let _ = entity_click.update(cx, |this, cx| this.select_session(session_id, cx));
        })
        .child(
            div()
                .w_full()
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::MEDIUM)
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(session.title.clone()),
        )
        .child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(format!(
                    "{}  ·  {}",
                    session.captured_at_label, session.duration_label
                )),
        )
        .child(
            div()
                .flex()
                .items_center()
                .gap(px(6.))
                .child(
                    studio::compact_text_button(
                        format!("open-trace-session-{open_id}"),
                        language.text(TextKey::TraceWorkspaceOpen),
                        true,
                        false,
                        colors,
                    )
                    .on_click(move |_, _, cx| {
                        let open_id = open_id.clone();
                        let _ = open_entity.update(cx, |this, cx| this.open_session(open_id, cx));
                    }),
                )
                .child(
                    studio::compact_text_button(
                        format!("delete-trace-session-{delete_id}"),
                        language.text(TextKey::TraceWorkspaceDelete),
                        true,
                        false,
                        colors,
                    )
                    .on_click(move |_, _, cx| {
                        let delete_id = delete_id.clone();
                        let _ =
                            delete_entity.update(cx, |this, cx| this.delete_session(delete_id, cx));
                    }),
                ),
        )
        .into_any_element()
}

fn bookmarks_panel(
    this: &TraceWorkspace,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let viewer_available = this
        .workspace_artifact
        .as_ref()
        .is_some_and(|artifact| artifact.viewer_available);
    let entity = cx.entity();
    let mut list = div()
        .id("trace-bookmark-list")
        .flex_1()
        .min_h_0()
        .w_full()
        .overflow_y_scrollbar()
        .flex()
        .flex_col()
        .gap(px(studio::metrics::CARD_GAP))
        .p(px(studio::metrics::WORKSPACE_PADDING));
    if this.workspace_bookmarks.is_empty() {
        list = list.child(empty_message(
            language.text(TextKey::TraceWorkspaceEmptyBookmarks),
            colors,
        ));
    }
    for bookmark in &this.workspace_bookmarks {
        list = list.child(bookmark_row(
            bookmark,
            viewer_available,
            &entity,
            language,
            colors,
        ));
    }
    workspace_card(
        "trace-bookmarks-panel",
        language.text(TextKey::TraceWorkspaceBookmarks),
        None,
        colors,
        list.into_any_element(),
    )
}

fn bookmark_row(
    bookmark: &TraceWorkspaceBookmark,
    viewer_available: bool,
    entity: &Entity<TraceWorkspace>,
    language: Language,
    colors: &StudioColors,
) -> AnyElement {
    let bookmark_id = bookmark.bookmark_id.clone();
    let timestamp = bookmark.timestamp_nanos;
    let go_entity = entity.clone();
    let open_id = bookmark_id.clone();
    let delete_entity = entity.clone();
    let delete_id = bookmark_id.clone();
    div()
        .id(format!("trace-bookmark-{}", bookmark.bookmark_id))
        .w_full()
        .flex_shrink_0()
        .flex()
        .flex_col()
        .gap(px(3.))
        .p(px(studio::metrics::COMPACT_PADDING))
        .rounded(px(studio::metrics::CARD_RADIUS))
        .border_1()
        .border_color(colors.border)
        .bg(colors.panel)
        .child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .font_weight(FontWeight::MEDIUM)
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(bookmark.label.clone()),
        )
        .child(
            div()
                .w_full()
                .flex()
                .items_center()
                .gap(px(6.))
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(model::format_trace_timestamp(timestamp)),
                )
                .child(
                    studio::compact_text_button(
                        format!("open-bookmark-{open_id}"),
                        language.text(TextKey::TraceWorkspaceGo),
                        viewer_available,
                        false,
                        colors,
                    )
                    .on_click(move |_, _, cx| {
                        let _ =
                            go_entity.update(cx, |this, cx| this.open_in_timeline(timestamp, cx));
                    }),
                )
                .child(
                    studio::compact_text_button(
                        format!("delete-bookmark-{delete_id}"),
                        language.text(TextKey::TraceWorkspaceDelete),
                        true,
                        false,
                        colors,
                    )
                    .on_click(move |_, _, cx| {
                        let delete_id = delete_id.clone();
                        let _ = delete_entity
                            .update(cx, |this, cx| this.delete_bookmark(delete_id, cx));
                    }),
                ),
        )
        .into_any_element()
}

fn viewer_or_capture(
    this: &mut TraceWorkspace,
    window: &mut Window,
    colors: &StudioColors,
    cx: &mut Context<TraceWorkspace>,
) -> AnyElement {
    if this.capture_visible {
        let language = this.language;
        let close = studio::compact_text_button(
            "close-trace-capture",
            language.text(TextKey::TraceWorkspaceCloseCapture),
            true,
            false,
            colors,
        )
        .on_click(cx.listener(|this, _, _, cx| this.toggle_capture(cx)));
        let content = capture_page::capture_page(this, window, cx);
        workspace_card(
            "trace-capture-panel",
            language.text(TextKey::TraceWorkspaceCapture),
            Some(close.into_any_element()),
            colors,
            div()
                .flex_1()
                .min_h_0()
                .w_full()
                .child(content)
                .into_any_element(),
        )
    } else {
        viewer_panel(this, colors, cx)
    }
}

fn viewer_panel(
    this: &TraceWorkspace,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let body = match this.workspace_artifact.as_ref() {
        None => empty_viewer(language, colors),
        Some(artifact) => {
            let anchors =
                model::trace_timeline_anchors(&this.insights, this.workspace_artifact.as_ref());
            let entity = cx.entity();
            let mut column = div()
                .id("trace-viewer-content")
                .flex_1()
                .min_h_0()
                .w_full()
                .overflow_y_scrollbar()
                .flex()
                .flex_col()
                .gap(px(studio::metrics::PANEL_GAP))
                .p(px(studio::metrics::WORKSPACE_PADDING))
                .child(
                    div()
                        .w_full()
                        .text_size(px(FONT_TITLE))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(artifact.display_name.clone()),
                )
                .child(evidence_anchors(&anchors, language, colors, &entity));
            column = column
                .child(evidence_line(
                    language.text(TextKey::TraceWorkspaceArtifactId),
                    artifact.artifact_id.clone(),
                    colors.secondary_text,
                    colors,
                ))
                .child(evidence_line(
                    language.text(TextKey::TraceWorkspaceActiveArtifact),
                    artifact.location_label.clone(),
                    colors.secondary_text,
                    colors,
                ))
                .child(evidence_line(
                    language.text(TextKey::TraceWorkspaceCompleteness),
                    artifact.completeness_label.clone(),
                    colors.secondary_text,
                    colors,
                ));
            if !artifact.limitations.is_empty() {
                column = column.child(studio::divider(colors));
                for limitation in &artifact.limitations {
                    column = column.child(evidence_line(
                        language.text(TextKey::TraceWorkspaceLimitation),
                        limitation.clone(),
                        colors.warning,
                        colors,
                    ));
                }
            }
            column = column.child(source_of_truth_card(language, colors)).child(
                div()
                    .id("trace-viewer-actions")
                    .w_full()
                    .flex()
                    .items_center()
                    .gap(px(8.))
                    .overflow_x_scroll()
                    .child(
                        studio::compact_text_button(
                            "open-perfetto-viewer",
                            language.text(TextKey::TraceWorkspaceOpenViewer),
                            artifact.viewer_available,
                            artifact.viewer_available,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.open_viewer(cx))),
                    )
                    .child(
                        studio::compact_text_button(
                            "export-trace-artifact",
                            language.text(TextKey::TraceWorkspaceExport),
                            this.active_trace.is_some(),
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.export_raw_trace(cx))),
                    )
                    .child(
                        studio::compact_text_button(
                            "show-trace-capture",
                            language.text(TextKey::TraceWorkspaceCapture),
                            this.capture.device_ready(),
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.toggle_capture(cx))),
                    ),
            );
            column.into_any_element()
        }
    };
    workspace_card(
        "trace-viewer-panel",
        language.text(TextKey::TraceWorkspaceViewer),
        None,
        colors,
        body,
    )
}

fn empty_viewer(language: Language, colors: &StudioColors) -> AnyElement {
    div()
        .flex_1()
        .min_h_0()
        .w_full()
        .flex()
        .flex_col()
        .items_center()
        .justify_center()
        .gap(px(8.))
        .p(px(24.))
        .child(perfetto_mark(colors))
        .child(
            div()
                .text_size(px(FONT_TITLE))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .child(language.text(TextKey::TraceWorkspaceNoTrace)),
        )
        .child(
            div()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(language.text(TextKey::TraceWorkspaceNoTraceDescription)),
        )
        .into_any_element()
}

fn perfetto_mark(colors: &StudioColors) -> AnyElement {
    div()
        .w(px(48.))
        .h(px(48.))
        .flex()
        .items_center()
        .justify_center()
        .rounded(px(10.))
        .border_1()
        .border_color(colors.accent)
        .bg(colors.panel.blend(colors.accent.alpha(0.15)))
        .child(
            div()
                .text_size(px(FONT_TITLE))
                .font_weight(FontWeight::BOLD)
                .text_color(colors.accent)
                .child("PF"),
        )
        .into_any_element()
}

fn source_of_truth_card(language: Language, colors: &StudioColors) -> AnyElement {
    div()
        .id("perfetto-source-of-truth")
        .w_full()
        .flex()
        .flex_col()
        .gap(px(4.))
        .p(px(studio::metrics::COMPACT_PADDING))
        .rounded(px(studio::metrics::CARD_RADIUS))
        .border_1()
        .border_color(colors.accent.alpha(0.4))
        .bg(colors.panel.blend(colors.accent.alpha(0.1)))
        .child(
            div()
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.accent)
                .child(language.text(TextKey::TraceWorkspaceOpenViewer)),
        )
        .child(
            div()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(language.text(TextKey::TraceWorkspaceSourceOfTruth)),
        )
        .into_any_element()
}

fn evidence_anchors(
    anchors: &[TraceInsightEvidence],
    language: Language,
    colors: &StudioColors,
    entity: &Entity<TraceWorkspace>,
) -> AnyElement {
    let mut row = div()
        .id("trace-evidence-anchors")
        .w_full()
        .flex()
        .items_center()
        .gap(px(6.))
        .overflow_x_scroll()
        .child(
            div()
                .flex_shrink_0()
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .child(language.text(TextKey::TraceWorkspaceAnchors)),
        );
    if anchors.is_empty() {
        row = row.child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(language.text(TextKey::TraceWorkspaceNoAnchors)),
        );
        return row.into_any_element();
    }
    for insight in anchors {
        let timestamp = insight.timestamp_nanos.unwrap_or_default();
        let label = format!(
            "{}  ·  {}",
            model::format_trace_timestamp(timestamp),
            insight.title
        );
        let entity = entity.clone();
        row = row.child(
            studio::compact_text_button(
                format!("trace-anchor-{}", insight.query_id),
                label,
                true,
                false,
                colors,
            )
            .on_click(move |_, _, cx| {
                let _ = entity.update(cx, |this, cx| this.open_in_timeline(timestamp, cx));
            }),
        );
    }
    row.into_any_element()
}

fn workspace_card(
    id: &'static str,
    title: impl Into<SharedString>,
    action: Option<AnyElement>,
    colors: &StudioColors,
    content: AnyElement,
) -> AnyElement {
    let mut header = div()
        .h(px(studio::metrics::PANEL_HEADER_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(6.))
        .px(px(studio::metrics::COMPACT_PADDING))
        .child(studio::section_title(title, colors));
    if let Some(action) = action {
        header = header.child(div().flex_1()).child(action);
    }
    div()
        .id(id)
        .size_full()
        .flex()
        .flex_col()
        .overflow_hidden()
        .bg(colors.panel)
        .border_1()
        .border_color(colors.border)
        .rounded(px(studio::metrics::CARD_RADIUS))
        .child(header)
        .child(studio::divider(colors))
        .child(content)
        .into_any_element()
}

fn empty_message(text: impl Into<SharedString>, colors: &StudioColors) -> AnyElement {
    let text: SharedString = text.into();
    div()
        .p(px(8.))
        .text_size(px(FONT_SUPPORTING))
        .text_color(colors.secondary_text)
        .child(text)
        .into_any_element()
}

fn evidence_line(
    label: impl Into<SharedString>,
    value: impl Into<SharedString>,
    value_color: Rgba,
    colors: &StudioColors,
) -> AnyElement {
    let label: SharedString = label.into();
    let value: SharedString = value.into();
    div()
        .w_full()
        .flex()
        .items_center()
        .gap(px(6.))
        .child(
            div()
                .flex_shrink_0()
                .text_size(px(FONT_SUPPORTING))
                .font_weight(FontWeight::MEDIUM)
                .text_color(colors.secondary_text)
                .child(format!("{}:", label)),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(FONT_SUPPORTING))
                .text_color(value_color)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(value),
        )
        .into_any_element()
}

fn vertical_scrollbar(id: &'static str, handle: &UniformListScrollHandle) -> AnyElement {
    div()
        .absolute()
        .inset_0()
        .child(
            Scrollbar::new(handle)
                .id(id)
                .axis(ScrollbarAxis::Vertical)
                .viewport_from_layout(),
        )
        .into_any_element()
}

fn status_bar(this: &TraceWorkspace, colors: &StudioColors) -> AnyElement {
    let language = this.language;
    let selected = this.capture.selected_serial().and_then(|serial| {
        this.capture
            .devices()
            .iter()
            .find(|device| device.serial == serial)
    });
    let (dot, text) = match selected {
        Some(device) if device.online => (
            colors.success,
            language.format(
                TextKey::DeviceConnected,
                &[("value1", &device.display_name)],
            ),
        ),
        _ => (
            colors.error,
            language.text(TextKey::NoOnlineDevice).to_owned(),
        ),
    };
    div()
        .id("trace-status-bar")
        .w_full()
        .flex_shrink_0()
        .flex()
        .flex_col()
        .child(studio::divider(colors))
        .child(
            div()
                .id("trace-status-bar-content")
                .h(px(STATUS_BAR_HEIGHT))
                .w_full()
                .flex()
                .items_center()
                .gap(px(8.))
                .px(px(TOOLBAR_PADDING))
                .overflow_x_scroll()
                .child(
                    div()
                        .flex_shrink_0()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .child(format!("{}:", language.text(TextKey::DeviceStatus))),
                )
                .child(
                    div()
                        .w(px(STATUS_DOT))
                        .h(px(STATUS_DOT))
                        .flex_shrink_0()
                        .rounded_full()
                        .bg(dot),
                )
                .child(
                    div()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(text),
                ),
        )
        .into_any_element()
}

fn workspace_status(this: &TraceWorkspace, colors: &StudioColors) -> AnyElement {
    let message = if this.importing {
        Some((
            false,
            this.language
                .text(TextKey::TraceWorkspaceRunning)
                .to_owned(),
        ))
    } else if let Some(error) = this.error.clone() {
        Some((true, error))
    } else {
        this.status.clone().map(|status| (false, status))
    };
    match message {
        Some((is_error, message)) => div()
            .id(if is_error {
                "trace-workspace-error"
            } else {
                "trace-workspace-status"
            })
            .w_full()
            .flex_shrink_0()
            .rounded(px(studio::metrics::SMALL_RADIUS))
            .bg(colors.panel)
            .px(px(10.))
            .py(px(5.))
            .text_size(px(FONT_SUPPORTING))
            .text_color(if is_error {
                colors.error
            } else {
                colors.secondary_text
            })
            .child(message)
            .into_any_element(),
        None => div().into_any_element(),
    }
}

fn selected_row_color(colors: &StudioColors) -> Rgba {
    colors.accent.alpha(0.12).blend(colors.panel)
}

fn severity_color(severity: TraceInsightSeverity, colors: &StudioColors) -> Rgba {
    match severity {
        TraceInsightSeverity::Critical => colors.error,
        TraceInsightSeverity::Warning => colors.warning,
        TraceInsightSeverity::Info => colors.info,
        TraceInsightSeverity::Opportunity => colors.success,
    }
}

fn category_id(category: TraceInsightCategory) -> &'static str {
    match category {
        TraceInsightCategory::Frame => "frame",
        TraceInsightCategory::Binder => "binder",
        TraceInsightCategory::LongTask => "long_task",
        TraceInsightCategory::Rendering => "rendering",
        TraceInsightCategory::Cpu => "cpu",
        TraceInsightCategory::Memory => "memory",
        TraceInsightCategory::Input => "input",
        TraceInsightCategory::Other => "other",
    }
}

fn category_key(category: TraceInsightCategory) -> TextKey {
    match category {
        TraceInsightCategory::Frame => TextKey::TraceWorkspaceSummaryFrames,
        TraceInsightCategory::Binder => TextKey::TraceWorkspaceSummaryBinder,
        TraceInsightCategory::LongTask => TextKey::TraceWorkspaceSummaryLongTasks,
        TraceInsightCategory::Rendering => TextKey::TraceWorkspaceSummaryRendering,
        TraceInsightCategory::Cpu => TextKey::TraceWorkspaceCategoryCpu,
        TraceInsightCategory::Memory => TextKey::TraceWorkspaceCategoryMemory,
        TraceInsightCategory::Input => TextKey::TraceWorkspaceCategoryInput,
        TraceInsightCategory::Other => TextKey::TraceWorkspaceCategoryOther,
    }
}

fn summary_label(category: TraceInsightCategory, language: Language) -> String {
    language.text(category_key(category)).to_owned()
}

fn filter_id(filter: TraceInsightFilter) -> &'static str {
    match filter {
        TraceInsightFilter::All => "all",
        TraceInsightFilter::Critical => "critical",
        TraceInsightFilter::Warning => "warning",
        TraceInsightFilter::Info => "info",
    }
}

fn filter_key(filter: TraceInsightFilter) -> TextKey {
    match filter {
        TraceInsightFilter::All => TextKey::TraceWorkspaceFilterAll,
        TraceInsightFilter::Critical => TextKey::TraceWorkspaceFilterCritical,
        TraceInsightFilter::Warning => TextKey::TraceWorkspaceFilterWarning,
        TraceInsightFilter::Info => TextKey::TraceWorkspaceFilterInfo,
    }
}

fn detail_tab_key(tab: model::TraceDetailTab) -> TextKey {
    match tab {
        model::TraceDetailTab::Result => TextKey::TraceWorkspaceTabResult,
        model::TraceDetailTab::Query => TextKey::TraceWorkspaceTabQuery,
        model::TraceDetailTab::Search => TextKey::TraceWorkspaceTabSearch,
    }
}

fn insights_panel(
    this: &TraceWorkspace,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let preset_insights = model::visible_trace_insights(
        &this.insights,
        &this.presets,
        Some(this.selected_preset.as_str()),
        TraceInsightFilter::All,
    );
    let counts = model::trace_insight_counts(&preset_insights);
    let visible = model::visible_trace_insights(
        &this.insights,
        &this.presets,
        Some(this.selected_preset.as_str()),
        this.insight_filter,
    );
    let all_label = language.format(
        TextKey::TraceWorkspaceAllInsights,
        &[("value1", &this.insights.len())],
    );
    let header_action = div()
        .text_size(px(FONT_SUPPORTING))
        .text_color(colors.accent)
        .whitespace_nowrap()
        .child(all_label)
        .into_any_element();

    let mut filters = div()
        .id("trace-insight-filters")
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(4.))
        .overflow_x_scroll();
    for (filter, count) in counts {
        let label = language.format(filter_key(filter), &[("value1", &count)]);
        filters = filters.child(
            studio::compact_text_button(
                format!("trace-insight-filter-{}", filter_id(filter)),
                label,
                true,
                this.insight_filter == filter,
                colors,
            )
            .on_click(cx.listener(move |this, _, _, cx| this.set_insight_filter(filter, cx))),
        );
    }

    let mut list = div()
        .id("trace-insight-list")
        .flex_1()
        .min_h_0()
        .w_full()
        .overflow_y_scrollbar()
        .flex()
        .flex_col()
        .gap(px(studio::metrics::CARD_GAP));
    if visible.is_empty() {
        list = list.child(empty_message(
            language.text(TextKey::TraceWorkspaceEmptyInsights),
            colors,
        ));
    }
    for insight in &visible {
        list = list.child(insight_card(this, insight, colors, cx));
    }

    let body = div()
        .flex_1()
        .min_h_0()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(6.))
        .p(px(studio::metrics::WORKSPACE_PADDING))
        .child(filters)
        .child(list)
        .into_any_element();
    workspace_card(
        "trace-insights-panel",
        language.text(TextKey::TraceWorkspaceInsights),
        Some(header_action),
        colors,
        body,
    )
}

fn insight_card(
    this: &TraceWorkspace,
    insight: &TraceInsightEvidence,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let accent = severity_color(insight.severity, colors);
    let selected = this.selected_query.as_deref() == Some(insight.query_id.as_str());
    let running = this.running_queries.contains(&insight.query_id);
    let query_id = insight.query_id.clone();
    let run_label = if running {
        language.text(TextKey::TraceWorkspaceRunning)
    } else {
        language.text(TextKey::TraceWorkspaceRunDiagnostic)
    };
    let mut card = div()
        .id(format!("trace-insight-{}", insight.query_id))
        .w_full()
        .flex_shrink_0()
        .flex()
        .flex_col()
        .gap(px(4.))
        .p(px(studio::metrics::COMPACT_PADDING))
        .rounded(px(studio::metrics::CARD_RADIUS))
        .border_1()
        .border_color(if selected { accent } else { colors.border })
        .bg(if selected {
            colors.panel.blend(accent.alpha(0.12))
        } else {
            colors.panel
        })
        .child(
            div()
                .w_full()
                .flex()
                .items_center()
                .gap(px(7.))
                .child(
                    div()
                        .w(px(STATUS_DOT))
                        .h(px(STATUS_DOT))
                        .flex_shrink_0()
                        .rounded_full()
                        .bg(accent),
                )
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(FONT_BODY))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(colors.text)
                        .child(insight.title.clone()),
                )
                .child(
                    div()
                        .flex_shrink_0()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .child(summary_label(insight.category, language)),
                ),
        );
    if let Some(aggregate) = insight
        .aggregate
        .as_deref()
        .filter(|value| !value.trim().is_empty())
    {
        card = card.child(evidence_line(
            language.text(TextKey::TraceWorkspaceAggregate),
            aggregate.to_owned(),
            accent,
            colors,
        ));
    }
    card = card.child(evidence_line(
        language.text(TextKey::TraceWorkspaceQueryId),
        insight.query_id.clone(),
        colors.secondary_text,
        colors,
    ));
    card = card.child(evidence_line(
        language.text(TextKey::TraceWorkspaceArtifactId),
        insight.artifact_id.clone(),
        colors.secondary_text,
        colors,
    ));
    if let Some(summary) = insight
        .result_rows_summary
        .as_deref()
        .filter(|value| !value.trim().is_empty())
    {
        card = card.child(evidence_line(
            language.text(TextKey::TraceWorkspaceResult),
            summary.to_owned(),
            accent,
            colors,
        ));
    }
    for limitation in &insight.limitations {
        card = card.child(evidence_line(
            language.text(TextKey::TraceWorkspaceLimitation),
            limitation.clone(),
            colors.warning,
            colors,
        ));
    }
    if let Some(error) = &insight.error {
        card = card.child(evidence_line(
            language.text(TextKey::TraceWorkspaceError),
            error.clone(),
            colors.error,
            colors,
        ));
    }
    card = card.child(
        div()
            .id(format!("trace-insight-actions-{}", insight.query_id))
            .w_full()
            .flex()
            .items_center()
            .gap(px(6.))
            .overflow_x_scroll()
            .child(
                studio::compact_text_button(
                    format!("run-trace-diagnostic-{}", insight.query_id),
                    run_label,
                    !running,
                    selected,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    let query_id = query_id.clone();
                    this.run_diagnostic(query_id, cx);
                })),
            )
            .child(
                studio::compact_text_button(
                    format!("open-insight-timeline-{}", insight.query_id),
                    language.text(TextKey::TraceWorkspaceOpenInTimeline),
                    model::can_open_insight_in_timeline(insight, this.workspace_artifact.as_ref()),
                    false,
                    colors,
                )
                .on_click(cx.listener({
                    let timestamp = insight.timestamp_nanos;
                    move |this, _, _, cx| {
                        if let Some(timestamp) = timestamp {
                            this.open_in_timeline(timestamp, cx);
                        }
                    }
                })),
            )
            .child(
                studio::compact_text_button(
                    format!("view-insight-result-{}", insight.query_id),
                    language.text(TextKey::TraceWorkspaceViewResult),
                    insight.error.is_none() && insight.result_rows_summary.is_some(),
                    false,
                    colors,
                )
                .on_click(cx.listener({
                    let query_id = insight.query_id.clone();
                    move |this, _, _, cx| {
                        let query_id = query_id.clone();
                        this.view_insight_result(query_id, cx);
                    }
                })),
            ),
    );
    card.into_any_element()
}

fn detail_panel(
    this: &TraceWorkspace,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let tabs = div()
        .flex()
        .items_center()
        .gap(px(4.))
        .child(tab_button(this, model::TraceDetailTab::Result, colors, cx))
        .child(tab_button(this, model::TraceDetailTab::Query, colors, cx))
        .child(tab_button(this, model::TraceDetailTab::Search, colors, cx));
    let body = match this.detail_tab {
        model::TraceDetailTab::Result => result_tab(this, colors, cx),
        model::TraceDetailTab::Query => query_tab(this, colors, cx),
        model::TraceDetailTab::Search => search_tab(this, colors, cx),
    };
    workspace_card(
        "trace-detail-panel",
        language.text(TextKey::TraceWorkspaceDiagnosticResult),
        Some(tabs.into_any_element()),
        colors,
        body,
    )
}

fn tab_button(
    this: &TraceWorkspace,
    tab: model::TraceDetailTab,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    let language = this.language;
    studio::tab_button(
        format!("trace-detail-tab-{}", detail_tab_id(tab)),
        language.text(detail_tab_key(tab)),
        this.detail_tab == tab,
        colors,
    )
    .on_click(cx.listener(move |this, _, _, cx| this.set_detail_tab(tab, cx)))
    .into_any_element()
}

fn detail_tab_id(tab: model::TraceDetailTab) -> &'static str {
    match tab {
        model::TraceDetailTab::Result => "result",
        model::TraceDetailTab::Query => "query",
        model::TraceDetailTab::Search => "search",
    }
}

fn result_tab(
    this: &TraceWorkspace,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let Some(table) = this.result_table.as_ref() else {
        return div()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(empty_message(
                language.text(TextKey::TraceWorkspaceEmptyResult),
                colors,
            ))
            .into_any_element();
    };
    let viewer_available = this
        .workspace_artifact
        .as_ref()
        .is_some_and(|artifact| artifact.viewer_available);
    let entity = cx.entity();
    let title = format!(
        "{}  ·  {}",
        table.title,
        language.format(
            TextKey::TraceWorkspaceRowsShown,
            &[("value1", &table.rows.len())]
        )
    );
    let mut column = div()
        .id("trace-result-table")
        .flex_1()
        .min_h_0()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(3.))
        .p(px(8.))
        .child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(title),
        );
    if table.truncated {
        column = column.child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.warning)
                .child(language.text(TextKey::TraceWorkspaceTruncated)),
        );
    }
    if table.rows.is_empty() {
        return column
            .child(empty_message(
                language.text(TextKey::TraceWorkspaceNoRows),
                colors,
            ))
            .into_any_element();
    }
    let table = Rc::new(table.clone());
    column = column.child(table_header_row(&table.columns, colors));
    let rows = table.clone();
    let list_entity = entity.clone();
    let list = uniform_list("trace-result-rows", table.rows.len(), {
        let rows = rows.clone();
        let entity = list_entity.clone();
        let colors_copy = *colors;
        move |range, _, _| {
            let mut items = Vec::with_capacity(range.len());
            for index in range {
                if index < rows.rows.len() {
                    items.push(result_row(
                        index,
                        &rows,
                        viewer_available,
                        &entity,
                        language,
                        &colors_copy,
                    ));
                }
            }
            items
        }
    })
    .h_full()
    .w_full()
    .track_scroll(&this.ui.result_scroll);
    column
        .child(
            div()
                .relative()
                .flex_1()
                .min_h_0()
                .w_full()
                .child(list)
                .child(vertical_scrollbar(
                    "trace-result-scrollbar",
                    &this.ui.result_scroll,
                )),
        )
        .into_any_element()
}

fn table_header_row(columns: &[String], colors: &StudioColors) -> AnyElement {
    let mut row = div()
        .id("trace-result-header")
        .h(px(TABLE_HEADER_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(6.))
        .px(px(6.))
        .bg(colors.panel)
        .border_b_1()
        .border_color(colors.border);
    for column in columns {
        row = row.child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(FONT_SUPPORTING))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(column.clone()),
        );
    }
    row.child(div().w(px(TABLE_ACTIONS_WIDTH)).flex_shrink_0())
        .into_any_element()
}

fn result_row(
    index: usize,
    table: &Rc<TraceResultTable>,
    viewer_available: bool,
    entity: &Entity<TraceWorkspace>,
    language: Language,
    colors: &StudioColors,
) -> AnyElement {
    let mut row = div()
        .id(("trace-result-row", index))
        .h(px(RESULT_ROW_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(6.))
        .px(px(6.));
    for column in 0..table.columns.len() {
        let cell = table
            .rows
            .get(index)
            .and_then(|row| row.get(column))
            .cloned()
            .unwrap_or_default();
        row = row.child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(cell),
        );
    }
    let timestamp = table.row_timestamps_nanos.get(index).copied().flatten();
    let bookmark = timestamp.map(|timestamp| TraceBookmarkRequest {
        label: table.title.clone(),
        timestamp_nanos: timestamp,
        source_query_id: Some(table.query_id.clone()),
    });
    row.child(row_actions(
        format!("result-{index}"),
        timestamp,
        viewer_available,
        bookmark,
        entity,
        language,
        colors,
    ))
    .into_any_element()
}

fn query_tab(
    this: &TraceWorkspace,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let Some(table) = this.result_table.as_ref() else {
        return div()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(empty_message(
                language.text(TextKey::TraceWorkspaceEmptyResult),
                colors,
            ))
            .into_any_element();
    };
    let sql = table.sql.clone();
    let copy_enabled = !sql.trim().is_empty();
    div()
        .id("trace-query-tab")
        .flex_1()
        .min_h_0()
        .w_full()
        .overflow_y_scrollbar()
        .flex()
        .flex_col()
        .gap(px(6.))
        .p(px(8.))
        .child(evidence_line(
            language.text(TextKey::TraceWorkspaceQueryId),
            table.query_id.clone(),
            colors.secondary_text,
            colors,
        ))
        .child(evidence_line(
            language.text(TextKey::TraceWorkspaceArtifactId),
            table.artifact_id.clone(),
            colors.secondary_text,
            colors,
        ))
        .child(evidence_line(
            language.text(TextKey::TraceWorkspaceSchema),
            table.schema_version.clone(),
            colors.secondary_text,
            colors,
        ))
        .child(evidence_line(
            language.text(TextKey::TraceWorkspaceDescription),
            table.description.clone(),
            colors.secondary_text,
            colors,
        ))
        .child(
            div()
                .w_full()
                .flex()
                .items_center()
                .gap(px(6.))
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .child(language.text(TextKey::TraceWorkspaceReadOnlyQuery)),
                )
                .child(
                    studio::compact_text_button(
                        "trace-copy-sql",
                        language.text(TextKey::TraceWorkspaceCopy),
                        copy_enabled,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(move |_, _, _, cx| {
                        cx.write_to_clipboard(ClipboardItem::new_string(sql.clone()));
                    })),
                ),
        )
        .child(
            div()
                .id("trace-query-sql")
                .w_full()
                .rounded(px(studio::metrics::SMALL_RADIUS))
                .bg(colors.panel)
                .border_1()
                .border_color(colors.border)
                .p(px(studio::metrics::COMPACT_PADDING))
                .overflow_x_scroll()
                .child(
                    div()
                        .font_family("monospace")
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.text)
                        .child(table.sql.clone()),
                ),
        )
        .into_any_element()
}

fn search_tab(
    this: &TraceWorkspace,
    colors: &StudioColors,
    cx: &Context<TraceWorkspace>,
) -> AnyElement {
    let language = this.language;
    if let Some(error) = this.search_error.clone() {
        return div()
            .flex_1()
            .min_h_0()
            .w_full()
            .p(px(8.))
            .child(evidence_line(
                language.text(TextKey::TraceWorkspaceTabSearch),
                error,
                colors.error,
                colors,
            ))
            .into_any_element();
    }
    if this.search_running {
        return div()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(empty_message(
                language.text(TextKey::TraceWorkspaceRunning),
                colors,
            ))
            .into_any_element();
    }
    let Some(term) = this.searched_term.clone() else {
        return div()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(empty_message(
                language.text(TextKey::TraceWorkspaceSearchHint),
                colors,
            ))
            .into_any_element();
    };
    if this.search_hits.is_empty() {
        return div()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(empty_message(
                language.format(TextKey::TraceWorkspaceSearchNoHits, &[("value1", &term)]),
                colors,
            ))
            .into_any_element();
    }
    let viewer_available = this
        .workspace_artifact
        .as_ref()
        .is_some_and(|artifact| artifact.viewer_available);
    let entity = cx.entity();
    let hits: Rc<Vec<model::TraceSearchHit>> = Rc::new(this.search_hits.clone());
    let title = language.format(
        TextKey::TraceWorkspaceSearchHits,
        &[("value1", &hits.len()), ("value2", &term)],
    );
    let header_cells = vec![
        language.text(TextKey::TraceWorkspaceTimestamp).to_owned(),
        language.text(TextKey::TraceWorkspaceDuration).to_owned(),
        language.text(TextKey::TraceWorkspaceTabSearch).to_owned(),
        language.text(TextKey::TraceWorkspaceThread).to_owned(),
    ];
    let list_entity = entity.clone();
    let list = uniform_list("trace-search-hits", hits.len(), {
        let hits = hits.clone();
        let entity = list_entity.clone();
        let colors_copy = *colors;
        move |range, _, _| {
            let mut items = Vec::with_capacity(range.len());
            for index in range {
                if let Some(hit) = hits.get(index) {
                    items.push(search_hit_row(
                        index,
                        hit,
                        viewer_available,
                        &entity,
                        language,
                        &colors_copy,
                    ));
                }
            }
            items
        }
    })
    .h_full()
    .w_full()
    .track_scroll(&this.ui.hits_scroll);
    div()
        .id("trace-search-results")
        .flex_1()
        .min_h_0()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(3.))
        .p(px(8.))
        .child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(title),
        )
        .child(table_header_row(&header_cells, colors))
        .child(
            div()
                .relative()
                .flex_1()
                .min_h_0()
                .w_full()
                .child(list)
                .child(vertical_scrollbar(
                    "trace-search-scrollbar",
                    &this.ui.hits_scroll,
                )),
        )
        .into_any_element()
}

fn search_hit_row(
    index: usize,
    hit: &model::TraceSearchHit,
    viewer_available: bool,
    entity: &Entity<TraceWorkspace>,
    language: Language,
    colors: &StudioColors,
) -> AnyElement {
    let cells = [
        model::format_trace_timestamp(hit.timestamp_nanos),
        model::format_trace_duration_nanos(hit.duration_nanos),
        hit.name.clone(),
        hit.thread_label.clone(),
    ];
    let mut row = div()
        .id(("trace-search-hit", index))
        .h(px(RESULT_ROW_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(6.))
        .px(px(6.));
    for cell in cells {
        row = row.child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(cell),
        );
    }
    let bookmark = TraceBookmarkRequest {
        label: hit.name.clone(),
        timestamp_nanos: hit.timestamp_nanos,
        source_query_id: None,
    };
    row.child(row_actions(
        format!("search-{index}"),
        Some(hit.timestamp_nanos),
        viewer_available,
        Some(bookmark),
        entity,
        language,
        colors,
    ))
    .into_any_element()
}

fn row_actions(
    tag: String,
    timestamp: Option<i64>,
    viewer_available: bool,
    bookmark: Option<TraceBookmarkRequest>,
    entity: &Entity<TraceWorkspace>,
    language: Language,
    colors: &StudioColors,
) -> AnyElement {
    let go_entity = entity.clone();
    let bookmark_entity = entity.clone();
    div()
        .w(px(TABLE_ACTIONS_WIDTH))
        .flex_shrink_0()
        .flex()
        .items_center()
        .justify_end()
        .gap(px(4.))
        .child(
            studio::compact_text_button(
                format!("open-timestamp-{tag}"),
                language.text(TextKey::TraceWorkspaceGo),
                timestamp.is_some() && viewer_available,
                false,
                colors,
            )
            .on_click(move |_, _, cx| {
                if let Some(timestamp) = timestamp {
                    let _ = go_entity.update(cx, |this, cx| this.open_in_timeline(timestamp, cx));
                }
            }),
        )
        .child(
            studio::compact_text_button(
                format!("add-bookmark-{tag}"),
                language.text(TextKey::TraceWorkspaceBookmark),
                bookmark.is_some(),
                false,
                colors,
            )
            .on_click(move |_, _, cx| {
                if let Some(request) = bookmark.clone() {
                    let _ = bookmark_entity.update(cx, |this, cx| this.add_bookmark(request, cx));
                }
            }),
        )
        .into_any_element()
}
