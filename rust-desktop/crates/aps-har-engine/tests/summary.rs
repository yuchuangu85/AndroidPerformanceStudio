use aps_har_engine::{summarize, summarize_with_entry_limit};
use std::io::{self, Read};

const HAR: &str = r#"{
  "log": {
    "version": "1.2",
    "creator": {"name": "fixture"},
    "entries": [
      {
        "startedDateTime": "2026-01-01T00:00:00Z",
        "time": 30,
        "request": {"method": "GET", "url": "https://example.test/a", "bodySize": 12},
        "response": {"status": 200, "httpVersion": "HTTP/2", "bodySize": 100, "content": {"size": 120}},
        "timings": {"blocked": 1, "dns": -1, "connect": 10, "ssl": 5, "send": 1, "wait": 10, "receive": 8}
      },
      {
        "startedDateTime": "2026-01-01T00:00:01Z",
        "time": 240,
        "request": {"method": "POST", "url": "https://example.test/b", "bodySize": -1},
        "response": {"status": 503, "httpVersion": "HTTP/1.1", "bodySize": 20},
        "timings": {"blocked": 0, "dns": 2, "connect": 3, "ssl": -1, "send": 1, "wait": 200, "receive": 34}
      }
    ]
  }
}"#;

#[test]
fn summarizes_core_fields_phases_and_slowest_requests() {
    let summary = summarize(HAR.as_bytes()).expect("fixture should parse");
    assert_eq!(summary.protocol_version, 1);
    assert_eq!(summary.har_version, "1.2");
    assert_eq!(summary.entry_count, 2);
    assert_eq!(summary.status_families["2xx"], 1);
    assert_eq!(summary.status_families["5xx"], 1);
    assert_eq!(summary.request_bytes.total_bytes, 12);
    assert_eq!(summary.request_bytes.unknown_count, 1);
    assert_eq!(summary.response_bytes.total_bytes, 120);
    assert_eq!(summary.decoded_response_bytes.total_bytes, 120);
    assert_eq!(summary.durations.mean_ms, Some(135.0));

    let ssl = summary
        .phases
        .iter()
        .find(|phase| phase.kind == "ssl")
        .unwrap();
    assert_eq!(ssl.parent_kind, Some("connect"));
    assert_eq!(ssl.durations.sample_count, 1);
    assert_eq!(ssl.durations.missing_count, 1);
    assert_eq!(summary.slowest_requests[0].entry_index, 1);
    assert_eq!(summary.slowest_requests[0].method, "POST");

    let output = summary.to_json();
    assert!(output.contains("\"protocolVersion\":1"));
    assert!(output.contains("\"slowestRequests\""));
    assert!(!output.contains("example.test"));
}

struct ChunkedReader {
    bytes: Vec<u8>,
    offset: usize,
    max_chunk: usize,
}
impl Read for ChunkedReader {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if self.offset == self.bytes.len() {
            return Ok(0);
        }
        let count = (self.bytes.len() - self.offset)
            .min(self.max_chunk)
            .min(buffer.len());
        buffer[..count].copy_from_slice(&self.bytes[self.offset..self.offset + count]);
        self.offset += count;
        Ok(count)
    }
}

#[test]
fn reads_incrementally_from_short_reads() {
    let reader = ChunkedReader {
        bytes: HAR.as_bytes().to_vec(),
        offset: 0,
        max_chunk: 3,
    };
    assert_eq!(summarize(reader).unwrap().entry_count, 2);
}

#[test]
fn rejects_malformed_truncated_duplicate_and_trailing_json() {
    for input in [
        r#"{"log":{"version":"1.0","entries":[]}}"#,
        r#"{"log":{"version":"1.2","entries":[{"request":}]}}"#,
        r#"{"log":{"version":"1.2","version":"1.2","entries":[]}}"#,
        r#"{"log":{"version":"1.2","entries":[]}} {}"#,
        r#"{"log":{"version":"1.2","entries":[{"startedDateTime":"x","request":{"method":"GET","url":"u"},"response":{"status":200,"bodySize":-2}}]}}"#,
    ] {
        assert!(
            summarize(input.as_bytes()).is_err(),
            "accepted malformed input: {input}"
        );
    }
}

#[test]
fn enforces_entry_and_nesting_budgets() {
    assert!(summarize_with_entry_limit(HAR.as_bytes(), 1).is_err());
    assert!(summarize_with_entry_limit(HAR.as_bytes(), 0).is_err());
    let mut nested = String::from(
        r#"{"log":{"version":"1.2","entries":[{"startedDateTime":"x","request":{"method":"GET","url":"u"},"response":{"status":200},"timings":{"x":"#,
    );
    nested.push_str(&"[".repeat(70));
    nested.push('0');
    nested.push_str(&"]".repeat(70));
    nested.push_str(r#"}}]}}"#);
    assert!(summarize(nested.as_bytes()).is_err());
}

#[test]
fn rejects_oversized_entry_string() {
    let value = "x".repeat(256 * 1024 + 1);
    let har = format!(
        r#"{{"log":{{"version":"1.2","entries":[{{"startedDateTime":"{}","request":{{"method":"GET","url":"u"}},"response":{{"status":200}}}}]}}}}"#,
        value
    );
    assert!(summarize(har.as_bytes()).is_err());
}

#[test]
fn rejects_reader_errors() {
    struct Broken;
    impl Read for Broken {
        fn read(&mut self, _: &mut [u8]) -> io::Result<usize> {
            Err(io::Error::other("broken"))
        }
    }
    assert!(summarize(Broken).is_err());
}

#[test]
fn top_ten_order_is_deterministic_and_invalid_timings_stay_separate() {
    let entry = r#"{"startedDateTime":"x","time":10,"request":{"method":"GET","url":"secret"},"response":{"status":0},"timings":{"wait":-2}}"#;
    let har = format!(
        r#"{{"log":{{"entries":[{}],"version":"1.1"}}}}"#,
        vec![entry; 20].join(",")
    );
    let summary = summarize(har.as_bytes()).unwrap();
    assert_eq!(summary.entry_count, 20);
    assert_eq!(summary.slowest_requests.len(), 10);
    assert_eq!(
        summary
            .slowest_requests
            .iter()
            .map(|request| request.entry_index)
            .collect::<Vec<_>>(),
        (0..10).collect::<Vec<_>>()
    );
    assert_eq!(summary.phases[5].durations.invalid_count, 20);
    assert_eq!(summary.phases[5].durations.mean_ms, None);
    assert_eq!(summary.status_families["unknown"], 20);
}
