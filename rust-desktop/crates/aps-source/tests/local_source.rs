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
    fn workspace(&self) -> LocalSourceWorkspace {
        LocalSourceWorkspace::register(&self.0, "Selected project").unwrap()
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
#[test]
fn registration_does_not_index_and_snapshot_identity_is_content_bound() {
    let root = Fixture::new();
    root.write("app/src/App.kt", "package demo\nclass App\nfun work() {}\n");
    let workspace = root.workspace();
    let first = workspace.snapshot(SourceLimits::default()).unwrap();
    let unchanged = workspace.snapshot(SourceLimits::default()).unwrap();
    assert_eq!(first.id(), unchanged.id());
    assert_eq!(first.file_count(), 1);
    assert!(first.coverage().manifest_complete());
    assert!(!first.coverage().structurally_complete());
    let file = &first.files(0, 512).unwrap().rows[0];
    assert_eq!(file.module, "app");
    assert_eq!(file.line_count, 3);
    let location = first.location("app/src/App.kt", 2, 3).unwrap();
    let snippet = workspace
        .read_location(&first, &location, SnippetLimits::default())
        .unwrap();
    assert_eq!(snippet.text, "class App\nfun work() {}\n");
    root.write(
        "app/src/App.kt",
        "package demo\nclass Changed\nfun work() {}\n",
    );
    assert!(workspace
        .read_location(&first, &location, SnippetLimits::default())
        .is_err());
    let changed = workspace.snapshot(SourceLimits::default()).unwrap();
    assert_ne!(first.id(), changed.id());
}
#[test]
fn path_policy_rejects_traversal_absolute_hidden_credentials_and_unsupported_types() {
    for path in [
        "../App.kt",
        "/tmp/App.kt",
        "a/../../App.kt",
        "a\\App.kt",
        "C:/App.kt",
        ".git/Config.kt",
        "src/.env.kt",
        "src/ApiToken.kt",
        "src/credentials.kt",
        "src/keys.pem",
        "src/App.kt/",
        "a//App.kt",
        "src/data.json",
    ] {
        assert!(validate_relative_source_path(path).is_err(), "{path}");
    }
    assert!(validate_relative_source_path("app/src/main/java/App.java").is_ok());
}
#[test]
fn snapshot_excludes_sensitive_build_hidden_and_gitignored_sources() {
    let root = Fixture::new();
    root.write("src/Good.kt", "fun good() {}\n");
    root.write("src/Generated.kt", "fun generated() {}\n");
    root.write("src/private/Anything.kt", "fun excluded() {}\n");
    root.write("build/Build.kt", "class Build\n");
    root.write(".hidden/Hidden.kt", "class Hidden\n");
    root.write("src/TokenStore.kt", "class TokenStore\n");
    root.write(".gitignore", "src/Generated.kt\n!src/Generated.kt\n");
    let snapshot = root.workspace().snapshot(SourceLimits::default()).unwrap();
    let files = snapshot.files(0, 512).unwrap();
    assert_eq!(
        files
            .rows
            .iter()
            .map(|file| file.relative_path.as_str())
            .collect::<Vec<_>>(),
        ["src/Good.kt"]
    );
}
#[test]
fn deterministic_candidates_have_no_exact_confidence_and_cannot_be_tampered() {
    let root = Fixture::new();
    root.write(
        "app/src/App.kt",
        "package demo\n// fun fake() {}\nval s = \"fun fake()\"\nclass App\nfun work() {}\n",
    );
    let snapshot = root.workspace().snapshot(SourceLimits::default()).unwrap();
    let query = ResolutionQuery {
        symbol: Some("demo.work".into()),
        ..ResolutionQuery::default()
    };
    let candidates = snapshot.resolve(&query).unwrap();
    assert_eq!(candidates.len(), 1);
    assert_eq!(candidates[0].confidence, ResolutionConfidence::Probable);
    assert_eq!(candidates[0].location.start_line, 5);
    assert!(!candidates[0].index_complete);
    snapshot.validate_candidate(&candidates[0]).unwrap();
    let mut altered = candidates[0].clone();
    altered.location.start_line = 4;
    altered.location.end_line = 4;
    assert!(snapshot.validate_candidate(&altered).is_err());
    let none = snapshot
        .resolve(&ResolutionQuery {
            symbol: Some("fake".into()),
            ..ResolutionQuery::default()
        })
        .unwrap();
    assert!(none.is_empty());
    let file = snapshot
        .resolve(&ResolutionQuery {
            file_name: Some("App.kt".into()),
            ..ResolutionQuery::default()
        })
        .unwrap();
    assert_eq!(file[0].confidence, ResolutionConfidence::Weak);
}
#[test]
fn snippet_limits_preserve_raw_line_endings_and_report_truncation() {
    let root = Fixture::new();
    root.write("Example.kt", "\r\nclass Example\r\nfun run() {}\r\n");
    let workspace = root.workspace();
    let snapshot = workspace.snapshot(SourceLimits::default()).unwrap();
    let location = snapshot.location("Example.kt", 1, 3).unwrap();
    let snippet = workspace
        .read_location(
            &snapshot,
            &location,
            SnippetLimits {
                max_lines: 2,
                max_bytes: 1024,
            },
        )
        .unwrap();
    assert_eq!(snippet.text, "\r\nclass Example\r\n");
    assert_eq!(snippet.end_line, 2);
    assert!(snippet.truncated);
    assert!(workspace
        .read_location(
            &snapshot,
            &snapshot.location("Example.kt", 2, 2).unwrap(),
            SnippetLimits {
                max_lines: 2,
                max_bytes: 1
            }
        )
        .is_err());
    assert!(snapshot.location("Example.kt", 0, 2).is_err());
    assert!(snapshot.location("Example.kt", 1, 100).is_err());
}
#[test]
fn file_byte_symbol_and_entry_budgets_are_visible_and_do_not_claim_full_index() {
    let root = Fixture::new();
    root.write("A.kt", "class A\nfun a() {}\n");
    root.write("B.kt", "class B\n");
    let workspace = root.workspace();
    let snapshot = workspace
        .snapshot(SourceLimits {
            max_files: 1,
            max_symbols: 1,
            ..SourceLimits::default()
        })
        .unwrap();
    assert_eq!(snapshot.file_count(), 1);
    assert!(snapshot.coverage().file_budget_exhausted);
    assert!(snapshot.coverage().symbol_budget_exhausted);
    assert!(!snapshot.coverage().manifest_complete());
    let tiny = workspace
        .snapshot(SourceLimits {
            max_total_bytes: 1,
            ..SourceLimits::default()
        })
        .unwrap();
    assert_eq!(tiny.file_count(), 0);
    assert!(tiny.coverage().byte_budget_exhausted);
    let entries = workspace
        .snapshot(SourceLimits {
            max_entries: 1,
            ..SourceLimits::default()
        })
        .unwrap();
    assert!(entries.coverage().entry_budget_exhausted);
    assert_eq!(entries.file_count(), 0);
    assert!(snapshot.files(0, 513).is_err());
    assert!(snapshot.resolve(&ResolutionQuery::default()).is_err());
}
#[test]
fn unsupported_ignore_syntax_excludes_directory_instead_of_guessing() {
    let root = Fixture::new();
    root.write(".gitignore", "[a-z]*.kt\n");
    root.write("App.kt", "class App\n");
    let snapshot = root.workspace().snapshot(SourceLimits::default()).unwrap();
    assert_eq!(snapshot.file_count(), 0);
    assert_eq!(snapshot.coverage().unreadable_entries, 1);
}
#[cfg(unix)]
#[test]
fn symlink_files_directories_and_root_are_rejected_without_reading_escape_content() {
    use std::os::unix::fs::symlink;
    let root = Fixture::new();
    let outside = Fixture::new();
    outside.write("Outside.kt", "class Outside\n");
    root.write("Safe.kt", "class Safe\n");
    symlink(outside.0.join("Outside.kt"), root.0.join("Linked.kt")).unwrap();
    symlink(&outside.0, root.0.join("linked")).unwrap();
    let workspace = root.workspace();
    let snapshot = workspace.snapshot(SourceLimits::default()).unwrap();
    assert_eq!(snapshot.file_count(), 1);
    assert!(snapshot.location("Linked.kt", 1, 1).is_err());
    let alias = root.0.join("alias");
    symlink(&outside.0, &alias).unwrap();
    assert!(LocalSourceWorkspace::register(alias, "Unsafe alias").is_err());
}

#[test]
fn manifest_lookup_and_candidate_navigation_are_independent_of_file_page() {
    let root = Fixture::new();
    for index in 0..520 {
        root.write(&format!("src/File{index:04}.kt"), "class Item\n");
    }
    root.write("src/Last.kt", "class Last\nfun tail() {}\n");
    let workspace = root.workspace();
    let snapshot = workspace.snapshot(SourceLimits::default()).unwrap();
    assert!(!snapshot
        .files(0, MAX_FILE_PAGE)
        .unwrap()
        .rows
        .iter()
        .any(|file| file.relative_path == "src/Last.kt"));
    assert_eq!(snapshot.file("src/Last.kt").unwrap().line_count, 2);
    let candidates = snapshot
        .resolve(&ResolutionQuery {
            symbol: Some("tail".into()),
            ..Default::default()
        })
        .unwrap();
    let candidate = &candidates[0];
    snapshot.validate_candidate(candidate).unwrap();
    let location = snapshot
        .location(&candidate.location.relative_path, 1, 2)
        .unwrap();
    assert_eq!(
        workspace
            .read_location(&snapshot, &location, SnippetLimits::default())
            .unwrap()
            .text,
        "class Last\nfun tail() {}\n"
    );
    assert!(snapshot.file("src/Absent.kt").is_err());
    assert!(snapshot.file("../src/Last.kt").is_err());
}
