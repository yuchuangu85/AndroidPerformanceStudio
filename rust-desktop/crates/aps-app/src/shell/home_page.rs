//! Compose-shaped dashboard backed only by published Rust device/history facts.
use super::*;
use crate::{
    home_recent::{HomeRecentSnapshot, HomeRecentSource},
    studio::{self, StudioColors},
    targets::HomeDeviceOverview,
};
use aps_adb::DeviceState;
use gpui_kit::component::calendar::CalendarState;

const RAIL_WIDTH: f32 = 320.;
const TWO_COLUMN_MIN_WIDTH: f32 = 900.;
const MODULE_CARD_HEIGHT: f32 = 78.;
const QUICK_ACTION_HEIGHT: f32 = 92.;
const GAP: f32 = studio::metrics::CARD_GAP;
const RECENT_WEIGHTS: [f32; 5] = [2.2, 1.6, 0.85, 1.3, 0.8];

#[derive(Clone, Debug)]
pub(super) enum HomeRecentState {
    Loading,
    Available(HomeRecentSnapshot),
    Unavailable(String),
}

#[derive(Clone, Copy)]
struct HomeModule {
    destination: Destination,
    description: TextKey,
}
const MODULES: [HomeModule; 11] = [
    HomeModule {
        destination: Destination::LayoutInspector,
        description: TextKey::HomeLayoutDescription,
    },
    HomeModule {
        destination: Destination::Cpu,
        description: TextKey::HomeCpuDescription,
    },
    HomeModule {
        destination: Destination::Trace,
        description: TextKey::HomeTraceDescription,
    },
    HomeModule {
        destination: Destination::Memory,
        description: TextKey::HomeMemoryDescription,
    },
    HomeModule {
        destination: Destination::Frame,
        description: TextKey::HomeFrameDescription,
    },
    HomeModule {
        destination: Destination::Startup,
        description: TextKey::HomeStartupDescription,
    },
    HomeModule {
        destination: Destination::Battery,
        description: TextKey::HomeBatteryDescription,
    },
    HomeModule {
        destination: Destination::Network,
        description: TextKey::HomeNetworkDescription,
    },
    HomeModule {
        destination: Destination::Gpu,
        description: TextKey::HomeGpuDescription,
    },
    HomeModule {
        destination: Destination::Benchmark,
        description: TextKey::HomeBenchmarkDescription,
    },
    HomeModule {
        destination: Destination::SourceWorkspaces,
        description: TextKey::HomeSourceDescription,
    },
];

const PLAY: &[u8] = br##"<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none">
  <path d="M5 3.5h14A1.5 1.5 0 0 1 20.5 5v14a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 19V5A1.5 1.5 0 0 1 5 3.5Z" stroke="#000000" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M10 8.5 16 12l-6 3.5Z" fill="#000000"/>
</svg>"##;
const IMPORT: &[u8] = br##"<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none">
  <path d="M4 15v3.5A1.5 1.5 0 0 0 5.5 20h13a1.5 1.5 0 0 0 1.5-1.5V15" stroke="#000000" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M12 4v10.5" stroke="#000000" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M8 11l4 3.5 4-3.5" stroke="#000000" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
</svg>"##;
const DEVICE: &[u8] = br##"<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none">
  <path d="M9 3.5h6A1.5 1.5 0 0 1 16.5 5v14a1.5 1.5 0 0 1-1.5 1.5H9A1.5 1.5 0 0 1 7.5 19V5A1.5 1.5 0 0 1 9 3.5Z" stroke="#000000" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M11 17.5h2" stroke="#000000" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
</svg>"##;
const HISTORY: &[u8] = br##"<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none">
  <path d="M4.5 12a7.5 7.5 0 1 0 15 0 7.5 7.5 0 1 0-15 0Z" stroke="#000000" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
  <path d="M12 7.5V12l3 2" stroke="#000000" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/>
</svg>"##;

