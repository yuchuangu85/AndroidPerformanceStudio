//! GPU Analysis Hub: artifact identities and explicit external-tool operations.
//! File metadata is never promoted to captured graphics measurements.
mod panels;
use super::*;
use aps_gpu::{
    AgiCapability, AgiLaunchMode, AgiToolchain, GpuArtifactIndex, GpuDeviceContext,
    GpuDeviceContextReader, LocationStatus,
};
use gpui_kit::component::{
    input::{Input, InputEvent, InputState},
    menu::{DropdownMenu, PopupMenuItem},
};

const WIDE_WIDTH: f32 = 1100.;
const SUMMARY_WIDTH: f32 = 900.;
const ARTIFACT_RAIL: f32 = 264.;
const INSIGHT_RAIL: f32 = 300.;
const COMPACT_RAIL_HEIGHT: f32 = 236.;
const GPU_PAGE: usize = 100;

#[derive(Clone)]
pub(super) struct GpuRecord {
    pub artifact: Arc<GpuArtifact>,
    pub status: Option<LocationStatus>,
}
impl GpuRecord {
    pub fn id(&self) -> String {
        self.artifact.sha256.to_hex()
    }
    pub fn title(&self) -> String {
        self.artifact
            .locations()
            .first()
            .and_then(|path| path.file_name())
            .map_or_else(|| self.id(), |name| name.to_string_lossy().into_owned())
    }
}
#[derive(Default)]
pub(super) struct GpuUi {
    pub artifacts: Vec<GpuRecord>,
    pub selected: Option<String>,
    pub capability: Option<AgiCapability>,
    pub device: Option<GpuDeviceContext>,
    pub device_busy: bool,
    pub device_error: Option<String>,
    pub artifact_offset: usize,
    pub rail_offset: usize,
    index: GpuArtifactIndex,
    filter: String,
    search: Option<Entity<InputState>>,
    search_subscription: Option<Subscription>,
    language: Option<Language>,
    device_serial: Option<String>,
    device_cancel: CancellationToken,
    device_generation: u64,
}
impl GpuUi {
    pub fn selected_record(&self) -> Option<&GpuRecord> {
        self.selected
            .as_deref()
            .and_then(|id| self.artifacts.iter().find(|record| record.id() == id))
    }
}
impl Drop for GpuUi {
    fn drop(&mut self) {
        self.device_cancel.cancel();
    }
}

#[derive(Clone)]
pub(crate) struct OpenGpuTrace(pub Option<PathBuf>);
impl EventEmitter<OpenGpuTrace> for ImportWorkspace {}

fn kind_key(kind: ArtifactKind) -> TextKey {
    match kind {
        ArtifactKind::AgiSystemProfile => TextKey::GpuArtifactKindAgiSystemProfile,
        ArtifactKind::AgiFrameProfile => TextKey::GpuArtifactKindAgiFrameProfile,
        ArtifactKind::PerfettoTrace => TextKey::GpuArtifactKindPerfettoTrace,
        ArtifactKind::Screenshot => TextKey::GpuArtifactKindScreenshot,
        ArtifactKind::ExternalReport => TextKey::GpuArtifactKindExternalReport,
        ArtifactKind::Unknown => TextKey::GpuArtifactKindUnknown,
    }
}
fn status_key(status: Option<LocationStatus>) -> TextKey {
    match status {
        None => TextKey::GpuUnverified,
        Some(LocationStatus::Available) => TextKey::GpuArtifactAvailable,
        Some(LocationStatus::Missing) => TextKey::GpuArtifactMissing,
        Some(LocationStatus::SizeChanged) => TextKey::GpuArtifactSizeChanged,
        Some(LocationStatus::ContentChanged) => TextKey::GpuArtifactContentChanged,
        Some(LocationStatus::Unreadable) => TextKey::GpuArtifactUnreadable,
    }
}
fn size_text(bytes: u64) -> String {
    if bytes >= 1024 * 1024 {
        format!("{:.1} MiB", bytes as f64 / (1024. * 1024.))
    } else if bytes >= 1024 {
        format!("{:.1} KiB", bytes as f64 / 1024.)
    } else {
        format!("{bytes} B")
    }
}
fn gpu_note(text: impl Into<SharedString>, colors: &studio::StudioColors) -> Div {
    studio::supporting(text, colors)
        .line_height(px(16.))
        .flex_shrink_0()
}
fn artifact_matches(record: &GpuRecord, query: &str) -> bool {
    let query = query.trim().to_lowercase();
    query.is_empty()
        || record.id().contains(&query)
        || format!("{:?}", record.artifact.kind)
            .to_lowercase()
            .contains(&query)
        || record
            .artifact
            .locations()
            .iter()
            .any(|path| path.to_string_lossy().to_lowercase().contains(&query))
}
fn launch_supported(capability: Option<&AgiCapability>) -> bool {
    capability.is_some_and(|capability| capability.launch_mode != AgiLaunchMode::Unsupported)
}
fn can_open(record: Option<&GpuRecord>, capability: Option<&AgiCapability>) -> bool {
    record.is_some_and(|record| {
        record.status == Some(LocationStatus::Available)
            && match record.artifact.open_route {
                OpenRoute::Agi => launch_supported(capability),
                OpenRoute::Perfetto | OpenRoute::Desktop => true,
                OpenRoute::None => false,
            }
    })
}
fn open_key(record: Option<&GpuRecord>, capability: Option<&AgiCapability>) -> TextKey {
    match record.map(|record| record.artifact.open_route) {
        Some(OpenRoute::Agi) if launch_supported(capability) => TextKey::GpuLaunchAgi,
        Some(OpenRoute::Perfetto) => TextKey::GpuOpenInPerfetto,
        Some(OpenRoute::Desktop) => TextKey::GpuOpenWithDesktop,
        _ => TextKey::GpuUnavailable,
    }
}

