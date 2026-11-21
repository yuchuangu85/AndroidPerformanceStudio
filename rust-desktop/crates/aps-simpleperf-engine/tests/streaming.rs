mod common;

use aps_simpleperf_engine::simpleperf::{
    parse_reader_with_limit, parse_stream, MAGIC, MAX_RECORD_BYTES,
};
use common::{encoded, profile};
use std::io::{self, Cursor, Read};

struct Chunked<'a> {
    bytes: &'a [u8],
    max_request: usize,
    read: usize,
    interrupt: bool,
}

impl Read for Chunked<'_> {
    fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
        if self.interrupt {
            self.interrupt = false;
            return Err(io::ErrorKind::Interrupted.into());
        }
        self.max_request = self.max_request.max(output.len());
        let length = output.len().min(3).min(self.bytes.len());
        output[..length].copy_from_slice(&self.bytes[..length]);
        self.bytes = &self.bytes[length..];
        self.read += length;
        Ok(length)
    }
}

#[test]
fn fragmented_reads_and_interruption_match_the_slice_parser() {
    let bytes = encoded(&profile());
    let mut reader = Chunked {
        bytes: &bytes,
        max_request: 0,
        read: 0,
        interrupt: true,
    };
    assert_eq!(
        parse_reader_with_limit(&mut reader, bytes.len()).unwrap(),
        parse_stream(&bytes).unwrap()
    );
    assert_eq!(reader.read, bytes.len());
    assert!(reader.max_request < bytes.len());
}

#[test]
fn every_truncated_prefix_errors_instead_of_returning_partial_records() {
    let bytes = encoded(&profile());
    for length in 0..bytes.len() {
        assert!(
            parse_reader_with_limit(&mut Cursor::new(&bytes[..length]), bytes.len()).is_err(),
            "accepted prefix {length}"
        );
    }
    assert!(parse_reader_with_limit(&mut Cursor::new(&bytes), bytes.len()).is_ok());
}

#[test]
fn input_cap_is_checked_before_allocating_or_reading_a_record_payload() {
    let bytes = encoded(&profile());
    let mut reader = Chunked {
        bytes: &bytes,
        max_request: 0,
        read: 0,
        interrupt: false,
    };
    let error = parse_reader_with_limit(&mut reader, MAGIC.len() + 6).unwrap_err();
    assert!(error.0.contains("input limit"));
    assert_eq!(reader.read, MAGIC.len() + 6);
    assert_eq!(reader.max_request, MAGIC.len());
    for limit in [0, 1, 10, 12, bytes.len() - 1] {
        assert!(parse_reader_with_limit(&mut Cursor::new(&bytes), limit)
            .unwrap_err()
            .0
            .contains("input limit"));
    }
    let mut oversized = MAGIC.to_vec();
    oversized.extend(1u16.to_le_bytes());
    oversized.extend(((MAX_RECORD_BYTES + 1) as u32).to_le_bytes());
    let mut cursor = Cursor::new(oversized);
    assert!(parse_reader_with_limit(&mut cursor, usize::MAX)
        .unwrap_err()
        .0
        .contains("record is"));
    assert_eq!(cursor.position(), 16);
}

#[test]
fn eof_probe_rejects_growth_after_terminator_at_the_exact_input_limit() {
    let bytes = encoded(&profile());
    for tail in [vec![0], vec![0; 64], bytes.clone()] {
        let mut grown = bytes.clone();
        grown.extend(tail);
        let mut cursor = Cursor::new(grown);
        assert!(parse_reader_with_limit(&mut cursor, bytes.len())
            .unwrap_err()
            .0
            .contains("terminator"));
        assert_eq!(cursor.position(), bytes.len() as u64 + 1);
    }
}

#[test]
fn malformed_late_record_invalidates_previously_decoded_samples() {
    let mut bytes = encoded(&profile());
    bytes.truncate(bytes.len() - 4);
    for payload in [
        vec![0],
        vec![0x0a, 1, 0x80],
        vec![0x0a, 0, 0x38],
        vec![0x0f],
        vec![0x1a, 3, 0x12, 1, 0xff],
    ] {
        let mut malformed = bytes.clone();
        malformed.extend((payload.len() as u32).to_le_bytes());
        malformed.extend(payload);
        malformed.extend(0u32.to_le_bytes());
        assert!(parse_reader_with_limit(&mut Cursor::new(&malformed), malformed.len()).is_err());
    }
}

#[test]
fn read_errors_are_propagated_instead_of_being_mistaken_for_clean_eof() {
    struct Broken;
    impl Read for Broken {
        fn read(&mut self, _: &mut [u8]) -> io::Result<usize> {
            Err(io::Error::other("fixture read failure"))
        }
    }
    assert!(parse_reader_with_limit(&mut Broken, 4096)
        .unwrap_err()
        .0
        .contains("fixture read failure"));
    let bytes = encoded(&profile());
    let mut reader = Cursor::new(bytes).chain(Broken);
    assert!(parse_reader_with_limit(&mut reader, 4096)
        .unwrap_err()
        .0
        .contains("terminator EOF"));
}
