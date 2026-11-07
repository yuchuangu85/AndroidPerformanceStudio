//! Frame Profiler page UI.
//!
//! FrameProfilerScreen.kt / FrameProfilerMainPage.kt are the behavior oracles:
//! a compact header (device + searchable process selectors, refresh, imports,
//! FrameTimeline capture, Perfetto open, capture toggle, AI analysis), the
//! summary cards, the resizable analysis workspace (timeline, clusters,
//! problem frames, frame detail, attribution), and a copyable status bar.
//! Every action goes through the FrameWorkspace runtime; this module owns
//! presentation-only state.

use super::FrameWorkspace;
use super::model;
use crate::studio::{self, FONT_BODY, FONT_SUPPORTING, FONT_TITLE, StudioColors};
use aps_frame::analysis::{AnalyzedFrame, DeadlineMissCluster, FrameAnalysisResult};
use aps_frame::{
    FrameDeadlineVerdict, FrameSource, FrameTimelineStatus, FrameViewFilter, JankSeverity,
};
use aps_ui_foundation::{Destination, Language, TextKey};
use gpui_kit::{
    component::{
        button::Button,
        menu::{DropdownMenu, PopupMenuItem},
        scroll::ScrollableElement,
        spinner::Spinner,
    },
    prelude::*,
    *,
};
use std::{
    collections::BTreeSet,
    sync::{Arc, Mutex},
};

const TOOLBAR_HEIGHT: f32 = 36.;
const TOOLBAR_PADDING: f32 = 16.;
const TOOLBAR_GAP: f32 = 8.;
const WIDE_BREAKPOINT: f32 = 760.;
const SPLITTER: f32 = 7.;
const MIN_TIMELINE_HEIGHT: f32 = 184.;
const NARROW_TIMELINE_HEIGHT: f32 = 360.;
const MIN_BODY_HEIGHT: f32 = 140.;
const MIN_CONTEXT_HEIGHT: f32 = 96.;
const CONTEXT_HEIGHT: f32 = 128.;
const CLUSTERS_MIN_WIDTH: f32 = 180.;
const PROBLEMS_MIN_WIDTH: f32 = 260.;
const DETAILS_MIN_WIDTH: f32 = 260.;
const SUMMARY_CARD_HEIGHT: f32 = 56.;
const SPARKLINE_WIDTH: f32 = 40.;
const SPARKLINE_HEIGHT: f32 = 34.;
const CLUSTER_SPARKLINE_WIDTH: f32 = 54.;
const CLUSTER_SPARKLINE_HEIGHT: f32 = 32.;
const MAX_METRIC_SAMPLES: usize = 32;
const CLUSTER_SPARKLINE_FRAMES: usize = 8;
const TIMELINE_INITIAL_FRAMES: usize = 500;
const TIMELINE_MAX_VISIBLE_FRAMES: usize = 1_000;
const TIMELINE_MIN_VISIBLE_FRAMES: usize = 20;
const AXIS_GUTTER_WIDTH: f32 = 48.;
const FRAME_AXIS_HEIGHT: f32 = 18.;
const STATUS_BAR_HEIGHT: f32 = 25.;
const PROBLEM_ROW_HEIGHT: f32 = 22.;

#[derive(Clone, Copy, PartialEq, Eq)]
enum SplitterKind {
    Timeline,
    Context,
    Clusters,
    Details,
}

#[derive(Clone, Copy)]
struct SplitterDrag {
    kind: SplitterKind,
    start_x: f32,
    start_y: f32,
    start_ratio: f32,
}

#[derive(Clone, Copy)]
struct TimelinePan {
    start_x: f32,
    start_index: usize,
    bar_width: f32,
}

/// Presentation-only state owned by the page.
pub(in crate::frame) struct PageUi {
    language: Option<Language>,
    timeline_analysis: Option<usize>,
    timeline_total: usize,
    timeline_visible_count: usize,
    timeline_first_index: usize,
    timeline_hovered: Option<usize>,
    timeline_window: (usize, usize),
    timeline_bounds: Arc<Mutex<Option<Bounds<Pixels>>>>,
    timeline_pan: Option<TimelinePan>,
    split_drag: Option<SplitterDrag>,
    timeline_ratio: f32,
    context_height: f32,
    clusters_ratio: f32,
    details_ratio: f32,
    workspace_size: (f32, f32),
    problem_scroll: UniformListScrollHandle,
    problem_selected: Option<u64>,
    copy_feedback: Option<(u64, bool)>,
}

impl PageUi {
    pub(in crate::frame) fn new(_window: &mut Window, _cx: &mut Context<FrameWorkspace>) -> Self {
        Self {
            language: None,
            timeline_analysis: None,
            timeline_total: 0,
            timeline_visible_count: 0,
            timeline_first_index: 0,
            timeline_hovered: None,
            timeline_window: (0, 0),
            timeline_bounds: Arc::new(Mutex::new(None)),
            timeline_pan: None,
            split_drag: None,
            timeline_ratio: 0.30,
            context_height: CONTEXT_HEIGHT,
            clusters_ratio: 0.30,
            details_ratio: 0.72,
            workspace_size: (0., 0.),
            problem_scroll: UniformListScrollHandle::new(),
            problem_selected: None,
            copy_feedback: None,
        }
    }

    pub(in crate::frame) fn set_language(
        &mut self,
        language: Language,
        _window: &mut Window,
        _cx: &mut App,
    ) {
        self.language = Some(language);
    }
}

pub(in crate::frame) fn render(
    this: &mut FrameWorkspace,
    window: &mut Window,
    cx: &mut Context<FrameWorkspace>,
) -> AnyElement {
    let colors = StudioColors::from_theme(cx);
    if this.ui.language != Some(this.language) {
        let language = this.language;
        this.ui.set_language(language, window, cx);
    }
    sync_timeline(this);
    sync_problem_selection(this);

    let entity = cx.entity().downgrade();
    let colors_for_body = colors;
    let root = div()
        .id("frame-profiler-page")
        .size_full()
        .min_h_0()
        .flex()
        .flex_col()
        .bg(colors.workspace)
        .text_color(colors.text)
        .text_size(px(FONT_BODY))
        .on_mouse_move(
            cx.listener(|this, event: &MouseMoveEvent, _, cx| update_split_drag(this, event, cx)),
        )
        .on_mouse_up(
            MouseButton::Left,
            cx.listener(|this, _, _, cx| {
                if this.ui.split_drag.take().is_some() || this.ui.timeline_pan.take().is_some() {
                    cx.notify();
                }
            }),
        )
        .child(header(this, &colors, cx))
        .child(studio::divider(&colors));
    root.child(
        div()
            .relative()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(container_query(move |size, window, cx| {
                let Some(entity) = entity.upgrade() else {
                    return div().into_any_element();
                };
                entity.update(cx, |this, cx| {
                    body(this, size, window, &colors_for_body, cx)
                })
            }))
            .when(this.is_loading, |this| {
                this.child(
                    div()
                        .absolute()
                        .inset_0()
                        .flex()
                        .items_center()
                        .justify_center()
                        .bg(colors.panel.alpha(0.18))
                        .child(Spinner::new().into_any_element()),
                )
            }),
    )
    .child(status_bar(this, &colors))
    .into_any_element()
}

fn sync_timeline(this: &mut FrameWorkspace) {
    let (analysis_key, total, selected_index) = match this.filtered_analysis.as_deref() {
        Some(analysis) => (
            this.filtered_analysis
                .as_ref()
                .map(|analysis| Arc::as_ptr(analysis) as usize),
            analysis.frames.len(),
            this.selected_frame_id.and_then(|id| {
                analysis
                    .frames
                    .iter()
                    .position(|frame| frame.sample.frame_id == id)
            }),
        ),
        None => (None, 0, None),
    };
    if this.ui.timeline_analysis != analysis_key || this.ui.timeline_total != total {
        this.ui.timeline_analysis = analysis_key;
        this.ui.timeline_total = total;
        this.ui.timeline_visible_count = total.clamp(1, TIMELINE_INITIAL_FRAMES);
        this.ui.timeline_first_index = 0;
        this.ui.timeline_hovered = None;
    }
    if let Some(index) = selected_index {
        let window = model::frame_timeline_window(
            total,
            this.ui.timeline_first_index,
            this.ui.timeline_visible_count,
        );
        if index < window.start_index || index >= window.end_exclusive() {
            this.ui.timeline_first_index =
                model::centered_frame_timeline_window(total, index, window.count).start_index;
        }
    }
    let window = model::frame_timeline_window(
        total,
        this.ui.timeline_first_index,
        this.ui.timeline_visible_count.max(1),
    );
    if this.ui.timeline_window != (window.start_index, window.count) {
        this.ui.timeline_window = (window.start_index, window.count);
        this.ui.timeline_hovered = None;
    }
}

fn sync_problem_selection(this: &mut FrameWorkspace) {
    let Some(analysis) = this.filtered_analysis.as_deref() else {
        return;
    };
    let selected = this.selected_frame_id;
    if this.ui.problem_selected == selected {
        return;
    }
    this.ui.problem_selected = selected;
    let Some(selected) = selected else {
        return;
    };
    let index = model::problem_frames(analysis)
        .iter()
        .position(|frame| frame.sample.frame_id == selected);
    if let Some(index) = index {
        this.ui
            .problem_scroll
            .scroll_to_item(index, ScrollStrategy::Center);
    }
}