impl ImportWorkspace {
    pub(super) fn render_gpu_hub(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let search = self.gpu_search(window, cx);
        let entity = cx.entity();
        container_query(move |size, _, cx| {
            entity.update(cx, |this, cx| this.gpu_content(size, &search, cx))
        })
        .size_full()
        .into_any_element()
    }
    fn gpu_search(&mut self, window: &mut Window, cx: &mut Context<Self>) -> Entity<InputState> {
        if self.gpu.search.is_none() {
            let language = self.language;
            let input = cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::GpuArtifactSearchPlaceholder))
                    .validate(|value, _| value.len() <= 256)
            });
            self.gpu.search_subscription = Some(cx.subscribe(&input, |this, input, event, cx| {
                if matches!(event, InputEvent::Change) {
                    this.gpu.filter = input.read(cx).value().to_string();
                    this.gpu.rail_offset = 0;
                    cx.notify();
                }
            }));
            self.gpu.search = Some(input);
        }
        let input = self.gpu.search.clone().expect("GPU search initialized");
        if self.gpu.language != Some(self.language) {
            input.update(cx, |input, cx| {
                input.set_placeholder(
                    self.language.text(TextKey::GpuArtifactSearchPlaceholder),
                    window,
                    cx,
                )
            });
            self.gpu.language = Some(self.language);
        }
        input
    }
    fn gpu_content(
        &mut self,
        size: Size<Pixels>,
        search: &Entity<InputState>,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = studio::StudioColors::from_theme(cx);
        let width = (f32::from(size.width) - 8.).max(0.);
        let wide = width >= WIDE_WIDTH;
        let rail = self.gpu_artifact_rail(search, &colors, cx);
        let center = self.gpu_center(wide, &colors, cx);
        let insights = self.gpu_insights(&colors, cx);
        let summary = self.gpu_summary(width, &colors);
        let mut body = studio::page(&colors).min_h_0().min_w_0();
        if wide {
            // Short windows still get a minimum usable pane viewport, owned by
            // this scroller instead of collapsing panels underneath the summary.
            let height = (f32::from(size.height) - 40. - 8.).max(440.);
            body = body.h(px(height)).flex_shrink_0().child(summary).child(
                div()
                    .flex()
                    .flex_1()
                    .min_h_0()
                    .gap(px(4.))
                    .child(
                        div()
                            .w(px(ARTIFACT_RAIL))
                            .h_full()
                            .flex_shrink_0()
                            .child(rail),
                    )
                    .child(div().flex_1().min_w_0().h_full().child(center))
                    .child(
                        div()
                            .id("gpu-insights-scroll")
                            .w(px(INSIGHT_RAIL))
                            .h_full()
                            .flex_shrink_0()
                            .overflow_y_scroll()
                            .child(insights),
                    ),
            );
        } else {
            body = body
                .h_auto()
                .flex_shrink_0()
                .child(summary)
                .child(div().h(px(COMPACT_RAIL_HEIGHT)).flex_shrink_0().child(rail))
                .child(center)
                .child(insights);
        }
        div()
            .id("gpu-analysis-hub")
            .size_full()
            .min_h_0()
            .min_w_0()
            .flex()
            .flex_col()
            .bg(colors.workspace)
            .text_color(colors.text)
            .text_size(px(studio::FONT_BODY))
            .child(self.gpu_toolbar(&colors, cx))
            .when(
                self.busy || self.error.is_some() || self.notice.is_some(),
                |root| {
                    let (text, tone) = if let Some(error) = &self.error {
                        (error.render(self.language), colors.error)
                    } else if self.busy {
                        (
                            self.language.text(TextKey::GpuWorking).to_owned(),
                            colors.info,
                        )
                    } else {
                        (
                            self.notice
                                .as_ref()
                                .map(|notice| notice.render(self.language))
                                .unwrap_or_default(),
                            colors.secondary_text,
                        )
                    };
                    root.child(
                        div()
                            .px(px(8.))
                            .py(px(4.))
                            .flex_shrink_0()
                            .child(gpu_note(text, &colors).text_color(tone)),
                    )
                },
            )
            .child(
                div()
                    .id("gpu-workspace-scroll")
                    .flex_1()
                    .min_h_0()
                    .min_w_0()
                    .overflow_y_scroll()
                    .child(body),
            )
            .into_any_element()
    }
    fn gpu_summary(&self, width: f32, colors: &studio::StudioColors) -> AnyElement {
        let language = self.language;
        let total = self.gpu.artifacts.len();
        let available = self
            .gpu
            .artifacts
            .iter()
            .filter(|record| record.status == Some(LocationStatus::Available))
            .count();
        let attention = self
            .gpu
            .artifacts
            .iter()
            .filter(|record| {
                record
                    .status
                    .is_some_and(|status| status != LocationStatus::Available)
            })
            .count();
        let unverified = total - available - attention;
        let kind_counts = [
            ArtifactKind::AgiSystemProfile,
            ArtifactKind::AgiFrameProfile,
            ArtifactKind::PerfettoTrace,
            ArtifactKind::Screenshot,
            ArtifactKind::ExternalReport,
            ArtifactKind::Unknown,
        ]
        .into_iter()
        .filter_map(|kind| {
            let count = self
                .gpu
                .artifacts
                .iter()
                .filter(|record| record.artifact.kind == kind)
                .count();
            (count > 0).then(|| format!("{} {count}", language.text(kind_key(kind))))
        })
        .collect::<Vec<_>>()
        .join(" · ");
        let capability = self.gpu.capability.as_ref();
        let cards = [
            (
                TextKey::GpuSummaryIndexedArtifacts,
                total.to_string(),
                if kind_counts.is_empty() {
                    language.text(TextKey::GpuNoGpuArtifacts).into()
                } else {
                    kind_counts
                },
                colors.accent,
            ),
            (
                TextKey::GpuSummaryAvailableArtifacts,
                available.to_string(),
                language.format(
                    TextKey::GpuSummaryAvailableSupport,
                    &[("count", &available), ("total", &total)],
                ),
                colors.success,
            ),
            (
                TextKey::GpuSummaryAttentionArtifacts,
                attention.to_string(),
                if attention > 0 {
                    language.format(
                        TextKey::GpuSummaryAttentionSupport,
                        &[("count", &attention), ("total", &total)],
                    )
                } else if unverified > 0 {
                    language.format(
                        TextKey::GpuSummaryAttentionUnverified,
                        &[("count", &unverified)],
                    )
                } else {
                    language
                        .text(if total == 0 {
                            TextKey::GpuNoGpuArtifacts
                        } else {
                            TextKey::GpuSummaryAttentionNone
                        })
                        .into()
                },
                if attention > 0 {
                    colors.warning
                } else {
                    colors.secondary_text
                },
            ),
            (
                TextKey::GpuSummaryCapability,
                capability
                    .and_then(|value| value.version.clone())
                    .unwrap_or_else(|| {
                        language
                            .text(if capability.is_some() {
                                TextKey::GpuUnknownVersion
                            } else {
                                TextKey::GpuAgiIsNotConfigured
                            })
                            .into()
                    }),
                capability.map_or_else(
                    || language.text(TextKey::GpuUnavailable).into(),
                    |value| {
                        language.format(
                            TextKey::GpuCapabilitySummary,
                            &[
                                (
                                    "mode",
                                    &language.text(match value.launch_mode {
                                        AgiLaunchMode::VerifiedCli => {
                                            TextKey::GpuLaunchModeVerifiedCli
                                        }
                                        AgiLaunchMode::GuiOnly => TextKey::GpuLaunchModeGuiOnly,
                                        AgiLaunchMode::Unsupported => {
                                            TextKey::GpuLaunchModeUnsupported
                                        }
                                    }),
                                ),
                                (
                                    "executable",
                                    &value
                                        .executable
                                        .file_name()
                                        .unwrap_or_default()
                                        .to_string_lossy(),
                                ),
                            ],
                        )
                    },
                ),
                if launch_supported(capability) {
                    colors.info
                } else {
                    colors.warning
                },
            ),
        ];
        let columns = if width >= SUMMARY_WIDTH { 4 } else { 2 };
        let mut result = div().flex().flex_col().flex_shrink_0().gap(px(4.));
        for (row, cards) in cards.chunks(columns).enumerate() {
            result = result.child(
                div()
                    .flex()
                    .gap(px(4.))
                    .h(px(104.))
                    .children(cards.iter().enumerate().map(
                        |(column, (key, value, support, tone))| {
                            let icon: &'static [u8] = match row * columns + column {
                                0 => include_bytes!(
                                    "../../assets/navigation/ic_nav_gpu_inspector.svg"
                                ),
                                1 => include_bytes!("../../assets/gpu/ic_stat_verified.svg"),
                                2 => include_bytes!("../../assets/gpu/ic_stat_attention.svg"),
                                _ => include_bytes!("../../assets/gpu/ic_stat_capability.svg"),
                            };
                            studio::card(colors)
                                .id(("gpu-summary", row * columns + column))
                                .gap(px(4.))
                                .border_color(tone.alpha(0.5))
                                .flex_1()
                                .min_w_0()
                                .h_full()
                                .child(
                                    div()
                                        .flex()
                                        .items_center()
                                        .gap(px(8.))
                                        .min_w_0()
                                        .child(
                                            svg()
                                                .data(icon)
                                                .size(px(18.))
                                                .flex_shrink_0()
                                                .text_color(*tone),
                                        )
                                        .child(
                                            div()
                                                .font_weight(FontWeight::SEMIBOLD)
                                                .text_color(*tone)
                                                .min_w_0()
                                                .text_ellipsis()
                                                .child(crate::studio::selectable(
                                                    language.text(*key),
                                                )),
                                        ),
                                )
                                .child(
                                    div()
                                        .text_size(px(studio::FONT_TITLE))
                                        .font_weight(FontWeight::SEMIBOLD)
                                        .min_w_0()
                                        .text_ellipsis()
                                        .child(crate::studio::selectable(value.clone())),
                                )
                                .child(gpu_note(support.clone(), colors))
                        },
                    )),
            );
        }
        result.into_any_element()
    }
    fn gpu_toolbar(&self, colors: &studio::StudioColors, cx: &mut Context<Self>) -> AnyElement {
        let language = self.language;
        let entity = cx.entity();
        let items: Vec<_> = self
            .gpu
            .artifacts
            .iter()
            .take(aps_gpu::MAX_ARTIFACTS)
            .map(|record| (record.id(), record.title()))
            .collect();
        let selected = self.gpu.selected_record();
        div()
            .id("gpu-analysis-toolbar")
            .h(px(40.))
            .flex_shrink_0()
            .flex()
            .items_center()
            .gap(px(8.))
            .px(px(8.))
            .border_b_1()
            .border_color(colors.border)
            .overflow_x_scrollbar()
            .child(gpu_note(language.text(TextKey::GpuAnalysisTools), colors))
            .child(
                studio::compact_text_button(
                    "gpu-launch",
                    language.text(TextKey::GpuLaunchAgi),
                    !self.busy && launch_supported(self.gpu.capability.as_ref()),
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::GpuAgiManualOpen))
                .on_click(cx.listener(|this, _, _, cx| this.gpu_launch(cx))),
            )
            .child(
                studio::compact_text_button(
                    "gpu-import",
                    language.text(TextKey::GpuImportGpuTrace),
                    !self.busy,
                    true,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.gpu_pick(false, cx))),
            )
            .child(
                studio::compact_text_button(
                    "gpu-trace-analyzer",
                    language.text(TextKey::GpuOpenTraceAnalyzer),
                    !self.busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|_, _, _, cx| cx.emit(OpenGpuTrace(None)))),
            )
            .child(
                studio::compact_text_button(
                    "gpu-artifact-selector",
                    selected.map_or_else(
                        || language.text(TextKey::GpuSelectAnalysisArtifact).to_owned(),
                        GpuRecord::title,
                    ),
                    !self.busy && !items.is_empty(),
                    false,
                    colors,
                )
                .w(px(220.))
                .dropdown_menu(move |mut menu, _, _| {
                    for (id, title) in &items {
                        let id = id.clone();
                        let entity = entity.clone();
                        menu = menu.item(PopupMenuItem::new(title.clone()).on_click(
                            move |_, _, cx| {
                                entity.update(cx, |this, cx| this.gpu_select(id.clone(), cx))
                            },
                        ));
                    }
                    menu
                }),
            )
            .child(
                studio::compact_text_button(
                    "gpu-reveal",
                    language.text(TextKey::GpuShowInFiles),
                    !self.busy && selected.is_some(),
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.gpu_reveal(cx))),
            )
            .when(self.busy, |toolbar| {
                toolbar.child(
                    studio::compact_text_button(
                        "gpu-cancel",
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
                    "gpu-refresh-agi",
                    language.text(TextKey::GpuRefreshAgi),
                    !self.busy && self.gpu.capability.is_some(),
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.gpu_refresh_agi(cx))),
            )
            .child(
                studio::compact_text_button(
                    "gpu-configure-agi",
                    language.text(TextKey::GpuConfigureAgi),
                    !self.busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.gpu_configure(cx))),
            )
            .child(
                studio::compact_text_button(
                    "gpu-ai",
                    language.text(TextKey::ShellPrepareAiAnalysis),
                    !self.busy && selected.is_some(),
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::GpuAiMetadataOnly))
                .on_click(cx.listener(|_, _, window, cx| {
                    window.dispatch_action(Box::new(crate::shell::PrepareCurrentAi), cx)
                })),
            )
            .into_any_element()
    }
    fn gpu_artifact_rail(
        &self,
        search: &Entity<InputState>,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let mut panel = studio::card(colors)
            .p(px(4.))
            .gap(px(4.))
            .size_full()
            .min_h_0()
            .min_w_0()
            .child(studio::section_title(
                language.text(TextKey::GpuGpuArtifacts),
                colors,
            ))
            .child(gpu_note(
                language.format(
                    TextKey::GpuArtifactCount,
                    &[("count", &self.gpu.artifacts.len())],
                ),
                colors,
            ))
            .child(Input::new(search).w_full());
        let visible: Vec<_> = self
            .gpu
            .artifacts
            .iter()
            .filter(|record| artifact_matches(record, &self.gpu.filter))
            .collect();
        let offset = self.gpu.rail_offset;
        let mut list = div()
            .id("gpu-artifact-list")
            .flex()
            .flex_col()
            .gap(px(4.))
            .min_h_0()
            .flex_1()
            .overflow_y_scroll();
        for (index, record) in visible.iter().skip(offset).take(GPU_PAGE).enumerate() {
            let id = record.id();
            let selected = self.gpu.selected.as_ref() == Some(&id);
            list = list.child(
                Button::new(("gpu-artifact-card", index))
                    .ghost()
                    .selected(selected)
                    .h(px(78.))
                    .w_full()
                    .flex_shrink_0()
                    .p(px(6.))
                    .rounded(px(8.))
                    .justify_start()
                    .bg(if selected {
                        colors.selected_row
                    } else {
                        colors.panel
                    })
                    .border_1()
                    .border_color(if selected {
                        colors.accent
                    } else {
                        colors.border
                    })
                    .accessibility_label(record.title())
                    .child(
                        div()
                            .flex()
                            .gap(px(6.))
                            .items_center()
                            .w_full()
                            .child(
                                svg()
                                    .data(include_bytes!(
                                        "../../assets/navigation/ic_nav_gpu_inspector.svg"
                                    ))
                                    .size(px(18.))
                                    .text_color(colors.accent),
                            )
                            .child(
                                div()
                                    .flex()
                                    .flex_col()
                                    .min_w_0()
                                    .flex_1()
                                    .gap(px(1.))
                                    .child(
                                        div()
                                            .font_weight(FontWeight::MEDIUM)
                                            .whitespace_nowrap()
                                            .text_ellipsis()
                                            .child(record.title()),
                                    )
                                    .child(
                                        gpu_note(
                                            format!(
                                                "— · {}",
                                                language.text(kind_key(record.artifact.kind))
                                            ),
                                            colors,
                                        )
                                        .whitespace_nowrap()
                                        .text_ellipsis(),
                                    )
                                    .child(gpu_note(
                                        format!(
                                            "{} · {}",
                                            language.text(status_key(record.status)),
                                            size_text(record.artifact.size_bytes)
                                        ),
                                        colors,
                                    )),
                            ),
                    )
                    .on_click(cx.listener(move |this, _, _, cx| this.gpu_select(id.clone(), cx))),
            );
        }
        if visible.is_empty() {
            list = list.child(gpu_note(
                language.text(if self.gpu.artifacts.is_empty() {
                    TextKey::GpuNoGpuArtifacts
                } else {
                    TextKey::GpuNoMatchingArtifacts
                }),
                colors,
            ));
        }
        panel = panel.child(list).child(gpu_note(
            language.text(TextKey::GpuIndexSessionOnly),
            colors,
        ));
        if visible.len() > GPU_PAGE {
            panel = panel.child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(4.))
                    .child(
                        studio::compact_text_button(
                            "gpu-rail-prev",
                            language.text(TextKey::GpuPrevious),
                            offset > 0,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.gpu.rail_offset = this.gpu.rail_offset.saturating_sub(GPU_PAGE);
                            cx.notify();
                        })),
                    )
                    .child(gpu_note(
                        language.format(
                            TextKey::GpuPageRange,
                            &[
                                ("first", &(offset + 1)),
                                ("last", &(offset + GPU_PAGE).min(visible.len())),
                                ("total", &visible.len()),
                            ],
                        ),
                        colors,
                    ))
                    .child(
                        studio::compact_text_button(
                            "gpu-rail-next",
                            language.text(TextKey::GpuNext),
                            offset + GPU_PAGE < visible.len(),
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.gpu.rail_offset = this.gpu.rail_offset.saturating_add(GPU_PAGE);
                            cx.notify();
                        })),
                    ),
            );
        }
        panel.into_any_element()
    }

    fn gpu_begin(&mut self, cx: &mut Context<Self>) -> (u64, CancellationToken) {
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        self.export_generation += 1;
        self.export_busy = false;
        self.busy = true;
        self.error = None;
        self.notice = None;
        cx.notify();
        (self.generation, self.cancel.clone())
    }
    pub(super) fn gpu_pick(&mut self, relocate: bool, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let record = self.gpu.selected_record().cloned();
        if relocate && record.is_none() {
            return;
        }
        let (generation, cancel) = self.gpu_begin(cx);
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(
                self.language
                    .text(if relocate {
                        TextKey::GpuRelocate
                    } else {
                        TextKey::GpuImportGpuTrace
                    })
                    .into(),
            ),
        });
        let mut index = self.gpu.index.clone();
        cx.spawn(async move |this, cx| {
            let selected = picker.await;
            let path = match selected {
                Ok(Ok(Some(paths))) if paths.len() == 1 => paths[0].clone(),
                _ => {
                    this.update(cx, |this, cx| {
                        if this.generation == generation {
                            this.busy = false;
                            if matches!(selected, Ok(Err(_))) {
                                this.error = Some(TextKey::GpuPickerFailed.into());
                            }
                            cx.notify();
                        }
                    })
                    .ok();
                    return;
                }
            };
            let outcome = cx
                .background_executor()
                .spawn(async move {
                    let digest = if relocate {
                        let mut artifact =
                            (*record.expect("relocation requires selection").artifact).clone();
                        artifact
                            .relocate(&path, &cancel)
                            .map_err(|_| UiText::from(TextKey::GpuRelocateMismatch))?;
                        index
                            .import(&path, &cancel)
                            .map_err(|_| UiText::from(TextKey::GpuImportFailed))?
                    } else {
                        index
                            .import(&path, &cancel)
                            .map_err(|_| UiText::from(TextKey::GpuImportFailed))?
                    };
                    let artifact = index.get(digest).ok_or(TextKey::GpuImportFailed)?.clone();
                    let resolved = artifact
                        .resolve_location(&cancel)
                        .map_err(|_| UiText::from(TextKey::GpuVerifyFailed))?;
                    if cancel.is_cancelled() {
                        return Err(TextKey::GpuOperationCancelled.into());
                    }
                    Ok::<_, UiText>((index, artifact, resolved.status))
                })
                .await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match outcome {
                    Ok((index, artifact, status)) => {
                        let digest = artifact.sha256.to_hex();
                        this.gpu.index = index;
                        let old: BTreeMap<_, _> = this
                            .gpu
                            .artifacts
                            .iter()
                            .map(|record| (record.id(), record.status))
                            .collect();
                        this.gpu.artifacts = this
                            .gpu
                            .index
                            .artifacts()
                            .iter()
                            .map(|artifact| GpuRecord {
                                artifact: Arc::new(artifact.clone()),
                                status: if artifact.sha256.to_hex() == digest {
                                    Some(status)
                                } else {
                                    old.get(&artifact.sha256.to_hex()).copied().flatten()
                                },
                            })
                            .collect();
                        this.gpu.rail_offset = 0;
                        this.gpu_select(digest, cx);
                        this.notice = Some(TextKey::GpuArtifactImported.into());
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
    pub(super) fn gpu_select(&mut self, id: String, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(record) = self
            .gpu
            .artifacts
            .iter()
            .find(|record| record.id() == id)
            .cloned()
        else {
            return;
        };
        self.gpu.selected = Some(id);
        self.gpu_publish_record(&record);
        cx.notify();
    }
    fn gpu_publish_record(&mut self, record: &GpuRecord) {
        self.session = Some(Session::Gpu(record.artifact.clone()));
        self.session_id = Some(record.id());
        // AI/export receive only the minimized identity, never the paths or device dumps.
        self.page=Some(PageData{summary:format!("GPU artifact · {:?} · {} bytes",record.artifact.kind,record.artifact.size_bytes),
            columns:vec!["SHA-256".into(),"Kind".into(),"Size bytes".into(),"Verification".into()],
            rows:vec![vec![record.id(),format!("{:?}",record.artifact.kind),record.artifact.size_bytes.to_string(),record.status.map_or_else(||"Unverified".into(),|status|format!("{status:?}"))]],
            warnings:vec!["Artifact identity only; no AGI frame payload is decoded. Verification does not grant external launch authority.".into()],
            offset:0,has_more:false,row_actions:Vec::new(),ui:PageUi::default()});
        self.generation += 1;
        self.export_generation += 1;
        self.export_busy = false;
    }
    pub(super) fn gpu_verify(&mut self, cx: &mut Context<Self>) {
        self.gpu_verify_intent(false, false, cx);
    }
    pub(super) fn gpu_relocate(&mut self, cx: &mut Context<Self>) {
        self.gpu_pick(true, cx);
    }
    pub(super) fn gpu_reveal(&mut self, cx: &mut Context<Self>) {
        self.gpu_verify_intent(false, true, cx);
    }
    pub(super) fn gpu_open(&mut self, cx: &mut Context<Self>) {
        self.gpu_verify_intent(true, false, cx);
    }
    fn gpu_verify_intent(&mut self, open: bool, reveal: bool, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(record) = self.gpu.selected_record().cloned() else {
            return;
        };
        let capability = self.gpu.capability.clone();
        let (generation, cancel) = self.gpu_begin(cx);
        cx.spawn(async move |this, cx| {
            let artifact = record.artifact.clone();
            let result = cx
                .background_executor()
                .spawn(async move { artifact.resolve_location(&cancel) })
                .await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(location) => {
                        let status = location.status;
                        if let Some(record) = this
                            .gpu
                            .artifacts
                            .iter_mut()
                            .find(|candidate| candidate.id() == record.id())
                        {
                            record.status = Some(status);
                        }
                        let mut updated = record.clone();
                        updated.status = Some(status);
                        this.gpu_publish_record(&updated);
                        this.notice = Some(
                            if status == LocationStatus::Available {
                                TextKey::GpuArtifactVerified
                            } else {
                                TextKey::GpuArtifactRecovery
                            }
                            .into(),
                        );
                        if let Some(path) = location
                            .path
                            .filter(|_| status == LocationStatus::Available)
                        {
                            if reveal {
                                cx.reveal_path(&path);
                            }
                            if open {
                                match record.artifact.open_route {
                                    OpenRoute::Perfetto => cx.emit(OpenGpuTrace(Some(path))),
                                    OpenRoute::Desktop => cx.open_with_system(&path),
                                    OpenRoute::Agi if launch_supported(capability.as_ref()) => {
                                        this.gpu_launch(cx)
                                    }
                                    _ => this.error = Some(TextKey::GpuUnavailableAction.into()),
                                }
                            }
                        }
                    }
                    Err(_) => {
                        if let Some(record) = this
                            .gpu
                            .artifacts
                            .iter_mut()
                            .find(|candidate| candidate.id() == record.id())
                        {
                            record.status = Some(LocationStatus::Unreadable);
                        }
                        let mut updated = record;
                        updated.status = Some(LocationStatus::Unreadable);
                        this.gpu_publish_record(&updated);
                        this.error = Some(TextKey::GpuVerifyFailed.into());
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
    fn gpu_configure(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let (generation, _) = self.gpu_begin(cx);
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(self.language.text(TextKey::GpuSelectExecutable).into()),
        });
        cx.spawn(async move |this, cx| {
            let selected = picker.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match selected {
                    Ok(Ok(Some(paths))) if paths.len() == 1 => this.gpu_probe(paths[0].clone(), cx),
                    Ok(Err(_)) => this.error = Some(TextKey::GpuPickerFailed.into()),
                    _ => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
    fn gpu_refresh_agi(&mut self, cx: &mut Context<Self>) {
        if let Some(capability) = &self.gpu.capability {
            self.gpu_probe(capability.executable.clone(), cx);
        }
    }
    fn gpu_probe(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let (generation, cancel) = self.gpu_begin(cx);
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { AgiToolchain::probe(&path, &cancel) })
                .await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(capability) => {
                        this.gpu.capability = Some(capability);
                        this.notice = Some(TextKey::GpuAgiRefreshed.into());
                    }
                    Err(_) => this.error = Some(TextKey::GpuConfigurationInvalid.into()),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
    fn gpu_launch(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(capability) = self
            .gpu
            .capability
            .clone()
            .filter(|capability| launch_supported(Some(capability)))
        else {
            return;
        };
        let (generation, _) = self.gpu_begin(cx);
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move { AgiToolchain::launch(&capability) })
                .await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(()) => this.notice = Some(TextKey::GpuLaunchRequested.into()),
                    Err(_) => this.error = Some(TextKey::GpuLaunchFailed.into()),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
    pub(crate) fn set_gpu_target(&mut self, serial: Option<String>, cx: &mut Context<Self>) {
        if self.kind != Destination::Gpu || self.gpu.device_serial == serial {
            return;
        }
        self.gpu.device_cancel.cancel();
        self.gpu.device_generation += 1;
        self.gpu.device_busy = false;
        self.gpu.device = None;
        self.gpu.device_error = None;
        self.gpu.device_serial = serial;
        cx.notify();
    }
    pub(super) fn gpu_refresh_device(&mut self, cx: &mut Context<Self>) {
        let Some(serial) = self.gpu.device_serial.clone() else {
            return;
        };
        if self.gpu.device_busy {
            return;
        }
        self.gpu.device_cancel.cancel();
        self.gpu.device_cancel = CancellationToken::new();
        self.gpu.device_generation += 1;
        let generation = self.gpu.device_generation;
        let cancel = self.gpu.device_cancel.clone();
        self.gpu.device_busy = true;
        self.gpu.device_error = None;
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    let location = aps_adb::AdbLocator::new()
                        .locate(None)
                        .map_err(|error| error.to_string())?;
                    GpuDeviceContextReader::new(location).read(&serial, &cancel)
                })
                .await;
            this.update(cx, |this, cx| {
                if this.gpu.device_generation != generation {
                    return;
                }
                this.gpu.device_busy = false;
                match result {
                    Ok(context) => this.gpu.device = Some(context),
                    Err(error) => {
                        this.gpu.device = None;
                        this.gpu.device_error = Some(error);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}
#[cfg(test)]
mod tests {
    use super::{
        ARTIFACT_RAIL, COMPACT_RAIL_HEIGHT, INSIGHT_RAIL, SUMMARY_WIDTH, WIDE_WIDTH, can_open,
        size_text,
    };
    #[test]
    fn reference_layout_dimensions() {
        assert_eq!(
            (
                WIDE_WIDTH,
                SUMMARY_WIDTH,
                ARTIFACT_RAIL,
                INSIGHT_RAIL,
                COMPACT_RAIL_HEIGHT
            ),
            (1100., 900., 264., 300., 236.)
        );
    }
    #[test]
    fn no_selection_never_grants_open() {
        assert!(!can_open(None, None));
        assert_eq!(size_text(0), "0 B");
        assert_eq!(size_text(1024), "1.0 KiB");
    }

    struct Fixture(std::path::PathBuf);
    impl Fixture {
        fn new() -> Self {
            use std::sync::atomic::{AtomicU64, Ordering};
            static NEXT: AtomicU64 = AtomicU64::new(0);
            let path = std::env::temp_dir().join(format!(
                "aps-gpu-hub-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir(&path).unwrap();
            Self(path)
        }
        fn record(&self, filename: &str, bytes: &[u8]) -> super::GpuRecord {
            let path = self.0.join(filename);
            std::fs::write(&path, bytes).unwrap();
            super::GpuRecord {
                artifact: std::sync::Arc::new(
                    aps_gpu::GpuArtifact::import(&path, &aps_platform::CancellationToken::new())
                        .unwrap(),
                ),
                status: None,
            }
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn selection_is_digest_bound_not_a_filtered_position_or_duplicate_filename() {
        let first = Fixture::new();
        let second = Fixture::new();
        let a = first.record("capture.pftrace", b"first distinct capture");
        let b = second.record("capture.pftrace", b"second distinct capture");
        assert_eq!(a.title(), b.title());
        assert_ne!(a.id(), b.id());
        let mut state = super::GpuUi::default();
        state.selected = Some(b.id());
        state.artifacts = vec![a, b.clone()];
        assert_eq!(state.selected_record().unwrap().id(), b.id());
        state.artifacts.reverse();
        state.filter = "no matching artifact".into();
        assert_eq!(state.selected_record().unwrap().id(), b.id());
        assert!(
            state
                .artifacts
                .iter()
                .all(|record| !super::artifact_matches(record, &state.filter))
        );
    }

    #[test]
    fn availability_and_real_route_gate_opening_instead_of_extension_alone() {
        let fixture = Fixture::new();
        let mut trace = fixture.record("capture.pftrace", b"trace identity only");
        assert!(!can_open(Some(&trace), None));
        trace.status = Some(aps_gpu::LocationStatus::Available);
        assert!(can_open(Some(&trace), None));
        for status in [
            aps_gpu::LocationStatus::Missing,
            aps_gpu::LocationStatus::SizeChanged,
            aps_gpu::LocationStatus::ContentChanged,
            aps_gpu::LocationStatus::Unreadable,
        ] {
            trace.status = Some(status);
            assert!(!can_open(Some(&trace), None));
        }
        let mut agi = fixture.record("capture.gfxtrace", b"opaque AGI content");
        agi.status = Some(aps_gpu::LocationStatus::Available);
        assert!(!can_open(Some(&agi), None));
        let mut capability = aps_gpu::AgiCapability {
            executable: fixture.0.join("not-executed"),
            version: None,
            launch_mode: aps_gpu::AgiLaunchMode::GuiOnly,
            artifact_open_supported: false,
            stable_arguments: Vec::new(),
            warnings: Vec::new(),
        };
        // A manual GUI launch is explicitly labelled separately from opening an artifact.
        assert!(can_open(Some(&agi), Some(&capability)));
        assert_eq!(
            super::open_key(Some(&agi), Some(&capability)),
            super::TextKey::GpuLaunchAgi
        );
        capability.launch_mode = aps_gpu::AgiLaunchMode::Unsupported;
        assert!(!can_open(Some(&agi), Some(&capability)));
    }

    #[test]
    fn application_filter_is_case_insensitive_and_does_not_change_identity() {
        let fixture = Fixture::new();
        let record = fixture.record("FRAME.gfxtrace", b"opaque evidence");
        let id = record.id();
        assert!(super::artifact_matches(&record, " frame "));
        assert!(super::artifact_matches(&record, "agiframeprofile"));
        assert!(super::artifact_matches(&record, &id[..12]));
        assert!(!super::artifact_matches(&record, "unrelated"));
        assert_eq!(record.id(), id);
        assert_eq!(super::status_key(None), super::TextKey::GpuUnverified);
    }
}
