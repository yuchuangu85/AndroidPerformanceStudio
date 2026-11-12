//! Reference-shaped settings. Unsupported adapters are disabled, never fake-saved.
use super::*;
use crate::studio::{self, StudioColors};
use aps_ui_foundation::LayoutPreferences;
use gpui_kit::component::{
    input::{Input, InputEvent, InputState},
    radio::Radio,
    slider::{Slider, SliderEvent, SliderState},
    switch::Switch,
};
const GENERAL_DROPDOWN_WIDTH: f32 = 240.;
const GENERAL_CUSTOM_COLOR_WIDTH: f32 = 120.;
/// Width of the custom colour value / restore-default column placed to the
/// right of the gradient picker.
const GENERAL_COLOR_SIDE_WIDTH: f32 = 160.;
/// Non-linguistic color literal placeholder; never translated.
const HEX_COLOR_PLACEHOLDER: &str = "#AARRGGBB";
// Kept verbatim with ComposeCompilerReportGuide: these are target-project
// snippets, not settings saved or executed by the desktop application.
const COMPOSE_REPORT_CONFIGURATION: &str = "composeCompiler {\n    reportsDestination =\n        layout.buildDirectory.dir(\"compose_compiler\")\n    metricsDestination =\n        layout.buildDirectory.dir(\"compose_compiler\")\n}";
const COMPOSE_REPORT_GENERATE_COMMAND: &str = "./gradlew :app:compileReleaseKotlin";

use std::{
    path::Path,
    sync::{Arc, Mutex},
};

pub(super) struct SettingsUi {
    sdk: Entity<InputState>,
    saved_sdk: String,
    sdk_error: Option<String>,
    sdk_busy: bool,
    sdk_task: Option<Task<()>>,
    colors: [Entity<InputState>; 4],
    color_error: Option<usize>,
    hue: Entity<SliderState>,
    hsv: (f32, f32, f32),
    archive: Entity<SliderState>,
    lsp_confirm: bool,
}

impl SettingsUi {
    pub(super) fn new(
        settings: &Settings,
        path: Option<&Path>,
        capture: &Entity<CaptureWorkspace>,
        window: &mut Window,
        cx: &mut Context<Studio>,
        subscriptions: &mut Vec<Subscription>,
    ) -> Self {
        let (saved_sdk, mut sdk_error) = match path
            .and_then(Path::parent)
            .map(|root| load_sdk(&root.join("android-sdk.conf")))
        {
            Some(Ok(value)) => (value, None),
            Some(Err(error)) => (String::new(), Some(error)),
            None => (String::new(), None),
        };
        if !saved_sdk.is_empty()
            && let Err(error) = aps_adb::AdbLocator::configure_sdk(Some(Path::new(&saved_sdk)))
        {
            sdk_error = Some(error);
        }
        let sdk = cx.new(|cx| {
            InputState::new(window, cx)
                .placeholder(settings.language.text(TextKey::SettingsUiSdk))
                .default_value(saved_sdk.clone())
                .validate(|value, _| value.len() <= 4096)
        });
        subscriptions.push(cx.observe(&sdk, |_, _, cx| cx.notify()));
        let colors = [
            settings
                .custom_accent
                .unwrap_or(0xff000000 | settings.accent_color.rgb()),
            settings.layout.normal_color,
            settings.layout.hover_color,
            settings.layout.selected_color,
        ]
        .map(|color| {
            cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(HEX_COLOR_PLACEHOLDER)
                    .default_value(format!("#{color:08X}"))
                    .validate(|value, _| value.len() <= 9)
            })
        });
        for (index, input) in colors.iter().enumerate() {
            subscriptions.push(cx.subscribe_in(
                input,
                window,
                move |this, _, event, window, cx| {
                    if matches!(event, InputEvent::Change) {
                        let text = this.settings_ui.colors[index].read(cx).value().to_string();
                        if let Some(color) = parse_argb(&text, index == 0) {
                            let current = match index {
                                0 => this
                                    .settings
                                    .custom_accent
                                    .unwrap_or(0xff000000 | this.settings.accent_color.rgb()),
                                1 => this.settings.layout.normal_color,
                                2 => this.settings.layout.hover_color,
                                _ => this.settings.layout.selected_color,
                            };
                            this.settings_ui.color_error = None;
                            if color != current {
                                match index {
                                    0 => {
                                        this.settings.custom_accent = Some(color);
                                        this.settings_ui.hsv = rgb_to_hsv(color);
                                        this.settings_ui.hue.update(cx, |slider, cx| {
                                            slider.set_value(this.settings_ui.hsv.0, window, cx)
                                        });
                                    }
                                    1 => this.settings.layout.normal_color = color,
                                    2 => this.settings.layout.hover_color = color,
                                    _ => this.settings.layout.selected_color = color,
                                }
                                this.persist_settings(window, cx);
                            }
                        } else {
                            this.settings_ui.color_error = Some(index);
                        }
                        cx.notify();
                    }
                },
            ));
        }
        let hsv = rgb_to_hsv(
            settings
                .custom_accent
                .unwrap_or(settings.accent_color.rgb()),
        );
        let hue = cx.new(|_| {
            SliderState::new()
                .min(0.)
                .max(360.)
                .step(1.)
                .default_value(hsv.0)
        });
        subscriptions.push(cx.subscribe_in(&hue, window, |this, _, event, window, cx| {
            if let SliderEvent::Change(value) = event {
                this.settings_ui.hsv.0 = value.start();
                this.apply_picker(window, cx);
            }
        }));
        let archive = cx.new(|_| {
            SliderState::new()
                .min(1.)
                .max(10.)
                .step(1.)
                .default_value(settings.layout.archive_multiplier as f32)
        });
        subscriptions.push(
            cx.subscribe_in(&archive, window, |this, _, event, window, cx| {
                if let SliderEvent::Change(value) = event {
                    this.settings.layout.archive_multiplier =
                        value.start().round().clamp(1., 10.) as u8;
                    this.persist_settings(window, cx);
                }
            }),
        );
        // Settings and capture render the same entities. Only whole duration/Hz
        // values update preferences; manual/fractional drafts and periods stay live.
        let [_, rate, duration] = capture.read(cx).sampling_inputs();
        for input in [&rate, &duration] {
            subscriptions.push(
                cx.subscribe_in(input, window, |this, _, event, window, cx| {
                    if matches!(event, InputEvent::Change) {
                        this.persist_capture_defaults(window, cx);
                        cx.notify();
                    }
                }),
            );
        }
        subscriptions.push(cx.subscribe_in(
            capture,
            window,
            |this, _, _: &crate::capture::CaptureDefaultsChanged, window, cx| {
                this.persist_capture_defaults(window, cx);
                cx.notify();
            },
        ));
        subscriptions.push(cx.observe(capture, |this, _, cx| {
            this.refresh_route_menus(cx);
            cx.notify();
        }));
        Self {
            sdk,
            saved_sdk,
            sdk_error,
            sdk_busy: false,
            sdk_task: None,
            colors,
            color_error: None,
            hue,
            hsv,
            archive,
            lsp_confirm: false,
        }
    }
}

impl Studio {
    fn persist_capture_defaults(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let draft = self.capture.read(cx).draft(cx);
        let previous = (
            self.settings.capture_duration_seconds,
            self.settings.capture_frequency_hz,
        );
        let next = capture_defaults_for_draft(&draft, previous);
        if previous != next {
            self.settings.capture_duration_seconds = next.0;
            self.settings.capture_frequency_hz = next.1;
            self.persist_settings(window, cx);
        }
    }