fn update_split_drag(
    this: &mut FrameWorkspace,
    event: &MouseMoveEvent,
    cx: &mut Context<FrameWorkspace>,
) {
    if let Some(pan) = this.ui.timeline_pan {
        if event.pressed_button != Some(MouseButton::Left) {
            this.ui.timeline_pan = None;
            return;
        }
        if pan.bar_width > 0.0 && this.ui.timeline_total > 0 {
            let delta =
                ((pan.start_x - f32::from(event.position.x)) / pan.bar_width).round() as isize;
            let total = this.ui.timeline_total;
            let count = this.ui.timeline_visible_count.clamp(1, total);
            let maximum_start = total.saturating_sub(count) as isize;
            let target = (pan.start_index as isize + delta).clamp(0, maximum_start) as usize;
            if target != this.ui.timeline_first_index {
                this.ui.timeline_first_index = target;
                cx.notify();
            }
        }
        return;
    }
    let Some(drag) = this.ui.split_drag else {
        return;
    };
    if event.pressed_button != Some(MouseButton::Left) {
        this.ui.split_drag = None;
        return;
    }
    let (width, height) = this.ui.workspace_size;
    let timeline_range = (0.18, 0.72);
    match drag.kind {
        SplitterKind::Timeline => {
            if height > 0.0 {
                let delta = (f32::from(event.position.y) - drag.start_y) / height;
                this.ui.timeline_ratio =
                    (drag.start_ratio + delta).clamp(timeline_range.0, timeline_range.1);
                cx.notify();
            }
        }
        SplitterKind::Context => {
            let delta = f32::from(event.position.y) - drag.start_y;
            this.ui.context_height = (drag.start_ratio + delta).clamp(MIN_CONTEXT_HEIGHT, 400.);
            cx.notify();
        }
        SplitterKind::Clusters => {
            if width > 0.0 {
                let delta = (f32::from(event.position.x) - drag.start_x) / width;
                this.ui.clusters_ratio = (drag.start_ratio + delta).clamp(0.16, 0.52);
                cx.notify();
            }
        }
        SplitterKind::Details => {
            if width > 0.0 {
                let delta = (f32::from(event.position.x) - drag.start_x) / width;
                this.ui.details_ratio = (drag.start_ratio + delta).clamp(0.48, 0.88);
                cx.notify();
            }
        }
    }
}

fn header_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    enabled: bool,
    selected: bool,
    colors: &StudioColors,
) -> Button {
    studio::compact_text_button(id, label, enabled, selected, colors).flex_shrink_0()
}

fn header(
    this: &FrameWorkspace,
    colors: &StudioColors,
    cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let language = this.language;
    let busy = this.is_capturing || this.is_loading;
    let process_ready = this.selected_process_pid.is_some();
    let device_ready = this.selected_device_serial.as_ref().is_some_and(|serial| {
        this.devices
            .iter()
            .any(|device| &device.serial == serial && device.online)
    });
    let capture_label = if this.is_capturing {
        language.text(TextKey::FrameStopCapture)
    } else {
        language.text(TextKey::FrameStartCapture)
    };
    div()
        .id("frame-profiler-toolbar")
        .h(px(TOOLBAR_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(TOOLBAR_GAP))
        .px(px(TOOLBAR_PADDING))
        .overflow_x_scroll()
        .child(
            header_button(
                "frame-refresh",
                language.text(TextKey::FrameRefresh),
                !busy && !this.is_refreshing_devices,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.refresh_devices(cx))),
        )
        .child(
            header_button(
                "frame-import-perfetto",
                language.text(TextKey::FrameImportPerfettoFrametimeline),
                !busy,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.pick_import_perfetto(cx))),
        )
        .child(
            header_button(
                "frame-associate-perfetto",
                language.text(TextKey::FrameAssociatePerfettoTrace),
                !busy && this.analysis.is_some(),
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.pick_associate_perfetto(cx))),
        )
        .child(
            header_button(
                "frame-capture-frametimeline",
                language.text(TextKey::FrameCaptureFrametimeline),
                !busy && process_ready,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.capture_frame_timeline(cx))),
        )
        .child(
            header_button(
                "frame-open-trace",
                language.text(TextKey::FrameOpenTraceInPerfetto),
                this.perfetto_trace_file.is_some(),
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.open_trace(cx))),
        )
        .child(
            header_button(
                "frame-toggle-capture",
                capture_label,
                process_ready && (this.is_capturing || (device_ready && !this.is_loading)),
                this.is_capturing,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| {
                if this.is_capturing {
                    this.stop_capture(cx);
                } else {
                    this.start_capture(cx);
                }
            })),
        )
        .child(div().flex_1())
        .child(
            studio::text_button(
                "frame-ai-analysis",
                language.text(TextKey::FoundationAiAnalysis),
                this.can_analyze() && !busy,
                false,
                colors,
            )
            .flex_shrink_0()
            .on_click(cx.listener(|this, _, window, cx| {
                let request = this.analysis_request();
                if let Some(studio) = this.studio.clone() {
                    studio
                        .update(cx, |studio, cx| {
                            studio.present_ai_request(Destination::Frame, request, window, cx)
                        })
                        .ok();
                }
            })),
        )
        .into_any_element()
}

fn status_message(this: &FrameWorkspace) -> Option<(String, bool)> {
    if let Some(error) = this.error.clone() {
        return Some((error, true));
    }
    this.operation_status
        .as_ref()
        .map(|status| (status.localized(this.language), false))
}

fn status_bar(this: &FrameWorkspace, colors: &StudioColors) -> AnyElement {
    let language = this.language;
    let message = status_message(this);
    let mut row = div()
        .id("frame-status-bar-content")
        .h(px(STATUS_BAR_HEIGHT))
        .w_full()
        .flex()
        .items_center()
        .gap(px(8.))
        .px(px(TOOLBAR_PADDING))
        .overflow_x_scroll();
    if let Some((message, is_error)) = message {
        let copy = message.clone();
        row = row
            .child(
                div()
                    .flex_shrink_0()
                    .text_size(px(FONT_SUPPORTING))
                    .text_color(colors.muted_text)
                    .child(format!("{}:", language.text(TextKey::FrameStatus))),
            )
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .text_size(px(FONT_SUPPORTING))
                    .text_color(if is_error {
                        colors.error
                    } else {
                        colors.secondary_text
                    })
                    .whitespace_nowrap()
                    .text_ellipsis()
                    .child(message),
            )
            .child(
                studio::compact_text_button(
                    "frame-status-copy",
                    language.text(TextKey::FrameCopy),
                    true,
                    false,
                    colors,
                )
                .on_click({
                    let copy = copy;
                    move |_, _, cx| {
                        cx.write_to_clipboard(ClipboardItem::new_string(copy.clone()));
                    }
                }),
            );
    }
    div()
        .id("frame-status-bar")
        .w_full()
        .flex_shrink_0()
        .flex()
        .flex_col()
        .child(studio::divider(colors))
        .child(row)
        .into_any_element()
}

fn body(
    this: &mut FrameWorkspace,
    size: Size<Pixels>,
    _window: &mut Window,
    colors: &StudioColors,
    cx: &mut Context<FrameWorkspace>,
) -> AnyElement {
    let width = f32::from(size.width);
    let height = f32::from(size.height);
    this.ui.workspace_size = (width, height);
    let this = &*this;
    let narrow = width < WIDE_BREAKPOINT;
    let summary = summary_cards(
        this.filtered_analysis.as_deref(),
        &this.observed_refresh_rates_hz,
        this.language,
        narrow,
        colors,
    );
    let workspace = if narrow {
        narrow_workspace(this, width, colors, cx)
    } else {
        wide_workspace(this, width, height, colors, cx)
    };
    div()
        .size_full()
        .flex()
        .flex_col()
        .gap(px(studio::metrics::CARD_GAP))
        .p(px(studio::metrics::WORKSPACE_PADDING))
        .child(summary)
        .child(workspace)
        .into_any_element()
}

fn summary_cards(
    analysis: Option<&FrameAnalysisResult>,
    observed_refresh_rates_hz: &[f64],
    language: Language,
    narrow: bool,
    colors: &StudioColors,
) -> AnyElement {
    let summary = analysis.map(|analysis| &analysis.summary);
    let total_frames = summary
        .map_or(0, |summary| summary.total_frames)
        .to_string();
    let deadline = model::percent_text(summary.and_then(|summary| summary.deadline_miss_rate));
    let p50 = model::format_millis(summary.and_then(|summary| summary.p50_duration_ns));
    let p95 = model::format_millis(summary.and_then(|summary| summary.p95_duration_ns));
    let worst = model::format_millis(summary.and_then(|summary| summary.worst_duration_ns));
    let platform = model::percent_text(summary.and_then(|summary| summary.platform_jank_rate));
    let refresh = model::refresh_rate_text(analysis, observed_refresh_rates_hz);
    let durations = model::duration_series(analysis);
    let deadline_samples = model::deadline_series(analysis);
    let cards = [
        (
            "frames",
            TextKey::FrameFrames,
            total_frames,
            Vec::new(),
            colors.accent,
        ),
        (
            "deadline",
            TextKey::FrameDeadlineMissRate,
            deadline,
            deadline_samples,
            colors.error,
        ),
        (
            "p50",
            TextKey::FrameP50,
            p50,
            durations.clone(),
            colors.accent,
        ),
        (
            "p95",
            TextKey::FrameP95,
            p95,
            durations.clone(),
            colors.info,
        ),
        (
            "worst",
            TextKey::FrameWorst,
            worst,
            durations,
            colors.warning,
        ),
        (
            "platform-jank",
            TextKey::FramePlatformJankRate,
            platform,
            Vec::new(),
            colors.error,
        ),
        (
            "refresh-rate",
            TextKey::FrameRefreshRate,
            refresh,
            Vec::new(),
            colors.info,
        ),
    ];
    if narrow {
        let mut column = div()
            .id("frame-summary-grid")
            .w_full()
            .flex_shrink_0()
            .flex()
            .flex_col()
            .gap(px(studio::metrics::CARD_GAP));
        for chunk in cards.chunks(2) {
            let mut row = div().w_full().flex().gap(px(studio::metrics::CARD_GAP));
            for (id, label, value, samples, tone) in chunk {
                row = row.child(div().flex_1().min_w_0().child(metric_card(
                    id,
                    language.text(*label),
                    value.clone(),
                    samples,
                    *tone,
                    colors,
                )));
            }
            column = column.child(row);
        }
        column.into_any_element()
    } else {
        let mut row = div()
            .id("frame-summary-grid")
            .w_full()
            .flex_shrink_0()
            .flex()
            .gap(px(studio::metrics::CARD_GAP));
        for (id, label, value, samples, tone) in cards {
            row = row.child(div().flex_1().min_w_0().child(metric_card(
                id,
                language.text(label),
                value,
                &samples,
                tone,
                colors,
            )));
        }
        row.into_any_element()
    }
}

