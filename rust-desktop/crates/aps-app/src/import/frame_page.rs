//! Compose-aligned offline Frame profiler workspace.
//! Only gfxinfo FrameStats evidence owned by `aps-frame` is rendered here.
use super::*;
use aps_frame::{
    AnalyzedFrame, BudgetSource, FrameAnalysisPage, FramePage, FrameStages, FrameSummary,
};

const MIN_VISIBLE_FRAMES: usize = 20;
const MAX_VISIBLE_FRAMES: usize = 500;
const DEFAULT_VISIBLE_FRAMES: usize = 100;

fn ns(value: Option<u64>) -> String {
    value.map_or_else(
        || "—".into(),
        |value| format!("{:.2} ms", value as f64 / 1_000_000.0),
    )
}

fn integer(value: Option<u64>) -> String {
    value.map_or_else(|| "—".into(), |value| value.to_string())
}

fn percent(value: Option<f64>) -> String {
    value.map_or_else(|| "—".into(), |value| format!("{:.1}%", value * 100.0))
}

fn verdict(value: DeadlineVerdict, language: Language) -> &'static str {
    match value {
        DeadlineVerdict::Met => language.text(TextKey::FramePageDeadlineMet),
        DeadlineVerdict::Missed => language.text(TextKey::FramePageDeadlineMiss),
        DeadlineVerdict::Unknown => language.text(TextKey::FramePageUnknown),
    }
}

fn budget_source(value: BudgetSource, language: Language) -> &'static str {
    match value {
        BudgetSource::PlatformDeadline => language.text(TextKey::FramePageBudgetPlatformDeadline),
        BudgetSource::FrameInterval => language.text(TextKey::FramePageBudgetFrameInterval),
        BudgetSource::InferredVsync => language.text(TextKey::FramePageBudgetInferredVsync),
        BudgetSource::Unknown => "—",
    }
}

fn known_budget(frame: &AnalyzedFrame) -> Option<u64> {
    (frame.budget_source != BudgetSource::Unknown)
        .then_some(frame.budget_ns)
        .flatten()
}

