use aps_simpleperf_engine::wire::{decode, read_frame, write_frame, Value, VERSION};

#[test]
fn v1_wire_keeps_the_existing_framed_bytes() {
    let value = Value::map([("v", VERSION.into())]);
    // Frame length, map count, UTF-8 key, signed big-endian integer.
    let bytes: &[u8] = &[
        0, 0, 0, 19, 6, 0, 0, 0, 1, 0, 0, 0, 1, b'v', 3, 0, 0, 0, 0, 0, 0, 0, 1,
    ];
    let mut encoded = Vec::new();
    write_frame(&mut encoded, &value).unwrap();
    assert_eq!(encoded, bytes);
    let frame = read_frame(&mut &bytes[..]).unwrap().unwrap();
    assert_eq!(decode(&frame).unwrap(), value);
}
