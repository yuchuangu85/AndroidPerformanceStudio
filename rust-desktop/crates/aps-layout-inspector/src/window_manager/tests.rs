use super::*;

fn parse(text: &str) -> WindowTypes {
    WindowTypes::parse(text.as_bytes(), &CancellationToken::new()).unwrap()
}

fn record(number: usize, hash: &str, title: &str, attributes: &str) -> String {
    format!("  Window #{number} Window{{{hash} u0 {title}}}:\n    mAttrs={{{attributes}}}\n")
}

#[test]
fn numeric_types_include_oem_values_and_respect_system_and_java_int_boundaries() {
    for (value, expected) in [
        ("2045", Some(2045)),
        ("0x7fd", Some(2045)),
        ("0X7FD", Some(2045)),
        ("1999", None),
        ("2000", Some(2000)),
        ("0", None),
        ("3000", Some(3000)),
        ("2147483647", Some(i32::MAX as u32)),
        ("0x7fffffff", Some(i32::MAX as u32)),
        ("2147483648", None),
        ("4294967295", None),
        ("0x80000000", None),
        ("0xffffffff", None),
    ] {
        let snapshot = parse(&record(0, "abcd", "OEM panel", &format!("ty={value}")));
        assert_eq!(snapshot.system_type("abcd OEM panel"), expected, "{value}");
    }
}

#[test]
fn symbolic_types_are_explicit_and_application_types_stay_non_system() {
    for (symbol, expected) in [
        ("STATUS_BAR", Some(2000)),
        ("NAVIGATION_BAR", Some(2019)),
        ("BASE_APPLICATION", Some(1)),
        ("APPLICATION", Some(2)),
        ("APPLICATION_PANEL", Some(1000)),
        ("NAVIGATION_BAR_PANEL", Some(2024)),
        ("PRIVATE_PRESENTATION", Some(2030)),
        ("SCREENSHOT", Some(2036)),
        ("PRESENTATION", Some(2037)),
        ("STATUS_BAR_ADDITIONAL", Some(2041)),
        ("LAST_SYSTEM_WINDOW", None),
        ("OEM_STATUS_BAR", None),
        ("TYPE_STATUS_BAR", None),
    ] {
        let snapshot = parse(&record(0, "abcd", "Panel", &format!("ty={symbol}")));
        assert_eq!(snapshot.types["abcd Panel"], expected, "{symbol}");
        assert_eq!(
            snapshot.system_type("abcd Panel"),
            expected.filter(|value| *value >= 2000),
            "{symbol}"
        );
    }
}

#[test]
fn exact_zip_identity_preserves_full_title_and_spaces() {
    let snapshot = parse(&record(0, "0aBC", " My  panel / package.Name ", "ty=2000"));
    assert_eq!(
        snapshot.system_type("0aBC  My  panel / package.Name "),
        Some(2000)
    );
    for mismatch in [
        "My  panel / package.Name",
        "0aBC My  panel / package.Name",
        "0abc  My  panel / package.Name ",
        "window:0aBC",
        "0aBC",
    ] {
        assert_eq!(snapshot.system_type(mismatch), None, "{mismatch}");
    }
}

#[test]
fn window_titles_and_other_record_fields_never_supply_types() {
    let snapshot = parse(concat!(
        "WINDOW MANAGER WINDOWS (dumpsys window windows)\n",
        "  Window #0 Window{abc u0 com.android.systemui/StatusBar ty=2000}:\n",
        "    mOwnerUid=1000 ty=2000\n",
        "    mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION}\n",
        "  Window #1 Window{def u0 Missing attrs}:\n",
        "    mOwnerUid=1000 ty=STATUS_BAR\n",
        "      mAttrs={ty=2000}\n",
        "  Window #2 Window{fed u0 Real system window}:\n",
        "    mAttrs={ty=2019}\n",
    ));
    assert_eq!(
        snapshot.system_type("abc com.android.systemui/StatusBar ty=2000"),
        None
    );
    assert_eq!(snapshot.system_type("def Missing attrs"), None);
    assert_eq!(snapshot.system_type("fed Real system window"), Some(2019));
}

