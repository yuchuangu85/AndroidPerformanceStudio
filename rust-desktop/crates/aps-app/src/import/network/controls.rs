//! Reference-shaped request controls. All queries remain feature-owned and
//! whole-session; unavailable outcomes are never substituted with HTTP status.
use super::*;
use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};

fn active_advanced_filters(query: &EntryQuery) -> usize {
    usize::from(query.status != StatusFilter::All)
        + usize::from(query.method.is_some())
        + usize::from(query.sort != EntrySort::Entry)
}

impl ImportWorkspace {
    pub(super) fn network_controls(
        &mut self,
        search: &Entity<InputState>,
        colors: &studio::StudioColors,
        narrow: bool,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let ready = matches!(self.session, Some(Session::Network(_))) && !self.busy;
        let active_filters = active_advanced_filters(&self.network.query);
        let more_label = language.text(if self.network.filters_expanded {
            TextKey::NetworkControlsLessFilters
        } else {
            TextKey::NetworkControlsMoreFilters
        });
        let more_label = if active_filters == 0 {
            more_label.to_owned()
        } else {
            language.format(
                TextKey::NetworkControlsActiveFilters,
                &[("label", &more_label), ("count", &active_filters)],
            )
        };
        let count = self.page.as_ref().map_or(0, |page| page.rows.len());
        let total = match &self.session {
            Some(Session::Network(session)) => session.summary().entry_count,
            _ => 0,
        };
        let tabs = div()
            .id("network-control-tabs-scroll")
            .flex()
            .items_center()
            .gap(px(4.))
            .min_w_0()
            .max_w(relative(1.))
            .when(narrow, |tabs| tabs.w_full())
            .overflow_x_scrollbar()
            .children(
                [
                    (false, TextKey::ImportUiRequestList),
                    (true, TextKey::NetworkDomains),
                ]
                .into_iter()
                .map(|(domains, key)| {
                    studio::tab_button(
                        ("network-view", usize::from(domains)),
                        language.text(key),
                        self.network.domains == domains,
                        colors,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        // Switching presentation must not reset an unsubmitted search.
                        this.network.domains = domains;
                        cx.notify();
                    }))
                }),
            )
            .child(
                studio::compact_text_button(
                    "network-outcome-filter",
                    language.text(TextKey::NetworkControlsAllOutcomes),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::NetworkControlsOutcomeUnavailable)),
            )
            .child(
                studio::supporting(
                    language.format(
                        TextKey::NetworkControlsCount,
                        &[("matching", &self.network.total), ("total", &total)],
                    ),
                    colors,
                )
                .whitespace_nowrap(),
            );
        let search_actions = div()
            .flex()
            .items_center()
            .gap(px(4.))
            .flex_wrap()
            .flex_1()
            .min_w_0()
            .when(narrow, |actions| actions.w_full())
            .child(
                div()
                    .flex_1()
                    .flex_basis(px(120.))
                    .min_w(px(120.))
                    .child(Input::new(search).h(px(24.))),
            )
            .child(
                studio::compact_text_button(
                    "network-search-apply",
                    language.text(TextKey::NetworkApply),
                    ready,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    let mut query = this.network.query.clone();
                    query.search = this
                        .network
                        .search
                        .as_ref()
                        .map(|search| search.read(cx).value().to_string())
                        .unwrap_or_default();
                    this.query_network(0, query, cx);
                })),
            )
            .child(
                studio::compact_text_button(
                    "network-reset",
                    language.text(TextKey::NetworkReset),
                    ready,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.network.draft_needs_reset = true;
                    this.query_network(0, EntryQuery::default(), cx);
                })),
            )
            .child(
                studio::compact_text_button(
                    "network-more-filters",
                    more_label,
                    true,
                    self.network.filters_expanded || active_filters > 0,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.network.filters_expanded = !this.network.filters_expanded;
                    cx.notify();
                })),
            );
        let primary = div().w_full().flex().gap(px(8.));
        let primary = if narrow {
            primary.flex_col().child(tabs).child(search_actions)
        } else {
            primary.items_center().child(tabs).child(search_actions)
        };
        let mut controls = div()
            .flex()
            .flex_col()
            .gap(px(4.))
            .flex_shrink_0()
            .child(primary);
        if self.network.filters_expanded {
            let mut advanced = div()
                .id("network-more-filters-region")
                .flex()
                .flex_wrap()
                .items_center()
                .gap(px(4.))
                .child(studio::dense_label(
                    language.text(TextKey::NetworkControlsHttpStatus),
                    colors.secondary_text,
                ));
            for (index, (filter, key)) in [
                (StatusFilter::All, TextKey::ImportUiNetworkAll),
                (StatusFilter::Success2xx3xx, TextKey::ImportUiNetworkSuccess),
                (
                    StatusFilter::ClientError4xx,
                    TextKey::ImportUiNetworkClientErrors,
                ),
                (
                    StatusFilter::ServerError5xx,
                    TextKey::ImportUiNetworkServerErrors,
                ),
                (StatusFilter::Unknown, TextKey::NetworkStatusUnknown),
            ]
            .into_iter()
            .enumerate()
            {
                advanced = advanced.child(
                    studio::compact_text_button(
                        ("network-status-filter", index),
                        language.text(key),
                        ready,
                        filter == self.network.query.status,
                        colors,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        let mut query = this.network.query.clone();
                        query.status = filter;
                        this.query_network(0, query, cx);
                    })),
                );
            }
            advanced = advanced.child(
                studio::compact_text_button(
                    "network-method-filter",
                    language.format(
                        TextKey::NetworkMethodFilter,
                        &[(
                            "method",
                            &self
                                .network
                                .query
                                .method
                                .as_deref()
                                .unwrap_or(language.text(TextKey::NetworkAllMethods)),
                        )],
                    ),
                    ready,
                    self.network.query.method.is_some(),
                    colors,
                )
                .dropdown_menu({
                    let workspace = cx.entity().downgrade();
                    let current = self.network.query.method.clone();
                    move |menu, _, _| {
                        let mut menu = menu.min_w(px(180.));
                        for method in [
                            None,
                            Some("GET"),
                            Some("POST"),
                            Some("PUT"),
                            Some("PATCH"),
                            Some("DELETE"),
                            Some("HEAD"),
                            Some("OPTIONS"),
                            Some("CONNECT"),
                            Some("TRACE"),
                            Some("OTHER"),
                        ] {
                            let label = method.unwrap_or(language.text(TextKey::NetworkAllMethods));
                            let label = if current.as_deref() == method {
                                format!("✓ {label}")
                            } else {
                                label.to_owned()
                            };
                            let workspace = workspace.clone();
                            menu =
                                menu.item(PopupMenuItem::new(label).on_click(move |_, _, cx| {
                                    let _ = workspace.update(cx, |this, cx| {
                                        let mut query = this.network.query.clone();
                                        query.method = method.map(str::to_owned);
                                        this.query_network(0, query, cx);
                                    });
                                }));
                        }
                        menu
                    }
                }),
            );
            advanced = advanced.child(studio::dense_label(
                language.text(TextKey::NetworkSort),
                colors.secondary_text,
            ));
            for (index, (sort, key)) in [
                (EntrySort::Entry, TextKey::NetworkSortEntry),
                (EntrySort::DurationDescending, TextKey::NetworkSortDuration),
                (EntrySort::ResponseBytesDescending, TextKey::NetworkSortSize),
            ]
            .into_iter()
            .enumerate()
            {
                advanced = advanced.child(
                    studio::compact_text_button(
                        ("network-sort", index),
                        language.text(key),
                        ready,
                        sort == self.network.query.sort,
                        colors,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        let mut query = this.network.query.clone();
                        query.sort = sort;
                        this.query_network(0, query, cx);
                    })),
                );
            }
            controls = controls.child(advanced).child(studio::supporting(
                language.format(TextKey::NetworkControlsPageCount, &[("visible", &count)]),
                colors,
            ));
        }
        controls.into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::{EntryQuery, EntrySort, StatusFilter, active_advanced_filters};

    #[::core::prelude::v1::test]
    fn collapsed_filters_report_only_hidden_active_choices() {
        let mut query = EntryQuery::default();
        assert_eq!(active_advanced_filters(&query), 0);
        query.search = "GET".into();
        assert_eq!(active_advanced_filters(&query), 0);
        query.status = StatusFilter::ClientError4xx;
        assert_eq!(active_advanced_filters(&query), 1);
        query.method = Some("GET".into());
        query.sort = EntrySort::DurationDescending;
        assert_eq!(active_advanced_filters(&query), 3);
        assert_eq!(active_advanced_filters(&EntryQuery::default()), 0);
    }

    // Source contracts cover the layout/draft boundary without opening a native
    // window. Per rewrite policy these are authored/compiled, not executed.
    const SOURCE: &str = include_str!("controls.rs");

    #[::core::prelude::v1::test]
    fn responsive_controls_use_natural_height_and_bounded_wrapping_regions() {
        let implementation = SOURCE.split("#[cfg(test)]").next().unwrap();
        assert!(!implementation.contains("container_query("));
        assert!(implementation.contains("primary.flex_col().child(tabs).child(search_actions)"));
        assert!(implementation.contains(".when(narrow, |tabs| tabs.w_full())"));
        assert!(implementation.contains(".when(narrow, |actions| actions.w_full())"));
        assert!(implementation.contains(".flex_wrap()"));
        assert!(implementation.contains(".max_w(relative(1.))"));
    }

    #[::core::prelude::v1::test]
    fn view_tabs_preserve_drafts_and_http_status_is_not_outcome() {
        let implementation = SOURCE.split("#[cfg(test)]").next().unwrap();
        let tabs = implementation
            .split("let tabs =")
            .nth(1)
            .unwrap()
            .split("let search_actions =")
            .next()
            .unwrap();
        assert!(!tabs.contains("query_network("));
        assert!(!tabs.contains("set_value("));
        assert!(tabs.contains("NetworkControlsOutcomeUnavailable"));
        assert!(tabs.contains("NetworkControlsAllOutcomes"));
        assert!(implementation.contains("NetworkControlsHttpStatus"));
        for choice in [
            "StatusFilter::All",
            "StatusFilter::Success2xx3xx",
            "StatusFilter::ClientError4xx",
            "StatusFilter::ServerError5xx",
            "StatusFilter::Unknown",
            "EntrySort::Entry",
            "EntrySort::DurationDescending",
            "EntrySort::ResponseBytesDescending",
        ] {
            assert!(
                implementation.contains(choice),
                "lost query choice {choice}"
            );
        }
        assert!(implementation.contains("query.method = method.map(str::to_owned)"));
        assert!(implementation.contains("this.query_network(0, query, cx)"));
    }
}
