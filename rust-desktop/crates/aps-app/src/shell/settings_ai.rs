//! Settings navigation does not authorize AI transport, discover local secrets,
//! import code, or silently enable execution permissions.
use super::*;
use crate::studio::{self, StudioColors};
use gpui_kit::component::checkbox::Checkbox;

impl Studio {
    pub(super) fn settings_ai(&self, cx: &mut Context<Self>) -> AnyElement {
        let language = self.settings.language;
        let colors = StudioColors::from_theme(cx);
        let tab = self.settings.ai_settings_tab.min(3);
        let tabs = [
            TextKey::AiSettingsProvider,
            TextKey::AiSettingsAgent,
            TextKey::AiSettingsSkill,
            TextKey::AiSettingsPolicy,
        ];
        let selector = div().flex().justify_center().flex_shrink_0().child(
            div()
                .id("ai-settings-tabs")
                .flex()
                .overflow_x_scroll()
                .border_1()
                .border_color(colors.border)
                .rounded(px(6.))
                .children(tabs.into_iter().enumerate().map(|(index, key)| {
                    studio::tab_button(
                        ("ai-settings-tab", index),
                        language.text(key),
                        tab as usize == index,
                        &colors,
                    )
                    .h(px(30.))
                    .on_click(cx.listener(move |this, _, window, cx| {
                        this.settings.ai_settings_tab = index as u8;
                        this.persist_settings(window, cx);
                    }))
                })),
        );
        let body = match tab {
            0 => div()
                .flex()
                .flex_col()
                .gap(px(4.))
                .child(self.ai.update(cx, |ai, cx| ai.settings_provider_panel(cx)))
                .child(
                    studio::text_button(
                        "ai-settings-open-analysis",
                        language.text(TextKey::AiSettingsOpenPreflight),
                        true,
                        false,
                        &colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.destination = Destination::Ai;
                        cx.notify();
                    })),
                ),
            1 | 2 => {
                let heading = language.text(if tab == 1 {
                    TextKey::AiSettingsAgent
                } else {
                    TextKey::AiSettingsSkill
                });
                div()
                    .flex()
                    .flex_col()
                    .gap(px(4.))
                    .child(
                        studio::card(&colors)
                            .child(studio::section_title(
                                language.text(TextKey::AiSettingsPackageImport),
                                &colors,
                            ))
                            .child(
                                div()
                                    .flex()
                                    .items_center()
                                    .flex_wrap()
                                    .gap(px(8.))
                                    .child(
                                        div()
                                            .w(px(320.))
                                            .h(px(28.))
                                            .px(px(8.))
                                            .flex()
                                            .items_center()
                                            .border_1()
                                            .border_color(colors.border)
                                            .rounded(px(6.))
                                            .text_color(colors.muted_text)
                                            .child(
                                                language
                                                    .text(TextKey::AiSettingsDirectoryPlaceholder),
                                            ),
                                    )
                                    .child(studio::text_button(
                                        "ai-package-browse",
                                        language.text(TextKey::AiSettingsBrowse),
                                        false,
                                        false,
                                        &colors,
                                    ))
                                    .child(studio::text_button(
                                        "ai-package-import",
                                        language.text(TextKey::AiSettingsImport),
                                        false,
                                        true,
                                        &colors,
                                    )),
                            )
                            .child(studio::supporting(
                                language.text(TextKey::AiSettingsPackageUnavailable),
                                &colors,
                            )),
                    )
                    .child(
                        studio::card(&colors)
                            .child(studio::section_title(heading, &colors))
                            .child(studio::supporting(
                                language.text(TextKey::AiSettingsNoPackages),
                                &colors,
                            )),
                    )
            }
            _ => div()
                .flex()
                .flex_col()
                .gap(px(4.))
                .child(
                    studio::card(&colors)
                        .child(studio::section_title(
                            language.text(TextKey::AiSettingsPermissions),
                            &colors,
                        ))
                        .child(
                            Checkbox::new("ai-policy-report-permission")
                                .label(language.text(TextKey::AiSettingsAllowReports))
                                .checked(false)
                                .disabled(true),
                        )
                        .child(
                            Checkbox::new("ai-policy-script-permission")
                                .label(language.text(TextKey::AiSettingsAllowScripts))
                                .checked(false)
                                .disabled(true),
                        )
                        .child(studio::supporting(
                            language.text(TextKey::AiSettingsPermissionsUnavailable),
                            &colors,
                        )),
                )
                .child(
                    studio::card(&colors)
                        .child(studio::section_title(
                            language.text(TextKey::AiSettingsReportOutput),
                            &colors,
                        ))
                        .child(ai_disabled_selector(
                            "ai-report-format",
                            language.text(TextKey::AiSettingsReportFormat),
                            "HTML (.html)",
                            &colors,
                        ))
                        .child(ai_disabled_selector(
                            "ai-report-language",
                            language.text(TextKey::AiSettingsReportLanguage),
                            language.text(TextKey::AiSettingsAuto),
                            &colors,
                        ))
                        .child(studio::supporting(
                            language.text(TextKey::AiSettingsReportUnavailable),
                            &colors,
                        )),
                )
                .child(
                    studio::card(&colors)
                        .child(studio::section_title(
                            language.text(TextKey::AiSettingsHistory),
                            &colors,
                        ))
                        .child(studio::supporting(
                            language.text(TextKey::AiSettingsHistoryPolicy),
                            &colors,
                        ))
                        .child(studio::text_button(
                            "ai-clear-history",
                            language.text(TextKey::AiSettingsClearHistory),
                            false,
                            false,
                            &colors,
                        )),
                ),
        };
        div()
            .size_full()
            .min_h_0()
            .flex()
            .flex_col()
            .gap(px(8.))
            .p(px(4.))
            .text_size(px(typography::BODY))
            .child(
                div()
                    .flex_shrink_0()
                    .text_size(px(typography::TITLE))
                    .font_weight(FontWeight::SEMIBOLD)
                    .child(crate::studio::selectable(
                        language.text(TextKey::AiSettingsTitle),
                    )),
            )
            .child(selector)
            .child(
                div()
                    .id("ai-settings-category-content")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .child(body),
            )
            .into_any_element()
    }
}

fn ai_disabled_selector(
    id: &'static str,
    label: &'static str,
    value: &'static str,
    colors: &StudioColors,
) -> Div {
    div()
        .flex()
        .flex_col()
        .gap(px(4.))
        .child(studio::section_title(label, colors))
        .child(
            Button::new(id)
                .w(px(320.))
                .h(px(28.))
                .justify_start()
                .label(value)
                .disabled(true),
        )
}