    pub(super) fn settings_page(&self, cx: &mut Context<Self>) -> AnyElement {
        let language = self.settings.language;
        let colors = StudioColors::from_theme(cx);
        let sidebar = div()
            .w(px(176.))
            .flex_shrink_0()
            .h_full()
            .flex()
            .flex_col()
            .gap(px(2.))
            .py(px(8.))
            .border_r_1()
            .border_color(colors.border)
            .children(SettingsPage::ALL.into_iter().map(|page| {
                let asset: &'static [u8] = match page {
                    SettingsPage::General => {
                        include_bytes!("../../assets/navigation/ic_settings_general.svg")
                    }
                    SettingsPage::Configuration => {
                        include_bytes!("../../assets/navigation/ic_settings_configuration.svg")
                    }
                    SettingsPage::LayoutInspector => {
                        include_bytes!("../../assets/navigation/ic_nav_layout_inspector.svg")
                    }
                    SettingsPage::Simpleperf | SettingsPage::CpuCapture => {
                        include_bytes!("../../assets/navigation/ic_nav_cpu_profiler.svg")
                    }
                    SettingsPage::Ai => {
                        include_bytes!("../../assets/navigation/ic_settings_ai.svg")
                    }
                    SettingsPage::About => {
                        include_bytes!("../../assets/navigation/ic_settings_about.svg")
                    }
                };
                let selected = self.settings_page == page;
                Button::new(("settings-page", page as usize))
                    .ghost()
                    .accessibility_label(page.label(language))
                    .w_full()
                    .h(px(32.))
                    .px(px(12.))
                    .justify_start()
                    .rounded(px(4.))
                    .selected(selected)
                    .bg(if selected {
                        colors.accent.alpha(0.18)
                    } else {
                        colors.panel
                    })
                    .text_color(colors.text)
                    .child(
                        // Button centers its inner label wrapper; fill it so
                        // every icon/label starts at the same sidebar inset.
                        div()
                            .flex()
                            .w_full()
                            .min_w_0()
                            .items_center()
                            .gap(px(6.))
                            .child(svg().data(asset).size(px(16.)).flex_shrink_0().text_color(
                                if selected {
                                    colors.accent
                                } else {
                                    colors.secondary_text
                                },
                            ))
                            .child(
                                div()
                                    .flex_1()
                                    .text_left()
                                    .text_size(px(13.))
                                    .font_weight(if selected {
                                        FontWeight::SEMIBOLD
                                    } else {
                                        FontWeight::NORMAL
                                    })
                                    .child(page.label(language)),
                            ),
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.select_settings_page(page);
                        this.refresh_route_menus(cx);
                        cx.notify();
                    }))
            }));
        let content = match self.settings_page {
            SettingsPage::General => self.settings_general(cx),
            SettingsPage::Configuration => self.settings_configuration(cx),
            SettingsPage::LayoutInspector => self.settings_inspector(cx),
            SettingsPage::Simpleperf => self.settings_simpleperf(cx),
            SettingsPage::CpuCapture => self.capture.clone().into_any_element(),
            SettingsPage::Ai => self.settings_ai(cx),
            SettingsPage::About => self.settings_about(cx),
        };
        div()
            .flex()
            .size_full()
            .text_size(px(13.))
            .bg(colors.workspace)
            .text_color(colors.text)
            .child(sidebar)
            .child(
                div()
                    .id("settings-content")
                    .flex_1()
                    .min_w_0()
                    .h_full()
                    .when(
                        !matches!(
                            self.settings_page,
                            SettingsPage::Ai | SettingsPage::CpuCapture
                        ),
                        |element| element.overflow_y_scroll(),
                    )
                    .track_scroll(&self.settings_scroll)
                    .child(content),
            )
            .into_any_element()
    }

    fn settings_general(&self, cx: &mut Context<Self>) -> AnyElement {
        let lang = self.settings.language;
        let colors = StudioColors::from_theme(cx);
        let weak = cx.entity().downgrade();
        let language_menu = dropdown(
            "settings-language",
            format!("{}: {}", lang.text(TextKey::CommonLanguage), lang.label()),
            cx,
        )
        .w(px(GENERAL_DROPDOWN_WIDTH))
        .dropdown_menu({
            let weak = weak.clone();
            move |menu, _, _| {
                Language::ALL
                    .into_iter()
                    .fold(menu.min_w(px(200.)), |menu, value| {
                        let weak = weak.clone();
                        menu.item(PopupMenuItem::new(value.label()).on_click(
                            move |_, window, cx| {
                                let _ = weak.update(cx, |this, cx| {
                                    this.settings.language = value;
                                    this.persist_settings(window, cx);
                                });
                            },
                        ))
                    })
            }
        });
        let theme_menu = dropdown(
            "settings-theme",
            format!(
                "{}: {}",
                lang.text(TextKey::CommonTheme),
                self.settings.theme.label(lang)
            ),
            cx,
        )
        .w(px(GENERAL_DROPDOWN_WIDTH))
        .dropdown_menu({
            let weak = weak.clone();
            move |menu, _, _| {
                [ThemeMode::System, ThemeMode::Light, ThemeMode::Dark]
                    .into_iter()
                    .fold(menu.min_w(px(200.)), |menu, value| {
                        let weak = weak.clone();
                        menu.item(PopupMenuItem::new(value.label(lang)).on_click(
                            move |_, window, cx| {
                                let _ = weak.update(cx, |this, cx| {
                                    this.settings.theme = value;
                                    this.persist_settings(window, cx);
                                });
                            },
                        ))
                    })
            }
        });
        let scale_menu = dropdown(
            "settings-scale",
            format!(
                "{}: {}",
                lang.text(TextKey::ShellDisplayScale),
                self.settings.display_scale.label()
            ),
            cx,
        )
        .w(px(GENERAL_DROPDOWN_WIDTH))
        .dropdown_menu({
            let weak = weak.clone();
            move |menu, _, _| {
                DisplayScale::ALL
                    .into_iter()
                    .fold(menu.min_w(px(200.)), |menu, value| {
                        let weak = weak.clone();
                        menu.item(PopupMenuItem::new(value.label()).on_click(
                            move |_, window, cx| {
                                let _ = weak.update(cx, |this, cx| {
                                    this.settings.display_scale = value;
                                    this.persist_settings(window, cx);
                                });
                            },
                        ))
                    })
            }
        });
        let presets = div()
            .w(px(330.))
            .flex_shrink_0()
            .flex()
            .flex_col()
            .gap(px(4.))
            .children(AccentColor::ALL.chunks(2).enumerate().map(|(row, pair)| {
                div()
                    .flex()
                    .gap(px(4.))
                    .children(pair.iter().enumerate().map(|(column, value)| {
                        let value = *value;
                        Button::new(("settings-preset", row * 2 + column))
                            .ghost()
                            .flex_1()
                            .h(px(38.))
                            .justify_start()
                            .border_1()
                            .rounded(px(6.))
                            .border_color(
                                if self.settings.custom_accent.is_none()
                                    && self.settings.accent_color == value
                                {
                                    colors.accent
                                } else {
                                    colors.border
                                },
                            )
                            .selected(
                                self.settings.custom_accent.is_none()
                                    && self.settings.accent_color == value,
                            )
                            .child(
                                div()
                                    .flex()
                                    .items_center()
                                    .gap(px(8.))
                                    .child(div().size(px(18.)).rounded_full().bg(rgb(value.rgb())))
                                    .child(value.label(lang)),
                            )
                            .on_click(cx.listener(move |this, _, window, cx| {
                                this.settings.accent_color = value;
                                this.settings.custom_accent = None;
                                this.sync_color(0, 0xff000000 | value.rgb(), window, cx);
                                this.persist_settings(window, cx);
                            }))
                    }))
            }));
        // The gradient picker keeps its width; the colour value and the restore
        // action sit in a column to its right instead of below the picker.
        let custom = div()
            .w(px(GENERAL_CUSTOM_COLOR_WIDTH
                + 8.
                + GENERAL_COLOR_SIDE_WIDTH))
            .flex_shrink_0()
            .flex()
            .flex_row()
            .gap(px(8.))
            .child(
                div()
                    .w(px(GENERAL_CUSTOM_COLOR_WIDTH))
                    .flex_shrink_0()
                    .flex()
                    .flex_col()
                    .gap(px(8.))
                    .child(self.sv_picker(cx))
                    .child(
                        div()
                            .relative()
                            .h(px(24.))
                            .w(px(GENERAL_CUSTOM_COLOR_WIDTH))
                            .child(
                                div()
                                    .absolute()
                                    .top(px(3.))
                                    .w_full()
                                    .h(px(18.))
                                    .flex()
                                    .children((0..6).map(|segment| {
                                        div().flex_1().h_full().bg(linear_gradient(
                                            90.,
                                            linear_color_stop(
                                                rgb(hsv_rgb(segment as f32 * 60., 1., 1.)),
                                                0.,
                                            ),
                                            linear_color_stop(
                                                rgb(hsv_rgb((segment + 1) as f32 * 60., 1., 1.)),
                                                1.,
                                            ),
                                        ))
                                    })),
                            )
                            .child(
                                Slider::new(&self.settings_ui.hue)
                                    .bg(transparent_black())
                                    .text_color(rgb(0xffffff))
                                    .accessibility_label(lang.text(TextKey::SettingsUiHue)),
                            ),
                    ),
            )
            .child(
                div()
                    .w(px(GENERAL_COLOR_SIDE_WIDTH))
                    .flex_shrink_0()
                    .flex()
                    .flex_col()
                    .gap(px(8.))
                    .child(self.color_control(0, cx))
                    .child(
                        studio::text_button(
                            "reset-accent",
                            lang.text(TextKey::SettingsUiReset),
                            true,
                            false,
                            &colors,
                        )
                        .on_click(cx.listener(|this, _, window, cx| {
                            this.settings.accent_color = AccentColor::default();
                            this.settings.custom_accent = None;
                            this.sync_color(
                                0,
                                0xff000000 | this.settings.accent_color.rgb(),
                                window,
                                cx,
                            );
                            this.persist_settings(window, cx);
                        })),
                    ),
            );
        page(lang.text(TextKey::CommonGeneral), &colors)
            .child(div().px(px(8.)).child(language_menu))
            .child(div().px(px(8.)).child(theme_menu))
            .child(div().px(px(8.)).child(scale_menu))
            .child(studio::divider(&colors))
            .child(row(
                lang.text(TextKey::SettingsUiLabels),
                Switch::new("settings-sidebar-labels")
                    .checked(!self.settings.navigation_rail_collapsed)
                    .accessibility_label(lang.text(TextKey::SettingsUiLabels))
                    .on_click(cx.listener(|this, checked: &bool, window, cx| {
                        this.settings.navigation_rail_collapsed = !*checked;
                        this.persist_settings(window, cx);
                    })),
            ))
            .child(studio::divider(&colors))
            .child(studio::section_title(
                lang.text(TextKey::SettingsUiAccent),
                &colors,
            ))
            .child(
                div()
                    .id("settings-accent-scroll")
                    .w(relative(0.66))
                    .min_w_0()
                    .overflow_x_scroll()
                    .child(
                        div()
                            .flex()
                            .w_full()
                            .gap(px(12.))
                            .min_w(px(660.))
                            .child(presets)
                            .child(
                                div()
                                    .w(px(studio::metrics::HAIRLINE))
                                    .self_stretch()
                                    .flex_shrink_0()
                                    .bg(colors.border),
                            )
                            .child(custom),
                    ),
            )
            .into_any_element()
    }

    fn settings_configuration(&self, cx: &mut Context<Self>) -> AnyElement {
        let lang = self.settings.language;
        let colors = StudioColors::from_theme(cx);
        let draft = self.settings_ui.sdk.read(cx).value().to_string();
        page(lang.text(TextKey::CommonConfiguration), &colors)
            .child(
                studio::card(&colors)
                    .child(studio::section_title(
                        lang.text(TextKey::SettingsUiSdk),
                        &colors,
                    ))
                    .child(
                        Input::new(&self.settings_ui.sdk)
                            .disabled(self.settings_ui.sdk_busy)
                            .h(px(28.))
                            .rounded(px(4.))
                            .bg(colors.field)
                            .border_1()
                            .border_color(colors.border),
                    )
                    .child(studio::supporting(
                        lang.text(TextKey::SettingsUiSdkHint),
                        &colors,
                    ))
                    .child(
                        div()
                            .flex()
                            .gap(px(8.))
                            .child(
                                studio::text_button(
                                    "sdk-browse",
                                    lang.text(TextKey::SettingsUiBrowse),
                                    !self.settings_ui.sdk_busy,
                                    false,
                                    &colors,
                                )
                                .on_click(cx.listener(|this, _, _, cx| this.pick_sdk(cx))),
                            )
                            .child(
                                studio::text_button(
                                    "sdk-apply",
                                    lang.text(if self.settings_ui.sdk_busy {
                                        TextKey::SettingsUiSaving
                                    } else {
                                        TextKey::SettingsUiApply
                                    }),
                                    !self.settings_ui.sdk_busy
                                        && draft.trim() != self.settings_ui.saved_sdk,
                                    true,
                                    &colors,
                                )
                                .on_click(cx.listener(|this, _, _, cx| this.apply_sdk(None, cx))),
                            )
                            .child(
                                studio::text_button(
                                    "sdk-clear",
                                    lang.text(TextKey::SettingsUiClear),
                                    !self.settings_ui.sdk_busy
                                        && (!draft.is_empty()
                                            || !self.settings_ui.saved_sdk.is_empty()),
                                    false,
                                    &colors,
                                )
                                .on_click(cx.listener(
                                    |this, _, _, cx| this.apply_sdk(Some(String::new()), cx),
                                )),
                            ),
                    )
                    .when_some(self.settings_ui.sdk_error.clone(), |card, error| {
                        card.child(
                            div()
                                .text_color(colors.error)
                                .child(crate::studio::selectable(error)),
                        )
                    }),
            )
            .child(
                studio::card(&colors)
                    .child(studio::section_title(
                        lang.text(TextKey::SettingsUiSemanticNavigation),
                        &colors,
                    ))
                    .child(row(
                        lang.text(TextKey::SettingsUiSemanticNavigation),
                        Switch::new("settings-semantic-navigation")
                            .checked(self.settings.source_lsp_enabled)
                            .accessibility_label(lang.text(TextKey::SettingsUiSemanticNavigation))
                            .on_click(cx.listener(|this, checked: &bool, window, cx| {
                                if *checked && !this.settings.source_lsp_enabled {
                                    this.settings_ui.lsp_confirm = true;
                                    cx.notify();
                                } else if !*checked {
                                    this.settings.source_lsp_enabled = false;
                                    this.settings_ui.lsp_confirm = false;
                                    this.persist_settings(window, cx);
                                }
                            })),
                    ))
                    .child(studio::supporting(
                        lang.text(TextKey::SettingsUiSemanticNavigationHint),
                        &colors,
                    ))
                    .when(self.settings_ui.lsp_confirm, |card| {
                        card.child(div().text_color(colors.warning).child(
                            crate::studio::selectable(
                                lang.text(TextKey::SettingsUiSemanticNavigationWarning),
                            ),
                        ))
                        .child(
                            div()
                                .flex()
                                .gap(px(8.))
                                .child(
                                    studio::text_button(
                                        "source-lsp-cancel",
                                        lang.text(TextKey::SettingsUiCancel),
                                        true,
                                        false,
                                        &colors,
                                    )
                                    .on_click(cx.listener(
                                        |this, _, _, cx| {
                                            this.settings_ui.lsp_confirm = false;
                                            cx.notify();
                                        },
                                    )),
                                )
                                .child(
                                    studio::text_button(
                                        "source-lsp-enable",
                                        lang.text(TextKey::SettingsUiSemanticNavigationConfirm),
                                        true,
                                        true,
                                        &colors,
                                    )
                                    .on_click(cx.listener(
                                        |this, _, window, cx| {
                                            this.settings.source_lsp_enabled = true;
                                            this.settings_ui.lsp_confirm = false;
                                            this.persist_settings(window, cx);
                                        },
                                    )),
                                ),
                        )
                    }),
            )
            .into_any_element()
    }

    fn settings_inspector(&self, cx: &mut Context<Self>) -> AnyElement {
        let lang = self.settings.language;
        let colors = StudioColors::from_theme(cx);
        let prefs = self.settings.layout;
        let toggles = [
            (
                TextKey::SettingsUiHideHierarchy,
                prefs.hide_invisible_hierarchy,
            ),
            (
                TextKey::SettingsUiHideFindings,
                prefs.hide_invisible_findings,
            ),
            (
                TextKey::SettingsUiShowSystemComposables,
                prefs.show_system_composables,
            ),
            (TextKey::SettingsUiHideIndices, prefs.hide_indices),
            (TextKey::SettingsUiShowIds, prefs.show_ids),
            (TextKey::SettingsUiShowLayers, prefs.show_layer_buttons),
            (TextKey::SettingsUiShowBounds, prefs.show_visible_bounds),
        ];
        let mut view = studio::card(&colors).child(studio::section_title(
            lang.text(TextKey::SettingsUiView),
            &colors,
        ));
        for (index, (key, value)) in toggles.into_iter().enumerate() {
            view = view.child(row(
                lang.text(key),
                Switch::new(("inspector-setting", index))
                    .checked(value)
                    .accessibility_label(lang.text(key))
                    .on_click(cx.listener(move |this, checked: &bool, window, cx| {
                        match index {
                            0 => this.settings.layout.hide_invisible_hierarchy = *checked,
                            1 => this.settings.layout.hide_invisible_findings = *checked,
                            2 => this.settings.layout.show_system_composables = *checked,
                            3 => this.settings.layout.hide_indices = *checked,
                            4 => this.settings.layout.show_ids = *checked,
                            5 => this.settings.layout.show_layer_buttons = *checked,
                            _ => this.settings.layout.show_visible_bounds = *checked,
                        };
                        this.persist_settings(window, cx);
                    })),
            ));
            if key == TextKey::SettingsUiShowSystemComposables {
                view = view.child(div().px(px(8.)).pb(px(4.)).child(studio::supporting(
                    lang.text(TextKey::SettingsUiShowSystemComposablesHint),
                    &colors,
                )));
            }
        }
        view = view
            .child(studio::section_title(
                lang.text(TextKey::SettingsUiHitOrder),
                &colors,
            ))
            .child(
                div()
                    .pl(px(20.))
                    .pt(px(10.))
                    .flex()
                    .flex_col()
                    .items_start()
                    .gap(px(8.))
                    .children(
                        [
                            (true, TextKey::SettingsUiSmallest),
                            (false, TextKey::SettingsUiZOrder),
                        ]
                        .into_iter()
                        .enumerate()
                        .map(|(index, (smallest, key))| {
                            Radio::new(("settings-hit-order", index))
                                .label(lang.text(key))
                                .checked(prefs.hit_smallest == smallest)
                                .on_click(cx.listener(move |this, checked: &bool, window, cx| {
                                    if *checked {
                                        this.settings.layout.hit_smallest = smallest;
                                        this.persist_settings(window, cx);
                                    }
                                }))
                        }),
                    ),
            );
        let mut color_card = studio::card(&colors).child(studio::section_title(
            lang.text(TextKey::SettingsUiBorderColors),
            &colors,
        ));
        for (index, key) in [
            TextKey::SettingsUiNormal,
            TextKey::SettingsUiHover,
            TextKey::SettingsUiSelected,
        ]
        .into_iter()
        .enumerate()
        {
            color_card = color_card.child(
                div()
                    .flex()
                    .flex_col()
                    .gap(px(5.))
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap(px(8.))
                            .child(
                                div()
                                    .w(px(82.))
                                    .child(crate::studio::selectable(lang.text(key))),
                            )
                            .child(self.color_control(index + 1, cx))
                            .child(
                                studio::text_button(
                                    ("reset-border", index),
                                    lang.text(TextKey::SettingsUiReset),
                                    true,
                                    false,
                                    &colors,
                                )
                                .on_click(cx.listener(
                                    move |this, _, window, cx| {
                                        let default = LayoutPreferences::default();
                                        let color = [
                                            default.normal_color,
                                            default.hover_color,
                                            default.selected_color,
                                        ][index];
                                        this.set_color(index + 1, color, window, cx);
                                    },
                                )),
                            ),
                    )
                    .child(
                        div().flex().pl(px(90.)).gap(px(7.)).children(
                            [
                                0xff7dd3fc, 0xfff59e0b, 0xffef4444, 0xff22c55e, 0xffa855f7,
                                0xffffffff,
                            ]
                            .into_iter()
                            .enumerate()
                            .map(|(i, color)| {
                                Button::new((
                                    SharedString::from(format!("border-preset-{index}")),
                                    i,
                                ))
                                .ghost()
                                .size(px(20.))
                                .p_0()
                                .accessibility_label(format!("{} #{color:08X}", lang.text(key)))
                                .child(div().size(px(20.)).rounded_full().bg(rgba_argb(color)))
                                .on_click(cx.listener(
                                    move |this, _, window, cx| {
                                        this.set_color(index + 1, color, window, cx)
                                    },
                                ))
                            }),
                        ),
                    ),
            );
        }
        page(lang.text(TextKey::FoundationLayoutInspector), &colors)
            .child(view)
            .child(
                studio::card(&colors)
                    .child(studio::section_title(
                        lang.text(TextKey::SettingsUiArchive),
                        &colors,
                    ))
                    .child(lang.format(
                        TextKey::SettingsUiArchiveLimit,
                        &[
                            ("limit", &(108 * prefs.archive_multiplier as u32)),
                            ("multiplier", &prefs.archive_multiplier),
                        ],
                    ))
                    .child(
                        div().w(px(320.)).child(
                            Slider::new(&self.settings_ui.archive)
                                .accessibility_label(lang.text(TextKey::SettingsUiArchive)),
                        ),
                    )
                    .child(studio::supporting(
                        lang.text(TextKey::SettingsUiArchiveHint),
                        &colors,
                    )),
            )
            .child(color_card)
            .child(self.compose_reports_guide(&colors, cx))
            .into_any_element()
    }

    fn compose_reports_guide(&self, colors: &StudioColors, cx: &mut Context<Self>) -> Div {
        let language = self.settings.language;
        studio::card(colors)
            .child(studio::section_title(
                language.text(TextKey::ComposeReportsTitle),
                colors,
            ))
            .child(crate::studio::selectable(
                language.text(TextKey::ComposeReportsPurpose),
            ))
            .child(studio::section_title(
                language.text(TextKey::ComposeReportsConfigure),
                colors,
            ))
            .child(crate::studio::selectable(
                language.text(TextKey::ComposeReportsConfigureHint),
            ))
            .child(compose_report_code(
                "compose-report-configuration",
                COMPOSE_REPORT_CONFIGURATION,
                TextKey::ComposeReportsCopyConfiguration,
                language,
                colors,
                cx,
            ))
            .child(studio::section_title(
                language.text(TextKey::ComposeReportsGenerate),
                colors,
            ))
            .child(crate::studio::selectable(
                language.text(TextKey::ComposeReportsGenerateHint),
            ))
            .child(compose_report_code(
                "compose-report-generate-command",
                COMPOSE_REPORT_GENERATE_COMMAND,
                TextKey::ComposeReportsCopyCommand,
                language,
                colors,
                cx,
            ))
            .child(crate::studio::selectable(
                language.text(TextKey::ComposeReportsOutputHint),
            ))
            .child(studio::section_title(
                language.text(TextKey::ComposeReportsImportStep),
                colors,
            ))
            .child(crate::studio::selectable(
                language.text(TextKey::ComposeReportsImportHint),
            ))
            .child(studio::supporting(
                language.text(TextKey::ComposeReportsLimitations),
                colors,
            ))
    }

    /// Sampling controls share CaptureWorkspace's draft; applying a template or
    /// changing an input never edits a frozen plan or starts device recording.
    fn settings_simpleperf(&self, cx: &mut Context<Self>) -> AnyElement {
        let lang = self.settings.language;
        let colors = StudioColors::from_theme(cx);
        let capture = self.capture.read(cx);
        let no_target = !capture.has_target(cx);
        // Configuration is useful before a device/process is selected. The
        // capture action still validates the target when the user starts it.
        let can_edit = capture.can_edit_configuration();
        let draft = capture.draft(cx);
        let [event, rate, duration] = capture.sampling_inputs();
        let follow_mouse = self.settings.simpleperf_follow_tooltip;
        let mut view = page(lang.text(TextKey::CommonSimpleperf), &colors);
        if no_target {
            view = view.child(studio::supporting(
                lang.text(TextKey::SettingsUiCaptureParametersConnect),
                &colors,
            ));
        }
        view.child(
            studio::card(&colors)
                .child(studio::section_title(
                    lang.text(TextKey::SettingsUiTemplates),
                    &colors,
                ))
                .child(studio::supporting(
                    lang.text(TextKey::SettingsUiSamplingTemplateDescription),
                    &colors,
                ))
                .children(
                    aps_capture::SamplingTemplate::ALL
                        .into_iter()
                        .enumerate()
                        .map(|(index, template)| {
                            div()
                                .w_full()
                                .flex()
                                .flex_col()
                                .gap(px(2.))
                                .child(
                                    Radio::new(("sampling-template", index))
                                        .label(lang.text(crate::capture::template_label(template)))
                                        .checked(draft.template == Some(template))
                                        .disabled(!can_edit)
                                        .on_click(cx.listener(
                                            move |this, _: &bool, window, cx| {
                                                if can_edit {
                                                    let targets = this.targets.read(cx);
                                                    let lists = targets.current_lists();
                                                    let app_scoped =
                                                        this.capture.read(cx).template_app_scoped(
                                                            targets.selected_device(),
                                                            lists.as_ref().map_or(&[], |lists| {
                                                                lists.processes.as_slice()
                                                            }),
                                                            cx,
                                                        );
                                                    this.capture.update(cx, |capture, cx| {
                                                        capture.apply_template(
                                                            template, app_scoped, window, cx,
                                                        )
                                                    });
                                                    this.persist_capture_defaults(window, cx);
                                                    cx.notify();
                                                }
                                            },
                                        )),
                                )
                                .child(studio::supporting(
                                    lang.text(crate::capture::template_description(template)),
                                    &colors,
                                ))
                        }),
                ),
        )
        .child(
            studio::card(&colors)
                .child(studio::section_title(
                    lang.text(TextKey::SettingsUiCapture),
                    &colors,
                ))
                .child(studio::supporting(
                    lang.text(TextKey::SettingsUiCaptureConfigurationDescription),
                    &colors,
                ))
                .child(studio::section_title(
                    lang.text(TextKey::SettingsUiEventRate),
                    &colors,
                ))
                .child(field_label(lang.text(TextKey::SettingsUiEvent), &colors))
                .child(input_field(&event, &colors, can_edit))
                .child(studio::supporting(
                    lang.text(TextKey::SettingsUiAvailableEvents),
                    &colors,
                ))
                .child(field_label(lang.text(TextKey::SettingsUiSampling), &colors))
                .child(
                    div().flex().flex_wrap().gap(px(5.)).children(
                        [false, true]
                            .into_iter()
                            .enumerate()
                            .map(|(index, period)| {
                                Radio::new(("rate-mode", index))
                                    .label(lang.text(if period {
                                        TextKey::SettingsUiPeriod
                                    } else {
                                        TextKey::SettingsUiFrequency
                                    }))
                                    .checked(draft.period_mode == period)
                                    .disabled(!can_edit)
                                    .on_click(cx.listener(move |this, checked: &bool, _, cx| {
                                        if *checked && can_edit {
                                            this.capture.update(cx, |capture, cx| {
                                                capture.set_period_mode(period, cx)
                                            });
                                            cx.notify();
                                        }
                                    }))
                            }),
                    ),
                )
                .child(
                    div()
                        .flex()
                        .flex_wrap()
                        .gap(px(8.))
                        .child(
                            div()
                                .flex_1()
                                .min_w(px(140.))
                                .child(field_label(
                                    lang.text(if draft.period_mode {
                                        TextKey::SettingsUiEventsPerSample
                                    } else {
                                        TextKey::CaptureFrequencyHzPlaceholder
                                    }),
                                    &colors,
                                ))
                                .child(input_field(&rate, &colors, can_edit)),
                        )
                        .child(
                            div()
                                .flex_1()
                                .min_w(px(140.))
                                .child(field_label(
                                    lang.text(TextKey::SettingsUiDurationSecondsHint),
                                    &colors,
                                ))
                                .child(input_field(&duration, &colors, can_edit)),
                        ),
                )
                .child(studio::supporting(
                    lang.text(TextKey::CaptureDurationLimit),
                    &colors,
                ))
                .when_some(draft.validate().err(), |card, error| {
                    card.child(div().text_color(colors.error).child(lang.text(error)))
                })
                .when(no_target, |card| {
                    card.child(studio::supporting(
                        lang.text(TextKey::SettingsUiSamplingTargetRequired),
                        &colors,
                    ))
                }),
        )
        .child(
            studio::card(&colors)
                .child(studio::section_title(
                    lang.text(TextKey::SettingsUiAdvanced),
                    &colors,
                ))
                .child(studio::supporting(
                    lang.text(TextKey::SettingsUiAdvancedParametersDescription),
                    &colors,
                ))
                .child(field_label(
                    lang.text(TextKey::SettingsUiCallgraph),
                    &colors,
                ))
                .child(
                    div().flex().flex_wrap().gap(px(5.)).children(
                        aps_capture::CallGraphMode::ALL.into_iter().enumerate().map(
                            |(index, mode)| {
                                Radio::new(("callgraph", index))
                                    .label(lang.text(crate::capture::call_graph_label(mode)))
                                    .checked(draft.call_graph == mode)
                                    .disabled(!can_edit)
                                    .on_click(cx.listener(move |this, checked: &bool, _, cx| {
                                        if *checked && can_edit {
                                            this.capture.update(cx, |capture, cx| {
                                                capture.set_call_graph(mode, cx)
                                            });
                                            cx.notify();
                                        }
                                    }))
                            },
                        ),
                    ),
                )
                .child(field_label(lang.text(TextKey::SettingsUiScope), &colors))
                .child(
                    div().flex().flex_wrap().gap(px(5.)).children(
                        aps_capture::EventScope::ALL.into_iter().enumerate().map(
                            |(index, scope)| {
                                Radio::new(("event-scope", index))
                                    .label(lang.text(crate::capture::scope_label(scope)))
                                    .checked(draft.scope == scope)
                                    .disabled(!can_edit)
                                    .on_click(cx.listener(move |this, checked: &bool, _, cx| {
                                        if *checked && can_edit {
                                            this.capture.update(cx, |capture, cx| {
                                                capture.set_scope(scope, cx)
                                            });
                                            cx.notify();
                                        }
                                    }))
                            },
                        ),
                    ),
                ),
        )
        .child(
            studio::card(&colors)
                .child(studio::section_title(
                    lang.text(TextKey::SettingsUiFlame),
                    &colors,
                ))
                .child(studio::supporting(
                    lang.text(TextKey::SettingsUiFrameInformationDescription),
                    &colors,
                ))
                .child(studio::section_title(
                    lang.text(TextKey::SettingsUiFrameInformationBox),
                    &colors,
                ))
                .child(studio::supporting(
                    lang.text(TextKey::SettingsUiFrameInformationBehavior),
                    &colors,
                ))
                .child(
                    div()
                        .flex()
                        .flex_col()
                        .gap(px(6.))
                        .child(
                            Radio::new(("tooltip-mode", 0usize))
                                .label(lang.text(TextKey::SettingsUiFixed))
                                .checked(!follow_mouse)
                                .on_click(cx.listener(|this, checked: &bool, window, cx| {
                                    if *checked && this.settings.simpleperf_follow_tooltip {
                                        this.settings.simpleperf_follow_tooltip = false;
                                        this.persist_settings(window, cx);
                                        cx.notify();
                                    }
                                })),
                        )
                        .child(
                            Radio::new(("tooltip-mode", 1usize))
                                .label(lang.text(TextKey::SettingsUiFollow))
                                .checked(follow_mouse)
                                .on_click(cx.listener(|this, checked: &bool, window, cx| {
                                    if *checked && !this.settings.simpleperf_follow_tooltip {
                                        this.settings.simpleperf_follow_tooltip = true;
                                        this.persist_settings(window, cx);
                                        cx.notify();
                                    }
                                })),
                        ),
                ),
        )
        .child(
            studio::card(&colors)
                .child(studio::section_title(
                    lang.text(TextKey::SettingsUiSimpleperfEngine),
                    &colors,
                ))
                .child(studio::supporting(
                    lang.text(TextKey::SettingsUiAnalysisEngineDescription),
                    &colors,
                ))
                .child(studio::supporting(
                    lang.text(TextKey::SettingsUiAnalysisEngineOptions),
                    &colors,
                ))
                .child(
                    div().flex().flex_wrap().gap(px(6.)).children(
                        [
                            (0u8, TextKey::SettingsUiNewEngine),
                            (1u8, TextKey::SettingsUiFirefoxLocalEngine),
                            (2u8, TextKey::SettingsUiFirefoxEngine),
                        ]
                        .into_iter()
                        .map(|(engine, label)| {
                            Radio::new(("engine", engine as usize))
                                .label(lang.text(label))
                                .checked(self.settings.simpleperf_viewer_engine == engine)
                                .on_click(cx.listener(move |this, checked: &bool, window, cx| {
                                    if *checked && this.settings.simpleperf_viewer_engine != engine
                                    {
                                        this.settings.simpleperf_viewer_engine = engine;
                                        this.persist_settings(window, cx);
                                        cx.notify();
                                    }
                                }))
                        }),
                    ),
                )
                .child(studio::supporting(
                    lang.text(TextKey::SettingsUiViewerHint),
                    &colors,
                )),
        )
        .child(
            studio::card(&colors)
                .child(studio::section_title(
                    lang.text(TextKey::SettingsUiGuide),
                    &colors,
                ))
                .child(studio::supporting(
                    lang.text(TextKey::SettingsUiUserGuideDescription),
                    &colors,
                ))
                .child(
                    div().flex().child(
                        studio::text_button(
                            "open-user-guide",
                            lang.text(TextKey::SettingsUiOpenUserGuideBrowser),
                            true,
                            true,
                            &colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            match crate::user_guide::url(this.settings.language) {
                                Ok(url) => cx.open_url(&url),
                                Err(error) => eprintln!("User guide unavailable: {error}"),
                            }
                        })),
                    ),
                ),
        )
        .into_any_element()
    }

    fn settings_about(&self, cx: &mut Context<Self>) -> AnyElement {
        let lang = self.settings.language;
        let colors = StudioColors::from_theme(cx);
        page(lang.text(TextKey::CommonAbout), &colors)
            .child(div().child(crate::studio::selectable(
                lang.text(TextKey::FoundationAndroidPerformanceStudio),
            )))
            .child(studio::supporting(
                format!(
                    "{} {}",
                    lang.text(TextKey::CommonVersion),
                    env!("CARGO_PKG_VERSION")
                ),
                &colors,
            ))
            .into_any_element()
    }

    fn color_control(&self, index: usize, cx: &mut Context<Self>) -> AnyElement {
        let lang = self.settings.language;
        let colors = StudioColors::from_theme(cx);
        let current = match index {
            0 => self
                .settings
                .custom_accent
                .unwrap_or(0xff000000 | self.settings.accent_color.rgb()),
            1 => self.settings.layout.normal_color,
            2 => self.settings.layout.hover_color,
            _ => self.settings.layout.selected_color,
        };
        div()
            .flex()
            .flex_col()
            .gap(px(4.))
            .flex_1()
            .min_w_0()
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(if index == 0 { 4. } else { 8. }))
                    .child(
                        div()
                            .size(px(if index == 0 { 16. } else { 20. }))
                            .flex_shrink_0()
                            .rounded_full()
                            .bg(rgba_argb(current)),
                    )
                    .child(
                        div().flex_1().min_w(px(100.)).max_w(px(200.)).child(
                            Input::new(&self.settings_ui.colors[index])
                                .w_full()
                                .h(px(28.))
                                .rounded(px(4.))
                                .bg(colors.field)
                                .border_1()
                                .border_color(colors.border),
                        ),
                    ),
            )
            .when(self.settings_ui.color_error == Some(index), |element| {
                element.child(div().text_size(px(11.)).text_color(colors.error).child(
                    crate::studio::selectable(lang.text(TextKey::SettingsUiHexError)),
                ))
            })
            .into_any_element()
    }

    fn sync_color(
        &mut self,
        index: usize,
        color: u32,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.settings_ui.colors[index].update(cx, |input, cx| {
            input.set_value(format!("#{color:08X}"), window, cx)
        });
        self.settings_ui.color_error = None;
        if index == 0 {
            self.settings_ui.hsv = rgb_to_hsv(color);
            self.settings_ui.hue.update(cx, |slider, cx| {
                slider.set_value(self.settings_ui.hsv.0, window, cx)
            });
        }
    }

    fn set_color(&mut self, index: usize, color: u32, window: &mut Window, cx: &mut Context<Self>) {
        match index {
            0 => self.settings.custom_accent = Some(color),
            1 => self.settings.layout.normal_color = color,
            2 => self.settings.layout.hover_color = color,
            _ => self.settings.layout.selected_color = color,
        };
        self.sync_color(index, color, window, cx);
        self.persist_settings(window, cx);
    }

    fn apply_picker(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let (h, s, v) = self.settings_ui.hsv;
        let color = hsv_rgb(h, s, v) | 0xff000000;
        self.settings.custom_accent = Some(color);
        self.settings_ui.colors[0].update(cx, |input, cx| {
            input.set_value(format!("#{color:08X}"), window, cx)
        });
        self.settings_ui.color_error = None;
        self.persist_settings(window, cx);
    }

    fn sv_picker(&self, cx: &mut Context<Self>) -> AnyElement {
        let (h, s, v) = self.settings_ui.hsv;
        let origin = Arc::new(Mutex::new(None::<Bounds<Pixels>>));
        let paint_origin = origin.clone();
        let drag_origin = origin.clone();
        div()
            .id("settings-sv-picker")
            .w(px(GENERAL_CUSTOM_COLOR_WIDTH))
            .h(px(140.))
            .cursor_pointer()
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, event: &MouseDownEvent, window, cx| {
                    if let Some(bounds) = origin.lock().ok().and_then(|value| *value) {
                        this.settings_ui.hsv.1 = (f32::from(event.position.x - bounds.origin.x)
                            / f32::from(bounds.size.width))
                        .clamp(0., 1.);
                        this.settings_ui.hsv.2 = (1.
                            - f32::from(event.position.y - bounds.origin.y)
                                / f32::from(bounds.size.height))
                        .clamp(0., 1.);
                        this.apply_picker(window, cx);
                    }
                }),
            )
            .on_mouse_move(
                cx.listener(move |this, event: &MouseMoveEvent, window, cx| {
                    if event.pressed_button == Some(MouseButton::Left)
                        && let Some(bounds) = drag_origin.lock().ok().and_then(|value| *value)
                    {
                        this.settings_ui.hsv.1 = (f32::from(event.position.x - bounds.origin.x)
                            / f32::from(bounds.size.width))
                        .clamp(0., 1.);
                        this.settings_ui.hsv.2 = (1.
                            - f32::from(event.position.y - bounds.origin.y)
                                / f32::from(bounds.size.height))
                        .clamp(0., 1.);
                        this.apply_picker(window, cx);
                    }
                }),
            )
            .child(
                canvas(
                    move |bounds, _, _| {
                        if let Ok(mut value) = paint_origin.lock() {
                            *value = Some(bounds);
                        }
                        bounds
                    },
                    move |_, bounds, window, _| {
                        for row in 0..140 {
                            let value = 1. - row as f32 / 139.;
                            let strip = Bounds::new(
                                point(bounds.origin.x, bounds.origin.y + px(row as f32)),
                                size(bounds.size.width, px(1.)),
                            );
                            window.paint_quad(fill(
                                strip,
                                linear_gradient(
                                    90.,
                                    linear_color_stop(rgb(hsv_rgb(h, 0., value)), 0.),
                                    linear_color_stop(rgb(hsv_rgb(h, 1., value)), 1.),
                                ),
                            ));
                        }
                        let marker = Bounds::new(
                            point(
                                bounds.origin.x + px(s * 234.),
                                bounds.origin.y + px((1. - v) * 134.),
                            ),
                            size(px(6.), px(6.)),
                        );
                        window.paint_quad(quad(
                            marker,
                            px(3.),
                            transparent_black(),
                            px(1.),
                            rgb(0xffffff),
                            BorderStyle::Solid,
                        ));
                    },
                )
                .size_full(),
            )
            .into_any_element()
    }

    fn pick_sdk(&mut self, cx: &mut Context<Self>) {
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: false,
            directories: true,
            multiple: false,
            prompt: Some(self.settings.language.text(TextKey::SettingsUiSdk).into()),
        });
        self.settings_ui.sdk_busy = true;
        self.settings_ui.sdk_task = Some(cx.spawn(async move |this, cx| {
            let result = picker.await;
            let _ = this.update(cx, |this, cx| {
                this.settings_ui.sdk_busy = false;
                match result {
                    Ok(Ok(Some(paths))) if paths.len() == 1 => {
                        this.apply_sdk(Some(paths[0].to_string_lossy().into_owned()), cx)
                    }
                    Ok(Ok(_)) => {}
                    _ => {
                        this.settings_ui.sdk_error = Some(
                            this.settings
                                .language
                                .text(TextKey::SettingsUiSdkError)
                                .into(),
                        )
                    }
                };
                cx.notify();
            });
        }));
        cx.notify();
    }

    fn apply_sdk(&mut self, draft: Option<String>, cx: &mut Context<Self>) {
        let draft = draft
            .unwrap_or_else(|| self.settings_ui.sdk.read(cx).value().to_string())
            .trim()
            .to_owned();
        let Some(path) = self
            .settings_path
            .as_ref()
            .and_then(|path| path.parent())
            .map(|root| root.join("android-sdk.conf"))
        else {
            self.settings_ui.sdk_error = Some(
                self.settings
                    .language
                    .text(TextKey::ShellSettingsDirectoryUnavailable)
                    .into(),
            );
            cx.notify();
            return;
        };
        self.settings_ui.sdk_busy = true;
        self.settings_ui.sdk_error = None;
        let invalid = self
            .settings
            .language
            .text(TextKey::SettingsUiSdkError)
            .to_owned();
        self.settings_ui.sdk_task = Some(cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    let root = if draft.is_empty() {
                        None
                    } else {
                        Some(Path::new(&draft))
                    };
                    // Validation happens before any persistence and publication is
                    // infallible after the atomic write under the same lock.
                    if let Some(root) = root {
                        aps_adb::AdbLocator::validate_sdk(root).map_err(|_| invalid)?;
                    }
                    let canonical =
                        aps_adb::AdbLocator::configure_sdk_transaction(root, |canonical| {
                            let saved = canonical
                                .map(|root| root.to_string_lossy().into_owned())
                                .unwrap_or_default();
                            save_sdk(&path, &saved)
                        })?;
                    let saved = canonical
                        .map(|root| root.to_string_lossy().into_owned())
                        .unwrap_or_default();
                    Ok::<_, String>(saved)
                })
                .await;
            let _ = this.update_in(cx, |this, window, cx| {
                this.settings_ui.sdk_busy = false;
                match result {
                    Ok(saved) => {
                        this.settings_ui
                            .sdk
                            .update(cx, |input, cx| input.set_value(saved.clone(), window, cx));
                        this.settings_ui.saved_sdk = saved;
                        this.settings_ui.sdk_error = None;
                    }
                    Err(error) => this.settings_ui.sdk_error = Some(error),
                };
                cx.notify();
            });
        }));
        cx.notify();
    }
}

