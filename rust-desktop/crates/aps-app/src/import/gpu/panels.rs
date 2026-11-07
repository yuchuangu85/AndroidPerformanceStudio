//! GPU hub panels display indexed facts only, never decoded AGI frame metrics.
use super::*;
use aps_gpu::{AgiCapability, AgiLaunchMode};
use studio::StudioColors;

const PAGE_LIMIT: usize = 100;
const TABLE_WEIGHTS: [f32; 8] = [1.1, 1.8, 1.2, 1.4, 0.9, 1.2, 1.1, 1.0];

fn supporting(text: impl Into<SharedString>, colors: &StudioColors) -> Div {
    studio::supporting(text, colors)
        .line_height(px(15.))
        .flex_shrink_0()
}
fn body(text: impl Into<SharedString>, colors: &StudioColors) -> Div {
    div()
        .text_size(px(13.))
        .line_height(px(18.))
        .text_color(colors.text)
        .flex_shrink_0()
        .child(crate::studio::selectable(text.into()))
}
fn context(key: TextKey, value: Option<String>, language: Language, colors: &StudioColors) -> Div {
    div()
        .flex()
        .flex_col()
        .min_w_0()
        .gap(px(4.))
        .flex_shrink_0()
        .child(supporting(language.text(key), colors))
        .child(body(
            value.unwrap_or_else(|| language.text(TextKey::GpuUnknown).into()),
            colors,
        ))
}
fn section(key: TextKey, language: Language, colors: &StudioColors) -> Div {
    studio::section_title(language.text(key), colors)
        .line_height(px(18.))
        .flex_shrink_0()
}
fn route_key(route: OpenRoute) -> TextKey {
    match route {
        OpenRoute::Agi => TextKey::GpuRouteAgi,
        OpenRoute::Perfetto => TextKey::GpuRoutePerfetto,
        OpenRoute::Desktop => TextKey::GpuRouteDesktop,
        OpenRoute::None => TextKey::GpuRouteNone,
    }
}
fn launch_key(capability: Option<&AgiCapability>) -> TextKey {
    match capability.map(|c| c.launch_mode) {
        Some(AgiLaunchMode::VerifiedCli) => TextKey::GpuLaunchModeVerifiedCli,
        Some(AgiLaunchMode::GuiOnly) => TextKey::GpuLaunchModeGuiOnly,
        Some(AgiLaunchMode::Unsupported) => TextKey::GpuLaunchModeUnsupported,
        None => TextKey::GpuUnknown,
    }
}
fn closed_panels(language: Language, colors: &StudioColors) -> Div {
    let mut panel = div()
        .flex()
        .flex_col()
        .gap(px(8.))
        .flex_shrink_0()
        .child(section(TextKey::GpuClosedPanelsTitle, language, colors));
    for key in [
        TextKey::GpuClosedPanelGpuCounters,
        TextKey::GpuClosedPanelShaderHotspots,
        TextKey::GpuClosedPanelRenderPasses,
        TextKey::GpuClosedPanelDrawCalls,
        TextKey::GpuClosedPanelOverdraw,
    ] {
        panel = panel.child(
            supporting(format!("• {}", language.text(key)), colors).text_color(colors.warning),
        );
    }
    panel.child(supporting(
        language.text(TextKey::GpuClosedPanelsHint),
        colors,
    ))
}
fn flag(key: TextKey, value: Option<bool>, language: Language, colors: &StudioColors) -> Div {
    context(
        key,
        Some(
            language
                .text(match value {
                    Some(true) => TextKey::GpuYes,
                    Some(false) => TextKey::GpuNo,
                    None => TextKey::GpuUnknown,
                })
                .into(),
        ),
        language,
        colors,
    )
}
fn capability_panel(
    capability: Option<&AgiCapability>,
    language: Language,
    colors: &StudioColors,
    full: bool,
) -> Div {
    let mut panel = studio::card(colors)
        .flex_shrink_0()
        .child(section(
            if full {
                TextKey::GpuToolCapability
            } else {
                TextKey::GpuAgiCapability
            },
            language,
            colors,
        ))
        .child(supporting(
            language.text(TextKey::GpuHubPositioning),
            colors,
        ))
        .child(context(
            TextKey::GpuLaunchMode,
            Some(language.text(launch_key(capability)).into()),
            language,
            colors,
        ))
        .child(context(
            TextKey::GpuAgiVersion,
            capability.and_then(|c| c.version.clone()),
            language,
            colors,
        ))
        .child(context(
            TextKey::GpuAgiExecutable,
            capability.map(|c| c.executable.display().to_string()),
            language,
            colors,
        ))
        .child(flag(
            TextKey::GpuCapabilityLaunchSupported,
            capability.map(|c| launch_supported(Some(c))),
            language,
            colors,
        ))
        .child(flag(
            TextKey::GpuCapabilityArtifactOpenSupported,
            capability.map(|c| c.artifact_open_supported),
            language,
            colors,
        ));
    let arguments = capability
        .map(|c| c.stable_arguments.join(" "))
        .filter(|v| !v.is_empty())
        .unwrap_or_else(|| language.text(TextKey::GpuCapabilityNoArguments).into());
    panel = panel
        .child(context(
            TextKey::GpuCapabilitySupportedArguments,
            Some(arguments),
            language,
            colors,
        ))
        .child(section(TextKey::GpuCapabilityWarnings, language, colors));
    match capability {
        None => panel = panel.child(supporting(language.text(TextKey::GpuUnknown), colors)),
        Some(c) if c.warnings.is_empty() => {
            panel = panel.child(supporting(
                language.text(TextKey::GpuCapabilityNoWarnings),
                colors,
            ))
        }
        Some(c) => {
            for warning in &c.warnings {
                panel = panel.child(
                    supporting(localized_warning(warning, language), colors)
                        .text_color(colors.warning),
                );
            }
        }
    }
    if full {
        panel = panel
            .child(studio::divider(colors))
            .child(closed_panels(language, colors));
    }
    panel
}
fn localized_warning(warning: &str, language: Language) -> String {
    let key = match warning {
        "AGI version could not be verified." => Some(TextKey::GpuWarningAgiVersionUnknown),
        "AGI help probe did not complete successfully." => {
            Some(TextKey::GpuWarningAgiHelpProbeFailed)
        }
        "Stable AGI artifact-open arguments were not detected." => {
            Some(TextKey::GpuWarningAgiArtifactOpenUnverified)
        }
        "Stable AGI artifact-open arguments are not verified; open artifacts from the AGI application." => {
            Some(TextKey::GpuAgiManualOpen)
        }
        _ => None,
    };
    key.map_or_else(|| warning.to_owned(), |key| language.text(key).into())
}

