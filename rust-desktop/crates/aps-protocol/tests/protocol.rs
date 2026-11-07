use aps_contracts::{ApsError, ErrorCode, ProfilerSessionId, RequestId, SourceDigest};
use aps_protocol::*;
fn request() -> Request {
    Request::new(
        RequestId::new("request:1").unwrap(),
        SourceDigest::from_bytes([42; 32]),
        ProfilerSessionId::new("session:1").unwrap(),
        7,
        "cpu.page",
        Value::map([("offset", Value::Int(0)), ("limit", Value::Int(10))]),
    )
    .unwrap()
}
#[test]
fn framed_request_response_roundtrip_preserves_every_identity_field() {
    let request = request();
    let limits = FrameLimits::default();
    let mut bytes = Vec::new();
    write_request(&mut bytes, &request, limits).unwrap();
    let decoded = read_request(&mut bytes.as_slice(), limits)
        .unwrap()
        .unwrap();
    assert_eq!(decoded, request);
    let response = Response::ok(&request, Value::map([("rows", Value::List(Vec::new()))])).unwrap();
    bytes.clear();
    write_response(&mut bytes, &response, limits).unwrap();
    let decoded = read_response(&mut bytes.as_slice(), limits)
        .unwrap()
        .unwrap();
    assert_eq!(decoded, response);
    decoded.validate_for(&request).unwrap();
    assert!(read_request(&mut [].as_slice(), limits).unwrap().is_none());
}
#[test]
fn unknown_missing_wrong_typed_and_stale_envelope_fields_are_rejected() {
    let request = request();
    let limits = FrameLimits::default();
    let mut value = request.to_value();
    if let Value::Map(map) = &mut value {
        map.insert("extra".into(), Value::Null);
    }
    assert!(Request::from_value(&value, limits).is_err());
    let mut value = request.to_value();
    if let Value::Map(map) = &mut value {
        map.remove("args");
    }
    assert!(Request::from_value(&value, limits).is_err());
    for (key, value) in [
        ("protocolVersion", Value::Int(2)),
        ("generation", Value::Int(-1)),
        ("requestId", Value::Int(1)),
        ("args", Value::List(Vec::new())),
    ] {
        let mut modified = request.to_value();
        if let Value::Map(map) = &mut modified {
            map.insert(key.into(), value);
        }
        assert!(Request::from_value(&modified, limits).is_err());
    }
    let good = Response::ok(&request, Value::Null).unwrap();
    let mut modified = good.clone();
    modified.generation += 1;
    assert!(modified.validate_for(&request).is_err());
    let mut modified = good.clone();
    modified.source_digest = SourceDigest::from_bytes([0; 32]);
    assert!(modified.validate_for(&request).is_err());
    let mut modified = good.clone();
    modified.session_id = ProfilerSessionId::new("other").unwrap();
    assert!(modified.validate_for(&request).is_err());
    let mut modified = good;
    modified.request_id = RequestId::new("other").unwrap();
    assert!(modified.validate_for(&request).is_err());
}
#[test]
fn frame_length_is_rejected_before_body_allocation_and_truncation_is_not_clean_eof() {
    let limits = FrameLimits {
        max_frame_bytes: 256,
        ..FrameLimits::default()
    };
    assert!(read_request(&mut 257u32.to_be_bytes().as_slice(), limits).is_err());
    assert!(read_request(&mut [0, 0].as_slice(), limits).is_err());
    assert!(read_request(&mut [0, 0, 0, 5, 1].as_slice(), limits).is_err());
    let tiny = FrameLimits {
        max_frame_bytes: 32,
        ..FrameLimits::default()
    };
    let mut output = Vec::new();
    assert!(write_request(&mut output, &request(), tiny).is_err());
    assert!(output.is_empty());
    assert!(encode_value(
        &Value::String("a".repeat(20)),
        FrameLimits {
            max_string_bytes: 10,
            ..FrameLimits::default()
        }
    )
    .is_err());
    assert!(encode_value(
        &Value::List(vec![Value::Null; 10]),
        FrameLimits {
            max_values: 5,
            ..FrameLimits::default()
        }
    )
    .is_err());
    assert!(encode_value(
        &Value::List(vec![Value::List(vec![Value::Null])]),
        FrameLimits {
            max_depth: 1,
            ..FrameLimits::default()
        }
    )
    .is_err());
}
#[test]
fn duplicate_raw_wire_keys_and_trailing_values_are_rejected() {
    // map(tag6,count2), same key encoded twice; codec rejects duplicate keys.
    let bytes = [6, 0, 0, 0, 2, 0, 0, 0, 1, b'x', 0, 0, 0, 0, 1, b'x', 0];
    assert!(decode_value(&bytes, FrameLimits::default()).is_err());
    assert!(decode_value(&[0, 0], FrameLimits::default()).is_err());
}
#[test]
fn cancellation_is_cooperative_and_terminal_states_cannot_be_reopened() {
    let state = CancellationState::new();
    let worker = state.clone();
    state.start().unwrap();
    worker.request_cancel().unwrap();
    assert_eq!(state.phase(), CancellationPhase::CancelRequested);
    assert!(state.complete().is_err());
    worker.acknowledge_cancelled().unwrap();
    assert_eq!(state.phase(), CancellationPhase::Cancelled);
    assert!(state.start().is_err());
    let done = CancellationState::new();
    done.start().unwrap();
    done.complete().unwrap();
    assert!(done.request_cancel().is_err());
}
#[test]
fn generation_guard_rejects_replays_and_requires_strict_advancement() {
    let request = request();
    let mut guard = GenerationGuard::new(
        request.session_id.clone(),
        request.source_digest,
        request.generation,
    )
    .unwrap();
    guard.accept(&request).unwrap();
    assert!(guard.accept(&request).is_err());
    assert!(guard.advance(7).is_err());
    guard.advance(8).unwrap();
    assert!(guard.accept(&request).is_err());
    let mut next = request;
    next.generation = 8;
    guard.accept(&next).unwrap();
}
#[test]
fn response_error_shapes_and_strict_operation_schema_do_not_imply_authorization() {
    let request = request();
    let error = ApsError::new(ErrorCode::ResourceLimit, "Output budget exceeded", false).unwrap();
    let response = Response::error(&request, error).unwrap();
    response.validate_for(&request).unwrap();
    let cancelled = Response::cancelled(&request).unwrap();
    assert_eq!(cancelled.status, ResponseStatus::Cancelled);
    let mut invalid = cancelled;
    invalid.payload = Value::Bool(true);
    assert!(invalid.validate(FrameLimits::default()).is_err());
    let schema = OperationSchema {
        operation: "cpu.page".into(),
        fields: vec![
            ArgumentField {
                name: "offset".into(),
                kind: ArgumentKind::Int,
                required: true,
                nullable: false,
            },
            ArgumentField {
                name: "limit".into(),
                kind: ArgumentKind::Int,
                required: true,
                nullable: false,
            },
        ],
    };
    schema
        .validate_request(&request, FrameLimits::default())
        .unwrap();
    let mut invalid = request.clone();
    invalid.args = Value::map([
        ("offset", Value::Int(0)),
        ("limit", Value::Int(10)),
        ("permission", Value::Bool(true)),
    ]);
    assert!(schema
        .validate_request(&invalid, FrameLimits::default())
        .is_err());
    let mut invalid = request;
    invalid.args = Value::map([
        ("offset", Value::Int(0)),
        ("limit", Value::String("10".into())),
    ]);
    assert!(schema
        .validate_request(&invalid, FrameLimits::default())
        .is_err());
}
