use aps_har_engine::stream_entries;
use std::io::Write;
use std::process::{Command, Stdio};

#[test]
fn entry_transport_keeps_original_headers_bodies_arrays_and_metadata_order() {
    let entry = "{\n\"request\":{\"headers\":[{\"name\":\"Authorization\",\"value\":\"secret\"}],\"postData\":{\"text\":\"body\"}}}";
    let input = format!("{{\"log\":{{\"entries\":[{entry},null],\"creator\":{{\"name\":\"fixture\"}},\"version\":\"1.2\"}}}}");
    let mut output = Vec::new();
    stream_entries(input.as_bytes(), &mut output).unwrap();
    let text = String::from_utf8(output).unwrap();
    assert!(text.starts_with("APS-HAR-ENTRIES\t1\n"));
    assert!(text.contains(&format!("entry\t0\t{}\n{entry}\n", entry.len())));
    assert!(text.contains("entry\t1\t4\nnull\n"));
    assert!(text.ends_with("version\t3\n1.2\nend\t2\n"));
}

#[test]
fn syntax_budgets_duplicates_truncation_and_trailing_data_never_commit() {
    let valid = br#"{"log":{"version":"1.2","entries":[{},null]}}"#;
    for end in 0..valid.len() {
        let mut output = Vec::new();
        assert!(stream_entries(&valid[..end], &mut output).is_err());
        assert!(!output.ends_with(b"end\t2\n"));
    }
    for input in [
        "{\"log\":{\"version\":\"1.2\",\"entries\":[],\"entries\":[]}}".to_owned(),
        "{\"log\":{\"version\":\"9.9\",\"entries\":[]}}".to_owned(),
        "{\"log\":{\"entries\":[]}}".to_owned(),
        "{\"log\":{\"version\":\"1.2\",\"entries\":[]}} {}".to_owned(),
        format!(
            "{{\"log\":{{\"version\":\"1.2\",\"entries\":[\"{}\"]}}}}",
            "s".repeat(256 * 1024)
        ),
    ] {
        let mut output = Vec::new();
        assert!(stream_entries(input.as_bytes(), &mut output).is_err());
        assert!(!String::from_utf8(output)
            .unwrap()
            .lines()
            .any(|line| line.starts_with("end\t")));
    }
}

#[test]
fn cli_streams_semantically_bad_entries_without_weakening_summary() {
    let input = br#"{"log":{"version":"1.2","entries":[{"request":{}},null]}}"#;
    for (command, success) in [("stream-entries", true), ("summarize", false)] {
        let mut child = Command::new(env!("CARGO_BIN_EXE_aps-har-engine"))
            .args([command, "-"])
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        child.stdin.take().unwrap().write_all(input).unwrap();
        let result = child.wait_with_output().unwrap();
        assert_eq!(result.status.success(), success);
        if success {
            assert!(result.stdout.ends_with(b"end\t2\n"));
            assert!(result.stderr.is_empty());
        } else {
            assert!(result.stdout.is_empty());
        }
    }
}

#[test]
fn broken_consumer_is_an_error() {
    let input = br#"{"log":{"version":"1.2","entries":[]}}"#;
    assert!(stream_entries(input.as_slice(), std::io::Cursor::new(&mut [0u8; 8][..])).is_err());
}