fn table_cell(value: String, column: usize, colors: &StudioColors) -> Div {
    body(value, colors)
        .flex_basis(px(0.))
        .flex_grow(TABLE_WEIGHTS[column])
        .min_w_0()
        .px(px(3.))
        .whitespace_nowrap()
        .text_ellipsis()
}

impl ImportWorkspace {
    pub(super) fn gpu_center(
        &mut self,
        wide: bool,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let device = self.gpu.device.as_ref();
        let mut context_panel = studio::card(colors).flex_shrink_0().child(
            div()
                .flex()
                .flex_wrap()
                .items_center()
                .gap(px(8.))
                .child(section(TextKey::GpuDeviceGraphicsContext, language, colors).flex_1())
                .child(
                    studio::compact_text_button(
                        "gpu-refresh-device",
                        language.text(TextKey::GpuRefreshDevices),
                        !self.gpu.device_busy && self.gpu.device_serial.is_some(),
                        false,
                        colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.gpu_refresh_device(cx))),
                ),
        );
        if self.gpu.device_busy {
            context_panel = context_panel.child(supporting(
                language.text(TextKey::GpuReadingDeviceContext),
                colors,
            ));
        } else if device.is_none() {
            context_panel = context_panel.child(supporting(
                language.text(if self.gpu.device_serial.is_some() {
                    TextKey::GpuDeviceUnavailable
                } else {
                    TextKey::GpuNoConnectedDevices
                }),
                colors,
            ));
        }
        if let Some(error) = &self.gpu.device_error {
            context_panel =
                context_panel.child(supporting(error.clone(), colors).text_color(colors.error));
        }
        let values = [
            (
                TextKey::GpuGpuRenderer,
                device.and_then(|d| d.gpu_renderer.clone()),
            ),
            (
                TextKey::GpuGpuVendor,
                device.and_then(|d| d.gpu_vendor.clone()),
            ),
            (TextKey::GpuGpuDriver, device.and_then(|d| d.driver.clone())),
            (
                TextKey::GpuGraphicsApi,
                device.and_then(|d| d.graphics_api.clone()),
            ),
            (
                TextKey::GpuGraphicsImplementation,
                device.and_then(|d| d.graphics_implementation.clone()),
            ),
        ];
        context_panel = context_panel.child(div().flex().flex_wrap().gap(px(8.)).children(
            values.into_iter().map(|(key, value)| {
                context(key, value, language, colors)
                    .flex_1()
                    .min_w(px(100.))
            }),
        ));
        let mut tabs = div().flex().flex_wrap().gap(px(4.)).flex_shrink_0();
        for (index, (tab, key)) in [
            (GpuTab::Overview, TextKey::GpuTabOverview),
            (GpuTab::Artifacts, TextKey::GpuTabArtifacts),
            (GpuTab::RelatedEvidence, TextKey::GpuRelatedEvidence),
            (GpuTab::ToolCapability, TextKey::GpuToolCapability),
        ]
        .into_iter()
        .enumerate()
        {
            tabs = tabs.child(
                studio::tab_button(
                    ("gpu-hub-tab", index),
                    language.text(key),
                    self.gpu_tab == tab,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.gpu_tab = tab;
                    cx.notify();
                })),
            );
        }
        let selected_title = self
            .gpu
            .selected_record()
            .map(GpuRecord::title)
            .unwrap_or_else(|| language.text(TextKey::GpuNoGpuArtifacts).into());
        let tab_content = match self.gpu_tab {
            GpuTab::Overview => self.gpu_preview(wide, colors, cx),
            GpuTab::Artifacts => self.gpu_artifacts_table(wide, colors, cx),
            GpuTab::RelatedEvidence => studio::card(colors)
                .flex_shrink_0()
                .when(wide, |panel| panel.flex_grow(1.))
                .min_h(px(180.))
                .child(section(TextKey::GpuRelatedEvidence, language, colors))
                .child(supporting(
                    language.text(TextKey::GpuRelatedUnavailable),
                    colors,
                ))
                .into_any_element(),
            GpuTab::ToolCapability => {
                capability_panel(self.gpu.capability.as_ref(), language, colors, true)
                    .when(wide, |panel| panel.flex_grow(1.))
                    .into_any_element()
            }
        };
        // The whole center scrolls, not just the last panel: short windows keep
        // device errors, every tab and all recovery actions reachable.
        div()
            .id("gpu-analysis-center")
            .flex()
            .flex_col()
            .gap(px(4.))
            .min_w_0()
            .when(wide, |s| s.size_full().min_h_0().overflow_y_scroll())
            .when(!wide, |s| s.flex_shrink_0())
            .child(context_panel)
            .child(tabs)
            .child(
                supporting(selected_title, colors)
                    .whitespace_nowrap()
                    .text_ellipsis(),
            )
            .child(tab_content)
            .into_any_element()
    }

    fn gpu_preview(&self, wide: bool, colors: &StudioColors, cx: &mut Context<Self>) -> AnyElement {
        let language = self.language;
        let mut panel = studio::card(colors)
            .flex_shrink_0()
            .when(wide, |panel| panel.flex_grow(1.))
            .min_h(px(220.))
            .child(section(TextKey::GpuArtifactPreview, language, colors));
        if let Some(record) = self.gpu.selected_record() {
            let artifact = &record.artifact;
            panel = panel.child(
                div()
                    .flex()
                    .flex_wrap()
                    .items_center()
                    .gap(px(8.))
                    .child(
                        body(record.title(), colors)
                            .font_weight(FontWeight::SEMIBOLD)
                            .flex_1()
                            .min_w_0(),
                    )
                    .child(supporting(language.text(status_key(record.status)), colors))
                    .child(
                        studio::compact_text_button(
                            "gpu-preview-open",
                            language.text(open_key(Some(record), self.gpu.capability.as_ref())),
                            !self.busy && can_open(Some(record), self.gpu.capability.as_ref()),
                            true,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.gpu_open(cx))),
                    ),
            );
            let unknown = language.text(TextKey::GpuUnknown).to_owned();
            panel = panel
                .child(
                    div().flex().flex_wrap().gap(px(12.)).children(
                        [
                            (
                                TextKey::GpuArtifactColumnKind,
                                language.text(kind_key(artifact.kind)).to_owned(),
                            ),
                            (TextKey::GpuArtifactColumnPackage, unknown.clone()),
                            (TextKey::GpuArtifactColumnDevice, unknown.clone()),
                            (TextKey::GpuGraphicsApi, unknown.clone()),
                            (TextKey::GpuArtifactSize, size_text(artifact.size_bytes)),
                            (TextKey::GpuArtifactColumnCaptured, unknown.clone()),
                            (TextKey::GpuArtifactAgiVersion, unknown.clone()),
                            (TextKey::GpuGraphicsImplementation, unknown.clone()),
                            (TextKey::GpuArtifactNotesLabel, unknown.clone()),
                            (
                                TextKey::GpuArtifactOpenRoute,
                                language.text(route_key(artifact.open_route)).to_owned(),
                            ),
                        ]
                        .into_iter()
                        .map(|(key, value)| {
                            context(key, Some(value), language, colors)
                                .min_w(px(120.))
                                .flex_1()
                        }),
                    ),
                )
                .child(supporting(language.text(TextKey::GpuMetadataHint), colors))
                .child(studio::divider(colors))
                .child(context(
                    TextKey::GpuArtifactHash,
                    Some(artifact.sha256.to_hex()),
                    language,
                    colors,
                ));
            for path in artifact.locations() {
                panel = panel.child(context(
                    TextKey::GpuArtifactLocation,
                    Some(path.display().to_string()),
                    language,
                    colors,
                ));
            }
            let copy = format!(
                "{}\nSHA-256 {}\n{} B\n{}",
                record.title(),
                artifact.sha256.to_hex(),
                artifact.size_bytes,
                artifact
                    .locations()
                    .iter()
                    .map(|p| p.display().to_string())
                    .collect::<Vec<_>>()
                    .join("\n")
            );
            panel = panel.child(
                studio::compact_text_button(
                    "gpu-copy-metadata",
                    language.text(TextKey::GpuCopyMetadata),
                    true,
                    false,
                    colors,
                )
                .on_click(cx.listener(move |_, _, _, cx| {
                    cx.write_to_clipboard(ClipboardItem::new_string(copy.clone()))
                })),
            );
        } else {
            panel = panel.child(supporting(
                language.text(TextKey::GpuSelectAnalysisArtifact),
                colors,
            ));
        }
        panel
            .child(studio::divider(colors))
            .child(closed_panels(language, colors))
            .into_any_element()
    }

    fn gpu_artifacts_table(
        &self,
        wide: bool,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let total = self.gpu.artifacts.len();
        let offset = self
            .gpu
            .artifact_offset
            .min(total.saturating_sub(1) / PAGE_LIMIT * PAGE_LIMIT);
        let end = offset.saturating_add(PAGE_LIMIT).min(total);
        let header = div()
            .flex()
            .h(px(28.))
            .items_center()
            .px(px(4.))
            .bg(colors.section_background)
            .children(
                [
                    TextKey::GpuArtifactColumnKind,
                    TextKey::GpuArtifactColumnFile,
                    TextKey::GpuArtifactColumnDevice,
                    TextKey::GpuArtifactColumnPackage,
                    TextKey::GpuGraphicsApi,
                    TextKey::GpuArtifactColumnCaptured,
                    TextKey::GpuArtifactColumnStatus,
                    TextKey::GpuArtifactColumnAction,
                ]
                .into_iter()
                .enumerate()
                .map(|(index, key)| table_cell(language.text(key).into(), index, colors)),
            );
        let mut rows = div()
            .flex()
            .flex_col()
            .min_h(px(120.))
            .max_h(px(360.))
            .overflow_y_scrollbar();
        if total == 0 {
            rows = rows.child(div().p(px(8.)).child(supporting(
                language.text(TextKey::GpuNoGpuArtifacts),
                colors,
            )));
        }
        for (index, record) in self
            .gpu
            .artifacts
            .iter()
            .enumerate()
            .skip(offset)
            .take(PAGE_LIMIT)
        {
            let id = record.id();
            let values = [
                language.text(kind_key(record.artifact.kind)).to_owned(),
                record.title(),
                language.text(TextKey::GpuUnknown).into(),
                language.text(TextKey::GpuUnknown).into(),
                language.text(TextKey::GpuUnknown).into(),
                language.text(TextKey::GpuUnknown).into(),
                language.text(status_key(record.status)).into(),
            ];
            let selected = self.gpu.selected.as_deref() == Some(id.as_str());
            let open_id = id.clone();
            rows = rows.child(
                div()
                    .flex()
                    .h(px(32.))
                    .flex_shrink_0()
                    .items_center()
                    .px(px(4.))
                    .border_b_1()
                    .border_color(colors.border)
                    .bg(if selected {
                        colors.selected_row
                    } else {
                        colors.panel
                    })
                    .child(
                        Button::new(("gpu-artifact-row", index))
                            .ghost()
                            .selected(selected)
                            .h_full()
                            .rounded_none()
                            .justify_start()
                            .px_0()
                            .flex_basis(px(0.))
                            .flex_grow(TABLE_WEIGHTS[..7].iter().sum())
                            .min_w_0()
                            .accessibility_label(values.join(" · "))
                            .child(
                                div().flex().items_center().size_full().children(
                                    values
                                        .into_iter()
                                        .enumerate()
                                        .map(|(column, value)| table_cell(value, column, colors)),
                                ),
                            )
                            .on_click(
                                cx.listener(move |this, _, _, cx| this.gpu_select(id.clone(), cx)),
                            ),
                    )
                    .child(
                        div()
                            .flex_basis(px(0.))
                            .flex_grow(TABLE_WEIGHTS[7])
                            .px(px(3.))
                            .child(
                                studio::compact_text_button(
                                    ("gpu-artifact-open", index),
                                    language
                                        .text(open_key(Some(record), self.gpu.capability.as_ref())),
                                    !self.busy
                                        && can_open(Some(record), self.gpu.capability.as_ref()),
                                    false,
                                    colors,
                                )
                                .on_click(cx.listener(
                                    move |this, _, _, cx| {
                                        this.gpu_select(open_id.clone(), cx);
                                        this.gpu_open(cx);
                                    },
                                )),
                            ),
                    ),
            );
        }
        studio::card(colors)
            .flex_shrink_0()
            .when(wide, |panel| panel.flex_grow(1.))
            .child(section(TextKey::GpuTabArtifacts, language, colors))
            .child(supporting(language.text(TextKey::GpuMetadataHint), colors))
            .child(
                div()
                    .min_w_0()
                    .overflow_x_scrollbar()
                    .child(div().min_w(px(920.)).w_full().child(header).child(rows)),
            )
            .child(
                div()
                    .flex()
                    .flex_wrap()
                    .items_center()
                    .gap(px(8.))
                    .child(
                        studio::compact_text_button(
                            "gpu-artifacts-prev",
                            language.text(TextKey::GpuPrevious),
                            offset > 0,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.gpu.artifact_offset =
                                this.gpu.artifact_offset.saturating_sub(PAGE_LIMIT);
                            cx.notify();
                        })),
                    )
                    .child(supporting(
                        language.format(
                            TextKey::GpuPage,
                            &[
                                ("start", &(if total == 0 { 0 } else { offset + 1 })),
                                ("end", &end),
                                ("total", &total),
                            ],
                        ),
                        colors,
                    ))
                    .child(
                        studio::compact_text_button(
                            "gpu-artifacts-next",
                            language.text(TextKey::GpuNext),
                            end < total,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.gpu.artifact_offset =
                                this.gpu.artifact_offset.saturating_add(PAGE_LIMIT);
                            cx.notify();
                        })),
                    ),
            )
            .into_any_element()
    }

    pub(super) fn gpu_insights(&self, colors: &StudioColors, cx: &mut Context<Self>) -> AnyElement {
        let language = self.language;
        let record = self.gpu.selected_record();
        let mut open = studio::card(colors).flex_shrink_0().child(section(
            TextKey::GpuOpenWith,
            language,
            colors,
        ));
        if let Some(record) = record {
            open = open
                .child(body(record.title(), colors))
                .child(supporting(language.text(status_key(record.status)), colors));
        }
        open = open
            .child(supporting(
                language.text(TextKey::GpuToolResponsibilityHint),
                colors,
            ))
            .child(studio::divider(colors))
            .child(
                studio::text_button(
                    "gpu-open-primary",
                    language.text(open_key(record, self.gpu.capability.as_ref())),
                    !self.busy
                        && record.is_some_and(|r| can_open(Some(r), self.gpu.capability.as_ref())),
                    true,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.gpu_open(cx))),
            )
            .child(
                div()
                    .flex()
                    .flex_wrap()
                    .gap(px(4.))
                    .child(
                        studio::compact_text_button(
                            "gpu-open-verify",
                            language.text(TextKey::GpuVerify),
                            !self.busy && record.is_some(),
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.gpu_verify(cx))),
                    )
                    .child(
                        studio::compact_text_button(
                            "gpu-open-reveal",
                            language.text(TextKey::GpuShowInFiles),
                            !self.busy && record.is_some(),
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.gpu_reveal(cx))),
                    )
                    .child(
                        studio::compact_text_button(
                            "gpu-open-relocate",
                            language.text(TextKey::GpuRelocate),
                            !self.busy && record.is_some(),
                            false,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.gpu_relocate(cx))),
                    ),
            );
        div()
            .flex()
            .flex_col()
            .gap(px(4.))
            .min_w_0()
            .flex_shrink_0()
            .child(capability_panel(
                self.gpu.capability.as_ref(),
                language,
                colors,
                false,
            ))
            .child(open)
            .into_any_element()
    }
}
