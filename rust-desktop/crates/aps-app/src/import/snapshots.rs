//! Bounded, in-memory HPROF tab ownership. Each tab owns its worker-backed
//! session and its typed workspace state; switching never reopens or reparses
//! the source file.
use super::*;
use crate::import::memory::state::{MemoryState, MemoryViewMode};

pub(super) const MAX_OPEN_MEMORY_SNAPSHOTS: usize = 3;
static NEXT_MEMORY_SNAPSHOT: AtomicU64 = AtomicU64::new(1);

pub(super) fn new_memory_snapshot_id() -> Option<u64> {
    NEXT_MEMORY_SNAPSHOT
        .try_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
        .ok()
}

pub(super) fn memory_tab_limit_message(language: Language) -> UiText {
    if language == Language::SimplifiedChinese {
        "最多同时打开 3 个 HPROF 快照。请先关闭一个快照，再导入新文件。".into()
    } else {
        "At most 3 HPROF snapshots may be open. Close one before importing another.".into()
    }
}

pub(super) fn memory_identity_exhausted_message(language: Language) -> UiText {
    if language == Language::SimplifiedChinese {
        "HPROF 快照身份空间已耗尽；保留当前快照。".into()
    } else {
        "HPROF snapshot identity space is exhausted; the current snapshot was retained.".into()
    }
}

pub(super) struct MemorySnapshot {
    pub id: u64,
    pub path: PathBuf,
    pub session: Arc<Mutex<MemorySession>>,
    pub state: MemoryState,
    pub session_id: String,
    pub import_duration: Option<Duration>,
}

fn label(path: &Path) -> String {
    path.file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .unwrap_or("HPROF snapshot")
        .to_owned()
}

impl ImportWorkspace {
    /// `(identity, visible filename, active)` in import order. The filename is
    /// a label only; identity is monotonic and distinct from an HPROF object ID.
    pub(super) fn memory_snapshot_tabs(&self) -> Vec<(u64, String, bool)> {
        if self.kind != Destination::Memory {
            return Vec::new();
        }
        let mut tabs = self
            .memory_snapshots
            .iter()
            .map(|snapshot| (snapshot.id, label(&snapshot.path), false))
            .collect::<Vec<_>>();
        if let (Some(id), Some(path), Some(Session::Memory(_))) = (
            self.memory_snapshot_id,
            self.path.as_deref(),
            self.session.as_ref(),
        ) {
            tabs.push((id, label(path), true));
        }
        tabs.sort_by_key(|tab| tab.0);
        tabs
    }

    pub(super) fn take_active_memory_snapshot(&mut self) -> Option<MemorySnapshot> {
        if self.kind != Destination::Memory
            || self.memory_snapshot_id.is_none()
            || self.path.is_none()
            || self.session_id.is_none()
            || !matches!(self.session, Some(Session::Memory(_)))
        {
            return None;
        }
        let Session::Memory(session) = self.session.take()? else {
            unreachable!("prechecked Memory session");
        };
        Some(MemorySnapshot {
            id: self.memory_snapshot_id.take()?,
            path: self.path.take()?,
            session,
            state: std::mem::take(&mut self.memory_state),
            session_id: self.session_id.take()?,
            import_duration: self.import_duration.take(),
        })
    }

    fn restore_memory_snapshot(&mut self, snapshot: MemorySnapshot) {
        self.memory_snapshot_id = Some(snapshot.id);
        self.path = Some(snapshot.path);
        self.session = Some(Session::Memory(snapshot.session));
        self.session_id = Some(snapshot.session_id);
        self.memory_state = snapshot.state;
        self.import_duration = snapshot.import_duration;
        self.import_started = None;
    }

    pub(super) fn select_memory_snapshot(&mut self, id: u64, cx: &mut Context<Self>) {
        if self.kind != Destination::Memory || self.memory_snapshot_id == Some(id) {
            return;
        }
        if self.memory_capture.in_progress {
            self.cancel_work(cx);
        }
        let Some(index) = self
            .memory_snapshots
            .iter()
            .position(|snapshot| snapshot.id == id)
        else {
            return;
        };
        let selected = self.memory_snapshots.remove(index);
        if let Some(previous) = self.take_active_memory_snapshot() {
            self.memory_snapshots.push(previous);
        }
        self.memory_state.error = None;
        self.memory_state.notice = None;
        self.restore_memory_snapshot(selected);
        self.memory_invalidate_comparison(cx);
        cx.notify();
    }

