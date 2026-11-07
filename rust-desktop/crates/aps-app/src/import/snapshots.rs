//! Bounded, in-memory HPROF tab ownership. Each tab owns its worker-backed
//! session; switching never reopens or reparses the source file.
use super::*;

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
    pub page: PageData,
    pub session_id: String,
    pub view: MemoryView,
    pub published_view: MemoryView,
    pub history: Vec<(MemoryView, usize)>,
    pub panel: MemoryPanel,
    pub ui: memory_page::MemoryUi,
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
            || self.page.is_none()
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
            page: self.page.take()?,
            session_id: self.session_id.take()?,
            view: std::mem::replace(&mut self.memory_view, MemoryView::Histogram),
            published_view: std::mem::replace(
                &mut self.published_memory_view,
                MemoryView::Histogram,
            ),
            history: std::mem::take(&mut self.memory_history),
            panel: std::mem::replace(&mut self.memory_panel, MemoryPanel::Dashboard),
            ui: std::mem::take(&mut self.memory_ui),
        })
    }

    fn restore_memory_snapshot(&mut self, snapshot: MemorySnapshot) {
        self.memory_snapshot_id = Some(snapshot.id);
        self.path = Some(snapshot.path);
        self.session = Some(Session::Memory(snapshot.session));
        self.page = Some(snapshot.page);
        self.session_id = Some(snapshot.session_id);
        self.memory_view = snapshot.view;
        self.published_memory_view = snapshot.published_view;
        self.memory_history = snapshot.history;
        self.memory_panel = snapshot.panel;
        self.memory_ui = snapshot.ui;
    }

    pub(super) fn select_memory_snapshot(&mut self, id: u64, cx: &mut Context<Self>) {
        if self.kind != Destination::Memory || self.busy || self.memory_snapshot_id == Some(id) {
            return;
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
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        self.export_generation += 1;
        self.export_busy = false;
        self.error = None;
        self.notice = None;
        self.memory_diff_report = None;
        self.memory_diff_page = None;
        self.restore_memory_snapshot(selected);
        if self.memory_panel == MemoryPanel::Diff {
            self.compare_memory_snapshots(cx);
        }
        cx.notify();
    }

    pub(super) fn close_memory_snapshot(&mut self, id: u64, cx: &mut Context<Self>) {
        if self.kind != Destination::Memory {
            return;
        }
        if self.memory_snapshot_id != Some(id) {
            if self.busy {
                return;
            }
            if let Some(index) = self
                .memory_snapshots
                .iter()
                .position(|snapshot| snapshot.id == id)
            {
                // Dropping the last Arc kills and reaps this tab's worker.
                self.memory_snapshots.remove(index);
                self.memory_diff_report = None;
                self.memory_diff_page = None;
                if self.memory_panel == MemoryPanel::Diff {
                    self.compare_memory_snapshots(cx);
                }
                cx.notify();
            }
            return;
        }
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        self.export_generation += 1;
        self.export_busy = false;
        self.busy = false;
        drop(self.take_active_memory_snapshot());
        self.error = None;
        self.notice = None;
        self.memory_diff_report = None;
        self.memory_diff_page = None;
        if let Some(previous) = self.memory_snapshots.pop() {
            self.restore_memory_snapshot(previous);
        } else {
            self.memory_snapshot_id = None;
            self.session = None;
            self.path = None;
            self.page = None;
            self.session_id = None;
            self.memory_view = MemoryView::Histogram;
            self.published_memory_view = MemoryView::Histogram;
            self.memory_history.clear();
            self.memory_panel = MemoryPanel::Dashboard;
            self.memory_ui = memory_page::MemoryUi::default();
        }
        if self.memory_panel == MemoryPanel::Diff {
            self.compare_memory_snapshots(cx);
        }
        cx.notify();
    }

    /// The newest inactive tab is the candidate baseline, the active tab is the
    /// candidate comparison. Callers must compare class aggregates/provenance,
    /// never treat equal object IDs from two dumps as the same object.
    pub(super) fn memory_comparison_pair(
        &self,
    ) -> Option<(Arc<Mutex<MemorySession>>, Arc<Mutex<MemorySession>>)> {
        let before = self.memory_snapshots.last()?.session.clone();
        let Some(Session::Memory(after)) = &self.session else {
            return None;
        };
        Some((before, after.clone()))
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
}