#[derive(Clone, Copy)]
struct QuickAction {
    title: TextKey,
    description: TextKey,
    destination: Destination,
    import: bool,
    icon: &'static [u8],
}
const QUICK_ACTIONS: [QuickAction; 4] = [
    QuickAction {
        title: TextKey::HomeStartCapture,
        description: TextKey::HomeStartCaptureDescription,
        destination: Destination::Trace,
        import: false,
        icon: PLAY,
    },
    QuickAction {
        title: TextKey::HomeImportTrace,
        description: TextKey::HomeImportTraceDescription,
        destination: Destination::Trace,
        import: true,
        icon: IMPORT,
    },
    QuickAction {
        title: TextKey::HomeAnalyzeHprof,
        description: TextKey::HomeAnalyzeHprofDescription,
        destination: Destination::Memory,
        import: true,
        icon: include_bytes!("../../assets/navigation/ic_nav_memory_profiler.svg"),
    },
    QuickAction {
        title: TextKey::HomeStartupTest,
        description: TextKey::HomeStartupTestDescription,
        destination: Destination::Startup,
        import: false,
        icon: include_bytes!("../../assets/navigation/ic_nav_startup_profiler.svg"),
    },
];

fn module_columns(width: f32) -> usize {
    if width >= 820. {
        3
    } else if width >= 560. {
        2
    } else {
        1
    }
}
fn quick_columns(width: f32) -> usize {
    if width >= 720. {
        4
    } else if width >= 380. {
        2
    } else {
        1
    }
}
fn icon(data: &'static [u8], color: Rgba, size: f32) -> Svg {
    svg().data(data).size(px(size)).text_color(color)
}

impl Studio {
    pub(super) fn home(&self, window: &mut Window, cx: &mut Context<Self>) -> AnyElement {
        let language = self.settings.language.resolved();
        let colors = StudioColors::from_theme(cx);
        let devices = self.targets.read(cx).home_overview();
        let recent = self.home_recent.clone();
        let entity = cx.entity();
        // CalendarState is the already-pinned GPUI local-date adapter; no calendar
        // UI is mounted and no new dependency, process or network read is added.
        let calendar = cx.new(|cx| CalendarState::new(window, cx));
        let today = calendar.read(cx).today().format("%-m %-d %u").to_string();
        let date = date_label(language, &today);
        container_query(move |size, _, _| {
            let available = (f32::from(size.width) - 8.).max(0.);
            let wide = available >= TWO_COLUMN_MIN_WIDTH;
            let main_width = if wide {
                available - RAIL_WIDTH - GAP
            } else {
                available
            };
            let main = main_column(main_width, language, &colors, &devices, &recent, &entity);
            let rail = rail_column(
                if wide { RAIL_WIDTH } else { available },
                language,
                &colors,
                date,
                &devices,
            );
            let content = div().flex().items_start().gap(px(GAP)).w_full();
            div()
                .id("home-dashboard-scroll")
                .size_full()
                .overflow_y_scroll()
                .p(px(4.))
                .bg(colors.workspace)
                .text_color(colors.text)
                .text_size(px(studio::FONT_BODY))
                .line_height(px(18.))
                .child(if wide {
                    content.child(main).child(rail)
                } else {
                    content.flex_col().child(main).child(rail)
                })
        })
        .size_full()
        .into_any_element()
    }
}

fn main_column(
    width: f32,
    language: Language,
    colors: &StudioColors,
    devices: &HomeDeviceOverview,
    recent: &HomeRecentState,
    entity: &Entity<Studio>,
) -> Div {
    let devices_value = match devices {
        HomeDeviceOverview::Available(items) => items
            .iter()
            .filter(|device| device.is_online())
            .count()
            .to_string(),
        _ => "—".into(),
    };
    let recent_value = match recent {
        HomeRecentState::Available(snapshot)
            if snapshot.unavailable_source_count < snapshot.source_count =>
        {
            snapshot.items.len().min(6).to_string()
        }
        _ => "—".into(),
    };
    div()
        .w(px(width))
        .flex_shrink_0()
        .min_w_0()
        .flex()
        .flex_col()
        .gap(px(GAP))
        .child(
            div()
                .flex()
                .items_stretch()
                .gap(px(GAP))
                .child(metric(
                    language.text(TextKey::HomeConnectedDevices),
                    devices_value,
                    None,
                    DEVICE,
                    colors.info,
                    colors,
                ))
                .child(metric(
                    language.text(TextKey::HomeRecentArtifacts),
                    recent_value,
                    Some(language.text(TextKey::HomeRecentScope)),
                    HISTORY,
                    colors.accent,
                    colors,
                )),
        )
        .child(
            section(language.text(TextKey::HomeQuickActions), colors)
                .child(quick_grid(width, language, colors, entity)),
        )
        .child(
            section(language.text(TextKey::HomeModules), colors)
                .child(module_grid(width, language, colors, entity)),
        )
        .child(recent_panel(width, language, colors, recent, entity))
}

