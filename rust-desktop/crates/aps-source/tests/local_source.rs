use aps_contracts::SourceDigest;
use aps_source::*;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};

static NEXT: AtomicUsize = AtomicUsize::new(0);

struct Fixture(PathBuf);

impl Fixture {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-source-fixture-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }

    fn write(&self, path: &str, text: &str) {
        let destination = self.0.join(path);
        std::fs::create_dir_all(destination.parent().unwrap()).unwrap();
        std::fs::write(destination, text).unwrap();
    }

    fn local(&self) -> SourceProviderConfig {
        SourceProviderConfig::Local {
            root: self.0.clone(),
        }
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}

fn file(snapshot: SourceSnapshotId, path: &str, text: &str) -> SourceFile {
    SourceFile {
        snapshot_id: snapshot,
        relative_path: path.to_owned(),
        language: SourceLanguage::from_path(path),
        content_hash: digest_text(text),
        size_bytes: text.len() as u64,
    }
}

fn snapshot(id: SourceSnapshotId, workspace: SourceWorkspaceId) -> SourceSnapshot {
    SourceSnapshot {
        id,
        workspace_id: workspace,
        immutable_revision: "revision".into(),
        dirty_content_digest: None,
        manifest_hash: digest_text("manifest"),
        created_unix_ms: 1,
        index_version: 1,
        index_complete: true,
    }
}

#[test]
fn digest_helpers_are_sha256_and_ids_are_canonical() {
    assert_eq!(
        digest_text("").to_hex(),
        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    );
    assert_eq!(digest_bytes(b"abc"), digest_text("abc"));
    let id = SourceWorkspaceId::from_digest(digest_text("workspace"));
    assert_eq!(SourceWorkspaceId::from_hex(&id.to_hex()).unwrap(), id);
    assert_eq!(id.to_string(), id.to_hex());
    assert!(SourceWorkspaceId::from_hex("not-a-digest").is_err());
    assert!(SourceWorkspaceId::from_hex(&id.to_hex().to_uppercase()).is_err());
}

#[test]
fn language_mapping_and_extension_allowlist_match_compose() {
    assert_eq!(
        SourceLanguage::from_path("src/Main.kt"),
        SourceLanguage::Kotlin
    );
    assert_eq!(
        SourceLanguage::from_path("src/Main.KTS"),
        SourceLanguage::Kotlin
    );
    assert_eq!(
        SourceLanguage::from_path("src/Main.java"),
        SourceLanguage::Java
    );
    assert_eq!(
        SourceLanguage::from_path("res/layout/main.xml"),
        SourceLanguage::Xml
    );
    assert_eq!(SourceLanguage::from_path("native/one.c"), SourceLanguage::C);
    assert_eq!(SourceLanguage::from_path("native/one.h"), SourceLanguage::C);
    assert_eq!(
        SourceLanguage::from_path("native/one.cpp"),
        SourceLanguage::Cpp
    );
    assert_eq!(
        SourceLanguage::from_path("native/one.hh"),
        SourceLanguage::Cpp
    );
    assert_eq!(
        SourceLanguage::from_path("native/one.cxx"),
        SourceLanguage::Cpp
    );
    assert_eq!(
        SourceLanguage::from_path("README.md"),
        SourceLanguage::Other
    );
    assert_eq!(
        SourceLanguage::from_path("no-extension"),
        SourceLanguage::Other
    );
    assert_eq!(
        SourceLanguage::extension_list(),
        ["kt", "kts", "java", "xml", "c", "cc", "cpp", "cxx", "h", "hh", "hpp"]
    );
}

#[test]
fn local_provider_lists_allowed_files_only_and_reads_contained_paths() {
    let root = Fixture::new();
    root.write("src/main/Sample.kt", "package sample\nclass Sample");
    root.write("build/generated/Ignored.kt", "class Ignored");
    root.write(".git/Config.kt", "class Git");
    root.write("node_modules/Thing.kt", "class Thing");
    root.write("README.md", "docs");
    let provider = LocalSourceProvider;
    let config = root.local();
    let revision = provider.resolve_revision(&config).unwrap();
    assert!(revision == "unversioned" || revision.contains("dirty"));
    let files = provider.list_files(&config, &revision).unwrap();
    assert_eq!(
        files
            .iter()
            .map(|file| file.relative_path.as_str())
            .collect::<Vec<_>>(),
        ["src/main/Sample.kt"]
    );
    assert!(files[0].content_hash.is_none());
    let bytes = provider
        .read_file(&config, &revision, "src/main/Sample.kt")
        .unwrap();
    assert_eq!(
        String::from_utf8(bytes).unwrap(),
        "package sample\nclass Sample"
    );
    assert!(provider
        .read_file(&config, &revision, "../outside.kt")
        .is_err());
    assert!(provider.read_file(&config, &revision, "README.md").is_err());
    let missing = SourceProviderConfig::Local {
        root: root.0.join("absent"),
    };
    assert!(provider.resolve_revision(&missing).is_err());
}

