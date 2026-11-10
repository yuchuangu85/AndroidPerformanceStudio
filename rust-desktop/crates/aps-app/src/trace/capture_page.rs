//! Compose PerfettoCapturePage parity.
//!
//! Three panels exactly like the Compose capture page: trace templates, data
//! source/probe groups (capability-aware), and the configuration form with
//! Start/Stop capture, status and the completed-trace action. The page never
//! runs a device command by itself; every action goes through Capture/Lead
//! runtime methods. Wide windows use the Compose column split; narrow windows
//! stack the same sections in one scroll.

use super::TraceWorkspace;
use super::capture::{
    CaptureState, CaptureTemplate, DeviceCapabilities, Probe, ProbeGroup, format_unix_ms,
};
use crate::studio::{self, FONT_BODY, FONT_SUPPORTING, StudioColors};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    component::{
        Disableable, Sizable,
        checkbox::Checkbox,
        input::{Input, InputState, Textarea, TextareaState},
    },
    prelude::*,
    *,
};
use std::collections::BTreeSet;

const TEMPLATE_PANEL_WIDTH: f32 = 190.;
const DATA_SOURCES_PANEL_WIDTH: f32 = 300.;
const WIDE_CAPTURE_BREAKPOINT: f32 = 780.;
const CUSTOM_CONFIG_HEIGHT: f32 = 150.;

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
    let colors = StudioColors::from_theme(cx);
    let entity = cx.entity().downgrade();
    let inputs = this.capture.inputs();
    let custom_config_ready = this.capture.selected_template() != CaptureTemplate::Custom
        || inputs.is_some_and(|inputs| !inputs.custom_config.read(cx).value().trim().is_empty());
    let target_package_blank =
        inputs.is_none_or(|inputs| inputs.target_package.read(cx).value().trim().is_empty());
    let snapshot = CaptureSnapshot {
        language,
        colors,
        template: this.capture.selected_template(),
        probes: this.capture.enabled_probes().clone(),
        capabilities: this.capture.capabilities().cloned(),
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
            && !this.capture.is_active(),
    };
    container_query(move |size, _, _| capture_workspace(f32::from(size.width), &snapshot, &entity))
        .size_full()
        .into_any_element()
}

struct CaptureSnapshot {
    language: Language,
    colors: StudioColors,
    template: CaptureTemplate,
    probes: BTreeSet<Probe>,
    capabilities: Option<DeviceCapabilities>,
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
    snapshot: &CaptureSnapshot,
    entity: &WeakEntity<TraceWorkspace>,
) -> AnyElement {
    if available >= WIDE_CAPTURE_BREAKPOINT {
        wide_workspace(snapshot, entity)
    } else {
        narrow_workspace(snapshot, entity)
    }
}

fn wide_workspace(snapshot: &CaptureSnapshot, entity: &WeakEntity<TraceWorkspace>) -> AnyElement {
    div()
        .id("trace-capture-workspace")
        .size_full()
        .flex()
        .child(
            scrolling_panel(
                "capture-template-panel",
                snapshot.language.text(TextKey::TraceTemplate),
                snapshot.colors,
                template_content(snapshot, entity),
            )
            .w(px(TEMPLATE_PANEL_WIDTH)),
        )
        .child(vertical_divider(&snapshot.colors))
        .child(
            scrolling_panel(
                "capture-data-sources-panel",
                snapshot.language.text(TextKey::DataSources),
                snapshot.colors,
                data_sources_content(snapshot, entity),
            )
            .w(px(DATA_SOURCES_PANEL_WIDTH)),
        )
        .child(vertical_divider(&snapshot.colors))
        .child(
            scrolling_panel(
                "capture-configuration-panel",
                snapshot.language.text(TextKey::Configuration),
                snapshot.colors,
                configuration_content(snapshot, entity),
            )
            .flex_1()
            .min_w_0(),
        )
        .into_any_element()
}