fn compose_report_code(
    id: &'static str,
    code: &'static str,
    copy_key: TextKey,
    language: Language,
    colors: &StudioColors,
    cx: &mut Context<Studio>,
) -> Div {
    div()
        .w_full()
        .flex()
        .flex_col()
        .items_start()
        .gap(px(8.))
        .p(px(8.))
        .rounded(px(4.))
        .bg(colors.workspace)
        .border_1()
        .border_color(colors.border)
        .child(
            div()
                .w_full()
                .font_family("monospace")
                .text_size(px(studio::FONT_SUPPORTING))
                .child(crate::studio::selectable_keyed(id, code)),
        )
        .child(
            studio::compact_text_button(
                ElementId::Name(format!("{id}-copy").into()),
                language.text(copy_key),
                true,
                false,
                colors,
            )
            .on_click(cx.listener(move |_, _, _, cx| {
                cx.write_to_clipboard(ClipboardItem::new_string(code.to_owned()));
            })),
        )
}

fn page(title: &str, colors: &StudioColors) -> Div {
    div()
        .flex()
        .flex_col()
        .gap(px(8.))
        .p(px(4.))
        .text_size(px(13.))
        .text_color(colors.text)
        .child(
            div()
                .text_size(px(16.))
                .font_weight(FontWeight::SEMIBOLD)
                .child(crate::studio::selectable(title.to_owned())),
        )
}
fn row(label: &str, control: impl IntoElement) -> AnyElement {
    div()
        .flex()
        .items_center()
        .justify_between()
        .gap(px(8.))
        .px(px(8.))
        .child(
            div()
                .flex_1()
                .min_w_0()
                .child(crate::studio::selectable(label.to_owned())),
        )
        .child(control)
        .into_any_element()
}
fn dropdown(id: impl Into<ElementId>, label: impl Into<SharedString>, cx: &App) -> Button {
    Button::new(id)
        .w(px(320.))
        .h(px(30.))
        .justify_start()
        .border_1()
        .border_color(cx.theme().border)
        .label(label)
}
/// Small muted label above a Compose-style text field.
fn field_label(label: impl Into<SharedString>, colors: &StudioColors) -> Div {
    div()
        .text_size(px(11.))
        .text_color(colors.muted_text)
        .child(label.into())
}

