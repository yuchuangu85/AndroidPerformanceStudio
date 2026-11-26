//! Offline HTML snapshot of the retained live-memory comparison history.

use super::axis::AxisScale;
use super::{
    HISTORY_WINDOW, MetricKey, MetricSection, Series, Session, TimedSample, meminfo::MetricUnit,
};
use crate::import::export::MAX_EXPORT_BYTES;
use aps_ui_foundation::{Language, TextKey};
use std::{
    collections::BTreeSet,
    fmt::{self, Write as _},
    ops::RangeInclusive,
    time::Duration,
};

const RAW_PREVIEW_BYTES: usize = 16 * 1024;
const SECTIONS: [(MetricSection, &str, TextKey); 5] = [
    (
        MetricSection::Summary,
        "summary",
        TextKey::MemoryCompareSummary,
    ),
    (
        MetricSection::Detail,
        "detail",
        TextKey::MemoryCompareDetail,
    ),
    (
        MetricSection::Objects,
        "objects",
        TextKey::MemoryCompareObjects,
    ),
    (MetricSection::Sql, "sql", TextKey::MemoryCompareSql),
    (
        MetricSection::Budget,
        "budget",
        TextKey::MemoryCompareBudget,
    ),
];

/// Render a captured session using the elapsed time at the moment of capture.
pub(super) fn render_html(
    session: &Session,
    elapsed: Duration,
    language: Language,
) -> Result<String, String> {
    let language = language.resolved();
    let mut html = BoundedHtml(String::new());
    render(&mut html, session, elapsed, language).map_err(|_| {
        format!(
            "{} ({MAX_EXPORT_BYTES} bytes)",
            language.text(TextKey::MemoryCompareReportSizeLimit)
        )
    })?;
    Ok(html.0)
}

struct BoundedHtml(String);

impl fmt::Write for BoundedHtml {
    fn write_str(&mut self, text: &str) -> fmt::Result {
        if text.len() > MAX_EXPORT_BYTES - self.0.len() {
            return Err(fmt::Error);
        }
        self.0.push_str(text);
        Ok(())
    }
}