#[test]
fn wrapped_attributes_and_nested_values_are_scoped_to_their_record() {
    let snapshot = parse(concat!(
        "  Window #0 Window{abc u12 Wrapped panel}:\n",
        "    mAttrs={(0,0)(fillxfill) sim={adjust=pan ty=1}\n",
        "      ty=STATUS_BAR fmt=TRANSLUCENT\n",
        "      fl=NOT_FOCUSABLE}\n",
        "  Window #1 Window{def u0 Incomplete}:\n",
        "    mAttrs={sim={adjust=pan}\n",
        "    mOwnerUid=1000 ty=2000}\n",
        "  Window #2 Window{fed u0 Following}:\n",
        "    mAttrs={ty=2019}\n",
    ));
    assert_eq!(snapshot.system_type("abc Wrapped panel"), Some(2000));
    assert_eq!(snapshot.system_type("def Incomplete"), None);
    assert_eq!(snapshot.system_type("fed Following"), Some(2019));
}

#[test]
fn missing_unknown_malformed_and_duplicate_types_fail_closed() {
    for attributes in [
        "",
        "fl=NOT_FOCUSABLE",
        "ty=UNKNOWN",
        "ty=",
        "ty= 2000",
        "ty=-2000",
        "ty=+2000",
        "ty=0x",
        "ty=0xggg",
        "ty=2000x",
        "ty=4294967296",
        "ty=0x100000000",
        "ty=2000 ty=1",
        "ty=1 ty=2000",
        "ty=2000 ty=2000",
        "ty=UNKNOWN ty=2000",
        "ty=2000 ty=UNKNOWN",
        "sim={ty=2000}",
        "notty=2000",
    ] {
        let snapshot = parse(&record(0, "abc", "StatusBar", attributes));
        assert_eq!(snapshot.system_type("abc StatusBar"), None, "{attributes}");
    }
    for text in [
        "  Window #0 Window{abc u0 StatusBar}:\n",
        "  Window #0 Window{abc u0 StatusBar}:\n    mAttrs={ty=2000\n",
        "  Window #0 Window{abc u0 StatusBar}:\n    mAttrs=ty=2000\n",
        "  Window #0 Window{abc u0 StatusBar}:\n    mAttrs={ty=2000} junk\n",
        "  Window #0 Window{abc u0 StatusBar}:\n    mAttrs={ty=2000}\n    mAttrs={ty=2000}\n",
    ] {
        assert_eq!(parse(text).system_type("abc StatusBar"), None, "{text}");
    }
}

#[test]
fn duplicate_identities_never_recover_positive_evidence() {
    for (first, second) in [
        ("ty=1", "ty=2000"),
        ("ty=2000", "ty=1"),
        ("ty=2000", "ty=2000"),
        ("", "ty=2000"),
        ("ty=2000", ""),
    ] {
        let text = format!(
            "{}{}{}",
            record(0, "abc", "Panel", first),
            record(1, "abc", "Panel", second),
            record(2, "abc", "Panel", "ty=2000")
        );
        let snapshot = parse(&text);
        assert_eq!(snapshot.types.get("abc Panel"), Some(&None));
        assert_eq!(snapshot.system_type("abc Panel"), None);
    }
}

#[test]
fn stable_system_windows_requires_unchanged_exact_name_and_numeric_type() {
    let before = parse(&format!(
        "{}{}{}{}{}{}",
        record(0, "a", "Stable", "ty=STATUS_BAR"),
        record(1, "b", "Changed type", "ty=2019"),
        record(2, "c", "Old title", "ty=2045"),
        record(3, "d", "Gone", "ty=2000"),
        record(4, "e", "Application", "ty=1"),
        record(5, "f", "Unknown", "")
    ));
    let after = parse(&format!(
        "{}{}{}{}{}{}",
        record(0, "a", "Stable", "ty=0x7d0"),
        record(1, "b", "Changed type", "ty=2000"),
        record(2, "c", "New title", "ty=2045"),
        record(3, "d0", "New", "ty=2000"),
        record(4, "e", "Application", "ty=1"),
        record(5, "f", "Unknown", "ty=2000")
    ));
    let stable = before.stable_system_windows(&after);
    assert_eq!(stable.types.len(), 1);
    assert_eq!(stable.system_type("a Stable"), Some(2000));
    assert_eq!(after.stable_system_windows(&before), stable);
    assert_eq!(
        stable.stable_system_windows(&WindowTypes::default()),
        WindowTypes::default()
    );
}

