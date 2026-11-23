//! Perfetto capture configuration and explicit recording.
//!
//! Templates, data sources and configuration stay in three independently
//! scrolling columns. The page never runs a device command by itself; every
//! action goes through Capture runtime methods.

use super::TraceWorkspace;
use super::capture::{
    CaptureIntegritySummary, CaptureState, CaptureTemplate, DeviceCapabilities, Probe,
    ProbeBlocker, ProbeGroup, format_unix_ms,
};
use super::capture_config::PreflightReport;
use crate::studio::{self, FONT_BODY, FONT_SUPPORTING, StudioColors};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    component::{
        Disableable, IconName, Sizable,
        button::{Button, ButtonVariants},
        checkbox::Checkbox,
        input::{Input, InputState, Textarea, TextareaState},
        resizable::{ResizableState, h_resizable, resizable_panel},
    },
    prelude::*,
    *,
};
use std::collections::BTreeSet;

const TEMPLATE_CARD_HEIGHT: f32 = 76.;
const TEMPLATE_PANEL_MIN_WIDTH: f32 = 170.;
const TEMPLATE_PANEL_MAX_WIDTH: f32 = 210.;
const DATA_SOURCES_MIN_WIDTH: f32 = 280.;
const CONFIGURATION_MIN_WIDTH: f32 = 300.;
const CONFIGURATION_DEFAULT_WIDTH: f32 = 360.;

/// page.rs entry point; the same widget tree as the Compose capture page.
#[allow(dead_code)]
pub(in crate::trace) fn render(
    this: &mut TraceWorkspace,
    window: &mut Window,
    cx: &mut Context<TraceWorkspace>,
) -> AnyElement {
    capture_page(this, window, cx)
}

pub(in crate::trace) fn capture_page(
    this: &mut TraceWorkspace,
    window: &mut Window,
    cx: &mut Context<TraceWorkspace>,
) -> AnyElement {
    let language = this.language;
    this.capture.ensure_inputs(language, window, cx);
    if !this.capture.is_active() {
        this.capture.sync_shared_target_package(window, cx);
    }
    let colors = StudioColors::from_theme(cx);
    let entity = cx.entity().downgrade();
    let inputs = this.capture.inputs();
    let custom_config_ready = this.capture.selected_template() != CaptureTemplate::Custom
        || inputs.is_some_and(|inputs| !inputs.custom_config.read(cx).value().trim().is_empty());
    let target_package_blank =
        inputs.is_none_or(|inputs| inputs.target_package.read(cx).value().trim().is_empty());
    let snapshot = CaptureSnapshot {
        panel_state: this.ui.capture_panels.clone(),
        language,
        colors,
        template: this.capture.selected_template(),
        probes: this.capture.enabled_probes().clone(),
        capabilities: this.capture.capabilities().cloned(),
        preflight: this.capture.preflight_report().cloned(),
        config_notice: this.capture.config_notice().map(str::to_owned),
        integrity: this.capture.integrity().cloned(),
        active: this.capture.is_active(),
        state: this.capture.state().clone(),
        inputs: inputs.map(|inputs| CaptureInputsSnapshot {
            target_package: inputs.target_package.clone(),
            duration: inputs.duration.clone(),
            buffer_mb: inputs.buffer_mb.clone(),
            additional_categories: inputs.additional_categories.clone(),
            custom_config: inputs.custom_config.clone(),
        }),
        device_label: this
            .capture
            .selected_device()
            .filter(|device| device.online)
            .map(|device| device.display_name.clone()),
        target_package_blank,
        can_start: this.capture.device_ready()
            && this.capture.capabilities().is_some()
            && custom_config_ready
            && this
                .capture
                .preflight_report()
                .is_some_and(PreflightReport::can_start)
            && !this.capture.is_active(),
    };
    container_query(move |size, _, _| {
        capture_workspace(
            f32::from(size.width),
            f32::from(size.height),
            &snapshot,
            &entity,
        )
    })
    .size_full()
    .into_any_element()
}

struct CaptureSnapshot {
    panel_state: Entity<ResizableState>,
    language: Language,
    colors: StudioColors,
    template: CaptureTemplate,
    probes: BTreeSet<Probe>,
    capabilities: Option<DeviceCapabilities>,
    preflight: Option<PreflightReport>,
    config_notice: Option<String>,
    integrity: Option<CaptureIntegritySummary>,
    active: bool,
    state: CaptureState,
    inputs: Option<CaptureInputsSnapshot>,
    device_label: Option<String>,
    target_package_blank: bool,
    can_start: bool,
}