#[test]
fn local_revision_digest_changes_when_dirty_content_changes() {
    let root = Fixture::new();
    root.write("src/Main.kt", "class Before");
    let provider = LocalSourceProvider;
    let config = root.local();
    let first = provider.resolve_revision(&config).unwrap();
    root.write("src/Main.kt", "class After");
    let second = provider.resolve_revision(&config).unwrap();
    assert_ne!(first, second);
    assert!(first.starts_with("unversioned-dirty-"));
}

#[test]
fn content_cache_round_trips_and_rejects_tampered_objects() {
    let root = Fixture::new();
    let cache = ContentAddressedSourceCache::new(root.0.join("cache"));
    let content = b"class Cached";
    let hash = cache.put(content).unwrap();
    assert!(cache.contains(hash));
    assert_eq!(cache.read(hash).unwrap(), content);
    let hex = hash.to_hex();
    let object = root.0.join("cache").join(&hex[..2]).join(&hex[2..]);
    std::fs::write(&object, b"tampered").unwrap();
    assert!(cache.read(hash).is_err());
}

#[test]
fn indexer_extracts_kotlin_java_xml_and_native_declarations() {
    let indexer = StructuralSourceIndexer;
    let snapshot_id = SourceSnapshotId::from_digest(digest_text("snapshot"));
    let kotlin_text = "package sample\nclass Widget {\n    fun render(value: Int) = value\n}\n";
    let kotlin = indexer.index(
        &file(snapshot_id, "sample/Widget.kt", kotlin_text),
        kotlin_text,
        64,
    );
    assert!(kotlin.iter().any(|symbol| {
        symbol.kind == SourceSymbolKind::Type
            && symbol.qualified_name == "sample.Widget"
            && symbol.start_line == 2
    }));
    assert!(kotlin.iter().any(|symbol| {
        symbol.kind == SourceSymbolKind::Function
            && symbol.qualified_name == "sample.render"
            && symbol.signature.as_deref() == Some("value: Int")
    }));
    let java_text = "package sample;\nclass Widget {\n    public void render(int value) {}\n}\n";
    let java = indexer.index(
        &file(snapshot_id, "sample/Widget.java", java_text),
        java_text,
        64,
    );
    assert!(java.iter().any(|symbol| {
        symbol.kind == SourceSymbolKind::Method && symbol.qualified_name == "sample.render"
    }));
    let xml_text = "<TextView android:id=\"@+id/title\" />\n";
    let xml = indexer.index(
        &file(snapshot_id, "res/layout/widget.xml", xml_text),
        xml_text,
        64,
    );
    assert_eq!(xml.len(), 1);
    assert_eq!(xml[0].kind, SourceSymbolKind::Resource);
    assert_eq!(xml[0].qualified_name, "id/title");
    let native_text = "int main(int argc, char** argv) {\n}\n";
    let native = indexer.index(
        &file(snapshot_id, "native/main.c", native_text),
        native_text,
        64,
    );
    assert!(native.iter().any(|symbol| {
        symbol.kind == SourceSymbolKind::NativeSymbol && symbol.qualified_name == "main"
    }));
    assert!(indexer
        .index(
            &file(snapshot_id, "native/main.c", native_text),
            native_text,
            0
        )
        .is_empty());
}

