use aps_method_recording::*;

fn u16(bytes: &mut Vec<u8>, value: u16) {
    bytes.extend(value.to_le_bytes());
}
fn u32(bytes: &mut Vec<u8>, value: u32) {
    bytes.extend(value.to_le_bytes());
}
fn u64(bytes: &mut Vec<u8>, value: u64) {
    bytes.extend(value.to_le_bytes());
}
fn uleb(bytes: &mut Vec<u8>, mut value: u64) {
    loop {
        let mut byte = (value & 0x7f) as u8;
        value >>= 7;
        if value != 0 {
            byte |= 0x80;
        }
        bytes.push(byte);
        if value == 0 {
            break;
        }
    }
}
fn sleb(bytes: &mut Vec<u8>, mut value: i64) {
    loop {
        let mut byte = (value & 0x7f) as u8;
        value >>= 7;
        let done = (value == 0 && byte & 0x40 == 0) || (value == -1 && byte & 0x40 != 0);
        if !done {
            byte |= 0x80;
        }
        bytes.push(byte);
        if done {
            break;
        }
    }
}
fn streaming(version: u16) -> Vec<u8> {
    let mut bytes = b"SLOW".to_vec();
    u16(&mut bytes, version);
    u64(&mut bytes, 1_000_000_000);
    bytes.resize(32, 0);
    bytes
}
fn thread(bytes: &mut Vec<u8>, id: u32, name: &str) {
    bytes.push(0);
    u32(bytes, id);
    u16(bytes, name.len() as u16);
    bytes.extend(name.as_bytes());
}
fn method(bytes: &mut Vec<u8>, id: u64, info: &str) {
    bytes.push(1);
    u64(bytes, id);
    u16(bytes, info.len() as u16);
    bytes.extend(info.as_bytes());
}
fn summary(bytes: &mut Vec<u8>, text: &str) {
    bytes.push(3);
    u16(bytes, text.len() as u16);
    bytes.extend(text.as_bytes());
}
/// Tuples are per-block deltas of (method word, primary ns, CPU ns).
fn block(bytes: &mut Vec<u8>, id: u32, entries: &[(i64, u64, u64)], dual: bool) {
    let mut payload = Vec::new();
    for &(word, primary, cpu) in entries {
        sleb(&mut payload, word);
        uleb(&mut payload, primary);
        if dual {
            uleb(&mut payload, cpu);
        }
    }
    raw_block(bytes, id, entries.len(), &payload);
}
fn raw_block(bytes: &mut Vec<u8>, id: u32, count: usize, payload: &[u8]) {
    bytes.push(2);
    u32(bytes, id);
    bytes.extend(&(count as u32).to_le_bytes()[..3]);
    u32(bytes, payload.len() as u32);
    bytes.extend(payload);
}
fn nested() -> Vec<u8> {
    let mut bytes = streaming(4);
    thread(&mut bytes, 1, "main");
    method(&mut bytes, 1, "example/Main\ta\t()V\tMain.java\n");
    method(&mut bytes, 2, "example/Main\tb\t()V\tMain.java\n");
    // A Enter 0, B Enter 10, B Exit 30, A Exit 40.
    block(
        &mut bytes,
        1,
        &[(4, 0, 0), (4, 10, 0), (1, 20, 0), (-4, 10, 0)],
        false,
    );
    bytes
}
fn stats(session: &MethodRecordingSession, thread: Option<u32>, method: u64) -> MethodStatistics {
    session
        .top_methods_page(thread, 0, 512)
        .unwrap()
        .rows
        .into_iter()
        .find(|row| row.method_id == method)
        .unwrap()
}
fn classic(version: u16, text: &str, records: &[(u16, u32, u32, u32)]) -> Vec<u8> {
    let mut bytes = b"SLOW".to_vec();
    u16(&mut bytes, version);
    u16(&mut bytes, 32);
    u64(&mut bytes, 1_000_000);
    u16(&mut bytes, if version == 3 { 14 } else { 10 });
    bytes.resize(32, 0);
    bytes.extend(text.as_bytes());
    for &(tid, word, cpu, wall) in records {
        u16(&mut bytes, tid);
        u32(&mut bytes, word);
        u32(&mut bytes, cpu);
        if version == 3 {
            u32(&mut bytes, wall);
        }
    }
    bytes
}

