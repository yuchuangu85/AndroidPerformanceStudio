//! Reference request-detail structure over the published minimized HAR row only.
//! No raw HAR reads, transport, source lookup or content recovery occur here.
use super::*;

// NetworkProfilerScreen.kt DETAIL_PHASES. These are timing phases, not the
// separate header/body content-evidence blocks beneath them.
const DETAIL_PHASES: [TextKey; 10] = [
    TextKey::NetworkDetailDispatcherQueue,
    TextKey::NetworkDetailDns,
    TextKey::NetworkDetailConnect,
    TextKey::NetworkDetailTls,
    TextKey::NetworkDetailRequestHeaders,
    TextKey::NetworkDetailRequestBody,
    TextKey::NetworkDetailServerWait,
    TextKey::NetworkDetailResponseHeaders,
    TextKey::NetworkDetailResponseBody,
    TextKey::NetworkDetailConnectionHeld,
];

fn supporting(text: impl Into<SharedString>, colors: &studio::StudioColors) -> Div {
    studio::supporting(text, colors).line_height(px(15.))
}

fn badge(label: &'static str, colors: &studio::StudioColors) -> Div {
    div()
        .flex_shrink_0()
        .px(px(6.))
        .py(px(2.))
        .border_1()
        .border_color(colors.border)
        .rounded(px(4.))
        .bg(colors.section_background)
        .text_color(colors.secondary_text)
        .text_size(px(11.))
        .line_height(px(15.))
        .whitespace_nowrap()
        .child(label)
}

fn detail_panel(
    language: Language,
    colors: &studio::StudioColors,
    selected: bool,
) -> Stateful<Div> {
    panel(colors)
        .id("network-request-detail")
        .size_full()
        .min_h_0()
        .min_w_0()
        .overflow_hidden()
        .p(px(8.))
        .gap(px(4.))
        .child(
            div()
                .h(px(36.))
                .flex_shrink_0()
                .flex()
                .flex_col()
                .justify_center()
                .gap(px(2.))
                .child(
                    studio::section_title(language.text(TextKey::NetworkDetailTitle), colors)
                        .line_height(px(17.)),
                )
                .children(selected.then(|| {
                    supporting(aps_network::MINIMIZED_URL, colors)
                        .whitespace_nowrap()
                        .text_ellipsis()
                })),
        )
}

/// Separate first-load state: None in request_detail remains empty selection,
/// never an implicit promise that a network query is running.
pub(super) fn request_detail_loading(
    language: Language,
    colors: &studio::StudioColors,
) -> AnyElement {
    detail_panel(language, colors, false)
        .child(
            div()
                .flex_1()
                .min_h_0()
                .flex()
                .items_center()
                .justify_center()
                .p(px(12.))
                .child(supporting(
                    language.text(TextKey::NetworkDetailLoading),
                    colors,
                )),
        )
        .into_any_element()
}

