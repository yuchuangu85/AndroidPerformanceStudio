use aps_hprof_engine::{
    serve,
    wire::{self, Value},
};
use std::io::Cursor;

#[test]
fn round_trips_all_types_exact_signed_ids_and_utf8() {
    let value = Value::map([
        ("null", Value::Null),
        ("true", true.into()),
        ("false", false.into()),
        (
            "ids",
            Value::List(vec![i64::MIN.into(), i64::MAX.into(), (-1i64).into()]),
        ),
        ("text", "\u{6d4b}\u{8bd5}\0\u{1f642}".into()),
        ("empty", Value::map([])),
    ]);
    let encoded = wire::encode(&value).unwrap();
    assert_eq!(wire::decode(&encoded).unwrap(), value);
    for prefix in 0..encoded.len() {
        assert!(wire::decode(&encoded[..prefix]).is_err());
    }
    let mut trailing = encoded;
    trailing.push(0);
    assert!(wire::decode(&trailing).is_err());
}

#[test]
fn rejects_duplicate_keys_invalid_utf8_tags_and_hostile_counts() {
    let duplicate = [6, 0, 0, 0, 2, 0, 0, 0, 1, b'x', 0, 0, 0, 0, 1, b'x', 0];
    assert!(wire::decode(&duplicate).unwrap_err().contains("duplicate"));
    for bytes in [
        vec![4, 0, 0, 0, 1, 255],
        vec![255],
        vec![5, 255, 255, 255, 255],
        vec![6, 255, 255, 255, 255],
    ] {
        assert!(wire::decode(&bytes).is_err());
    }
}

#[test]
fn nesting_and_output_limits_are_explicit() {
    let mut value = Value::Null;
    for _ in 0..wire::MAX_DEPTH {
        value = Value::List(vec![value]);
    }
    assert_eq!(wire::decode(&wire::encode(&value).unwrap()).unwrap(), value);
    value = Value::List(vec![value]);
    assert!(wire::encode(&value).is_err());
    let mut bytes = vec![];
    for _ in 0..=wire::MAX_DEPTH {
        bytes.extend([5, 0, 0, 0, 1]);
    }
    bytes.push(0);
    assert!(wire::decode(&bytes).is_err());
    let value = Value::String("x".repeat(wire::MAX_FRAME));
    let mut output = Vec::new();
    assert!(wire::write_frame(&mut output, &value).is_err());
    assert!(output.is_empty());
    let value = Value::List(
        (0..9)
            .map(|_| Value::String("x".repeat(wire::MAX_STRING)))
            .collect(),
    );
    assert!(wire::encode(&value).unwrap_err().contains("32 MiB"));
}

#[test]
fn framing_distinguishes_clean_eof_truncation_and_oversize() {
    assert_eq!(wire::read_frame(&mut Cursor::new(vec![])).unwrap(), None);
    for bytes in [
        vec![0],
        vec![0, 0, 0, 2, 0],
        vec![0, 0, 0, 0],
        ((wire::MAX_FRAME + 1) as u32).to_be_bytes().to_vec(),
    ] {
        assert!(wire::read_frame(&mut Cursor::new(bytes)).is_err());
    }
    let mut stream = Vec::new();
    wire::write_frame(&mut stream, &Value::Int(-1)).unwrap();
    wire::write_frame(&mut stream, &Value::Null).unwrap();
    let mut reader = Cursor::new(stream);
    assert_eq!(
        wire::decode(&wire::read_frame(&mut reader).unwrap().unwrap()).unwrap(),
        Value::Int(-1)
    );
    assert_eq!(
        wire::decode(&wire::read_frame(&mut reader).unwrap().unwrap()).unwrap(),
        Value::Null
    );
    assert!(wire::read_frame(&mut reader).unwrap().is_none());
}

#[test]
fn malformed_requests_produce_correlated_errors_and_close_flushes() {
    let mut input = Vec::new();
    for value in [
        Value::map([
            ("version", 2i64.into()),
            ("id", i64::MIN.into()),
            ("op", "histogram".into()),
            ("args", Value::map([])),
        ]),
        Value::map([
            ("version", 1i64.into()),
            ("id", 7i64.into()),
            ("op", "identity".into()),
            ("args", Value::map([])),
        ]),
        Value::map([
            ("version", 1i64.into()),
            ("id", 8i64.into()),
            ("op", "close".into()),
            ("args", Value::map([])),
        ]),
    ] {
        wire::write_frame(&mut input, &value).unwrap();
    }
    let mut output = Vec::new();
    serve(&mut Cursor::new(input), &mut output).unwrap();
    let mut reader = Cursor::new(output);
    for (id, status) in [(i64::MIN, "error"), (7, "error"), (8, "ok")] {
        let response = wire::decode(&wire::read_frame(&mut reader).unwrap().unwrap()).unwrap();
        assert_eq!(response.get("id").unwrap().as_int().unwrap(), id);
        assert_eq!(response.get("status").unwrap().as_str().unwrap(), status);
    }
    assert!(wire::read_frame(&mut reader).unwrap().is_none());
}

#[test]
fn arbitrary_small_payloads_never_panic() {
    let mut seed = 0x123456789abcdefu64;
    for n in 0..10_000 {
        let bytes: Vec<_> = (0..n % 97)
            .map(|_| {
                seed ^= seed << 13;
                seed ^= seed >> 7;
                seed ^= seed << 17;
                seed as u8
            })
            .collect();
        let _ = wire::decode(&bytes);
    }
}
