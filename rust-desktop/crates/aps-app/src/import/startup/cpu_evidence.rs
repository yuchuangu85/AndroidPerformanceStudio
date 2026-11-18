//! Join owned, per-launch Perfetto evidence to the published live run, never an
//! imported path, device wall clock, previous PID, or a different iteration.
use super::*;
use crate::evidence::object;
use aps_ai::{AnalysisRequest, Evidence};
use aps_json::Value;
use aps_perfetto::StartupCpuEvidence::{self, Measured, Unavailable};
use aps_startup::StartupRun;

pub(super) fn analyze_runs(
    session: &StartupSession,
    traces: &BTreeMap<String, PathBuf>,
    cancel: &CancellationToken,
) -> Result<BTreeMap<String, StartupCpuEvidence>, UiText> {
    let mut result = BTreeMap::new();
    let tool = (!traces.is_empty()).then(|| crate::perfetto_tool::locate(cancel));
    for run in session.runs() {
        if cancel.is_cancelled() {
            return Err(TextKey::ImportUiImportCancelled.into());
        }
        let evidence = match (traces.get(&run.id), tool.as_ref(), run.package.as_deref()) {
            (Some(path), Some(Ok(tool)), Some(package)) => {
                let parsed = TraceSession::open(tool.clone(), path, cancel)
                    .and_then(|s| s.startup_cpu_hotspots(package, cancel));
                match parsed {
                    Ok(evidence) => verify_live_pid(run, evidence),
                    Err(error) => Unavailable(error.to_string()),
                }
            }
            (_, Some(Err(error)), _) => Unavailable(error.clone()),
            _ => Unavailable("No verified CPU trace was captured for this launch.".into()),
        };
        result.insert(run.id.clone(), evidence);
    }
    if cancel.is_cancelled() {
        return Err(TextKey::ImportUiImportCancelled.into());
    }
    Ok(result)
}

fn verify_live_pid(run: &StartupRun, evidence: StartupCpuEvidence) -> StartupCpuEvidence {
    if let Measured(window) = &evidence {
        // These fields are authored by the live runner's post-launch ps query,
        // scoped to the verified install UID. Cold launch invalidates the old PID.
        let matches = capture::is_live_run(run)
            && run.imported_fields.get("pidsAfter").is_some_and(|pids| {
                pids.split(',')
                    .any(|pid| pid.parse::<u32>() == Ok(window.pid))
            });
        if !matches {
            return Unavailable("Trace PID was not verified for this app after launch.".into());
        }
    }
    evidence
}

// Only compact identifier-like labels may leave the app after approval. Keep a
// local rank for redacted paths, very long names, and arbitrary trace strings.
fn symbol_for_ai(name: &str) -> Option<&str> {
    (!name.is_empty()
        && name.len() <= 160
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"_.$:<>,()[]*~&-".contains(&b)))
    .then_some(name)
}

