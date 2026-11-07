//! Reference-shaped settings. Unsupported adapters are disabled, never fake-saved.
use super::*;
use crate::studio::{self, StudioColors};
use aps_ui_foundation::LayoutPreferences;
use gpui_kit::component::{
    input::{Input, InputEvent, InputState},
    slider::{Slider, SliderEvent, SliderState},
    switch::Switch,
};
const GENERAL_DROPDOWN_WIDTH: f32 = 240.;
const GENERAL_CUSTOM_COLOR_WIDTH: f32 = 120.;
/// Non-linguistic color literal placeholder; never translated.
const HEX_COLOR_PLACEHOLDER: &str = "#AARRGGBB";

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
}

impl SettingsUi {
    pub(super) fn new(
        settings: &Settings,
        path: Option<&Path>,
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
        }
    }
}

impl Studio {
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
                    SettingsPage::Simpleperf => {
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
                        this.settings_page = page;
                        cx.notify();
                    }))
            }));
        let content = match self.settings_page {
            SettingsPage::General => self.settings_general(cx),
            SettingsPage::Configuration => self.settings_configuration(cx),
            SettingsPage::LayoutInspector => self.settings_inspector(cx),
            SettingsPage::Simpleperf => self.settings_simpleperf(cx),
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
                    .when(self.settings_page != SettingsPage::Ai, |element| {
                        element.overflow_y_scroll()
                    })
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
            .flex_1()
            .min_w(px(130.))
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
        let custom = div()
            .w(px(GENERAL_CUSTOM_COLOR_WIDTH))
            .flex_shrink_0()
            .flex()
            .flex_col()
            .gap(px(8.))
            .child(studio::section_title(
                lang.text(TextKey::SettingsUiCustom),
                &colors,
            ))
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
            )
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
                    this.sync_color(0, 0xff000000 | this.settings.accent_color.rgb(), window, cx);
                    this.persist_settings(window, cx);
                })),
            );
        page(lang.text(TextKey::CommonGeneral), &colors)
            .child(div().px(px(8.)).child(language_menu))
            .child(div().px(px(8.)).child(theme_menu))
            .child(div().px(px(8.)).child(scale_menu))
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
                    .w(relative(0.5))
                    .min_w_0()
                    .overflow_x_scroll()
                    .child(
                        div()
                            .flex()
                            .w_full()
                            .gap(px(12.))
                            .min_w(px(262.))
                            .child(presets)
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
                    .child(Input::new(&self.settings_ui.sdk).disabled(self.settings_ui.sdk_busy))
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
                        card.child(div().text_color(colors.error).child(error))
                    }),
            )
            .child(
                studio::card(&colors)
                    .child(row(
                        lang.text(TextKey::SettingsUiCpuEngine),
                        self.rust_engine("cpu-engine", cx),
                    ))
                    .child(row(
                        lang.text(TextKey::SettingsUiHarEngine),
                        self.rust_engine("har-engine", cx),
                    ))
                    .child(studio::supporting(
                        lang.text(TextKey::SettingsUiEngineHint),
                        &colors,
                    )),
            )
            .into_any_element()
    }

    fn rust_engine(&self, id: &'static str, cx: &mut Context<Self>) -> AnyElement {
        let label = self
            .settings
            .language
            .text(TextKey::SettingsUiLegacyUnavailable);
        dropdown(id, "Rust", cx)
            .dropdown_menu(move |menu, _, _| {
                menu.item(PopupMenuItem::new("Rust"))
                    .item(PopupMenuItem::new(label).disabled(true))
            })
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
                            2 => this.settings.layout.hide_indices = *checked,
                            3 => this.settings.layout.show_ids = *checked,
                            4 => this.settings.layout.show_layer_buttons = *checked,
                            _ => this.settings.layout.show_visible_bounds = *checked,
                        };
                        this.persist_settings(window, cx);
                    })),
            ));
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
                            studio::tab_button(
                                ("settings-hit-order", index),
                                lang.text(key),
                                prefs.hit_smallest == smallest,
                                &colors,
                            )
                            .on_click(cx.listener(
                                move |this, _, window, cx| {
                                    this.settings.layout.hit_smallest = smallest;
                                    this.persist_settings(window, cx);
                                },
                            ))
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
                            .child(div().w(px(82.)).child(lang.text(key)))
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
            .into_any_element()
    }

    fn settings_simpleperf(&self, cx: &mut Context<Self>) -> AnyElement {
        let lang = self.settings.language;
        let colors = StudioColors::from_theme(cx);
        let no_target = self.targets.read(cx).selected_device().is_none()
            || self.targets.read(cx).selected_process().is_none();
        let mut templates = studio::card(&colors).child(studio::section_title(
            lang.text(TextKey::SettingsUiTemplates),
            &colors,
        ));
        for (index, key) in [
            TextKey::SettingsUiTemplateBasic,
            TextKey::SettingsUiTemplateUi,
            TextKey::SettingsUiTemplateNative,
            TextKey::SettingsUiTemplateLow,
            TextKey::SettingsUiTemplateSystem,
        ]
        .into_iter()
        .enumerate()
        {
            templates = templates.child(
                Button::new(("sampling-template", index))
                    .ghost()
                    .justify_start()
                    .disabled(true)
                    .label(lang.text(key)),
            );
        }
        templates = templates.child(studio::supporting(
            lang.text(TextKey::SettingsUiDeviceRequired),
            &colors,
        ));
        let duration = self.settings.capture_duration_seconds;
        let frequency = self.settings.capture_frequency_hz;
        let capture = studio::card(&colors)
            .child(studio::section_title(
                lang.text(TextKey::SettingsUiCapture),
                &colors,
            ))
            .child(row(
                lang.text(TextKey::SettingsUiEvent),
                dropdown("simpleperf-event", "cpu-clock", cx).disabled(true),
            ))
            .child(studio::supporting(
                lang.text(TextKey::SettingsUiAvailableEvents),
                &colors,
            ))
            .child(row(
                lang.text(TextKey::SettingsUiSampling),
                div()
                    .flex()
                    .gap(px(4.))
                    .child(
                        Button::new("sampling-frequency")
                            .label("Frequency")
                            .selected(true)
                            .disabled(true),
                    )
                    .child(
                        Button::new("sampling-period")
                            .label("Period")
                            .disabled(true),
                    ),
            ))
            .child(stepper(
                lang.text(TextKey::ShellFrequencyHz),
                frequency,
                Button::new("frequency-minus")
                    .label("−")
                    .disabled(frequency <= 1)
                    .on_click(cx.listener(|this, _, window, cx| {
                        this.settings.capture_frequency_hz =
                            this.settings.capture_frequency_hz.saturating_sub(1).max(1);
                        this.persist_settings(window, cx);
                    })),
                Button::new("frequency-plus")
                    .label("+")
                    .disabled(frequency >= 4000)
                    .on_click(cx.listener(|this, _, window, cx| {
                        this.settings.capture_frequency_hz =
                            (this.settings.capture_frequency_hz + 1).min(4000);
                        this.persist_settings(window, cx);
                    })),
            ))
            .child(stepper(
                lang.text(TextKey::ShellDurationSeconds),
                duration,
                Button::new("duration-minus")
                    .label("−")
                    .disabled(duration <= 1)
                    .on_click(cx.listener(|this, _, window, cx| {
                        this.settings.capture_duration_seconds = this
                            .settings
                            .capture_duration_seconds
                            .saturating_sub(1)
                            .max(1);
                        this.persist_settings(window, cx);
                    })),
                Button::new("duration-plus")
                    .label("+")
                    .disabled(duration >= 300)
                    .on_click(cx.listener(|this, _, window, cx| {
                        this.settings.capture_duration_seconds =
                            (this.settings.capture_duration_seconds + 1).min(300);
                        this.persist_settings(window, cx);
                    })),
            ))
            .when(no_target, |card| {
                card.child(studio::supporting(
                    lang.text(TextKey::SettingsUiDeviceRequired),
                    &colors,
                ))
            });
        let advanced = studio::card(&colors)
            .child(studio::section_title(
                lang.text(TextKey::SettingsUiAdvanced),
                &colors,
            ))
            .child(row(
                lang.text(TextKey::SettingsUiCallgraph),
                div().flex().gap(px(4.)).children(
                    ["DWARF", "Frame Pointer", "None"]
                        .into_iter()
                        .enumerate()
                        .map(|(i, label)| {
                            Button::new(("callgraph", i)).label(label).disabled(true)
                        }),
                ),
            ))
            .child(row(
                lang.text(TextKey::SettingsUiScope),
                div().flex().gap(px(4.)).children(
                    ["User", "Kernel", "Both"]
                        .into_iter()
                        .enumerate()
                        .map(|(i, label)| Button::new(("scope", i)).label(label).disabled(true)),
                ),
            ))
            .child(studio::supporting(
                lang.text(TextKey::SettingsUiDeviceRequired),
                &colors,
            ));
        let weak = cx.entity().downgrade();
        let tooltip = dropdown(
            "flame-tooltip",
            lang.text(if self.settings.simpleperf_follow_tooltip {
                TextKey::SettingsUiFollow
            } else {
                TextKey::SettingsUiFixed
            }),
            cx,
        )
        .dropdown_menu(move |menu, _, _| {
            [
                (false, TextKey::SettingsUiFixed),
                (true, TextKey::SettingsUiFollow),
            ]
            .into_iter()
            .fold(menu.min_w(px(200.)), |menu, (value, key)| {
                let weak = weak.clone();
                menu.item(
                    PopupMenuItem::new(lang.text(key)).on_click(move |_, window, cx| {
                        let _ = weak.update(cx, |this, cx| {
                            this.settings.simpleperf_follow_tooltip = value;
                            this.persist_settings(window, cx);
                        });
                    }),
                )
            })
        });
        page(lang.text(TextKey::CommonSimpleperf), &colors)
            .child(templates)
            .child(capture)
            .child(advanced)
            .child(
                studio::card(&colors)
                    .child(studio::section_title(
                        lang.text(TextKey::SettingsUiFlame),
                        &colors,
                    ))
                    .child(row(lang.text(TextKey::SettingsUiTooltip), tooltip)),
            )
            .child(
                studio::card(&colors)
                    .child(studio::section_title(
                        lang.text(TextKey::SettingsUiAnalysis),
                        &colors,
                    ))
                    .child(dropdown("simpleperf-viewer", "Local", cx).dropdown_menu(
                        |menu, _, _| {
                            menu.item(PopupMenuItem::new("Local"))
                                .item(PopupMenuItem::new("Firefox Profiler Local").disabled(true))
                                .item(PopupMenuItem::new("Firefox Profiler").disabled(true))
                        },
                    ))
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
                        lang.text(TextKey::SettingsUiGuideBody),
                        &colors,
                    ))
                    .child(
                        studio::text_button(
                            "settings-open-capture",
                            lang.text(TextKey::SettingsUiOpenCapture),
                            true,
                            false,
                            &colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.destination = Destination::Capture;
                            cx.notify();
                        })),
                    ),
            )
            .into_any_element()
    }

    fn settings_about(&self, cx: &mut Context<Self>) -> AnyElement {
        let lang = self.settings.language;
        let colors = StudioColors::from_theme(cx);
        page(lang.text(TextKey::CommonAbout), &colors)
            .child(div().child(lang.text(TextKey::FoundationAndroidPerformanceStudio)))
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
                        div()
                            .flex_1()
                            .min_w(px(100.))
                            .child(Input::new(&self.settings_ui.colors[index])),
                    ),
            )
            .when(self.settings_ui.color_error == Some(index), |element| {
                element.child(
                    div()
                        .text_size(px(11.))
                        .text_color(colors.error)
                        .child(lang.text(TextKey::SettingsUiHexError)),
                )
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
                .child(title.to_owned()),
        )
}
fn row(label: &str, control: impl IntoElement) -> AnyElement {
    div()
        .flex()
        .items_center()
        .justify_between()
        .gap(px(8.))
        .px(px(8.))
        .child(div().flex_1().min_w_0().child(label.to_owned()))
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
fn stepper(label: &str, value: u32, minus: Button, plus: Button) -> AnyElement {
    row(
        label,
        div()
            .flex()
            .w(px(320.))
            .items_center()
            .gap(px(8.))
            .child(minus)
            .child(div().w(px(64.)).text_center().child(value.to_string()))
            .child(plus),
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
mod tests {
    use super::*;
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
        assert!(general.contains(".w(relative(0.5))"));
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