#[derive(Clone)]
struct CaptureInputsSnapshot {
    target_package: Entity<InputState>,
    duration: Entity<InputState>,
    buffer_mb: Entity<InputState>,
    additional_categories: Entity<InputState>,
    custom_config: Entity<TextareaState>,
}

fn capture_workspace(
    available: f32,
    height: f32,
    snapshot: &CaptureSnapshot,
    entity: &WeakEntity<TraceWorkspace>,
) -> AnyElement {
    let (template_width, sources_width) = capture_panel_widths(available);
    let content_width = available.max(template_width + sources_width + CONFIGURATION_MIN_WIDTH);
    let configuration_width = content_width - template_width - sources_width;
    div()
        .id("trace-capture-workspace")
        .size_full()
        .min_w_0()
        .overflow_x_scroll()
        .child(
            div().w(px(content_width)).h_full().child(
                h_resizable("capture-horizontal-layout")
                    .with_state(&snapshot.panel_state)
                    .child(
                        resizable_panel()
                            .size(px(template_width))
                            .size_range(
                                px(TEMPLATE_PANEL_MIN_WIDTH)
                                    ..px(content_width
                                        - DATA_SOURCES_MIN_WIDTH
                                        - CONFIGURATION_MIN_WIDTH),
                            )
                            .child(scrolling_panel(
                                "capture-template-panel",
                                snapshot.language.text(TextKey::TraceTemplate),
                                snapshot.colors,
                                template_content(snapshot, entity),
                            )),
                    )
                    .child(
                        resizable_panel()
                            .size(px(sources_width))
                            .size_range(
                                px(DATA_SOURCES_MIN_WIDTH)
                                    ..px(content_width
                                        - TEMPLATE_PANEL_MIN_WIDTH
                                        - CONFIGURATION_MIN_WIDTH),
                            )
                            .child(scrolling_panel(
                                "capture-data-sources-panel",
                                snapshot.language.text(TextKey::DataSources),
                                snapshot.colors,
                                data_sources_content(snapshot, entity),
                            )),
                    )
                    .child(
                        resizable_panel()
                            .size(px(configuration_width))
                            .size_range(
                                px(CONFIGURATION_MIN_WIDTH)
                                    ..px(content_width
                                        - TEMPLATE_PANEL_MIN_WIDTH
                                        - DATA_SOURCES_MIN_WIDTH),
                            )
                            .child(scrolling_panel(
                                "capture-configuration-panel",
                                snapshot.language.text(TextKey::Configuration),
                                snapshot.colors,
                                configuration_content(snapshot, entity, height),
                            )),
                    ),
            ),
        )
        .into_any_element()
}

fn capture_panel_widths(available: f32) -> (f32, f32) {
    let content =
        available.max(TEMPLATE_PANEL_MIN_WIDTH + DATA_SOURCES_MIN_WIDTH + CONFIGURATION_MIN_WIDTH);
    let templates = (content * 0.18).clamp(TEMPLATE_PANEL_MIN_WIDTH, TEMPLATE_PANEL_MAX_WIDTH);
    let sources = (content - templates - CONFIGURATION_DEFAULT_WIDTH).max(DATA_SOURCES_MIN_WIDTH);
    (templates, sources)
}

fn scrolling_panel(
    id: &'static str,
    title: impl Into<SharedString>,
    colors: StudioColors,
    content: AnyElement,
) -> Stateful<Div> {
    div()
        .id(id)
        .flex()
        .flex_col()
        .h_full()
        .w_full()
        .min_w_0()
        .min_h_0()
        .overflow_hidden()
        .bg(colors.panel)
        .child(panel_header(title, colors))
        .child(studio::divider(&colors))
        .child(
            div()
                .id((id, 1usize))
                .flex_1()
                .min_h_0()
                .overflow_y_scroll()
                .p(px(6.))
                .child(content),
        )
}

fn panel_header(title: impl Into<SharedString>, colors: StudioColors) -> Div {
    div()
        .px(px(8.))
        .py(px(6.))
        .text_size(px(FONT_SUPPORTING))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(colors.secondary_text)
        .child(title.into())
}