fn metric_card(
    id: &'static str,
    label: impl Into<SharedString>,
    value: String,
    samples: &[Option<f64>],
    tone: Rgba,
    colors: &StudioColors,
) -> AnyElement {
    div()
        .id(format!("frame-metric-{id}"))
        .h(px(SUMMARY_CARD_HEIGHT))
        .w_full()
        .flex()
        .items_center()
        .gap(px(8.))
        .p(px(studio::metrics::COMPACT_PADDING))
        .rounded(px(studio::metrics::CARD_RADIUS))
        .border_1()
        .border_color(tone.alpha(0.5))
        .bg(colors.panel.blend(tone.alpha(0.10)))
        .child(div().w(px(3.)).h(px(40.)).flex_shrink_0().bg(tone))
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .gap(px(4.))
                .child(
                    div()
                        .w_full()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(tone)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(label.into()),
                )
                .child(
                    div()
                        .w_full()
                        .text_size(px(FONT_BODY))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(value),
                ),
        )
        .child(sparkline(samples, tone))
        .into_any_element()
}

fn sparkline(samples: &[Option<f64>], color: Rgba) -> AnyElement {
    let start = samples.len().saturating_sub(MAX_METRIC_SAMPLES);
    let visible: Vec<Option<f64>> = samples[start..].to_vec();
    let values: Vec<f64> = visible.iter().filter_map(|value| *value).collect();
    if values.is_empty() {
        return div()
            .w(px(SPARKLINE_WIDTH))
            .h(px(SPARKLINE_HEIGHT))
            .flex_shrink_0()
            .into_any_element();
    }
    let minimum = values.iter().copied().fold(f64::INFINITY, f64::min);
    let maximum = values.iter().copied().fold(f64::NEG_INFINITY, f64::max);
    let span = maximum - minimum;
    canvas(
        move |_bounds: Bounds<Pixels>, _: &mut Window, _: &mut App| {},
        move |bounds: Bounds<Pixels>, _, window: &mut Window, _: &mut App| {
            let width = f32::from(bounds.size.width);
            let height = f32::from(bounds.size.height);
            if width <= 0.0 || height <= 0.0 || visible.is_empty() {
                return;
            }
            let slot_width = width / visible.len() as f32;
            for (index, sample) in visible.iter().enumerate() {
                let Some(value) = sample else {
                    continue;
                };
                let normalized = if span <= 0.0 {
                    0.55
                } else {
                    (((value - minimum) / span) as f32).clamp(0.0, 1.0)
                };
                let bar_height = (height * (0.18 + normalized * 0.82)).max(2.0);
                let bar_width = (slot_width * 0.62).max(1.0);
                window.paint_quad(quad(
                    Bounds {
                        origin: point(
                            bounds.origin.x + px(index as f32 * slot_width + slot_width * 0.18),
                            bounds.origin.y + px(height - bar_height),
                        ),
                        size: size(px(bar_width), px(bar_height)),
                    },
                    px(0.),
                    color.alpha(0.45 + normalized * 0.5),
                    px(0.),
                    transparent_black(),
                    Default::default(),
                ));
            }
        },
    )
    .w(px(SPARKLINE_WIDTH))
    .h(px(SPARKLINE_HEIGHT))
    .flex_shrink_0()
    .into_any_element()
}

fn splitter(
    id: &'static str,
    kind: SplitterKind,
    vertical: bool,
    colors: &StudioColors,
    cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let mut element = div()
        .id(id)
        .flex_shrink_0()
        .flex()
        .items_center()
        .justify_center()
        .on_mouse_down(
            MouseButton::Left,
            cx.listener(move |this, event: &MouseDownEvent, _, cx| {
                let start_ratio = match kind {
                    SplitterKind::Timeline => this.ui.timeline_ratio,
                    SplitterKind::Context => this.ui.context_height,
                    SplitterKind::Clusters => this.ui.clusters_ratio,
                    SplitterKind::Details => this.ui.details_ratio,
                };
                this.ui.split_drag = Some(SplitterDrag {
                    kind,
                    start_x: f32::from(event.position.x),
                    start_y: f32::from(event.position.y),
                    start_ratio,
                });
                cx.notify();
            }),
        )
        .child(if vertical {
            div().w(px(1.)).h_full().bg(colors.strong_border)
        } else {
            div().w_full().h(px(1.)).bg(colors.strong_border)
        });
    element = if vertical {
        element
            .w(px(SPLITTER))
            .h_full()
            .cursor(CursorStyle::ResizeLeftRight)
    } else {
        element
            .h(px(SPLITTER))
            .w_full()
            .cursor(CursorStyle::ResizeUpDown)
    };
    element.into_any_element()
}

fn analysis_card(content: AnyElement, height: Option<f32>, colors: &StudioColors) -> AnyElement {
    let mut card = div()
        .w_full()
        .flex()
        .flex_col()
        .overflow_hidden()
        .bg(colors.panel)
        .border_1()
        .border_color(colors.border)
        .rounded(px(studio::metrics::CARD_RADIUS));
    if let Some(height) = height {
        card = card.h(px(height));
    }
    card.child(content).into_any_element()
}

fn wide_workspace(
    this: &FrameWorkspace,
    width: f32,
    height: f32,
    colors: &StudioColors,
    cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let filtered = this.filtered_analysis.as_deref();
    let show_context = filtered.is_some_and(|analysis| !analysis.attributions.is_empty())
        || !this.warnings.is_empty();
    let available = (height
        - SUMMARY_CARD_HEIGHT
        - studio::metrics::CARD_GAP
        - 2.0 * studio::metrics::WORKSPACE_PADDING)
        .max(180.0);
    let dividers = SPLITTER * if show_context { 2.0 } else { 1.0 };
    let minimum_total = MIN_TIMELINE_HEIGHT
        + MIN_BODY_HEIGHT
        + if show_context {
            MIN_CONTEXT_HEIGHT
        } else {
            0.0
        };
    let pane_height = (available - dividers).max(minimum_total);
    let mut context_height = if show_context {
        this.ui.context_height.clamp(MIN_CONTEXT_HEIGHT, 400.0)
    } else {
        0.0
    };
    let timeline_upper = (pane_height - MIN_BODY_HEIGHT - context_height).max(MIN_TIMELINE_HEIGHT);
    let timeline_height =
        (pane_height * this.ui.timeline_ratio).clamp(MIN_TIMELINE_HEIGHT, timeline_upper);
    context_height = context_height
        .min((pane_height - timeline_height - MIN_BODY_HEIGHT).max(MIN_CONTEXT_HEIGHT))
        .max(0.0);
    let body_height = (pane_height - timeline_height - context_height).max(MIN_BODY_HEIGHT);
    let interior_height = timeline_height + body_height + context_height + dividers;

    let available_body_width = width - 2.0 * studio::metrics::WORKSPACE_PADDING;
    let body_width = available_body_width
        .max(CLUSTERS_MIN_WIDTH + PROBLEMS_MIN_WIDTH + DETAILS_MIN_WIDTH + SPLITTER * 2.0);
    let pane_width = body_width - SPLITTER * 2.0;
    let clusters_width = (pane_width * this.ui.clusters_ratio).clamp(
        CLUSTERS_MIN_WIDTH,
        (pane_width - PROBLEMS_MIN_WIDTH - DETAILS_MIN_WIDTH).max(CLUSTERS_MIN_WIDTH),
    );
    let details_boundary = (pane_width * this.ui.details_ratio).clamp(
        clusters_width + PROBLEMS_MIN_WIDTH,
        (pane_width - DETAILS_MIN_WIDTH).max(clusters_width + PROBLEMS_MIN_WIDTH),
    );
    let problems_width = details_boundary - clusters_width;

    let body_row = div()
        .id("frame-workspace-body-row")
        .w(px(body_width))
        .h(px(body_height))
        .flex()
        .child(
            div()
                .w(px(clusters_width))
                .h_full()
                .flex_shrink_0()
                .overflow_hidden()
                .child(clusters_panel(this, colors, cx)),
        )
        .child(splitter(
            "frame-clusters-width-resizer",
            SplitterKind::Clusters,
            true,
            colors,
            cx,
        ))
        .child(
            div()
                .w(px(problems_width))
                .h_full()
                .flex_shrink_0()
                .overflow_hidden()
                .child(problems_panel(this, colors, cx)),
        )
        .child(splitter(
            "frame-details-width-resizer",
            SplitterKind::Details,
            true,
            colors,
            cx,
        ))
        .child(
            div()
                .flex_1()
                .min_w_0()
                .h_full()
                .overflow_hidden()
                .child(detail_panel(this, colors, cx)),
        );

    let mut interior = div()
        .id("frame-workspace-interior")
        .w_full()
        .h(px(interior_height))
        .flex()
        .flex_col()
        .child(
            div()
                .h(px(timeline_height))
                .w_full()
                .flex_shrink_0()
                .overflow_hidden()
                .child(timeline_panel(this, colors, cx)),
        )
        .child(splitter(
            "frame-timeline-height-resizer",
            SplitterKind::Timeline,
            false,
            colors,
            cx,
        ))
        .child(
            div()
                .id("frame-workspace-body-scroll")
                .h(px(body_height))
                .w_full()
                .flex_shrink_0()
                .overflow_x_scroll()
                .child(body_row),
        );
    if show_context {
        interior = interior
            .child(splitter(
                "frame-context-height-resizer",
                SplitterKind::Context,
                false,
                colors,
                cx,
            ))
            .child(
                div()
                    .h(px(context_height))
                    .w_full()
                    .flex_shrink_0()
                    .overflow_hidden()
                    .child(context_panel(this, colors, cx)),
            );
    }
    let card = div()
        .id("frame-analysis-card")
        .w_full()
        .h(px(interior_height))
        .flex()
        .flex_col()
        .overflow_hidden()
        .bg(colors.panel)
        .border_1()
        .border_color(colors.border)
        .rounded(px(studio::metrics::CARD_RADIUS))
        .child(interior);
    div()
        .id("frame-workspace-scroll")
        .flex_1()
        .min_h_0()
        .w_full()
        .overflow_y_scroll()
        .child(card)
        .into_any_element()
}