fn narrow_workspace(snapshot: &CaptureSnapshot, entity: &WeakEntity<TraceWorkspace>) -> AnyElement {
    div()
        .id("capture-narrow-scroll")
        .size_full()
        .overflow_y_scroll()
        .p(px(6.))
        .flex()
        .flex_col()
        .gap(px(8.))
        .child(section_card(
            "capture-narrow-template",
            snapshot.language.text(TextKey::TraceTemplate),
            snapshot.colors,
            template_content(snapshot, entity),
        ))
        .child(section_card(
            "capture-narrow-data-sources",
            snapshot.language.text(TextKey::DataSources),
            snapshot.colors,
            data_sources_content(snapshot, entity),
        ))
        .child(section_card(
            "capture-narrow-configuration",
            snapshot.language.text(TextKey::Configuration),
            snapshot.colors,
            configuration_content(snapshot, entity),
        ))
        .into_any_element()
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
        .min_h_0()
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

fn section_card(
    id: &'static str,
    title: impl Into<SharedString>,
    colors: StudioColors,
    content: AnyElement,
) -> Stateful<Div> {
    div()
        .id(id)
        .w_full()
        .flex()
        .flex_col()
        .border_1()
        .border_color(colors.border)
        .rounded(px(8.))
        .overflow_hidden()
        .child(panel_header(title, colors))
        .child(studio::divider(&colors))
        .child(div().p(px(6.)).child(content))
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

fn vertical_divider(colors: &StudioColors) -> Div {
    div().w(px(1.)).h_full().flex_shrink_0().bg(colors.border)
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
) -> Stateful<Div> {
    let selected = snapshot.template == template;
    let colors = snapshot.colors;
    let language = snapshot.language;
    let entity = entity.clone();
    div()
        .id(("capture-template", template as usize))
        .w_full()
        .flex()
        .flex_col()
        .gap(px(2.))
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
        .cursor_pointer()
        .child(
            div()
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(if selected {
                    colors.accent_text
                } else {
                    colors.accent
                })
                .child(crate::studio::selectable(language.text(template.label()))),
        )
        .child(
            div()
                .text_size(px(FONT_SUPPORTING))
                .text_color(if selected {
                    colors.accent_text
                } else {
                    colors.secondary_text
                })
                .child(crate::studio::selectable(
                    language.text(template.description()),
                )),
        )
        .on_click(move |_, _, cx| {
            let _ = entity.update(cx, |this, cx| {
                this.capture.select_template(template);
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
    let reason = snapshot
        .capabilities
        .as_ref()
        .and_then(|capabilities| capabilities.unsupported_reason(probe))
        .or_else(|| {
            (probe.requires_target_package() && snapshot.target_package_blank)
                .then(|| "Requires a target package".to_owned())
        });
    let disabled = reason.is_some();
    let checked = snapshot.probes.contains(&probe) && !disabled;
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
    if let Some(reason) = reason {
        label = label.child(
            div()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.error)
                .child(crate::studio::selectable(reason)),
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
                .disabled(disabled)
                .on_change(move |checked, _, cx| {
                    let _ = entity.update(cx, |this, cx| {
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

    if let Some(inputs) = &snapshot.inputs {
        column = column
            .child(field_label(language.text(TextKey::TargetPackage), colors))
            .child(Input::new(&inputs.target_package).w_full())
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
                            .child(Input::new(&inputs.duration).w_full()),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .flex()
                            .flex_col()
                            .gap(px(2.))
                            .child(field_label(language.text(TextKey::BufferMb), colors))
                            .child(Input::new(&inputs.buffer_mb).w_full()),
                    ),
            )
            .child(field_label(
                language.text(TextKey::AdditionalCategoriesEvents),
                colors,
            ))
            .child(Input::new(&inputs.additional_categories).w_full());
        if snapshot.template == CaptureTemplate::Custom {
            column = column
                .child(field_label(
                    language.text(TextKey::CustomTraceconfigTextProtobuf),
                    colors,
                ))
                .child(
                    Textarea::new(&inputs.custom_config)
                        .h(px(CUSTOM_CONFIG_HEIGHT))
                        .w_full(),
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
    column.into_any_element()
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