fn template_content(snapshot: &CaptureSnapshot, entity: &WeakEntity<TraceWorkspace>) -> AnyElement {
    let mut column = div().w_full().flex().flex_col().gap(px(6.));
    for template in CaptureTemplate::ALL {
        column = column.child(template_card(template, snapshot, entity));
    }
    column.into_any_element()
}

fn template_card(
    template: CaptureTemplate,
    snapshot: &CaptureSnapshot,
    entity: &WeakEntity<TraceWorkspace>,
) -> Button {
    let selected = snapshot.template == template;
    let colors = snapshot.colors;
    let language = snapshot.language;
    let entity = entity.clone();
    Button::new(("capture-template", template as usize))
        .ghost()
        .w_full()
        .h(px(TEMPLATE_CARD_HEIGHT))
        .px(px(8.))
        .py(px(7.))
        .rounded(px(6.))
        .border_1()
        .border_color(colors.accent)
        .bg(if selected {
            colors.accent
        } else {
            colors.panel
        })
        .disabled(snapshot.active)
        .accessibility_label(language.text(template.label()))
        .child(
            div()
                .w_full()
                .min_w_0()
                .flex()
                .flex_col()
                .gap(px(2.))
                .whitespace_normal()
                .child(
                    div()
                        .text_size(px(FONT_BODY))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(if selected {
                            colors.accent_text
                        } else {
                            colors.accent
                        })
                        .child(language.text(template.label())),
                )
                .child(
                    div()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(if selected {
                            colors.accent_text
                        } else {
                            colors.secondary_text
                        })
                        .child(language.text(template.description())),
                ),
        )
        .on_click(move |_, _, cx| {
            let _ = entity.update(cx, |this, cx| {
                if this.capture.is_active() {
                    return;
                }
                this.capture.select_template(template, cx);
                cx.notify();
            });
        })
}

fn data_sources_content(
    snapshot: &CaptureSnapshot,
    entity: &WeakEntity<TraceWorkspace>,
) -> AnyElement {
    let language = snapshot.language;
    let colors = snapshot.colors;
    let mut column = div().w_full().flex().flex_col().gap(px(6.));
    if let Some(error) = snapshot
        .capabilities
        .as_ref()
        .and_then(|capabilities| capabilities.query_error.as_deref())
    {
        column = column.child(error_line(
            language.format(TextKey::CapabilityQueryFailed, &[("value1", &error)]),
            colors.error,
        ));
    }
    if snapshot.template != CaptureTemplate::Custom {
        for group in ProbeGroup::ALL {
            column = column.child(probe_group_card(group, snapshot, entity));
        }
    }
    column.into_any_element()
}

fn probe_group_card(
    group: ProbeGroup,
    snapshot: &CaptureSnapshot,
    entity: &WeakEntity<TraceWorkspace>,
) -> AnyElement {
    let colors = snapshot.colors;
    let probes: Vec<Probe> = Probe::ALL
        .into_iter()
        .filter(|probe| probe.group() == group)
        .collect();
    let mut card = div()
        .id(("capture-probe-group", group as usize))
        .w_full()
        .flex()
        .flex_col()
        .gap(px(4.))
        .p(px(7.))
        .border_1()
        .border_color(colors.border)
        .rounded(px(6.))
        .child(
            div()
                .text_size(px(FONT_SUPPORTING))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .child(group.label().to_uppercase()),
        );
    for chunk in probes.chunks(2) {
        let mut row = div().w_full().flex().gap(px(8.));
        for probe in chunk {
            row = row.child(probe_option(*probe, snapshot, entity));
        }
        if chunk.len() == 1 {
            row = row.child(div().flex_1());
        }
        card = card.child(row);
    }
    card.into_any_element()
}

