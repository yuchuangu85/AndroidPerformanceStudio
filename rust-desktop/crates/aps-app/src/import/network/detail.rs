//! Request detail over redacted rows and on-demand phase/header evidence.
//! Bodies, cookies and raw values are never recovered or displayed.
use super::*;
use aps_network::{EntryDetail, EntryPhase, PhaseAvailability, PhaseConfidence, PhaseKind};

// NetworkProfilerScreen.kt DETAIL_PHASES. These are timing phases, not the
// separate header/body content-evidence blocks beneath them.
const DETAIL_PHASES: [PhaseKind; 10] = [
    PhaseKind::DispatcherQueue,
    PhaseKind::Dns,
    PhaseKind::Connect,
    PhaseKind::Tls,
    PhaseKind::RequestHeaders,
    PhaseKind::RequestBody,
    PhaseKind::ServerWait,
    PhaseKind::ResponseHeaders,
    PhaseKind::ResponseBody,
    PhaseKind::ConnectionHeld,
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
        .child(crate::studio::selectable(label))
}

fn detail_panel(
    language: Language,
    colors: &studio::StudioColors,
    selected: bool,
    url: Option<&str>,
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
                    supporting(url.unwrap_or("—").to_owned(), colors)
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
    detail_panel(language, colors, false, None)
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

fn phase_label(kind: PhaseKind, language: Language) -> &'static str {
    language.text(match kind {
        PhaseKind::DispatcherQueue => TextKey::NetworkDetailDispatcherQueue,
        PhaseKind::ProxySelect => TextKey::NetworkLiveProxy,
        PhaseKind::Dns => TextKey::NetworkDetailDns,
        PhaseKind::Connect => TextKey::NetworkDetailConnect,
        PhaseKind::Tls => TextKey::NetworkDetailTls,
        PhaseKind::RequestHeaders => TextKey::NetworkDetailRequestHeaders,
        PhaseKind::RequestBody => TextKey::NetworkDetailRequestBody,
        PhaseKind::ServerWait => TextKey::NetworkDetailServerWait,
        PhaseKind::ResponseHeaders => TextKey::NetworkDetailResponseHeaders,
        PhaseKind::ResponseBody => TextKey::NetworkDetailResponseBody,
        PhaseKind::ConnectionHeld => TextKey::NetworkDetailConnectionHeld,
        PhaseKind::Total => TextKey::NetworkDetailTotal,
    })
}

fn confidence_label(confidence: PhaseConfidence, language: Language) -> &'static str {
    language.text(match confidence {
        PhaseConfidence::Exact => TextKey::NetworkDetailConfidenceExact,
        PhaseConfidence::Derived => TextKey::NetworkDetailConfidenceDerived,
        PhaseConfidence::Inferred => TextKey::NetworkDetailConfidenceInferred,
        PhaseConfidence::Approximated => TextKey::NetworkDetailConfidenceApproximated,
        PhaseConfidence::Partial => TextKey::NetworkDetailConfidencePartial,
        PhaseConfidence::Unknown => TextKey::NetworkDetailConfidenceUnknown,
    })
}

fn phase_duration_text(phase: Option<&EntryPhase>, language: Language) -> String {
    let Some(phase) = phase else {
        return language.text(TextKey::NetworkDetailNotObserved).to_owned();
    };
    match phase.duration_ms {
        Some(duration) => format!("{duration:.1} ms"),
        None => language
            .text(match phase.availability {
                PhaseAvailability::NotApplicable => TextKey::NetworkDetailNotApplicable,
                PhaseAvailability::Invalid => TextKey::NetworkDetailInvalid,
                PhaseAvailability::Unavailable => TextKey::NetworkDetailUnavailable,
                PhaseAvailability::Value => TextKey::NetworkDetailUnavailable,
            })
            .to_owned(),
    }
}

fn outcome_label(outcome: &str, language: Language) -> &'static str {
    language.text(match outcome {
        "completed" => TextKey::NetworkOutcomeCompleted,
        "failed" => TextKey::NetworkOutcomeFailed,
        "cancelled" => TextKey::NetworkOutcomeCancelled,
        "incomplete" => TextKey::NetworkOutcomeIncomplete,
        "in-flight" => TextKey::NetworkOutcomeInFlight,
        _ => TextKey::NetworkUnknown,
    })
}

