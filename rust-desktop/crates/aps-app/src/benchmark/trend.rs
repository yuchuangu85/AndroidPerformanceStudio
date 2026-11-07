//! Benchmark metric-history trend panel.
//!
//! Port of Compose `BenchmarkTrendPanel.kt`: the panel plots the stored metric medians in import
//! order and states when the history mixes devices/API levels or carries too few raw samples,
//! because a trend that silently compared incompatible runs would be invented evidence. The chart
//! is painted with the GPUI canvas API at the Compose dimensions (132dp plot, 64dp axis gutter,
//! three grid lines, 2px accent polyline, filled comparable dots and warning-outline dots).

use super::BenchmarkWorkspace;
use super::model::{self, BenchmarkReportsState, TREND_WINDOW};
use crate::studio::{self, FONT_SUPPORTING, StudioColors};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    component::menu::{DropdownMenu, PopupMenuItem},
    prelude::*,
    *,
};

/// Compose `TREND_CHART_HEIGHT_DP`.
const CHART_HEIGHT: f32 = 132.;
/// Compose axis gutter: a 64dp column with the max/mid/min labels, 6dp from the plot.
const AXIS_GUTTER_WIDTH: f32 = 64.;
const AXIS_GUTTER_PADDING: f32 = 6.;
/// Compose draws 3.5dp-radius circles; a fully rounded quad is the same shape.
const DOT_RADIUS: f32 = 3.5;
const DOT_DIAMETER: f32 = DOT_RADIUS * 2.0;
const METRIC_SELECTOR_WIDTH: f32 = 260.;

/// Compose `BenchmarkTrendPanel`.
pub(in crate::benchmark) fn panel(
    state: &BenchmarkReportsState,
    language: Language,
    cx: &mut Context<BenchmarkWorkspace>,
) -> AnyElement {
    let colors = StudioColors::from_theme(cx);
    let mut panel = studio::card(&colors).w_full().min_w_0();
    panel = panel.child(header(state, language, &colors, cx));
    let Some(trend) = state.trend.as_ref().filter(|trend| trend.points.len() >= 2) else {
        return panel
            .child(message(
                language.text(TextKey::BenchmarkTrendEmpty),
                colors.secondary_text,
            ))
            .into_any_element();
    };
    if trend.environment_varies {
        panel = panel.child(message(
            language.text(TextKey::BenchmarkTrendEnvironmentVaries),
            colors.warning,
        ));
    }
    if trend.has_low_sample_points {
        panel = panel.child(message(
            language.format(
                TextKey::BenchmarkTrendLowSample,
                &[("minimum", &trend.minimum_sample_count)],
            ),
            colors.error,
        ));
    }
    panel.child(chart(trend, &colors)).into_any_element()
}

/// Title, metric selector and the "Last {count} versions" window caption.
fn header(
    state: &BenchmarkReportsState,
    language: Language,
    colors: &StudioColors,
    cx: &mut Context<BenchmarkWorkspace>,
) -> Div {
    let selected = state
        .metrics
        .iter()
        .find(|option| state.selected_metric.as_deref() == Some(option.key.as_str()));
    let label: SharedString = selected
        .map(model::MetricOption::label)
        .unwrap_or_else(|| language.text(TextKey::BenchmarkTrendEmpty).to_owned())
        .into();
    let items: Vec<(String, String)> = state
        .metrics
        .iter()
        .map(|option| (option.key.clone(), option.label()))
        .collect();
    let enabled = !items.is_empty();
    let entity = cx.entity().downgrade();
    div()
        .w_full()
        .flex()
        .items_center()
        .gap(px(studio::metrics::PANEL_GAP))
        .child(div().flex_1().min_w_0().child(studio::section_title(
            language.text(TextKey::BenchmarkBenchmarkTrend),
            colors,
        )))
        .child(
            div().w(px(METRIC_SELECTOR_WIDTH)).flex_shrink_0().child(
                studio::compact_text_button(
                    "benchmark-trend-metric",
                    label,
                    enabled,
                    false,
                    colors,
                )
                .w_full()
                .justify_start()
                .dropdown_menu(move |menu, _, _| {
                    let mut menu = menu;
                    for (key, label) in &items {
                        let key = key.clone();
                        let entity = entity.clone();
                        menu = menu.item(PopupMenuItem::new(label.clone()).on_click(
                            move |_, _, cx| {
                                let key = key.clone();
                                let _ = entity.update(cx, |this, cx| {
                                    this.select_metric(key, cx);
                                });
                            },
                        ));
                    }
                    menu
                }),
            ),
        )
        .child(
            div()
                .flex_shrink_0()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(studio::selectable(language.format(
                    TextKey::BenchmarkTrendWindow,
                    &[("count", &TREND_WINDOW)],
                ))),
        )
}