fn narrow_workspace(
    this: &FrameWorkspace,
    _width: f32,
    colors: &StudioColors,
    cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let filtered = this.filtered_analysis.as_deref();
    let show_context = filtered.is_some_and(|analysis| !analysis.attributions.is_empty())
        || !this.warnings.is_empty();
    let mut column = div()
        .id("frame-workspace-narrow")
        .flex_1()
        .min_h_0()
        .w_full()
        .overflow_y_scroll()
        .flex()
        .flex_col()
        .gap(px(studio::metrics::CARD_GAP))
        .child(
            div()
                .h(px(NARROW_TIMELINE_HEIGHT))
                .w_full()
                .flex_shrink_0()
                .child(analysis_card(
                    timeline_panel(this, colors, cx),
                    None,
                    colors,
                )),
        )
        .child(
            div()
                .h(px(240.))
                .w_full()
                .flex_shrink_0()
                .child(analysis_card(
                    clusters_panel(this, colors, cx),
                    None,
                    colors,
                )),
        )
        .child(
            div()
                .h(px(300.))
                .w_full()
                .flex_shrink_0()
                .child(analysis_card(
                    problems_panel(this, colors, cx),
                    None,
                    colors,
                )),
        )
        .child(
            div()
                .h(px(560.))
                .w_full()
                .flex_shrink_0()
                .child(analysis_card(detail_panel(this, colors, cx), None, colors)),
        );
    if show_context {
        column = column.child(
            div()
                .h(px(220.))
                .w_full()
                .flex_shrink_0()
                .child(analysis_card(context_panel(this, colors, cx), None, colors)),
        );
    }
    column.into_any_element()
}

fn timeline_status_color(status: FrameTimelineStatus, colors: &StudioColors) -> Rgba {
    match status {
        FrameTimelineStatus::Good => rgb(0x4caf50),
        FrameTimelineStatus::HighLatency => rgb(0xc0d588),
        FrameTimelineStatus::SelfJank => rgb(0xff5722),
        FrameTimelineStatus::OtherJank => rgb(0xffeb3b),
        FrameTimelineStatus::Dropped => rgb(0x03a9f4),
        FrameTimelineStatus::Unknown => colors.secondary_text,
    }
}

fn filter_button(
    id: &'static str,
    label: impl Into<SharedString>,
    enabled: bool,
    colors: &StudioColors,
) -> Button {
    studio::compact_text_button(id, label, enabled, false, colors)
}

fn source_filter_control(
    this: &FrameWorkspace,
    colors: &StudioColors,
    cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let language = this.language;
    let sources = this
        .analysis
        .as_deref()
        .map(model::available_sources)
        .unwrap_or_default();
    let label = this
        .source_filter
        .map(|source| model::source_display(language, source))
        .unwrap_or_else(|| language.text(TextKey::FrameAllSources).to_owned());
    let entity = cx.entity().downgrade();
    let all_label = language.text(TextKey::FrameAllSources).to_owned();
    let busy = this.is_capturing || this.is_loading;
    let items: Vec<(FrameSource, String)> = sources
        .iter()
        .map(|source| (*source, model::source_display(language, *source)))
        .collect();
    filter_button(
        "frame-source-filter",
        label,
        !busy && !items.is_empty(),
        colors,
    )
    .dropdown_menu(move |menu, _, _| {
        let mut menu = menu;
        let reset = entity.clone();
        menu = menu.item(
            PopupMenuItem::new(all_label.clone()).on_click(move |_, _, cx| {
                let reset = reset.clone();
                let _ = reset.update(cx, |this, cx| this.set_source_filter(None, cx));
            }),
        );
        for (source, label) in &items {
            let source = *source;
            let entity = entity.clone();
            menu = menu.item(PopupMenuItem::new(label.clone()).on_click(move |_, _, cx| {
                let _ = entity.update(cx, |this, cx| this.set_source_filter(Some(source), cx));
            }));
        }
        menu
    })
    .into_any_element()
}

fn view_filter_control(
    this: &FrameWorkspace,
    colors: &StudioColors,
    cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let language = this.language;
    let label = language.text(model::view_filter_label(this.view_filter));
    let entity = cx.entity().downgrade();
    let busy = this.is_capturing || this.is_loading;
    let items: Vec<(FrameViewFilter, String)> = model::VIEW_FILTERS
        .iter()
        .map(|filter| {
            (
                *filter,
                language.text(model::view_filter_label(*filter)).to_owned(),
            )
        })
        .collect();
    filter_button("frame-result-filter", label, !busy, colors)
        .dropdown_menu(move |menu, _, _| {
            let mut menu = menu;
            for (filter, label) in &items {
                let filter = *filter;
                let entity = entity.clone();
                menu = menu.item(PopupMenuItem::new(label.clone()).on_click(move |_, _, cx| {
                    let _ = entity.update(cx, |this, cx| this.set_view_filter(filter, cx));
                }));
            }
            menu
        })
        .into_any_element()
}

