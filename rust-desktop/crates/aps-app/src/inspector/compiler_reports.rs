//! Optional static compiler evidence. Import has its own lifecycle: it neither
//! replaces a capture nor grants permission to attach a runtime inspector.
use super::*;
use aps_layout_inspector::{CompilerFunctionMatch, CompilerReport, CompilerStability};

const MAX_COMPILER_PARAMETER_ROWS: usize = 32;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ImportSource {
    File,
    Folder,
}

impl ImportSource {
    const ALL: [Self; 2] = [Self::File, Self::Folder];

    fn label(self, language: Language) -> &'static str {
        language.text(match self {
            Self::File => TextKey::ComposeReportsFile,
            Self::Folder => TextKey::ComposeReportsFolder,
        })
    }

    fn picker_options(self, language: Language) -> PathPromptOptions {
        PathPromptOptions {
            files: self == Self::File,
            directories: self == Self::Folder,
            multiple: false,
            prompt: Some(self.label(language).into()),
        }
    }
}

#[derive(Default)]
pub(super) struct CompilerReportUi {
    pub(super) report: Option<Arc<CompilerReport>>,
    importing: bool,
    cancel: CancellationToken,
    pub(super) result: Option<Result<Arc<CompilerReport>, String>>,
    pub(super) notice: Option<NoticeSnapshot>,
    pub(super) timing: ImportTiming,
}

impl CompilerReportUi {
    fn begin(&mut self) -> bool {
        if self.importing {
            return false;
        }
        self.importing = true;
        true
    }

    fn finish(&mut self, result: Option<Result<Arc<CompilerReport>, String>>, language: Language) {
        self.importing = false;
        let Some(result) = result else {
            // Native picker cancellation must not erase the last report or its diagnostics.
            return;
        };
        self.notice = Some(match &result {
            Ok(report) => {
                self.report = Some(report.clone());
                let message = result_text(language, &result);
                if report.warnings.is_empty() {
                    NoticeSnapshot::info(crate::evidence::clipped(&message, 8192))
                } else {
                    NoticeSnapshot::warning(crate::evidence::clipped(&message, 8192))
                }
            }
            Err(detail) => NoticeSnapshot::error(
                language.format(TextKey::ComposeReportsFailed, &[("detail", detail)]),
            ),
        });
        self.result = Some(result);
    }
}

impl Drop for CompilerReportUi {
    fn drop(&mut self) {
        self.cancel.cancel();
    }
}

