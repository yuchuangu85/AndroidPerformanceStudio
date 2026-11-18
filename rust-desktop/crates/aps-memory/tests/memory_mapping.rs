//! Mapping import support: the engine must group classes under original names
//! while keeping the obfuscated name as reviewable evidence.

use aps_hprof_engine::cache::sha256_file;
use aps_memory::{MAX_MAPPING_ENTRIES, MemoryError, MemorySession};
use aps_platform::CancellationToken;
use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT: AtomicU64 = AtomicU64::new(0);

/// Minimal Java 1.0.3 HPROF with one obfuscated class and one instance.
struct MinimalHprof {
    bytes: Vec<u8>,
    dump: Vec<u8>,
    strings: BTreeMap<&'static str, u64>,
    next_string: u64,
}

impl MinimalHprof {
    fn new() -> Self {
        let mut bytes = b"JAVA PROFILE 1.0.3\0".to_vec();
        bytes.extend(8u32.to_be_bytes());
        bytes.extend(1_700_000_000_000u64.to_be_bytes());
        Self {
            bytes,
            dump: Vec::new(),
            strings: BTreeMap::new(),
            next_string: 0x10000,
        }
    }

    fn record(&mut self, tag: u8, data: &[u8]) {
        self.bytes.push(tag);
        self.bytes.extend(0u32.to_be_bytes());
        self.bytes.extend((data.len() as u32).to_be_bytes());
        self.bytes.extend(data);
    }

    fn string(&mut self, name: &'static str) -> u64 {
        if let Some(id) = self.strings.get(name) {
            return *id;
        }
        let id = self.next_string;
        self.next_string += 1;
        let mut data = id.to_be_bytes().to_vec();
        data.extend(name.as_bytes());
        self.record(1, &data);
        self.strings.insert(name, id);
        id
    }

    fn class(&mut self, class_id: u64, name: &'static str, instance_size: u32) {
        let name_id = self.string(name);
        let mut load = Vec::new();
        load.extend(1u32.to_be_bytes());
        load.extend(class_id.to_be_bytes());
        load.extend(0u32.to_be_bytes());
        load.extend(name_id.to_be_bytes());
        load.extend(0u64.to_be_bytes());
        self.record(2, &load);

        let mut class_dump = Vec::new();
        class_dump.push(0x20);
        class_dump.extend(class_id.to_be_bytes());
        class_dump.extend(0u32.to_be_bytes());
        class_dump.extend(0u64.to_be_bytes());
        class_dump.extend([0u8; 40]);
        class_dump.extend(instance_size.to_be_bytes());
        class_dump.extend(0u16.to_be_bytes());
        class_dump.extend(0u16.to_be_bytes());
        class_dump.extend(0u16.to_be_bytes());
        self.dump.extend(class_dump);
    }

    fn instance(&mut self, object_id: u64, class_id: u64, payload: &[u8]) {
        self.dump.push(0x21);
        self.dump.extend(object_id.to_be_bytes());
        self.dump.extend(0u32.to_be_bytes());
        self.dump.extend(class_id.to_be_bytes());
        self.dump.extend((payload.len() as u32).to_be_bytes());
        self.dump.extend(payload);
    }

    fn static_holder(&mut self, class_id: u64, target: u64) {
        let name_id = self.string("sample.Holder");
        let field_id = self.string("screen");
        let mut load = 1u32.to_be_bytes().to_vec();
        load.extend(class_id.to_be_bytes());
        load.extend(0u32.to_be_bytes());
        load.extend(name_id.to_be_bytes());
        self.record(2, &load);
        self.dump.push(0x20);
        self.dump.extend(class_id.to_be_bytes());
        self.dump.extend(0u32.to_be_bytes());
        self.dump.extend([0; 48]);
        self.dump.extend(0u32.to_be_bytes());
        self.dump.extend(0u16.to_be_bytes());
        self.dump.extend(1u16.to_be_bytes());
        self.dump.extend(field_id.to_be_bytes());
        self.dump.push(2);
        self.dump.extend(target.to_be_bytes());
        self.dump.extend(0u16.to_be_bytes());
    }

    fn class_root(&mut self, class_id: u64) {
        self.dump.push(0x05);
        self.dump.extend(class_id.to_be_bytes());
    }

    fn finish(mut self) -> Vec<u8> {
        let dump = std::mem::take(&mut self.dump);
        self.record(0x1c, &dump);
        self.bytes
    }
}

struct Fixture(PathBuf);

