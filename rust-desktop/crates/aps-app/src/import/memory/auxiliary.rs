//! Bitmaps / Native Heap / ART Samples pages.
//!
//! The Compose pages are the behavior oracle. HPROF bitmap findings are real
//! bounded evidence; bitmap dump capture sessions, native heap traces and ART
//! sample traces are not wired in this build, so their pages show an explicit
//! empty state and disabled capture/import actions instead of simulated data.
use super::ImportWorkspace;
use super::widgets::toolbar_button;
use crate::studio::{self, StudioColors};
use aps_memory::BitmapInstance;
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::{
        button::Button,
        scroll::{Scrollbar, ScrollbarAxis},
    },
    prelude::*,
    *,
};
use std::rc::Rc;

const BITMAP_ROW_HEIGHT: f32 = 36.;
const BITMAP_ID_WIDTH: f32 = 108.;
const BITMAP_RETAINED_WIDTH: f32 = 120.;
const SUMMARY_METRIC_WIDTH: f32 = 150.;
const TABLE_FONT: f32 = typography::BODY;
const TABLE_LINE: f32 = 16.;

/// Compact binary byte label shared by the memory findings pages.
pub(super) fn memory_bytes(value: u64) -> String {
    if value >= 1024 * 1024 {
        format!("{:.1} MiB", value as f64 / 1048576.)
    } else if value >= 1024 {
        format!("{:.1} KiB", value as f64 / 1024.)
    } else {
        format!("{value} B")
    }
}

/// Missing evidence is never rendered as zero.
pub(super) fn memory_optional_bytes(value: Option<u64>) -> String {
    value.map_or_else(|| "\u{2014}".to_owned(), memory_bytes)
}

fn text_cell(text: impl Into<SharedString>, color: Rgba) -> Div {
    div()
        .text_size(px(TABLE_FONT))
        .line_height(px(TABLE_LINE))
        .text_color(color)
        .whitespace_nowrap()
        .text_ellipsis()
        .child(crate::studio::selectable(text.into()))
}

fn supporting_line(text: impl Into<SharedString>, colors: &StudioColors) -> Div {
    div()
        .text_size(px(typography::SUPPORTING))
        .line_height(px(15.))
        .text_color(colors.secondary_text)
        .child(crate::studio::selectable(text.into()))
}

/// Compose page header: 13 px semibold title, 11 px subtitle and the disabled
/// capture/import/export actions.
fn page_title(
    title: impl Into<SharedString>,
    subtitle: impl Into<SharedString>,
    actions: Vec<Button>,
    colors: &StudioColors,
) -> AnyElement {
    div()
        .w_full()
        .flex()
        .items_center()
        .gap(px(8.))
        .child(studio::section_title(title, colors))
        .child(
            div().flex_1().min_w_0().child(
                studio::supporting(subtitle, colors)
                    .whitespace_nowrap()
                    .text_ellipsis(),
            ),
        )
        .children(actions)
        .into_any_element()
}