fn render(
    html: &mut BoundedHtml,
    session: &Session,
    elapsed: Duration,
    language: Language,
) -> fmt::Result {
    let title = Esc(language.text(TextKey::MemoryCompareReportTitle));
    let devices = Esc(language.text(TextKey::MemoryCompareReportDevices));
    let raw = Esc(language.text(TextKey::MemoryCompareReportRaw));
    let state = language.text(if session.running() {
        TextKey::MemoryCompareRunning
    } else {
        TextKey::MemoryCompareStopped
    });
    let unavailable = language.text(TextKey::MemoryCompareUnavailable);
    write!(
        html,
        r#"<!doctype html><html lang="{}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'"><title>{}</title><style>{CSS}</style></head><body><div class="report-shell">"#,
        language.locale(),
        title,
    )?;
    write!(
        html,
        r##"<nav class="report-nav" aria-label="{}">"##,
        Esc(language.text(TextKey::MemoryCompareReportSkipTo))
    )?;
    for (_, id, label) in SECTIONS {
        write!(
            html,
            r##"<a id="nav-{id}" href="#{id}">{}</a>"##,
            Esc(language.text(label))
        )?;
    }
    write!(
        html,
        r##"<a id="nav-raw" href="#raw">{raw}</a></nav><main class="report-content" id="report-content" tabindex="0"><header><h1>{}</h1><dl><dt>{}</dt><dd>{}</dd><dt>{}</dt><dd>{}</dd><dt>{}</dt><dd>{}</dd></dl></header>"##,
        title,
        Esc(language.text(TextKey::MemoryCompareReportSessionId)),
        session.id,
        Esc(language.text(TextKey::MemoryCompareReportState)),
        Esc(state),
        Esc(language.text(TextKey::MemoryCompareReportElapsed)),
        Time(elapsed)
    )?;
    write!(
        html,
        r##"<section id="devices"><h2>{devices}</h2><div class="table"><table><thead><tr><th>{}</th><th>PID</th><th>{}</th><th>{}</th><th>{}</th><th>{}</th></tr></thead><tbody>"##,
        Esc(language.text(TextKey::MemoryCompareReportDevice)),
        Esc(language.text(TextKey::MemoryCompareReportProcess)),
        Esc(language.text(TextKey::MemoryCompareReportRetainedSamples)),
        Esc(language.text(TextKey::MemoryCompareReportLastAttemptElapsed)),
        Esc(language.text(TextKey::MemoryCompareReportLatestError))
    )?;
    for (index, series) in session.series.iter().enumerate() {
        let target = &series.target;
        let successes = series
            .samples
            .iter()
            .filter(|s| s.metrics.is_some())
            .count();
        write!(
            html,
            "<tr><th scope=\"row\">{}{} <small>[{}]</small></th><td>{}</td><td>{}</td><td>{successes} / {}</td><td>",
            Swatch(index),
            Esc(&target.device_name),
            Esc(&target.serial),
            target.pid,
            Esc(&target.process_name),
            series.samples.len()
        )?;
        if let Some(sample) = series.samples.back() {
            write!(html, "{}", Time(sample.elapsed))?;
        } else {
            html.write_str("&mdash;")?;
        }
        write!(
            html,
            "</td><td>{}</td></tr>",
            Esc(series.error.as_deref().unwrap_or("—"))
        )?;
    }
    html.write_str("</tbody></table></div></section>")?;

    let first = elapsed.saturating_sub(HISTORY_WINDOW);
    let window = first..=elapsed;
    write!(
        html,
        "<p>{} {} &ndash; {}. {}</p>",
        Esc(language.text(TextKey::MemoryCompareReportChartWindow)),
        Time(first),
        Time(elapsed),
        Esc(language.text(TextKey::MemoryCompareReportWindowNote))
    )?;
    let mut metrics = BTreeSet::new();
    for key in session
        .series
        .iter()
        .flat_map(|s| window_samples(s, &window))
        .filter_map(|s| s.metrics.as_ref())
        .flat_map(|m| m.keys())
    {
        metrics.insert(key);
        // Each distinct metric requires at least its enclosing article tags.
        if metrics.len() > MAX_EXPORT_BYTES / "<article></article>".len() {
            return Err(fmt::Error);
        }
    }
    for (section, id, label) in SECTIONS {
        write!(
            html,
            r#"<section id="{id}" class="report-panel"><h2>{}</h2>"#,
            Esc(language.text(label))
        )?;
        let keys: Vec<_> = metrics
            .iter()
            .copied()
            .filter(|key| key.section == section)
            .collect();
        if keys.is_empty() {
            write!(html, "<p>{}</p>", Esc(unavailable))?;
        } else if section == MetricSection::Objects {
            render_objects_table(html, &session.series, &keys, &window, language)?;
        } else {
            html.write_str("<div class=\"metric-grid\">")?;
            for key in keys {
                render_metric(html, &session.series, key, &window, language)?;
            }
            html.write_str("</div>")?;
        }
        html.write_str("</section>")?;
    }
    write!(
        html,
        r#"<section id="raw" class="report-panel"><h2>{raw}</h2><div class="raw-comparison"><div class="raw-grid">"#
    )?;
    for (index, series) in session.series.iter().enumerate() {
        write!(
            html,
            "<details class=\"raw-device\" open><summary>{}{} · PID {}</summary><p class=\"raw-process\">{}</p>",
            Swatch(index),
            Esc(&series.target.device_name),
            series.target.pid,
            Esc(&series.target.process_name),
        )?;
        if let Some(raw) = &series.latest_raw {
            let preview = raw.floor_char_boundary(RAW_PREVIEW_BYTES.min(raw.len()));
            // Keep the report bounded while retaining a browser-readable preview.
            write!(html, "<pre>\n{}</pre>", Esc(&raw[..preview]))?;
            if preview < raw.len() {
                write!(
                    html,
                    "<p>{}</p>",
                    Esc(language.text(TextKey::MemoryCompareReportRawTruncated))
                )?;
            }
        } else {
            write!(html, "<p>{}</p>", Esc(unavailable))?;
        }
        html.write_str("</details>")?;
    }
    html.write_str("</div></div></section></main></div></body></html>")
}