pub(super) fn request_detail(
    row: Option<&[String]>,
    language: Language,
    colors: &studio::StudioColors,
) -> AnyElement {
    let Some(row) = row else {
        return detail_panel(language, colors, false)
            .child(
                div()
                    .flex_1()
                    .min_h_0()
                    .flex()
                    .items_center()
                    .justify_center()
                    .p(px(12.))
                    .child(supporting(
                        language.text(TextKey::NetworkDetailSelect),
                        colors,
                    )),
            )
            .into_any_element();
    };
    let safe = safe_details(row);
    let copy = safe.copy_text();
    let response = safe.response_bytes.map_or_else(|| "—".into(), bytes);
    let request = safe.request_bytes.map_or_else(|| "—".into(), bytes);
    let status = safe
        .status
        .map_or_else(|| "—".into(), |value| value.to_string());
    let entry = safe
        .entry
        .map_or_else(|| "—".into(), |value| value.to_string());
    let mut exchange = studio::card(colors)
        .gap(px(7.))
        .child(
            div()
                .flex()
                .items_center()
                .gap(px(6.))
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(13.))
                        .line_height(px(18.))
                        .font_weight(FontWeight::SEMIBOLD)
                        .child(language.format(TextKey::NetworkDetailEntry, &[("entry", &entry)])),
                )
                .child(
                    supporting(
                        language.format(TextKey::NetworkDetailStatus, &[("status", &status)]),
                        colors,
                    )
                    .text_color(status_color(&status, colors)),
                ),
        )
        .child(supporting(
            language.text(TextKey::NetworkDetailNotExchange),
            colors,
        ))
        .child(
            div()
                .flex()
                .flex_wrap()
                .gap(px(6.))
                .child(supporting(
                    language.text(TextKey::NetworkDetailProtocol),
                    colors,
                ))
                .child(supporting(
                    language.text(TextKey::NetworkDetailConnectionUnknown),
                    colors,
                )),
        )
        .child(supporting(
            language.format(
                TextKey::NetworkDetailTotal,
                &[("duration", &duration(safe.duration_ms))],
            ),
            colors,
        ))
        .child(
            studio::section_title(language.text(TextKey::NetworkDetailPhases), colors)
                .line_height(px(17.)),
        )
        .child(supporting(
            language.text(TextKey::NetworkDetailPhaseHint),
            colors,
        ));
    for (index, key) in DETAIL_PHASES.into_iter().enumerate() {
        exchange = exchange.child(
            div()
                .id(("network-detail-phase", index))
                .w_full()
                .flex()
                .items_center()
                .gap(px(6.))
                .px(px(7.))
                .py(px(5.))
                .rounded(px(4.))
                .bg(colors.section_background)
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(11.))
                        .line_height(px(15.))
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(language.text(key)),
                )
                .child(
                    supporting(language.text(TextKey::NetworkDetailNotRetained), colors)
                        .w(px(74.))
                        .flex_shrink_0()
                        .whitespace_nowrap(),
                )
                .child(badge(
                    language.text(TextKey::NetworkDetailUnavailable),
                    colors,
                )),
        );
    }
    for (id, title, body) in [
        (
            "network-request-headers",
            TextKey::NetworkDetailRequestHeadersEvidence,
            TextKey::NetworkDetailHeadersExcluded,
        ),
        (
            "network-request-body",
            TextKey::NetworkDetailRequestBodyEvidence,
            TextKey::NetworkDetailBodyExcluded,
        ),
        (
            "network-response-headers",
            TextKey::NetworkDetailResponseHeadersEvidence,
            TextKey::NetworkDetailHeadersExcluded,
        ),
        (
            "network-response-body",
            TextKey::NetworkDetailResponseBodyEvidence,
            TextKey::NetworkDetailBodyExcluded,
        ),
    ] {
        exchange = exchange.child(
            div()
                .id(id)
                .w_full()
                .flex()
                .flex_col()
                .gap(px(2.))
                .p(px(7.))
                .bg(colors.section_background)
                .rounded(px(4.))
                .child(supporting(language.text(title), colors).font_weight(FontWeight::SEMIBOLD))
                .child(supporting(language.text(body), colors)),
        );
    }
    // Only the 36px title remains fixed. Every other block shares this one
    // viewport, so long localized caveats cannot shrink phase evidence to zero
    // in the 220px wide-layout or 216px stacked-layout detail pane.
    let content = div()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(4.))
        .child(
            div()
                .flex()
                .items_center()
                .flex_wrap()
                .gap(px(6.))
                .child(
                    div()
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_size(px(13.))
                        .line_height(px(18.))
                        .child(safe.method),
                )
                .child(badge(language.text(TextKey::NetworkDetailUnknown), colors))
                .child(div().flex_1())
                .child(supporting(
                    language.format(
                        TextKey::NetworkDetailBytes,
                        &[("response", &response), ("request", &request)],
                    ),
                    colors,
                )),
        )
        .child(supporting(
            language.text(TextKey::NetworkDetailLegend),
            colors,
        ))
        .child(supporting(
            language.text(TextKey::NetworkDetailOutcome),
            colors,
        ))
        .child(studio::divider(colors))
        .child(exchange)
        .child(
            studio::compact_text_button(
                "network-copy-minimized-details",
                language.text(TextKey::NetworkDetailCopy),
                true,
                false,
                colors,
            )
            .on_click(move |_, _, cx| {
                cx.write_to_clipboard(ClipboardItem::new_string(copy.clone()))
            }),
        )
        .child(supporting(
            language.text(TextKey::NetworkDetailScope),
            colors,
        ));
    detail_panel(language, colors, true)
        .child(
            div()
                .id("network-request-detail-scroll")
                .flex_1()
                .min_h_0()
                .min_w_0()
                .overflow_y_scrollbar()
                .child(content),
        )
        .into_any_element()
}

/// Reconstruct the fixed minimized-v1 whitelist rather than copying arbitrary
/// display columns. Extra/malformed cells never reach the clipboard.
struct SafeDetails {
    entry: Option<u64>,
    method: &'static str,
    status: Option<u16>,
    duration_ms: Option<f64>,
    request_bytes: Option<u64>,
    response_bytes: Option<u64>,
}