fn probe_option(
    probe: Probe,
    snapshot: &CaptureSnapshot,
    entity: &WeakEntity<TraceWorkspace>,
) -> AnyElement {
    let blocker = snapshot
        .capabilities
        .as_ref()
        .and_then(|capabilities| capabilities.probe_blocker(probe))
        .or_else(|| {
            (probe.requires_target_package() && snapshot.target_package_blank)
                .then_some(ProbeBlocker::TargetPackage)
        });
    let disabled = snapshot.active || blocker.is_some();
    let checked = snapshot.probes.contains(&probe) && blocker.is_none();
    let colors = snapshot.colors;
    let entity = entity.clone();
    let mut label = div()
        .flex()
        .flex_col()
        .gap(px(1.))
        .flex_1()
        .min_w_0()
        .child(
            div()
                .text_size(px(FONT_SUPPORTING))
                .text_color(if disabled {
                    colors.muted_text
                } else {
                    colors.text
                })
                .child(probe.label()),
        );
    if let Some(blocker) = blocker {
        label = label.child(
            div()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.warning)
                .child(crate::studio::selectable(
                    blocker.message(snapshot.language),
                )),
        );
    }
    div()
        .flex_1()
        .min_w_0()
        .flex()
        .items_center()
        .gap(px(5.))
        .child(
            Checkbox::new(("capture-probe", probe as usize))
                .with_size(gpui_kit::component::Size::Small)
                .checked(checked)
                .accessibility_label(probe.label())
                .disabled(disabled)
                .on_change(move |checked, _, cx| {
                    let _ = entity.update(cx, |this, cx| {
                        if this.capture.is_active() {
                            return;
                        }
                        this.capture.toggle_probe(probe, *checked);
                        cx.notify();
                    });
                }),
        )
        .child(label)
        .into_any_element()
}

fn configuration_content(
    snapshot: &CaptureSnapshot,
    entity: &WeakEntity<TraceWorkspace>,
    height: f32,
) -> AnyElement {
    let language = snapshot.language;
    let colors = snapshot.colors;
    let mut column = div().w_full().flex().flex_col().gap(px(8.));

    column = column.child(
        div()
            .text_size(px(FONT_SUPPORTING))
            .text_color(if snapshot.device_label.is_some() {
                colors.success
            } else {
                colors.error
            })
            .child(match snapshot.device_label.as_deref() {
                Some(label) => language.format(TextKey::DeviceConnected, &[("value1", &label)]),
                None => language.text(TextKey::NoOnlineDevice).to_owned(),
            }),
    );

    if snapshot.template == CaptureTemplate::FullCapture {
        column = column.child(error_line(
            language.text(TextKey::TraceCaptureFullWarning).to_owned(),
            colors.warning,
        ));
    }
    if let Some(inputs) = &snapshot.inputs {
        if snapshot.template == CaptureTemplate::Custom {
            column = column.child(config_controls(snapshot, entity));
            if let Some(notice) = &snapshot.config_notice {
                column = column.child(error_line(notice.clone(), colors.secondary_text));
            }
            let editor_height = custom_config_height(height);
            column = column
                .child(field_label(
                    language.text(TextKey::CustomTraceconfigTextProtobuf),
                    colors,
                ))
                .child(
                    div().w_full().h(px(editor_height)).flex_shrink_0().child(
                        Textarea::new(&inputs.custom_config)
                            .h(px(editor_height))
                            .w_full()
                            .readonly(snapshot.active)
                            .aria_label(language.text(TextKey::CustomTraceconfigTextProtobuf)),
                    ),
                );
        } else {
            column = column
                .child(field_label(language.text(TextKey::TargetPackage), colors))
                .child(
                    Input::new(&inputs.target_package)
                        .w_full()
                        .readonly(snapshot.active)
                        .aria_label(language.text(TextKey::TargetPackage)),
                )
                .child(
                    div()
                        .w_full()
                        .flex()
                        .gap(px(8.))
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .flex()
                                .flex_col()
                                .gap(px(2.))
                                .child(field_label(language.text(TextKey::DurationSeconds), colors))
                                .child(
                                    Input::new(&inputs.duration)
                                        .w_full()
                                        .readonly(snapshot.active)
                                        .aria_label(language.text(TextKey::DurationSeconds)),
                                ),
                        )
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .flex()
                                .flex_col()
                                .gap(px(2.))
                                .child(field_label(language.text(TextKey::BufferMb), colors))
                                .child(
                                    Input::new(&inputs.buffer_mb)
                                        .w_full()
                                        .readonly(snapshot.active)
                                        .aria_label(language.text(TextKey::BufferMb)),
                                ),
                        ),
                )
                .child(field_label(
                    language.text(TextKey::AdditionalCategoriesEvents),
                    colors,
                ))
                .child(
                    Input::new(&inputs.additional_categories)
                        .w_full()
                        .readonly(snapshot.active)
                        .aria_label(language.text(TextKey::AdditionalCategoriesEvents)),
                );
        }
    }

    let mut buttons = div().w_full().flex().flex_wrap().gap(px(6.));
    let start_entity = entity.clone();
    buttons = buttons.child(
        studio::compact_text_button(
            "capture-start",
            language.text(TextKey::StartCapture),
            snapshot.can_start,
            true,
            &colors,
        )
        .on_click(move |_, _, cx| {
            let _ = start_entity.update(cx, |this, cx| {
                if this.capture.is_active() {
                    return;
                }
                let config = this.capture.config(cx);
                this.start_capture(config, cx);
            });
        }),
    );
    if matches!(snapshot.state, CaptureState::Recording { .. }) {
        let stop_entity = entity.clone();
        buttons = buttons.child(
            studio::compact_text_button(
                "capture-stop",
                language.text(TextKey::Stop),
                true,
                false,
                &colors,
            )
            .on_click(move |_, _, cx| {
                let _ = stop_entity.update(cx, |this, cx| this.stop_capture(cx));
            }),
        );
    }
    if let CaptureState::Completed { trace_path, .. } = &snapshot.state {
        let open_entity = entity.clone();
        let path = trace_path.clone();
        buttons = buttons.child(
            studio::compact_text_button(
                "capture-open",
                language.text(TextKey::OpenInPerfettoUi),
                true,
                false,
                &colors,
            )
            .on_click(move |_, _, cx| {
                let path = path.clone();
                let _ = open_entity.update(cx, |this, cx| {
                    this.open_captured_trace(path.clone(), cx);
                });
            }),
        );
    }
    column = column.child(buttons);

    if snapshot.state.in_progress() {
        column = column.child(
            div()
                .id("capture-progress")
                .w_full()
                .h(px(2.))
                .rounded(px(1.))
                .bg(colors.accent),
        );
    }
    column = column.child(capture_status(snapshot.state.clone(), language, colors));
    if matches!(snapshot.state, CaptureState::Completed { .. })
        && let Some(summary) = &snapshot.integrity
    {
        column = column.child(integrity_content(summary, snapshot, entity));
    }
    if let Some(report) = &snapshot.preflight {
        column = column.child(preflight_content(report, language, colors));
    }
    column.into_any_element()
}