fn stage_values(stages: &FrameStages) -> [(&'static str, Option<u64>, TextKey); 8] {
    [
        ("input", stages.input_ns, TextKey::FramePageInput),
        (
            "animation",
            stages.animation_ns,
            TextKey::FramePageAnimation,
        ),
        ("layout", stages.layout_measure_ns, TextKey::FramePageLayout),
        ("draw", stages.draw_ns, TextKey::FramePageDraw),
        ("sync", stages.sync_ns, TextKey::FramePageSync),
        (
            "command",
            stages.command_issue_ns,
            TextKey::FramePageCommand,
        ),
        ("swap", stages.swap_buffers_ns, TextKey::FramePageSwap),
        ("gpu", stages.gpu_ns, TextKey::FramePageGpu),
    ]
}

fn largest_stage(frame: &AnalyzedFrame, language: Language) -> String {
    stage_values(&frame.stages)
        .into_iter()
        .filter_map(|(_, value, key)| value.map(|value| (value, key)))
        .max_by_key(|(value, _)| *value)
        .map_or_else(
            || "—".into(),
            |(value, key)| format!("{} · {}", language.text(key), ns(Some(value))),
        )
}

impl ImportWorkspace {
    fn frame_page(&self) -> Option<FramePage> {
        self.page.as_ref()?.ui.frame_page.clone()
    }

    fn frame_windows(&self) -> Vec<String> {
        self.page
            .as_ref()
            .map(|page| page.ui.frame_windows.iter().take(16).cloned().collect())
            .unwrap_or_default()
    }

    fn frame_windows_truncated(&self) -> bool {
        self.page
            .as_ref()
            .is_some_and(|page| page.ui.frame_windows.len() > 16 || page.ui.frame_windows_truncated)
    }

    fn frame_analysis_page(&self) -> Option<FrameAnalysisPage> {
        self.page.as_ref()?.ui.frame_analysis.clone()
    }

    fn select_frame_at(&mut self, id: u64, filtered_index: usize, cx: &mut Context<Self>) {
        let count = self
            .frame_timeline_count
            .clamp(MIN_VISIBLE_FRAMES, MAX_VISIBLE_FRAMES);
        let offset = filtered_index / count * count;
        self.selected_frame_id = Some(id);
        self.frame_hovered_id = None;
        if self.frame_timeline_start == offset
            && self
                .page
                .as_ref()
                .and_then(|page| page.ui.frame_page.as_ref())
                .is_some_and(|page| page.rows.iter().any(|frame| frame.id == id))
        {
            cx.notify();
        } else {
            self.frame_timeline_start = offset;
            self.page(offset, cx);
        }
    }

    fn move_selected_frame(&mut self, previous: bool, cx: &mut Context<Self>) {
        let Some(page) = self.frame_page() else {
            return;
        };
        let current = self
            .selected_frame_id
            .and_then(|id| page.rows.iter().position(|frame| frame.id == id))
            .unwrap_or(0);
        if previous {
            if let Some(frame) = current
                .checked_sub(1)
                .and_then(|index| page.rows.get(index))
            {
                self.selected_frame_id = Some(frame.id);
                cx.notify();
            } else if page.offset > 0 {
                let offset = page.offset.saturating_sub(page.rows.len().max(1));
                self.selected_frame_id = None;
                self.frame_select_last_on_publish = true;
                self.frame_timeline_start = offset;
                self.page(offset, cx);
            }
        } else if let Some(frame) = page.rows.get(current + 1) {
            self.selected_frame_id = Some(frame.id);
            cx.notify();
        } else if page.offset.saturating_add(page.rows.len()) < page.total {
            let offset = page.offset.saturating_add(page.rows.len());
            self.selected_frame_id = None;
            self.frame_timeline_start = offset;
            self.page(offset, cx);
        }
    }

    fn set_frame_filter(&mut self, deadline: Option<DeadlineVerdict>, cx: &mut Context<Self>) {
        self.frame_deadline_filter = deadline;
        self.frame_timeline_start = 0;
        self.selected_frame_id = None;
        self.frame_hovered_id = None;
        self.page(0, cx);
    }

    fn set_frame_window(&mut self, window: Option<String>, cx: &mut Context<Self>) {
        self.frame_window_filter = window;
        self.frame_timeline_start = 0;
        self.selected_frame_id = None;
        self.frame_hovered_id = None;
        self.page(0, cx);
    }

    fn move_frame_range(&mut self, forward: bool, total: usize, cx: &mut Context<Self>) {
        let count = self
            .frame_timeline_count
            .clamp(MIN_VISIBLE_FRAMES, MAX_VISIBLE_FRAMES);
        let offset = if forward {
            self.frame_timeline_start
                .saturating_add(count)
                .min(total.saturating_sub(1))
        } else {
            self.frame_timeline_start.saturating_sub(count)
        };
        self.frame_timeline_start = offset;
        self.selected_frame_id = None;
        self.frame_hovered_id = None;
        self.page(offset, cx);
    }

    fn zoom_frame_range(&mut self, zoom_in: bool, cx: &mut Context<Self>) {
        let current = self
            .frame_timeline_count
            .clamp(MIN_VISIBLE_FRAMES, MAX_VISIBLE_FRAMES);
        self.frame_timeline_count = if zoom_in {
            current.saturating_sub(20).max(MIN_VISIBLE_FRAMES)
        } else {
            current.saturating_add(20).min(MAX_VISIBLE_FRAMES)
        };
        self.frame_timeline_start = 0;
        self.selected_frame_id = None;
        self.frame_hovered_id = None;
        self.page(0, cx);
    }

    pub(super) fn render_frame_workspace(&mut self, cx: &mut Context<Self>) -> AnyElement {
        let colors = studio::StudioColors::from_theme(cx);
        let language = self.language;
        let loaded = matches!(self.session, Some(Session::Frame(_)));
        if self.frame_timeline_count == 0 {
            self.frame_timeline_count = DEFAULT_VISIBLE_FRAMES;
        }
        let page = self.frame_page();
        let summary = page
            .as_ref()
            .map(|page| page.summary.clone())
            .or_else(|| match self.session.as_ref() {
                Some(Session::Frame(session)) => Some(session.summary().clone()),
                _ => None,
            })
            .unwrap_or_default();
        let mut root = studio::page(&colors)
            .id("frame-profiler-workspace")
            .min_h_0()
            .child(self.frame_toolbar(loaded, &colors, cx))
            .child(self.frame_summary(&summary, &colors));
        if !loaded {
            root = root.child(
                studio::card(&colors)
                    .min_h(px(180.))
                    .child(studio::section_title(
                        language.text(TextKey::FramePageEmpty),
                        &colors,
                    ))
                    .child(studio::supporting(
                        language.text(TextKey::FramePageCaptureUnavailable),
                        &colors,
                    )),
            );
        } else {
            root = root.child(self.frame_analysis(page.as_ref(), &colors, cx));
        }
        root.child(studio::supporting(
            language.text(TextKey::FramePageEvidenceBoundary),
            &colors,
        ))
        .into_any_element()
    }

    fn frame_toolbar(
        &mut self,
        loaded: bool,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let mut toolbar = div()
            .id("frame-profiler-toolbar")
            .flex()
            .items_center()
            .gap_1()
            .h(px(40.))
            .overflow_x_scrollbar()
            .border_b_1()
            .border_color(colors.border)
            .child(
                studio::compact_text_button(
                    "frame-import",
                    language.text(TextKey::FramePageImport),
                    !self.busy,
                    true,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.pick(cx))),
            )
            .child(studio::compact_text_button(
                "frame-device-unavailable",
                language.text(TextKey::FramePageDeviceUnavailable),
                false,
                false,
                colors,
            ))
            .child(studio::compact_text_button(
                "frame-process-unavailable",
                language.text(TextKey::FramePageProcessUnavailable),
                false,
                false,
                colors,
            ))
            .child(studio::compact_text_button(
                "frame-capture-unavailable",
                language.text(TextKey::FramePageCaptureActionUnavailable),
                false,
                false,
                colors,
            ))
            .child(studio::compact_text_button(
                "frame-perfetto-unavailable",
                language.text(TextKey::FramePagePerfettoUnavailable),
                false,
                false,
                colors,
            ))
            .child(studio::compact_text_button(
                "frame-ai-unavailable",
                language.text(TextKey::FramePageAiUnavailable),
                false,
                false,
                colors,
            ))
            .child(studio::supporting(
                language.text(TextKey::FramePageSourceGfxinfo),
                colors,
            ));
        toolbar = toolbar.child(
            studio::compact_text_button(
                "frame-window-all",
                language.text(TextKey::FramePageAllWindows),
                loaded && !self.busy,
                false,
                colors,
            )
            .selected(self.frame_window_filter.is_none())
            .on_click(cx.listener(|this, _, _, cx| this.set_frame_window(None, cx))),
        );
        for (index, window) in self.frame_windows().into_iter().enumerate() {
            let selected = self.frame_window_filter.as_ref() == Some(&window);
            toolbar = toolbar.child(
                studio::compact_text_button(
                    ("frame-window", index),
                    window.clone(),
                    loaded && !self.busy,
                    false,
                    colors,
                )
                .selected(selected)
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.set_frame_window(Some(window.clone()), cx)
                })),
            );
        }
        if self.frame_windows_truncated() {
            toolbar = toolbar.child(studio::supporting(
                language.text(TextKey::FramePageWindowsTruncated),
                colors,
            ));
        }
        toolbar
            .child(
                studio::compact_text_button(
                    "frame-all",
                    language.text(TextKey::FramePageAllFrames),
                    loaded && !self.busy,
                    false,
                    colors,
                )
                .selected(self.frame_deadline_filter.is_none())
                .on_click(cx.listener(|this, _, _, cx| this.set_frame_filter(None, cx))),
            )
            .child(
                studio::compact_text_button(
                    "frame-met",
                    language.text(TextKey::FramePageDeadlineMet),
                    loaded && !self.busy,
                    false,
                    colors,
                )
                .selected(self.frame_deadline_filter == Some(DeadlineVerdict::Met))
                .on_click(cx.listener(|this, _, _, cx| {
                    this.set_frame_filter(Some(DeadlineVerdict::Met), cx)
                })),
            )
            .child(
                studio::compact_text_button(
                    "frame-missed",
                    language.text(TextKey::FramePageDeadlineMiss),
                    loaded && !self.busy,
                    false,
                    colors,
                )
                .selected(self.frame_deadline_filter == Some(DeadlineVerdict::Missed))
                .on_click(cx.listener(|this, _, _, cx| {
                    this.set_frame_filter(Some(DeadlineVerdict::Missed), cx)
                })),
            )
            .child(
                studio::compact_text_button(
                    "frame-unknown",
                    language.text(TextKey::FramePageUnknown),
                    loaded && !self.busy,
                    false,
                    colors,
                )
                .selected(self.frame_deadline_filter == Some(DeadlineVerdict::Unknown))
                .on_click(cx.listener(|this, _, _, cx| {
                    this.set_frame_filter(Some(DeadlineVerdict::Unknown), cx)
                })),
            )
            .child(
                studio::compact_text_button(
                    "frame-cancel",
                    language.text(TextKey::ImportCancelOperation),
                    self.busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.cancel_work(cx))),
            )
            .child(
                studio::compact_text_button(
                    "frame-close",
                    language.text(TextKey::CommonClose),
                    loaded && !self.busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.close(cx))),
            )
            .into_any_element()
    }

    fn frame_summary(&self, summary: &FrameSummary, colors: &studio::StudioColors) -> AnyElement {
        let language = self.language;
        let metrics = [
            (
                TextKey::FramePageFrames,
                summary.total_frames.to_string(),
                UiText::format(
                    TextKey::FramePageClassified,
                    [("count", summary.classified_frames.to_string().into())],
                )
                .render(language),
                colors.accent,
            ),
            (
                TextKey::FramePageDeadlineMisses,
                summary.deadline_misses.to_string(),
                percent(summary.deadline_miss_rate),
                if summary.deadline_misses > 0 {
                    colors.error
                } else {
                    colors.success
                },
            ),
            (
                TextKey::FramePageP50,
                ns(summary.p50_duration_ns),
                language.text(TextKey::FramePageGfxinfoSupport).into(),
                colors.info,
            ),
            (
                TextKey::FramePageP95,
                ns(summary.p95_duration_ns),
                language.text(TextKey::FramePageGfxinfoSupport).into(),
                colors.warning,
            ),
            (
                TextKey::FramePageWorst,
                ns(summary.worst_duration_ns),
                UiText::format(
                    TextKey::FramePageP99Support,
                    [("value", ns(summary.p99_duration_ns).into())],
                )
                .render(language),
                colors.error,
            ),
            (
                TextKey::FramePagePlatformJankRate,
                percent(summary.platform_jank_rate),
                UiText::format(
                    TextKey::FramePagePlatformClassified,
                    [(
                        "count",
                        summary.platform_classified_frames.to_string().into(),
                    )],
                )
                .render(language),
                colors.warning,
            ),
            (
                TextKey::FramePageRefreshRate,
                "—".into(),
                language.text(TextKey::FramePageRefreshUnavailable).into(),
                colors.secondary_text,
            ),
        ];
        div()
            .id("frame-summary")
            .flex()
            .gap_1()
            .overflow_x_scrollbar()
            .children(metrics.into_iter().enumerate().map(
                |(index, (key, value, support, tone))| {
                    studio::kpi_tile(
                        ("frame-metric", index),
                        language.text(key),
                        value,
                        support,
                        tone,
                        colors,
                    )
                    .min_w(px(130.))
                    .flex_1()
                },
            ))
            .into_any_element()
    }

    fn frame_analysis(
        &mut self,
        page: Option<&FramePage>,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let rows = page.map(|page| page.rows.clone()).unwrap_or_default();
        let analysis = self.frame_analysis_page();
        let selected = self
            .selected_frame_id
            .and_then(|id| rows.iter().find(|frame| frame.id == id).cloned())
            .or_else(|| rows.first().cloned());
        let timeline = self.frame_timeline(page, analysis.as_ref(), selected.as_ref(), colors, cx);
        let clusters = self.frame_clusters(analysis.as_ref(), colors, cx);
        let problems = self.frame_problem_frames(analysis.as_ref(), colors, cx);
        let details = self.frame_detail(selected.as_ref(), &rows, colors, cx);
        let mut card = studio::card(colors)
            .id("frame-analysis-card")
            .min_h_0()
            .flex_1()
            .p_0()
            .gap_0()
            .child(timeline)
            .child(div().h(px(7.)).bg(colors.border))
            .child(
                div()
                    .id("frame-workspace-body")
                    .flex()
                    .min_h(px(180.))
                    .flex_1()
                    .overflow_x_scrollbar()
                    .child(clusters)
                    .child(div().w(px(7.)).bg(colors.border))
                    .child(problems)
                    .child(div().w(px(7.)).bg(colors.border))
                    .child(details),
            );
        if let Some(Session::Frame(session)) = self.session.as_ref() {
            if !session.warnings().is_empty() {
                card = card.child(div().h(px(7.)).bg(colors.border)).child(
                    studio::card(colors)
                        .id("frame-context-panel")
                        .child(studio::section_title(
                            language.text(TextKey::FramePageSource),
                            colors,
                        ))
                        .children(
                            session
                                .warnings()
                                .iter()
                                .take(16)
                                .cloned()
                                .map(|warning| studio::supporting(warning, colors)),
                        ),
                );
            }
        }
        card.into_any_element()
    }

    fn frame_timeline(
        &mut self,
        page: Option<&FramePage>,
        analysis: Option<&FrameAnalysisPage>,
        selected: Option<&AnalyzedFrame>,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let mut panel = div()
            .id("frame-timeline-panel")
            .flex()
            .flex_col()
            .gap_1()
            .p_2()
            .h(px(235.));
        let total = page.map_or(0, |page| page.total);
        let offset = page.map_or(0, |page| page.offset);
        let end = page.map_or(0, |page| page.offset.saturating_add(page.rows.len()));
        let range = UiText::format(
            TextKey::FramePageTimelineRange,
            [
                (
                    "start",
                    (offset.saturating_add(1).min(end)).to_string().into(),
                ),
                ("end", end.to_string().into()),
                ("total", total.to_string().into()),
            ],
        )
        .render(language);
        panel = panel.child(
            div()
                .flex()
                .items_center()
                .gap_1()
                .child(div().flex_1().child(studio::section_title(
                    language.text(TextKey::FramePageTimeline),
                    colors,
                )))
                .child(studio::supporting(range, colors))
                .child(
                    studio::compact_text_button(
                        "frame-timeline-prev",
                        language.text(TextKey::CommonPrevious),
                        offset > 0,
                        false,
                        colors,
                    )
                    .on_click(
                        cx.listener(move |this, _, _, cx| this.move_frame_range(false, total, cx)),
                    ),
                )
                .child(
                    studio::compact_text_button(
                        "frame-timeline-next",
                        language.text(TextKey::CommonNext),
                        end < total,
                        false,
                        colors,
                    )
                    .on_click(
                        cx.listener(move |this, _, _, cx| this.move_frame_range(true, total, cx)),
                    ),
                )
                .child(
                    studio::compact_text_button(
                        "frame-zoom-in",
                        language.text(TextKey::FramePageZoomIn),
                        self.frame_timeline_count > MIN_VISIBLE_FRAMES,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.zoom_frame_range(true, cx))),
                )
                .child(
                    studio::compact_text_button(
                        "frame-zoom-out",
                        language.text(TextKey::FramePageZoomOut),
                        self.frame_timeline_count < MAX_VISIBLE_FRAMES,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.zoom_frame_range(false, cx))),
                ),
        );
        let Some(page) = page else {
            return panel
                .child(studio::supporting(
                    language.text(TextKey::FramePageTimelineUnavailable),
                    colors,
                ))
                .into_any_element();
        };
        let max = page
            .rows
            .iter()
            .filter_map(|frame| frame.duration_ns)
            .max()
            .unwrap_or(1);
        if page.rows.is_empty() {
            return panel
                .child(studio::supporting(
                    language.text(TextKey::FramePageTimelineUnavailable),
                    colors,
                ))
                .into_any_element();
        }
        const CANVAS_WIDTH: f32 = 1200.0;
        const CANVAS_HEIGHT: f32 = 125.0;
        let frames = page
            .rows
            .iter()
            .map(|frame| (frame.id, frame.duration_ns, frame.deadline))
            .collect::<Vec<_>>();
        let axis_ids = (0..5)
            .filter_map(|tick| {
                let index = tick * frames.len().saturating_sub(1) / 4;
                frames.get(index).map(|frame| frame.0)
            })
            .collect::<Vec<_>>();
        let hit_frames = frames.clone();
        let paint_frames = frames.clone();
        let cluster_starts = analysis
            .into_iter()
            .flat_map(|analysis| analysis.clusters.iter())
            .map(|cluster| cluster.first_frame_id)
            .collect::<std::collections::BTreeSet<_>>();
        let selected_id = selected.map(|frame| frame.id);
        let colors = *colors;
        let origin = Arc::new(Mutex::new(Point::default()));
        let paint_origin = origin.clone();
        let down_origin = origin.clone();
        let move_origin = origin;
        let hovered = self.frame_hovered_id.and_then(|id| {
            page.rows.iter().find(|frame| frame.id == id).map(|frame| {
                format!(
                    "{} #{} · {} · {}",
                    language.text(TextKey::FramePageFrames),
                    frame.id,
                    ns(frame.duration_ns),
                    verdict(frame.deadline, language)
                )
            })
        });
        panel
            .children(hovered.map(|text| studio::supporting(text, &colors)))
            .child(
                div()
                    .flex()
                    .child(
                        div()
                            .id("frame-timeline-duration-axis")
                            .w(px(64.))
                            .h(px(CANVAS_HEIGHT))
                            .flex()
                            .flex_col()
                            .justify_between()
                            .child(studio::supporting(ns(Some(max)), &colors))
                            .child(studio::supporting(ns(Some(max / 2)), &colors))
                            .child(studio::supporting("0 ms", &colors)),
                    )
                    .child(
                        div()
                            .id("frame-timeline-scroll")
                            .flex_1()
                            .overflow_x_scrollbar()
                            .child(
                                div()
                                    .id("frame-timeline-bars")
                                    .relative()
                                    .w(px(CANVAS_WIDTH))
                                    .h(px(CANVAS_HEIGHT))
                                    .on_mouse_down(
                                        MouseButton::Left,
                                        cx.listener(move |this, event: &MouseDownEvent, _, cx| {
                                            let Ok(origin) = down_origin.lock() else {
                                                return;
                                            };
                                            let x = f32::from(event.position.x - origin.x);
                                            let index = ((x / CANVAS_WIDTH)
                                                * hit_frames.len() as f32)
                                                .floor()
                                                as usize;
                                            if let Some((id, _, _)) = hit_frames.get(index) {
                                                this.selected_frame_id = Some(*id);
                                                cx.notify();
                                            }
                                        }),
                                    )
                                    .on_mouse_move(cx.listener(
                                        move |this, event: &MouseMoveEvent, _, cx| {
                                            let Ok(origin) = move_origin.lock() else {
                                                return;
                                            };
                                            let x = f32::from(event.position.x - origin.x);
                                            let index = ((x / CANVAS_WIDTH) * frames.len() as f32)
                                                .floor()
                                                as usize;
                                            let id = frames.get(index).map(|frame| frame.0);
                                            if this.frame_hovered_id != id {
                                                this.frame_hovered_id = id;
                                                cx.notify();
                                            }
                                        },
                                    ))
                                    .on_mouse_exit(cx.listener(|this, _, _, cx| {
                                        if this.frame_hovered_id.take().is_some() {
                                            cx.notify();
                                        }
                                    }))
                                    .child(canvas(
                                        move |bounds, _, _| {
                                            if let Ok(mut origin) = paint_origin.lock() {
                                                *origin = bounds.origin;
                                            }
                                        },
                                        move |bounds, _, window, _| {
                                            let bar_width =
                                                CANVAS_WIDTH / paint_frames.len() as f32;
                                            for (index, (id, duration, deadline)) in
                                                paint_frames.iter().enumerate()
                                            {
                                                let height = duration.map_or(2.0, |duration| {
                                                    (duration as f32 / max as f32 * CANVAS_HEIGHT)
                                                        .max(2.0)
                                                });
                                                let color = if selected_id == Some(*id) {
                                                    colors.accent
                                                } else {
                                                    match deadline {
                                                        DeadlineVerdict::Met => colors.success,
                                                        DeadlineVerdict::Missed => colors.error,
                                                        DeadlineVerdict::Unknown => colors.warning,
                                                    }
                                                };
                                                window.paint_quad(quad(
                                                    Bounds {
                                                        origin: point(
                                                            bounds.origin.x
                                                                + px(index as f32 * bar_width),
                                                            bounds.origin.y
                                                                + px(CANVAS_HEIGHT - height),
                                                        ),
                                                        size: size(
                                                            px((bar_width - 1.0).max(1.0)),
                                                            px(height),
                                                        ),
                                                    },
                                                    px(0.),
                                                    color,
                                                    px(0.),
                                                    transparent_black(),
                                                    Default::default(),
                                                ));
                                                if cluster_starts.contains(id) {
                                                    window.paint_quad(quad(
                                                        Bounds {
                                                            origin: point(
                                                                bounds.origin.x
                                                                    + px(index as f32 * bar_width),
                                                                bounds.origin.y,
                                                            ),
                                                            size: size(
                                                                px((bar_width - 1.0).max(2.0)),
                                                                px(4.0),
                                                            ),
                                                        },
                                                        px(0.),
                                                        colors.warning,
                                                        px(0.),
                                                        transparent_black(),
                                                        Default::default(),
                                                    ));
                                                }
                                            }
                                        },
                                    )),
                            ),
                    ),
            )
            .child(
                div()
                    .id("frame-timeline-frame-axis")
                    .flex()
                    .justify_between()
                    .children(axis_ids.into_iter().map(|id| {
                        studio::supporting(
                            format!("{} #{id}", language.text(TextKey::FramePageFrames)),
                            &colors,
                        )
                    })),
            )
            .child(
                div()
                    .flex()
                    .gap_2()
                    .child(studio::supporting(
                        format!("● {}", verdict(DeadlineVerdict::Met, language)),
                        &colors,
                    ))
                    .child(studio::supporting(
                        format!("● {}", verdict(DeadlineVerdict::Missed, language)),
                        &colors,
                    ))
                    .child(studio::supporting(
                        format!("● {}", verdict(DeadlineVerdict::Unknown, language)),
                        &colors,
                    )),
            )
            .into_any_element()
    }

    fn frame_clusters(
        &mut self,
        analysis: Option<&FrameAnalysisPage>,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let mut panel = studio::card(colors)
            .id("frame-clusters-panel")
            .w(px(240.))
            .flex_shrink_0()
            .min_h_0()
            .child(studio::section_title(
                language.text(TextKey::FramePageClusters),
                colors,
            ));
        let Some(analysis) = analysis else {
            return panel
                .child(studio::supporting(
                    language.text(TextKey::FramePageTimelineUnavailable),
                    colors,
                ))
                .into_any_element();
        };
        if analysis.clusters.is_empty() {
            return panel
                .child(studio::supporting(
                    language.text(TextKey::FramePageNoClusters),
                    colors,
                ))
                .into_any_element();
        }
        panel = panel.child(studio::supporting(
            UiText::format(
                TextKey::FramePageClusterCount,
                [("count", analysis.cluster_total.to_string().into())],
            )
            .render(language),
            colors,
        ));
        if analysis.clusters_truncated {
            panel = panel.child(studio::supporting(
                language.text(TextKey::FramePageClustersTruncated),
                colors,
            ));
        }
        panel
            .child(
                div()
                    .flex()
                    .flex_col()
                    .min_h_0()
                    .overflow_y_scrollbar()
                    .children(
                        analysis
                            .clusters
                            .iter()
                            .enumerate()
                            .map(|(index, cluster)| {
                                let id = cluster.first_frame_id;
                                let filtered_index = cluster.first_filtered_index;
                                Button::new(("frame-cluster", index))
                                    .ghost()
                                    .w_full()
                                    .label(format!(
                                        "#{}–#{} · {} {} · {}",
                                        cluster.first_frame_id,
                                        cluster.last_frame_id,
                                        cluster.miss_count,
                                        language.text(TextKey::FramePageDeadlineMisses),
                                        ns(cluster.worst_duration_ns)
                                    ))
                                    .on_click(cx.listener(move |this, _, _, cx| {
                                        this.select_frame_at(id, filtered_index, cx)
                                    }))
                            }),
                    ),
            )
            .into_any_element()
    }

    fn frame_problem_frames(
        &mut self,
        analysis: Option<&FrameAnalysisPage>,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let mut panel = studio::card(colors)
            .id("frame-problems-panel")
            .min_w(px(360.))
            .flex_1()
            .min_h_0()
            .child(studio::section_title(
                language.text(TextKey::FramePageProblemFrames),
                colors,
            ));
        let Some(analysis) = analysis else {
            return panel
                .child(studio::supporting(
                    language.text(TextKey::FramePageTimelineUnavailable),
                    colors,
                ))
                .into_any_element();
        };
        if analysis.problems.is_empty() {
            return panel
                .child(studio::supporting(
                    language.text(TextKey::FramePageNoProblemFrames),
                    colors,
                ))
                .into_any_element();
        }
        if analysis.problem_total > analysis.problems.len() {
            panel = panel.child(studio::supporting(
                UiText::format(
                    TextKey::FramePageProblemsBounded,
                    [
                        ("shown", analysis.problems.len().to_string().into()),
                        ("total", analysis.problem_total.to_string().into()),
                    ],
                )
                .render(language),
                colors,
            ));
        }
        panel = panel.child(
            div()
                .flex()
                .gap_2()
                .child(div().w(px(58.)).child(crate::studio::selectable(
                    language.text(TextKey::FramePageFrameId),
                )))
                .child(div().w(px(100.)).child(crate::studio::selectable(
                    language.text(TextKey::FramePageDuration),
                )))
                .child(div().flex_1().child(crate::studio::selectable(
                    language.text(TextKey::FramePageDeadline),
                )))
                .child(div().flex_1().child(crate::studio::selectable(
                    language.text(TextKey::FramePageWindow),
                )))
                .child(div().flex_1().child(crate::studio::selectable(
                    language.text(TextKey::FramePageLargestStage),
                ))),
        );
        panel
            .child(
                div()
                    .flex()
                    .flex_col()
                    .min_h_0()
                    .overflow_y_scrollbar()
                    .children(
                        analysis
                            .problems
                            .iter()
                            .enumerate()
                            .map(|(index, problem)| {
                                let frame = &problem.frame;
                                let id = frame.id;
                                let filtered_index = problem.filtered_index;
                                Button::new(("frame-problem", index))
                                    .ghost()
                                    .selected(self.selected_frame_id == Some(id))
                                    .w_full()
                                    .label(format!(
                                        "#{}  {}  {}  {}  {}",
                                        id,
                                        ns(frame.duration_ns),
                                        verdict(frame.deadline, language),
                                        frame.window.as_deref().unwrap_or("—"),
                                        largest_stage(&frame, language),
                                    ))
                                    .on_click(cx.listener(move |this, _, _, cx| {
                                        this.select_frame_at(id, filtered_index, cx)
                                    }))
                            }),
                    ),
            )
            .into_any_element()
    }

    fn frame_detail(
        &mut self,
        selected: Option<&AnalyzedFrame>,
        rows: &[AnalyzedFrame],
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let mut panel = studio::card(colors)
            .id("frame-detail-panel")
            .w(px(350.))
            .flex_shrink_0()
            .min_h_0()
            .overflow_y_scrollbar()
            .child(studio::section_title(
                language.text(TextKey::FramePageDetail),
                colors,
            ));
        let Some(frame) = selected else {
            return panel
                .child(studio::supporting(
                    language.text(TextKey::FramePageTimelineUnavailable),
                    colors,
                ))
                .into_any_element();
        };
        let current = rows.iter().position(|candidate| candidate.id == frame.id);
        let page = self.frame_page();
        let previous = current
            .and_then(|index| index.checked_sub(1))
            .and_then(|index| rows.get(index))
            .map(|frame| frame.id)
            .or_else(|| {
                page.as_ref()
                    .filter(|page| page.offset > 0)
                    .map(|_| frame.id)
            });
        let next = current
            .and_then(|index| rows.get(index + 1))
            .map(|frame| frame.id)
            .or_else(|| {
                page.as_ref()
                    .filter(|page| page.offset.saturating_add(page.rows.len()) < page.total)
                    .map(|_| frame.id)
            });
        let mut evidence = format!(
            "Source: gfxinfo FrameStats\nFrame ID: {}\nWindow: {}\nIntended VSync ns: {}\nActual VSync ns: {}\nCompleted ns: {}\nPresented ns: {}\nFrameTimeline VSync ID: {}\nFlags: {}\nDuration: {}\nExpected budget: {}\nBudget source: {}\nDeadline: {}\nMissed VSync: {}\nPlatform jank: {}",
            frame.id,
            frame.window.as_deref().unwrap_or("—"),
            integer(frame.intended_vsync_ns),
            integer(frame.actual_vsync_ns),
            integer(frame.completed_ns),
            integer(frame.present_ns),
            integer(frame.vsync_id),
            frame.flags,
            ns(frame.duration_ns),
            ns(known_budget(frame)),
            budget_source(frame.budget_source, language),
            verdict(frame.deadline, language),
            integer(frame.missed_vsync_count),
            frame
                .platform_jank
                .map_or("—".into(), |value| value.to_string()),
        );
        evidence.push_str("\nStage breakdown:");
        for (name, value, _) in stage_values(&frame.stages) {
            evidence.push_str(&format!("\n{name}: {}", ns(value)));
        }
        evidence.push_str("\nSource columns:");
        for (name, value) in &frame.columns {
            evidence.push_str(&format!("\n{name}: {value}"));
        }
        panel = panel
            .child(
                div()
                    .flex()
                    .gap_1()
                    .child(
                        studio::compact_text_button(
                            "frame-detail-prev",
                            language.text(TextKey::FramePagePreviousFrame),
                            previous.is_some(),
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.move_selected_frame(true, cx))),
                    )
                    .child(
                        studio::compact_text_button(
                            "frame-detail-next",
                            language.text(TextKey::FramePageNextFrame),
                            next.is_some(),
                            false,
                            colors,
                        )
                        .on_click(
                            cx.listener(|this, _, _, cx| this.move_selected_frame(false, cx)),
                        ),
                    ),
            )
            .child(self.frame_detail_row(
                TextKey::FramePageSource,
                language.text(TextKey::FramePageSourceGfxinfo),
                colors,
            ))
            .child(self.frame_detail_row(TextKey::FramePageFrameId, frame.id.to_string(), colors))
            .child(self.frame_detail_row(
                TextKey::FramePageWindow,
                frame.window.clone().unwrap_or_else(|| "—".into()),
                colors,
            ))
            .child(self.frame_detail_row(
                TextKey::FramePageIntendedVsync,
                integer(frame.intended_vsync_ns),
                colors,
            ))
            .child(self.frame_detail_row(
                TextKey::FramePageActualVsync,
                integer(frame.actual_vsync_ns),
                colors,
            ))
            .child(self.frame_detail_row(
                TextKey::FramePageCompleted,
                integer(frame.completed_ns),
                colors,
            ))
            .child(self.frame_detail_row(
                TextKey::FramePagePresented,
                integer(frame.present_ns),
                colors,
            ))
            .child(self.frame_detail_row(
                TextKey::FramePageVsyncId,
                integer(frame.vsync_id),
                colors,
            ))
            .child(self.frame_detail_row(TextKey::FramePageFlags, frame.flags.to_string(), colors))
            .child(self.frame_detail_row(TextKey::FramePageDuration, ns(frame.duration_ns), colors))
            .child(self.frame_detail_row(TextKey::FramePageBudget, ns(known_budget(frame)), colors))
            .child(self.frame_detail_row(
                TextKey::FramePageBudgetSource,
                budget_source(frame.budget_source, language),
                colors,
            ))
            .child(self.frame_detail_row(
                TextKey::FramePageDeadline,
                verdict(frame.deadline, language),
                colors,
            ))
            .child(self.frame_detail_row(
                TextKey::FramePageMissedVsync,
                integer(frame.missed_vsync_count),
                colors,
            ))
            .child(
                self.frame_detail_row(
                    TextKey::FramePagePlatformJank,
                    frame
                        .platform_jank
                        .map_or("—".into(), |value| value.to_string()),
                    colors,
                ),
            )
            .child(self.frame_detail_row(TextKey::FramePageJankTypes, "—", colors))
            .child(self.frame_detail_row(
                TextKey::FramePageLargestStage,
                largest_stage(frame, language),
                colors,
            ))
            .child(studio::divider(colors))
            .child(studio::section_title(
                language.text(TextKey::FramePageStageBreakdown),
                colors,
            ));
        let max_stage = stage_values(&frame.stages)
            .into_iter()
            .filter_map(|(_, value, _)| value)
            .max()
            .unwrap_or(1);
        for (_, value, key) in stage_values(&frame.stages) {
            let width = value.map_or(0.0, |value| value as f32 / max_stage as f32 * 120.0);
            panel = panel.child(
                div()
                    .flex()
                    .items_center()
                    .gap_2()
                    .child(
                        div()
                            .w(px(120.))
                            .child(crate::studio::selectable(language.text(key))),
                    )
                    .child(
                        div()
                            .w(px(120.))
                            .h(px(7.))
                            .bg(colors.border)
                            .child(div().w(px(width)).h(px(7.)).bg(colors.accent)),
                    )
                    .child(studio::supporting(ns(value), colors)),
            );
        }
        panel = panel
            .child(studio::divider(colors))
            .child(studio::section_title(
                language.text(TextKey::FramePageStateLabels),
                colors,
            ));
        for (name, value) in frame.columns.iter().take(64) {
            panel = panel.child(
                div()
                    .flex()
                    .gap_2()
                    .child(
                        div()
                            .w(px(135.))
                            .child(crate::studio::selectable(name.clone())),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .child(crate::studio::selectable(value.clone())),
                    ),
            );
        }
        panel
            .child(studio::divider(colors))
            .child(
                studio::compact_text_button(
                    "frame-copy-evidence",
                    language.text(TextKey::FramePageCopyEvidence),
                    true,
                    false,
                    colors,
                )
                .on_click(cx.listener(move |_, _, _, cx| {
                    cx.write_to_clipboard(ClipboardItem::new_string(evidence.clone()))
                })),
            )
            .child(studio::supporting(
                language.text(TextKey::FramePageOpenLayoutUnavailable),
                colors,
            ))
            .child(studio::supporting(
                language.text(TextKey::FramePageOpenTraceUnavailable),
                colors,
            ))
            .child(studio::section_title(
                language.text(TextKey::FramePageLayoutPreview),
                colors,
            ))
            .child(studio::supporting(
                language.text(TextKey::FramePageLayoutPreviewUnavailable),
                colors,
            ))
            .into_any_element()
    }

    fn frame_detail_row(
        &self,
        key: TextKey,
        value: impl Into<SharedString>,
        colors: &studio::StudioColors,
    ) -> AnyElement {
        div()
            .flex()
            .gap_2()
            .py(px(3.))
            .border_b_1()
            .border_color(colors.border)
            .child(
                div()
                    .w(px(135.))
                    .text_size(px(typography::SUPPORTING))
                    .text_color(colors.secondary_text)
                    .child(self.language.text(key)),
            )
            .child(
                div()
                    .flex_1()
                    .min_w_0()
                    .text_size(px(typography::SUPPORTING))
                    .child(value.into()),
            )
            .into_any_element()
    }
}