    /// A different active snapshot invalidates an already-published class-level
    /// comparison; recompute it when the Diff view is visible.
    fn memory_invalidate_comparison(&mut self, cx: &mut Context<Self>) {
        if self.memory_state.view_mode != MemoryViewMode::Diff {
            return;
        }
        self.memory_state.comparison = None;
        self.memory_state.comparison_pair = None;
        self.memory_state.comparison_error = None;
        self.memory_compare_snapshots(cx);
    }

    pub(super) fn close_memory_snapshot(&mut self, id: u64, cx: &mut Context<Self>) {
        if self.kind != Destination::Memory {
            return;
        }
        if self.memory_capture.in_progress {
            self.cancel_work(cx);
        }
        if self.memory_snapshot_id != Some(id) {
            if let Some(index) = self
                .memory_snapshots
                .iter()
                .position(|snapshot| snapshot.id == id)
            {
                // Dropping the last Arc kills and reaps this tab's worker.
                self.memory_snapshots.remove(index);
                self.memory_invalidate_comparison(cx);
                cx.notify();
            }
            return;
        }
        drop(self.take_active_memory_snapshot());
        self.memory_state = MemoryState::new();
        self.memory_state.error = None;
        self.memory_state.notice = None;
        self.session = None;
        self.path = None;
        self.page = None;
        self.session_id = None;
        self.import_duration = None;
        self.import_started = None;
        if let Some(previous) = self.memory_snapshots.pop() {
            self.restore_memory_snapshot(previous);
        }
        self.memory_invalidate_comparison(cx);
        cx.notify();
    }

    /// The newest inactive tab is the candidate baseline, the active tab is the
    /// candidate comparison. Object IDs never cross the snapshot boundary.
    pub(super) fn memory_comparison_pair(
        &self,
    ) -> Option<(
        Arc<aps_memory::MemorySnapshot>,
        Arc<aps_memory::MemorySnapshot>,
        String,
        String,
    )> {
        let before = self.memory_snapshots.last()?;
        let after: Arc<aps_memory::MemorySnapshot> = self.memory_state.snapshot.clone()?;
        Some((
            before.state.snapshot.clone()?,
            after,
            label(&before.path),
            self.memory_state.snapshot_label.clone(),
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn tab_identity_is_monotonic_and_independent_of_filename() {
        let first = new_memory_snapshot_id().unwrap();
        let second = new_memory_snapshot_id().unwrap();
        assert!(second > first);
        assert_eq!(
            label(Path::new("/one/same.hprof")),
            label(Path::new("/two/same.hprof"))
        );
    }

    #[::core::prelude::v1::test]
    fn empty_memory_tabs_and_comparison_do_not_fabricate_a_session() {
        let workspace = ImportWorkspace::new(Destination::Memory, Language::English);
        assert!(workspace.memory_snapshot_tabs().is_empty());
        assert!(workspace.memory_comparison_pair().is_none());
    }

    #[::core::prelude::v1::test]
    fn import_duration_is_owned_by_each_snapshot_not_the_last_opened_tab() {
        let source = include_str!("snapshots.rs");
        let take = source
            .split("fn take_active_memory_snapshot(")
            .nth(1)
            .unwrap()
            .split("fn restore_memory_snapshot(")
            .next()
            .unwrap();
        assert!(take.contains("import_duration: self.import_duration.take()"));
        let restore = source
            .split("fn restore_memory_snapshot(")
            .nth(1)
            .unwrap()
            .split("fn select_memory_snapshot(")
            .next()
            .unwrap();
        assert!(restore.contains("self.import_duration = snapshot.import_duration"));
        let close = source
            .split("fn close_memory_snapshot(")
            .nth(1)
            .unwrap()
            .split("fn memory_comparison_pair(")
            .next()
            .unwrap();
        assert!(close.contains("self.import_duration = None"));
    }
}