impl InspectorWorkspace {
    fn pick_compiler_report(&mut self, source: ImportSource, cx: &mut Context<Self>) {
        if !self.compiler_reports.begin() {
            return;
        }
        let picker = cx.prompt_for_paths(source.picker_options(self.language));
        let executor = cx.background_executor().clone();
        let cancel = self.compiler_reports.cancel.clone();
        cx.notify();
        cx.spawn(async move |this, cx| {
            let mut started = None;
            let result = match picker.await {
                Ok(Ok(Some(mut paths))) if paths.len() == 1 => {
                    let path = paths.remove(0);
                    started = Some(std::time::Instant::now());
                    Some(
                        executor
                            .spawn(async move {
                                CompilerReport::parse(&path, &cancel)
                                    .map(Arc::new)
                                    .map_err(|error| error.to_string())
                            })
                            .await,
                    )
                }
                Ok(Ok(_)) => None,
                Ok(Err(error)) => Some(Err(error.to_string())),
                Err(error) => Some(Err(error.to_string())),
            };
            this.update(cx, |this, cx| {
                let success = result.as_ref().is_some_and(|result| result.is_ok());
                this.compiler_reports.finish(result, this.language);
                if success && let Some(started) = started {
                    this.compiler_reports.timing = ImportTiming::completed(started);
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}

pub(super) fn summary(language: Language, report: &CompilerReport) -> String {
    language.format(
        TextKey::ComposeReportsSummary,
        &[
            ("files", &report.files.len()),
            ("functions", &report.functions.len()),
            ("classes", &report.classes.len()),
        ],
    )
}

fn result_text(language: Language, result: &Result<Arc<CompilerReport>, String>) -> String {
    match result {
        Err(detail) => language.format(TextKey::ComposeReportsFailed, &[("detail", detail)]),
        Ok(report) => {
            let mut text = format!(
                "{}\n\n{}\n{}\n\n{}\n{}",
                summary(language, report),
                language.text(TextKey::ComposeReportsSourceDirectory),
                report.source_directory.display(),
                language.text(TextKey::ComposeReportsFiles),
                report
                    .files
                    .iter()
                    .map(|file| file.display().to_string())
                    .collect::<Vec<_>>()
                    .join("\n"),
            );
            if !report.warnings.is_empty() {
                text.push_str(&format!(
                    "\n\n{}\n{}",
                    language.text(TextKey::ComposeReportsWarnings),
                    report.warnings.join("\n")
                ));
            }
            text
        }
    }
}

/// Use the same dropdown component and outline metrics as the capture target.
pub(super) fn import_control(
    language: Language,
    colors: &StudioColors,
    ui: &CompilerReportUi,
    cx: &mut Context<InspectorWorkspace>,
) -> AnyElement {
    let label = language.text(if ui.importing {
        TextKey::ComposeReportsImporting
    } else {
        TextKey::ComposeReportsImport
    });
    let entity = cx.entity().downgrade();
    Button::new("inspector-import-compose-reports")
        .ghost()
        .h(px(TOOLBAR_BUTTON_HEIGHT))
        .px(px(8.))
        .flex_shrink_0()
        .rounded(px(TOOLBAR_BUTTON_RADIUS))
        .bg(colors.section_background)
        .border_1()
        .border_color(colors.accent)
        .text_color(colors.accent)
        .disabled(ui.importing)
        .accessibility_label(label)
        .child(
            div()
                .flex()
                .items_center()
                .gap(px(4.))
                .text_size(px(studio::FONT_SUPPORTING))
                .whitespace_nowrap()
                .child(label)
                .child(IconName::ChevronDown),
        )
        .dropdown_menu(move |menu, _, _| {
            let mut menu = menu.min_w(px(160.));
            for source in ImportSource::ALL {
                let entity = entity.clone();
                menu = menu.item(PopupMenuItem::new(source.label(language)).on_click(
                    move |_, _, cx| {
                        entity
                            .update(cx, |this, cx| this.pick_compiler_report(source, cx))
                            .ok();
                    },
                ));
            }
            menu
        })
        .into_any_element()
}

pub(super) fn result_dialog(
    language: Language,
    colors: &StudioColors,
    ui: &CompilerReportUi,
    entity: Entity<InspectorWorkspace>,
) -> Option<AnyElement> {
    let result = ui.result.as_ref()?;
    let title = language.text(if result.is_ok() {
        TextKey::ComposeReportsImportedTitle
    } else {
        TextKey::ComposeReportsFailedTitle
    });
    let text = result_text(language, result);
    let copy_text = text.clone();
    let close_entity = entity.clone();
    Some(
        div()
            .id("inspector-compiler-report-backdrop")
            .absolute()
            .inset_0()
            .flex()
            .items_center()
            .justify_center()
            .bg(rgba(0x00000052))
            .on_click(move |_, _, cx| {
                entity.update(cx, |this, cx| {
                    this.compiler_reports.result = None;
                    cx.notify();
                });
            })
            .child(
                div()
                    .id("inspector-compiler-report-result")
                    .w(px(620.))
                    .max_w(relative(0.9))
                    .max_h(relative(0.85))
                    .p(px(12.))
                    .flex()
                    .flex_col()
                    .gap(px(10.))
                    .bg(colors.panel)
                    .border_1()
                    .border_color(colors.strong_border)
                    .rounded(px(studio::metrics::CARD_RADIUS))
                    .on_click(|_, _, cx| cx.stop_propagation())
                    .child(
                        div()
                            .text_size(px(studio::FONT_BODY))
                            .font_weight(FontWeight::SEMIBOLD)
                            .child(title),
                    )
                    .child(studio::divider(colors))
                    .child(
                        div()
                            .id("inspector-compiler-report-result-text")
                            .min_h_0()
                            .max_h(px(360.))
                            .overflow_y_scroll()
                            .text_size(px(studio::FONT_SUPPORTING))
                            .child(studio::selectable(text)),
                    )
                    .child(studio::supporting(
                        language.text(TextKey::ComposeReportsStaticHint),
                        colors,
                    ))
                    .child(
                        div()
                            .flex()
                            .justify_end()
                            .gap(px(8.))
                            .child(
                                Button::new("inspector-compiler-report-copy")
                                    .ghost()
                                    .label(language.text(TextKey::ComposeReportsCopyResult))
                                    .on_click(move |_, _, cx| {
                                        cx.write_to_clipboard(ClipboardItem::new_string(
                                            copy_text.clone(),
                                        ));
                                    }),
                            )
                            .child(
                                Button::new("inspector-compiler-report-close")
                                    .primary()
                                    .label(language.text(TextKey::ComposeReportsClose))
                                    .on_click(move |_, _, cx| {
                                        close_entity.update(cx, |this, cx| {
                                            this.compiler_reports.result = None;
                                            cx.notify();
                                        });
                                    }),
                            ),
                    ),
            )
            .into_any_element(),
    )
}

pub(super) fn compiler_section(
    language: Language,
    report: Option<&CompilerReport>,
    evidence: Option<&ComposeEvidence>,
    node: &LayoutNode,
) -> Option<DetailSection> {
    let report = report.filter(|_| node.kind == NodeKind::Compose)?;
    let metadata = compose_node_id(&node.id).and_then(|id| evidence?.nodes.get(&id));
    let matched = metadata.map_or(CompilerFunctionMatch::Missing, |metadata| {
        report.match_function(
            &metadata.name,
            metadata
                .source
                .as_ref()
                .map(|source| source.file_name.as_str()),
        )
    });
    Some(function_section(language, matched))
}

fn stability_label(language: Language, stability: CompilerStability) -> &'static str {
    language.text(match stability {
        CompilerStability::Stable => TextKey::ComposeReportsStable,
        CompilerStability::Unstable => TextKey::ComposeReportsUnstable,
        CompilerStability::Runtime => TextKey::ComposeReportsRuntime,
        CompilerStability::Unknown => TextKey::ComposeReportsUnknown,
    })
}

fn function_section(language: Language, matched: CompilerFunctionMatch<'_>) -> DetailSection {
    let mut rows = vec![DetailRow::toned(
        "",
        language.text(TextKey::ComposeReportsStaticHint),
        DetailTone::Info,
    )];
    match matched {
        CompilerFunctionMatch::Missing => rows.push(DetailRow::new(
            "",
            language.text(TextKey::ComposeReportsNoMatch),
        )),
        CompilerFunctionMatch::Ambiguous(_) => rows.push(DetailRow::toned(
            "",
            language.text(TextKey::ComposeReportsAmbiguous),
            DetailTone::Warning,
        )),
        CompilerFunctionMatch::Unique(function) => {
            for (key, flag) in [
                (TextKey::ComposeReportsRestartable, function.restartable),
                (TextKey::ComposeReportsSkippable, function.skippable),
                (TextKey::ComposeReportsReadonly, function.readonly),
            ] {
                rows.push(DetailRow::toned(
                    language.text(key),
                    language.text(if flag {
                        TextKey::ComposeReportsYes
                    } else {
                        TextKey::ComposeReportsNo
                    }),
                    if key == TextKey::ComposeReportsSkippable && !flag {
                        DetailTone::Warning
                    } else {
                        DetailTone::Normal
                    },
                ));
            }
            let unstable_count = function
                .parameters
                .iter()
                .filter(|parameter| parameter.stability == CompilerStability::Unstable)
                .count();
            let unstable = function
                .parameters
                .iter()
                .filter(|parameter| parameter.stability == CompilerStability::Unstable)
                .take(MAX_COMPILER_PARAMETER_ROWS)
                .map(|parameter| {
                    clipped_compose_label(&format!("{}: {}", parameter.name, parameter.type_name))
                })
                .collect::<Vec<_>>();
            let unstable_text = unstable.join(", ");
            let shortened = unstable_text.len() > MAX_COMPOSE_LABEL_BYTES
                || function
                    .parameters
                    .iter()
                    .take(MAX_COMPILER_PARAMETER_ROWS)
                    .any(|parameter| {
                        parameter.name.len() > MAX_COMPOSE_LABEL_BYTES
                            || parameter.type_name.len() > MAX_COMPOSE_LABEL_BYTES
                    });
            let mut unstable_text = clipped_compose_label(&unstable_text);
            if unstable_count > unstable.len() {
                unstable_text.push_str(&format!(
                    "\n{}",
                    language.format(
                        TextKey::ComposeReportsOmittedParameters,
                        &[("count", &(unstable_count - unstable.len()))]
                    )
                ));
            }
            rows.push(DetailRow::toned(
                language.text(TextKey::ComposeReportsUnstableParameters),
                if unstable.is_empty() {
                    "—".into()
                } else {
                    unstable_text
                },
                if unstable.is_empty() {
                    DetailTone::Normal
                } else {
                    DetailTone::Warning
                },
            ));
            for parameter in function.parameters.iter().take(MAX_COMPILER_PARAMETER_ROWS) {
                rows.push(DetailRow::toned(
                    clipped_compose_label(&parameter.name),
                    format!(
                        "{} · {}",
                        clipped_compose_label(&parameter.type_name),
                        stability_label(language, parameter.stability)
                    ),
                    if parameter.stability == CompilerStability::Unstable {
                        DetailTone::Warning
                    } else {
                        DetailTone::Normal
                    },
                ));
            }
            if function.parameters.len() > MAX_COMPILER_PARAMETER_ROWS {
                rows.push(DetailRow::toned(
                    "",
                    language.format(
                        TextKey::ComposeReportsOmittedParameters,
                        &[(
                            "count",
                            &(function.parameters.len() - MAX_COMPILER_PARAMETER_ROWS),
                        )],
                    ),
                    DetailTone::Info,
                ));
            }
            if shortened {
                rows.push(DetailRow::toned(
                    "",
                    language.text(TextKey::ComposeReportsShortened),
                    DetailTone::Info,
                ));
            }
        }
    }
    DetailSection::new(TextKey::ComposeReportsCompiler, rows)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn report() -> Arc<CompilerReport> {
        Arc::new(CompilerReport {
            source_directory: PathBuf::from("reports"),
            files: vec![PathBuf::from("reports/app-composables.txt")],
            functions: Vec::new(),
            classes: Vec::new(),
            warnings: Vec::new(),
        })
    }

    #[::core::prelude::v1::test]
    fn picker_modes_are_explicit_and_single_selection() {
        let file = ImportSource::File.picker_options(Language::English);
        let folder = ImportSource::Folder.picker_options(Language::SimplifiedChinese);
        assert!(file.files && !file.directories && !file.multiple);
        assert!(folder.directories && !folder.files && !folder.multiple);
    }

    #[::core::prelude::v1::test]
    fn cancellation_and_failure_preserve_the_previous_report() {
        let mut ui = CompilerReportUi::default();
        let previous = report();
        ui.finish(Some(Ok(previous.clone())), Language::English);
        ui.timing = ImportTiming::from_duration(std::time::Duration::from_secs(2));
        let previous_timing = ui.timing;
        assert!(ui.begin());
        assert!(!ui.begin());
        ui.finish(None, Language::English);
        assert!(!ui.importing);
        assert!(Arc::ptr_eq(ui.report.as_ref().unwrap(), &previous));
        assert_eq!(ui.timing, previous_timing);
        assert!(ui.begin());
        ui.finish(Some(Err("unsupported report".into())), Language::English);
        assert!(!ui.importing);
        assert!(Arc::ptr_eq(ui.report.as_ref().unwrap(), &previous));
        assert!(ui.result.as_ref().unwrap().is_err());
        assert_eq!(ui.timing, previous_timing);
        assert!(
            ui.notice
                .as_ref()
                .unwrap()
                .message
                .contains("unsupported report")
        );
    }

    #[::core::prelude::v1::test]
    fn results_retain_paths_and_parser_diagnostics() {
        let mut imported = (*report()).clone();
        imported
            .warnings
            .push("Malformed sibling app-classes.txt".into());
        let mut ui = CompilerReportUi::default();
        ui.finish(Some(Ok(Arc::new(imported))), Language::English);
        let message = result_text(Language::English, ui.result.as_ref().unwrap());
        assert!(message.contains("app-composables.txt"));
        assert!(message.contains("Malformed sibling"));
        assert_eq!(
            ui.notice.as_ref().unwrap().level,
            crate::notifications::NoticeLevel::Warning
        );
    }

    #[::core::prelude::v1::test]
    fn ambiguous_and_unmatched_nodes_do_not_invent_compiler_flags() {
        for matched in [
            CompilerFunctionMatch::Missing,
            CompilerFunctionMatch::Ambiguous(2),
        ] {
            let section = function_section(Language::English, matched);
            assert_eq!(section.rows.len(), 2);
            assert!(section.rows.iter().all(|row| row.label.is_empty()));
        }
    }

    #[::core::prelude::v1::test]
    fn compiler_flags_and_unstable_parameters_remain_static_diagnostics() {
        let function = aps_layout_inspector::CompilerFunction {
            package_name: Some("example".into()),
            name: "Feed".into(),
            restartable: true,
            skippable: true,
            readonly: false,
            parameters: vec![aps_layout_inspector::CompilerParameter {
                name: "state".into(),
                type_name: "FeedState".into(),
                stability: CompilerStability::Unstable,
            }],
            source_file: None,
            source_line: None,
        };
        let section = function_section(Language::English, CompilerFunctionMatch::Unique(&function));
        assert_eq!(section.title, TextKey::ComposeReportsCompiler);
        assert!(section.rows[0].tone == DetailTone::Info);
        assert!(section.rows[0].value.contains("Static compiler"));
        let skippable = section
            .rows
            .iter()
            .find(|row| row.label == "Skippable")
            .unwrap();
        // Strong-skipping builds can have unstable parameters AND be skippable.
        assert_eq!(skippable.value, "Yes");
        assert!(skippable.tone == DetailTone::Normal);
        assert!(section.rows.iter().any(|row| row.label == "state"
            && row.value.contains("FeedState")
            && row.tone == DetailTone::Warning));
        assert!(
            !section
                .rows
                .iter()
                .any(|row| row.label.contains("Recompose"))
        );
    }

    #[::core::prelude::v1::test]
    fn large_parameter_views_report_display_omissions_without_mutating_evidence() {
        let function = aps_layout_inspector::CompilerFunction {
            name: "Large".into(),
            parameters: (0..80)
                .map(|index| aps_layout_inspector::CompilerParameter {
                    name: format!("parameter{index}"),
                    type_name: "VeryLongType".repeat(100),
                    stability: CompilerStability::Unstable,
                })
                .collect(),
            ..Default::default()
        };
        let section = function_section(Language::English, CompilerFunctionMatch::Unique(&function));
        assert!(section.rows.len() <= MAX_COMPILER_PARAMETER_ROWS + 8);
        assert!(
            section
                .rows
                .iter()
                .any(|row| row.value.contains("48 additional parameters"))
        );
        assert!(
            section
                .rows
                .iter()
                .any(|row| row.value.contains("shortened"))
        );
        assert_eq!(function.parameters.len(), 80);
        assert!(function.parameters[0].type_name.len() > MAX_COMPOSE_LABEL_BYTES);
    }
}