#[test]
fn streaming_v4_preserves_existing_fixture_event_words() {
    let mut bytes = streaming(4);
    thread(&mut bytes, 1, "main");
    method(&mut bytes, 1, "android/os/Looper\tloop\t()V\tLooper.java\n");
    block(&mut bytes, 1, &[(4, 1000, 0), (5, 1000, 0)], false);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    assert_eq!(session.header().start_time_nanos, 1_000_000_000);
    assert_eq!(
        session.header().clock_source,
        ClockSource::UnspecifiedSingle
    );
    assert_eq!(
        session.method(1).unwrap().display_name(),
        "android.os.Looper.loop"
    );
    let events = session.events_page(None, 0, 10).unwrap().rows;
    assert_eq!(
        (events[0].method_id, events[0].action, events[0].time_nanos),
        (1, Action::Enter, 1000)
    );
    assert_eq!(
        (events[1].method_id, events[1].action, events[1].time_nanos),
        (2, Action::Exit, 2000)
    );
    assert!(session.diagnostics().has(DiagnosticKind::UnmatchedExit));
    assert!(session.diagnostics().has(DiagnosticKind::UnclosedEnter));
    assert_eq!(session.method(2).unwrap().display_name(), "0x2");
}

#[test]
fn streaming_v5_retains_separate_cpu_clock_and_delta_semantics() {
    let mut bytes = streaming(5);
    method(
        &mut bytes,
        2,
        "android/os/Binder\ttransact\t(IJ)V\tBinder.java\n",
    );
    block(&mut bytes, 2, &[(8, 100, 50), (9, 300, 120)], true);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    let events = session.events_page(Some(2), 0, 10).unwrap().rows;
    assert_eq!((events[0].time_nanos, events[0].cpu_nanos), (100, Some(50)));
    assert_eq!(
        (events[1].time_nanos, events[1].cpu_nanos),
        (400, Some(170))
    );
    // The existing fixture's second word is 17, so it targets a different ID.
    assert_eq!(events[1].method_id, 4);
    assert!(session.diagnostics().has(DiagnosticKind::UnmatchedExit));
}

#[test]
fn classic_v3_decodes_tables_words_and_microseconds() {
    let bytes = classic(
        3,
        "*threads\n\t1\tmain\n*methods\n\t8\tandroid/os/Looper\tloop\t()V\tLooper.java\n*end\n",
        &[(1, 8, 1000, 2000), (1, 9, 2000, 4000)],
    );
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    assert_eq!(session.header().start_time_nanos, 1_000_000_000);
    assert_eq!(session.header().clock_source, ClockSource::WallAndThreadCpu);
    assert_eq!(session.thread(1).unwrap().name, "main");
    let events = session.events_page(None, 0, 10).unwrap().rows;
    assert_eq!(
        (
            events[0].method_id,
            events[0].time_nanos,
            events[0].cpu_nanos
        ),
        (2, 2_000_000, Some(1_000_000))
    );
    let method = stats(&session, Some(1), 2);
    assert_eq!(
        method.self_time,
        DurationTotals {
            primary_nanos: 2_000_000,
            cpu_nanos: Some(1_000_000)
        }
    );
    assert_eq!(method.completed_calls, 1);
    assert!(session.diagnostics().is_empty());
}

