use super::*;
use std::io::Cursor;
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT: AtomicU64 = AtomicU64::new(0);
struct Fixture(PathBuf);
impl Fixture {
    fn new(bytes: &[u8]) -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-gpu-source-safety-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::write(&path, bytes).unwrap();
        Self(path)
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

#[test]
fn classification_prefix_and_digest_come_from_the_same_stream() {
    let fixture = Fixture::new(b"PERFETTO same-descriptor evidence");
    let mut file = File::open(&fixture.0).unwrap();
    let expected = file.metadata().unwrap();
    file.read_exact(&mut [0; 8]).unwrap();
    let (digest, prefix) =
        hash_open_source(&fixture.0, &mut file, &expected, &CancellationToken::new()).unwrap();
    assert_eq!(
        digest,
        SourceDigest::hash_reader(Cursor::new(b"PERFETTO same-descriptor evidence"), 1024).unwrap()
    );
    assert_eq!(classify(&fixture.0, &prefix), ArtifactKind::PerfettoTrace);
    assert_eq!(prefix, &b"PERFETTO same-descriptor evidence"[..32]);
}

#[cfg(unix)]
#[test]
fn replacing_a_path_does_not_bind_open_descriptor_a_to_file_b_digest() {
    let original = Fixture::new(b"PERFETTO original");
    let replacement = Fixture::new(b"different content");
    let mut file = File::open(&original.0).unwrap();
    let expected = file.metadata().unwrap();
    std::fs::rename(&replacement.0, &original.0).unwrap();
    assert!(matches!(
        hash_open_source(&original.0, &mut file, &expected, &CancellationToken::new()),
        Err(GpuError::SourceChanged)
    ));
}

#[test]
fn modifying_the_open_source_invalidates_its_prior_stamp() {
    let fixture = Fixture::new(b"before");
    let mut file = File::open(&fixture.0).unwrap();
    let expected = file.metadata().unwrap();
    std::fs::write(&fixture.0, b"changed-size").unwrap();
    assert!(matches!(
        hash_open_source(&fixture.0, &mut file, &expected, &CancellationToken::new()),
        Err(GpuError::SourceChanged)
    ));
}

#[test]
fn reader_bounds_each_io_chunk_and_does_not_read_after_cancellation() {
    let cancel = CancellationToken::new();
    let mut reader = CheckedSourceReader {
        source: Cursor::new(vec![7; 128 * 1024]),
        cancel: &cancel,
        prefix: Vec::new(),
    };
    let mut output = vec![0; 128 * 1024];
    assert_eq!(reader.read(&mut output).unwrap(), 64 * 1024);
    assert_eq!(reader.prefix.len(), 32);
    cancel.cancel();
    assert!(reader.read(&mut output).is_err());
    assert_eq!(reader.source.position(), 64 * 1024);
}

#[test]
fn cancellation_during_an_underlying_read_discards_the_chunk() {
    struct CancellingRead(CancellationToken);
    impl Read for CancellingRead {
        fn read(&mut self, output: &mut [u8]) -> io::Result<usize> {
            output[0] = 1;
            self.0.cancel();
            Ok(1)
        }
    }
    let cancel = CancellationToken::new();
    let mut reader = CheckedSourceReader {
        source: CancellingRead(cancel.clone()),
        cancel: &cancel,
        prefix: Vec::new(),
    };
    assert!(reader.read(&mut [0; 16]).is_err());
    assert!(reader.prefix.is_empty());
}

#[test]
fn cancelled_resolution_and_relocation_preserve_imported_identity() {
    let source = Fixture::new(b"same source");
    let copy = Fixture::new(b"same source");
    let cancel = CancellationToken::new();
    let mut artifact = GpuArtifact::import(&source.0, &cancel).unwrap();
    let digest = artifact.sha256;
    let locations = artifact.locations().to_vec();
    cancel.cancel();
    assert!(matches!(
        artifact.resolve_location(&cancel),
        Err(GpuError::Cancelled)
    ));
    assert!(matches!(
        artifact.relocate(&copy.0, &cancel),
        Err(GpuError::Cancelled)
    ));
    assert_eq!(artifact.sha256, digest);
    assert_eq!(artifact.locations(), locations);
}