impl ImportWorkspace {
    pub(super) fn render_memory_bitmaps(
        &mut self,
        colors: &StudioColors,
        _cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let page = self
            .memory_state
            .snapshot
            .as_ref()
            .map(|snapshot| snapshot.findings.bitmaps.clone());
        let total = page.as_ref().map_or(0, |page| page.total);
        let rows: Rc<Vec<BitmapInstance>> = Rc::new(
            page.as_ref()
                .map_or_else(Vec::new, |page| page.rows.clone()),
        );
        let duplicate_classes = self.memory_state.duplicate_classes.len();
        let unavailable = language.text(TextKey::MemoryPageUnavailable);

        let title = page_title(
            language.text(TextKey::MemoryAppBitmapDumpView),
            language.text(TextKey::MemoryPageBitmapDumpNoSession),
            vec![
                toolbar_button(
                    "memory-bitmap-dump",
                    language.text(TextKey::MemoryPageDumpBitmaps),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::MemoryPageCaptureUnavailable)),
                toolbar_button(
                    "memory-bitmap-export",
                    language.text(TextKey::MemoryAppExportBitmapDump),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::MemoryPageCaptureUnavailable)),
            ],
            colors,
        );

        // Compose BitmapDumpSummaryMetrics: without a dump session every
        // summary metric is unavailable; the labels stay visible so the page
        // shape matches the oracle without inventing numbers.
        let mut summary = div().w_full().flex().flex_wrap().gap(px(4.));
        for (index, key) in [
            TextKey::MemoryPageBitmapContentMemory,
            TextKey::MemoryPageBitmapPngStorage,
            TextKey::MemoryPageBitmapAverageContent,
            TextKey::MemoryPageBitmapExportedImages,
            TextKey::MemoryPageBitmapUniqueImages,
            TextKey::MemoryPageBitmapDuplicateGroups,
            TextKey::MemoryPageBitmapLargestImage,
        ]
        .into_iter()
        .enumerate()
        {
            summary = summary.child(
                studio::card(colors)
                    .id(("memory-bitmap-summary", index))
                    .w(px(SUMMARY_METRIC_WIDTH))
                    .flex_shrink_0()
                    .bg(colors.section_background)
                    .border_color(colors.accent)
                    .p(px(8.))
                    .gap(px(2.))
                    .child(
                        div()
                            .text_size(px(TABLE_FONT))
                            .line_height(px(TABLE_LINE))
                            .text_color(colors.secondary_text)
                            .whitespace_nowrap()
                            .text_ellipsis()
                            .child(crate::studio::selectable(language.text(key))),
                    )
                    .child(
                        div()
                            .text_size(px(typography::TITLE))
                            .line_height(px(19.))
                            .font_weight(FontWeight::SEMIBOLD)
                            .text_color(colors.text)
                            .whitespace_nowrap()
                            .text_ellipsis()
                            .child(crate::studio::selectable(unavailable)),
                    ),
            );
        }

        let mut root = studio::page(colors)
            .id("memory-bitmaps-page")
            .h_auto()
            .flex_1()
            .min_h_0()
            .child(title)
            .child(summary);
        if rows.is_empty() {
            root = root.child(
                studio::card(colors)
                    .flex_1()
                    .min_h_0()
                    .gap(px(4.))
                    .child(studio::section_title(
                        language.text(TextKey::MemoryPageFindingBitmap),
                        colors,
                    ))
                    .child(text_cell(
                        language.text(TextKey::MemoryPageBitmapsEmpty),
                        colors.secondary_text,
                    ))
                    .child(supporting_line(
                        language.text(TextKey::MemoryPageArtifactUnavailable),
                        colors,
                    )),
            );
        } else {
            let mut table = studio::card(colors)
                .flex_1()
                .min_h_0()
                .w_full()
                .p_0()
                .gap_0()
                .child(
                    div()
                        .w_full()
                        .flex()
                        .items_center()
                        .gap(px(8.))
                        .p(px(8.))
                        .child(studio::section_title(
                            language.text(TextKey::MemoryPageFindingBitmap),
                            colors,
                        ))
                        .child(studio::supporting(
                            language.format(TextKey::MemoryPageCountValue, &[("arg_1", &total)]),
                            colors,
                        ))
                        .child(studio::supporting(
                            language.format(
                                TextKey::MemoryPageBitmapDumpSummary,
                                &[
                                    ("arg_1", &total),
                                    ("arg_2", &"\u{2014}"),
                                    ("arg_3", &"\u{2014}"),
                                    ("arg_4", &"\u{2014}"),
                                ],
                            ),
                            colors,
                        ))
                        .child(div().flex_1())
                        .child(studio::supporting(
                            format!(
                                "{}: {}",
                                language.text(TextKey::MemoryPageDuplicateBitmaps),
                                duplicate_classes
                            ),
                            colors,
                        ))
                        .child(studio::supporting(
                            format!(
                                "{}: {}",
                                language.text(TextKey::MemoryPageBitmapDuplicateGroups),
                                language.text(TextKey::MemoryPageUnavailable)
                            ),
                            colors,
                        )),
                );
            let colors_copy = *colors;
            let rows_for_list = rows.clone();
            let list = uniform_list("memory-bitmap-rows", rows_for_list.len(), {
                let rows = rows_for_list.clone();
                move |range, _, _| {
                    let mut items = Vec::with_capacity(range.len());
                    for index in range {
                        if let Some(row) = rows.get(index) {
                            items.push(bitmap_row(index, row, language, &colors_copy));
                        }
                    }
                    items
                }
            })
            .h_full()
            .w_full()
            .track_scroll(&self.memory_state.leaks_scroll);
            table = table.child(
                div()
                    .relative()
                    .flex_1()
                    .min_h_0()
                    .w_full()
                    .child(list)
                    .child(vertical_scrollbar(
                        "memory-bitmap-rows-scrollbar",
                        &self.memory_state.leaks_scroll,
                    )),
            );
            root = root.child(table);
        }
        root.into_any_element()
    }

    pub(super) fn render_memory_native_heap(
        &mut self,
        colors: &StudioColors,
        _cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let title = page_title(
            language.text(TextKey::MemoryAppNativeHeapView),
            language.text(TextKey::MemoryPageNoNativeHeapTrace),
            vec![
                toolbar_button(
                    "memory-native-capture",
                    language.text(TextKey::MemoryPageCaptureNativeHeap),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::MemoryPageCaptureUnavailable)),
                toolbar_button(
                    "memory-native-import",
                    language.text(TextKey::MemoryAppImportNativeHeap),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::MemoryPageCaptureUnavailable)),
                toolbar_button(
                    "memory-native-export",
                    language.text(TextKey::MemoryAppExportNativeHeapTrace),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::MemoryPageCaptureUnavailable)),
            ],
            colors,
        );
        studio::page(colors)
            .id("memory-native-heap-page")
            .h_auto()
            .flex_1()
            .min_h_0()
            .child(title)
            .child(
                studio::card(colors)
                    .flex_1()
                    .min_h_0()
                    .gap(px(4.))
                    .child(studio::section_title(
                        language.text(TextKey::MemoryPageNativeHeap),
                        colors,
                    ))
                    .child(text_cell(
                        language.text(TextKey::MemoryPageNativeHeapEmpty),
                        colors.secondary_text,
                    ))
                    .child(supporting_line(
                        language.text(TextKey::MemoryPageNoNativeHeapTrace),
                        colors,
                    ))
                    .child(supporting_line(
                        language.text(TextKey::MemoryPageCaptureUnavailable),
                        colors,
                    )),
            )
            .into_any_element()
    }

    pub(super) fn render_memory_art_samples(
        &mut self,
        colors: &StudioColors,
        _cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let title = page_title(
            language.text(TextKey::MemoryAppSampledArtView),
            language.text(TextKey::MemoryPageArtSampledTraceSource),
            vec![
                toolbar_button(
                    "memory-art-record",
                    language.text(TextKey::MemoryAppRecordSampledArt),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::MemoryPageCaptureUnavailable)),
                toolbar_button(
                    "memory-art-import",
                    language.text(TextKey::MemoryAppMenuImportSampledArt),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::MemoryPageCaptureUnavailable)),
                toolbar_button(
                    "memory-art-export",
                    language.text(TextKey::MemoryAppExportSampledArtTrace),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::MemoryPageCaptureUnavailable)),
            ],
            colors,
        );
        studio::page(colors)
            .id("memory-art-samples-page")
            .h_auto()
            .flex_1()
            .min_h_0()
            .child(title)
            .child(supporting_line(
                language.text(TextKey::MemoryPageArtSampledEvidenceBoundary),
                colors,
            ))
            .child(
                studio::card(colors)
                    .flex_1()
                    .min_h_0()
                    .gap(px(4.))
                    .child(text_cell(
                        language.text(TextKey::MemoryPageArtSamplesEmpty),
                        colors.secondary_text,
                    ))
                    .child(supporting_line(
                        language.text(TextKey::MemoryPageArtSampledTraceUnavailable),
                        colors,
                    ))
                    .child(supporting_line(
                        language.text(TextKey::MemoryPageCaptureUnavailable),
                        colors,
                    )),
            )
            .into_any_element()
    }
}