fn safe_details(row: &[String]) -> SafeDetails {
    let method = match row.get(1).map(String::as_str) {
        Some("GET") => "GET",
        Some("POST") => "POST",
        Some("PUT") => "PUT",
        Some("PATCH") => "PATCH",
        Some("DELETE") => "DELETE",
        Some("HEAD") => "HEAD",
        Some("OPTIONS") => "OPTIONS",
        Some("CONNECT") => "CONNECT",
        Some("TRACE") => "TRACE",
        _ => "OTHER",
    };
    SafeDetails {
        entry: row.first().and_then(|value| value.parse().ok()),
        method,
        status: row
            .get(2)
            .and_then(|value| value.parse::<u16>().ok())
            .filter(|value| (100..=599).contains(value)),
        duration_ms: number(row, 3).filter(|value| value.is_finite() && *value >= 0.),
        request_bytes: byte_value(row, 4),
        response_bytes: byte_value(row, 5),
    }
}

impl SafeDetails {
    fn copy_text(&self) -> String {
        let optional = |value: Option<u64>| {
            value.map_or_else(|| "unavailable".into(), |value| value.to_string())
        };
        format!(
            "policy=aps-network-rust-minimized-v1\nentry={}\nmethod={}\nurl={}\nstatus={}\nduration_ms={}\nrequest_bytes={}\nresponse_bytes={}\noutcome=unknown\nphase_evidence=not_retained\ncontent_evidence=removed\n",
            optional(self.entry),
            self.method,
            aps_network::MINIMIZED_URL,
            self.status
                .map_or_else(|| "unavailable".into(), |value| value.to_string()),
            self.duration_ms
                .map_or_else(|| "unavailable".into(), |value| value.to_string()),
            optional(self.request_bytes),
            optional(self.response_bytes),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[::core::prelude::v1::test]
    fn short_detail_panes_scroll_everything_beneath_fixed_header() {
        let source = include_str!("detail.rs");
        let implementation = source.split("#[cfg(test)]").next().unwrap();
        let body = implementation.split("let content = div()").nth(1).unwrap();
        let (content, viewport) = body
            .split_once("detail_panel(language, colors, true)")
            .unwrap();
        for section in [
            "NetworkDetailLegend",
            "NetworkDetailOutcome",
            ".child(exchange)",
            "network-copy-minimized-details",
            "NetworkDetailScope",
        ] {
            assert!(
                content.contains(section),
                "non-scrolling detail section: {section}"
            );
        }
        assert!(viewport.contains(".overflow_y_scrollbar()"));
        assert!(viewport.contains(".child(content)"));
        assert!(!content.contains(".flex_1()\n                .min_h_0()\n                .min_w_0()\n                .overflow_y_scrollbar()"));
    }

    #[::core::prelude::v1::test]
    fn phases_keep_reference_order_separate_from_content_blocks() {
        assert_eq!(DETAIL_PHASES.len(), 10);
        assert_eq!(DETAIL_PHASES[0], TextKey::NetworkDetailDispatcherQueue);
        assert_eq!(DETAIL_PHASES[4], TextKey::NetworkDetailRequestHeaders);
        assert_eq!(DETAIL_PHASES[5], TextKey::NetworkDetailRequestBody);
        assert_eq!(DETAIL_PHASES[6], TextKey::NetworkDetailServerWait);
        assert_eq!(DETAIL_PHASES[9], TextKey::NetworkDetailConnectionHeld);
    }
    #[::core::prelude::v1::test]
    fn copied_details_reconstruct_minimized_whitelist_and_preserve_unknowns() {
        let row = [
            "3",
            "GET",
            "503",
            "12.5",
            "unavailable",
            "1024",
            "https://secret.invalid/?token=secret",
            "raw-header: secret",
        ]
        .map(str::to_owned);
        let copy = safe_details(&row).copy_text();
        assert!(copy.contains("status=503\n"));
        assert!(copy.contains("outcome=unknown\n"));
        assert!(copy.contains("request_bytes=unavailable\n"));
        assert!(copy.contains("url=redacted://minimized-url\n"));
        assert!(!copy.contains("secret"));
        assert!(!copy.contains("raw-header"));
    }
    #[::core::prelude::v1::test]
    fn malformed_fields_are_not_republished_as_source_evidence() {
        let row = ["arbitrary", "bad\nmethod", "0", "NaN", "-1", "bad"].map(str::to_owned);
        let safe = safe_details(&row);
        assert_eq!(safe.entry, None);
        assert_eq!(safe.method, "OTHER");
        assert_eq!(safe.status, None);
        assert_eq!(safe.duration_ms, None);
        assert_eq!(safe.request_bytes, None);
        assert_eq!(safe.response_bytes, None);
    }
}