fn timeline_panel(
    this: &FrameWorkspace,
    colors: &StudioColors,
    cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let language = this.language;
    let filtered = this.filtered_analysis.as_deref();
    let frames: &[AnalyzedFrame] = filtered.map_or(&[], |analysis| analysis.frames.as_slice());
    let total = frames.len();
    let window = model::frame_timeline_window(
        total,
        this.ui.timeline_first_index,
        this.ui.timeline_visible_count.max(1),
    );
    let selected_index = this
        .selected_frame_id
        .and_then(|id| frames.iter().position(|frame| frame.sample.frame_id == id));

    let heading = div()
        .w_full()
        .flex_shrink_0()
        .flex()
        .flex_wrap()
        .items_center()
        .gap(px(studio::metrics::PANEL_GAP))
        .child(
            div()
                .flex_shrink_0()
                .text_size(px(FONT_TITLE))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .child(language.text(TextKey::FrameTimeline)),
        )
        .child(
            div()
                .flex()
                .flex_wrap()
                .items_center()
                .gap(px(6.))
                .child(
                    div()
                        .flex_shrink_0()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .child(language.text(TextKey::FrameSource)),
                )
                .child(source_filter_control(this, colors, cx))
                .child(
                    div()
                        .flex_shrink_0()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .child(language.text(TextKey::FrameFilter)),
                )
                .child(view_filter_control(this, colors, cx)),
        )
        .child(div().flex_1())
        .child(
            div()
                .flex_shrink_0()
                .flex()
                .items_center()
                .gap(px(6.))
                .child(
                    filter_button(
                        "frame-timeline-previous",
                        language.text(TextKey::FrameTimelinePrevious),
                        window.start_index > 0,
                        colors,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.ui.timeline_first_index =
                            window.start_index.saturating_sub(window.count);
                        cx.notify();
                    })),
                )
                .child(
                    filter_button(
                        "frame-timeline-next",
                        language.text(TextKey::FrameTimelineNext),
                        window.end_exclusive() < total,
                        colors,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        if window.count > 0 && total >= window.count {
                            this.ui.timeline_first_index =
                                (window.start_index + window.count).min(total - window.count);
                        }
                        cx.notify();
                    })),
                )
                .child(
                    filter_button(
                        "frame-timeline-zoom-in",
                        language.text(TextKey::FrameTimelineZoomIn),
                        window.count > TIMELINE_MIN_VISIBLE_FRAMES,
                        colors,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        let anchor = selected_index
                            .filter(|index| {
                                *index >= window.start_index && *index < window.end_exclusive()
                            })
                            .unwrap_or(window.start_index + window.count / 2);
                        let count = (window.count / 2).max(TIMELINE_MIN_VISIBLE_FRAMES);
                        let next = model::centered_frame_timeline_window(total, anchor, count);
                        this.ui.timeline_visible_count = next.count;
                        this.ui.timeline_first_index = next.start_index;
                        cx.notify();
                    })),
                )
                .child(
                    filter_button(
                        "frame-timeline-zoom-out",
                        language.text(TextKey::FrameTimelineZoomOut),
                        window.count < total.min(TIMELINE_MAX_VISIBLE_FRAMES),
                        colors,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        let anchor = selected_index
                            .filter(|index| {
                                *index >= window.start_index && *index < window.end_exclusive()
                            })
                            .unwrap_or(window.start_index + window.count / 2);
                        let count = (window.count * 2).min(total.min(TIMELINE_MAX_VISIBLE_FRAMES));
                        let next =
                            model::centered_frame_timeline_window(total, anchor, count.max(1));
                        this.ui.timeline_visible_count = next.count;
                        this.ui.timeline_first_index = next.start_index;
                        cx.notify();
                    })),
                ),
        );

    if frames.is_empty() {
        let mut empty = div()
            .id("frame-timeline-panel")
            .size_full()
            .flex()
            .flex_col()
            .gap(px(6.))
            .p(px(studio::metrics::COMPACT_PADDING))
            .child(heading)
            .child(
                div()
                    .w_full()
                    .text_size(px(FONT_BODY))
                    .text_color(colors.secondary_text)
                    .child(language.text(TextKey::FrameNoFramesMatchFilter)),
            );
        if this.analysis.is_none() {
            let capture_hint = if this.is_capturing {
                TextKey::FrameWaitingForLiveFrameData
            } else {
                TextKey::FrameCaptureOnlineOrImportFramestats
            };
            empty = empty
                .child(
                    div()
                        .pt(px(8.))
                        .text_size(px(FONT_BODY))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(colors.text)
                        .child(language.text(capture_hint)),
                )
                .child(
                    div()
                        .w_full()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .child(language.text(
                            TextKey::FrameSelectADebuggableProcessFramemetricsAgentIsPreferredAndGfxinf,
                        )),
                );
            if let Some(message) = this
                .operation_status
                .as_ref()
                .map(|status| status.localized(language))
            {
                empty = empty.child(
                    div()
                        .w_full()
                        .pt(px(8.))
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .child(message),
                );
            }
            if let Some(error) = this.error.clone() {
                empty = empty.child(
                    div()
                        .w_full()
                        .pt(px(8.))
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.error)
                        .child(error),
                );
            }
        }
        return empty.into_any_element();
    }

    let visible = &frames[window.start_index..window.end_exclusive()];
    let maximum_duration = visible
        .iter()
        .filter_map(|frame| frame.sample.resolved_duration_ns())
        .max()
        .unwrap_or(1)
        .max(1);
    let ticks = model::duration_ticks(maximum_duration);
    let tick_labels: Vec<String> = ticks
        .iter()
        .rev()
        .map(|tick| model::format_tick(*tick))
        .collect();
    let axis_indices = model::axis_indices(window);
    let axis_labels: Vec<String> = axis_indices
        .iter()
        .filter_map(|index| frames.get(*index))
        .map(|frame| format!("#{}", frame.sample.frame_id))
        .collect();
    let cluster_ids: BTreeSet<u64> = filtered
        .map(|analysis| {
            analysis
                .clusters
                .iter()
                .flat_map(|cluster| cluster.deadline_miss_frame_ids.iter().copied())
                .collect()
        })
        .unwrap_or_default();
    let range_label = language.format(
        TextKey::FrameTimelineVisibleRange,
        &[
            (
                "value1",
                &if window.count == 0 {
                    0
                } else {
                    window.start_index + 1
                },
            ),
            ("value2", &window.end_exclusive()),
            ("value3", &total),
        ],
    );

    let legend = {
        let mut row = div()
            .id("frame-timeline-legend")
            .w_full()
            .flex_shrink_0()
            .flex()
            .items_center()
            .gap(px(12.))
            .overflow_x_scroll();
        for status in model::TIMELINE_LEGEND {
            row = row.child(
                div()
                    .flex_shrink_0()
                    .flex()
                    .items_center()
                    .gap(px(4.))
                    .child(
                        div()
                            .w(px(8.))
                            .h(px(8.))
                            .rounded(px(1.))
                            .bg(timeline_status_color(status, colors)),
                    )
                    .child(
                        div()
                            .text_size(px(FONT_SUPPORTING))
                            .text_color(colors.secondary_text)
                            .child(language.text(model::timeline_status_label(status))),
                    ),
            );
        }
        row
    };

    let caption = {
        let hint = if cluster_ids.is_empty() {
            String::new()
        } else {
            language
                .text(TextKey::FrameTimelineClusterMarker)
                .to_owned()
        };
        let hover = this
            .ui
            .timeline_hovered
            .and_then(|index| frames.get(index))
            .map(|frame| {
                language.format(
                    TextKey::FrameTimelineHoverFrame,
                    &[
                        ("value1", &frame.sample.frame_id),
                        (
                            "value2",
                            &model::format_millis(frame.sample.resolved_duration_ns()),
                        ),
                        ("value3", &frame.deadline_verdict.as_name()),
                    ],
                )
            });
        let parts: Vec<String> = [hint, hover.unwrap_or_default()]
            .into_iter()
            .filter(|part| !part.is_empty())
            .collect();
        parts.join("  |  ")
    };

    let bars: Vec<(Option<i64>, FrameTimelineStatus, bool)> = visible
        .iter()
        .map(|frame| {
            (
                frame.sample.resolved_duration_ns(),
                model::frame_timeline_status(&frame.sample),
                cluster_ids.contains(&frame.sample.frame_id),
            )
        })
        .collect();
    let paint_ticks = ticks.clone();
    let bounds_arc = this.ui.timeline_bounds.clone();
    let hover_arc_move = this.ui.timeline_bounds.clone();
    let hover_arc_down = this.ui.timeline_bounds.clone();
    let paint_colors = *colors;
    let paint_axis = axis_indices.clone();
    let paint_start = window.start_index;
    let paint_count = window.count;
    let paint_selected = selected_index.and_then(|index| {
        (index >= window.start_index && index < window.end_exclusive())
            .then_some(index - window.start_index)
    });
    let plot = div()
        .id("frame-timeline-canvas-area")
        .relative()
        .flex_1()
        .min_w_0()
        .h_full()
        .on_mouse_move(cx.listener(move |this, event: &MouseMoveEvent, _, cx| {
            if this.ui.timeline_pan.is_some() {
                return;
            }
            let Ok(bounds) = hover_arc_move.lock().map(|bounds| *bounds) else {
                return;
            };
            let Some(bounds) = bounds else {
                return;
            };
            let width = f32::from(bounds.size.width);
            let x = f32::from(event.position.x - bounds.origin.x);
            let window = model::frame_timeline_window(
                this.ui.timeline_total,
                this.ui.timeline_first_index,
                this.ui.timeline_visible_count.max(1),
            );
            let hovered = window.index_at(x, width);
            if this.ui.timeline_hovered != hovered {
                this.ui.timeline_hovered = hovered;
                cx.notify();
            }
        }))
        .on_mouse_down(
            MouseButton::Left,
            cx.listener(move |this, event: &MouseDownEvent, _, cx| {
                let Ok(bounds) = hover_arc_down.lock().map(|bounds| *bounds) else {
                    return;
                };
                let Some(bounds) = bounds else {
                    return;
                };
                let width = f32::from(bounds.size.width);
                let x = f32::from(event.position.x - bounds.origin.x);
                let window = model::frame_timeline_window(
                    this.ui.timeline_total,
                    this.ui.timeline_first_index,
                    this.ui.timeline_visible_count.max(1),
                );
                let Some(index) = window.index_at(x, width) else {
                    return;
                };
                this.ui.timeline_pan = Some(TimelinePan {
                    start_x: f32::from(event.position.x),
                    start_index: this.ui.timeline_first_index,
                    bar_width: width / window.count.max(1) as f32,
                });
                if let Some(frame) = this
                    .filtered_analysis
                    .as_deref()
                    .and_then(|analysis| analysis.frames.get(index))
                {
                    let frame_id = frame.sample.frame_id;
                    this.select_frame(frame_id, cx);
                }
            }),
        )
        .on_mouse_exit(cx.listener(|this, _, _, cx| {
            if this.ui.timeline_hovered.take().is_some() {
                cx.notify();
            }
        }))
        .child(
            canvas(
                move |bounds: Bounds<Pixels>, _: &mut Window, _: &mut App| {
                    if let Ok(mut slot) = bounds_arc.lock() {
                        *slot = Some(bounds);
                    }
                },
                move |bounds: Bounds<Pixels>, _, window: &mut Window, _: &mut App| {
                    let width = f32::from(bounds.size.width);
                    let height = f32::from(bounds.size.height);
                    if width <= 0.0 || height <= 0.0 || paint_count == 0 || bars.is_empty() {
                        return;
                    }
                    let bar_width = width / paint_count as f32;
                    let maximum_tick = *paint_ticks.last().unwrap_or(&1.0);
                    if maximum_tick <= 0.0 {
                        return;
                    }
                    for tick in &paint_ticks {
                        let y = height * (1.0 - (tick / maximum_tick) as f32);
                        window.paint_quad(quad(
                            Bounds {
                                origin: point(bounds.origin.x, bounds.origin.y + px(y)),
                                size: size(px(width), px(1.)),
                            },
                            px(0.),
                            paint_colors.border.alpha(0.6),
                            px(0.),
                            transparent_black(),
                            Default::default(),
                        ));
                    }
                    for index in &paint_axis {
                        if *index < paint_start {
                            continue;
                        }
                        let x = ((*index - paint_start) as f32 + 0.5) * bar_width;
                        window.paint_quad(quad(
                            Bounds {
                                origin: point(bounds.origin.x + px(x), bounds.origin.y),
                                size: size(px(1.), px(height)),
                            },
                            px(0.),
                            paint_colors.border.alpha(0.4),
                            px(0.),
                            transparent_black(),
                            Default::default(),
                        ));
                    }
                    for (index, (duration_ns, status, cluster)) in bars.iter().enumerate() {
                        let duration_ms = duration_ns.unwrap_or(0) as f64 / 1_000_000.0;
                        let bar_height = ((duration_ms / maximum_tick) as f32 * height).max(2.0);
                        window.paint_quad(quad(
                            Bounds {
                                origin: point(
                                    bounds.origin.x + px(index as f32 * bar_width),
                                    bounds.origin.y + px(height - bar_height),
                                ),
                                size: size(px((bar_width - 1.0).max(1.0)), px(bar_height)),
                            },
                            px(0.),
                            timeline_status_color(*status, &paint_colors),
                            px(0.),
                            transparent_black(),
                            Default::default(),
                        ));
                        if *cluster {
                            window.paint_quad(quad(
                                Bounds {
                                    origin: point(
                                        bounds.origin.x + px(index as f32 * bar_width),
                                        bounds.origin.y,
                                    ),
                                    size: size(px(bar_width.max(1.0)), px(4.)),
                                },
                                px(0.),
                                paint_colors.error,
                                px(0.),
                                transparent_black(),
                                Default::default(),
                            ));
                        }
                    }
                    if let Some(index) = paint_selected {
                        let x = index as f32 * bar_width + bar_width / 2.0;
                        window.paint_quad(quad(
                            Bounds {
                                origin: point(bounds.origin.x + px(x), bounds.origin.y),
                                size: size(px(2.), px(height)),
                            },
                            px(0.),
                            paint_colors.text,
                            px(0.),
                            transparent_black(),
                            Default::default(),
                        ));
                    }
                },
            )
            .absolute()
            .inset_0(),
        );

    let plot_row = div()
        .flex_1()
        .min_h_0()
        .w_full()
        .flex()
        .child(
            div()
                .w(px(AXIS_GUTTER_WIDTH))
                .h_full()
                .flex_shrink_0()
                .flex()
                .flex_col()
                .justify_between()
                .items_end()
                .children(tick_labels.into_iter().map(|label| {
                    div()
                        .pr(px(6.))
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .child(label)
                })),
        )
        .child(plot);
    let frame_axis = div()
        .w_full()
        .h(px(FRAME_AXIS_HEIGHT))
        .flex_shrink_0()
        .flex()
        .justify_between()
        .pl(px(AXIS_GUTTER_WIDTH))
        .children(axis_labels.into_iter().map(|label| {
            div()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(label)
        }));
    div()
        .id("frame-timeline-panel")
        .size_full()
        .flex()
        .flex_col()
        .gap(px(4.))
        .p(px(studio::metrics::COMPACT_PADDING))
        .child(heading)
        .child(legend)
        .child(
            div()
                .w_full()
                .flex_shrink_0()
                .flex()
                .justify_between()
                .child(
                    div()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .child(language.text(TextKey::FrameTimelineDurationAxis)),
                )
                .child(
                    div()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .child(range_label),
                ),
        )
        .child(plot_row)
        .child(frame_axis)
        .child(
            div()
                .w_full()
                .flex_shrink_0()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(caption),
        )
        .into_any_element()
}

