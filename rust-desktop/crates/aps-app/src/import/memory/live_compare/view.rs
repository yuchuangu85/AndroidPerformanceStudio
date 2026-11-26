//! Presentation for the session-owned, per-device live memory comparison.

use super::axis::AxisScale;
use super::meminfo::MetricUnit;
use super::{
    DeviceRow, HISTORY_WINDOW, ImportWorkspace, MetricKey, MetricSection, Series, Session,
};
use crate::studio::{self, StudioColors, metrics};
use aps_adb::AndroidApplicationProcess;
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    component::{
        ActiveTheme,
        combobox::{Combobox, ComboboxEvent, ComboboxState},
        scroll::ScrollableElement,
        searchable_list::{SearchableListItem, SearchableVec},
    },
    prelude::*,
    *,
};
use std::{collections::BTreeSet, time::Duration};

const SECTIONS: [(MetricSection, TextKey); 5] = [
    (MetricSection::Summary, TextKey::MemoryCompareSummary),
    (MetricSection::Detail, TextKey::MemoryCompareDetail),
    (MetricSection::Objects, TextKey::MemoryCompareObjects),
    (MetricSection::Sql, TextKey::MemoryCompareSql),
    (MetricSection::Budget, TextKey::MemoryCompareBudget),
];
const RAW_PREVIEW_BYTES: usize = 16 * 1024;
const CHARTS_PER_ROW: usize = 3;

type ProcessList = SearchableVec<ProcessOption>;

#[derive(Clone)]
struct ProcessOption {
    process: AndroidApplicationProcess,
    label: SharedString,
}

impl SearchableListItem for ProcessOption {
    type Value = u32;

    fn title(&self) -> SharedString {
        self.label.clone()
    }

    fn value(&self) -> &u32 {
        &self.process.pid
    }

    fn render(&self, _: &mut Window, cx: &mut App) -> impl IntoElement {
        div()
            .flex()
            .flex_col()
            .min_w_0()
            .child(div().truncate().child(self.label.clone()))
            .when(self.process.package_name != self.process.name, |row| {
                row.child(
                    div()
                        .truncate()
                        .text_size(px(studio::FONT_SUPPORTING))
                        .text_color(cx.theme().muted_foreground)
                        .child(self.process.package_name.clone()),
                )
            })
    }

    fn matches(&self, query: &str) -> bool {
        let query = query.trim().to_ascii_lowercase();
        self.process.name.to_ascii_lowercase().contains(&query)
            || self
                .process
                .package_name
                .to_ascii_lowercase()
                .contains(&query)
            || self.process.pid.to_string().contains(&query)
    }
}

struct DevicePicker {
    combo: Entity<ComboboxState<ProcessList>>,
    processes: Vec<AndroidApplicationProcess>,
    language: Language,
    _selection: Subscription,
}

#[derive(Default)]
struct ReportControls {
    chose_section: bool,
    raw_target: Option<usize>,
}