fn window_samples<'a>(
    series: &'a Series,
    window: &'a RangeInclusive<Duration>,
) -> impl DoubleEndedIterator<Item = &'a TimedSample> {
    series
        .samples
        .iter()
        .filter(|s| window.contains(&s.elapsed))
}

fn display_value(value: u64, unit: MetricUnit) -> String {
    match unit {
        MetricUnit::Kib => format!("{:.2}", value as f64 / 1024.0),
        MetricUnit::Count => value.to_string(),
    }
}

fn render_objects_table(
    html: &mut BoundedHtml,
    series: &[Series],
    keys: &[&MetricKey],
    window: &RangeInclusive<Duration>,
    language: Language,
) -> fmt::Result {
    write!(
        html,
        "<article class=\"objects-table\"><div class=\"table\"><table><thead><tr><th>{}</th>",
        Esc(language.text(TextKey::MemoryCompareReportMetric)),
    )?;
    for (index, device) in series.iter().enumerate() {
        write!(
            html,
            "<th>{}{}</th>",
            Swatch(index),
            Esc(&device.target.device_name)
        )?;
    }
    html.write_str("</tr></thead><tbody>")?;
    for key in keys {
        write!(
            html,
            "<tr><th scope=\"row\">{} / {} <small>({})</small></th>",
            Esc(&key.category),
            Esc(&key.measure),
            Esc(language.text(match key.unit {
                MetricUnit::Kib => TextKey::MemoryCompareMegabyte,
                MetricUnit::Count => TextKey::MemoryCompareCount,
            })),
        )?;
        for (index, device) in series.iter().enumerate() {
            let value = device
                .error
                .is_none()
                .then(|| {
                    window_samples(device, window)
                        .next_back()
                        .and_then(|sample| sample.metrics.as_ref()?.get(*key))
                })
                .flatten();
            match value {
                Some(value) => write!(html, "<td style=\"color:{}\">{value}</td>", Color(index))?,
                None => html.write_str("<td>&mdash;</td>")?,
            }
        }
        html.write_str("</tr>")?;
    }
    html.write_str("</tbody></table></div></article>")
}

