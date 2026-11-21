use aps_simpleperf_engine::simpleperf::{parse_stream, Record, MAGIC};

fn stream(payload: &[u8]) -> Vec<u8> {
    let mut bytes = MAGIC.to_vec();
    bytes.extend(1u16.to_le_bytes());
    if !payload.is_empty() {
        bytes.extend((payload.len() as u32).to_le_bytes());
        bytes.extend(payload);
    }
    bytes.extend(0u32.to_le_bytes());
    bytes
}

#[test]
fn consumes_unknown_fields_after_known_oneof_value() {
    // Empty Sample, followed by valid unknown varint/fixed64/bytes/fixed32.
    let parsed = parse_stream(&stream(&[
        0x0a, 0, 0x38, 1, 0x41, 0, 0, 0, 0, 0, 0, 0, 0, 0x4a, 1, b'x', 0x55, 0, 0, 0, 0,
    ]))
    .unwrap();
    assert!(matches!(parsed.records[0], Record::Sample(_)));
}

#[test]
fn rejects_malformed_fields_after_a_known_record_value() {
    for tail in [
        vec![0x38],
        vec![0],
        vec![0x4a, 2, b'x'],
        vec![0x12, 1, 0x80],
    ] {
        let mut payload = vec![0x0a, 0];
        payload.extend(tail);
        assert!(parse_stream(&stream(&payload)).is_err());
    }
}

#[test]
fn last_oneof_value_wins_even_with_unknown_fields_between_values() {
    let parsed = parse_stream(&stream(&[0x0a, 0, 0x38, 1, 0x12, 4, 8, 3, 16, 5])).unwrap();
    assert_eq!(
        parsed.records,
        vec![Record::Lost {
            sample_count: 3,
            lost_count: 5
        }]
    );
    let parsed = parse_stream(&stream(&[0x12, 0, 0x0a, 2, 0x20, 9])).unwrap();
    assert!(matches!(&parsed.records[0], Record::Sample(sample) if sample.event_count == 9));
    let parsed = parse_stream(&stream(&[0x0a, 2, 0x20, 1, 0x0a, 2, 0x20, 9])).unwrap();
    assert!(matches!(&parsed.records[0], Record::Sample(sample) if sample.event_count == 9));
}

#[test]
fn rejects_any_bytes_after_terminator() {
    for payload in [vec![], vec![0x0a, 0]] {
        let valid = stream(&payload);
        assert!(parse_stream(&valid).is_ok());
        for tail in [vec![0], vec![0, 0, 0, 0], stream(&[])] {
            let mut bytes = valid.clone();
            bytes.extend(tail);
            assert!(parse_stream(&bytes).unwrap_err().0.contains("terminator"));
        }
    }
}

#[test]
fn rejects_the_513th_frame_before_decoding_or_pushing_it() {
    // Repeated empty CallChainEntry messages; encode enclosing Sample length.
    let mut sample = [0x1a, 0].repeat(512);
    let mut payload = vec![0x0a, 0x80, 0x08]; // 1024-byte Sample
    payload.extend(&sample);
    let parsed = parse_stream(&stream(&payload)).unwrap();
    assert!(matches!(&parsed.records[0], Record::Sample(sample) if sample.callchain.len() == 512));
    // The 513th entry is malformed. The cap must win before its length is read.
    sample.push(0x1a);
    let mut payload = vec![0x0a, 0x81, 0x08]; // 1025-byte Sample
    payload.extend(sample);
    assert!(parse_stream(&stream(&payload))
        .unwrap_err()
        .0
        .contains("512 frames"));
}