impl ImportWorkspace {
    pub(in crate::import::memory) fn render_memory_live_compare(
        &mut self,
        window: &mut Window,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let state = &self.memory_compare;
        let running = state.running();
        let can_select = state.targets_editable();
        let selected_count = state.selected_targets().len();
        let actions = div()
            .flex()
            .flex_wrap()
            .gap(px(metrics::PANEL_GAP))
            .child(
                studio::text_button(
                    "memory-compare-refresh",
                    language.text(TextKey::MemoryCompareRefresh),
                    !running && !state.discovering,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.memory_compare_refresh(cx))),
            )
            .child(
                studio::text_button(
                    "memory-compare-start",
                    language.text(TextKey::MemoryCompareStart),
                    can_select && selected_count >= 2,
                    true,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.memory_compare_start(cx))),
            )
            .child(
                studio::text_button(
                    "memory-compare-stop",
                    language.text(TextKey::MemoryCompareStop),
                    running,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.memory_compare_stop(cx))),
            )
            .child(
                studio::text_button(
                    "memory-compare-export-html",
                    language.text(TextKey::MemoryCompareExportHtml),
                    self.memory_compare_export_ready(),
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.memory_compare_export_html(cx))),
            );
        let mut content = div()
            .id(("memory-compare-content", state.section as usize))
            .flex_1()
            .min_h_0()
            .overflow_y_scroll()
            .flex()
            .flex_col()
            .gap(px(metrics::CARD_GAP));
        if let Some(error) = &state.discovery_error {
            content = content.child(studio::card(colors).flex_shrink_0().child(error_text(
                language.format(TextKey::MemoryCompareRefreshFailed, &[("error", error)]),
                colors,
            )));
        } else if state.devices.is_empty() {
            let message = if state.discovering {
                TextKey::MemoryCompareRefreshing
            } else {
                TextKey::MemoryCompareNoDevices
            };
            content = content.child(
                studio::card(colors)
                    .flex_shrink_0()
                    .child(studio::supporting(language.text(message), colors)),
            );
        }
        if !state.devices.is_empty() {
            let mut devices = studio::card(colors).flex_shrink_0();
            if !running {
                devices = devices.child(studio::supporting(
                    language.text(TextKey::MemoryCompareChooseTargets),
                    colors,
                ));
            }
            for row in &state.devices {
                let color = state
                    .session
                    .as_ref()
                    .and_then(|session| {
                        session.series.iter().position(|series| {
                            series.target.serial == row.device.serial
                                && Some(series.target.pid) == row.selected
                        })
                    })
                    .map(|index| series_color(index, colors));
                devices = devices.child(device_row(
                    row, color, can_select, language, colors, window, cx,
                ));
            }
            content = content.child(devices);
        }

        let elapsed = state
            .session
            .as_ref()
            .map(Session::latest_elapsed)
            .unwrap_or_default();
        let start = elapsed.saturating_sub(HISTORY_WINDOW);
        let keys: BTreeSet<&MetricKey> = state
            .session
            .iter()
            .flat_map(|session| &session.series)
            .flat_map(|series| &series.samples)
            .filter(|point| point.elapsed >= start)
            .filter_map(|point| point.metrics.as_ref())
            .flat_map(|metrics| metrics.keys())
            .collect();
        let controls = window.use_keyed_state(
            (
                "memory-compare-report-controls",
                state
                    .session
                    .as_ref()
                    .map_or(0, |session| session.id.wrapping_add(1)),
            ),
            cx,
            |_, _| ReportControls::default(),
        );
        let section = if !controls.read(cx).chose_section
            && state.section == MetricSection::Summary
            && !keys.iter().any(|key| key.section == MetricSection::Summary)
        {
            keys.iter().next().map_or(state.section, |key| key.section)
        } else {
            state.section
        };

        if let Some(session) = &state.session {
            content = content.child(
                studio::card(colors)
                    .flex_shrink_0()
                    .child(studio::section_title(
                        language.text(if running {
                            TextKey::MemoryCompareRunning
                        } else {
                            TextKey::MemoryCompareStopped
                        }),
                        colors,
                    ))
                    .child(studio::supporting(
                        language.format(
                            TextKey::MemoryCompareElapsed,
                            &[
                                ("time", &elapsed_number(elapsed)),
                                ("window", &HISTORY_WINDOW.as_secs()),
                            ],
                        ),
                        colors,
                    )),
            );
            for (index, series) in session.series.iter().enumerate() {
                content = content.child(series_card(
                    session.id, elapsed, index, series, &controls, language, colors, cx,
                ));
            }
        }
        let tabs = div()
            .flex_shrink_0()
            .flex()
            .flex_wrap()
            .gap(px(metrics::CARD_GAP))
            .children(
                SECTIONS
                    .into_iter()
                    .enumerate()
                    .map(|(index, (candidate, key))| {
                        let controls = controls.clone();
                        studio::tab_button(
                            ("memory-compare-section", index),
                            language.text(key),
                            section == candidate,
                            colors,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            controls.update(cx, |controls, _| controls.chose_section = true);
                            this.memory_compare.section = candidate;
                            cx.notify();
                        }))
                    }),
            );
        let section_keys: Vec<_> = keys
            .into_iter()
            .filter(|key| key.section == section)
            .collect();
        if let Some(session) = &state.session {
            if section_keys.is_empty() {
                content = content.child(studio::card(colors).flex_shrink_0().child(
                    studio::supporting(language.text(TextKey::MemoryCompareNoMetrics), colors),
                ));
            }
            if section == MetricSection::Objects {
                if !section_keys.is_empty() {
                    content = content.child(objects_table(
                        &section_keys,
                        &session.series,
                        language,
                        colors,
                    ));
                }
            } else {
                for row_keys in section_keys.chunks(CHARTS_PER_ROW) {
                    let mut row = div()
                        .w_full()
                        .flex()
                        .flex_shrink_0()
                        .gap(px(metrics::CARD_GAP));
                    for key in row_keys {
                        row = row.child(
                            metric_card(key, &session.series, start, elapsed, language, colors)
                                .flex_1()
                                .min_w_0(),
                        );
                    }
                    for _ in row_keys.len()..CHARTS_PER_ROW {
                        row = row.child(div().flex_1().min_w_0());
                    }
                    content = content.child(row);
                }
            }
        }
        studio::page_fill(colors)
            .child(studio::section_title(
                language.text(TextKey::MemoryCompareView),
                colors,
            ))
            .child(studio::supporting(
                language.text(TextKey::MemoryCompareIntro),
                colors,
            ))
            .child(actions)
            .when_some(state.session.as_ref(), |page, session| {
                page.child(overall_values(&session.series, language, colors))
            })
            .when(!running, |page| {
                page.child(studio::supporting(
                    language.format(
                        TextKey::MemoryCompareSelectedDevices,
                        &[("count", &selected_count)],
                    ),
                    colors,
                ))
            })
            .child(tabs)
            .child(content)
            .into_any_element()
    }
}