fn config_controls(snapshot: &CaptureSnapshot, entity: &WeakEntity<TraceWorkspace>) -> Div {
    let language = snapshot.language;
    let colors = snapshot.colors;
    let editable = !snapshot.active && snapshot.inputs.is_some();
    let import_entity = entity.clone();
    let save_entity = entity.clone();
    let reset_entity = entity.clone();
    let validate_entity = entity.clone();
    div()
        .w_full()
        .flex()
        .flex_wrap()
        .gap(px(6.))
        .child(
            studio::compact_text_button(
                "capture-import-config",
                language.text(TextKey::TraceCaptureImportConfig),
                editable,
                false,
                &colors,
            )
            .icon(IconName::FolderOpen)
            .on_click(move |_, window, cx| {
                let _ = import_entity.update(cx, |this, cx| {
                    if this.capture.is_active() {
                        return;
                    }
                    this.capture.import_config(window, cx);
                });
            }),
        )
        .child(
            studio::compact_text_button(
                "capture-save-config",
                language.text(TextKey::TraceCaptureSaveDraft),
                editable && snapshot.template == CaptureTemplate::Custom,
                false,
                &colors,
            )
            .on_click(move |_, _, cx| {
                let _ = save_entity.update(cx, |this, cx| {
                    if this.capture.is_active() {
                        return;
                    }
                    this.capture.save_config(cx);
                });
            }),
        )
        .child(
            studio::compact_text_button(
                "capture-reset-config",
                language.text(TextKey::TraceCaptureResetConfig),
                editable,
                false,
                &colors,
            )
            .icon(IconName::Undo)
            .on_click(move |_, window, cx| {
                let _ = reset_entity.update(cx, |this, cx| {
                    if this.capture.is_active() {
                        return;
                    }
                    this.capture.reset_config(window, cx);
                    cx.notify();
                });
            }),
        )
        .child(
            studio::compact_text_button(
                "capture-validate-config",
                language.text(TextKey::TraceCaptureValidateConfig),
                editable,
                false,
                &colors,
            )
            .icon(IconName::CircleCheck)
            .on_click(move |_, _, cx| {
                let _ = validate_entity.update(cx, |this, cx| {
                    if this.capture.is_active() {
                        return;
                    }
                    this.capture.run_preflight(cx);
                    cx.notify();
                });
            }),
        )
}