/// Framed editable text field used by the capture parameters.
fn input_field(input: &Entity<InputState>, colors: &StudioColors, enabled: bool) -> AnyElement {
    Input::new(input)
        .disabled(!enabled)
        .w_full()
        .h(px(28.))
        .rounded(px(4.))
        .bg(colors.field)
        .border_1()
        .border_color(colors.border)
        .into_any_element()
}

fn capture_defaults_for_draft(
    draft: &crate::capture::CpuCaptureDraft,
    previous: (u32, u32),
) -> (u32, u32) {
    let (duration, frequency) = draft.persistable_defaults();
    (
        duration.unwrap_or(previous.0),
        frequency.unwrap_or(previous.1),
    )
}
fn parse_argb(value: &str, short: bool) -> Option<u32> {
    let value = value.trim().strip_prefix('#').unwrap_or(value.trim());
    if !value.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return None;
    }
    let value = if short && value.len() == 3 {
        value.chars().flat_map(|ch| [ch, ch]).collect::<String>()
    } else {
        value.to_owned()
    };
    match value.len() {
        6 => u32::from_str_radix(&value, 16)
            .ok()
            .map(|value| value | 0xff000000),
        8 => u32::from_str_radix(&value, 16).ok(),
        _ => None,
    }
}
fn rgba_argb(value: u32) -> Rgba {
    rgb(value & 0x00ffffff).alpha(((value >> 24) & 255) as f32 / 255.)
}
fn rgb_to_hsv(value: u32) -> (f32, f32, f32) {
    let r = ((value >> 16) & 255) as f32 / 255.;
    let g = ((value >> 8) & 255) as f32 / 255.;
    let b = (value & 255) as f32 / 255.;
    let max = r.max(g).max(b);
    let min = r.min(g).min(b);
    let delta = max - min;
    let h = if delta == 0. {
        0.
    } else if max == r {
        60. * ((g - b) / delta).rem_euclid(6.)
    } else if max == g {
        60. * ((b - r) / delta + 2.)
    } else {
        60. * ((r - g) / delta + 4.)
    };
    (h, if max == 0. { 0. } else { delta / max }, max)
}
fn hsv_rgb(h: f32, s: f32, v: f32) -> u32 {
    let c = v * s;
    let x = c * (1. - ((h / 60.).rem_euclid(2.) - 1.).abs());
    let m = v - c;
    let (r, g, b) = match (h.rem_euclid(360.) / 60.) as u8 {
        0 => (c, x, 0.),
        1 => (x, c, 0.),
        2 => (0., c, x),
        3 => (0., x, c),
        4 => (x, 0., c),
        _ => (c, 0., x),
    };
    let byte = |value: f32| ((value + m) * 255.).round().clamp(0., 255.) as u32;
    (byte(r) << 16) | (byte(g) << 8) | byte(b)
}