fn empty_panel(message: impl Into<SharedString>, colors: &StudioColors) -> AnyElement {
    div()
        .p(px(8.))
        .text_size(px(FONT_SUPPORTING))
        .text_color(colors.secondary_text)
        .child(message.into())
        .into_any_element()
}

fn panel_header(
    title: impl Into<SharedString>,
    subtitle: Option<String>,
    colors: &StudioColors,
) -> Div {
    let mut header = div()
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(6.))
        .px(px(studio::metrics::COMPACT_PADDING))
        .py(px(6.))
        .child(studio::section_title(title, colors));
    if let Some(subtitle) = subtitle {
        header = header.child(
            div()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(subtitle),
        );
    }
    header
}

fn clusters_panel(
    this: &FrameWorkspace,
    colors: &StudioColors,
    cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let language = this.language;
    let analysis = this.filtered_analysis.as_deref();
    let action = analysis
        .map(|analysis| analysis.clusters.len())
        .unwrap_or(0);
    let subtitle = language.format(TextKey::FrameClusterCount, &[("value1", &action)]);
    let header = panel_header(
        language.text(TextKey::FrameJankClusters),
        Some(subtitle),
        colors,
    );
    let mut list = div()
        .id("frame-cluster-list")
        .flex_1()
        .min_h_0()
        .w_full()
        .overflow_y_scrollbar()
        .flex()
        .flex_col()
        .gap(px(2.));
    match analysis {
        None => {
            list = list.child(empty_panel(
                language.text(TextKey::FrameNoJankClustersDetected),
                colors,
            ));
        }
        Some(analysis) if analysis.clusters.is_empty() => {
            list = list.child(empty_panel(
                language.text(TextKey::FrameNoJankClustersDetected),
                colors,
            ));
        }
        Some(analysis) => {
            for cluster in &analysis.clusters {
                list = list.child(cluster_row(
                    analysis,
                    cluster,
                    this.selected_frame_id,
                    language,
                    colors,
                    cx,
                ));
            }
        }
    }
    div()
        .id("frame-clusters-panel")
        .size_full()
        .flex()
        .flex_col()
        .overflow_hidden()
        .child(header)
        .child(list)
        .into_any_element()
}

fn severity_color(severity: JankSeverity, colors: &StudioColors) -> Rgba {
    match severity {
        JankSeverity::Minor => colors.success,
        JankSeverity::Major => colors.warning,
        JankSeverity::Severe | JankSeverity::Frozen => colors.error,
        JankSeverity::Smooth | JankSeverity::Unknown => colors.secondary_text,
    }
}

fn cluster_row(
    analysis: &FrameAnalysisResult,
    cluster: &DeadlineMissCluster,
    selected_frame_id: Option<u64>,
    language: Language,
    colors: &StudioColors,
    cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let accent = severity_color(cluster.worst_severity, colors);
    let selected = selected_frame_id
        .is_some_and(|frame_id| cluster.deadline_miss_frame_ids.contains(&frame_id));
    let target = model::cluster_target_frame_id(analysis, cluster);
    let range = language.format(
        TextKey::FrameText,
        &[
            ("value1", &cluster.first_frame_id),
            ("value2", &cluster.last_frame_id),
        ],
    );
    let dominant = cluster
        .dominant_reported_stage
        .clone()
        .unwrap_or_else(|| language.text(TextKey::FrameUnknownStage).to_owned());
    let summary = language.format(
        TextKey::FrameJankClusterSummary,
        &[
            ("value1", &cluster.deadline_miss_frame_ids.len()),
            ("value2", &dominant),
        ],
    );
    let severity_line = format!(
        "{} · {}",
        cluster.worst_severity.as_name(),
        model::format_millis(Some(cluster.duration_ns))
    );
    let durations: Vec<f64> = cluster
        .deadline_miss_frame_ids
        .iter()
        .rev()
        .take(CLUSTER_SPARKLINE_FRAMES)
        .filter_map(|frame_id| {
            analysis
                .frames
                .iter()
                .find(|frame| frame.sample.frame_id == *frame_id)
                .and_then(|frame| frame.sample.resolved_duration_ns())
                .map(|duration| duration as f64)
        })
        .collect::<Vec<_>>()
        .into_iter()
        .rev()
        .collect();
    let row = div()
        .id(("frame-cluster-row", cluster.id))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(8.))
        .px(px(studio::metrics::COMPACT_PADDING))
        .py(px(8.))
        .bg(if selected {
            colors.accent.alpha(0.12)
        } else {
            colors.panel
        })
        .cursor_pointer()
        .on_click(cx.listener(move |this, _, _, cx| {
            if let Some(target) = target {
                this.select_frame(target, cx);
            }
        }))
        .child(
            div()
                .w(px(28.))
                .h(px(28.))
                .flex_shrink_0()
                .flex()
                .items_center()
                .justify_center()
                .bg(accent)
                .child(
                    div()
                        .text_size(px(FONT_SUPPORTING))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(colors.accent_text)
                        .child(format!("#{}", cluster.id + 1)),
                ),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .gap(px(3.))
                .child(
                    div()
                        .w_full()
                        .text_size(px(FONT_SUPPORTING))
                        .font_weight(if selected {
                            FontWeight::SEMIBOLD
                        } else {
                            FontWeight::NORMAL
                        })
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(range),
                )
                .child(
                    div()
                        .w_full()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(summary),
                )
                .child(
                    div()
                        .w_full()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(accent)
                        .whitespace_nowrap()
                        .child(severity_line),
                ),
        )
        .child(cluster_sparkline(&durations, accent));
    row.into_any_element()
}

fn cluster_sparkline(durations: &[f64], color: Rgba) -> AnyElement {
    if durations.is_empty() {
        return div()
            .w(px(CLUSTER_SPARKLINE_WIDTH))
            .h(px(CLUSTER_SPARKLINE_HEIGHT))
            .flex_shrink_0()
            .into_any_element();
    }
    let durations = durations.to_vec();
    let maximum = durations
        .iter()
        .copied()
        .fold(f64::NEG_INFINITY, f64::max)
        .max(1.0);
    canvas(
        move |_bounds: Bounds<Pixels>, _: &mut Window, _: &mut App| {},
        move |bounds: Bounds<Pixels>, _, window: &mut Window, _: &mut App| {
            let width = f32::from(bounds.size.width);
            let height = f32::from(bounds.size.height);
            if width <= 0.0 || height <= 0.0 || durations.is_empty() {
                return;
            }
            let bar_width = width / (durations.len() as f32 * 1.5).max(1.0);
            for (index, duration) in durations.iter().enumerate() {
                let bar_height = ((*duration / maximum) as f32 * height).max(2.0);
                window.paint_quad(quad(
                    Bounds {
                        origin: point(
                            bounds.origin.x + px(index as f32 * bar_width * 1.5),
                            bounds.origin.y + px(height - bar_height),
                        ),
                        size: size(px(bar_width), px(bar_height)),
                    },
                    px(0.),
                    color,
                    px(0.),
                    transparent_black(),
                    Default::default(),
                ));
            }
        },
    )
    .w(px(CLUSTER_SPARKLINE_WIDTH))
    .h(px(CLUSTER_SPARKLINE_HEIGHT))
    .flex_shrink_0()
    .into_any_element()
}