fn custom_config_height(available_height: f32) -> f32 {
    (available_height * 0.5).clamp(280., 560.)
}

fn preflight_content(
    report: &PreflightReport,
    language: Language,
    colors: StudioColors,
) -> Stateful<Div> {
    let mut column = div()
        .id("capture-preflight")
        .w_full()
        .flex()
        .flex_col()
        .gap(px(4.))
        .child(studio::divider(&colors))
        .child(field_label(
            language.text(TextKey::TraceCapturePreflight),
            colors,
        ))
        .child(error_line(
            language.format(
                TextKey::TraceCapturePreflightSummary,
                &[
                    ("value1", &report.errors.len()),
                    ("value2", &report.warnings.len()),
                ],
            ),
            if !report.errors.is_empty() {
                colors.error
            } else if !report.warnings.is_empty() {
                colors.warning
            } else {
                colors.success
            },
        ))
        .child(error_line(
            match report.duration_ms {
                Some(duration) => {
                    language.format(TextKey::TraceCaptureDurationMs, &[("value1", &duration)])
                }
                None => language
                    .text(TextKey::TraceCaptureDurationUnspecified)
                    .to_owned(),
            },
            colors.secondary_text,
        ))
        .child(error_line(
            language.format(
                TextKey::TraceCaptureTotalBufferKb,
                &[("value1", &report.total_buffer_kb)],
            ),
            colors.secondary_text,
        ));
    for error in &report.errors {
        column = column.child(error_line(
            language.format(TextKey::TraceCapturePreflightError, &[("value1", error)]),
            colors.error,
        ));
    }
    for warning in &report.warnings {
        column = column.child(error_line(
            language.format(
                TextKey::TraceCapturePreflightWarning,
                &[("value1", warning)],
            ),
            colors.warning,
        ));
    }
    column
        .child(metadata_list(
            language.text(TextKey::TraceCaptureParsedDataSources),
            &report.data_sources,
            language,
            colors,
        ))
        .child(metadata_list(
            language.text(TextKey::TraceCaptureParsedCategories),
            &report.atrace_categories,
            language,
            colors,
        ))
}

fn metadata_list(
    title: &'static str,
    values: &[String],
    language: Language,
    colors: StudioColors,
) -> Div {
    let mut column = div()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(2.))
        .child(field_label(title, colors));
    if values.is_empty() {
        column = column.child(error_line(
            language.text(TextKey::TraceCaptureNone).to_owned(),
            colors.muted_text,
        ));
    } else {
        for value in values {
            column = column.child(error_line(value.clone(), colors.secondary_text));
        }
    }
    column
}

fn integrity_content(
    summary: &CaptureIntegritySummary,
    snapshot: &CaptureSnapshot,
    entity: &WeakEntity<TraceWorkspace>,
) -> Stateful<Div> {
    let language = snapshot.language;
    let colors = snapshot.colors;
    let reveal_entity = entity.clone();
    let mut column = div()
        .id("capture-integrity")
        .w_full()
        .flex()
        .flex_col()
        .gap(px(4.))
        .child(studio::divider(&colors))
        .child(field_label(
            language.text(TextKey::TraceCaptureIntegrity),
            colors,
        ))
        .child(error_line(summary.status.clone(), colors.text));
    for message in &summary.messages {
        column = column.child(error_line(message.clone(), colors.secondary_text));
    }
    column.child(
        studio::compact_text_button(
            "capture-reveal-integrity-report",
            language.text(TextKey::TraceCaptureRevealIntegrityReport),
            summary.report_path.is_some(),
            false,
            &colors,
        )
        .icon(IconName::FolderOpen)
        .on_click(move |_, _, cx| {
            let _ = reveal_entity.update(cx, |this, cx| this.capture.reveal_integrity_report(cx));
        }),
    )
}

