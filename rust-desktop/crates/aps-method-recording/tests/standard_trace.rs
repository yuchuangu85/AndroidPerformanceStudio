use aps_method_recording::*;

// Writer-derived fixtures, not device captures: AOSP ART runtime/trace.cc at
// de0d0de0f4, Trace::FinishTracing / GetMethodLine / LogMethodTraceEvent.
const TABLES: &str = "*threads\n10\tmain\n42\tworker\n*methods\n0x0\texample.Main\trun\t()V\tMain.java\n0x10\texample.Main\tchild\t(I)V\t\n*end\n";

fn summary(version: u16, clock: &str, tables: &str) -> String {
    format!("*version\n{version}\ndata-file-overflow=false\nclock={clock}\nelapsed-time-usec=999999\nnum-method-calls=999\nclock-call-overhead-nsec=42\nvm=art\npid=123\n{tables}")
}

// Each record is (u16 thread, u32 method/action, u32 first clock, u32 wall).
// In single-clock v2 the first clock is whichever clock the summary declares;
// v3 writes CPU first, then wall. Header/event values are microseconds.
fn fixture(text: &str, version: u16, records: &[(u16, u32, u32, u32)]) -> Vec<u8> {
    let mut bytes = text.as_bytes().to_vec();
    let base = bytes.len();
    bytes.extend(b"SLOW");
    bytes.extend(version.to_le_bytes());
    bytes.extend(32u16.to_le_bytes());
    bytes.extend(1_234_567u64.to_le_bytes());
    if version == 3 {
        bytes.extend(14u16.to_le_bytes());
    }
    bytes.resize(base + 32, 0);
    for &(thread, word, first, wall) in records {
        bytes.extend(thread.to_le_bytes());
        bytes.extend(word.to_le_bytes());
        bytes.extend(first.to_le_bytes());
        if version == 3 {
            bytes.extend(wall.to_le_bytes());
        }
    }
    bytes
}

fn stats(session: &MethodRecordingSession, thread: Option<u32>, id: u64) -> MethodStatistics {
    session
        .top_methods_page(thread, 0, 512)
        .unwrap()
        .rows
        .into_iter()
        .find(|row| row.method_id == id)
        .unwrap()
}

#[test]
fn standard_v2_single_wall_and_thread_cpu_clocks_are_explicit() {
    for (name, clock) in [
        ("wall", ClockSource::Wall),
        ("thread-cpu", ClockSource::ThreadCpu),
    ] {
        let text = summary(2, name, TABLES);
        let bytes = fixture(&text, 2, &[(10, 0, 100, 0), (10, 1, 140, 0)]);
        let session = MethodRecordingSession::parse(&bytes).unwrap();
        assert_eq!(session.header().version, 2);
        assert_eq!(session.header().clock_source, clock);
        assert_eq!(session.header().start_time_nanos, 1_234_567_000);
        let events = session.events_page(None, 0, 512).unwrap().rows;
        assert_eq!(events[0].source_offset, text.len() + 32);
        assert_eq!((events[0].method_id, events[0].thread_id), (0, 10));
        assert_eq!(events[0].time_nanos, 100_000);
        assert_eq!(events[1].time_nanos, 140_000);
        assert_eq!(events[0].cpu_nanos, None);
        assert_eq!(
            stats(&session, None, 0).self_time,
            DurationTotals {
                primary_nanos: 40_000,
                cpu_nanos: None,
            }
        );
        assert_eq!(stats(&session, None, 0).completed_calls, 1);
        assert_eq!(session.summary().event_count, 2); // Not num-method-calls=999.
        assert!(session.diagnostics().is_empty());
    }
}

#[test]
fn standard_v3_dual_clock_nested_replay_keeps_cpu_wall_and_unroll_separate() {
    let text = summary(3, "dual", TABLES);
    let records = [
        (10, 0, 10, 100),
        (10, 16, 12, 110),
        (10, 18, 20, 130),
        (10, 1, 25, 150),
    ];
    let session = MethodRecordingSession::parse(&fixture(&text, 3, &records)).unwrap();
    assert_eq!(session.header().clock_source, ClockSource::WallAndThreadCpu);
    let events = session.events_page(None, 0, 512).unwrap().rows;
    assert_eq!(
        (
            events[1].method_id,
            events[1].time_nanos,
            events[1].cpu_nanos
        ),
        (4, 110_000, Some(12_000))
    );
    assert_eq!(events[2].action, Action::Unroll);
    let parent = stats(&session, Some(10), 0);
    let child = stats(&session, Some(10), 4);
    assert_eq!(
        parent.self_time,
        DurationTotals {
            primary_nanos: 30_000,
            cpu_nanos: Some(7_000)
        }
    );
    assert_eq!(
        parent.inclusive_time,
        DurationTotals {
            primary_nanos: 50_000,
            cpu_nanos: Some(15_000)
        }
    );
    assert_eq!(
        child.self_time,
        DurationTotals {
            primary_nanos: 20_000,
            cpu_nanos: Some(8_000)
        }
    );
    assert_eq!(child.unrolled_calls, 1);
    assert_eq!(
        session
            .timeline_page(TimelineQuery::default())
            .unwrap()
            .total,
        3
    );
    assert!(session.diagnostics().is_empty());
}