fn load_sdk(path: &Path) -> Result<String, String> {
    use std::io::Read;
    let file = match std::fs::File::open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(String::new()),
        Err(error) => return Err(error.to_string()),
    };
    let mut text = String::new();
    file.take(4100)
        .read_to_string(&mut text)
        .map_err(|error| error.to_string())?;
    if text.len() > 4096 || text.contains(['\n', '\r', '\0']) {
        return Err("Invalid Rust SDK preference; original file preserved".into());
    }
    if !text.is_empty() {
        aps_adb::AdbLocator::validate_sdk(Path::new(&text)).map_err(|error| error.to_string())?;
    }
    Ok(text)
}
fn save_sdk(path: &Path, value: &str) -> Result<(), String> {
    use std::io::Write;
    if value.len() > 4096 || value.contains(['\n', '\r', '\0']) {
        return Err("Invalid SDK path".into());
    }
    let root = path.parent().ok_or("Missing SDK preference directory")?;
    std::fs::create_dir_all(root).map_err(|error| error.to_string())?;
    let temp = root.join(format!(
        "android-sdk.{}.{}.tmp",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_err(|error| error.to_string())?
            .as_nanos()
    ));
    let mut file = std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temp)
        .map_err(|error| error.to_string())?;
    let result = (|| {
        file.write_all(value.as_bytes())?;
        file.sync_all()?;
        drop(file);
        std::fs::rename(&temp, path)
    })();
    let _ = std::fs::remove_file(temp);
    result.map_err(|error: std::io::Error| error.to_string())
}