fn device_row(
    row: &DeviceRow,
    color: Option<Rgba>,
    can_select: bool,
    language: Language,
    colors: &StudioColors,
    window: &mut Window,
    cx: &mut Context<ImportWorkspace>,
) -> Div {
    let serial = row.device.serial.clone();
    let workspace = cx.weak_entity();
    let picker = window.use_keyed_state(
        SharedString::from(format!("memory-compare-picker-{serial}")),
        cx,
        |window, cx| {
            let combo = cx.new(|cx| {
                ComboboxState::new(SearchableVec::new(Vec::new()), Vec::new(), window, cx)
                    .searchable(true)
            });
            let _selection = cx.subscribe(
                &combo,
                move |_, _, event: &ComboboxEvent<ProcessList>, cx| {
                    if let ComboboxEvent::Change(values) = event {
                        let _ = workspace.update(cx, |this, cx| {
                            this.memory_compare_select(&serial, values.first().copied(), cx)
                        });
                    }
                },
            );
            DevicePicker {
                combo,
                processes: Vec::new(),
                language,
                _selection,
            }
        },
    );
    let combo = picker.read(cx).combo.clone();
    let list_changed =
        picker.read(cx).processes != row.processes || picker.read(cx).language != language;
    if list_changed {
        picker.update(cx, |picker, _| {
            picker.processes.clone_from(&row.processes);
            picker.language = language;
        });
    }
    if list_changed || combo.read(cx).selected_value() != row.selected {
        combo.update(cx, |combo, cx| {
            if list_changed {
                let options: Vec<_> = row
                    .processes
                    .iter()
                    .map(|process| ProcessOption {
                        label: process_label(&process.name, process.pid, language).into(),
                        process: process.clone(),
                    })
                    .collect();
                combo.set_items(SearchableVec::new(options), window, cx);
            }
            combo.set_selected_values(row.selected.as_slice(), window, cx);
        });
    }
    let empty = language.text(TextKey::MemoryCompareNoMatches);
    let label = row.device.display_name();
    let picker = Combobox::new(&combo)
        .w_full()
        .h(px(metrics::BUTTON_HEIGHT))
        .placeholder(language.text(TextKey::MemoryCompareSelectProcess))
        .search_placeholder(language.text(TextKey::MemoryCompareSearchProcess))
        .menu_max_h(px(300.))
        .cleanable(true)
        .disabled(
            !can_select
                || !row.device.is_online()
                || row.error.is_some()
                || row.processes.is_empty(),
        )
        .empty(move |_, cx| {
            div()
                .p(px(metrics::COMPACT_PADDING))
                .text_color(cx.theme().muted_foreground)
                .child(empty)
        });
    let controls = div()
        .flex()
        .flex_wrap()
        .items_center()
        .gap(px(metrics::PANEL_GAP))
        .child(label_swatch(label, color, colors))
        .child(div().min_w(px(200.)).max_w(px(420.)).flex_1().child(picker));
    let status = if !row.device.is_online() {
        Some((
            language.format(
                TextKey::MemoryCompareDeviceOffline,
                &[("state", &row.device.raw_state)],
            ),
            false,
        ))
    } else if let Some(error) = &row.error {
        Some((
            language.format(TextKey::MemoryCompareProcessListFailed, &[("error", error)]),
            true,
        ))
    } else if row.processes.is_empty() {
        Some((
            language.text(TextKey::MemoryCompareNoProcesses).to_owned(),
            false,
        ))
    } else {
        None
    };
    div()
        .flex()
        .flex_col()
        .child(controls)
        .when_some(status, |row, (text, error)| {
            row.child(if error {
                error_text(text, colors)
            } else {
                studio::supporting(text, colors)
            })
        })
}