fn metric(
    label: &'static str,
    value: String,
    supporting: Option<&'static str>,
    image: &'static [u8],
    tone: Rgba,
    colors: &StudioColors,
) -> Div {
    studio::card(colors)
        .flex_1()
        .min_w_0()
        .gap(px(4.))
        .bg(colors.panel)
        .border_color(tone.alpha(0.5))
        .child(
            div()
                .flex()
                .items_center()
                .gap(px(8.))
                .child(icon(image, tone, 18.))
                .child(
                    div()
                        .min_w_0()
                        .text_size(px(13.))
                        .line_height(px(17.))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(tone)
                        .child(crate::studio::selectable(label)),
                ),
        )
        .child(
            div()
                .text_size(px(16.))
                .line_height(px(21.))
                .font_weight(FontWeight::SEMIBOLD)
                .child(crate::studio::selectable(value)),
        )
        .children(supporting.map(|text| studio::supporting(text, colors).line_height(px(15.))))
}

fn section(title: &'static str, colors: &StudioColors) -> Div {
    div()
        .flex()
        .flex_col()
        .gap(px(GAP))
        .child(studio::section_title(title, colors))
}

fn home_card(id: impl Into<ElementId>, width: f32, height: f32, colors: &StudioColors) -> Button {
    Button::new(id)
        .ghost()
        .w(px(width))
        .h(px(height))
        .flex_shrink_0()
        .p_0()
        .justify_start()
        .rounded(px(8.))
        .bg(colors.panel)
        .border_1()
        .border_color(colors.border.alpha(0.72))
        // Component Button owns its hover/active registrations. Registering a
        // second hover style here panics during the first debug-build render.
        .shadow_sm()
}

fn card_content(
    image: &'static [u8],
    title: &'static str,
    description: &'static str,
    quick: bool,
    colors: &StudioColors,
) -> Div {
    div()
        .size_full()
        .flex()
        .items_center()
        .gap(px(if quick { 12. } else { 11. }))
        .px(px(if quick { 16. } else { 14. }))
        .py(px(if quick { 14. } else { 12. }))
        .child(
            div()
                .size(px(if quick { 40. } else { 34. }))
                .flex_shrink_0()
                .flex()
                .items_center()
                .justify_center()
                .rounded(px(if quick { 10. } else { 9. }))
                .bg(colors.accent.alpha(if colors.dark {
                    if quick { 0.24 } else { 0.22 }
                } else {
                    0.12
                }))
                .child(icon(image, colors.accent, if quick { 20. } else { 18. })),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .gap(px(if quick { 3. } else { 2. }))
                .child(
                    div()
                        .text_size(px(16.))
                        .line_height(px(19.))
                        .font_weight(FontWeight::SEMIBOLD)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(crate::studio::selectable(title)),
                )
                .child(
                    div()
                        .text_size(px(11.))
                        .line_height(px(15.))
                        .text_color(colors.secondary_text)
                        .line_clamp(2)
                        .child(crate::studio::selectable(description)),
                ),
        )
}