fn connection_label(value: &str, language: Language) -> &'static str {
    language.text(match value {
        "new" => TextKey::NetworkConnectionNew,
        "reused" => TextKey::NetworkConnectionReused,
        _ => TextKey::NetworkConnectionUnknown,
    })
}

fn header_block(
    id: &'static str,
    title: TextKey,
    headers: &[(String, String)],
    language: Language,
    colors: &studio::StudioColors,
) -> Stateful<Div> {
    let mut headers = headers.to_vec();
    headers.sort_by(|left, right| {
        left.0
            .to_ascii_lowercase()
            .cmp(&right.0.to_ascii_lowercase())
    });
    let mut block = div()
        .id(id)
        .w_full()
        .flex()
        .flex_col()
        .gap(px(2.))
        .p(px(7.))
        .bg(colors.section_background)
        .rounded(px(4.))
        .child(supporting(language.text(title), colors).font_weight(FontWeight::SEMIBOLD));
    if headers.is_empty() {
        block = block.child(supporting(
            language.text(TextKey::NetworkDetailHeadersExcluded),
            colors,
        ));
    } else {
        block = block.child(supporting(
            language.text(TextKey::NetworkDetailHeadersRedacted),
            colors,
        ));
        for (name, value) in headers {
            block = block.child(
                div()
                    .whitespace_nowrap()
                    .text_ellipsis()
                    .text_size(px(11.))
                    .line_height(px(15.))
                    .text_color(colors.text)
                    .child(crate::studio::selectable(format!("{name}: {value}"))),
            );
        }
    }
    block
}

fn copy_text(row: Option<&[String]>, detail: Option<&EntryDetail>) -> String {
    let cell = |index: usize| {
        row.and_then(|row| row.get(index))
            .cloned()
            .unwrap_or_else(|| "unavailable".into())
    };
    let mut output = format!(
        "policy={}\nentry={}\nmethod={}\nurl={}\nstatus={}\nduration_ms={}\ntransferred_bytes={}\nprotocol={}\noutcome={}\nconnection={}\n",
        aps_network::MINIMIZATION_POLICY,
        cell(0),
        cell(1),
        cell(2),
        cell(3),
        cell(4),
        cell(5),
        cell(6),
        cell(7),
        cell(9),
    );
    if let Some(detail) = detail {
        if let Some(tls) = &detail.tls_version {
            output.push_str(&format!("tls={tls}\n"));
        }
        if let Some(cipher) = &detail.cipher_suite {
            output.push_str(&format!("cipher={cipher}\n"));
        }
        if let Some(failure) = &detail.failure_type {
            output.push_str(&format!("failure={failure}\n"));
        }
        output.push_str("phases:\n");
        for phase in &detail.phases {
            output.push_str(&format!(
                "{}={}|{}|{}\n",
                phase.kind.name(),
                phase
                    .duration_ms
                    .map_or_else(|| "unavailable".into(), |value| format!("{value:.3}")),
                phase.availability.name(),
                phase.confidence.name()
            ));
        }
        for (label, headers) in [
            ("request_headers", &detail.request_headers),
            ("response_headers", &detail.response_headers),
        ] {
            output.push_str(&format!("{label}:\n"));
            let mut headers = headers.clone();
            headers.sort_by(|left, right| {
                left.0
                    .to_ascii_lowercase()
                    .cmp(&right.0.to_ascii_lowercase())
            });
            for (name, value) in headers {
                output.push_str(&format!("{name}: {value}\n"));
            }
        }
    } else {
        output.push_str("phase_evidence=not_loaded\n");
    }
    output.push_str("bodies=not_captured\ncookies=not_captured\n");
    output
}