fn render_metric(
    html: &mut BoundedHtml,
    series: &[Series],
    key: &MetricKey,
    window: &RangeInclusive<Duration>,
    language: Language,
) -> fmt::Result {
    let (first, last) = (*window.start(), *window.end());
    let unit = language.text(match key.unit {
        MetricUnit::Kib => TextKey::MemoryCompareMegabyte,
        MetricUnit::Count => TextKey::MemoryCompareCount,
    });
    let max = series
        .iter()
        .flat_map(|s| window_samples(s, window))
        .filter_map(|s| s.metrics.as_ref()?.get(key))
        .copied()
        .max()
        .unwrap_or(0)
        .max(1);
    let scale = AxisScale::new(max, key.unit);
    let max = scale.maximum;
    write!(
        html,
        r#"<article><h3>{} / {} <small>({})</small></h3><svg viewBox="0 0 760 210" role="img"><title>{} / {} · {}</title><text class="axis-unit" x="182" y="12" text-anchor="end">{}</text><path class="axis" d="M190 24V168H744"/>"#,
        Esc(&key.category),
        Esc(&key.measure),
        Esc(unit),
        Esc(&key.category),
        Esc(&key.measure),
        Esc(unit),
        Esc(unit),
    )?;
    for (fraction, label) in scale.ticks() {
        let y = 24. + 144. * fraction;
        write!(
            html,
            r#"<path class="grid" d="M190 {y}H744"/><path class="axis" d="M184 {y}H190"/><text class="y-tick" x="178" y="{y}" dy="0.35em" text-anchor="end">{}</text>"#,
            Esc(&label),
        )?;
    }
    write!(
        html,
        r#"<text x="190" y="197">{}</text><text x="744" y="197" text-anchor="end">{}</text>"#,
        Time(first),
        Time(last)
    )?;
    for (index, device) in series.iter().enumerate() {
        let mut points = window_samples(device, window)
            .filter_map(|s| s.metrics.as_ref()?.get(key))
            .peekable();
        if points.peek().is_none() {
            continue;
        }
        write!(
            html,
            r#"<path class="series" stroke="{}" d=""#,
            Color(index)
        )?;
        let span = last.saturating_sub(first).as_secs_f64();
        let mut in_run = false;
        for sample in window_samples(device, window) {
            let Some(value) = sample.metrics.as_ref().and_then(|m| m.get(key)) else {
                in_run = false;
                continue;
            };
            let x = if span == 0.0 {
                190
            } else {
                190 + (554.0 * sample.elapsed.saturating_sub(first).as_secs_f64() / span).round()
                    as u32
            };
            let y = 168 - (144.0 * (*value as f64 / max as f64)).round() as u32;
            if in_run {
                write!(html, " {x} {y}")?; // Coordinates after a moveto imply lineto.
            } else {
                // A short line with round caps makes a one-sample run visible.
                write!(html, "M{x} {y}h.01M{x} {y}")?;
                in_run = true;
            }
        }
        html.write_str("\"/>")?;
    }
    write!(
        html,
        "</svg><div class=\"table\"><table><thead><tr><th>{}</th><th>{}</th><th>{}</th><th>{}</th></tr></thead><tbody>",
        Esc(language.text(TextKey::MemoryCompareReportDevice)),
        Esc(language.text(TextKey::MemoryCompareReportAtLastAttempt)),
        Esc(language.text(TextKey::MemoryCompareReportLatestValue)),
        Esc(language.text(TextKey::MemoryCompareReportValueCount))
    )?;
    for (index, device) in series.iter().enumerate() {
        let mut values = window_samples(device, window)
            .rev()
            .filter_map(|s| Some((s.elapsed, s.metrics.as_ref()?.get(key)?)));
        let latest = values.next();
        let current = window_samples(device, window)
            .next_back()
            .and_then(|s| s.metrics.as_ref()?.get(key));
        write!(
            html,
            "<tr><th scope=\"row\">{}{} <small>[{}]</small></th><td>",
            Swatch(index),
            Esc(&device.target.device_name),
            Esc(&device.target.serial)
        )?;
        if let Some(value) = current {
            write!(html, "{}", display_value(*value, key.unit))?;
        } else {
            html.write_str("&mdash;")?;
        }
        html.write_str("</td><td>")?;
        if let Some((elapsed, value)) = latest {
            write!(
                html,
                "{} ({})",
                display_value(*value, key.unit),
                Time(elapsed)
            )?;
        } else {
            html.write_str("&mdash;")?;
        }
        write!(
            html,
            "</td><td>{}</td></tr>",
            usize::from(latest.is_some()) + values.count()
        )?;
    }
    html.write_str("</tbody></table></div></article>")
}

struct Time(Duration);
impl fmt::Display for Time {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}.{:03} s", self.0.as_secs(), self.0.subsec_millis())
    }
}

struct Esc<'a>(&'a str);
impl fmt::Display for Esc<'_> {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let mut start = 0;
        for (i, c) in self.0.char_indices() {
            let replacement = match c {
                '&' => "&amp;",
                '<' => "&lt;",
                '>' => "&gt;",
                '"' => "&quot;",
                '\'' => "&#39;",
                '\0' => "�",
                _ => continue,
            };
            f.write_str(&self.0[start..i])?;
            f.write_str(replacement)?;
            start = i + c.len_utf8();
        }
        f.write_str(&self.0[start..])
    }
}

struct Color(usize);
impl fmt::Display for Color {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let hue = (0.58 + self.0 as f32 * 0.618_034).fract() * 360.0;
        write!(f, "hsl({hue:.2},62%,34%)")
    }
}