fn problems_panel(
    this: &FrameWorkspace,
    colors: &StudioColors,
    cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let language = this.language;
    let analysis = this.filtered_analysis.as_deref();
    let problem_indices: Vec<usize> = analysis
        .map(|analysis| {
            analysis
                .frames
                .iter()
                .enumerate()
                .filter(|(_, frame)| model::is_problem_frame(frame))
                .map(|(index, _)| index)
                .collect()
        })
        .unwrap_or_default();
    let header = panel_header(language.text(TextKey::FrameProblemFrames), None, colors);
    let columns = div()
        .id("frame-problem-header")
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(6.))
        .px(px(studio::metrics::COMPACT_PADDING))
        .py(px(6.))
        .bg(colors.section_background)
        .child(problem_cell(
            language.text(TextKey::FrameFrame),
            0.55,
            colors,
            true,
        ))
        .child(problem_cell(
            language.text(TextKey::FrameDuration),
            0.8,
            colors,
            true,
        ))
        .child(problem_cell(
            language.text(TextKey::FrameSignal),
            1.2,
            colors,
            true,
        ))
        .child(problem_cell(
            language.text(TextKey::FrameActivity),
            1.0,
            colors,
            true,
        ))
        .child(problem_cell(
            language.text(TextKey::FrameLargestReportedStage),
            1.0,
            colors,
            true,
        ));
    let body = if problem_indices.is_empty() {
        empty_panel(language.text(TextKey::FrameNoProblemFrames), colors)
    } else {
        let entity = cx.entity();
        let selected_frame_id = this.selected_frame_id;
        let colors_copy = *colors;
        let frames_arc = this.filtered_analysis.clone();
        let list = uniform_list("frame-problem-list", problem_indices.len(), {
            let frames_arc = frames_arc.clone();
            move |range, _, _| {
                let mut items = Vec::with_capacity(range.len());
                for index in range {
                    let Some(position) = problem_indices.get(index) else {
                        continue;
                    };
                    let Some(frame) = frames_arc
                        .as_deref()
                        .and_then(|analysis| analysis.frames.get(*position))
                    else {
                        continue;
                    };
                    items.push(problem_row(
                        index,
                        frame,
                        selected_frame_id,
                        &entity,
                        language,
                        &colors_copy,
                    ));
                }
                items
            }
        })
        .h_full()
        .w_full()
        .track_scroll(&this.ui.problem_scroll);
        div()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(list)
            .into_any_element()
    };
    div()
        .id("frame-problems-panel")
        .size_full()
        .flex()
        .flex_col()
        .overflow_hidden()
        .child(header)
        .child(columns)
        .child(body)
        .into_any_element()
}

fn problem_cell(
    label: impl Into<SharedString>,
    grow: f32,
    colors: &StudioColors,
    header: bool,
) -> Div {
    div()
        .flex_grow(grow)
        .flex_basis(relative(0.))
        .min_w_0()
        .text_size(px(FONT_SUPPORTING))
        .font_weight(if header {
            FontWeight::SEMIBOLD
        } else {
            FontWeight::NORMAL
        })
        .text_color(if header {
            colors.secondary_text
        } else {
            colors.text
        })
        .whitespace_nowrap()
        .text_ellipsis()
        .child(label.into())
}

fn problem_row(
    index: usize,
    frame: &AnalyzedFrame,
    selected_frame_id: Option<u64>,
    entity: &Entity<FrameWorkspace>,
    language: Language,
    colors: &StudioColors,
) -> AnyElement {
    let selected = selected_frame_id == Some(frame.sample.frame_id);
    let frame_id = frame.sample.frame_id;
    let activity = frame
        .sample
        .activity_name
        .as_deref()
        .and_then(|name| name.rsplit('.').next())
        .unwrap_or("—")
        .to_owned();
    let largest_stage = frame
        .largest_reported_stage
        .clone()
        .unwrap_or_else(|| "—".to_owned());
    let entity = entity.clone();
    div()
        .id(("problem-frame-row", index))
        .h(px(PROBLEM_ROW_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(6.))
        .px(px(studio::metrics::COMPACT_PADDING))
        .bg(if selected {
            colors.accent.alpha(0.12)
        } else {
            colors.panel
        })
        .cursor_pointer()
        .on_click(move |_, _, cx| {
            let _ = entity.update(cx, |this, cx| this.select_frame(frame_id, cx));
        })
        .child(
            div()
                .flex_grow(0.55)
                .flex_basis(relative(0.))
                .min_w_0()
                .font_weight(if selected {
                    FontWeight::SEMIBOLD
                } else {
                    FontWeight::NORMAL
                })
                .child(format!("#{frame_id}")),
        )
        .child(problem_cell(
            model::format_millis(frame.sample.resolved_duration_ns()),
            0.8,
            colors,
            false,
        ))
        .child(problem_cell(
            model::problem_signal(frame, language),
            1.2,
            colors,
            false,
        ))
        .child(problem_cell(activity, 1.0, colors, false))
        .child(problem_cell(largest_stage, 1.0, colors, false))
        .into_any_element()
}

fn context_panel(
    this: &FrameWorkspace,
    colors: &StudioColors,
    _cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let language = this.language;
    let header = panel_header(language.text(TextKey::FrameAttribution), None, colors);
    let mut content = div()
        .id("frame-context-content")
        .flex_1()
        .min_h_0()
        .w_full()
        .overflow_y_scrollbar()
        .flex()
        .flex_col()
        .gap(px(6.))
        .px(px(studio::metrics::COMPACT_PADDING))
        .py(px(8.));
    if let Some(analysis) = this.filtered_analysis.as_deref() {
        for (index, attribution) in analysis.attributions.iter().take(5).enumerate() {
            content = content.child(
                div()
                    .id(("frame-attribution-entry", index))
                    .w_full()
                    .text_size(px(FONT_SUPPORTING))
                    .text_color(colors.secondary_text)
                    .whitespace_nowrap()
                    .text_ellipsis()
                    .child(model::attribution_entry(attribution, language)),
            );
        }
    }
    for warning in &this.warnings {
        content = content.child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.warning)
                .child(warning.clone()),
        );
    }
    div()
        .id("frame-context-panel")
        .size_full()
        .flex()
        .flex_col()
        .overflow_hidden()
        .child(header)
        .child(content)
        .into_any_element()
}

fn detail_panel(
    this: &FrameWorkspace,
    colors: &StudioColors,
    cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let language = this.language;
    let analysis = this.filtered_analysis.as_deref();
    let selected = this.selected_frame();
    let previous =
        analysis.and_then(|analysis| model::previous_frame_id(analysis, this.selected_frame_id));
    let next = analysis.and_then(|analysis| model::next_frame_id(analysis, this.selected_frame_id));
    let header = div()
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(6.))
        .px(px(studio::metrics::COMPACT_PADDING))
        .py(px(6.))
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .gap(px(1.))
                .child(
                    div()
                        .text_size(px(FONT_BODY))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(colors.text)
                        .child(language.text(TextKey::FrameDetail)),
                )
                .child({
                    let subtitle = selected
                        .map(|frame| {
                            format!(
                                "#{} · {}",
                                frame.sample.frame_id,
                                model::source_display(language, frame.sample.source)
                            )
                        })
                        .unwrap_or_default();
                    div()
                        .w_full()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(subtitle)
                }),
        )
        .child(
            studio::compact_text_button(
                "frame-previous-frame",
                language.text(TextKey::FramePreviousFrame),
                previous.is_some(),
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.previous_frame(cx))),
        )
        .child(
            studio::compact_text_button(
                "frame-next-frame",
                language.text(TextKey::FrameNextFrame),
                next.is_some(),
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.next_frame(cx))),
        );
    let Some(frame) = selected else {
        return div()
            .id("frame-detail-panel")
            .size_full()
            .flex()
            .flex_col()
            .overflow_hidden()
            .child(header)
            .into_any_element();
    };
    let content = div()
        .flex_1()
        .min_h_0()
        .w_full()
        .flex()
        .gap(px(10.))
        .px(px(studio::metrics::COMPACT_PADDING))
        .pb(px(8.))
        .child(detail_body(this, frame, colors, cx))
        .child(layout_preview(this, frame, colors, cx));
    div()
        .id("frame-detail-panel")
        .size_full()
        .flex()
        .flex_col()
        .overflow_hidden()
        .child(header)
        .child(content)
        .into_any_element()
}