#[test]
fn classic_v2_primary_is_thread_cpu_not_wall_time() {
    let bytes = classic(
        2,
        "*threads\n\t1\tmain\n*methods\n\t4\tMain\tonCreate\t()V\tMain.java\n*end\n",
        &[(1, 4, 500, 0), (1, 5, 700, 0)],
    );
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    assert_eq!(session.header().clock_source, ClockSource::ThreadCpu);
    assert_eq!(
        stats(&session, None, 1).self_time,
        DurationTotals {
            primary_nanos: 200_000,
            cpu_nanos: None
        }
    );
}

#[test]
fn nested_duration_analysis_counts_enters_and_real_intervals() {
    let session = MethodRecordingSession::parse(&nested()).unwrap();
    let a = stats(&session, Some(1), 1);
    let b = stats(&session, Some(1), 2);
    assert_eq!(
        (
            a.call_count,
            a.completed_calls,
            a.self_time.primary_nanos,
            a.inclusive_time.primary_nanos
        ),
        (1, 1, 20, 40)
    );
    assert_eq!(
        (
            b.call_count,
            b.completed_calls,
            b.self_time.primary_nanos,
            b.inclusive_time.primary_nanos
        ),
        (1, 1, 20, 20)
    );
    let intervals = session.timeline_page(TimelineQuery::default()).unwrap();
    assert_eq!(intervals.total, 3);
    assert_eq!(
        intervals
            .rows
            .iter()
            .map(|row| row.duration_nanos())
            .collect::<Vec<_>>(),
        vec![10, 20, 10]
    );
    assert_eq!(
        session.stack(intervals.rows[1].leaf_node_id).unwrap(),
        vec![1, 2]
    );
    assert_eq!(session.summary().observed_active_time.primary_nanos, 40);
    assert!(session.diagnostics().is_empty());
}

#[test]
fn recursive_inclusive_is_sum_of_invocations_not_deduplicated_occupancy() {
    let mut bytes = streaming(4);
    block(
        &mut bytes,
        1,
        &[(4, 0, 0), (0, 10, 0), (1, 10, 0), (0, 10, 0)],
        false,
    );
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    let a = stats(&session, None, 1);
    assert_eq!(
        (
            a.call_count,
            a.completed_calls,
            a.self_time.primary_nanos,
            a.inclusive_time.primary_nanos
        ),
        (2, 2, 30, 40)
    );
    let tree = session.call_tree_page(1, 0, 10).unwrap();
    assert_eq!(tree.rows.len(), 3);
    assert_eq!(tree.rows[2].depth, 2);
}

#[test]
fn incomplete_thread_never_extends_to_another_threads_last_event() {
    let mut bytes = streaming(4);
    block(&mut bytes, 1, &[(4, 0, 0), (4, 10, 0)], false);
    block(&mut bytes, 2, &[(12, 100, 0), (1, 100, 0)], false);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    let a = stats(&session, Some(1), 1);
    let b = stats(&session, Some(1), 2);
    assert_eq!(
        (
            a.inclusive_time.primary_nanos,
            b.inclusive_time.primary_nanos
        ),
        (10, 0)
    );
    assert_eq!((a.incomplete_calls, b.incomplete_calls), (1, 1));
    assert_eq!(
        session
            .timeline_page(TimelineQuery {
                thread_id: Some(1),
                ..Default::default()
            })
            .unwrap()
            .rows
            .last()
            .unwrap()
            .end_nanos,
        10
    );
}

#[test]
fn event_only_threads_are_retained_and_late_tables_are_resolved() {
    let mut bytes = streaming(4);
    block(&mut bytes, 42, &[(4, 0, 0), (1, 10, 0)], false);
    thread(&mut bytes, 42, "late main");
    method(&mut bytes, 1, "Main\trun\t()V\tMain.java");
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    assert_eq!(session.thread(42).unwrap().event_count, 2);
    assert!(session.thread(42).unwrap().declared);
    assert!(session.method(1).unwrap().declared);
    assert!(!session
        .diagnostics()
        .has(DiagnosticKind::UncataloguedThread));
    assert!(session.call_tree_page(42, 0, 10).unwrap().total > 0);
}

