//! Proposal only. The provider supplies line text, not a path, command or diff.
//! Local source, measurement, approval and citation checks precede review.
use super::{FrameWorkspace, probe_receipt, trace_patch};
use crate::source::{LocalProbeSource, ProbeRegistry};
use aps_ai::{AnalysisRequest, AnalysisResult, SourceCandidate};
use aps_json::{Map, Value};
use std::collections::BTreeSet;
use std::path::PathBuf;
pub(crate) struct OptimizeJob {
    root: PathBuf,
    package: String,
    marker: String,
    request: AnalysisRequest,
}
#[derive(Clone)]
pub(crate) struct Optimization {
    source: LocalProbeSource,
    receipt: probe_receipt::Receipt,
    evidence: String,
    candidate: String,
}
pub(crate) struct ReviewDiff {
    pub diff: String,
    pub label: String,
}
impl gpui_kit::EventEmitter<ReviewDiff> for crate::ai::AiWorkspace {}
impl FrameWorkspace {
    pub(crate) fn optimization_job(&self, marker: &str) -> Result<OptimizeJob, String> {
        let session = self.current_session.as_ref().ok_or("No current capture.")?;
        if self.is_loading
            || self.is_capturing
            || self
                .artifact
                .as_ref()
                .is_none_or(|art| Some(&art.id) != session.artifact.as_ref())
        {
            return Err("Select a completed target capture.".into());
        }
        OptimizeJob::from_capture(
            self.data_dir
                .clone()
                .ok_or("Missing local frame directory.")?,
            session,
            self.analysis.as_deref().ok_or("No analysis.")?,
            self.selected_frame().ok_or("Select a problem frame.")?,
            marker,
        )
    }
}
impl OptimizeJob {
    pub(super) fn from_capture(
        root: PathBuf,
        session: &aps_frame::FrameCaptureSession,
        a: &aps_frame::FrameAnalysisResult,
        selected: &aps_frame::analysis::AnalyzedFrame,
        marker: &str,
    ) -> Result<Self, String> {
        let package = session
            .package_name
            .as_ref()
            .ok_or("A target-scoped recapture is required.")?;
        if !super::can_probe(selected)
            || session.source != aps_frame::FrameSource::Perfetto
            || session.imported_file.is_some()
            || !session.provenance_complete
            || selected.sample.package_name.as_ref() != Some(package)
            || !selected.sample.process_id.is_some_and(|pid| pid > 0)
            || selected.sample.states.contains_key("aps.trace.omitted")
            || selected.sample.session_id != session.id
            || !super::perfetto::probes(&selected.sample).iter().any(|p| {
                p.marker == marker && p.role != aps_perfetto::frame_probes::ThreadRole::Other
            })
        {
            return Err("Selected probe is not in the current frame.".into());
        }
        let mut ids = BTreeSet::new();
        let mut max_overlap = 0;
        for f in &a.frames {
            if f.sample.session_id != session.id
                || f.sample.package_name.as_ref() != Some(package)
                || f.sample.process_id != selected.sample.process_id
                || !super::can_probe(f)
                || f.sample.states.contains_key("aps.trace.omitted")
            {
                continue;
            }
            let Some(token) = f.sample.frame_timeline_vsync_id else {
                continue;
            };
            let longest = super::perfetto::probes(&f.sample)
                .into_iter()
                .filter(|p| {
                    p.marker == marker && p.role != aps_perfetto::frame_probes::ThreadRole::Other
                })
                .map(|p| p.overlap_ns)
                .max();
            if let Some(ns) = longest {
                ids.insert(token);
                max_overlap = max_overlap.max(ns);
            }
        }
        if ids.len() < 2 {
            return Err("Need the same probe on the main or render thread in at least two distinct measured problem frames.".into());
        }
        let mut request = super::ai::probe_plan(a, selected.sample.frame_id)?;
        let mut facts = Map::new();
        facts.insert("marker".into(), marker.into());
        facts.insert("matchedProblemDisplayFrames".into(), ids.len().into());
        facts.insert("maxObservedWindowOverlapNs".into(), max_overlap.into());
        let payload = Value::Object(facts);
        let binding = aps_source::digest_text(&format!("{}:{}", request.evidence[0].id, payload));
        request.evidence.push(aps_ai::Evidence{id:format!("frame-opt:{}",binding.to_hex()),kind:"frame-optimization".into(),summary:"Repeated same-process overlap on measured problem frames; a hypothesis, not proven causation.".into(),payload});
        Ok(OptimizeJob {
            root,
            package: package.clone(),
            marker: marker.into(),
            request,
        })
    }
}
impl OptimizeJob {
    pub(crate) fn prepare(
        mut self,
        registry: ProbeRegistry,
    ) -> Result<(AnalysisRequest, Optimization), String> {
        let receipt = probe_receipt::lookup(&self.root, &self.marker, &self.package)?;
        let source = registry.current(&receipt.location, receipt.instrumented)?;
        let text = source.read_for_ai()?;
        let l = &source.location;
        let r = trace_patch::edit_range(
            &l.relative_path,
            &text,
            receipt.line,
            &receipt.method,
            &receipt.marker,
        )?;
        let snippet = text
            .split_inclusive('\n')
            .skip(r.start)
            .take(r.len())
            .collect::<String>();
        if snippet.len() > 8 * 1024 || snippet.trim().is_empty() {
            return Err("Method exceeds the bounded source excerpt limit.".into());
        }
        let evidence = self
            .request
            .evidence
            .last()
            .ok_or("Missing repeated frame evidence.")?
            .id
            .clone();
        let id = source.bind_candidate(
            &evidence,
            aps_source::SourceRange {
                start_line: r.start + 1,
                end_line: r.end,
            },
        )?;
        self.request.candidates.push(SourceCandidate {
            id: id.clone(),
            workspace_id: l.workspace_id.to_hex(),
            snapshot_id: l.snapshot_id.to_hex(),
            content_digest: l.content_hash,
            relative_path: l.relative_path.clone(),
            start_line: Some(r.start as u32 + 1),
            end_line: Some(r.end as u32),
            snippet: Some(snippet),
        });
        self.request.scope=concat!("Review one verified local synchronous method excerpt for a potential frame optimization. Repeated overlap is not causation or proof of an APK/source mapping. ",
            "If justified, provide ONE finding citing both the frame-optimization evidence and the supplied source candidate. Use the required OLD/NEW/END line format in the recommendation string. ",
            "old and new must be complete source lines without a final newline, retaining leading indentation; old must occur exactly once in the supplied excerpt. Do not include paths, Markdown fences, imports, or shell commands. Preserve behavior and the probe. Explain the hypothesis and follow-up test separately. Otherwise return no finding. Nothing will be applied automatically.").into();
        Ok((
            self.request,
            Optimization {
                source,
                receipt,
                evidence,
                candidate: id,
            },
        ))
    }
}
impl Optimization {
    pub(crate) fn validate(&self) -> Result<(), String> {
        self.source.read_for_ai().map(|_| ())
    }
    pub(crate) fn review(&self, result: &AnalysisResult) -> Result<Option<ReviewDiff>, String> {
        let src = self.source.read_for_ai()?;
        if result.findings.len() > 1 {
            return Err("Multiple findings; request one bounded change.".into());
        }
        let mut eligible = result.findings.iter().filter(|f| {
            f.evidence_ids.contains(&self.evidence) && f.candidate_ids.contains(&self.candidate)
        });
        let Some(f) = eligible.next() else {
            return if result.findings.is_empty() {
                Ok(None)
            } else {
                Err("Proposal does not cite the exact source and repeated frame evidence.".into())
            };
        };
        let p = &self.receipt;
        let diff = validated_diff(
            &self.source.location.relative_path,
            &src,
            p.line,
            &p.method,
            &p.marker,
            &f.recommendation,
        )?;
        Ok(Some(ReviewDiff {
            diff,
            label: format!(
                "{}:{} · {} · {}",
                p.location.relative_path,
                p.line,
                p.marker,
                &p.instrumented.to_hex()[..12]
            ),
        }))
    }
}
fn validated_diff(
    path: &str,
    src: &str,
    line: usize,
    name: &str,
    marker: &str,
    recommendation: &str,
) -> Result<String, String> {
    let bad = || {
        "Invalid edit: require unique, complete lines within the verified instrumented method."
            .to_owned()
    };
    let (old, new) = aps_ai::frame_edit::parse(recommendation).ok_or_else(bad)?;
    if new.contains("APS.Frame/") {
        return Err(bad());
    }
    let region = trace_patch::edit_range(path, src, line, name, marker)?;
    let lines: Vec<_> = src.split_inclusive('\n').collect();
    let olds: Vec<_> = old.split('\n').collect();
    let news: Vec<_> = if new.is_empty() {
        vec![]
    } else {
        new.split('\n').collect()
    };
    let mut starts = lines[region.clone()]
        .windows(olds.len())
        .enumerate()
        .filter(|(_, ls)| {
            ls.iter()
                .zip(&olds)
                .all(|(a, b)| a.trim_end_matches(['\r', '\n']) == *b)
        });
    let i = region.start + starts.next().ok_or_else(bad)?.0;
    if starts.next().is_some() {
        return Err(bad());
    }
    let end = i + olds.len();
    let eol = if lines[i].ends_with("\r\n") {
        "\r\n"
    } else {
        "\n"
    };
    let mut changed = lines[..i].concat();
    for l in &news {
        changed.push_str(l);
        changed.push_str(eol);
    }
    changed.push_str(&lines[end..].concat());
    let after = trace_patch::edit_range(path, &changed, line, name, marker)?;
    if region.start != after.start || after.len() + olds.len() != region.len() + news.len() {
        return Err(bad());
    }
    // Include a surrounding context line on both sides, even at excerpt boundaries.
    let mut diff = format!(
        "diff --git a/{path} b/{path}\n--- a/{path}\n+++ b/{path}\n@@ -{i},{} +{i},{} @@\n",
        olds.len() + 2,
        news.len() + 2
    );
    diff.push(' ');
    diff.push_str(lines[i - 1]);
    for l in &lines[i..end] {
        diff.push('-');
        diff.push_str(l);
    }
    for l in news {
        diff.push('+');
        diff.push_str(l);
        diff.push_str(eol);
    }
    diff.push(' ');
    diff.push_str(lines[end]);
    Ok(diff)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::frame::probe_tests::{samples, session};
    const M: &str = "APS.Frame/0000000000000001";
    #[test]
    fn repeated_layer_or_other_thread_is_not_independent_evidence() {
        let s = session("perfetto");
        let mut frames = samples(M);
        for f in &mut frames {
            f.frame_timeline_vsync_id = Some(1);
        }
        let job = |frames: &[aps_frame::FrameSample]| {
            let a = aps_frame::FrameJankAnalyzer::new().analyze(frames);
            OptimizeJob::from_capture(PathBuf::new(), &s, &a, &a.frames[0], M)
        };
        assert!(job(&frames).is_err());
        frames[1].frame_timeline_vsync_id = Some(2);
        frames[1]
            .states
            .insert("aps.trace.0".into(), format!("{M} 32 5 -1 Other"));
        assert!(job(&frames).is_err());
        frames[1]
            .states
            .insert("aps.trace.0".into(), format!("{M} 32 5 -1 RenderThread"));
        frames[2]
            .states
            .insert("aps.trace.0".into(), format!("{M} 30 15 -1 Main"));
        let j = job(&frames).unwrap();
        assert_eq!(
            j.request.evidence.last().unwrap().payload["maxObservedWindowOverlapNs"].as_u64(),
            Some(15)
        );
        frames[1]
            .states
            .insert("aps.trace.omitted".into(), "9".into());
        assert!(job(&frames).is_err());
    }
    #[test]
    fn proposed_edit_cannot_escape_try_or_select_ambiguous_or_outside_lines() {
        let src = format!(
            "class X {{\n    void draw() {{\n        android.os.Trace.beginSection(\"{M}\");\n        try {{\n            paint();\n            paint();\n            redraw();\n        }} finally {{\n            android.os.Trace.endSection();\n        }}\n    }}\n}}\n"
        );
        let propose = |old: &str, new: &str| {
            validated_diff(
                "X.java",
                &src,
                2,
                "draw",
                M,
                &aps_ai::frame_edit::example(old, new),
            )
        };
        assert!(propose("            paint();", "").is_err());
        assert!(propose("    void draw() {", "").is_err());
        assert!(
            propose(
                "            redraw();",
                "        } finally {}\n        try {"
            )
            .is_err()
        );
        assert!(propose("            redraw();", "            drawChanged();").is_ok());
        assert!(propose("            redraw();", "[[[").is_err());
    }
}
