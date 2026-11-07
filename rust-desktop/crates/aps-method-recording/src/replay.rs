use super::*;
use std::collections::HashMap;

struct Frame {
    method: MethodId,
    node: NodeId,
    start_primary: u64,
    start_cpu: u64,
}
struct ThreadReplay {
    root: NodeId,
    stack: Vec<Frame>,
    previous: Option<(u64, Option<u64>)>,
    elapsed_primary: u64,
    elapsed_cpu: u64,
    active_primary: u64,
    active_cpu: u64,
    last_event_index: Option<usize>,
}
#[derive(Clone, Copy)]
enum Closure {
    Returned,
    Unrolled,
    Incomplete,
}

pub(crate) fn analyze(
    trace: ParsedTrace,
    limits: ImportLimits,
) -> Result<MethodRecordingSession, ImportError> {
    let ParsedTrace {
        header,
        mut methods,
        mut threads,
        events,
        mut diagnostics,
        ..
    } = trace;
    let dual = header.clock_source == ClockSource::WallAndThreadCpu;
    let mut minimum = None::<u64>;
    let mut maximum = None::<u64>;
    for (index, event) in events.iter().enumerate() {
        if !threads.contains_key(&event.thread_id) {
            if threads.len() >= limits.max_threads {
                return Err(limit_error(
                    "Event-only thread exceeds thread catalog limit",
                ));
            }
            threads.insert(
                event.thread_id,
                Thread {
                    id: event.thread_id,
                    name: format!("Thread {}", event.thread_id),
                    declared: false,
                    event_count: 0,
                    first_time_nanos: None,
                    last_time_nanos: None,
                },
            );
            record(
                &mut diagnostics,
                DiagnosticKind::UncataloguedThread,
                event,
                index,
                "Thread has events but no name declaration".into(),
                limits,
            );
        }
        if !methods.contains_key(&event.method_id) {
            if methods.len() >= limits.max_methods {
                return Err(limit_error(
                    "Uncatalogued method exceeds method catalog limit",
                ));
            }
            methods.insert(
                event.method_id,
                Method {
                    id: event.method_id,
                    class_name: String::new(),
                    method_name: String::new(),
                    signature: String::new(),
                    source_file: String::new(),
                    declared: false,
                },
            );
            record(
                &mut diagnostics,
                DiagnosticKind::UncataloguedMethod,
                event,
                index,
                format!("Method 0x{:x} has no symbol declaration", event.method_id),
                limits,
            );
        }
        let thread = threads.get_mut(&event.thread_id).unwrap();
        thread.event_count += 1;
        thread.first_time_nanos = Some(
            thread
                .first_time_nanos
                .map_or(event.time_nanos, |old| old.min(event.time_nanos)),
        );
        thread.last_time_nanos = Some(
            thread
                .last_time_nanos
                .map_or(event.time_nanos, |old| old.max(event.time_nanos)),
        );
        minimum = Some(minimum.map_or(event.time_nanos, |old| old.min(event.time_nanos)));
        maximum = Some(maximum.map_or(event.time_nanos, |old| old.max(event.time_nanos)));
    }
    let mut nodes = Vec::<AnalysisNode>::new();
    let mut states = BTreeMap::<ThreadId, ThreadReplay>::new();
    for id in threads.keys().copied() {
        if nodes.len() >= limits.max_call_tree_nodes {
            return Err(limit_error("Thread roots exceed call-tree node limit"));
        }
        let root = nodes.len();
        nodes.push(AnalysisNode {
            row: CallTreeRow {
                id: root,
                parent_id: None,
                thread_id: id,
                method_id: None,
                depth: 0,
                child_count: 0,
                statistics: empty_statistics(0, dual),
            },
            children: Vec::new(),
        });
        states.insert(
            id,
            ThreadReplay {
                root,
                stack: Vec::new(),
                previous: None,
                elapsed_primary: 0,
                elapsed_cpu: 0,
                active_primary: 0,
                active_cpu: 0,
                last_event_index: None,
            },
        );
    }
    let mut node_keys = HashMap::<(NodeId, MethodId), NodeId>::new();
    let mut intervals = Vec::new();
    for (index, event) in events.iter().enumerate() {
        let state = states.get_mut(&event.thread_id).unwrap();
        if let Some((previous_time, previous_cpu)) = state.previous {
            if event.time_nanos < previous_time {
                record(&mut diagnostics, DiagnosticKind::ClockRegression, event, index, "Primary clock moved backwards; active frames closed as incomplete and no negative/bridging duration was generated".into(), limits);
                close_all(
                    state,
                    &mut nodes,
                    &mut diagnostics,
                    event,
                    index,
                    limits,
                    "Clock discontinuity leaves this invocation incomplete",
                )?;
            } else {
                let delta = event.time_nanos - previous_time;
                let cpu_delta = match (previous_cpu, event.cpu_nanos) {
                    (Some(before), Some(after)) if after >= before => Some(after - before),
                    (Some(_), Some(_)) => {
                        record(
                            &mut diagnostics,
                            DiagnosticKind::CpuClockRegression,
                            event,
                            index,
                            "Thread CPU clock moved backwards; this CPU delta was excluded".into(),
                            limits,
                        );
                        None
                    }
                    _ => None,
                };
                checked_add(&mut state.elapsed_primary, delta)?;
                checked_add(&mut state.elapsed_cpu, cpu_delta.unwrap_or(0))?;
                if let Some(frame) = state.stack.last() {
                    add_time(
                        &mut nodes[frame.node].row.statistics.self_time,
                        delta,
                        cpu_delta,
                    )?;
                    checked_add(&mut state.active_primary, delta)?;
                    checked_add(&mut state.active_cpu, cpu_delta.unwrap_or(0))?;
                    if delta > 0 {
                        intervals.push(TimelineInterval {
                            thread_id: event.thread_id,
                            start_nanos: previous_time,
                            end_nanos: event.time_nanos,
                            cpu_start_nanos: cpu_delta.and(previous_cpu),
                            cpu_end_nanos: cpu_delta.and(event.cpu_nanos),
                            leaf_node_id: frame.node,
                            depth: state.stack.len(),
                        });
                    }
                }
            }
        }
        match event.action {
            Action::Enter => {
                if state.stack.len() >= limits.max_stack_depth {
                    return Err(limit_error("Observed method stack exceeds depth limit"));
                }
                let parent = state.stack.last().map_or(state.root, |frame| frame.node);
                let key = (parent, event.method_id);
                let node = if let Some(node) = node_keys.get(&key) {
                    *node
                } else {
                    if nodes.len() >= limits.max_call_tree_nodes {
                        return Err(limit_error("Call tree exceeds node limit"));
                    }
                    let id = nodes.len();
                    nodes.push(AnalysisNode {
                        row: CallTreeRow {
                            id,
                            parent_id: Some(parent),
                            thread_id: event.thread_id,
                            method_id: Some(event.method_id),
                            depth: state.stack.len() + 1,
                            child_count: 0,
                            statistics: empty_statistics(event.method_id, dual),
                        },
                        children: Vec::new(),
                    });
                    nodes[parent].children.push(id);
                    nodes[parent].row.child_count += 1;
                    node_keys.insert(key, id);
                    id
                };
                nodes[node].row.statistics.call_count += 1;
                nodes[state.root].row.statistics.call_count += 1;
                state.stack.push(Frame {
                    method: event.method_id,
                    node,
                    start_primary: state.elapsed_primary,
                    start_cpu: state.elapsed_cpu,
                });
            }
            Action::Exit | Action::Unroll => {
                if let Some(position) = state
                    .stack
                    .iter()
                    .rposition(|frame| frame.method == event.method_id)
                {
                    if position + 1 != state.stack.len() {
                        record(&mut diagnostics, DiagnosticKind::MismatchedExit, event, index, format!("Exit/unroll targets method 0x{:x} below {} still-open child frame(s); children are incomplete, not matched returns", event.method_id, state.stack.len() - position - 1), limits);
                        while state.stack.len() > position + 1 {
                            let method = state.stack.last().unwrap().method;
                            record(&mut diagnostics, DiagnosticKind::UnclosedEnter, event, index, format!("Method 0x{method:x} has no matching exit before an ancestor exits"), limits);
                            close_top(state, &mut nodes, Closure::Incomplete)?;
                        }
                    }
                    let reason = if event.action == Action::Exit {
                        Closure::Returned
                    } else {
                        Closure::Unrolled
                    };
                    close_top(state, &mut nodes, reason)?;
                } else {
                    record(&mut diagnostics, DiagnosticKind::UnmatchedExit, event, index, format!("No active invocation matches {:?} for method 0x{:x}; unrelated frames were not popped", event.action, event.method_id), limits);
                }
            }
        }
        state.previous = Some((event.time_nanos, event.cpu_nanos));
        state.last_event_index = Some(index);
    }
    let mut observed = DurationTotals {
        primary_nanos: 0,
        cpu_nanos: dual.then_some(0),
    };
    for state in states.values_mut() {
        // This does not advance clocks. Each frame ends at this thread's last
        // observed event; it never borrows the global trace maximum timestamp.
        if !state.stack.is_empty() {
            let last = state.last_event_index.unwrap();
            close_all(
                state,
                &mut nodes,
                &mut diagnostics,
                &events[last],
                last,
                limits,
                "End of this thread's observed events leaves this invocation incomplete",
            )?;
        }
        let root = &mut nodes[state.root].row.statistics;
        root.inclusive_time = DurationTotals {
            primary_nanos: state.active_primary,
            cpu_nanos: dual.then_some(state.active_cpu),
        };
        add_time(
            &mut observed,
            state.active_primary,
            dual.then_some(state.active_cpu),
        )?;
    }
    let mut preorder = BTreeMap::new();
    for (id, state) in &states {
        let mut ids = Vec::new();
        let mut pending = vec![state.root];
        while let Some(node) = pending.pop() {
            ids.push(node);
            pending.extend(nodes[node].children.iter().rev().copied());
        }
        preorder.insert(*id, ids);
    }
    let mut global = BTreeMap::<MethodId, MethodStatistics>::new();
    let mut by_thread = BTreeMap::<ThreadId, BTreeMap<MethodId, MethodStatistics>>::new();
    for node in &nodes {
        let Some(method) = node.row.method_id else {
            continue;
        };
        merge(
            global
                .entry(method)
                .or_insert_with(|| empty_statistics(method, dual)),
            &node.row.statistics,
        )?;
        merge(
            by_thread
                .entry(node.row.thread_id)
                .or_default()
                .entry(method)
                .or_insert_with(|| empty_statistics(method, dual)),
            &node.row.statistics,
        )?;
    }
    let top_methods = sorted(global);
    let thread_top_methods = by_thread
        .into_iter()
        .map(|(id, values)| (id, sorted(values)))
        .collect();
    let summary = TraceSummary {
        event_count: events.len(),
        method_count: methods.len(),
        thread_count: threads.len(),
        interval_count: intervals.len(),
        call_tree_node_count: nodes.len(),
        minimum_event_time_nanos: minimum,
        maximum_event_time_nanos: maximum,
        observed_active_time: observed,
        has_integrity_diagnostics: !diagnostics.is_empty(),
    };
    Ok(MethodRecordingSession {
        header,
        methods,
        threads,
        events,
        diagnostics,
        summary,
        nodes,
        preorder,
        intervals,
        top_methods,
        thread_top_methods,
    })
}