#[test]
fn unmatched_exit_does_not_pop_unrelated_active_method() {
    let mut bytes = streaming(4);
    block(&mut bytes, 1, &[(4, 0, 0), (5, 10, 0), (-4, 10, 0)], false);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    let a = stats(&session, None, 1);
    assert_eq!(
        (
            a.completed_calls,
            a.incomplete_calls,
            a.self_time.primary_nanos
        ),
        (1, 0, 20)
    );
    assert!(session.diagnostics().has(DiagnosticKind::UnmatchedExit));
}

#[test]
fn ancestor_exit_marks_open_descendants_incomplete() {
    let mut bytes = streaming(4);
    block(&mut bytes, 1, &[(4, 0, 0), (4, 10, 0), (-3, 20, 0)], false);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    let b = stats(&session, None, 2);
    assert_eq!(
        (
            b.completed_calls,
            b.incomplete_calls,
            b.inclusive_time.primary_nanos
        ),
        (0, 1, 20)
    );
    assert!(session.diagnostics().has(DiagnosticKind::MismatchedExit));
    assert_eq!(stats(&session, None, 1).completed_calls, 1);
}

#[test]
fn unroll_is_not_counted_as_normal_return_or_as_an_extra_call() {
    let mut bytes = streaming(4);
    block(&mut bytes, 1, &[(4, 0, 0), (2, 30, 0)], false);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    let a = stats(&session, None, 1);
    assert_eq!(
        (
            a.call_count,
            a.completed_calls,
            a.unrolled_calls,
            a.incomplete_calls
        ),
        (1, 0, 1, 0)
    );
    assert_eq!(a.inclusive_time.primary_nanos, 30);
}

#[test]
fn block_accumulators_reset_and_clock_regressions_do_not_bridge() {
    let mut bytes = streaming(4);
    block(&mut bytes, 1, &[(4, 100, 0)], false);
    block(&mut bytes, 1, &[(5, 50, 0)], false);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    assert_eq!(
        session.events_page(None, 0, 10).unwrap().rows[1].time_nanos,
        50
    );
    assert!(session.diagnostics().has(DiagnosticKind::ClockRegression));
    assert_eq!(session.summary().observed_active_time.primary_nanos, 0);
    assert_eq!(stats(&session, None, 1).incomplete_calls, 1);
}

#[test]
fn cpu_clock_regression_excludes_only_invalid_cpu_delta() {
    let mut bytes = streaming(5);
    block(&mut bytes, 1, &[(4, 0, 100)], true);
    block(&mut bytes, 1, &[(5, 10, 50)], true);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    let a = stats(&session, None, 1);
    assert_eq!(
        a.self_time,
        DurationTotals {
            primary_nanos: 10,
            cpu_nanos: Some(0)
        }
    );
    assert!(session
        .diagnostics()
        .has(DiagnosticKind::CpuClockRegression));
    let row = &session
        .timeline_page(TimelineQuery::default())
        .unwrap()
        .rows[0];
    assert_eq!((row.cpu_start_nanos, row.cpu_end_nanos), (None, None));
}

#[test]
fn dual_clock_durations_are_not_substituted_for_each_other() {
    let mut bytes = streaming(5);
    block(&mut bytes, 1, &[(4, 100, 40), (1, 300, 70)], true);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    let a = stats(&session, None, 1);
    assert_eq!(
        a.self_time,
        DurationTotals {
            primary_nanos: 300,
            cpu_nanos: Some(70)
        }
    );
    assert_eq!(a.inclusive_time, a.self_time);
}

#[test]
fn unknown_packet_and_producer_overflow_remain_visible() {
    let mut bytes = nested();
    summary(&mut bytes, "data-file-overflow=true\n");
    bytes.extend([99, 1, 2, 3]);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    assert!(session.diagnostics().has(DiagnosticKind::CaptureOverflow));
    assert!(session.diagnostics().has(DiagnosticKind::UnknownPacket));
    assert!(session.summary().has_integrity_diagnostics);
    assert_eq!(session.summary().event_count, 4);
}