#[cfg(test)]
mod cpu_capture_host_tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn capture_planning_is_a_localized_settings_section_with_one_shared_owner() {
        assert_eq!(
            SettingsPage::ALL
                .iter()
                .filter(|&&page| page == SettingsPage::CpuCapture)
                .count(),
            1
        );
        for language in [Language::English, Language::SimplifiedChinese] {
            assert_eq!(
                SettingsPage::CpuCapture.label(language),
                language.text(TextKey::FoundationCapturePlanning)
            );
        }
        let source = include_str!("settings_page.rs");
        let page = source
            .split("pub(super) fn settings_page(")
            .nth(1)
            .unwrap()
            .split("fn settings_general(")
            .next()
            .unwrap();
        assert!(
            page.contains("SettingsPage::CpuCapture => self.capture.clone().into_any_element()")
        );
        assert!(!page.contains("CaptureWorkspace::new("));
        assert!(page.contains("SettingsPage::Ai | SettingsPage::CpuCapture"));
        assert!(page.contains("this.refresh_route_menus(cx)"));
        assert!(page.contains(".accessibility_label(page.label(language))"));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn switching_from_period_to_frequency_without_editing_text_saves_only_the_new_hz() {
        let saved = (10, 99);
        let mut draft = crate::capture::CpuCaptureDraft::new(saved.0, saved.1);
        draft.period_mode = true;
        draft.rate = "500".into();
        assert_eq!(capture_defaults_for_draft(&draft, saved), saved);
        draft.period_mode = false;
        let next = capture_defaults_for_draft(&draft, saved);
        assert_eq!(next, (10, 500));
        let root = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let path = root.path().join("settings.conf");
        let settings = Settings {
            capture_duration_seconds: next.0,
            capture_frequency_hz: next.1,
            ..Settings::default()
        };
        settings.save(&path).unwrap();
        assert_eq!(Settings::load(&path).unwrap().capture_frequency_hz, 500);

        // Both mode controls use the workspace setter, whose explicit event
        // persists mode-only changes even when InputEvent::Change never fires.
        let source = include_str!("../capture.rs");
        let setter = source
            .split("pub(crate) fn set_period_mode(")
            .nth(1)
            .unwrap()
            .split("pub(crate) fn apply_template(")
            .next()
            .unwrap();
        assert!(setter.contains("cx.emit(CaptureDefaultsChanged)"));
        let source = include_str!("settings_page.rs");
        let handler = source
            .split("_: &crate::capture::CaptureDefaultsChanged,")
            .nth(1)
            .unwrap()
            .split("subscriptions.push(cx.observe(capture")
            .next()
            .unwrap();
        assert!(handler.contains("this.persist_capture_defaults(window, cx)"));
    }

