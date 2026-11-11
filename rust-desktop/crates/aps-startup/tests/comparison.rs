use aps_startup::*;
use std::collections::BTreeMap;

fn report(
    samples: &[u64],
    edit: impl Fn(usize, &mut StartupRun, &mut RunEvidence),
) -> StartupSession {
    // Compose exports the TTFD column even when the metric is unavailable.
    let mut csv =
        "iteration,runId,requestedType,observedType,displayedTimeMs,fullyDrawnTimeMs\n".to_owned();
    for (i, value) in samples.iter().enumerate() {
        csv.push_str(&format!("{},run-{i},COLD,COLD,{value},\n", i + 1));
    }
    let imported = StartupSession::parse_csv(&csv).unwrap();
    let mut runs = imported.runs().to_vec();
    let mut metadata = BTreeMap::new();
    for (index, run) in runs.iter_mut().enumerate() {
        run.origin = StartupEvidenceOrigin::ImportedJson;
        let mut evidence = RunEvidence {
            context: Some(RunContext {
                device_local_id: "a".repeat(64),
                package_name: "dev.contract".into(),
                component_name: "dev.contract/.MainActivity".into(),
            }),
            compilation: Some(CompilationEvidence {
                requested_mode: "CURRENT".into(),
                compiler_filter_after: Some("speed".into()),
                profile_state_after: Some("present".into()),
                verified: true,
                profile_source: "UNVERIFIED".into(),
                profile_source_declared: false,
            }),
            environment: Some(EnvironmentEvidence {
                device_model: Some("Contract Device".into()),
                api_level: Some(35),
                emulator: Some(false),
                battery_percent: Some(80),
                charging: Some(false),
                thermal_status: Some(0),
                captured_at: Some("2026-11-11T00:00:00Z".into()),
                failures: vec![],
            }),
            ttid: MetricEvidence {
                source: Some("EVENT_LOG".into()),
                confidence: Confidence::Exact,
                unavailable_reason: None,
            },
            ttfd: MetricEvidence::default(),
            milestones: vec![
                Milestone {
                    kind: "ACTIVITY_PRE_CREATE".into(),
                    elapsed_realtime_ns: Some(1_000_000),
                    source: "AGENT".into(),
                    confidence: Confidence::Exact,
                },
                Milestone {
                    kind: "ACTIVITY_CREATED".into(),
                    elapsed_realtime_ns: Some(1_000_000 + samples[index] * 100_000),
                    source: "AGENT".into(),
                    confidence: Confidence::Estimated,
                },
            ],
        };
        edit(index, run, &mut evidence);
        metadata.insert(run.id.clone(), evidence);
    }
    StartupSession::from_report(runs, metadata, vec![]).unwrap()
}

#[test]
fn same_inputs_match_actual_compose_analyzer_status_bca_interval_and_phase() {
    let inputs = include_str!("fixtures/comparison-cases.csv");
    let expected = include_str!("fixtures/compose-oracle/expected.csv");
    for (input, expected) in inputs.lines().skip(1).zip(expected.lines().skip(1)) {
        let input: Vec<_> = input.split(';').collect();
        let expected: Vec<_> = expected.split(';').collect();
        assert_eq!(input[0], expected[0]);
        let samples = |s: &str| {
            s.split(':')
                .map(|s| s.parse::<u64>().unwrap())
                .collect::<Vec<_>>()
        };
        let current = report(&samples(input[1]), |_, _, _| {});
        let baseline = report(&samples(input[2]), |_, _, _| {});
        let comparison = current.compare(&baseline, 5.).unwrap();
        let status = match comparison.status {
            ComparisonStatus::Regression => "REGRESSION",
            ComparisonStatus::Improvement => "IMPROVEMENT",
            ComparisonStatus::NoChange => "NO_CHANGE",
            ComparisonStatus::Inconclusive => "INCONCLUSIVE",
            ComparisonStatus::Insufficient => "INSUFFICIENT",
            ComparisonStatus::Incompatible => "INCOMPATIBLE",
        };
        assert_eq!(status, expected[1], "{}", input[0]);
        let interval = comparison.confidence_interval_ms;
        let actual = [
            comparison.median_difference_ms,
            comparison.percent_difference,
            interval.map(|i| i.0),
            interval.map(|i| i.1),
            comparison.practical_threshold_ms,
            comparison
                .phase_differences
                .first()
                .map(|p| p.median_difference_ms),
        ];
        for (actual, expected) in actual.into_iter().zip(&expected[2..]) {
            if *expected == "null" {
                assert_eq!(actual, None);
            } else {
                assert!(
                    (actual.unwrap() - expected.parse::<f64>().unwrap()).abs() < 1e-9,
                    "{}: {actual:?} != {expected}",
                    input[0]
                );
            }
        }
        assert_eq!(comparison, current.compare(&baseline, 5.).unwrap());
    }
}