#[test]
fn trailing_partial_classic_record_and_unused_actions_are_reported() {
    let mut bytes = classic(2, "", &[(1, 7, 0, 0)]);
    bytes.push(9);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    assert_eq!(session.summary().event_count, 0);
    assert!(session.diagnostics().has(DiagnosticKind::UnusedAction));
    assert!(session.diagnostics().has(DiagnosticKind::TrailingBytes));
}

#[test]
fn malformed_truncated_unsupported_and_wrong_record_size_fail_closed() {
    assert_eq!(
        MethodRecordingSession::parse(b"nope").unwrap_err().kind,
        ErrorKind::Format
    );
    let mut old = b"SLOW".to_vec();
    u16(&mut old, 1);
    assert_eq!(
        MethodRecordingSession::parse(&old).unwrap_err().kind,
        ErrorKind::UnsupportedVersion
    );
    let mut truncated = streaming(4);
    truncated.truncate(20);
    assert_eq!(
        MethodRecordingSession::parse(&truncated).unwrap_err().kind,
        ErrorKind::Format
    );
    let mut wrong_size = classic(3, "", &[]);
    wrong_size[16] = 10;
    assert_eq!(
        MethodRecordingSession::parse(&wrong_size).unwrap_err().kind,
        ErrorKind::Format
    );
}

#[test]
fn leb_and_accumulator_overflow_are_rejected_not_wrapped() {
    let mut unsigned = streaming(4);
    let mut payload = vec![4];
    payload.extend([0x80; 9]);
    payload.push(2);
    raw_block(&mut unsigned, 1, 1, &payload);
    assert_eq!(
        MethodRecordingSession::parse(&unsigned).unwrap_err().kind,
        ErrorKind::ArithmeticOverflow
    );
    let mut signed = streaming(4);
    raw_block(&mut signed, 1, 1, &[0x80; 11]);
    assert_eq!(
        MethodRecordingSession::parse(&signed).unwrap_err().kind,
        ErrorKind::ArithmeticOverflow
    );
    let mut timestamp = streaming(4);
    block(&mut timestamp, 1, &[(4, u64::MAX, 0), (1, 1, 0)], false);
    assert_eq!(
        MethodRecordingSession::parse(&timestamp).unwrap_err().kind,
        ErrorKind::ArithmeticOverflow
    );
    let mut negative_word = streaming(4);
    block(&mut negative_word, 1, &[(-1, 0, 0)], false);
    assert_eq!(
        MethodRecordingSession::parse(&negative_word)
            .unwrap_err()
            .kind,
        ErrorKind::ArithmeticOverflow
    );
}

#[test]
fn raw_record_budget_counts_ignored_records_too() {
    let mut bytes = streaming(4);
    block(&mut bytes, 1, &[(7, 0, 0)], false);
    block(&mut bytes, 1, &[(7, 0, 0)], false);
    let error = MethodRecordingSession::import_bytes(
        &bytes,
        ImportLimits {
            max_events: 1,
            ..Default::default()
        },
    )
    .unwrap_err();
    assert_eq!(error.kind, ErrorKind::Limit);
}

#[test]
fn byte_text_depth_catalog_and_tree_limits_are_enforced() {
    let bytes = nested();
    for limits in [
        ImportLimits {
            max_bytes: bytes.len() as u64 - 1,
            ..Default::default()
        },
        ImportLimits {
            max_string_bytes: 5,
            ..Default::default()
        },
        ImportLimits {
            max_stack_depth: 1,
            ..Default::default()
        },
        ImportLimits {
            max_methods: 1,
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
            ErrorKind::Limit
        );
    }
}