#[test]
fn resolver_matches_evidence_and_caps_unverified_build_identity() {
    let mut repository = InMemorySourceRepository::default();
    let workspace_id = SourceWorkspaceId::from_digest(digest_text("workspace"));
    let snapshot_id = SourceSnapshotId::from_digest(digest_text("snapshot"));
    let kotlin_text = "package sample\nclass Widget {\n    fun render(value: Int) = value\n}\n";
    let xml_text = "<TextView android:id=\"@+id/title\" />\n";
    let native_text = "int main(int argc, char** argv) {\n}\n";
    let files = vec![
        file(snapshot_id, "sample/Widget.kt", kotlin_text),
        file(snapshot_id, "res/layout/widget.xml", xml_text),
        file(snapshot_id, "native/main.c", native_text),
    ];
    let mut symbols = Vec::new();
    let indexer = StructuralSourceIndexer;
    for source in &files {
        let text = match source.relative_path.as_str() {
            "sample/Widget.kt" => kotlin_text,
            "res/layout/widget.xml" => xml_text,
            _ => native_text,
        };
        symbols.extend(indexer.index(source, text, 64));
    }
    repository
        .save_workspace(&SourceWorkspace {
            id: workspace_id,
            display_name: "sources".into(),
            config: SourceProviderConfig::Local {
                root: PathBuf::from("."),
            },
            active_snapshot_id: Some(snapshot_id),
            phase: SourceWorkspacePhase::Ready,
            progress: 1.0,
            message: None,
            allow_ai_source_upload: false,
        })
        .unwrap();
    repository
        .save_snapshot(&snapshot(snapshot_id, workspace_id), &files, &symbols)
        .unwrap();

    let evidence = vec![
        SourceResolutionEvidence::TypeName {
            id: PerformanceEvidenceId::from_digest(digest_text("type")),
            qualified_name: "sample.Widget".into(),
        },
        SourceResolutionEvidence::AndroidResource {
            id: PerformanceEvidenceId::from_digest(digest_text("resource")),
            resource_type: "id".into(),
            resource_name: "title".into(),
        },
        SourceResolutionEvidence::ManagedSymbol {
            id: PerformanceEvidenceId::from_digest(digest_text("managed")),
            class_name: Some("sample.Widget".into()),
            method_name: "render".into(),
            signature: None,
            resource_path: None,
        },
        SourceResolutionEvidence::SourceFileLine {
            id: PerformanceEvidenceId::from_digest(digest_text("compose")),
            file_name: "Widget.kt".into(),
            package_hash: java_string_hash_code_absolute("sample"),
            line: 3,
        },
    ];
    let resolver = IndexedSourceResolver;
    let candidates = resolver
        .resolve(
            &repository,
            &[snapshot_id],
            &evidence,
            BuildIdentityMatch::Unverified,
            16,
        )
        .unwrap();
    assert!(candidates
        .iter()
        .any(|candidate| candidate.evidence_id.to_hex()
            == PerformanceEvidenceId::from_digest(digest_text("type")).to_hex()
            && candidate.confidence == ResolutionConfidence::Probable
            && candidate
                .reasons
                .iter()
                .any(|reason| reason == "Build identity not verified")));
    assert!(candidates.iter().any(|candidate| candidate
        .reasons
        .iter()
        .any(|reason| reason == "Compose file and package hash matched")));
    let exact = resolver
        .resolve(
            &repository,
            &[snapshot_id],
            &[SourceResolutionEvidence::TypeName {
                id: PerformanceEvidenceId::from_digest(digest_text("exact")),
                qualified_name: "sample.Widget".into(),
            }],
            BuildIdentityMatch::Verified,
            16,
        )
        .unwrap();
    assert_eq!(exact.len(), 1);
    assert_eq!(exact[0].confidence, ResolutionConfidence::Exact);
    let limited = resolver
        .resolve(
            &repository,
            &[snapshot_id],
            &evidence,
            BuildIdentityMatch::Unverified,
            1,
        )
        .unwrap();
    assert_eq!(limited.len(), 1);
}

fn java_string_hash_code_absolute(value: &str) -> u32 {
    let mut hash: i32 = 0;
    for unit in value.encode_utf16() {
        hash = hash.wrapping_mul(31).wrapping_add(i32::from(unit));
    }
    hash.wrapping_abs() as u32
}

#[test]
fn repository_cascades_snapshot_rows_on_workspace_removal() {
    let mut repository = InMemorySourceRepository::default();
    let workspace_id = SourceWorkspaceId::from_digest(digest_text("workspace"));
    let snapshot_id = SourceSnapshotId::from_digest(digest_text("snapshot"));
    repository
        .save_workspace(&SourceWorkspace {
            id: workspace_id,
            display_name: "sources".into(),
            config: SourceProviderConfig::Local {
                root: PathBuf::from("."),
            },
            active_snapshot_id: Some(snapshot_id),
            phase: SourceWorkspacePhase::Ready,
            progress: 1.0,
            message: None,
            allow_ai_source_upload: false,
        })
        .unwrap();
    repository
        .save_snapshot(
            &snapshot(snapshot_id, workspace_id),
            &[file(snapshot_id, "A.kt", "class A")],
            &[],
        )
        .unwrap();
    assert_eq!(repository.files(snapshot_id).unwrap().len(), 1);
    repository.delete_workspace(workspace_id).unwrap();
    assert!(repository.workspaces().unwrap().is_empty());
    assert!(repository.snapshot(snapshot_id).unwrap().is_none());
    assert!(repository.files(snapshot_id).unwrap().is_empty());
}

#[test]
fn path_policy_rejects_traversal_absolute_hidden_and_unsupported_types() {
    for path in [
        "../App.kt",
        "/tmp/App.kt",
        "a/../../App.kt",
        "a\\App.kt",
        "C:/App.kt",
        "",
        "src/data.json",
        "src/App.kt/",
        "a//App.kt",
    ] {
        assert!(validate_relative_source_path(path).is_err(), "{path}");
    }
    assert!(validate_relative_source_path("app/src/main/java/App.java").is_ok());
}

#[test]
fn provider_registry_reports_missing_kinds() {
    let registry = SourceProviderRegistry::local();
    assert_eq!(
        registry
            .provider_for(SourceProviderKind::Local)
            .unwrap()
            .kind(),
        SourceProviderKind::Local
    );
    assert!(registry.provider_for(SourceProviderKind::GitHub).is_err());
}

#[test]
fn source_location_and_range_shapes_are_public() {
    let range = SourceRange {
        start_line: 3,
        end_line: 5,
    };
    assert_eq!(range.start_line, 3);
    let digest = SourceDigest::from_hex(&"0".repeat(64)).unwrap();
    let location = SourceLocation {
        workspace_id: SourceWorkspaceId::from_digest(digest),
        snapshot_id: SourceSnapshotId::from_digest(digest),
        relative_path: "src/App.kt".into(),
        range: Some(range),
        content_hash: digest,
    };
    assert_eq!(location.range, Some(range));
}