/// One bounded bitmap finding: object identity, class and retained size above,
/// the localized dimension and estimated pixel-byte labels beneath. Per-row
/// duplicate counts are not published; duplicate evidence is summarized by the
/// presenter-derived class count instead.
fn bitmap_row(
    index: usize,
    bitmap: &BitmapInstance,
    language: Language,
    colors: &StudioColors,
) -> AnyElement {
    let dimensions = match (bitmap.width, bitmap.height) {
        (Some(width), Some(height)) => format!("{width}×{height}"),
        _ => "\u{2014}".to_owned(),
    };
    let retained = memory_optional_bytes(bitmap.retained_size);
    let details = format!(
        "{}: {} · {}: {}",
        language.text(TextKey::MemoryPageWidthHeight),
        dimensions,
        language.text(TextKey::MemoryPageEstimatedPixelBytes),
        memory_optional_bytes(bitmap.estimated_pixel_bytes),
    );
    let row = div()
        .id(("memory-bitmap-row", index))
        .h(px(BITMAP_ROW_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .flex_col()
        .justify_center()
        .gap(px(2.))
        .px(px(8.))
        .child(
            div()
                .w_full()
                .flex()
                .items_center()
                .gap(px(6.))
                .child(
                    div()
                        .w(px(BITMAP_ID_WIDTH))
                        .flex_shrink_0()
                        .text_size(px(typography::SUPPORTING))
                        .line_height(px(15.))
                        .text_color(colors.secondary_text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(crate::studio::selectable(format!(
                            "0x{:x}",
                            bitmap.object_id
                        ))),
                )
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(TABLE_FONT))
                        .line_height(px(TABLE_LINE))
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(bitmap.class_name.clone()),
                )
                .child(
                    div()
                        .w(px(BITMAP_RETAINED_WIDTH))
                        .flex_shrink_0()
                        .text_size(px(TABLE_FONT))
                        .line_height(px(TABLE_LINE))
                        .text_color(colors.secondary_text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(
                            language
                                .format(TextKey::MemoryPageRetainedBytes, &[("arg_1", &retained)]),
                        ),
                ),
        )
        .child(supporting_line(details, colors));
    row.into_any_element()
}

fn vertical_scrollbar(id: &'static str, handle: &UniformListScrollHandle) -> AnyElement {
    div()
        .absolute()
        .inset_0()
        .child(
            Scrollbar::new(handle)
                .id(id)
                .axis(ScrollbarAxis::Vertical)
                .viewport_from_layout(),
        )
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn bytes_stay_binary_and_missing_bytes_stay_unavailable() {
        assert_eq!(memory_bytes(0), "0 B");
        assert_eq!(memory_bytes(1024), "1.0 KiB");
        assert_eq!(memory_bytes(1024 * 1024), "1.0 MiB");
        assert_eq!(memory_optional_bytes(None), "\u{2014}");
        assert_eq!(memory_optional_bytes(Some(1536)), "1.5 KiB");
    }
}
