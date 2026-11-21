use super::*;

fn labels(windows: &[LayoutWindow], language: Language) -> Vec<String> {
    let names: Vec<String> = windows
        .iter()
        .enumerate()
        .map(|(index, window)| {
            window
                .title
                .as_deref()
                .map(str::trim)
                .filter(|title| !title.is_empty())
                .or_else(|| {
                    window
                        .nodes
                        .first()?
                        .class_name
                        .rsplit('.')
                        .next()
                        .map(str::trim)
                        .filter(|name| !name.is_empty())
                })
                .map(str::to_owned)
                .unwrap_or_else(|| {
                    format!("{} {}", language.text(TextKey::InspectorWindow), index + 1)
                })
        })
        .collect();
    names
        .iter()
        .zip(windows)
        .map(|(name, window)| {
            if names.iter().filter(|other| *other == name).take(2).count() > 1 {
                format!("{name} ({})", window.id)
            } else {
                name.clone()
            }
        })
        .collect()
}

pub(super) fn selected_label(
    archive: Option<&Arc<InspectorArchive>>,
    index: usize,
    language: Language,
) -> String {
    archive
        .and_then(|archive| labels(&archive.windows, language).get(index).cloned())
        .unwrap_or_else(|| "-".into())
}

pub(super) fn selector(
    language: Language,
    colors: &StudioColors,
    archive: &Arc<InspectorArchive>,
    current: usize,
    enabled: bool,
    cx: &mut Context<InspectorWorkspace>,
) -> AnyElement {
    let names = labels(&archive.windows, language);
    let name = names.get(current).cloned().unwrap_or_else(|| "-".into());
    let caption = format!("{}: {name}", language.text(TextKey::InspectorWindow));
    let entity = cx.entity().downgrade();
    let archive = archive.clone();
    Button::new("inspector-window")
        .ghost()
        .h(px(TOOLBAR_BUTTON_HEIGHT))
        .max_w(px(280.))
        .px(px(8.))
        .flex_shrink_0()
        .rounded(px(TOOLBAR_BUTTON_RADIUS))
        .bg(colors.section_background)
        .border_1()
        .border_color(colors.accent)
        .text_color(colors.accent)
        .disabled(!enabled)
        .accessibility_label(caption.clone())
        .tooltip(caption.clone())
        .child(
            div()
                .flex()
                .min_w_0()
                .items_center()
                .gap(px(4.))
                .child(
                    div()
                        .min_w_0()
                        .text_size(px(studio::FONT_SUPPORTING))
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(caption),
                )
                .child(div().flex_shrink_0().child(IconName::ChevronDown)),
        )
        .dropdown_menu(move |menu, _, _| {
            let mut menu = menu.min_w(px(200.)).max_w(px(480.));
            for (index, (window, name)) in archive.windows.iter().zip(&names).enumerate() {
                let id = window.id.clone();
                let entity = entity.clone();
                let archive = archive.clone();
                menu = menu.item(
                    PopupMenuItem::new(name.clone())
                        .checked(index == current)
                        .on_click(move |_, _, cx| {
                            let _ = entity.update(cx, |this, cx| {
                                if this
                                    .archive
                                    .as_ref()
                                    .is_some_and(|current| Arc::ptr_eq(current, &archive))
                                    && let Some(index) =
                                        archive.windows.iter().position(|window| window.id == id)
                                {
                                    this.select_window(index, cx);
                                }
                            });
                        }),
                );
            }
            menu
        })
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn window(id: &str, title: Option<&str>) -> LayoutWindow {
        LayoutWindow {
            id: id.into(),
            title: title.map(str::to_owned),
            kind: "OTHER".into(),
            bounds: LayoutBounds {
                left: 0,
                top: 0,
                right: 100,
                bottom: 100,
            },
            nodes: vec![],
        }
    }

    #[::core::prelude::v1::test]
    fn system_windows_use_captured_titles_not_internal_ids() {
        let windows = [
            window("window:a", Some("Taskbar")),
            window("window:b", Some("StatusBar")),
        ];
        assert_eq!(
            labels(&windows, Language::English),
            ["Taskbar", "StatusBar"]
        );
        assert_eq!(
            labels(&windows, Language::SimplifiedChinese),
            ["Taskbar", "StatusBar"]
        );
    }

    #[::core::prelude::v1::test]
    fn duplicate_names_are_disambiguated_without_changing_unique_titles() {
        let windows = [
            window("window:a", Some("Taskbar")),
            window("window:b", Some("Taskbar")),
            window("window:c", Some("NotificationShade")),
        ];
        assert_eq!(
            labels(&windows, Language::English),
            [
                "Taskbar (window:a)",
                "Taskbar (window:b)",
                "NotificationShade"
            ]
        );
    }

    #[::core::prelude::v1::test]
    fn missing_or_blank_titles_have_localized_fallbacks_not_only_ids() {
        let windows = [window("window:a", None), window("window:b", Some("   "))];
        assert_eq!(
            labels(&windows, Language::English),
            ["Window 1", "Window 2"]
        );
        assert_eq!(
            labels(&windows, Language::SimplifiedChinese),
            ["窗口 1", "窗口 2"]
        );
    }

    #[::core::prelude::v1::test]
    fn dropdown_selects_a_specific_window_and_rejects_old_archive_actions() {
        let source = include_str!("windows.rs")
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        assert!(source.contains(".dropdown_menu("));
        assert!(source.contains(".checked(index == current)"));
        assert!(source.contains("Arc::ptr_eq(current, &archive)"));
        assert!(source.contains("this.select_window(index, cx)"));
        assert!(!source.contains("cycle_window("));
    }

    #[::core::prelude::v1::test]
    fn footer_displays_the_selected_window_not_the_first_window() {
        let source = include_str!("../inspector.rs");
        let footer = source
            .split_once("fn status_bar(")
            .unwrap()
            .1
            .split_once("fn inherited_hidden(")
            .unwrap()
            .0;
        assert!(footer.contains("windows::selected_label(archive, window_index, language)"));
        assert!(!footer.contains("windows.get(0)"));
    }
}
