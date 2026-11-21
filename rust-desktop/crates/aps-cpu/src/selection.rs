use crate::{Query, WeightType};
use aps_simpleperf_engine::simpleperf::{FileRecord, Frame, Record, Sample, SimpleperfStream};
use std::collections::{BTreeMap, BTreeSet};

pub(crate) struct Selection<'a> {
    pub samples: Vec<(usize, &'a Sample)>,
    pub files: BTreeMap<u32, &'a FileRecord>,
    pub events: BTreeMap<u32, String>,
}

pub(crate) fn select<'a>(
    stream: &'a SimpleperfStream,
    query: &Query,
) -> Result<Selection<'a>, String> {
    query.validate()?;
    let mut files = BTreeMap::new();
    let mut events = BTreeMap::new();
    let mut threads = BTreeSet::new();
    for record in &stream.records {
        match record {
            Record::File(file) => {
                files.insert(file.id, file);
            }
            Record::Thread(thread) => {
                if let Ok(id) = i32::try_from(thread.thread_id) {
                    threads.insert(id);
                }
            }
            Record::MetaInfo(meta) => {
                events = meta
                    .event_types
                    .iter()
                    .enumerate()
                    .map(|(id, name)| (id as u32, name.clone()))
                    .collect();
            }
            Record::Sample(sample) => {
                threads.insert(sample.thread_id);
            }
            _ => {}
        }
    }
    for record in &stream.records {
        if let Record::Sample(sample) = record {
            events
                .entry(sample.event_type_id)
                .or_insert_with(|| format!("event#{}", sample.event_type_id));
        }
    }
    if let Some(id) = query.thread_ids.iter().find(|id| !threads.contains(id)) {
        return Err(format!("unknown thread filter {id}"));
    }
    if let Some(name) = query
        .event_types
        .iter()
        .find(|name| !events.values().any(|event| event == *name))
    {
        return Err(format!("unknown event filter {name}"));
    }
    let event_ids: BTreeSet<_> = events
        .iter()
        .filter(|(_, name)| query.event_types.is_empty() || query.event_types.contains(*name))
        .map(|(id, _)| *id)
        .collect();
    if query.weight_type == WeightType::EventCount && event_ids.len() > 1 {
        return Err(
            "EVENT_COUNT requires a single event type; use samples for multiple events".into(),
        );
    }
    let samples = stream
        .records
        .iter()
        .enumerate()
        .filter_map(|(ordinal, record)| {
            let Record::Sample(sample) = record else {
                return None;
            };
            (event_ids.contains(&sample.event_type_id)
                && (query.thread_ids.is_empty() || query.thread_ids.contains(&sample.thread_id))
                && query.start.is_none_or(|start| sample.time >= start)
                && query.end.is_none_or(|end| sample.time < end))
            .then_some((ordinal, sample))
        })
        .collect();
    Ok(Selection {
        samples,
        files,
        events,
    })
}

pub(crate) fn labels(files: &BTreeMap<u32, &FileRecord>, frame: &Frame) -> (String, String) {
    let file = files.get(&frame.file_id);
    let name = file
        .and_then(|file| {
            usize::try_from(frame.symbol_id)
                .ok()
                .and_then(|id| file.symbols.get(id))
        })
        .cloned()
        .unwrap_or_else(|| "<unknown-symbol>".into());
    let resource = file
        .map(|file| file.path.clone())
        .unwrap_or_else(|| format!("<unknown-file:{}>", frame.file_id));
    (name, resource)
}

pub(crate) fn weight(sample: &Sample, mode: WeightType) -> u64 {
    match mode {
        WeightType::Samples => 1,
        WeightType::EventCount => sample.event_count,
    }
}

pub(crate) fn add(target: &mut u64, value: u64) -> Result<(), String> {
    *target = target
        .checked_add(value)
        .filter(|sum| *sum <= i64::MAX as u64)
        .ok_or("CPU projection metric exceeds signed i64")?;
    Ok(())
}