impl ImportWorkspace {
    pub(in crate::import) fn startup_cpu_analysis_request(
        &self,
        session: &str,
        page: &PageData,
    ) -> Option<AnalysisRequest> {
        let id = self.selected_startup_run_id.as_deref()?;
        let run = page
            .ui
            .startup_page
            .as_ref()?
            .rows
            .iter()
            .find(|r| r.id == id)?;
        if !capture::is_live_run(run) {
            return None;
        }
        let evidence = self.startup_capture.cpu_hotspots.get(id)?;
        let cpu = match evidence {
            // Raw ADB diagnostics, paths, session keys or other error text are
            // local-only, including on partial / failed sampling.
            Unavailable(_) => object([("available", false.into())]),
            Measured(w) => {
                let top = w
                    .hotspots
                    .iter()
                    .take(12)
                    .enumerate()
                    .map(|(i, row)| {
                        object([
                            ("rank", (i + 1).into()),
                            ("symbol", symbol_for_ai(&row.symbol).into()),
                            ("selfSamples", row.samples.into()),
                        ])
                    })
                    .collect();
                let scheduling = w.scheduling.as_ref().map(|s| {
                    object([
                        ("runningNs", s.running_ns.into()),
                        ("runnableNs", s.runnable_ns.into()),
                        ("otherObservedNs", s.other_ns.into()),
                    ])
                });
                object([
                    ("available", true.into()),
                    ("ttidWindowNs", w.end_ns.abs_diff(w.start_ns).into()),
                    ("mainThreadScheduling", scheduling.into()),
                    ("mainThreadSamples", w.total_samples.into()),
                    (
                        "traceWideReportedLostRecords",
                        w.trace_perf_lost_records.into(),
                    ),
                    ("unresolvedSamples", w.unresolved_samples.into()),
                    ("hotspots", Value::Array(top)),
                ])
            }
        };
        let payload = object([
            ("iteration", run.iteration.into()),
            ("requestedMode", format!("{:?}", run.requested_mode).into()),
            ("observedMode", format!("{:?}", run.observed_mode).into()),
            ("platformTtidMs", run.ttid.milliseconds.into()),
            ("platformTtfdMs", run.ttfd.milliseconds.into()),
            ("cpu", cpu),
        ]);
        Some(AnalysisRequest {
            session_id: session.into(),
            run_id: format!("{session}-g{}-i{}", self.generation, run.iteration),
            generation: self.generation.max(1),
            profiler: "startup".into(),
            scope: format!("Selected live startup iteration {}; platform timings, main-thread schedule and up to 12 sampled leaf symbols/counts; no raw trace or device identifiers.", run.iteration),
            evidence: vec![Evidence {
                id: format!("startup-cpu-{}", run.iteration),
                kind: "startup-main-thread-cpu".into(),
                summary: "Launch to initial display. Scheduling durations cover observed intervals only; other is not a blocking cause. Leaf sample counts are not call counts, per-method durations or causal proof. Empty samples do not mean zero CPU cost. Reported trace-wide losses cannot be assigned to this thread.".into(),
                payload,
            }],
            candidates: Vec::new(),
            user_question: None,
            mcp: None,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_perfetto::{StartupCpuHotspot, StartupCpuScheduling, StartupCpuWindow};
    use aps_startup::StartupEvidenceOrigin;

    fn run() -> StartupRun {
        let s = StartupSession::parse_csv("iteration,runId,requestedType,observedType,displayedTimeMs,fullyDrawnTimeMs\n1,live-first,COLD,COLD,70,\n").unwrap();
        let mut r = s.runs()[0].clone();
        r.origin = StartupEvidenceOrigin::LiveCapture;
        r.package = Some("dev.aps.example".into());
        r.imported_fields.insert("pidsAfter".into(), "21,42".into());
        r
    }

    fn measured() -> StartupCpuEvidence {
        Measured(StartupCpuWindow {
            startup_id: 7,
            pid: 42,
            tid: 42,
            start_ns: 100,
            end_ns: 150,
            scheduling: Some(StartupCpuScheduling {
                running_ns: 20,
                runnable_ns: 12,
                other_ns: 7,
            }),
            total_samples: 8,
            trace_perf_lost_records: 3,
            unresolved_samples: 2,
            hotspots: vec![
                StartupCpuHotspot {
                    symbol: "App.init".into(),
                    samples: 4,
                },
                StartupCpuHotspot {
                    symbol: "/private/path/Secret.cpp".into(),
                    samples: 2,
                },
            ],
        })
    }

    #[::core::prelude::v1::test]
    fn cross_user_or_stale_pid_and_imported_pid_claim_do_not_authorize_a_window() {
        let mut r = run();
        assert_eq!(verify_live_pid(&r, measured()), measured());
        r.imported_fields
            .insert("pidsAfter".into(), "421,142".into());
        assert!(matches!(verify_live_pid(&r, measured()), Unavailable(_)));
        r.imported_fields.insert("pidsAfter".into(), "42".into());
        r.origin = StartupEvidenceOrigin::ImportedCsv;
        assert!(matches!(verify_live_pid(&r, measured()), Unavailable(_)));
    }

    #[::core::prelude::v1::test]
    fn owned_trace_belongs_to_one_live_iteration_and_cancel_does_not_publish() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        if !root
            .join("build/perfetto-tools")
            .join(if cfg!(windows) {
                "trace_processor_shell.exe"
            } else {
                "trace_processor_shell"
            })
            .is_file()
        {
            eprintln!("skipping owned trace integration: pinned Trace Processor not installed");
            return;
        }
        let mut first = run();
        first.package = Some("com.example.startup".into());
        first
            .imported_fields
            .insert("pidsAfter".into(), "30".into());
        let mut second = first.clone();
        second.id = "unrecorded".into();
        second.iteration = 2;
        let input_id = first.id.clone();
        let session = StartupSession::from_captured_runs(vec![first, second]).unwrap();
        let trace =
            root.join("rust-desktop/crates/aps-perfetto/tests/fixtures/startup-cpu.pftrace");
        let traces = BTreeMap::from([(input_id.clone(), trace)]);
        let cancel = CancellationToken::new();
        let analyzed = analyze_runs(&session, &traces, &cancel).unwrap();
        assert!(
            matches!(&analyzed[&input_id], Measured(w) if w.total_samples == 3 && w.pid == 30 && w.trace_perf_lost_records == 12)
        );
        assert!(matches!(&analyzed["unrecorded"], Unavailable(_)));
        cancel.cancel();
        assert!(analyze_runs(&session, &traces, &cancel).is_err());
    }

    #[::core::prelude::v1::test]
    fn selected_run_ai_reports_sample_units_and_omits_paths_and_other_iterations() {
        let r = run();
        let mut second = r.clone();
        second.id = "another".into();
        second.iteration = 2;
        let session = StartupSession::from_captured_runs(vec![r.clone(), second]).unwrap();
        let mut workspace = ImportWorkspace::new(Destination::Startup, Language::English);
        workspace.session_id = Some("session1".into());
        workspace.page = Some(
            load_page(
                &session,
                &StartupQuery::default(),
                &CancellationToken::new(),
            )
            .unwrap(),
        );
        workspace
            .startup_capture
            .cpu_hotspots
            .insert(r.id.clone(), measured());
        workspace.startup_capture.cpu_hotspots.insert(
            "another".into(),
            Unavailable("secret raw error /private/path".into()),
        );
        workspace.selected_startup_run_id = Some(r.id);
        let request = workspace.analysis_request().unwrap();
        assert_eq!(request.evidence.len(), 1);
        assert_eq!(request.evidence[0].kind, "startup-main-thread-cpu");
        let p = &request.evidence[0].payload;
        assert_eq!(p["cpu"]["hotspots"][0]["symbol"], "App.init");
        assert_eq!(p["cpu"]["hotspots"][0]["selfSamples"], 4);
        assert!(p["cpu"]["hotspots"][1]["symbol"].is_null());
        assert_eq!(p["cpu"]["mainThreadSamples"], 8);
        assert_eq!(p["cpu"]["traceWideReportedLostRecords"], 3);
        assert_eq!(p["cpu"]["unresolvedSamples"], 2);
        assert!(!p.to_string().contains("private"));
        workspace.selected_startup_run_id = Some("another".into());
        let second = workspace.analysis_request().unwrap();
        assert_ne!(second.run_id, request.run_id);
        assert_eq!(second.evidence[0].payload["cpu"]["available"], false);
        assert_eq!(second.evidence[0].payload["iteration"], 2);
        assert!(!second.evidence[0].payload.to_string().contains("secret"));
        workspace.startup_capture.clear_published();
        assert_eq!(
            workspace.analysis_request().unwrap().evidence[0].kind,
            "profiler-page"
        );
    }
}
