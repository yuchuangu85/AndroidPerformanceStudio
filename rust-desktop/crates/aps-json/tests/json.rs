//! Deferred tests: do not run before complete migration implementation.
use aps_json::{JsonError, Limits, Value, parse_slice, to_vec};

#[test]
fn arrays_booleans_and_unicode_are_preserved() {
    let value = parse_slice(
        br#"{"array":[true,false,null,1,"\u4e2d\u6587"]}"#,
        Limits::default(),
    )
    .unwrap();
    assert_eq!(value["array"][0], Value::Bool(true));
    assert_eq!(value["array"][4].as_str(), Some("中文"));
    let bytes = to_vec(&value, Limits::default()).unwrap();
    assert_eq!(parse_slice(&bytes, Limits::default()).unwrap(), value);
}

#[test]
fn duplicate_keys_trailing_data_and_invalid_unicode_are_rejected() {
    for input in [br#"{"a":1,"a":2}"#.as_slice(), br#"{} {}"#, br#""\ud800""#] {
        assert!(parse_slice(input, Limits::default()).is_err());
    }
}

#[test]
fn input_and_structure_budgets_apply_during_decode() {
    assert_eq!(
        parse_slice(
            b"[1,2,3]",
            Limits {
                max_bytes: 2,
                ..Limits::default()
            }
        ),
        Err(JsonError::TooLarge)
    );
    assert!(
        parse_slice(
            b"[[[1]]]",
            Limits {
                max_depth: 1,
                ..Limits::default()
            }
        )
        .is_err()
    );
    assert!(
        parse_slice(
            b"[1,2,3]",
            Limits {
                max_nodes: 2,
                ..Limits::default()
            }
        )
        .is_err()
    );
    assert!(
        parse_slice(
            br#""oversized""#,
            Limits {
                max_string_bytes: 2,
                ..Limits::default()
            }
        )
        .is_err()
    );
    assert!(
        parse_slice(
            b"[1,2,3]",
            Limits {
                max_collection_items: 2,
                ..Limits::default()
            }
        )
        .is_err()
    );
}

#[test]
fn serialization_does_not_exceed_the_output_budget() {
    let value = Value::String("text".into());
    assert_eq!(
        to_vec(
            &value,
            Limits {
                max_bytes: 2,
                ..Limits::default()
            }
        ),
        Err(JsonError::TooLarge)
    );
}