fn capture_status(state: CaptureState, language: Language, colors: StudioColors) -> Div {
    let text = match &state {
        CaptureState::Idle => language.text(TextKey::Ready).to_owned(),
        CaptureState::Preparing => language.text(TextKey::PreparingCapture).to_owned(),
        CaptureState::Recording { started_unix_ms } => language.format(
            TextKey::RecordingSince,
            &[("value1", &format_unix_ms(*started_unix_ms))],
        ),
        CaptureState::Pulling => language.text(TextKey::PullingTraceFromDevice).to_owned(),
        CaptureState::Completed { size_bytes, .. } => language.format(
            TextKey::CompletedMb,
            &[("value1", &(size_bytes / 1024 / 1024))],
        ),
        CaptureState::Failed(error) => {
            language.format(TextKey::Failed, &[("value1", &error.as_str())])
        }
    };
    div()
        .text_size(px(FONT_SUPPORTING))
        .text_color(if matches!(state, CaptureState::Failed(_)) {
            colors.error
        } else {
            colors.secondary_text
        })
        .child(crate::studio::selectable(text))
}

fn field_label(text: &'static str, colors: StudioColors) -> Div {
    div()
        .text_size(px(FONT_SUPPORTING))
        .font_weight(FontWeight::MEDIUM)
        .text_color(colors.secondary_text)
        .child(crate::studio::selectable(text))
}

fn error_line(text: String, color: Rgba) -> Div {
    div()
        .text_size(px(FONT_SUPPORTING))
        .text_color(color)
        .child(crate::studio::selectable(text))
}

#[cfg(test)]
mod tests {
    use super::{CONFIGURATION_MIN_WIDTH, capture_panel_widths, custom_config_height};
    use aps_ui_foundation::{Language, TextKey};

    #[test]
    fn capture_keeps_templates_data_sources_and_configuration_in_three_columns() {
        let source = include_str!("capture_page.rs");
        let implementation = source.split("#[cfg(test)]").next().unwrap();
        let workspace = implementation
            .split("fn capture_workspace(")
            .nth(1)
            .unwrap()
            .split("fn scrolling_panel(")
            .next()
            .unwrap();
        let templates = workspace.find("capture-template-panel").unwrap();
        let sources = workspace.find("capture-data-sources-panel").unwrap();
        let configuration = workspace.find("capture-configuration-panel").unwrap();
        assert!(templates < sources && sources < configuration);
        assert_eq!(workspace.matches("scrolling_panel(").count(), 3);
        assert!(workspace.contains("h_resizable(\"capture-horizontal-layout\")"));
        assert_eq!(workspace.matches("resizable_panel()").count(), 3);
        assert_eq!(workspace.matches(".size_range(").count(), 3);
        assert!(workspace.contains(".with_state(&snapshot.panel_state)"));
        assert!(!workspace.contains("vertical_divider("));
        assert!(!workspace.contains("narrow_workspace"));
        assert!(workspace.contains(".overflow_x_scroll()"));
        assert!(!workspace.contains("capture-device-categories"));
        assert!(!workspace.contains("capture-select-all-categories"));
    }

    #[test]
    fn three_column_capture_preserves_usable_widths_at_every_viewport() {
        for available in [0., 320., 580., 640., 900., 1320., 1920.] {
            let (templates, sources) = capture_panel_widths(available);
            let content = available.max(templates + sources + CONFIGURATION_MIN_WIDTH);
            assert!((170. ..=210.).contains(&templates));
            assert!(sources >= 280.);
            assert!(content - templates - sources >= CONFIGURATION_MIN_WIDTH);
            assert!(content >= available);
        }
    }

    #[test]
    fn capture_defaults_prioritize_data_sources_over_configuration() {
        for available in [1_000., 1_090., 1_258., 1_320., 1_920.] {
            let (templates, sources) = capture_panel_widths(available);
            let configuration = available - templates - sources;
            assert!(sources > configuration);
            assert_eq!(configuration, 360.);
        }
    }

    #[test]
    fn capture_split_state_belongs_to_the_page_not_the_recording() {
        let page = include_str!("page.rs");
        let state = page.split("impl PageUi").next().unwrap();
        assert!(state.contains("capture_panels: Entity<ResizableState>"));
        assert!(page.contains("capture_panels: cx.new(|_| ResizableState::default())"));
        let source = include_str!("capture_page.rs");
        let implementation = source.split("#[cfg(test)]").next().unwrap();
        assert!(implementation.contains("panel_state: this.ui.capture_panels.clone()"));
    }