#[test]
fn exiting_windows_never_include_the_marker_in_a_positive_identity() {
    let snapshot = parse(&record(0, "abc", "Panel EXITING", "ty=2000"));
    assert_eq!(snapshot.system_type("abc Panel"), None);
    assert_eq!(snapshot.system_type("abc Panel EXITING"), None);
    let duplicate = parse(&format!(
        "{}{}",
        record(0, "abc", "Panel", "ty=2000"),
        record(1, "abc", "Panel EXITING", "ty=2000")
    ));
    assert_eq!(duplicate.system_type("abc Panel"), None);
}

#[test]
fn nested_records_and_other_dump_sections_do_not_supply_attributes() {
    let snapshot = parse(concat!(
        "  Window #0 Window{abc u0 Missing}:\n",
        "    Window #1 Window{def u0 Nested}:\n",
        "      mAttrs={ty=2000}\n",
        "  Other section:\n",
        "    mAttrs={ty=2000}\n",
    ));
    assert_eq!(snapshot.system_type("abc Missing"), None);
    assert_eq!(snapshot.system_type("def Nested"), None);
}

#[test]
fn malformed_headers_fail_instead_of_borrowing_another_record() {
    for header in [
        "Window #x Window{abc u0 Panel}:",
        "Window #0 Window{not-hex u0 Panel}:",
        "Window #0 Window{abc user0 Panel}:",
        "Window #0 Window{abc u-1 Panel}:",
        "Window #0 Window{abc u0 Panel}",
        "Window #0 Window{abc u0 }:",
        "Window #0 Window{abc u0 Panel}: junk",
    ] {
        let text = format!("  {header}\n    mAttrs={{ty=2000}}\n");
        assert!(
            WindowTypes::parse(text.as_bytes(), &CancellationToken::new()).is_err(),
            "{header}"
        );
    }
}

#[test]
fn input_record_and_name_limits_are_enforced_at_the_boundary() {
    let cancel = CancellationToken::new();
    assert!(WindowTypes::parse(&vec![b' '; MAX_DUMP_BYTES], &cancel).is_ok());
    assert!(WindowTypes::parse(&vec![b' '; MAX_DUMP_BYTES + 1], &cancel).is_err());

    let title = "x".repeat(MAX_NAME_BYTES - 4);
    let exact_name = format!("abc {title}");
    assert_eq!(
        parse(&record(0, "abc", &title, "ty=2000")).system_type(&exact_name),
        Some(2000)
    );
    assert!(
        WindowTypes::parse(
            record(0, "abc", &(title + "x"), "ty=2000").as_bytes(),
            &cancel
        )
        .is_err()
    );

    let mut text = String::new();
    for index in 0..MAX_RECORDS {
        text.push_str(&record(index, &format!("{index:x}"), "Panel", "ty=2000"));
    }
    assert_eq!(parse(&text).types.len(), MAX_RECORDS);
    text.push_str(&record(MAX_RECORDS, "abcd", "Panel", "ty=2000"));
    assert!(WindowTypes::parse(text.as_bytes(), &cancel).is_err());
}

#[test]
fn utf8_is_strict_and_cancellation_applies_even_to_empty_input() {
    assert!(WindowTypes::parse(&[0xff], &CancellationToken::new()).is_err());
    assert_eq!(parse(""), WindowTypes::default());
    let cancel = CancellationToken::new();
    cancel.cancel();
    let error =
        WindowTypes::parse(record(0, "abc", "Panel", "ty=2000").as_bytes(), &cancel).unwrap_err();
    assert!(error.contains("cancelled"));
    assert!(WindowTypes::parse(&[], &cancel).is_err());
}

#[test]
fn unindented_records_and_crlf_are_supported_without_normalizing_names() {
    let snapshot = parse("Window #0 Window{abc u0 Panel}:\r\n  mAttrs={ty=2000}\r\n");
    assert_eq!(snapshot.system_type("abc Panel"), Some(2000));
}
