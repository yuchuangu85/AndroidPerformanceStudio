//! Bounded R8/ProGuard mapping.txt import.
//!
//! Mapping import deobfuscates the loaded heap: the selected HPROF is reopened
//! with a raw-class-name to original-class-name map, the typed snapshot is
//! republished, and the tab identity (path, label, digest) is retained. The
//! parser mirrors ProguardMappingParser.kt: only top-level class rows of the
//! form "original -> obfuscated:" are consumed; indented member rows are
//! ignored. Array descriptors are handled by the HPROF engine itself.
use super::ImportWorkspace;
use super::state::MemoryViewMode;
use crate::import::Session;
use aps_memory::{MAX_MAPPING_BYTES, MAX_MAPPING_ENTRIES, MemorySession};
use aps_platform::CancellationToken;
use aps_ui_foundation::{Destination, Language, TextKey};
use gpui_kit::{PathPromptOptions, prelude::*};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::Instant;

/// A mapping.txt larger than this is rejected before it is parsed.
pub(super) const MAX_MAPPING_FILE_BYTES: u64 = MAX_MAPPING_BYTES as u64;
/// A mapping.txt may not exceed this many physical lines.
pub(super) const MAX_MAPPING_LINES: usize = MAX_MAPPING_ENTRIES;
const MAX_MAPPING_NAME_BYTES: usize = 4096;

/// Parses the class section of an R8/ProGuard mapping file into raw -> original
/// class-name pairs. Pure so it can run on the background executor.
pub(super) fn parse_mapping(text: &str) -> Result<Vec<(String, String)>, String> {
    let mut mapping: BTreeMap<String, String> = BTreeMap::new();
    let mut lines = 0usize;
    for raw_line in text.lines() {
        lines += 1;
        if lines > MAX_MAPPING_LINES {
            return Err("mapping.txt exceeds the 200,000 line import budget".into());
        }
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        // Member (field/method) mappings are indented and carry no class mapping.
        if raw_line.starts_with(' ') || raw_line.starts_with('\t') {
            continue;
        }
        let Some(inner) = line.strip_suffix(':') else {
            continue;
        };
        let Some(arrow) = inner.rfind(" -> ") else {
            continue;
        };
        let original = inner[..arrow].trim();
        let obfuscated = inner[arrow + 4..].trim();
        if original.is_empty() || obfuscated.is_empty() {
            continue;
        }
        if original.len() > MAX_MAPPING_NAME_BYTES || obfuscated.len() > MAX_MAPPING_NAME_BYTES {
            return Err("mapping.txt contains a class name that exceeds the import budget".into());
        }
        // Last row wins exactly like the Compose linked map.
        mapping.insert(obfuscated.to_owned(), original.to_owned());
        if mapping.len() > MAX_MAPPING_ENTRIES {
            return Err("mapping.txt contains more than 200,000 class mappings".into());
        }
    }
    if mapping.is_empty() {
        return Err("mapping.txt contains no class mappings".into());
    }
    Ok(mapping.into_iter().collect())
}

fn mapping_message(language: Language, detail: impl std::fmt::Display) -> String {
    format!(
        "{}: {detail}",
        language.text(TextKey::MemoryAppUnableToLoadMapping)
    )
}

fn worker_executable() -> Result<PathBuf, String> {
    let executable = std::env::current_exe()
        .map_err(|_| "Cannot locate the aps-memory worker executable".to_owned())?;
    Ok(executable.with_file_name(if cfg!(target_os = "windows") {
        "aps-memory-worker.exe"
    } else {
        "aps-memory-worker"
    }))
}

fn read_mapping_file(path: &Path) -> Result<Vec<(String, String)>, String> {
    let metadata = std::fs::metadata(path)
        .map_err(|_| "Cannot inspect the selected mapping.txt".to_owned())?;
    if !metadata.is_file() || metadata.len() > MAX_MAPPING_FILE_BYTES {
        return Err("mapping.txt must be a regular file no larger than 8 MiB".into());
    }
    let text = std::fs::read_to_string(path).map_err(|_| {
        "mapping.txt is not readable UTF-8 text; select an R8/ProGuard mapping file".to_owned()
    })?;
    if text.len() as u64 > MAX_MAPPING_FILE_BYTES {
        return Err("mapping.txt must be no larger than 8 MiB".into());
    }
    parse_mapping(&text)
}