fn series_card(
    session_id: u64,
    elapsed: Duration,
    index: usize,
    series: &Series,
    controls: &Entity<ReportControls>,
    language: Language,
    colors: &StudioColors,
    cx: &mut Context<ImportWorkspace>,
) -> Div {
    let expanded = controls.read(cx).raw_target == Some(index);
    let raw = series
        .latest_raw
        .as_deref()
        .filter(|_| series.error.is_none());
    let color = series_color(index, colors);
    let series_label = label_swatch(series.target.device_name.clone(), Some(color), colors).child(
        studio::supporting(
            process_label(&series.target.process_name, series.target.pid, language),
            colors,
        ),
    );
    let controls = controls.clone();
    let actions = div()
        .flex()
        .flex_wrap()
        .gap(px(metrics::PANEL_GAP))
        .child(
            studio::compact_text_button(
                ("memory-compare-show-dump", index),
                language.text(if expanded {
                    TextKey::MemoryCompareHideDump
                } else {
                    TextKey::MemoryCompareShowDump
                }),
                raw.is_some() || expanded,
                false,
                colors,
            )
            .on_click(move |_, _, cx| {
                controls.update(cx, |controls, cx| {
                    controls.raw_target = if controls.raw_target == Some(index) {
                        None
                    } else {
                        Some(index)
                    };
                    cx.notify();
                })
            }),
        )
        .child(
            studio::compact_text_button(
                ("memory-compare-copy-dump", index),
                language.text(TextKey::MemoryCompareCopyDump),
                raw.is_some(),
                false,
                colors,
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                if let Some(series) = this
                    .memory_compare
                    .session
                    .as_ref()
                    .filter(|session| session.id == session_id)
                    .and_then(|session| session.series.get(index))
                    .filter(|series| series.error.is_none())
                    && let Some(raw) = &series.latest_raw
                {
                    cx.write_to_clipboard(ClipboardItem::new_string(raw.clone()));
                }
            })),
        );
    let card = studio::card(colors)
        .flex_shrink_0()
        .child(
            div()
                .flex()
                .flex_wrap()
                .items_center()
                .justify_between()
                .gap(px(metrics::PANEL_GAP))
                .child(series_label)
                .child(actions),
        )
        .child(studio::supporting(
            series.samples.back().map_or_else(
                || language.text(TextKey::MemoryCompareWaiting).to_owned(),
                |sample| {
                    language.format(
                        TextKey::MemoryCompareObserved,
                        &[
                            ("time", &elapsed_number(sample.elapsed)),
                            (
                                "age",
                                &elapsed_number(elapsed.saturating_sub(sample.elapsed)),
                            ),
                        ],
                    )
                },
            ),
            colors,
        ))
        .when_some(series.error.as_ref(), |card, error| {
            card.child(error_text(
                language.format(TextKey::MemoryCompareSampleFailed, &[("error", error)]),
                colors,
            ))
        });
    card.when(expanded, |card| {
        card.child(studio::supporting(
            language.text(TextKey::MemoryCompareRawPreview),
            colors,
        ))
        .child(match raw {
            Some(raw) => {
                let mut end = raw.len().min(RAW_PREVIEW_BYTES);
                while !raw.is_char_boundary(end) {
                    end -= 1;
                }
                div()
                    .flex()
                    .flex_col()
                    .gap(px(metrics::CARD_GAP))
                    .child(
                        div()
                            .id(("memory-compare-raw", index))
                            .max_h(px(200.))
                            .overflow_y_scroll()
                            .p(px(metrics::COMPACT_PADDING))
                            .bg(colors.field)
                            .font_family("monospace")
                            .text_size(px(studio::FONT_SUPPORTING))
                            .child(raw[..end].to_owned()),
                    )
                    .when(end < raw.len(), |preview| {
                        preview.child(studio::supporting(
                            language.text(TextKey::MemoryCompareRawLimited),
                            colors,
                        ))
                    })
            }
            None => studio::supporting(language.text(TextKey::MemoryCompareUnavailable), colors),
        })
    })
}