    #[test]
    fn custom_editor_has_stable_responsive_height_bounds() {
        assert_eq!(custom_config_height(0.), 280.);
        assert_eq!(custom_config_height(400.), 280.);
        assert_eq!(custom_config_height(720.), 360.);
        assert_eq!(custom_config_height(900.), 450.);
        assert_eq!(custom_config_height(1600.), 560.);
    }

    #[test]
    fn integrity_labels_bound_the_success_claim_in_both_languages() {
        for (key, english, chinese) in [
            (
                TextKey::TraceCaptureIntegrityPassed,
                "Available checks passed",
                "\u{5df2}\u{901a}\u{8fc7}\u{53ef}\u{7528}\u{68c0}\u{67e5}",
            ),
            (
                TextKey::TraceCaptureIntegrityIssues,
                "Issues found",
                "\u{53d1}\u{73b0}\u{95ee}\u{9898}",
            ),
            (
                TextKey::TraceCaptureIntegrityUnverified,
                "Not verified",
                "\u{672a}\u{9a8c}\u{8bc1}",
            ),
        ] {
            assert_eq!(Language::English.text(key), english);
            assert_eq!(Language::SimplifiedChinese.text(key), chinese);
        }
    }

    #[test]
    fn configuration_controls_only_delegate_to_capture_runtime() {
        let source = include_str!("capture_page.rs");
        let controls = source
            .split("fn config_controls(")
            .nth(1)
            .unwrap()
            .split("fn custom_config_height(")
            .next()
            .unwrap();
        for call in [
            "this.capture.import_config(window, cx)",
            "this.capture.save_config(cx)",
            "this.capture.reset_config(window, cx)",
            "this.capture.run_preflight(cx)",
        ] {
            assert!(controls.contains(call), "missing runtime action: {call}");
        }
        assert_eq!(controls.matches("if this.capture.is_active()").count(), 4);
        assert!(!controls.contains("start_capture("));
        assert!(!controls.contains("std::fs"));
    }

    #[test]
    fn custom_trace_config_is_not_rewritten_by_a_category_editor() {
        let source = include_str!("capture_page.rs");
        let implementation = source.split("#[cfg(test)]").next().unwrap();
        assert!(!implementation.contains("device_categories("));
        assert!(!implementation.contains("capture-device-category"));
        assert!(!implementation.contains("capture-select-all-categories"));
        assert!(!implementation.contains("capture-apply-categories"));
        assert!(!implementation.contains("apply_categories(window, cx)"));
    }

    #[test]
    fn custom_config_is_authoritative_and_inputs_lock_during_capture() {
        let source = include_str!("capture_page.rs");
        let configuration = source
            .split("fn configuration_content(")
            .nth(1)
            .unwrap()
            .split("fn config_controls(")
            .next()
            .unwrap();
        let branches = configuration
            .split("if snapshot.template == CaptureTemplate::Custom")
            .nth(1)
            .unwrap();
        let (custom, generated) = branches.split_once("} else {").unwrap();
        assert!(custom.contains("config_controls(snapshot, entity)"));
        assert!(custom.contains("snapshot.config_notice"));
        assert!(custom.contains("Textarea::new(&inputs.custom_config)"));
        assert!(custom.contains(".readonly(snapshot.active)"));
        assert!(!generated.contains("config_controls("));
        assert!(!generated.contains("snapshot.config_notice"));
        let before_custom = configuration
            .split("if snapshot.template == CaptureTemplate::Custom")
            .next()
            .unwrap();
        assert!(!before_custom.contains("config_controls("));
        assert!(!before_custom.contains("snapshot.config_notice"));
        for field in [
            "target_package",
            "duration",
            "buffer_mb",
            "additional_categories",
        ] {
            assert!(!custom.contains(&format!("&inputs.{field}")));
            assert!(generated.contains(&format!("Input::new(&inputs.{field})")));
        }
        assert_eq!(generated.matches(".readonly(snapshot.active)").count(), 4);
        let snapshot = source.split("struct CaptureSnapshot").next().unwrap();
        assert!(snapshot.contains(".is_some_and(PreflightReport::can_start)"));
    }
}