#[test]
fn standard_catalogs_use_decimal_threads_hex_methods_and_keep_empty_fields() {
    let tables = "*threads\n10\t main *end name \n42\tworker\n70000\tidle\n*methods\n10\texample.Main\trun\t()V\t\n0XABC0\texample.Other\tchild\t(I)V\tOther.java\n*end\n";
    let text = summary(2, "wall", tables);
    let session =
        MethodRecordingSession::parse(&fixture(&text, 2, &[(10, 16, 0, 0), (10, 17, 5, 0)]))
            .unwrap();
    assert_eq!(session.thread(10).unwrap().name, " main *end name ");
    assert!(session.thread(16).is_none());
    assert_eq!(session.thread(70000).unwrap().event_count, 0);
    assert_eq!(session.method(4).unwrap().source_file, "");
    assert_eq!(
        session.method(4).unwrap().display_name(),
        "example.Main.run"
    );
    assert!(session.method(0xabc0 >> 2).unwrap().declared);
    assert_eq!(session.summary().event_count, 2);
    assert!(session.diagnostics().is_empty());
}

#[test]
fn standard_empty_catalogs_keep_unresolved_events_and_diagnostics() {
    let text = summary(2, "wall", "*threads\n*methods\n*end\n");
    let session = MethodRecordingSession::parse(&fixture(
        &text,
        2,
        &[(42, 0xffff_fffc, 1, 0), (42, 0xffff_fffd, 2, 0)],
    ))
    .unwrap();
    assert_eq!(
        session.method(0x3fff_ffff).unwrap().display_name(),
        "0x3fffffff"
    );
    assert!(!session.thread(42).unwrap().declared);
    assert!(session
        .diagnostics()
        .has(DiagnosticKind::UncataloguedThread));
    assert!(session
        .diagnostics()
        .has(DiagnosticKind::UncataloguedMethod));
    assert_eq!(stats(&session, None, 0x3fff_ffff).completed_calls, 1);
}

#[test]
fn standard_duplicates_overflow_reserved_actions_and_partial_records_stay_visible() {
    let tables = "*threads\n10\told\n10\tnew\n*methods\n0x0\tMain\told\t()V\tMain.java\n0x0\tMain\tnew\t()V\tMain.java\n*end\n";
    let text = summary(2, "wall", tables).replace("overflow=false", "overflow=true");
    let mut bytes = fixture(&text, 2, &[(10, 3, 0, 0)]);
    bytes.extend([1, 2, 3]);
    let session = MethodRecordingSession::import_bytes(
        &bytes,
        ImportLimits {
            max_diagnostics: 1,
            ..Default::default()
        },
    )
    .unwrap();
    for kind in [
        DiagnosticKind::DuplicateThread,
        DiagnosticKind::DuplicateMethod,
        DiagnosticKind::CaptureOverflow,
        DiagnosticKind::UnusedAction,
        DiagnosticKind::TrailingBytes,
    ] {
        assert!(session.diagnostics().has(kind), "{kind:?}");
    }
    assert_eq!(session.diagnostics().entries.len(), 1);
    assert_eq!(session.diagnostics().omitted_entries, 4);
    assert_eq!(session.thread(10).unwrap().name, "new");
    assert_eq!(session.method(0).unwrap().method_name, "new");
    assert_eq!(session.summary().event_count, 0);
}

#[test]
fn standard_data_offset_is_relative_to_binary_header_and_skips_only_header_padding() {
    let text = summary(3, "dual", TABLES);
    let mut bytes = fixture(&text, 3, &[(42, 0, 1, 10), (42, 1, 2, 20)]);
    let base = text.len();
    bytes[base + 6..base + 8].copy_from_slice(&40u16.to_le_bytes());
    bytes.splice(base + 32..base + 32, [0xff; 8]);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    let events = session.events_page(None, 0, 10).unwrap().rows;
    assert_eq!(events[0].source_offset, base + 40);
    assert_eq!(stats(&session, Some(42), 0).self_time.primary_nanos, 10_000);
    assert!(session.diagnostics().is_empty());
}