#[test]
fn capped_diagnostic_messages_keep_full_occurrence_counts() {
    let mut bytes = streaming(4);
    block(&mut bytes, 1, &[(7, 0, 0), (0, 1, 0), (0, 1, 0)], false);
    let session = MethodRecordingSession::import_bytes(
        &bytes,
        ImportLimits {
            max_diagnostics: 1,
            ..Default::default()
        },
    )
    .unwrap();
    assert_eq!(session.diagnostics().entries.len(), 1);
    assert_eq!(session.diagnostics().omitted_entries, 2);
    assert_eq!(
        session.diagnostics().counts[&DiagnosticKind::UnusedAction],
        3
    );
}

#[test]
fn bounded_pages_preserve_stable_rows_and_half_open_timeline_filtering() {
    let session = MethodRecordingSession::parse(&nested()).unwrap();
    let first = session.top_methods_page(None, 0, 1).unwrap();
    let second = session.top_methods_page(None, 1, 1).unwrap();
    assert_eq!(first.total, 2);
    assert!(first.has_more);
    assert!(!second.has_more);
    assert_eq!((first.rows[0].method_id, second.rows[0].method_id), (1, 2));
    let intervals = session
        .timeline_page(TimelineQuery {
            thread_id: Some(1),
            start_nanos: Some(10),
            end_nanos: Some(30),
            ..Default::default()
        })
        .unwrap();
    assert_eq!(intervals.total, 1);
    assert_eq!(
        (intervals.rows[0].start_nanos, intervals.rows[0].end_nanos),
        (10, 30)
    );
    assert!(session.events_page(None, 0, 0).is_err());
    assert!(session.methods_page(0, 513).is_err());
    assert!(session.call_tree_page(999, 0, 10).is_err());
    assert!(session.stack(usize::MAX).is_err());
    assert!(session
        .events_page(None, usize::MAX, 10)
        .unwrap()
        .rows
        .is_empty());
}

#[test]
fn non_table_classic_thread_42_does_not_trigger_text_scanning() {
    let bytes = classic(2, "", &[(42, 4, 0, 0), (42, 5, 10, 0)]);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    assert_eq!(session.thread(42).unwrap().event_count, 2);
    assert_eq!(stats(&session, Some(42), 1).self_time.primary_nanos, 10_000);
}

#[test]
fn all_truncated_prefixes_of_a_packet_are_rejected() {
    let mut bytes = streaming(4);
    method(&mut bytes, 1, "Main\trun\t()V\tMain.java");
    for length in 33..bytes.len() {
        assert!(
            MethodRecordingSession::parse(&bytes[..length]).is_err(),
            "prefix {length} unexpectedly accepted"
        );
    }
}

#[test]
fn invalid_utf8_catalog_is_rejected_without_lossy_names() {
    let mut bytes = streaming(4);
    bytes.push(0);
    u32(&mut bytes, 1);
    u16(&mut bytes, 1);
    bytes.push(0xff);
    assert_eq!(
        MethodRecordingSession::parse(&bytes).unwrap_err().kind,
        ErrorKind::Format
    );
}

#[test]
fn extra_stream_block_payload_is_not_silently_ignored() {
    let mut bytes = streaming(4);
    raw_block(&mut bytes, 1, 0, &[1, 2, 3]);
    let session = MethodRecordingSession::parse(&bytes).unwrap();
    assert!(session.diagnostics().has(DiagnosticKind::TrailingBytes));
}

#[test]
fn empty_trace_has_no_fabricated_event_epoch_or_duration() {
    let session = MethodRecordingSession::parse(&streaming(4)).unwrap();
    assert_eq!(session.summary().minimum_event_time_nanos, None);
    assert_eq!(session.summary().maximum_event_time_nanos, None);
    assert_eq!(session.summary().observed_active_time.primary_nanos, 0);
    assert_eq!(session.events_page(None, 0, 10).unwrap().total, 0);
}