fn quick_grid(
    width: f32,
    language: Language,
    colors: &StudioColors,
    entity: &Entity<Studio>,
) -> Div {
    let columns = quick_columns(width);
    let card_width = ((width - GAP * (columns - 1) as f32) / columns as f32).max(0.);
    div()
        .flex()
        .flex_col()
        .gap(px(GAP))
        .children(
            QUICK_ACTIONS
                .chunks(columns)
                .enumerate()
                .map(|(row, actions)| {
                    div()
                        .flex()
                        .gap(px(GAP))
                        .children(actions.iter().enumerate().map(|(column, action)| {
                            let action = *action;
                            let entity = entity.clone();
                            home_card(
                                ("home-quick", row * columns + column),
                                card_width,
                                QUICK_ACTION_HEIGHT,
                                colors,
                            )
                            .accessibility_label(language.text(action.title))
                            .child(card_content(
                                action.icon,
                                language.text(action.title),
                                language.text(action.description),
                                true,
                                colors,
                            ))
                            .on_click(move |_, _, cx| {
                                entity.update(cx, |this, cx| {
                                    if action.import {
                                        this.open_home_import(action.destination, cx);
                                    } else if action.destination == Destination::Trace {
                                        this.navigate(action.destination, cx);
                                        this.trace
                                            .update(cx, |trace, cx| trace.open_capture_panel(cx));
                                    } else {
                                        this.navigate(action.destination, cx);
                                    }
                                })
                            })
                        }))
                }),
        )
}

fn module_grid(
    width: f32,
    language: Language,
    colors: &StudioColors,
    entity: &Entity<Studio>,
) -> Div {
    let columns = module_columns(width);
    let card_width = ((width - GAP * (columns - 1) as f32) / columns as f32).max(0.);
    div()
        .flex()
        .flex_col()
        .gap(px(GAP))
        .children(MODULES.chunks(columns).enumerate().map(|(row, modules)| {
            div()
                .flex()
                .gap(px(GAP))
                .children(modules.iter().enumerate().map(|(column, module)| {
                    let destination = module.destination;
                    let entity = entity.clone();
                    home_card(
                        ("home-module", row * columns + column),
                        card_width,
                        MODULE_CARD_HEIGHT,
                        colors,
                    )
                    .accessibility_label(destination.label(language))
                    .child(card_content(
                        navigation_icon_data(destination),
                        destination.label(language),
                        language.text(module.description),
                        false,
                        colors,
                    ))
                    .on_click(move |_, _, cx| {
                        entity.update(cx, |this, cx| this.navigate(destination, cx))
                    })
                }))
        }))
}

fn recent_cell(
    width: f32,
    text: impl Into<SharedString>,
    supporting: bool,
    colors: &StudioColors,
) -> Div {
    div()
        .w(px(width))
        .flex_shrink_0()
        .min_w_0()
        .text_size(px(if supporting { 11. } else { 13. }))
        .line_height(px(if supporting { 15. } else { 18. }))
        .text_color(if supporting {
            colors.secondary_text
        } else {
            colors.text
        })
        .whitespace_nowrap()
        .text_ellipsis()
        .child(crate::studio::selectable(text.into()))
}

