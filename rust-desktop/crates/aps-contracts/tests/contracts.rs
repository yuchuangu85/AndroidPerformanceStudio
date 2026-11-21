use aps_contracts::*;
use std::sync::atomic::{AtomicUsize, Ordering};

#[test]
fn typed_ids_are_bounded_and_sequence_requires_caller_owned_namespace() {
    assert!(ArtifactId::new("").is_err());
    assert!(RequestId::new("unsafe/path").is_err());
    let mut sequence = IdSequence::new("persisted-random-namespace").unwrap();
    let first = ArtifactId::new(sequence.next_id("artifact").unwrap()).unwrap();
    let second = ArtifactId::new(sequence.next_id("artifact").unwrap()).unwrap();
    assert_ne!(first, second);
    assert!(IdSequence::new("a".repeat(91)).is_err());
}
#[test]
fn pid_without_start_is_weak_and_pid_reuse_cannot_match_strong_identity() {
    let device = DeviceTargetId::new("usb-device").unwrap();
    let mut first = ProcessIdentity::new(device.clone(), 100, "app:worker").unwrap();
    let mut second = first.clone();
    assert_eq!(
        first.compare(&second).unwrap(),
        ProcessIdentityMatch::WeakIdentity
    );
    first.start = Some(ProcessStartIdentity::new("boot-a", 123).unwrap());
    second.start = Some(ProcessStartIdentity::new("boot-a", 124).unwrap());
    assert_eq!(
        first.compare(&second).unwrap(),
        ProcessIdentityMatch::DifferentInstance
    );
    second.start = first.start.clone();
    assert_eq!(
        first.compare(&second).unwrap(),
        ProcessIdentityMatch::SameInstance
    );
    second.start = Some(ProcessStartIdentity::new("boot-b", 123).unwrap());
    assert_eq!(
        first.compare(&second).unwrap(),
        ProcessIdentityMatch::DifferentInstance
    );
}
#[test]
fn clock_mapping_requires_domain_validity_and_preserves_uncertainty() {
    let source = ClockDomainId::new("device-boot-a").unwrap();
    let target = ClockDomainId::new("trace-a").unwrap();
    let mapping = ClockMapping::new(
        source.clone(),
        target.clone(),
        TimeRangeNanos::new(100, 200).unwrap(),
        100,
        1000,
        5,
    )
    .unwrap();
    let mapped = mapping.map(&source, 150).unwrap();
    assert_eq!(
        (
            mapped.estimate_nanos,
            mapped.earliest_nanos,
            mapped.latest_nanos
        ),
        (1050, 1045, 1055)
    );
    assert!(mapping.map(&source, 200).is_err());
    assert!(mapping.map(&target, 150).is_err());
    assert!(ClockMapping::new(
        source.clone(),
        source,
        TimeRangeNanos::new(100, 200).unwrap(),
        100,
        1000,
        5
    )
    .is_err());
}
#[test]
fn completeness_is_not_capability_and_each_missing_item_needs_a_reason() {
    let cpu = CapabilityId::new("cpu.samples").unwrap();
    let stacks = CapabilityId::new("cpu.stacks").unwrap();
    let requested = Capabilities::new([cpu.clone(), stacks.clone()]).unwrap();
    let retained = Capabilities::new([cpu]).unwrap();
    let missing = MissingCapability::new(stacks, "Unwind evidence unavailable").unwrap();
    assert!(ArtifactCompleteness::Partial {
        requested: requested.clone(),
        retained: retained.clone(),
        missing: vec![missing.clone()]
    }
    .validate()
    .is_ok());
    assert!(ArtifactCompleteness::Partial {
        requested,
        retained,
        missing: vec![missing.clone(), missing]
    }
    .validate()
    .is_err());
    assert!(ArtifactCompleteness::Complete {
        requested: Capabilities::default()
    }
    .validate()
    .is_err());
}
#[test]
fn import_preserves_producer_and_digest_chain_is_validated() {
    let digest = SourceDigest::from_bytes([7; 32]);
    let wrong = SourceDigest::from_bytes([8; 32]);
    let imported =
        ProvenanceStep::new(ProvenanceAction::Import, "APS import", Some(digest), digest).unwrap();
    let provenance = Provenance::new("Android Simpleperf", vec![imported]).unwrap();
    assert_eq!(provenance.producer, "Android Simpleperf");
    assert!(
        ProvenanceStep::new(ProvenanceAction::Import, "APS import", Some(digest), wrong).is_err()
    );
}
static NEXT: AtomicUsize = AtomicUsize::new(0);
#[test]
fn reused_sha256_implementation_matches_standard_files_and_byte_limits() {
    for (input, expected) in [
        (
            b"".as_slice(),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        ),
        (
            b"abc".as_slice(),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        ),
        (
            b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".as_slice(),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
        ),
    ] {
        let path = std::env::temp_dir().join(format!(
            "aps-contract-digest-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::write(&path, input).unwrap();
        let digest = SourceDigest::hash_file(&path, input.len() as u64).unwrap();
        assert_eq!(digest.to_hex(), expected);
        assert_eq!(
            SourceDigest::from_hex(&expected.to_uppercase()).unwrap(),
            digest
        );
        if !input.is_empty() {
            assert!(SourceDigest::hash_file(&path, input.len() as u64 - 1).is_err());
        }
        std::fs::remove_file(path).unwrap();
    }
    assert!(SourceDigest::from_hex("no").is_err());
    assert!(SourceDigest::from_hex(&"z".repeat(64)).is_err());
}

#[test]
fn source_digest_hashes_reader_bytes_without_temp_files_or_prefix_success() {
    let digest = SourceDigest::hash_reader(b"abc".as_slice(), 3).unwrap();
    assert_eq!(
        digest.to_hex(),
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    );
    assert!(SourceDigest::hash_reader(b"abcd".as_slice(), 3).is_err());
    assert!(SourceDigest::hash_reader(b"x".as_slice(), 0).is_err());
    assert_eq!(
        SourceDigest::hash_reader(b"".as_slice(), 0)
            .unwrap()
            .to_hex(),
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    );
}

#[test]
fn reader_source_digest_consumes_only_budget_and_single_eof_probe() {
    struct Endless(usize);
    impl std::io::Read for Endless {
        fn read(&mut self, output: &mut [u8]) -> std::io::Result<usize> {
            self.0 += output.len();
            output.fill(0);
            Ok(output.len())
        }
    }
    let mut reader = Endless(0);
    assert!(SourceDigest::hash_reader(&mut reader, 100_000).is_err());
    assert_eq!(reader.0, 100_001);
}