struct Swatch(usize);
impl fmt::Display for Swatch {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(
            f,
            r#"<span class="swatch" aria-hidden="true" style="background:{}"></span>"#,
            Color(self.0)
        )
    }
}

const CSS: &str = r#"
*{box-sizing:border-box}html,body{margin:0;height:100%;overflow:hidden}body{background:#f8fafc;color:#172033;font:14px system-ui,sans-serif;overflow-wrap:anywhere}
.report-shell{display:grid;grid-template-columns:220px minmax(0,1fr);height:100vh;height:100dvh;overflow:hidden}.report-nav{height:100%;overflow:hidden;display:flex;flex-direction:column;gap:6px;padding:24px 12px;border-right:1px solid #dbe3ef;background:#eef2f7}.report-nav a{display:block;color:#174eb2;text-decoration:none;padding:12px;border-radius:6px;line-height:1.4}.report-nav a:hover{background:#dfe8f6}.report-nav a:focus-visible{outline:2px solid #174eb2;outline-offset:-2px}.report-content{min-width:0;min-height:0;overflow-y:auto;overflow-x:hidden;overscroll-behavior:contain;padding:clamp(12px,2vw,28px)}
dl{display:flex;flex-wrap:wrap;gap:8px 14px}dt,small{color:#475569}dd{margin:0 16px 0 0;font-weight:600}
.report-panel{display:none}.report-content:not(:has(.report-panel:target)) #summary{display:block}.report-content:has(.report-panel:target) .report-panel{display:none}.report-content:has(.report-panel:target) .report-panel:target{display:block}
body:not(:has(.report-panel:target)) #nav-summary,body:has(#summary:target) #nav-summary,body:has(#detail:target) #nav-detail,body:has(#objects:target) #nav-objects,body:has(#sql:target) #nav-sql,body:has(#budget:target) #nav-budget,body:has(#raw:target) #nav-raw{background:#d6e4fc;color:#123c80;font-weight:600}
@media(max-width:700px){.report-shell{grid-template-columns:140px minmax(0,1fr)}.report-nav{padding:12px 6px}.report-nav a{padding:8px 6px;font-size:12px}}@media(max-height:480px){.report-nav{padding-top:8px;gap:2px}.report-nav a{padding-top:4px;padding-bottom:4px}}
@media print{html,body{height:auto;overflow:visible}.report-shell{display:block;height:auto;overflow:visible}.report-nav{display:none}.report-content{overflow:visible}.report-content .report-panel,#summary,.report-content:has(.report-panel:target) .report-panel{display:block}}
section,article,details{margin:16px 0}article,details,.table{background:white;border:1px solid #dbe3ef;border-radius:8px}article,details{padding:12px}article h3{margin:0}article .table{border:0}.table{overflow:auto}.metric-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:16px;align-items:start}.metric-grid article{min-width:0;margin:0}.objects-table{margin-top:16px}
table{border-collapse:collapse;width:100%;font-variant-numeric:tabular-nums}th,td{text-align:left;padding:8px 12px;border-bottom:1px solid #e2e8f0;min-width:70px;overflow-wrap:anywhere}.objects-table th,.objects-table td{min-width:220px}.objects-table th:first-child{min-width:260px}.objects-table th+th,.objects-table td+td{border-left:1px solid #dbe3ef}
svg{display:block;width:100%;height:auto}svg text{fill:#475569;font-size:18px}.y-tick{font-size:22px;font-variant-numeric:tabular-nums}.axis-unit{font-size:18px}.axis,.grid,.series{fill:none}.axis{stroke:#64748b}.grid{stroke:#e2e8f0}.series{stroke-width:2;stroke-linejoin:round;stroke-linecap:round}.swatch{display:inline-block;width:10px;height:10px;border-radius:50%;margin-right:6px}
@media(max-width:800px){.metric-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:560px){.metric-grid{grid-template-columns:minmax(0,1fr)}}
summary{cursor:pointer;font-weight:600}pre{white-space:pre-wrap;overflow:auto;max-height:32em;background:#f1f5f9;padding:10px}
.raw-comparison{max-width:100%;overflow-x:auto}.raw-grid{display:flex;flex-wrap:nowrap;gap:16px;align-items:start}.raw-device{flex:1 0 520px;min-width:520px;min-inline-size:520px;margin:0;overflow:hidden}.raw-device summary,.raw-process{overflow-wrap:anywhere}.raw-device pre{white-space:pre;overflow-wrap:normal;word-break:normal;tab-size:8;font:12px/1.5 ui-monospace,Consolas,monospace;overflow:auto;max-height:none;margin-bottom:0}
"#;

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::memory::live_compare::{CancellationToken, Target, TimedSample};
    use std::{collections::BTreeMap, time::Instant};

    fn session(devices: usize) -> Session {
        Session {
            id: 9,
            started: Instant::now(),
            stopped_at: Some(Duration::from_secs(10)),
            cancel: CancellationToken::new(),
            series: (0..devices)
                .map(|index| {
                    Series::new(Target {
                        device_name: format!("Phone {index}<script>"),
                        serial: format!("serial-{index}"),
                        pid: 42,
                        process_name: "com.example&remote".into(),
                        package_name: "com.example&remote".into(),
                    })
                })
                .collect(),
        }
    }

    fn key(section: MetricSection, category: &str) -> MetricKey {
        MetricKey {
            section,
            category: category.into(),
            measure: "Value".into(),
            unit: MetricUnit::Kib,
        }
    }

    fn sample(seconds: u64, metrics: Option<BTreeMap<MetricKey, u64>>) -> TimedSample {
        TimedSample {
            elapsed: Duration::from_secs(seconds),
            metrics,
        }
    }

    #[test]
    fn snapshot_contains_sections_gaps_exact_values_and_safe_text() {
        let mut session = session(2);
        let key = key(MetricSection::Summary, "Vendor <extra> \"'");
        session.series[0].samples.extend([
            sample(2, Some(BTreeMap::from([(key.clone(), 0)]))),
            sample(3, Some(BTreeMap::new())), // Omitted metric, not zero.
            sample(8, Some(BTreeMap::from([(key.clone(), u64::MAX)]))),
            sample(9, None), // Failed attempt, not the latest value.
        ]);
        session.series[0].error = Some("failed <unexpected>".into());
        session.series[1].samples.extend([
            sample(4, Some(BTreeMap::from([(key.clone(), 0)]))),
            sample(5, None),
            sample(7, Some(BTreeMap::from([(key, 1)]))),
        ]);
        session.series[1].latest_raw = Some("\n</pre><script>alert(1)</script>&".into());
        let elapsed = Duration::from_secs(10);
        let html = render_html(&session, elapsed, Language::English).unwrap();
        assert_eq!(
            html,
            render_html(&session, elapsed, Language::English).unwrap()
        );
        for (_, section, _) in SECTIONS {
            assert!(html.contains(&format!("href=\"#{section}\"")));
            assert!(html.contains(&format!("id=\"{section}\"")));
        }
        assert!(!html.contains("<script>"));
        assert!(html.contains("Vendor &lt;extra&gt; &quot;&#39;"));
        assert!(html.contains("com.example&amp;remote"));
        assert!(html.contains("failed &lt;unexpected&gt;"));
        assert!(
            html.contains("<pre>\n\n&lt;/pre&gt;&lt;script&gt;alert(1)&lt;/script&gt;&amp;</pre>")
        );
        assert!(html.contains("<td>&mdash;</td><td>18014398509481984.00 (8.000 s)</td><td>2</td>"));
        assert!(html.contains("M301 168h.01M301 168M633 24h.01M633 24"));
        assert!(html.contains("M412 168h.01M412 168M578 168h.01M578 168"));
        assert!(html.contains(&format!("stroke=\"{}\"", Color(1))));
        assert!(html.contains(&format!("background:{}", Color(1))));
        let zh = render_html(&session, elapsed, Language::SimplifiedChinese).unwrap();
        assert!(zh.contains("lang=\"zh-CN\""));
        assert!(zh.contains("实时内存对比报告"));
    }

    #[test]
    fn running_elapsed_is_supplied_and_raw_preview_is_utf8_bounded() {
        let mut session = session(1);
        session.stopped_at = None;
        session.series[0].latest_raw = Some(format!("{}界TAIL", "x".repeat(RAW_PREVIEW_BYTES - 1)));
        let html = render_html(&session, Duration::from_millis(12_345), Language::English).unwrap();
        assert!(html.contains("12.345 s"));
        assert!(html.contains(Language::English.text(TextKey::MemoryCompareRunning)));
        assert!(html.contains("Raw meminfo preview truncated in this report."));
        assert!(!html.contains("界TAIL"));
    }

    #[test]
    fn report_navigation_is_fixed_and_switches_six_independent_panels() {
        let html = render_html(&session(2), Duration::from_secs(10), Language::English).unwrap();
        assert!(html.contains("<div class=\"report-shell\">"));
        assert!(html.contains("<nav class=\"report-nav\""));
        assert!(
            html.contains("<main class=\"report-content\" id=\"report-content\" tabindex=\"0\">")
        );
        assert_eq!(html.matches("class=\"report-panel\"").count(), 6);
        for id in ["summary", "detail", "objects", "sql", "budget", "raw"] {
            assert!(html.contains(&format!("id=\"nav-{id}\" href=\"#{id}\"")));
            assert!(html.contains(&format!("<section id=\"{id}\" class=\"report-panel\">")));
        }
        assert!(!html.contains("href=\"#devices\""));
        assert!(html.contains(
            ".report-content:has(.report-panel:target) .report-panel:target{display:block}"
        ));
        assert!(html.contains("overflow-y:auto;overflow-x:hidden"));
        assert!(!html.contains("<script>"));
    }

    #[test]
    fn raw_reports_are_horizontal_open_and_keep_fixed_width_alignment() {
        for devices in [2, 3] {
            let mut session = session(devices);
            let raw = "    Pss      Rss\n    123      456\n<unsafe>&";
            for series in &mut session.series {
                series.latest_raw = Some(raw.into());
            }
            let html = render_html(&session, Duration::from_secs(10), Language::English).unwrap();
            let section = html
                .split("<section id=\"raw\" class=\"report-panel\">")
                .nth(1)
                .unwrap();
            assert!(section.contains("class=\"raw-comparison\"><div class=\"raw-grid\">"));
            assert_eq!(
                section
                    .matches("<details class=\"raw-device\" open>")
                    .count(),
                devices
            );
            assert_eq!(
                section
                    .matches("<pre>\n    Pss      Rss\n    123      456\n&lt;unsafe&gt;&amp;</pre>")
                    .count(),
                devices
            );
            assert!(section.contains("com.example&amp;remote"));
            assert!(!section.contains("<unsafe>"));
            assert!(html.contains(".raw-grid{display:flex;flex-wrap:nowrap;"));
            assert!(html.contains(".raw-device pre{white-space:pre;"));
            assert!(html.ends_with("</div></div></section></main></div></body></html>"));
        }
    }

    #[test]
    fn chart_window_excludes_stale_and_future_points_but_keeps_diagnostics() {
        let mut session = session(2);
        session.series[0].samples.push_back(sample(
            70,
            Some(BTreeMap::from([(
                key(MetricSection::Summary, "Stale metric"),
                999,
            )])),
        ));
        session.series[0].error = Some("stalled diagnostic".into());
        session.series[1].samples.extend([
            sample(
                80,
                Some(BTreeMap::from([(
                    key(MetricSection::Summary, "Visible metric"),
                    5,
                )])),
            ),
            sample(
                201,
                Some(BTreeMap::from([(
                    key(MetricSection::Summary, "Future metric"),
                    3,
                )])),
            ),
        ]);
        let html = render_html(&session, Duration::from_secs(200), Language::English).unwrap();
        assert_eq!(html.matches("<svg ").count(), 1);
        assert!(html.contains("80.000 s &ndash; 200.000 s"));
        assert!(html.contains("Visible metric"));
        assert!(!html.contains("Stale metric"));
        assert!(!html.contains("Future metric"));
        assert!(html.contains("70.000 s</td><td>stalled diagnostic"));
    }

    #[test]
    fn objects_compare_values_without_charts_and_memory_uses_three_columns() {
        let mut session = session(2);
        let object_key = key(MetricSection::Objects, "Views");
        let total_key = key(MetricSection::Summary, "Total");
        session.series[0].samples.push_back(sample(
            1,
            Some(BTreeMap::from([
                (object_key.clone(), 12),
                (total_key.clone(), 6400),
            ])),
        ));
        session.series[1].samples.push_back(sample(
            1,
            Some(BTreeMap::from([(object_key, 0), (total_key, 7800)])),
        ));
        let html = render_html(&session, Duration::from_secs(2), Language::English).unwrap();
        let objects = html
            .split("<section id=\"objects\" class=\"report-panel\">")
            .nth(1)
            .unwrap()
            .split("</section>")
            .next()
            .unwrap();
        assert!(objects.contains("Views"));
        assert!(objects.contains(">12</td>"));
        assert!(objects.contains(">0</td>"));
        assert!(!objects.contains("<svg"));
        assert_eq!(html.matches("<svg ").count(), 1);
        assert!(html.contains("class=\"metric-grid\""));
        assert!(html.contains("grid-template-columns:repeat(3,minmax(0,1fr))"));
        assert!(html.contains("<td>6.25</td>"));
        assert!(html.contains("<td>7.62</td>"));
    }

    #[test]
    fn exported_charts_include_three_readable_y_ticks_and_units() {
        let mut session = session(2);
        let key = key(MetricSection::Summary, "Total");
        session.series[0]
            .samples
            .push_back(sample(1, Some(BTreeMap::from([(key, 8192)]))));
        let html = render_html(&session, Duration::from_secs(2), Language::English).unwrap();
        assert_eq!(html.matches("class=\"y-tick\"").count(), 3);
        assert!(html.contains("text-anchor=\"end\">8.00</text>"));
        assert!(html.contains("text-anchor=\"end\">4.00</text>"));
        assert!(html.contains("text-anchor=\"end\">0.00</text>"));
        assert!(
            html.contains("class=\"axis-unit\" x=\"182\" y=\"12\" text-anchor=\"end\">M</text>")
        );
        assert!(html.contains("viewBox=\"0 0 760 210\""));
        assert!(html.contains("M190 24V168H744"));
    }

    #[test]
    fn typical_full_history_fits_and_oversize_is_an_error() {
        let mut session = session(4);
        let keys = (0..130)
            .map(|i| {
                key(
                    SECTIONS[i % SECTIONS.len()].0,
                    &format!("Fixture metric {i:03}"),
                )
            })
            .collect::<Vec<_>>();
        for device in &mut session.series {
            device.latest_raw = Some("<&".repeat(RAW_PREVIEW_BYTES / 2));
            for tick in 0..121 {
                device.samples.push_back(sample(
                    tick,
                    Some(keys.iter().cloned().map(|key| (key, tick)).collect()),
                ));
            }
        }
        let elapsed = Duration::from_secs(120);
        let html = render_html(&session, elapsed, Language::English).unwrap();
        assert_eq!(html.matches("<svg ").count(), 104);
        assert_eq!(html.matches("<path class=\"series\"").count(), 104 * 4);
        assert!(html.len() < MAX_EXPORT_BYTES / 2, "{} bytes", html.len());
        eprintln!("Full-history fixture: {} bytes", html.len());
        session.series[0].target.process_name = "&".repeat(MAX_EXPORT_BYTES / 3);
        let error = render_html(&session, elapsed, Language::English).unwrap_err();
        assert!(error.contains("exceeds the export size limit"));
    }
}