fn recent_panel(
    width: f32,
    language: Language,
    colors: &StudioColors,
    recent: &HomeRecentState,
    entity: &Entity<Studio>,
) -> Div {
    // Shared header/body weights, with a bounded horizontal scroll surface when
    // the five real columns cannot fit. No invented duration/device/status column.
    let table_width = (width - 16.).max(540.);
    let columns = RECENT_WEIGHTS
        .map(|weight| (table_width - 16.) * weight / RECENT_WEIGHTS.iter().sum::<f32>());
    let mut table = div()
        .w(px(table_width))
        .flex()
        .flex_col()
        .gap(px(4.))
        .child(
            div()
                .flex()
                .px(px(8.))
                .py(px(6.))
                .bg(colors.section_background)
                .children(
                    [
                        TextKey::HomeRecentName,
                        TextKey::HomeRecentPackage,
                        TextKey::HomeRecentType,
                        TextKey::HomeRecentCaptured,
                        TextKey::HomeRecentAction,
                    ]
                    .into_iter()
                    .enumerate()
                    .map(|(index, key)| {
                        recent_cell(columns[index], language.text(key), true, colors)
                            .font_weight(FontWeight::SEMIBOLD)
                    }),
                ),
        );
    match recent {
        HomeRecentState::Loading => {
            table = table.child(state_message(
                language.text(TextKey::HomeRecentSessions),
                language.text(TextKey::HomeRecentLoading),
                false,
                colors,
            ));
        }
        HomeRecentState::Unavailable(error) => {
            table = table.child(state_message(
                language.text(TextKey::HomeRecentUnavailable),
                error,
                true,
                colors,
            ));
        }
        HomeRecentState::Available(snapshot) => {
            let items = &snapshot.items;
            if snapshot.unavailable_source_count == snapshot.source_count {
                table = table.child(state_message(
                    language.text(TextKey::HomeRecentUnavailable),
                    language.text(TextKey::HomeRecentPartial),
                    true,
                    colors,
                ));
            } else if items.is_empty() {
                table = table.child(state_message(
                    language.text(TextKey::HomeRecentEmpty),
                    language.text(TextKey::HomeRecentEmptyHint),
                    false,
                    colors,
                ));
            } else {
                for (index, item) in items.iter().take(6).enumerate() {
                    let open_item = item.clone();
                    let entity = entity.clone();
                    let captured = item
                        .captured_at_unix_ms
                        .and_then(|value| u64::try_from(value).ok())
                        .map(crate::trace::format_unix_ms)
                        .unwrap_or_else(|| "—".to_owned());
                    let type_key = match item.source {
                        HomeRecentSource::Cpu => TextKey::HomeCpuType,
                        HomeRecentSource::Layout => TextKey::HomeLayoutType,
                        HomeRecentSource::Trace => TextKey::HomeTraceType,
                        HomeRecentSource::Memory => TextKey::HomeMemoryType,
                    };
                    table = table.child(
                        div()
                            .flex()
                            .items_center()
                            .px(px(8.))
                            .py(px(3.))
                            .child(recent_cell(columns[0], item.title.clone(), false, colors))
                            .child(recent_cell(
                                columns[1],
                                item.package_name.clone().unwrap_or_else(|| "—".into()),
                                true,
                                colors,
                            ))
                            .child(div().w(px(columns[2])).flex_shrink_0().child(status_chip(
                                language.text(type_key),
                                colors.secondary_text,
                                colors,
                            )))
                            .child(recent_cell(columns[3], captured, true, colors))
                            .child(
                                div()
                                    .w(px(columns[4]))
                                    .flex_shrink_0()
                                    .flex()
                                    .justify_end()
                                    .child(
                                        Button::new(("home-open-recent", index))
                                            .ghost()
                                            .h(px(21.))
                                            .px(px(6.))
                                            .rounded(px(4.))
                                            .text_color(colors.accent)
                                            .child(
                                                div()
                                                    .text_size(px(11.))
                                                    .line_height(px(15.))
                                                    .child(crate::studio::selectable(
                                                        language.text(TextKey::HomeOpenArtifact),
                                                    )),
                                            )
                                            .accessibility_label(format!(
                                                "{} {}",
                                                language.text(TextKey::HomeOpenArtifact),
                                                item.title
                                            ))
                                            .on_click(move |_, _, cx| {
                                                entity.update(cx, |this, cx| {
                                                    this.open_home_recent(open_item.clone(), cx)
                                                })
                                            }),
                                    ),
                            ),
                    );
                }
            }
        }
    }
    studio::card(colors)
        .gap(px(4.))
        .child(
            div()
                .h(px(36.))
                .flex()
                .items_center()
                .child(studio::section_title(
                    language.text(TextKey::HomeRecentSessions),
                    colors,
                )),
        )
        .children(matches!(recent, HomeRecentState::Available(snapshot)
            if snapshot.unavailable_source_count > 0 && snapshot.unavailable_source_count < snapshot.source_count)
            .then(|| studio::supporting(language.text(TextKey::HomeRecentPartial), colors)))
        .child(
            div()
                .id("home-recent-table-scroll")
                .w_full()
                .overflow_x_scroll()
                .child(table),
        )
}