/// Always read the newest observation. Older successes must not become a
/// misleading live value after a failed or omitted observation.
fn current_value_label(series: &Series, key: &MetricKey, language: Language) -> String {
    series.current_value(key).map_or_else(
        || language.text(TextKey::MemoryCompareUnavailable).to_owned(),
        |value| match key.unit {
            MetricUnit::Kib => language.format(
                TextKey::MemoryCompareValue,
                &[
                    ("value", &format_megabytes(value)),
                    ("unit", &language.text(TextKey::MemoryCompareMegabyte)),
                ],
            ),
            MetricUnit::Count => language.format(
                TextKey::MemoryCompareValue,
                &[
                    ("value", &value),
                    ("unit", &language.text(TextKey::MemoryCompareCount)),
                ],
            ),
        },
    )
}

fn format_megabytes(kib: u64) -> String {
    format!("{:.2}", kib as f64 / 1024.0)
}

fn overall_keys() -> [MetricKey; 3] {
    ["PSS", "RSS", "Swap PSS"].map(|measure| MetricKey {
        section: MetricSection::Summary,
        category: "Total".into(),
        measure: measure.into(),
        unit: MetricUnit::Kib,
    })
}

fn overall_values(series: &[Series], language: Language, colors: &StudioColors) -> Div {
    div()
        .w_full()
        .flex()
        .flex_shrink_0()
        .gap(px(metrics::CARD_GAP))
        .children(overall_keys().iter().map(|key| {
            studio::card(colors)
                .flex_1()
                .min_w_0()
                .child(studio::section_title(
                    format!("Total {}", key.measure),
                    colors,
                ))
                .children(series.iter().enumerate().map(|(index, device)| {
                    let color = series_color(index, colors);
                    div()
                        .flex()
                        .flex_wrap()
                        .gap(px(metrics::CARD_GAP))
                        .child(label_swatch(
                            device.target.device_name.clone(),
                            Some(color),
                            colors,
                        ))
                        .child(studio::dense_label(
                            current_value_label(device, key, language),
                            color,
                        ))
                }))
        }))
}

fn objects_table(
    keys: &[&MetricKey],
    series: &[Series],
    language: Language,
    colors: &StudioColors,
) -> AnyElement {
    const FIELD_WIDTH: f32 = 260.;
    const DEVICE_WIDTH: f32 = 320.;
    let width = FIELD_WIDTH + DEVICE_WIDTH * series.len() as f32;
    let mut heading = div().w(px(width)).flex().bg(colors.toolbar).child(
        div()
            .w(px(FIELD_WIDTH))
            .flex_shrink_0()
            .p_2()
            .child(language.text(TextKey::MemoryCompareObjects)),
    );
    for (index, device) in series.iter().enumerate() {
        heading = heading.child(
            div()
                .w(px(DEVICE_WIDTH))
                .flex_shrink_0()
                .min_w_0()
                .p_2()
                .border_l_1()
                .border_color(colors.border)
                .child(
                    label_swatch(
                        device.target.device_name.clone(),
                        Some(series_color(index, colors)),
                        colors,
                    )
                    .min_w_0(),
                ),
        );
    }
    let mut table = div().min_w(px(width)).flex().flex_col().child(heading);
    for key in keys {
        let mut row = div()
            .w(px(width))
            .flex()
            .border_t_1()
            .border_color(colors.border)
            .child(
                div()
                    .w(px(FIELD_WIDTH))
                    .flex_shrink_0()
                    .min_w_0()
                    .p_2()
                    .child(format!("{} · {}", key.category, key.measure)),
            );
        for (index, device) in series.iter().enumerate() {
            row = row.child(
                div()
                    .w(px(DEVICE_WIDTH))
                    .flex_shrink_0()
                    .min_w_0()
                    .p_2()
                    .border_l_1()
                    .border_color(colors.border)
                    .text_color(series_color(index, colors))
                    .child(current_value_label(device, key, language)),
            );
        }
        table = table.child(row);
    }
    studio::card(colors)
        .min_w_0()
        .flex_shrink_0()
        .child(
            div()
                .id("memory-compare-objects-table")
                .w_full()
                .overflow_x_scrollbar()
                .child(table),
        )
        .into_any_element()
}