#[test]
fn missing_and_unknown_evidence_fail_closed_before_statistical_decisions() {
    let baseline = report(&[100; 5], |_, _, _| {});
    let mutations: Vec<Box<dyn Fn(usize, &mut StartupRun, &mut RunEvidence)>> = vec![
        Box::new(|_, _, e| e.context = None),
        Box::new(|_, r, _| r.observed_mode = StartupMode::Unknown),
        Box::new(|_, _, e| e.compilation = None),
        Box::new(|_, _, e| e.compilation.as_mut().unwrap().verified = false),
        Box::new(|_, _, e| e.compilation.as_mut().unwrap().compiler_filter_after = None),
        Box::new(|_, _, e| e.compilation.as_mut().unwrap().profile_state_after = None),
        Box::new(|_, _, e| {
            let c = e.compilation.as_mut().unwrap();
            c.requested_mode = "SPEED_PROFILE".into();
            c.profile_source_declared = false;
        }),
        Box::new(|_, _, e| e.environment = None),
        Box::new(|_, _, e| e.environment.as_mut().unwrap().emulator = Some(true)),
        Box::new(|_, _, e| e.environment.as_mut().unwrap().battery_percent = Some(14)),
        Box::new(|_, _, e| e.environment.as_mut().unwrap().thermal_status = Some(3)),
        Box::new(|_, _, e| {
            e.environment
                .as_mut()
                .unwrap()
                .failures
                .push("capture failed".into())
        }),
        Box::new(|_, _, e| e.environment.as_mut().unwrap().captured_at = None),
        Box::new(|_, r, _| r.ttid.milliseconds = None),
        Box::new(|_, _, e| e.ttid.source = Some("UNKNOWN".into())),
        Box::new(|_, _, e| e.ttid.confidence = Confidence::Unavailable),
        Box::new(|i, _, e| {
            if i == 4 {
                e.ttid.confidence = Confidence::Estimated;
            }
        }),
    ];
    for mutate in mutations {
        let current = report(&[130; 5], mutate);
        let result = current.compare(&baseline, 5.).unwrap();
        assert_eq!(result.status, ComparisonStatus::Incompatible);
        assert!(!result.reasons.is_empty());
        assert!(result.confidence_interval_ms.is_none());
        assert!(result.median_difference_ms.is_none());
        assert!(result.phase_differences.is_empty());
    }
}

#[test]
fn reason_order_matches_compose_and_csv_cannot_promote_declarations() {
    let baseline = report(&[100; 3], |_, _, _| {});
    let current = report(&[130; 3], |_, r, e| {
        e.context = None;
        r.observed_mode = StartupMode::Unknown;
        e.compilation = None;
        e.environment = None;
        r.ttid.milliseconds = None;
        e.ttid = MetricEvidence::default();
    });
    assert_eq!(
        current.compare(&baseline, 5.).unwrap().reasons,
        vec![
            "Device or target identity differs or is missing.",
            "Requested or observed startup mode differs.",
            "Verified startup compilation state differs or is missing.",
            "Startup environment identity differs or is missing.",
            "TTID availability differs or is incomplete.",
            "TTID evidence source or confidence differs or is unavailable."
        ]
    );
    let csv = StartupSession::parse_csv("iteration,runId,requestedType,observedType,displayedTimeMs,fullyDrawnTimeMs,compilationVerified\n1,one,COLD,COLD,100,,true\n").unwrap();
    assert_eq!(
        csv.compare(&baseline, 5.).unwrap().status,
        ComparisonStatus::Incompatible
    );
}

#[test]
fn csv_requires_ttfd_column_but_empty_cell_preserves_unavailable_evidence() {
    assert!(matches!(
        StartupSession::parse_csv("iteration,runId,requestedType,observedType,displayedTimeMs\n1,one,COLD,COLD,100\n"),
        Err(error) if error == "Startup CSV lacks fullyDrawnTimeMs"
    ));
    let csv = StartupSession::parse_csv("iteration,runId,requestedType,observedType,displayedTimeMs,fullyDrawnTimeMs\n1,one,COLD,COLD,100,\n").unwrap();
    let run = &csv.runs()[0];
    assert_eq!(run.ttid.milliseconds, Some(100));
    assert_eq!(run.ttid.source, Some(MetricSource::ImportedCsv));
    assert_eq!(run.ttfd.milliseconds, None);
    assert_eq!(run.ttfd.source, None);
    assert!(run.ttfd.unavailable_reason.is_some());
    assert_eq!(
        run.imported_fields
            .get("fullyDrawnTimeMs")
            .map(String::as_str),
        Some("")
    );
    assert_eq!(csv.summary().ttfd.count, 0);
    assert_eq!(csv.summary().ttfd.missing, 1);
}

#[test]
fn phase_comparison_requires_every_run_and_same_source_endpoints() {
    let baseline = report(&[100; 3], |_, _, _| {});
    for mutate in [0, 1, 2] {
        let current = report(&[130; 3], |i, _, e| {
            if i == 2 {
                match mutate {
                    0 => {
                        e.milestones.pop();
                    }
                    1 => e.milestones[1].source = "PERFETTO".into(),
                    _ => e.milestones[1].confidence = Confidence::Unavailable,
                }
            }
        });
        let comparison = current.compare(&baseline, 5.).unwrap();
        assert_eq!(comparison.status, ComparisonStatus::Regression);
        assert!(comparison.phase_differences.is_empty());
    }
}

#[test]
fn thresholds_are_bounded_and_affect_interval_classification() {
    let baseline = report(&[100; 3], |_, _, _| {});
    let current = report(&[130; 3], |_, _, _| {});
    for invalid in [-1., 101., f64::NAN, f64::INFINITY] {
        assert!(current.compare(&baseline, invalid).is_err());
    }
    assert_eq!(
        current.compare(&baseline, 30.).unwrap().status,
        ComparisonStatus::NoChange
    );
    assert_eq!(
        current.compare(&baseline, 0.).unwrap().status,
        ComparisonStatus::Regression
    );
}