impl Fixture {
    fn obfuscated_class() -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-memory-mapping-{}-{}.hprof",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        let mut hprof = MinimalHprof::new();
        hprof.class(100, "a.b.C", 16);
        hprof.instance(1, 100, &[0u8; 16]);
        hprof.class_root(100);
        fs::write(&path, hprof.finish()).unwrap();
        Self(path)
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

fn open_fixture(mapping: &[(String, String)]) -> (Fixture, MemorySession, CancellationToken) {
    let file = Fixture::obfuscated_class();
    let digest = sha256_file(&file.0).unwrap();
    let cancel = CancellationToken::new();
    let executable = Path::new(env!("CARGO_BIN_EXE_aps-memory-worker"));
    let session =
        MemorySession::open_with_mapping(executable, &file.0, &digest, mapping, &cancel).unwrap();
    (file, session, cancel)
}

fn histogram_names(session: &mut MemorySession, cancel: &CancellationToken) -> Vec<String> {
    session
        .histogram(None, 0, 50, cancel)
        .unwrap()
        .get("rows")
        .unwrap()
        .as_list()
        .unwrap()
        .iter()
        .map(|row| row.get("className").unwrap().as_str().unwrap().to_owned())
        .collect()
}

#[test]
fn mapping_deobfuscates_class_and_instance_names() {
    let mapping = vec![("a.b.C".to_owned(), "com.example.C".to_owned())];
    let (_file, mut session, cancel) = open_fixture(&mapping);

    let snapshot = session.snapshot(&cancel).unwrap();
    assert_eq!(snapshot.metadata.class_count, 1);
    let class = snapshot
        .classes
        .iter()
        .find(|class| class.class_name == "com.example.C")
        .expect("mapped class");
    assert_eq!(class.obfuscated_class_name.as_deref(), Some("a.b.C"));

    let names = histogram_names(&mut session, &cancel);
    assert!(names.contains(&"com.example.C".to_owned()));
    assert!(!names.contains(&"a.b.C".to_owned()));

    let page = session
        .instances_page(&["com.example.C".to_owned()], None, 0, 50, &cancel)
        .unwrap();
    assert!(page.total >= 1);
    let instance = page
        .rows
        .iter()
        .find(|row| row.object_id == 1)
        .expect("mapped instance");
    assert_eq!(instance.class_name.as_deref(), Some("com.example.C"));
}

#[test]
fn without_mapping_the_raw_class_name_is_evidence() {
    let (_file, mut session, cancel) = open_fixture(&[]);
    let names = histogram_names(&mut session, &cancel);
    assert_eq!(names, vec!["a.b.C"]);
    let snapshot = session.snapshot(&cancel).unwrap();
    assert_eq!(snapshot.classes[0].obfuscated_class_name, None);
}

#[test]
fn imported_hprof_keeps_the_same_leak_path_in_snapshot_and_paged_findings() {
    let file = Fixture::obfuscated_class();
    let mut hprof = MinimalHprof::new();
    hprof.class(100, "a.b.C", 16);
    hprof.instance(u64::MAX, 100, &[0; 16]);
    hprof.static_holder(101, u64::MAX);
    hprof.class_root(101);
    fs::write(&file.0, hprof.finish()).unwrap();
    let digest = sha256_file(&file.0).unwrap();
    let cancel = CancellationToken::new();
    let executable = Path::new(env!("CARGO_BIN_EXE_aps-memory-worker"));
    let mut session = MemorySession::open_with_mapping(
        executable,
        &file.0,
        &digest,
        &[("a.b.C".into(), "sample.LeakingActivity".into())],
        &cancel,
    )
    .unwrap();
    let snapshot = session.snapshot(&cancel).unwrap();
    let page = session.leak_suspects_page(0, 200, &cancel).unwrap();
    assert_eq!(snapshot.source_digest, digest);
    assert_eq!(snapshot.findings.leak_suspects, page);
    assert_eq!(page.total, 1);
    let suspect = &page.rows[0];
    assert_eq!(suspect.class_name, "sample.LeakingActivity");
    assert_eq!(suspect.instance_count, 1);
    assert_eq!(suspect.retained_size, Some(16));
    assert!(suspect.reason.contains("sample.Holder.screen"));
    assert_eq!(suspect.reference_chain.len(), 2);
    assert_eq!(suspect.reference_chain[0].object_id, 101);
    assert_eq!(
        suspect.reference_chain[0].root_kind.as_deref(),
        Some("STICKY_CLASS")
    );
    assert_eq!(suspect.reference_chain[1].object_id, u64::MAX);
    assert_eq!(
        suspect.reference_chain[1].class_name,
        "sample.LeakingActivity"
    );
    assert_eq!(
        suspect.reference_chain[1].field_name.as_deref(),
        Some("static screen")
    );
}

#[test]
fn oversized_mapping_is_rejected_before_a_worker_starts() {
    let _file = Fixture::obfuscated_class();
    let mapping: Vec<(String, String)> = (0..=MAX_MAPPING_ENTRIES)
        .map(|index| (format!("a{index}"), format!("b{index}")))
        .collect();
    let cancel = CancellationToken::new();
    assert!(matches!(
        MemorySession::open_with_mapping(
            Path::new("missing-worker"),
            Path::new("/missing.hprof"),
            &"0".repeat(64),
            &mapping,
            &cancel,
        ),
        Err(MemoryError::InvalidInput)
    ));
}