pub(super) fn request_detail(
    row: Option<&[String]>,
    detail: Option<&EntryDetail>,
    loading: bool,
    language: Language,
    colors: &studio::StudioColors,
) -> AnyElement {
    let Some(row) = row else {
        return detail_panel(language, colors, false, None)
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
    let url = row.get(2).cloned().unwrap_or_else(|| "—".into());
    let method = row.get(1).cloned().unwrap_or_else(|| "OTHER".into());
    let status = row
        .get(3)
        .filter(|value| value.as_str() != "0")
        .cloned()
        .unwrap_or_else(|| "—".into());
    let duration_ms = number(row, 4);
    let transferred = byte_value(row, 5);
    let protocol = row.get(6).cloned().unwrap_or_else(|| "—".into());
    let outcome = row.get(7).cloned().unwrap_or_default();
    let connection = row.get(9).cloned().unwrap_or_default();
    let copy = copy_text(Some(row), detail);
    let mut content = div()
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
                        .child(crate::studio::selectable(method)),
                )
                .child(badge(outcome_label(&outcome, language), colors))
                .child(div().flex_1())
                .child(supporting(
                    language.format(
                        TextKey::NetworkDetailBytesDuration,
                        &[
                            ("bytes", &transferred.map_or_else(|| "—".to_owned(), bytes)),
                            ("duration", &duration(duration_ms)),
                        ],
                    ),
                    colors,
                )),
        )
        .child(supporting(
            language.format(
                TextKey::NetworkDetailExchangeSummary,
                &[
                    ("status", &status),
                    ("protocol", &protocol),
                    ("connection", &connection_label(&connection, language)),
                ],
            ),
            colors,
        ))
        .child(supporting(
            language.text(TextKey::NetworkDetailLegend),
            colors,
        ));
    if loading && detail.is_none() {
        content = content.child(supporting(
            language.text(TextKey::NetworkDetailLoading),
            colors,
        ));
    }
    if let Some(detail) = detail {
        if let Some(tls) = &detail.tls_version {
            content = content.child(supporting(
                language.format(
                    TextKey::NetworkDetailTlsHandshake,
                    &[
                        ("tls", tls),
                        ("cipher", &detail.cipher_suite.as_deref().unwrap_or("—")),
                    ],
                ),
                colors,
            ));
        } else {
            content = content.child(supporting(
                language.text(TextKey::NetworkDetailTlsNotCaptured),
                colors,
            ));
        }
        if let Some(failure) = &detail.failure_type {
            content = content.child(
                supporting(
                    language.format(TextKey::NetworkDetailFailure, &[("kind", failure)]),
                    colors,
                )
                .text_color(colors.error),
            );
        }
    }
    let mut exchange = studio::card(colors)
        .gap(px(5.))
        .child(
            studio::section_title(language.text(TextKey::NetworkDetailPhases), colors)
                .line_height(px(17.)),
        )
        .child(supporting(
            language.text(TextKey::NetworkDetailPhaseHint),
            colors,
        ));
    for (index, kind) in DETAIL_PHASES.into_iter().enumerate() {
        let phase = detail.and_then(|detail| detail.phases.iter().find(|phase| phase.kind == kind));
        let duration_text = phase_duration_text(phase, language);
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
                        .child(crate::studio::selectable(phase_label(kind, language))),
                )
                .child(
                    supporting(duration_text, colors)
                        .w(px(84.))
                        .flex_shrink_0()
                        .whitespace_nowrap(),
                )
                .child(badge(
                    phase.map_or_else(
                        || language.text(TextKey::NetworkDetailNotObserved),
                        |phase| confidence_label(phase.confidence, language),
                    ),
                    colors,
                )),
        );
    }
    if let Some(detail) = detail {
        exchange = exchange
            .child(header_block(
                "network-request-headers",
                TextKey::NetworkDetailRequestHeadersEvidence,
                &detail.request_headers,
                language,
                colors,
            ))
            .child(
                div()
                    .id("network-request-body")
                    .w_full()
                    .flex()
                    .flex_col()
                    .gap(px(2.))
                    .p(px(7.))
                    .bg(colors.section_background)
                    .rounded(px(4.))
                    .child(
                        supporting(
                            language.text(TextKey::NetworkDetailRequestBodyEvidence),
                            colors,
                        )
                        .font_weight(FontWeight::SEMIBOLD),
                    )
                    .child(supporting(
                        language.text(TextKey::NetworkDetailBodyExcluded),
                        colors,
                    )),
            )
            .child(header_block(
                "network-response-headers",
                TextKey::NetworkDetailResponseHeadersEvidence,
                &detail.response_headers,
                language,
                colors,
            ))
            .child(
                div()
                    .id("network-response-body")
                    .w_full()
                    .flex()
                    .flex_col()
                    .gap(px(2.))
                    .p(px(7.))
                    .bg(colors.section_background)
                    .rounded(px(4.))
                    .child(
                        supporting(
                            language.text(TextKey::NetworkDetailResponseBodyEvidence),
                            colors,
                        )
                        .font_weight(FontWeight::SEMIBOLD),
                    )
                    .child(supporting(
                        language.text(TextKey::NetworkDetailBodyExcluded),
                        colors,
                    )),
            );
    }
    // Only the 36px title remains fixed. Every other block shares this one
    // viewport, so long localized caveats cannot shrink phase evidence to zero
    // in the 220px wide-layout or 216px stacked-layout detail pane.
    let viewport = div()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(4.))
        .child(content)
        .child(studio::divider(colors))
        .child(exchange)
        .child(
            studio::compact_text_button(
                "network-copy-details",
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
    detail_panel(language, colors, true, Some(&url))
        .child(
            div()
                .id("network-request-detail-scroll")
                .flex_1()
                .min_h_0()
                .min_w_0()
                .overflow_y_scrollbar()
                .child(viewport),
        )
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn phases_keep_reference_order_separate_from_content_blocks() {
        assert_eq!(DETAIL_PHASES.len(), 10);
        assert_eq!(DETAIL_PHASES[0], PhaseKind::DispatcherQueue);
        assert_eq!(DETAIL_PHASES[4], PhaseKind::RequestHeaders);
        assert_eq!(DETAIL_PHASES[5], PhaseKind::RequestBody);
        assert_eq!(DETAIL_PHASES[6], PhaseKind::ServerWait);
        assert_eq!(DETAIL_PHASES[9], PhaseKind::ConnectionHeld);
    }

    #[::core::prelude::v1::test]
    fn copied_details_use_redacted_fields_and_never_raw_values() {
        let row = [
            "3",
            "GET",
            "https://example.test/<redacted-path>?token=<redacted>",
            "503",
            "12.5",
            "1024",
            "h2",
            "failed",
            "HAR",
            "reused",
        ]
        .map(str::to_owned);
        let detail = EntryDetail {
            request_headers: vec![("Authorization".into(), "<redacted>".into())],
            response_headers: vec![("Content-Type".into(), "application/json".into())],
            phases: vec![
                EntryPhase {
                    kind: PhaseKind::Dns,
                    duration_ms: Some(2.0),
                    availability: PhaseAvailability::Value,
                    confidence: PhaseConfidence::Inferred,
                    parent: None,
                },
                EntryPhase {
                    kind: PhaseKind::Tls,
                    duration_ms: None,
                    availability: PhaseAvailability::NotApplicable,
                    confidence: PhaseConfidence::Unknown,
                    parent: Some(PhaseKind::Connect),
                },
            ],
            failure_type: Some("HAR_ERROR".into()),
            tls_version: None,
            cipher_suite: None,
        };
        let copy = copy_text(Some(&row), Some(&detail));
        assert!(copy.contains("outcome=failed\n"));
        assert!(copy.contains("DNS=2.000|VALUE|INFERRED"));
        assert!(copy.contains("TLS=unavailable|NOT_APPLICABLE|UNKNOWN"));
        assert!(copy.contains("Content-Type: application/json"));
        assert!(copy.contains("Authorization: <redacted>"));
        assert!(!copy.contains("sensitive"));
    }

    #[::core::prelude::v1::test]
    fn detail_viewport_owns_scrolling_below_fixed_header() {
        let source = include_str!("detail.rs");
        let implementation = source.split("#[cfg(test)]").next().unwrap();
        assert!(implementation.contains(".id(\"network-request-detail-scroll\")"));
        assert!(implementation.contains(".overflow_y_scrollbar()"));
    }
}