impl ImportWorkspace {
    /// True when a mapping.txt can be applied: one snapshot is loaded, the tab
    /// owns its worker-backed session, and the workspace is not busy.
    pub(crate) fn memory_mapping_enabled(&self) -> bool {
        self.kind == Destination::Memory
            && self.memory_state.snapshot.is_some()
            && self.path.is_some()
            && matches!(self.session, Some(Session::Memory(_)))
            && !self.busy
            && !self.export_busy
            && !self.memory_state.loading
    }

    fn memory_mapping_is_current(
        &self,
        generation: u64,
        load_generation: u64,
        snapshot_id: u64,
    ) -> bool {
        self.generation == generation
            && self.memory_state.load_generation == load_generation
            && self.memory_snapshot_id == Some(snapshot_id)
    }

    fn discard_stale_memory_mapping(&mut self, generation: u64) {
        // Tab suspension owns per-tab invalidation; retire only this operation's busy flag.
        if self.generation == generation {
            self.busy = false;
        }
    }

    /// Opens the mapping picker, then applies the selected mapping. Compose's
    /// File > Import mapping.txt row and the toolbar Mapping button share it.
    pub(crate) fn memory_pick_mapping(&mut self, cx: &mut Context<Self>) {
        if !self.memory_mapping_enabled() {
            return;
        }
        let language = self.language;
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(language.text(TextKey::MemoryAppImportMapping).into()),
        });
        self.memory_state.error = None;
        self.memory_state.notice = None;
        cx.spawn(async move |this, cx| {
            let result = picker.await;
            this.update(cx, |this, cx| match result {
                Ok(Ok(Some(paths))) if paths.len() == 1 => {
                    let path = paths[0].clone();
                    this.memory_import_mapping(path, cx);
                }
                Ok(Err(_)) => {
                    this.memory_state.error =
                        Some(language.text(TextKey::ImportUiFilePickerFailed).to_owned());
                    cx.notify();
                }
                _ => {}
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Reopens the active snapshot with the selected mapping.txt and republishes
    /// the typed snapshot. The old worker session is replaced only after the new
    /// one produced a snapshot, so a failed mapping import keeps the current
    /// tab usable; the replaced session is dropped (killed and reaped) here.
    pub(crate) fn memory_import_mapping(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        if !self.memory_mapping_enabled() {
            return;
        }
        let Some(snapshot_id) = self.memory_snapshot_id else {
            return;
        };
        let started = Instant::now();
        let Some(Session::Memory(session)) = self.session.clone() else {
            return;
        };
        let Some(hprof_path) = self.path.clone() else {
            return;
        };
        let language = self.language;
        let Ok(worker) = worker_executable() else {
            self.memory_state.error = Some(mapping_message(
                language,
                "the memory worker executable is missing",
            ));
            return;
        };
        let label = self.memory_state.snapshot_label.clone();
        let view_mode = self.memory_state.view_mode;
        let file_label = path
            .file_name()
            .and_then(|name| name.to_str())
            .unwrap_or("mapping.txt")
            .to_owned();

        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        let generation = self.generation;
        self.busy = true;
        self.export_generation += 1;
        self.export_busy = false;
        self.memory_state.invalidate_pending_queries();
        let load_generation = self.memory_state.load_generation;
        self.memory_state.loading = true;
        self.memory_state.loading_message = Some(language.format(
            TextKey::MemoryAppImportingMapping,
            &[("arg_1", &file_label)],
        ));
        self.memory_state.error = None;
        self.memory_state.notice = None;
        let cancel = self.cancel.clone();
        let task = cx.background_executor().spawn(async move {
            // The digest is read off the UI thread; the worker re-verifies the
            // bytes before parsing, so a stale value can never deobfuscate a
            // different HPROF file.
            let digest = {
                let session = session
                    .lock()
                    .map_err(|_| "The active memory session is unavailable".to_owned())?;
                session.source_digest().to_owned()
            };
            let mapping = read_mapping_file(&path)?;
            let mut session =
                MemorySession::open_with_mapping(&worker, &hprof_path, &digest, &mapping, &cancel)
                    .map_err(|error| error.to_string())?;
            let snapshot = session
                .snapshot(&cancel)
                .map_err(|error| error.to_string())?;
            Ok::<_, String>((session, snapshot, mapping.len()))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if !this.memory_mapping_is_current(generation, load_generation, snapshot_id) {
                    // Stale result (cancel/other import/tab switch): dropping the fresh
                    // session here kills and reaps its worker.
                    this.discard_stale_memory_mapping(generation);
                    cx.notify();
                    return;
                }
                this.busy = false;
                this.memory_state.loading = false;
                this.memory_state.loading_message = None;
                match result {
                    Ok((session, snapshot, entries)) => {
                        this.session = Some(Session::Memory(Arc::new(Mutex::new(session))));
                        this.memory_state
                            .reset_for_snapshot(Arc::new(snapshot), label);
                        this.memory_state.view_mode = view_mode;
                        this.memory_state.mapping_loaded = true;
                        this.memory_state.mapping_entries = entries;
                        this.memory_state.notice = Some(
                            language
                                .text(TextKey::MemoryPageMappingLoadedNote)
                                .to_owned(),
                        );
                        this.memory_state.refresh_export_availability();
                        this.import_started = None;
                        this.import_duration = Some(started.elapsed());
                        #[cfg(debug_assertions)]
                        this.memory_debug_seed(cx);
                        if view_mode == MemoryViewMode::Dominators {
                            this.memory_load_dominators(None, cx);
                        }
                    }
                    Err(error) => {
                        this.memory_state.error = Some(mapping_message(language, error));
                        this.memory_resume_dominator_queries(cx);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mapping_completion_rejects_a_to_b_switch_with_matching_generations_and_path() {
        let mut workspace = ImportWorkspace::new(Destination::Memory, Language::English);
        workspace.generation = 17;
        workspace.memory_snapshot_id = Some(41);
        workspace.path = Some(PathBuf::from("/same/snapshot.hprof"));
        workspace.memory_state.load_generation = 3;
        let generation = workspace.generation;
        let load_generation = workspace.memory_state.load_generation;
        let snapshot_id = workspace.memory_snapshot_id.unwrap();
        let path = workspace.path.clone();
        assert!(workspace.memory_mapping_is_current(generation, load_generation, snapshot_id));

        // Switching tabs restores B's per-tab state but not the workspace generation.
        workspace.memory_state = super::super::state::MemoryState::new();
        workspace.memory_state.load_generation = load_generation;
        workspace.memory_snapshot_id = Some(42);
        assert_eq!(workspace.path, path);
        assert_eq!(workspace.generation, generation);
        assert_eq!(workspace.memory_state.load_generation, load_generation);
        assert!(
            !workspace.memory_mapping_is_current(generation, load_generation, snapshot_id),
            "A's mapping completion must not publish into B even when both generations and paths match"
        );
    }

    #[test]
    fn mapping_completion_requires_the_origin_snapshot_and_both_generations() {
        let mut workspace = ImportWorkspace::new(Destination::Memory, Language::English);
        let snapshot_id = 41;
        let generation = 17;
        let load_generation = 3;
        for (active_id, current_generation, current_load_generation, expected) in [
            (Some(snapshot_id), generation, load_generation, true),
            (Some(42), generation, load_generation, false),
            (None, generation, load_generation, false),
            (Some(snapshot_id), generation + 1, load_generation, false),
            (Some(snapshot_id), generation, load_generation + 1, false),
        ] {
            workspace.memory_snapshot_id = active_id;
            workspace.generation = current_generation;
            workspace.memory_state.load_generation = current_load_generation;
            assert_eq!(
                workspace.memory_mapping_is_current(generation, load_generation, snapshot_id),
                expected,
                "active={active_id:?}, generation={current_generation}, load={current_load_generation}"
            );
        }
    }

    #[test]
    fn discarded_mapping_clears_only_its_owned_busy_state_without_touching_b() {
        let mut workspace = ImportWorkspace::new(Destination::Memory, Language::English);
        workspace.generation = 17;
        workspace.memory_snapshot_id = Some(42);
        workspace.path = Some(PathBuf::from("/B.hprof"));
        workspace.busy = true;
        workspace.memory_state.load_generation = 3;
        workspace.memory_state.notice = Some("B notice".into());
        workspace.memory_state.error = Some("B error".into());
        let snapshot = Arc::new(aps_memory::MemorySnapshot::default());
        workspace.memory_state.snapshot = Some(snapshot.clone());
        let cancel = workspace.cancel.clone();

        workspace.discard_stale_memory_mapping(17);
        assert!(!workspace.busy);
        assert_eq!(workspace.memory_snapshot_id, Some(42));
        assert_eq!(workspace.path, Some(PathBuf::from("/B.hprof")));
        assert_eq!(workspace.memory_state.load_generation, 3);
        assert_eq!(workspace.memory_state.notice.as_deref(), Some("B notice"));
        assert_eq!(workspace.memory_state.error.as_deref(), Some("B error"));
        assert!(Arc::ptr_eq(
            workspace.memory_state.snapshot.as_ref().unwrap(),
            &snapshot
        ));
        assert!(!cancel.is_cancelled());

        workspace.busy = true;
        workspace.generation += 1;
        workspace.discard_stale_memory_mapping(17);
        assert!(
            workspace.busy,
            "old completion must not clear a newer operation's busy flag"
        );
        workspace.memory_snapshot_id = Some(41);
        workspace.memory_state.load_generation += 1;
        workspace.discard_stale_memory_mapping(18);
        assert!(
            !workspace.busy,
            "returning to A must not leave the finished mapping busy"
        );
    }

    #[test]
    fn mapping_checks_identity_before_publishing_success_failure_or_loading_state() {
        let source = include_str!("mapping.rs")
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        let import = source
            .split("pub(crate) fn memory_import_mapping(")
            .nth(1)
            .unwrap();
        let compact: String = import
            .lines()
            .filter(|line| !line.trim_start().starts_with("//"))
            .flat_map(|line| line.chars().filter(|c| !c.is_whitespace()))
            .collect();
        let (preparation, publication) = compact.split_once("cx.spawn(").unwrap();
        assert!(preparation.contains("letSome(snapshot_id)=self.memory_snapshot_idelse{return;};"));
        let (_, callback) = publication.split_once("this.update(cx,|this,cx|{").unwrap();
        let guarded = callback
            .strip_prefix(
                "if!this.memory_mapping_is_current(generation,load_generation,snapshot_id){",
            )
            .expect("mapping must validate the frozen request identity at callback entry");
        let (rejection, publication) = guarded.split_once("return;}").unwrap();
        assert_eq!(
            rejection,
            "this.discard_stale_memory_mapping(generation);cx.notify();"
        );
        assert!(publication.starts_with("this.busy=false;this.memory_state.loading=false;"));
        assert!(
            publication
                .contains("this.session=Some(Session::Memory(Arc::new(Mutex::new(session))));")
        );
        assert!(publication.contains(
            "Err(error)=>{this.memory_state.error=Some(mapping_message(language,error));this.memory_resume_dominator_queries(cx);}"
        ));
        assert!(!publication.contains("this.session=None"));
        assert!(!publication.contains("this.memory_snapshot_id="));
        assert!(!publication.contains("this.path="));
    }

    #[test]
    fn mapping_start_invalidates_pending_queries_and_failure_preserves_published_pages() {
        let source = include_str!("mapping.rs")
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        let import = source
            .split("pub(crate) fn memory_import_mapping(")
            .nth(1)
            .unwrap();
        let compact: String = import
            .lines()
            .filter(|line| !line.trim_start().starts_with("//"))
            .flat_map(|line| line.chars().filter(|c| !c.is_whitespace()))
            .collect();
        let preparation = compact
            .split("letcancel=self.cancel.clone();")
            .next()
            .unwrap();
        assert!(
            preparation.contains("self.memory_state.invalidate_pending_queries();letload_generation=self.memory_state.load_generation;self.memory_state.loading=true;"),
            "invalidate all pending queries before freezing the mapping load epoch and setting loading"
        );
        assert!(!preparation.contains("reset_for_snapshot("));
        assert!(!preparation.contains("self.memory_state="));
        assert!(!preparation.contains("self.session="));
        assert!(compact.contains("this.busy=false;this.memory_state.loading=false;this.memory_state.loading_message=None;matchresult{"));
        let failure = compact
            .split_once("Err(error)=>{")
            .expect("mapping failure must have an explicit recovery block")
            .1
            .split_once("}}cx.notify();")
            .expect("notify only after mapping failure recovery")
            .0;
        assert_eq!(
            failure,
            "this.memory_state.error=Some(mapping_message(language,error));this.memory_resume_dominator_queries(cx);",
            "failed mapping must only publish its error and resume missing queries without replacing published data"
        );
    }

    #[test]
    fn mapping_import_time_is_published_only_after_the_new_snapshot_is_ready() {
        let source = include_str!("mapping.rs");
        let import = source
            .split("pub(crate) fn memory_import_mapping(")
            .nth(1)
            .unwrap()
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        assert!(
            import.find("let started = Instant::now();").unwrap()
                < import.find("background_executor()").unwrap()
        );
        let success = import
            .split("Ok((session, snapshot, entries)) =>")
            .nth(1)
            .unwrap()
            .split("Err(error)")
            .next()
            .unwrap();
        assert!(
            success.find("reset_for_snapshot(").unwrap()
                < success
                    .find("this.import_duration = Some(started.elapsed());")
                    .unwrap()
        );
        assert!(
            !import
                .split("match result")
                .next()
                .unwrap()
                .contains("this.import_duration =")
        );
    }

    #[test]
    fn parses_class_rows_and_ignores_member_rows() {
        let text = "\
# comment
com.example.MainActivity -> a.b.C:
    int field -> a
    void method() -> b
com.example.Outer$Inner -> a.b$c:
android.app.Activity -> android.app.Activity:
";
        let mapping = parse_mapping(text).unwrap();
        assert_eq!(
            mapping,
            vec![
                ("a.b$c".to_owned(), "com.example.Outer$Inner".to_owned()),
                ("a.b.C".to_owned(), "com.example.MainActivity".to_owned()),
                (
                    "android.app.Activity".to_owned(),
                    "android.app.Activity".to_owned()
                ),
            ]
        );
    }

    #[::core::prelude::v1::test]
    fn reads_bounded_mapping_files_and_rejects_oversize_input() {
        let directory =
            std::env::temp_dir().join(format!("aps-mapping-import-{}-bounded", std::process::id()));
        let _ = std::fs::remove_dir_all(&directory);
        std::fs::create_dir_all(&directory).unwrap();
        let path = directory.join("mapping.txt");
        std::fs::write(&path, "com.example.C -> a.b.C:\n").unwrap();
        assert_eq!(
            read_mapping_file(&path).unwrap(),
            vec![("a.b.C".to_owned(), "com.example.C".to_owned())]
        );
        let non_text = directory.join("not-text.txt");
        std::fs::write(&non_text, [0xffu8, 0xfe, 0x00]).unwrap();
        assert!(read_mapping_file(&non_text).is_err());
        let oversized = directory.join("oversized.txt");
        std::fs::write(
            &oversized,
            vec![b'x'; (MAX_MAPPING_FILE_BYTES as usize) + 1],
        )
        .unwrap();
        assert!(read_mapping_file(&oversized).is_err());
        std::fs::remove_dir_all(&directory).unwrap();
    }

    #[test]
    fn rejects_empty_and_unbounded_mappings() {
        assert_eq!(
            parse_mapping("").unwrap_err(),
            "mapping.txt contains no class mappings"
        );
        assert_eq!(
            parse_mapping("# only comments\n").unwrap_err(),
            "mapping.txt contains no class mappings"
        );
        let too_long_name = format!("{} -> a:\n", "x".repeat(4097));
        assert!(parse_mapping(&too_long_name).is_err());
        let too_many = (0..=MAX_MAPPING_ENTRIES)
            .map(|index| format!("a.B{index} -> a.b{index}:\n"))
            .collect::<String>();
        assert!(parse_mapping(&too_many).is_err());
    }
}