#[test]
fn standard_crlf_summary_and_binary_asterisk_thread_do_not_confuse_text_boundary() {
    let text = summary(2, "wall", TABLES).replace('\n', "\r\n");
    let bytes = fixture(&text, 2, &[(42, 0, 0, 0), (42, 1, 1, 0)]);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    assert_eq!(session.thread(42).unwrap().name, "worker");
    assert_eq!(session.summary().event_count, 2);
    assert!(session.diagnostics().is_empty());
}

#[test]
fn standard_replay_does_not_extend_frames_to_metadata_or_other_threads() {
    let text = summary(3, "dual", TABLES);
    let bytes = fixture(
        &text,
        3,
        &[
            (10, 0, 0, 10),
            (10, 16, 5, 20),
            (42, 0, 0, 1000),
            (42, 1, 10, 2000),
        ],
    );
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    let parent = stats(&session, Some(10), 0);
    let child = stats(&session, Some(10), 4);
    assert_eq!(parent.inclusive_time.primary_nanos, 10_000);
    assert_eq!(child.inclusive_time.primary_nanos, 0);
    assert_eq!((parent.incomplete_calls, child.incomplete_calls), (1, 1));
    assert_eq!(session.summary().maximum_event_time_nanos, Some(2_000_000));
}

#[test]
fn standard_clock_wraparound_is_reported_not_reconstructed() {
    let text = summary(3, "dual", TABLES);
    let bytes = fixture(&text, 3, &[(10, 0, u32::MAX, u32::MAX), (10, 1, 1, 1)]);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    assert!(session.diagnostics().has(DiagnosticKind::ClockRegression));
    assert_eq!(session.summary().observed_active_time.primary_nanos, 0);
    assert_eq!(stats(&session, None, 0).incomplete_calls, 1);
}

#[test]
fn standard_truncated_prefixes_before_end_of_binary_header_fail() {
    for (version, clock) in [(2, "wall"), (3, "dual")] {
        let text = summary(version, clock, TABLES);
        let bytes = fixture(&text, version, &[]);
        for length in 0..bytes.len() {
            assert!(
                MethodRecordingSession::parse(&bytes[..length]).is_err(),
                "prefix {version}/{length}"
            );
        }
        let session = MethodRecordingSession::parse(&bytes).unwrap();
        assert_eq!(session.summary().event_count, 0);
        assert_eq!(session.summary().minimum_event_time_nanos, None);
    }
}

#[test]
fn standard_missing_end_invalid_utf8_and_non_line_terminators_fail() {
    let text = summary(2, "wall", TABLES);
    for malformed in [
        text.replace("*end\n", ""),
        text.replace("*end\n", "name*end\n"),
        text.replace("*end\n", "*end"),
        text.replace("*end\n", "*end\textra\n"),
    ] {
        assert_eq!(
            MethodRecordingSession::parse(&fixture(&malformed, 2, &[]))
                .unwrap_err()
                .kind,
            ErrorKind::Format
        );
    }
    let mut bytes = fixture(&text, 2, &[]);
    bytes[text.find("main").unwrap()] = 0xff;
    assert_eq!(
        MethodRecordingSession::parse(&bytes).unwrap_err().kind,
        ErrorKind::Format
    );
}