fn state_message(title: &str, detail: &str, error: bool, colors: &StudioColors) -> Div {
    div()
        .p(px(16.))
        .flex()
        .flex_col()
        .gap(px(4.))
        .child(
            div()
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(if error { colors.error } else { colors.text })
                .child(crate::studio::selectable(title.to_owned())),
        )
        .child(studio::supporting(detail.to_owned(), colors))
}

fn status_chip(label: &'static str, tone: Rgba, colors: &StudioColors) -> Div {
    div()
        .px(px(6.))
        .py(px(2.))
        .rounded(px(4.))
        .bg(tone.alpha(0.12))
        .text_color(if tone == colors.secondary_text {
            colors.secondary_text
        } else {
            tone
        })
        .text_size(px(11.))
        .whitespace_nowrap()
        .child(crate::studio::selectable(label))
}

fn rail_column(
    width: f32,
    language: Language,
    colors: &StudioColors,
    date: String,
    devices: &HomeDeviceOverview,
) -> Div {
    let mut device_panel = studio::card(colors).gap(px(8.));
    match devices {
        HomeDeviceOverview::Loading => {
            device_panel = device_panel.child(state_message(
                language.text(TextKey::HomeConnectedDevices),
                language.text(TextKey::HomeDevicesLoading),
                false,
                colors,
            ));
        }
        HomeDeviceOverview::Unavailable(error) => {
            device_panel = device_panel.child(state_message(
                language.text(TextKey::HomeDevicesUnavailable),
                error,
                true,
                colors,
            ));
        }
        HomeDeviceOverview::Available(items) => {
            device_panel = device_panel.child(div().h(px(36.)).flex().items_center().child(
                studio::section_title(language.text(TextKey::HomeConnectedDevices), colors),
            ));
            if items.is_empty() {
                device_panel = device_panel.child(studio::supporting(
                    language.text(TextKey::HomeNoDevices),
                    colors,
                ));
            }
            for device in items {
                let key = match device.state {
                    DeviceState::Device => TextKey::HomeDeviceOnline,
                    DeviceState::Offline => TextKey::HomeDeviceOffline,
                    DeviceState::Unauthorized => TextKey::HomeDeviceUnauthorized,
                    DeviceState::NoPermissions => TextKey::HomeDeviceNoPermissions,
                    DeviceState::Unknown => TextKey::HomeDeviceUnknown,
                };
                device_panel = device_panel.child(
                    div()
                        .flex()
                        .items_center()
                        .gap(px(8.))
                        .child(icon(
                            DEVICE,
                            if device.is_online() {
                                colors.accent
                            } else {
                                colors.muted_text
                            },
                            18.,
                        ))
                        .child(
                            div()
                                .flex_1()
                                .min_w_0()
                                .flex()
                                .flex_col()
                                .gap(px(2.))
                                .child(
                                    div()
                                        .text_size(px(13.))
                                        .line_height(px(18.))
                                        .whitespace_nowrap()
                                        .text_ellipsis()
                                        .child(device.display_name()),
                                )
                                .child(
                                    div()
                                        .text_size(px(11.))
                                        .line_height(px(15.))
                                        .text_color(colors.secondary_text)
                                        .whitespace_nowrap()
                                        .text_ellipsis()
                                        .child(device.serial.clone()),
                                ),
                        )
                        .child(status_chip(
                            language.text(key),
                            if device.is_online() {
                                colors.success
                            } else {
                                colors.warning
                            },
                            colors,
                        )),
                );
            }
        }
    }
    div()
        .w(px(width))
        .flex_shrink_0()
        .min_w_0()
        .flex()
        .flex_col()
        .gap(px(GAP))
        .child(
            studio::card(colors)
                .gap(px(8.))
                .child(
                    div()
                        .text_size(px(16.))
                        .line_height(px(20.))
                        .font_weight(FontWeight::SEMIBOLD)
                        .child(date),
                )
                .child(studio::supporting(
                    language.text(TextKey::HomeFocus),
                    colors,
                )),
        )
        .child(device_panel)
}

