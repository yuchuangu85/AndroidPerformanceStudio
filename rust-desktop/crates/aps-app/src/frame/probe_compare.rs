//! Operator-confirmed like-for-like runs; no automatic causality or source writes.
use super::FrameWorkspace;
use crate::studio::{self, StudioColors};
use aps_frame::{FrameAnalysisResult, FrameCaptureSession, FrameDeadlineVerdict, FrameSource};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    component::{WindowExt, scroll::ScrollableElement},
    prelude::*,
    *,
};
use std::collections::{BTreeMap, BTreeSet};
const LIMIT: usize = 8;
#[derive(Default)]
pub(super) struct Comparison {
    groups: [Vec<Run>; 2],
}
#[derive(PartialEq)]
struct Scope {
    serial: String,
    package: String,
    process: String,
    api: u32,
    rates: Vec<i64>,
    budgets: Vec<i64>,
}
struct Run {
    scope: Scope,
    hash: String,
    id: String,
    p95: i64,
    miss: usize,
    known: usize,
    frames: usize,
    measured: usize,
    probes: BTreeMap<String, Probe>,
    complete: bool,
}
struct Probe {
    windows: usize,
    spans: usize,
    p95: i64,
}
fn pct95(mut v: Vec<i64>) -> Option<i64> {
    let i = (v.len() * 95).div_ceil(100).checked_sub(1)?;
    Some(*v.select_nth_unstable(i).1)
}
fn median(mut v: Vec<i64>) -> Option<i64> {
    v.sort_unstable();
    let n = v.len();
    if n == 0 {
        return None;
    }
    Some(((i128::from(v[(n - 1) / 2]) + i128::from(v[n / 2])) / 2) as i64)
}
fn close(a: i64, b: i64) -> bool {
    a > 0 && b > 0 && a.abs_diff(b) <= (a.min(b) as u64 / 100).max(1)
}
fn signature(vals: BTreeSet<i64>) -> Vec<i64> {
    let mut out = vec![];
    for n in vals {
        if out.last().is_none_or(|v| !close(*v, n)) {
            out.push(n);
        }
    }
    out
}
fn compatible(a: &[i64], b: &[i64]) -> bool {
    a.len() == b.len() && a.iter().zip(b).all(|(x, y)| close(*x, *y))
}
impl Run {
    fn capture(
        session: &FrameCaptureSession,
        analysis: &FrameAnalysisResult,
        hash: &str,
    ) -> Result<Self, String> {
        let fail =
            || "A new, complete target-scoped FrameTimeline capture is required.".to_string();
        let (Some(package), Some(serial), Some(api)) = (
            &session.package_name,
            &session.device_serial,
            session.device_api_level,
        ) else {
            return Err(fail());
        };
        if hash.len() != 64
            || !hash.bytes().all(|b| b.is_ascii_hexdigit())
            || package.is_empty()
            || serial.is_empty()
            || session.source != FrameSource::Perfetto
            || session.id.is_empty()
            || !session.provenance_complete
            || session.imported_file.is_some()
        {
            return Err(fail());
        }
        let mut times = vec![];
        let mut budgets = BTreeSet::new();
        let mut rates = BTreeSet::new();
        let (mut miss, mut known) = (0, 0);
        let mut probes: BTreeMap<String, (usize, Vec<i64>)> = BTreeMap::new();
        let mut complete = true;
        let mut processes = BTreeSet::new();
        let mut pids = BTreeSet::new();
        let mut add_rate = |hz: f64| {
            if hz.is_finite() && (1.0..=1000.).contains(&hz) {
                rates.insert((hz * 1000.).round() as i64);
                Ok(())
            } else {
                Err(fail())
            }
        };
        for hz in &session.observed_refresh_rates_hz {
            add_rate(*hz)?;
        }
        for f in &analysis.frames {
            let s = &f.sample;
            if s.source != FrameSource::Perfetto
                || s.session_id != session.id
                || s.package_name.as_ref() != Some(package)
                || !s.process_id.is_some_and(|i| i > 0)
            {
                return Err(fail());
            }
            let process = s
                .states
                .get("aps.target.process")
                .filter(|p| !p.is_empty())
                .ok_or_else(fail)?;
            processes.insert(process.clone());
            pids.insert(s.process_id);
            if s.states.contains_key("aps.trace.omitted") {
                complete = false;
            }
            let Some(ns) = s.resolved_duration_ns().filter(|n| *n > 0) else {
                continue;
            };
            times.push(ns);
            if let Some(b) = s.known_expected_duration_ns() {
                budgets.insert(b);
            }
            if let Some(hz) = s.refresh_rate_hz {
                add_rate(hz)?;
            }
            match f.deadline_verdict {
                FrameDeadlineVerdict::Missed => {
                    miss += 1;
                    known += 1
                }
                FrameDeadlineVerdict::Met => known += 1,
                _ => {}
            }
            let mut seen = BTreeSet::new();
            for p in super::perfetto::probes(s) {
                let e = probes.entry(p.marker.clone()).or_default();
                if seen.insert(p.marker) {
                    e.0 += 1
                }
                e.1.push(p.overlap_ns);
            }
            if probes.len() > 256 {
                return Err("Too many distinct probes to compare.".into());
            }
        }
        let n = times.len();
        if n < 2
            || known == 0
            || budgets.is_empty()
            || rates.is_empty()
            || processes.len() != 1
            || pids.len() != 1
        {
            return Err(fail());
        }
        Ok(Self {
            scope: Scope {
                serial: serial.clone(),
                package: package.clone(),
                process: processes.pop_first().unwrap(),
                api,
                rates: signature(rates),
                budgets: signature(budgets),
            },
            hash: hash.to_ascii_lowercase(),
            id: session.id.clone(),
            p95: pct95(times).unwrap(),
            miss,
            known,
            frames: analysis.frames.len(),
            measured: n,
            probes: probes
                .into_iter()
                .map(|(k, (windows, v))| {
                    (
                        k,
                        Probe {
                            windows,
                            spans: v.len(),
                            p95: pct95(v).unwrap(),
                        },
                    )
                })
                .collect(),
            complete,
        })
    }
}
impl Comparison {
    fn add(&mut self, side: usize, run: Run) -> Result<(), String> {
        if side > 1 || side == 1 && self.groups[0].is_empty() {
            return Err("Pin a baseline run first.".into());
        }
        if self.groups[side].len() >= LIMIT {
            return Err("Run limit reached. Clear the comparison first.".into());
        }
        for r in self.groups.iter().flatten() {
            let (a, b) = (&r.scope, &run.scope);
            if r.id == run.id || r.hash == run.hash {
                return Err("This trace was already added.".into());
            }
            if a.package != b.package
                || a.serial != b.serial
                || a.process != b.process
                || a.api != b.api
                || !compatible(&a.rates, &b.rates)
                || !compatible(&a.budgets, &b.budgets)
            {
                return Err(
                    "Package, process, device, API, observed refresh or frame budgets differ."
                        .into(),
                );
            }
        }
        self.groups[side].push(run);
        Ok(())
    }
    fn rows(&self, l: Language) -> Vec<String> {
        let mut rows = vec![];
        for (i, g) in self.groups.iter().enumerate() {
            if g.is_empty() {
                continue;
            }
            let p = median(g.iter().map(|r| r.p95).collect()).unwrap();
            let miss: usize = g.iter().map(|r| r.miss).sum();
            let known: usize = g.iter().map(|r| r.known).sum();
            let title = l.text(if i == 0 {
                TextKey::FrameCompareBaselineLabel
            } else {
                TextKey::FrameCompareAfterLabel
            });
            rows.push(format!(
                "{title} ×{} · P95 {:.2} ms · {miss}/{known} ({:.1}%) · n={}/{}",
                g.len(),
                p as f64 / 1e6,
                100. * miss as f64 / known as f64,
                g.iter().map(|r| r.measured).sum::<usize>(),
                g.iter().map(|r| r.frames).sum::<usize>()
            ));
        }
        if self.groups.iter().all(|g| !g.is_empty()) {
            let [base, after] = &self.groups;
            let percentile =
                |g: &[Run]| median(g.iter().map(|r| r.p95).collect()).unwrap() as f64 / 1e6;
            let rate = |g: &[Run]| {
                100. * g.iter().map(|r| r.miss).sum::<usize>() as f64
                    / g.iter().map(|r| r.known).sum::<usize>() as f64
            };
            rows.push(format!(
                "Δ P95 {:+.2} ms · Δ {:+.2} pp",
                percentile(after) - percentile(base),
                rate(after) - rate(base)
            ));
            let markers: BTreeSet<_> = self
                .groups
                .iter()
                .flatten()
                .flat_map(|r| r.probes.keys())
                .collect();
            for marker in markers.iter().take(16) {
                let pair = self.groups.each_ref().map(|runs| {
                    let matched: Vec<_> =
                        runs.iter().filter_map(|r| r.probes.get(*marker)).collect();
                    let n = matched.len();
                    let p = if n == runs.len() && runs.iter().all(|r| r.complete) {
                        median(matched.iter().map(|p| p.p95).collect())
                    } else {
                        None
                    };
                    format!(
                        "{} [{}/{}; {} / {}]",
                        super::model::format_millis(p),
                        n,
                        runs.len(),
                        matched.iter().map(|v| v.windows).sum::<usize>(),
                        matched.iter().map(|v| v.spans).sum::<usize>()
                    )
                });
                rows.push(format!("{marker}: {} → {}", pair[0], pair[1]));
            }
            if markers.len() > 16 {
                rows.push(format!("+{}", markers.len() - 16));
            }
        }
        rows
    }
}
impl FrameWorkspace {
    pub(super) fn show_comparison(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let lang = self.language;
        let entity = cx.entity();
        let rows = self.comparison.rows(lang);
        let bound = self.current_session.as_ref().map(|s| s.id.clone());
        window.open_dialog(cx, move |dialog, _, cx| {
            let c = StudioColors::from_theme(cx);
            let mut content = div()
                .id("frame-compare-runs")
                .max_h(px(480.))
                .overflow_y_scrollbar()
                .flex()
                .flex_col()
                .gap(px(8.))
                .child(studio::selectable(lang.text(TextKey::FrameCompareHelp)));
            for row in &rows {
                content = content.child(studio::selectable(row.clone()));
            }
            for (i, k) in [
                TextKey::FrameCompareBase,
                TextKey::FrameCompareAfter,
                TextKey::FrameCompareClear,
            ]
            .into_iter()
            .enumerate()
            {
                let e = entity.clone();
                let bound = bound.clone();
                content = content.child(
                    studio::compact_text_button(
                        ("frame-compare-action", i),
                        lang.text(k),
                        true,
                        false,
                        &c,
                    )
                    .on_click(move |_, w, cx| {
                        w.close_dialog(cx);
                        e.update(cx, |f, cx| {
                            let result = if i == 2 {
                                f.comparison = Default::default();
                                Ok(())
                            } else {
                                (|| {
                                    let s = f
                                        .current_session
                                        .as_ref()
                                        .filter(|s| {
                                            Some(&s.id) == bound.as_ref()
                                                && !f.is_loading
                                                && !f.is_capturing
                                        })
                                        .ok_or("Session changed.")?;
                                    let a = f.analysis.as_deref().ok_or("No analysis.")?;
                                    let art = f
                                        .artifact
                                        .as_ref()
                                        .filter(|art| s.artifact.as_ref() == Some(&art.id))
                                        .ok_or("Missing trace identity.")?;
                                    f.comparison.add(i, Run::capture(s, a, &art.sha256)?)
                                })()
                            };
                            if let Err(err) = result {
                                f.error = Some(err);
                            } else {
                                f.error = None;
                                f.show_comparison(w, cx);
                            }
                            cx.notify();
                        });
                    }),
                );
            }
            dialog
                .title(lang.text(TextKey::FrameCompareTitle))
                .w(px(650.))
                .child(content)
        });
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::frame::probe_tests::{samples, session};
    const MARKER: &str = "APS.Frame/0000000000000001";
    fn make(id: usize, durations: &[i64; 4], with_probe: bool) -> Run {
        let mut s = session(&format!("run-{id}"));
        let mut frames = samples(MARKER);
        for (f, d) in frames.iter_mut().zip(durations) {
            f.session_id = s.id.clone();
            f.total_duration_ns = Some(*d);
            if !with_probe {
                f.states.remove("aps.trace.0");
            }
        }
        s.artifact = Some(format!("art-{id}"));
        Run::capture(
            &s,
            &aps_frame::FrameJankAnalyzer::new().analyze(&frames),
            &format!("{id:064x}"),
        )
        .unwrap()
    }
    #[::core::prelude::v1::test]
    fn cohorts_reject_duplicates_and_changes_and_preserve_missing_coverage() {
        let mut c = Comparison::default();
        assert!(c.add(1, make(1, &[12, 15, 16, 20], true)).is_err());
        c.add(0, make(1, &[12, 15, 16, 20], true)).unwrap();
        let mut duplicate = make(1, &[12, 15, 16, 20], true);
        duplicate.id = "another path".into();
        assert!(c.add(1, duplicate).is_err());
        for i in 0..6 {
            let mut wrong = make(i + 2, &[12, 15, 16, 20], true);
            match i {
                0 => wrong.scope.serial.push('2'),
                1 => wrong.scope.package.push('x'),
                2 => wrong.scope.process.push('x'),
                3 => wrong.scope.api += 1,
                4 => wrong.scope.budgets = vec![33],
                _ => wrong.scope.rates = vec![90000],
            }
            assert!(c.add(1, wrong).is_err());
        }
        c.add(0, make(2, &[10, 11, 12, 42], true)).unwrap();
        c.add(1, make(3, &[12, 12, 12, 12], false)).unwrap();
        let [base, after] = &c.groups;
        assert_eq!(median(base.iter().map(|r| r.p95).collect()), Some(31));
        assert_eq!((base[0].miss, base[0].known, after[0].miss), (1, 4, 0));
        let rows = c.rows(Language::English);
        assert!(rows.iter().any(|r| r.contains("-25.00 pp")));
        assert!(
            rows.iter().any(|r| r.starts_with(MARKER)
                && r.contains("[0/1; 0 / 0]")
                && !r.contains("→ 0.00"))
        );
    }
    #[::core::prelude::v1::test]
    fn missing_target_or_rate_and_mixed_pids_are_not_comparable() {
        let mut s = session("perfetto");
        let mut frames = samples(MARKER);
        let capture = |s: &FrameCaptureSession, frames: &[aps_frame::FrameSample]| {
            Run::capture(
                s,
                &aps_frame::FrameJankAnalyzer::new().analyze(frames),
                &"0".repeat(64),
            )
        };
        s.observed_refresh_rates_hz.clear();
        assert!(capture(&s, &frames).is_err());
        s.observed_refresh_rates_hz.push(60.);
        frames[0].process_id = Some(20);
        assert!(capture(&s, &frames).is_err());
        frames[0].process_id = Some(30);
        s.package_name = None;
        assert!(capture(&s, &frames).is_err());
    }
}