fn metric_card(
    key: &MetricKey,
    series: &[Series],
    start: Duration,
    end: Duration,
    language: Language,
    colors: &StudioColors,
) -> Div {
    let unit = language.text(match key.unit {
        MetricUnit::Kib => TextKey::MemoryCompareMegabyte,
        MetricUnit::Count => TextKey::MemoryCompareCount,
    });
    let values = div()
        .flex()
        .flex_wrap()
        .gap(px(metrics::PANEL_GAP))
        .children(series.iter().enumerate().map(|(index, series)| {
            let value = current_value_label(series, key, language);
            let color = series_color(index, colors);
            div()
                .min_w_0()
                .flex()
                .flex_col()
                .child(label_swatch(
                    series.target.device_name.clone(),
                    Some(color),
                    colors,
                ))
                .child(studio::dense_label(value, color))
        }));
    let width = end.saturating_sub(start).as_secs_f64().max(f64::EPSILON);
    let mut maximum = 0;
    let traces = series
        .iter()
        .enumerate()
        .map(|(index, series)| {
            let points = series
                .samples
                .iter()
                .filter(|point| point.elapsed >= start && point.elapsed <= end)
                .map(|point| {
                    let value = point
                        .metrics
                        .as_ref()
                        .and_then(|metrics| metrics.get(key))
                        .copied();
                    maximum = maximum.max(value.unwrap_or_default());
                    let x = (point.elapsed.saturating_sub(start).as_secs_f64() / width) as f32;
                    (x, value)
                })
                .collect::<Vec<_>>();
            (series_color(index, colors), points)
        })
        .collect::<Vec<_>>();
    let scale = AxisScale::new(maximum, key.unit);
    let maximum = scale.maximum;
    let ticks = scale.ticks();
    let gutter = ticks
        .iter()
        .map(|(_, label)| label.len())
        .max()
        .unwrap_or(1) as f32
        * 7.
        + 10.;
    let y_labels = div()
        .w(px(gutter))
        .h(px(104.))
        .flex_shrink_0()
        .flex()
        .flex_col()
        .justify_between()
        .children(ticks.into_iter().map(|(_, label)| {
            div()
                .w_full()
                .h(px(16.))
                .flex()
                .justify_end()
                .items_center()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(label)
        }));
    let axis_mid = start + end.saturating_sub(start) / 2;
    let axes = div()
        .pl(px(gutter + 3.))
        .pr(px(3.))
        .flex()
        .justify_between()
        .children([start, axis_mid, end].into_iter().map(|time| {
            studio::supporting(
                language.format(
                    TextKey::MemoryCompareAxisTime,
                    &[("time", &elapsed_number(time))],
                ),
                colors,
            )
        }));
    let border = colors.border;
    let axis_color = colors.secondary_text;
    let chart = canvas(
        |_, _, _| (),
        move |bounds, _, window, _| {
            let width = bounds.size.width - px(6.);
            let height = bounds.size.height - px(16.);
            if width <= px(0.) || height <= px(0.) {
                return;
            }
            let origin = point(bounds.origin.x + px(3.), bounds.origin.y + px(8.));
            for y in [0., 0.5, 1.] {
                paint_rect(
                    point(origin.x, origin.y + height * y),
                    size(width, px(1.)),
                    px(0.),
                    border,
                    window,
                );
            }
            // Explicit left Y axis and bottom X axis, with ticks at each label.
            paint_rect(origin, size(px(1.), height), px(0.), axis_color, window);
            for fraction in [0., 0.5, 1.] {
                paint_rect(
                    point(origin.x - px(3.), origin.y + height * fraction),
                    size(px(4.), px(1.)),
                    px(0.),
                    axis_color,
                    window,
                );
            }
            paint_rect(
                point(origin.x, origin.y + height),
                size(width, px(1.)),
                px(0.),
                axis_color,
                window,
            );
            for (color, trace) in &traces {
                let mut line = PathBuilder::stroke(px(1.5));
                let mut previous = false;
                for (index, (x, value)) in trace.iter().enumerate() {
                    let Some(value) = value else {
                        previous = false;
                        continue;
                    };
                    let y = (*value as f64 / maximum.max(1) as f64) as f32;
                    let location = point(origin.x + width * *x, origin.y + height * (1. - y));
                    if previous {
                        line.line_to(location);
                    } else {
                        line.move_to(location);
                        if !trace.get(index + 1).is_some_and(|(_, next)| next.is_some()) {
                            paint_rect(
                                point(location.x - px(2.), location.y - px(2.)),
                                size(px(4.), px(4.)),
                                px(2.),
                                *color,
                                window,
                            );
                        }
                    }
                    previous = true;
                }
                if let Ok(path) = line.build() {
                    window.paint_path(path, *color);
                }
            }
        },
    )
    .flex_1()
    .min_w_0()
    .h(px(104.));
    studio::card(colors)
        .flex_shrink_0()
        .child(studio::section_title(
            language.format(
                TextKey::MemoryCompareMetric,
                &[("category", &key.category), ("measure", &key.measure)],
            ),
            colors,
        ))
        .child(values)
        .child(studio::supporting(
            language.format(
                TextKey::MemoryCompareScale,
                &[
                    (
                        "max",
                        &if key.unit == MetricUnit::Kib {
                            format_megabytes(maximum.max(1))
                        } else {
                            maximum.max(1).to_string()
                        },
                    ),
                    ("unit", &unit),
                ],
            ),
            colors,
        ))
        .child(div().w_full().flex().child(y_labels).child(chart))
        .child(axes)
}