fn message(text: impl Into<SharedString>, color: Rgba) -> Div {
    div()
        .w_full()
        .min_w_0()
        .text_size(px(FONT_SUPPORTING))
        .line_height(px(15.))
        .text_color(color)
        .child(studio::selectable(text))
}

fn axis_label(text: String, colors: &StudioColors) -> Div {
    div()
        .font_family("monospace")
        .text_size(px(FONT_SUPPORTING))
        .text_color(colors.muted_text)
        .whitespace_nowrap()
        .child(studio::selectable(text))
}

/// Compose `TrendChart`: axis gutter, three horizontal grid lines, the accent polyline and one
/// dot per stored point.
fn chart(trend: &model::Trend, colors: &StudioColors) -> Div {
    let values: Vec<f64> = trend.points.iter().map(|point| point.value).collect();
    let (minimum, maximum) = axis_bounds(&values).unwrap_or((0.0, 1.0));
    let span = maximum - minimum;
    let plot_points: Vec<(f32, bool)> = trend
        .points
        .iter()
        .map(|point| {
            let ratio = if span > 0.0 {
                (point.value - minimum) / span
            } else {
                0.5
            };
            (ratio.clamp(0.0, 1.0) as f32, point.comparable)
        })
        .collect();
    let stride = x_label_stride(trend.points.len());
    let labels: Vec<String> = trend
        .points
        .iter()
        .enumerate()
        .filter(|(index, _)| index % stride == 0)
        .map(|(_, point)| point.label.clone())
        .collect();
    let axis = div()
        .w(px(AXIS_GUTTER_WIDTH))
        .h(px(CHART_HEIGHT))
        .flex_shrink_0()
        .pr(px(AXIS_GUTTER_PADDING))
        .flex()
        .flex_col()
        .items_end()
        .justify_between()
        .child(axis_label(axis_text(maximum), colors))
        .child(axis_label(axis_text((maximum + minimum) / 2.0), colors))
        .child(axis_label(axis_text(minimum), colors));
    let chart_colors = *colors;
    let graph = div()
        .flex_1()
        .min_w_0()
        .flex()
        .flex_col()
        .child(
            canvas(
                |_, _, _| (),
                move |bounds: Bounds<Pixels>, _, window: &mut Window, _: &mut App| {
                    paint_chart(bounds, &plot_points, &chart_colors, window);
                },
            )
            .w_full()
            .h(px(CHART_HEIGHT)),
        )
        .child(
            div()
                .w_full()
                .flex()
                .justify_between()
                .children(labels.into_iter().map(|label| {
                    div()
                        .text_size(px(FONT_SUPPORTING))
                        .text_color(colors.muted_text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(studio::selectable(label))
                })),
        );
    div().w_full().flex().child(axis).child(graph)
}

fn paint_chart(
    bounds: Bounds<Pixels>,
    points: &[(f32, bool)],
    colors: &StudioColors,
    window: &mut Window,
) {
    let width = f32::from(bounds.size.width);
    let height = f32::from(bounds.size.height);
    if width <= 0.0 || height <= 0.0 || points.len() < 2 {
        return;
    }
    for index in 0..3 {
        let y = height * index as f32 / 2.0;
        window.paint_quad(quad(
            Bounds::new(
                point(bounds.origin.x, bounds.origin.y + px(y)),
                size(px(width), px(1.)),
            ),
            px(0.),
            colors.border,
            px(0.),
            transparent_black(),
            BorderStyle::Solid,
        ));
    }
    let step_x = width / (points.len() - 1) as f32;
    let mut path = PathBuilder::stroke(px(2.));
    for (index, (ratio, _)) in points.iter().enumerate() {
        let location = point(
            bounds.origin.x + px(step_x * index as f32),
            bounds.origin.y + px(height - ratio * height),
        );
        if index == 0 {
            path.move_to(location);
        } else {
            path.line_to(location);
        }
    }
    if let Ok(path) = path.build() {
        window.paint_path(path, colors.accent);
    }
    for (index, (ratio, comparable)) in points.iter().enumerate() {
        let center = point(
            bounds.origin.x + px(step_x * index as f32),
            bounds.origin.y + px(height - ratio * height),
        );
        let dot = Bounds::new(
            point(center.x - px(DOT_RADIUS), center.y - px(DOT_RADIUS)),
            size(px(DOT_DIAMETER), px(DOT_DIAMETER)),
        );
        let background: Background = if *comparable {
            colors.accent.into()
        } else {
            transparent_black().into()
        };
        let border_color = if *comparable {
            rgba(0x00000000)
        } else {
            colors.warning
        };
        let border_width = if *comparable { px(0.) } else { px(2.) };
        window.paint_quad(quad(
            dot,
            px(DOT_RADIUS),
            background,
            border_width,
            border_color,
            BorderStyle::Solid,
        ));
    }
}

fn axis_text(value: f64) -> String {
    model::format_metric_value(Some(value), "")
}

/// Compose `TrendChart` padding: 10% of the value span, or of the magnitude when flat.
fn axis_bounds(values: &[f64]) -> Option<(f64, f64)> {
    let raw_min = values
        .iter()
        .copied()
        .filter(|value| value.is_finite())
        .fold(f64::INFINITY, f64::min);
    let raw_max = values
        .iter()
        .copied()
        .filter(|value| value.is_finite())
        .fold(f64::NEG_INFINITY, f64::max);
    if !raw_min.is_finite() || !raw_max.is_finite() {
        return None;
    }
    let padding = if raw_max - raw_min > 0.0 {
        raw_max - raw_min
    } else if raw_max.abs() > 0.0 {
        raw_max.abs()
    } else {
        1.0
    } * 0.1;
    Some((raw_min - padding, raw_max + padding))
}

/// Compose `xLabelStride`: at most eight labels in the Compose window.
fn x_label_stride(point_count: usize) -> usize {
    if point_count <= 7 {
        1
    } else {
        point_count / 7 + 1
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn axis_bounds_pad_by_ten_percent_and_handle_flat_spans() {
        let (minimum, maximum) = axis_bounds(&[10.0, 20.0, 15.0]).expect("finite values");
        assert!((minimum - 9.0).abs() < 1e-9);
        assert!((maximum - 21.0).abs() < 1e-9);
        let (minimum, maximum) = axis_bounds(&[5.0, 5.0]).expect("flat span");
        assert!((minimum - 4.5).abs() < 1e-9);
        assert!((maximum - 5.5).abs() < 1e-9);
        let (minimum, maximum) = axis_bounds(&[0.0, 0.0]).expect("zero span");
        assert!((minimum + 0.1).abs() < 1e-9);
        assert!((maximum - 0.1).abs() < 1e-9);
        assert!(axis_bounds(&[]).is_none());
        assert!(axis_bounds(&[f64::NAN]).is_none());
    }

    #[::core::prelude::v1::test]
    fn stride_keeps_the_compose_label_budget_inside_the_ten_version_window() {
        for count in 2..=TREND_WINDOW {
            let stride = x_label_stride(count);
            let labels = (0..count).filter(|index| index % stride == 0).count();
            assert!(labels <= 8, "count {count} -> {labels} labels");
            assert!(labels >= 1);
        }
        assert_eq!(x_label_stride(7), 1);
        assert_eq!(x_label_stride(8), 2);
        assert_eq!(x_label_stride(10), 2);
        // Compose uses floor(n / 7) + 1, not ceil(n / 7); they coincide inside the window.
        assert_eq!(x_label_stride(14), 3);
    }
}
