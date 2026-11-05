#![allow(dead_code)]
use aps_simpleperf_engine::simpleperf::{
    FileRecord, Frame, MetaInfo, Record, Sample, SimpleperfStream, ThreadRecord, MAGIC,
};

pub fn frame(symbol: i32) -> Frame {
    Frame {
        file_id: 7,
        symbol_id: symbol,
        virtual_address: (symbol as u64 + 1) * 10,
        execution_type: 0,
    }
}

pub fn sample(time: u64, thread: i32, event: u32, count: u64, symbols: &[i32]) -> Record {
    Record::Sample(Sample {
        time,
        thread_id: thread,
        event_type_id: event,
        event_count: count,
        callchain: symbols.iter().copied().map(frame).collect(),
    })
}

pub fn profile() -> SimpleperfStream {
    SimpleperfStream {
        version: 1,
        records: vec![
            sample(10, 11, 0, 3, &[1, 0]),
            sample(20, 22, 0, 5, &[1, 0]),
            sample(30, 11, 1, 101, &[1, 2]),
            sample(40, 22, 1, 103, &[0]),
            sample(50, 33, 0, 7, &[1, 0]),
            sample(60, 11, 0, 11, &[]),
            Record::Thread(ThreadRecord {
                thread_id: 44,
                process_id: 1,
                name: "declared-without-samples".into(),
            }),
            Record::MetaInfo(MetaInfo {
                event_types: vec!["cpu-cycles".into(), "instructions".into()],
                app_package_name: None,
                app_type: None,
                android_sdk_version: None,
                android_build_type: None,
                trace_offcpu: false,
            }),
            Record::File(FileRecord {
                id: 7,
                path: "fixture.so".into(),
                symbols: vec!["root".into(), "leaf".into(), "other".into()],
            }),
        ],
    }
}

pub fn varint(mut value: u64) -> Vec<u8> {
    let mut bytes = Vec::new();
    loop {
        let byte = (value & 127) as u8;
        value >>= 7;
        bytes.push(if value == 0 { byte } else { byte | 128 });
        if value == 0 {
            return bytes;
        }
    }
}

pub fn number(field: u8, value: u64) -> Vec<u8> {
    [varint(u64::from(field) << 3), varint(value)].concat()
}

pub fn bytes(field: u8, value: &[u8]) -> Vec<u8> {
    [
        varint((u64::from(field) << 3) | 2),
        varint(value.len() as u64),
        value.to_vec(),
    ]
    .concat()
}

pub fn encode_record(record: &Record) -> Vec<u8> {
    match record {
        Record::Sample(sample) => {
            let mut payload = [
                number(1, sample.time),
                number(2, sample.thread_id as u64),
                number(4, sample.event_count),
                number(5, u64::from(sample.event_type_id)),
            ]
            .concat();
            for frame in &sample.callchain {
                payload.extend(bytes(
                    3,
                    &[
                        number(1, frame.virtual_address),
                        number(2, u64::from(frame.file_id)),
                        number(3, frame.symbol_id as u64),
                        number(4, u64::from(frame.execution_type)),
                    ]
                    .concat(),
                ));
            }
            bytes(1, &payload)
        }
        Record::File(file) => {
            let mut payload = [
                number(1, u64::from(file.id)),
                bytes(2, file.path.as_bytes()),
            ]
            .concat();
            for symbol in &file.symbols {
                payload.extend(bytes(3, symbol.as_bytes()));
            }
            bytes(3, &payload)
        }
        Record::Thread(thread) => bytes(
            4,
            &[
                number(1, u64::from(thread.thread_id)),
                number(2, u64::from(thread.process_id)),
                bytes(3, thread.name.as_bytes()),
            ]
            .concat(),
        ),
        Record::MetaInfo(meta) => bytes(
            5,
            &meta
                .event_types
                .iter()
                .flat_map(|event| bytes(1, event.as_bytes()))
                .collect::<Vec<_>>(),
        ),
        _ => number(99, 1),
    }
}

pub fn encoded(profile: &SimpleperfStream) -> Vec<u8> {
    let mut stream = MAGIC.to_vec();
    stream.extend(profile.version.to_le_bytes());
    for record in &profile.records {
        let payload = encode_record(record);
        stream.extend((payload.len() as u32).to_le_bytes());
        stream.extend(payload);
    }
    stream.extend(0u32.to_le_bytes());
    stream
}