    #[::core::prelude::v1::test]
    fn capture_preferences_follow_valid_shared_draft_values_without_persisting_period_as_hz() {
        let saved = (37, 123);
        let mut draft = crate::capture::CpuCaptureDraft::new(saved.0, saved.1);
        draft.duration = "24".into();
        draft.rate = "456".into();
        assert_eq!(capture_defaults_for_draft(&draft, saved), (24, 456));
        draft.period_mode = true;
        assert_eq!(capture_defaults_for_draft(&draft, saved), (24, 123));
        let request = draft
            .request("run".into(), "device".into(), 42, None)
            .unwrap();
        assert_eq!(
            request.sampling.rate,
            aps_capture::SamplingRate::Period(456)
        );
        assert_eq!(request.duration.whole_seconds(), Some(24));
        draft.rate = i64::MAX.to_string();
        assert_eq!(capture_defaults_for_draft(&draft, saved), (24, 123));
        draft.period_mode = false;
        assert_eq!(capture_defaults_for_draft(&draft, saved), (24, 123));
        assert!(
            draft
                .request("run".into(), "device".into(), 42, None)
                .is_err()
        );
        assert_eq!(draft.rate, i64::MAX.to_string());
    }

    #[::core::prelude::v1::test]
    fn invalid_settings_text_is_not_saved_clamped_or_substituted_in_requests() {
        let saved = (37, 123);
        for text in ["", "0", "-1", "NaN", "4001", "10000", "1.5"] {
            let mut draft = crate::capture::CpuCaptureDraft::new(saved.0, saved.1);
            draft.rate = text.into();
            draft.duration = text.into();
            let before = draft.clone();
            assert_eq!(capture_defaults_for_draft(&draft, saved), saved);
            assert!(
                draft
                    .request("run".into(), "device".into(), 42, None)
                    .is_err()
            );
            assert_eq!(draft, before);
        }
    }

    #[::core::prelude::v1::test]
    fn applying_each_template_feeds_both_persistable_defaults_and_the_next_request() {
        for template in aps_capture::SamplingTemplate::ALL {
            let mut draft = crate::capture::CpuCaptureDraft::new(37, 123);
            draft.period_mode = true;
            draft.rate = "9223372036854775807".into();
            draft.apply_template(template, true);
            let (duration, frequency) = capture_defaults_for_draft(&draft, (37, 123));
            let request = draft
                .request("run".into(), "device".into(), 42, None)
                .unwrap();
            assert_eq!(duration, 10);
            assert_eq!(request.duration.whole_seconds(), Some(duration));
            assert_eq!(
                request.sampling.rate,
                aps_capture::SamplingRate::Frequency(frequency)
            );
            assert_eq!(request.sampling, template.create(true));
        }
    }

    #[::core::prelude::v1::test]
    fn manual_and_fractional_settings_are_live_requests_not_rounded_integer_preferences() {
        let saved = (37, 123);
        for (text, expected) in [
            ("", None),
            (" \t", None),
            ("0.25", Some("0.25")),
            ("37.5", Some("37.5")),
        ] {
            let mut draft = crate::capture::CpuCaptureDraft::new(saved.0, saved.1);
            draft.duration = text.into();
            draft.rate = "456".into();
            let request = draft
                .request("run".into(), "device".into(), 42, None)
                .unwrap();
            assert_eq!(request.duration.seconds_argument().as_deref(), expected);
            assert_eq!(request.duration.whole_seconds(), None);
            let defaults = capture_defaults_for_draft(&draft, saved);
            assert_eq!(defaults, (37, 456));
            let root = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
            let path = root.path().join("settings.conf");
            Settings {
                capture_duration_seconds: defaults.0,
                capture_frequency_hz: defaults.1,
                ..Settings::default()
            }
            .save(&path)
            .unwrap();
            let loaded = Settings::load(&path).unwrap();
            assert_eq!(loaded.capture_duration_seconds, 37);
            assert_eq!(loaded.capture_frequency_hz, 456);
            assert_eq!(draft.duration, text);
            draft.period_mode = true;
            assert_eq!(capture_defaults_for_draft(&draft, saved), saved);
            assert_eq!(draft.duration, text);
        }
        for (text, expected) in [("1", 1), ("24", 24), ("300", 300), ("10.0", 10)] {
            let mut draft = crate::capture::CpuCaptureDraft::new(saved.0, saved.1);
            draft.duration = text.into();
            assert_eq!(capture_defaults_for_draft(&draft, saved), (expected, 123));
        }
    }

    #[::core::prelude::v1::test]
    fn invalid_duration_settings_remain_visible_and_cannot_form_a_request_with_valid_hz() {
        let saved = (37, 123);
        for text in ["0", "-1", "301", "NaN", "inf", "300.000000001", "0.25s"] {
            let mut draft = crate::capture::CpuCaptureDraft::new(saved.0, saved.1);
            draft.duration = text.into();
            assert_eq!(capture_defaults_for_draft(&draft, saved), saved);
            assert_eq!(
                draft
                    .request("run".into(), "device".into(), 42, None)
                    .unwrap_err(),
                TextKey::CaptureInvalidDuration
            );
            assert_eq!(draft.duration, text);
        }
    }

    #[::core::prelude::v1::test]
    fn system_composable_setting_describes_visibility_and_next_capture() {
        let implementation = include_str!("settings_page.rs");
        let inspector = implementation
            .split("fn settings_inspector(")
            .nth(1)
            .unwrap()
            .split("fn compose_reports_guide(")
            .next()
            .unwrap();
        assert!(inspector.contains("TextKey::SettingsUiShowSystemComposablesHint"));
        let hint = Language::SimplifiedChinese.text(TextKey::SettingsUiShowSystemComposablesHint);
        for content in [
            "系统创建",
            "Box",
            "Row",
            "Column",
            "关闭后隐藏",
            "减少层级噪声",
            "下一次采集或刷新",
        ] {
            assert!(
                hint.contains(content),
                "missing setting explanation: {content}"
            );
        }
        let english = Language::English.text(TextKey::SettingsUiShowSystemComposablesHint);
        assert!(english.contains("next capture or refresh"));
        assert!(english.contains("reduce hierarchy noise"));
    }

    #[::core::prelude::v1::test]
    fn layout_inspector_settings_enter_at_the_top_of_the_scrollable_content() {
        let source = include_str!("settings_page.rs");
        let page = source
            .split("pub(super) fn settings_page(")
            .nth(1)
            .unwrap()
            .split("fn settings_general(")
            .next()
            .unwrap();
        assert!(page.contains("this.select_settings_page(page)"));
        assert!(page.contains(".track_scroll(&self.settings_scroll)"));

        let shell = include_str!("../shell.rs");
        let selection = shell
            .split("fn select_settings_page(")
            .nth(1)
            .unwrap()
            .split("fn entered_route(")
            .next()
            .unwrap();
        assert!(selection.contains("SettingsPage::LayoutInspector"));
        assert!(selection.contains("self.settings_scroll.set_offset(point(px(0.), px(0.)))"));
    }

    #[::core::prelude::v1::test]
    fn compose_report_guide_preserves_copyable_project_snippets() {
        assert_eq!(
            COMPOSE_REPORT_CONFIGURATION,
            "composeCompiler {\n    reportsDestination =\n        layout.buildDirectory.dir(\"compose_compiler\")\n    metricsDestination =\n        layout.buildDirectory.dir(\"compose_compiler\")\n}"
        );
        assert_eq!(
            COMPOSE_REPORT_GENERATE_COMMAND,
            "./gradlew :app:compileReleaseKotlin"
        );
        let implementation = include_str!("settings_page.rs");
        let inspector = implementation
            .split("fn settings_inspector(")
            .nth(1)
            .unwrap()
            .split("fn compose_reports_guide(")
            .next()
            .unwrap();
        assert!(inspector.contains(".child(self.compose_reports_guide(&colors, cx))"));
        let guide = implementation
            .split("fn compose_reports_guide(")
            .nth(1)
            .unwrap()
            .split("fn settings_simpleperf(")
            .next()
            .unwrap();
        for key in [
            "ComposeReportsPurpose",
            "ComposeReportsConfigureHint",
            "ComposeReportsGenerateHint",
            "ComposeReportsOutputHint",
            "ComposeReportsImportHint",
            "ComposeReportsLimitations",
        ] {
            assert!(guide.contains(key), "missing guide section: {key}");
        }
        assert_eq!(guide.matches(".child(compose_report_code(").count(), 2);
        let copy = implementation
            .split("fn compose_report_code(")
            .nth(1)
            .unwrap()
            .split("fn page(")
            .next()
            .unwrap();
        assert!(copy.contains("selectable_keyed(id, code)"));
        assert!(copy.contains("ClipboardItem::new_string(code.to_owned())"));
    }

    #[::core::prelude::v1::test]
    fn compose_report_guide_localizes_description_not_commands() {
        for language in [Language::English, Language::SimplifiedChinese] {
            assert!(
                language
                    .text(TextKey::ComposeReportsConfigureHint)
                    .contains("org.jetbrains.kotlin.plugin.compose")
            );
            let outputs = language.text(TextKey::ComposeReportsOutputHint);
            for name in [
                "*-composables.txt",
                "*-composables.csv",
                "*-classes.txt",
                "*-module.json",
            ] {
                assert!(outputs.contains(name), "missing report format {name}");
            }
            let generation = language.text(TextKey::ComposeReportsGenerateHint);
            for command in [":app:assembleRelease", "gradlew.bat", "--rerun-tasks"] {
                assert!(
                    generation.contains(command),
                    "missing generation hint {command}"
                );
            }
        }
        assert!(
            Language::English
                .text(TextKey::ComposeReportsImportHint)
                .contains("does not scan subfolders")
        );
        assert!(
            Language::SimplifiedChinese
                .text(TextKey::ComposeReportsImportHint)
                .contains("不递归扫描子文件夹")
        );
        assert_ne!(
            Language::English.text(TextKey::ComposeReportsPurpose),
            Language::SimplifiedChinese.text(TextKey::ComposeReportsPurpose)
        );
    }