fn detail_body(
    this: &FrameWorkspace,
    frame: &AnalyzedFrame,
    colors: &StudioColors,
    cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let language = this.language;
    let sample = &frame.sample;
    let duration_color = if frame.deadline_verdict == FrameDeadlineVerdict::Missed {
        colors.error
    } else {
        colors.text
    };
    let mut details = div()
        .id("frame-detail-body")
        .flex_grow(1.2)
        .flex_basis(relative(0.))
        .min_w_0()
        .h_full()
        .overflow_y_scrollbar()
        .flex()
        .flex_col()
        .gap(px(6.))
        .child(
            div()
                .w_full()
                .flex()
                .gap(px(12.))
                .child(detail_metric(
                    language.text(TextKey::FrameDuration),
                    model::format_millis(sample.resolved_duration_ns()),
                    duration_color,
                    colors,
                ))
                .child(detail_metric(
                    language.text(TextKey::FrameBudget),
                    model::display_budget(sample),
                    colors.text,
                    colors,
                )),
        )
        .child(studio::divider(colors))
        .child(detail_row(
            language.text(TextKey::FrameBudgetSource),
            model::expected_duration_source_enum_name(sample.expected_duration_source),
            colors.text,
            colors,
        ))
        .child(detail_row(
            language.text(TextKey::FrameVerdict),
            frame.deadline_verdict.as_name(),
            colors.text,
            colors,
        ))
        .child(detail_row(
            language.text(TextKey::FrameMissedVsync),
            frame
                .missed_vsync_count
                .map(|value| value.to_string())
                .unwrap_or_else(|| "—".to_owned()),
            colors.text,
            colors,
        ))
        .child(detail_row(
            language.text(TextKey::FramePlatformJank),
            sample
                .platform_jank
                .map(|value| value.to_string())
                .unwrap_or_else(|| "—".to_owned()),
            colors.text,
            colors,
        ))
        .child(detail_row(
            language.text(TextKey::FrameTimelineVsyncId),
            sample
                .frame_timeline_vsync_id
                .map(|value| value.to_string())
                .unwrap_or_else(|| "—".to_owned()),
            colors.text,
            colors,
        ))
        .child(detail_row(
            language.text(TextKey::FrameLargestReportedStage),
            frame
                .largest_reported_stage
                .clone()
                .unwrap_or_else(|| "—".to_owned()),
            colors.text,
            colors,
        ));
    let stages = sample.stages.values();
    if !stages.is_empty() {
        let maximum = model::maximum_stage_ns(&sample.stages);
        details = details.child(
            div()
                .w_full()
                .pt(px(2.))
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .child(language.text(TextKey::FrameStageBreakdown)),
        );
        for (index, (name, duration)) in stages.into_iter().enumerate() {
            details = details.child(stage_row(name, duration, maximum, index, colors));
        }
    }
    details = details.child(detail_row(
        language.text(TextKey::FrameSource),
        model::source_display(language, sample.source),
        colors.text,
        colors,
    ));
    if let Some(activity) = sample
        .activity_name
        .as_deref()
        .and_then(|name| name.rsplit('.').next())
    {
        details = details.child(detail_row(
            language.text(TextKey::FrameActivity),
            activity.to_owned(),
            colors.text,
            colors,
        ));
    }
    if let Some(fragment) = sample.fragment_name.clone() {
        details = details.child(detail_row(
            language.text(TextKey::FrameFragment),
            fragment,
            colors.text,
            colors,
        ));
    }
    if let Some(page) = sample.page_name.clone() {
        details = details.child(detail_row(
            language.text(TextKey::FramePage),
            page,
            colors.text,
            colors,
        ));
    }
    if let Some(state) = sample.interaction_state.clone() {
        details = details.child(detail_row(
            language.text(TextKey::FrameInteractionState),
            state,
            colors.text,
            colors,
        ));
    }
    if let Some(render_thread) = sample.render_thread_name.clone() {
        details = details.child(detail_row(
            language.text(TextKey::FrameRenderThread),
            render_thread,
            colors.text,
            colors,
        ));
    }
    if let Some(window) = sample.window_id.clone() {
        details = details.child(detail_row(
            language.text(TextKey::FrameWindow),
            window,
            colors.text,
            colors,
        ));
    }
    if let Some(surface_flinger) = sample.surface_flinger_jank_type.clone() {
        details = details.child(detail_row(
            language.text(TextKey::FrameSurfaceFlingerJank),
            surface_flinger,
            colors.text,
            colors,
        ));
    }
    if let Some(jank_stats) = sample.jank_stats_jank {
        details = details.child(detail_row(
            language.text(TextKey::FrameJankStats),
            jank_stats.to_string(),
            colors.text,
            colors,
        ));
    }
    if !sample.jank_stats_reasons.is_empty() {
        let reasons: Vec<&str> = sample
            .jank_stats_reasons
            .iter()
            .map(String::as_str)
            .collect();
        details = details.child(detail_row(
            language.text(TextKey::FrameJankStatsReasons),
            reasons.join(", "),
            colors.text,
            colors,
        ));
    }
    if let Some(stage) = frame.largest_reported_stage.clone() {
        details = details.child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(
                    language.format(TextKey::FrameStageInvestigationHint, &[("value1", &stage)]),
                ),
        );
    }
    if !frame.platform_jank_types.is_empty() {
        let names: Vec<&str> = frame
            .platform_jank_types
            .iter()
            .map(|jank_type| jank_type.as_name())
            .collect();
        details = details.child(detail_row(
            language.text(TextKey::FrameJankTypes),
            names.join(", "),
            colors.text,
            colors,
        ));
    }
    for (key, value) in &sample.states {
        let label = language.format(TextKey::FrameStateDetail, &[("value1", key)]);
        details = details.child(detail_row(label, value.clone(), colors.text, colors));
    }
    if this.perfetto_trace_file.is_some() {
        details = details.child(
            studio::compact_text_button(
                "frame-open-trace-from-detail",
                language.text(TextKey::FrameOpenTraceInPerfetto),
                true,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.open_trace(cx))),
        );
    }
    let evidence = model::copy_evidence_text(frame, language);
    let evidence_frame_id = frame.sample.frame_id;
    details = details
        .child(
            studio::compact_text_button(
                "frame-copy-evidence",
                language.text(TextKey::FrameCopyEvidence),
                true,
                false,
                colors,
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                cx.write_to_clipboard(ClipboardItem::new_string(evidence.clone()));
                this.ui.copy_feedback = Some((evidence_frame_id, true));
                cx.notify();
            })),
        )
        .child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(language.text(TextKey::FrameCopyEvidencePrivacyHint)),
        );
    if let Some((frame_id, succeeded)) = this.ui.copy_feedback {
        if frame_id == frame.sample.frame_id {
            details = details.child(
                div()
                    .w_full()
                    .text_size(px(FONT_SUPPORTING))
                    .text_color(if succeeded {
                        colors.success
                    } else {
                        colors.error
                    })
                    .child(language.text(if succeeded {
                        TextKey::FrameEvidenceCopied
                    } else {
                        TextKey::FrameClipboardUnavailable
                    })),
            );
        }
    }
    details.into_any_element()
}

fn detail_metric(
    label: impl Into<SharedString>,
    value: String,
    value_color: Rgba,
    colors: &StudioColors,
) -> AnyElement {
    div()
        .flex_grow(1.)
        .flex_basis(relative(0.))
        .min_w_0()
        .flex()
        .flex_col()
        .gap(px(2.))
        .child(
            div()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .child(label.into()),
        )
        .child(
            div()
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(value_color)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(value),
        )
        .into_any_element()
}

fn detail_row(
    label: impl Into<SharedString>,
    value: impl Into<SharedString>,
    value_color: Rgba,
    colors: &StudioColors,
) -> AnyElement {
    div()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(4.))
        .child(
            div()
                .w_full()
                .flex()
                .items_center()
                .gap(px(8.))
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(label.into()),
                )
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(FONT_SUPPORTING))
                        .font_weight(FontWeight::MEDIUM)
                        .text_color(value_color)
                        .text_right()
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(value.into()),
                ),
        )
        .child(studio::divider(colors))
        .into_any_element()
}

fn stage_row(
    name: &str,
    duration_ns: i64,
    maximum_ns: i64,
    index: usize,
    colors: &StudioColors,
) -> AnyElement {
    let fraction = (duration_ns as f64 / maximum_ns.max(1) as f64).clamp(0.0, 1.0) as f32;
    let bar_color = match index % 4 {
        0 => colors.accent,
        1 => colors.warning,
        2 => colors.error,
        _ => colors.info,
    };
    div()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(4.))
        .child(
            div()
                .w_full()
                .flex()
                .items_center()
                .gap(px(6.))
                .child(
                    div()
                        .w(px(92.))
                        .flex_shrink_0()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(name.to_owned()),
                )
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .h(px(7.))
                        .bg(colors.section_background)
                        .child(div().w(relative(fraction)).h_full().bg(bar_color)),
                )
                .child(
                    div()
                        .w(px(64.))
                        .flex_shrink_0()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.text)
                        .text_right()
                        .whitespace_nowrap()
                        .child(model::format_millis(Some(duration_ns))),
                ),
        )
        .child(studio::divider(colors))
        .into_any_element()
}

fn layout_preview(
    this: &FrameWorkspace,
    frame: &AnalyzedFrame,
    colors: &StudioColors,
    cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let language = this.language;
    let snapshot = frame.sample.layout_snapshot_id.clone();
    let has_package = frame.sample.package_name.is_some();
    let mut column = div()
        .id("frame-layout-preview-pane")
        .flex_grow(0.8)
        .flex_basis(relative(0.))
        .min_w_0()
        .h_full()
        .flex()
        .flex_col()
        .gap(px(6.))
        .child(
            div()
                .w_full()
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .child(language.text(TextKey::FrameLayoutPreview)),
        )
        .child(
            div()
                .flex_1()
                .min_h_0()
                .w_full()
                .flex()
                .items_center()
                .justify_center()
                .child(
                    div()
                        .h(px(180.))
                        .aspect_ratio(0.62)
                        .flex()
                        .flex_col()
                        .items_center()
                        .justify_center()
                        .gap(px(4.))
                        .border_1()
                        .border_color(colors.border)
                        .bg(colors.panel)
                        .child(
                            div()
                                .text_size(px(FONT_SUPPORTING))
                                .text_color(colors.secondary_text)
                                .child(language.text(TextKey::FrameScreenshotUnavailable)),
                        )
                        .when_some(snapshot, |this, snapshot| {
                            this.child(
                                div()
                                    .max_w_full()
                                    .text_size(px(FONT_SUPPORTING))
                                    .text_color(colors.muted_text)
                                    .whitespace_nowrap()
                                    .text_ellipsis()
                                    .child(snapshot),
                            )
                        }),
                ),
        )
        .child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(language.text(TextKey::FrameLayoutPreviewCurrentOnly)),
        );
    if has_package {
        column = column.child(
            studio::compact_text_button(
                "frame-layout-preview-action",
                language.text(TextKey::FrameCorrelateInLayoutInspector),
                true,
                false,
                colors,
            )
            .w_full()
            .on_click(cx.listener(|this, _, _, cx| this.inspect_layout(cx))),
        );
    }
    column.into_any_element()
}