fn empty_statistics(method_id: MethodId, dual: bool) -> MethodStatistics {
    MethodStatistics {
        method_id,
        self_time: DurationTotals {
            primary_nanos: 0,
            cpu_nanos: dual.then_some(0),
        },
        inclusive_time: DurationTotals {
            primary_nanos: 0,
            cpu_nanos: dual.then_some(0),
        },
        ..MethodStatistics::default()
    }
}
fn close_top(
    state: &mut ThreadReplay,
    nodes: &mut [AnalysisNode],
    reason: Closure,
) -> Result<(), ImportError> {
    let frame = state.stack.pop().unwrap();
    let stats = &mut nodes[frame.node].row.statistics;
    let cpu_duration = stats
        .self_time
        .cpu_nanos
        .map(|_| state.elapsed_cpu - frame.start_cpu);
    add_time(
        &mut stats.inclusive_time,
        state.elapsed_primary - frame.start_primary,
        cpu_duration,
    )?;
    match reason {
        Closure::Returned => {
            stats.completed_calls += 1;
            nodes[state.root].row.statistics.completed_calls += 1;
        }
        Closure::Unrolled => {
            stats.unrolled_calls += 1;
            nodes[state.root].row.statistics.unrolled_calls += 1;
        }
        Closure::Incomplete => {
            stats.incomplete_calls += 1;
            nodes[state.root].row.statistics.incomplete_calls += 1;
        }
    }
    Ok(())
}
fn close_all(
    state: &mut ThreadReplay,
    nodes: &mut [AnalysisNode],
    diagnostics: &mut Diagnostics,
    event: &MethodEvent,
    index: usize,
    limits: ImportLimits,
    message: &str,
) -> Result<(), ImportError> {
    while let Some(frame) = state.stack.last() {
        record(
            diagnostics,
            DiagnosticKind::UnclosedEnter,
            event,
            index,
            format!("{message}: method 0x{:x}", frame.method),
            limits,
        );
        close_top(state, nodes, Closure::Incomplete)?;
    }
    Ok(())
}
fn record(
    diagnostics: &mut Diagnostics,
    kind: DiagnosticKind,
    event: &MethodEvent,
    index: usize,
    message: String,
    limits: ImportLimits,
) {
    diagnostics.record(
        Diagnostic {
            kind,
            thread_id: Some(event.thread_id),
            event_index: Some(index),
            offset: Some(event.source_offset),
            message,
        },
        limits.max_diagnostics,
    );
}
fn checked_add(target: &mut u64, value: u64) -> Result<(), ImportError> {
    *target = target.checked_add(value).ok_or_else(|| {
        ImportError::new(
            ErrorKind::ArithmeticOverflow,
            None,
            "Aggregated method duration exceeds u64 nanoseconds",
        )
    })?;
    Ok(())
}
fn add_time(
    target: &mut DurationTotals,
    primary: u64,
    cpu: Option<u64>,
) -> Result<(), ImportError> {
    checked_add(&mut target.primary_nanos, primary)?;
    if let Some(target_cpu) = &mut target.cpu_nanos {
        checked_add(target_cpu, cpu.unwrap_or(0))?;
    }
    Ok(())
}
fn merge(target: &mut MethodStatistics, source: &MethodStatistics) -> Result<(), ImportError> {
    target.call_count += source.call_count;
    target.completed_calls += source.completed_calls;
    target.unrolled_calls += source.unrolled_calls;
    target.incomplete_calls += source.incomplete_calls;
    add_time(
        &mut target.self_time,
        source.self_time.primary_nanos,
        source.self_time.cpu_nanos,
    )?;
    add_time(
        &mut target.inclusive_time,
        source.inclusive_time.primary_nanos,
        source.inclusive_time.cpu_nanos,
    )
}
fn sorted(values: BTreeMap<MethodId, MethodStatistics>) -> Vec<MethodStatistics> {
    let mut values: Vec<_> = values.into_values().collect();
    values.sort_by(|a, b| {
        b.self_time
            .primary_nanos
            .cmp(&a.self_time.primary_nanos)
            .then_with(|| {
                b.inclusive_time
                    .primary_nanos
                    .cmp(&a.inclusive_time.primary_nanos)
            })
            .then_with(|| a.method_id.cmp(&b.method_id))
    });
    values
}
fn limit_error(message: &str) -> ImportError {
    ImportError::new(ErrorKind::Limit, None, message)
}