    /// Theme colour: no "Custom color" heading, and the value/restore controls
    /// sit in a column right of the picker, separated from the presets.
    #[::core::prelude::v1::test]
    fn theme_color_layout_moves_value_and_reset_to_the_right() {
        let implementation = include_str!("settings_page.rs");
        let general = implementation
            .split("fn settings_general(")
            .nth(1)
            .unwrap()
            .split("fn settings_configuration(")
            .next()
            .unwrap();
        assert!(!general.contains("SettingsUiCustom"));
        assert!(general.contains("GENERAL_COLOR_SIDE_WIDTH"));
        assert!(general.contains(".self_stretch()"));
        assert!(general.matches("studio::divider(&colors)").count() >= 2);
    }

    /// Colour value fields (Canvas border colours and the custom accent) render
    /// inside a visible field frame instead of bare text.
    #[::core::prelude::v1::test]
    fn color_value_fields_use_a_visible_frame() {
        let implementation = include_str!("settings_page.rs");
        let control = implementation
            .split("fn color_control(")
            .nth(1)
            .unwrap()
            .split("fn ")
            .next()
            .unwrap();
        assert!(control.contains("Input::new(&self.settings_ui.colors[index])"));
        assert!(control.contains(".bg(colors.field)"));
        assert!(control.contains(".border_color(colors.border)"));
    }

    /// The hit-test order options are a radio group, not tab buttons.
    #[::core::prelude::v1::test]
    fn hit_order_options_use_radio_buttons() {
        let implementation = include_str!("settings_page.rs");
        let section = implementation
            .split("TextKey::SettingsUiHitOrder")
            .nth(1)
            .unwrap()
            .split("TextKey::SettingsUiBorderColors")
            .next()
            .unwrap();
        assert!(section.contains("Radio::new"));
        assert!(!section.contains("tab_button"));
    }

    /// The Simpleperf settings page mirrors Compose's one-page card layout.
    #[::core::prelude::v1::test]
    fn simpleperf_settings_page_mirrors_compose_sections() {
        let implementation = include_str!("settings_page.rs");
        let page = implementation
            .split("fn settings_simpleperf(")
            .nth(1)
            .unwrap()
            .split("fn settings_about(")
            .next()
            .unwrap();
        for key in [
            "SettingsUiTemplates",
            "SettingsUiSamplingTemplateDescription",
            "SettingsUiCapture",
            "SettingsUiCaptureConfigurationDescription",
            "SettingsUiAdvanced",
            "SettingsUiAdvancedParametersDescription",
            "SettingsUiFlame",
            "SettingsUiFrameInformationBox",
            "SettingsUiSimpleperfEngine",
            "SettingsUiAnalysisEngineOptions",
            "SettingsUiGuide",
            "SettingsUiUserGuideDescription",
            "SettingsUiEventRate",
            "SettingsUiDurationSecondsHint",
            "SettingsUiFixed",
            "SettingsUiFollow",
            "SettingsUiNewEngine",
        ] {
            assert!(page.contains(key), "missing Compose section content: {key}");
        }
        assert!(page.contains(r#"Radio::new(("sampling-template", index))"#));
        assert!(page.contains("capture.apply_template("));
        // Native Radio controls inherit the shared theme's control border;
        // this page no longer draws the old choice-chip border itself.
        assert!(page.contains("input_field("));
        // The flame-graph options are single-select radios, not chips.
        assert!(page.contains(r#"Radio::new(("tooltip-mode", 0usize))"#));
        assert!(page.contains(r#"Radio::new(("tooltip-mode", 1usize))"#));
        assert!(page.contains(r#"Radio::new(("callgraph", index))"#));
        assert!(page.contains("capture.set_call_graph(mode, cx)"));
        assert!(page.contains(".checked(draft.call_graph == mode)"));
        assert!(page.contains(".disabled(!can_edit)"));
        assert!(page.contains("capture.sampling_inputs()"));
        assert!(page.contains(".checked(draft.scope == scope)"));
        assert!(page.contains(".checked(draft.period_mode == period)"));
        assert!(!page.contains("settings_ui.rate"));
        assert!(!page.contains("settings_ui.duration"));
        assert!(!page.contains("SettingsUiAdaptersNotConnected"));
        assert!(page.contains("open-user-guide"));
        assert!(!page.contains("stepper("));
    }

    #[::core::prelude::v1::test]
    fn simpleperf_settings_remains_editable_before_target_selection() {
        let implementation = include_str!("settings_page.rs");
        let page = implementation
            .split("fn settings_simpleperf(")
            .nth(1)
            .unwrap()
            .split("fn settings_about(")
            .next()
            .unwrap();
        assert!(page.contains("let can_edit = capture.can_edit_configuration();"));
        assert!(!page.contains("let can_edit = !no_target && capture.can_edit_configuration();"));
        assert!(page.contains(".when(no_target, |card|"));
    }

    #[::core::prelude::v1::test]
    fn simpleperf_viewer_choices_persist_without_opening_a_browser() {
        let source = include_str!("settings_page.rs");
        let choices = source
            .split("TextKey::SettingsUiSimpleperfEngine")
            .nth(1)
            .unwrap()
            .split("TextKey::SettingsUiGuide")
            .next()
            .unwrap();
        for key in [
            "SettingsUiNewEngine",
            "SettingsUiFirefoxLocalEngine",
            "SettingsUiFirefoxEngine",
        ] {
            assert!(choices.contains(key));
        }
        assert!(choices.contains("Radio::new((\"engine\", engine as usize))"));
        assert!(choices.contains(".checked(self.settings.simpleperf_viewer_engine == engine)"));
        assert!(!choices.contains(".disabled("));
        assert!(!choices.contains("open_url"));
        assert!(!choices.contains("open_selected_viewer"));
        let directory = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let path = directory.path().join("settings.conf");
        for engine in 0..=2 {
            let settings = Settings {
                simpleperf_viewer_engine: engine,
                ..Settings::default()
            };
            settings.save(&path).unwrap();
            assert_eq!(
                Settings::load(&path).unwrap().simpleperf_viewer_engine,
                engine
            );
        }
    }

    /// The Configuration page has no engine selectors: Rust is the only engine,
    /// so both the CPU analysis engine and the HAR import engine rows are gone.
    #[::core::prelude::v1::test]
    fn configuration_page_has_no_engine_rows() {
        let implementation = include_str!("settings_page.rs");
        let configuration = implementation
            .split("fn settings_configuration(")
            .nth(1)
            .unwrap()
            .split("fn settings_inspector(")
            .next()
            .unwrap();
        assert!(!configuration.contains("SettingsUiCpuEngine"));
        assert!(!configuration.contains("SettingsUiHarEngine"));
        assert!(!configuration.contains("rust_engine"));
        // The remaining configuration cards stay in place.
        assert!(configuration.contains("SettingsUiSdk"));
        assert!(configuration.contains("SettingsUiSemanticNavigation"));
        // The SDK path field is framed with the shared field colours.
        assert!(configuration.contains(".bg(colors.field)"));
        assert!(configuration.contains(".border_color(colors.border)"));
    }

    #[::core::prelude::v1::test]
    fn general_controls_keep_requested_compact_widths() {
        assert_eq!(GENERAL_DROPDOWN_WIDTH, 320. * 0.75);
        assert_eq!(GENERAL_CUSTOM_COLOR_WIDTH, 240. * 0.5);
        let implementation = include_str!("settings_page.rs");
        let general = implementation
            .split("fn settings_general(")
            .nth(1)
            .unwrap()
            .split("fn settings_configuration(")
            .next()
            .unwrap();
        assert_eq!(general.matches(".w(px(GENERAL_DROPDOWN_WIDTH))").count(), 3);
        assert!(general.contains(".w(relative(0.66))"));
        // The preset grid keeps a stable width so the wider custom column does
        // not squeeze the swatch labels.
        assert!(general.contains(".w(px(330.))"));
        let picker = implementation
            .split("fn sv_picker(")
            .nth(1)
            .unwrap()
            .split("fn ")
            .next()
            .unwrap();
        assert!(picker.contains(".w(px(GENERAL_CUSTOM_COLOR_WIDTH))"));
        assert!(!picker.contains("/ 240."));
        assert_eq!(picker.matches("/ f32::from(bounds.size.width)").count(), 2);
    }

    #[::core::prelude::v1::test]
    fn color_input_preserves_argb_and_rejects_partial_or_signed_values() {
        assert_eq!(parse_argb("#abc", true), Some(0xffaabbcc));
        assert_eq!(parse_argb("#80123456", false), Some(0x80123456));
        assert_eq!(parse_argb("123456", false), Some(0xff123456));
        for value in ["", "+12345", "#12345", "#gggggg", "123456789", "中文"] {
            assert_eq!(parse_argb(value, true), None);
        }
        assert_eq!(parse_argb("#abc", false), None);
    }
    #[::core::prelude::v1::test]
    fn hsv_primary_colors_and_roundtrip_are_stable() {
        for color in [0xff0000, 0x00ff00, 0x0000ff, 0x123456, 0xffffff, 0x000000] {
            let (h, s, v) = rgb_to_hsv(color);
            assert_eq!(hsv_rgb(h, s, v), color);
        }
    }
    #[::core::prelude::v1::test]
    fn sdk_preferences_replace_atomically_and_preserve_malformed_inputs() {
        let root = std::env::temp_dir().join(format!(
            "aps-sdk-settings-{}-{}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        std::fs::create_dir(&root).unwrap();
        let path = root.join("android-sdk.conf");
        save_sdk(&path, "").unwrap();
        assert_eq!(load_sdk(&path).unwrap(), "");
        std::fs::write(&path, "corrupt\npath").unwrap();
        assert!(load_sdk(&path).is_err());
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "corrupt\npath");
        assert!(save_sdk(&path, "invalid\npath").is_err());
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "corrupt\npath");
        std::fs::remove_file(&path).unwrap();
        std::fs::create_dir(&path).unwrap();
        std::fs::write(path.join("keep"), "unchanged").unwrap();
        assert!(save_sdk(&path, "").is_err());
        assert_eq!(
            std::fs::read_to_string(path.join("keep")).unwrap(),
            "unchanged"
        );
        assert_eq!(std::fs::read_dir(&root).unwrap().count(), 1);
        std::fs::remove_dir_all(root).unwrap();
    }
}