#[test]
fn standard_bad_magic_versions_clocks_sizes_offsets_and_overflow_fail_closed() {
    let text = summary(3, "dual", TABLES);
    let base = text.len();
    let bytes = fixture(&text, 3, &[]);
    for (range, replacement, kind) in [
        (base..base + 4, b"NOPE".to_vec(), ErrorKind::Format),
        (
            base + 4..base + 6,
            4u16.to_le_bytes().to_vec(),
            ErrorKind::UnsupportedVersion,
        ),
        (
            base + 6..base + 8,
            31u16.to_le_bytes().to_vec(),
            ErrorKind::Format,
        ),
        (
            base + 6..base + 8,
            u16::MAX.to_le_bytes().to_vec(),
            ErrorKind::Format,
        ),
        (
            base + 16..base + 18,
            10u16.to_le_bytes().to_vec(),
            ErrorKind::Format,
        ),
        (
            base + 16..base + 18,
            0u16.to_le_bytes().to_vec(),
            ErrorKind::Format,
        ),
        (
            base + 8..base + 16,
            u64::MAX.to_le_bytes().to_vec(),
            ErrorKind::ArithmeticOverflow,
        ),
    ] {
        let mut invalid = bytes.clone();
        let offset = range.start;
        invalid[range].copy_from_slice(&replacement);
        let error = MethodRecordingSession::parse(&invalid).unwrap_err();
        assert_eq!(error.kind, kind);
        assert_eq!(error.offset, Some(offset));
    }
    for (invalid, expected) in [
        (text.replacen("\n3\n", "\n2\n", 1), ErrorKind::Format),
        (
            text.replacen("\n3\n", "\n1\n", 1),
            ErrorKind::UnsupportedVersion,
        ),
        (text.replacen("\n3\n", "\n?\n", 1), ErrorKind::Format),
        (text.replace("clock=dual\n", ""), ErrorKind::Format),
        (text.replace("clock=dual", "clock=wall"), ErrorKind::Format),
        (
            text.replace("clock=dual", "clock=thread-cpu"),
            ErrorKind::Format,
        ),
        (
            text.replace("clock=dual", "clock=unknown"),
            ErrorKind::Format,
        ),
        (
            text.replace("clock=dual", "clock=dual\nclock=dual"),
            ErrorKind::Format,
        ),
        (
            text.replace("clock=dual", "malformed option"),
            ErrorKind::Format,
        ),
        (text.replace("*threads\n", "*other\n"), ErrorKind::Format),
        (text.replace("*methods\n", ""), ErrorKind::Format),
    ] {
        assert_eq!(
            MethodRecordingSession::parse(&fixture(&invalid, 3, &[]))
                .unwrap_err()
                .kind,
            expected,
            "{invalid}"
        );
    }
    assert_eq!(
        MethodRecordingSession::parse(&fixture(&summary(2, "dual", TABLES), 2, &[]))
            .unwrap_err()
            .kind,
        ErrorKind::Format
    );
}

#[test]
fn standard_malformed_catalogs_do_not_invent_ids_or_shift_fields() {
    let text = summary(2, "wall", TABLES);
    for (from, to) in [
        ("10\tmain", "0x10\tmain"),
        ("10\tmain", "4294967296\tmain"),
        ("10\tmain", "10 main"),
        ("0x10\texample", "0x11\texample"),
        ("0x10\texample", "0x100000000\texample"),
        ("0x10\texample", "bad-id\texample"),
        ("\tchild\t(I)V\t", "\t(I)V\t"),
        ("\tchild\t(I)V\t", "\tchild\tinvalid\t"),
        ("\tchild\t(I)V\t", "\t\t(I)V\t"),
        ("Main.java\n", "Main.java\textra\n"),
    ] {
        let invalid = text.replace(from, to);
        assert_eq!(
            MethodRecordingSession::parse(&fixture(&invalid, 2, &[]))
                .unwrap_err()
                .kind,
            ErrorKind::Format,
            "{invalid}"
        );
    }
}

#[test]
fn standard_limits_cover_full_summary_catalogs_events_and_replay() {
    let text = summary(3, "dual", TABLES);
    let bytes = fixture(&text, 3, &[(10, 0, 1, 10), (10, 16, 2, 20)]);
    let longest_line = text.lines().map(str::len).max().unwrap();
    for limits in [
        ImportLimits {
            max_bytes: bytes.len() as u64 - 1,
            ..Default::default()
        },
        ImportLimits {
            max_table_bytes: text.len() - 1,
            ..Default::default()
        },
        ImportLimits {
            max_string_bytes: longest_line - 1,
            ..Default::default()
        },
        ImportLimits {
            max_methods: 1,
            ..Default::default()
        },
        ImportLimits {
            max_threads: 1,
            ..Default::default()
        },
        ImportLimits {
            max_events: 1,
            ..Default::default()
        },
        ImportLimits {
            max_stack_depth: 1,
            ..Default::default()
        },
        ImportLimits {
            max_call_tree_nodes: 2,
            ..Default::default()
        },
    ] {
        assert_eq!(
            MethodRecordingSession::import_bytes(&bytes, limits)
                .unwrap_err()
                .kind,
            ErrorKind::Limit,
            "{limits:?}"
        );
    }
    let session = MethodRecordingSession::import_bytes(
        &bytes,
        ImportLimits {
            max_table_bytes: text.len(),
            max_string_bytes: longest_line,
            max_events: 2,
            ..Default::default()
        },
    )
    .unwrap();
    assert_eq!(session.summary().event_count, 2);
    let reserved = fixture(&text, 3, &[(10, 3, 0, 0), (10, 3, 0, 0)]);
    assert_eq!(
        MethodRecordingSession::import_bytes(
            &reserved,
            ImportLimits {
                max_events: 1,
                ..Default::default()
            }
        )
        .unwrap_err()
        .kind,
        ErrorKind::Limit
    );
}