fn date_label(language: Language, raw: &str) -> String {
    let parts: Vec<_> = raw
        .split_whitespace()
        .filter_map(|value| value.parse::<usize>().ok())
        .collect();
    let [month, day, weekday] = parts.as_slice() else {
        return language.text(TextKey::HomeDateUnavailable).to_owned();
    };
    let months = [
        TextKey::HomeMonthJan,
        TextKey::HomeMonthFeb,
        TextKey::HomeMonthMar,
        TextKey::HomeMonthApr,
        TextKey::HomeMonthMay,
        TextKey::HomeMonthJun,
        TextKey::HomeMonthJul,
        TextKey::HomeMonthAug,
        TextKey::HomeMonthSep,
        TextKey::HomeMonthOct,
        TextKey::HomeMonthNov,
        TextKey::HomeMonthDec,
    ];
    let weekdays = [
        TextKey::HomeWeekdayMon,
        TextKey::HomeWeekdayTue,
        TextKey::HomeWeekdayWed,
        TextKey::HomeWeekdayThu,
        TextKey::HomeWeekdayFri,
        TextKey::HomeWeekdaySat,
        TextKey::HomeWeekdaySun,
    ];
    let Some(month) = month.checked_sub(1).and_then(|index| months.get(index)) else {
        return language.text(TextKey::HomeDateUnavailable).to_owned();
    };
    let Some(weekday) = weekday.checked_sub(1).and_then(|index| weekdays.get(index)) else {
        return language.text(TextKey::HomeDateUnavailable).to_owned();
    };
    language.format(
        TextKey::HomeDate,
        &[
            ("month", &language.text(*month)),
            ("day", day),
            ("weekday", &language.text(*weekday)),
        ],
    )
}

#[cfg(test)]
mod tests {
    use super::{
        MODULES, QUICK_ACTIONS, RECENT_WEIGHTS, date_label, module_columns, quick_columns,
    };
    use aps_ui_foundation::{Destination, Language};
    #[::core::prelude::v1::test]
    fn responsive_thresholds_match_reference() {
        for (width, expected) in [(0., 1), (559., 1), (560., 2), (819., 2), (820., 3)] {
            assert_eq!(module_columns(width), expected);
        }
        for (width, expected) in [(0., 1), (379., 1), (380., 2), (719., 2), (720., 4)] {
            assert_eq!(quick_columns(width), expected);
        }
    }
    #[::core::prelude::v1::test]
    fn module_routes_and_recent_columns_match_reference() {
        assert_eq!(
            MODULES.map(|module| module.destination),
            [
                Destination::LayoutInspector,
                Destination::Cpu,
                Destination::Trace,
                Destination::Memory,
                Destination::Frame,
                Destination::Startup,
                Destination::Battery,
                Destination::Network,
                Destination::Gpu,
                Destination::Benchmark,
                Destination::SourceWorkspaces,
            ]
        );
        assert_eq!(
            QUICK_ACTIONS.map(|action| (action.destination, action.import)),
            [
                (Destination::Trace, false),
                (Destination::Trace, true),
                (Destination::Memory, true),
                (Destination::Startup, false),
            ]
        );
        assert_eq!(RECENT_WEIGHTS, [2.2, 1.6, 0.85, 1.3, 0.8]);
    }
    #[::core::prelude::v1::test]
    fn component_buttons_leave_state_style_registration_to_the_component() {
        // GPUI Kit 0.9 registers these styles inside Button::render, so direct
        // registrations in the application panic rather than override them.
        for source in [
            include_str!("home_page.rs"),
            include_str!("../import/network.rs"),
        ] {
            for state in ["hover", "active"] {
                let direct_registration = format!(".{state}(");
                assert!(!source.contains(&direct_registration));
            }
        }
    }

    #[::core::prelude::v1::test]
    fn local_date_copy_is_localized_without_external_lookup() {
        assert_eq!(date_label(Language::English, "11 7 6"), "Nov 7, Sat");
        assert_eq!(
            date_label(Language::SimplifiedChinese, "11 7 6"),
            "11月7日 周六"
        );
    }
}