fn paint_rect(
    origin: Point<Pixels>,
    size: Size<Pixels>,
    radius: Pixels,
    color: Rgba,
    window: &mut Window,
) {
    window.paint_quad(quad(
        Bounds::new(origin, size),
        radius,
        color,
        px(0.),
        transparent_black(),
        BorderStyle::Solid,
    ));
}

fn label_swatch(label: String, color: Option<Rgba>, colors: &StudioColors) -> Div {
    div()
        .flex()
        .items_center()
        .gap(px(metrics::CARD_GAP))
        .min_w_0()
        .when_some(color, |row, color| {
            row.child(div().size(px(8.)).flex_shrink_0().rounded_full().bg(color))
        })
        .child(
            div()
                .min_w_0()
                .truncate()
                .text_color(color.unwrap_or(colors.text))
                .font_weight(FontWeight::SEMIBOLD)
                .child(label),
        )
}

fn error_text(text: String, colors: &StudioColors) -> Div {
    studio::supporting(text, colors).text_color(colors.error)
}

fn process_label(name: &str, pid: u32, language: Language) -> String {
    language.format(
        TextKey::MemoryCompareProcess,
        &[("name", &name), ("pid", &pid)],
    )
}

fn elapsed_number(elapsed: Duration) -> String {
    format!("{:.1}", elapsed.as_secs_f64())
}

fn series_color(index: usize, colors: &StudioColors) -> Rgba {
    // A golden-angle hue step avoids repeating colors when more devices join.
    let hue = (0.58 + index as f32 * 0.618_034).fract();
    hsla(hue, 0.62, if colors.dark { 0.68 } else { 0.34 }, 1.).into()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn overall_memory_is_always_three_distinct_metrics() {
        assert_eq!(CHARTS_PER_ROW, 3);
        let keys = overall_keys();
        assert_eq!(keys.map(|key| key.measure), ["PSS", "RSS", "Swap PSS"]);
        assert!(
            overall_keys()
                .iter()
                .all(|key| key.section == MetricSection::Summary
                    && key.category == "Total"
                    && key.unit == MetricUnit::Kib)
        );
    }
}
