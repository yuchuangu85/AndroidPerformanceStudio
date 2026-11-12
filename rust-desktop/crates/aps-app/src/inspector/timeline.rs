use aps_layout_inspector::InspectorArchive;
use std::{collections::VecDeque, sync::Arc};

use crate::import_timing::ImportTiming;

const MAX_FRAMES: usize = 32;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum Source {
    Offline,
    Adb,
    Agent,
    Compose,
}

#[derive(Clone)]
pub(super) struct Frame {
    pub(super) id: u64,
    pub(super) archive: Arc<InspectorArchive>,
    pub(super) source: Source,
    pub(super) show_system_composables: bool,
    pub(super) import_timing: ImportTiming,
}

#[derive(Clone, Default)]
pub(super) struct History {
    frames: VecDeque<Frame>,
    selected: Option<u64>,
    next_id: u64,
}

impl History {
    pub(super) fn frames(&self) -> &VecDeque<Frame> {
        &self.frames
    }

    pub(super) fn selected(&self) -> Option<&Frame> {
        let id = self.selected?;
        self.frames.iter().find(|frame| frame.id == id)
    }

    pub(super) fn selected_mut(&mut self) -> Option<&mut Frame> {
        let id = self.selected?;
        self.frames.iter_mut().find(|frame| frame.id == id)
    }

    pub(super) fn append(
        &mut self,
        archive: Arc<InspectorArchive>,
        source: Source,
        show_system_composables: bool,
        import_timing: ImportTiming,
    ) {
        let id = self.next_id;
        self.next_id = self
            .next_id
            .checked_add(1)
            .expect("inspector frame ID exhausted");
        self.frames.push_back(Frame {
            id,
            archive,
            source,
            show_system_composables,
            import_timing,
        });
        if self.frames.len() > MAX_FRAMES {
            self.frames.pop_front();
        }
        self.selected = Some(id);
    }

    pub(super) fn replace(
        &mut self,
        archive: Arc<InspectorArchive>,
        source: Source,
        show_system_composables: bool,
        import_timing: ImportTiming,
    ) {
        self.frames.clear();
        self.selected = None;
        self.append(archive, source, show_system_composables, import_timing);
    }

    pub(super) fn select(&mut self, id: u64) -> bool {
        if !self.frames.iter().any(|frame| frame.id == id) {
            return false;
        }
        self.selected = Some(id);
        true
    }

    pub(super) fn remove(&mut self, id: u64) -> bool {
        let Some(index) = self.frames.iter().position(|frame| frame.id == id) else {
            return false;
        };
        self.frames.remove(index);
        if self.selected == Some(id) {
            self.selected = self
                .frames
                .get(index)
                .or_else(|| self.frames.back())
                .map(|frame| frame.id);
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_contracts::SourceDigest;
    use aps_layout_inspector::{
        Bounds, Capabilities, DisplayInfo, FrameIdentity, LayoutNode, LayoutWindow, NodeKind,
    };
    use std::time::Duration;

    fn archive(captured_at_epoch_millis: i64) -> Arc<InspectorArchive> {
        let bounds = Bounds {
            left: 0,
            top: 0,
            right: 1,
            bottom: 1,
        };
        Arc::new(InspectorArchive {
            archive_version: 1,
            frame: FrameIdentity {
                source_digest: SourceDigest::from_bytes([7; 32]),
                package_name: "example.timeline".into(),
                captured_at_epoch_millis,
                protocol_major: 1,
                protocol_minor: 1,
            },
            display: DisplayInfo {
                width_px: 1,
                height_px: 1,
                density: 1.0,
            },
            capabilities: Capabilities {
                view_hierarchy: true,
                compose_semantics: false,
                screenshots: false,
                timeline: false,
            },
            windows: vec![LayoutWindow {
                id: "main".into(),
                title: None,
                kind: "ACTIVITY".into(),
                bounds,
                nodes: vec![LayoutNode {
                    id: "root".into(),
                    kind: NodeKind::View,
                    class_name: "android.widget.FrameLayout".into(),
                    parent_index: None,
                    depth: 0,
                    bounds,
                    visible: true,
                    alpha: 1.0,
                    resource_name: None,
                    text: None,
                    semantics_role: None,
                    attributes: Default::default(),
                }],
            }],
            default_window_id: "main".into(),
            screenshot: None,
            screenshot_png: None,
            compose: None,
            export_payload: Default::default(),
        })
    }

    fn append(history: &mut History, archive: &Arc<InspectorArchive>) -> u64 {
        history.append(
            Arc::clone(archive),
            Source::Offline,
            false,
            ImportTiming::default(),
        );
        history.selected().unwrap().id
    }

    #[test]
    fn empty_history_has_no_selection_or_removable_frames() {
        let mut history = History::default();
        assert!(history.frames().is_empty());
        assert!(history.selected().is_none());
        assert!(history.selected_mut().is_none());
        assert!(!history.select(0));
        assert!(!history.remove(0));
        assert!(history.selected().is_none());
    }

    #[test]
    fn append_keeps_old_archive_arcs_and_selects_the_latest_frame() {
        let mut history = History::default();
        let old = archive(1);
        let new = archive(2);
        let first = append(&mut history, &old);
        let second = append(&mut history, &new);

        assert_eq!(history.frames().len(), 2);
        assert_eq!(second, first + 1);
        assert_eq!(history.selected().unwrap().id, second);
        assert!(Arc::ptr_eq(&history.frames()[0].archive, &old));
        assert!(Arc::ptr_eq(&history.frames()[1].archive, &new));
        assert_eq!(
            history.frames()[0].archive.frame.captured_at_epoch_millis,
            1
        );
        assert_eq!(Arc::strong_count(&old), 2);
        let cloned_frame = history.frames()[0].clone();
        assert_eq!(cloned_frame.id, first);
        assert!(Arc::ptr_eq(&cloned_frame.archive, &old));
    }

    #[test]
    fn selecting_old_or_unknown_frames_never_appends_or_consumes_ids() {
        let mut history = History::default();
        let archive = archive(1);
        let first = append(&mut history, &archive);
        let second = append(&mut history, &archive);

        assert!(history.select(first));
        assert!(history.select(first));
        assert!(!history.select(u64::MAX));
        assert_eq!(history.selected().unwrap().id, first);
        assert_eq!(history.frames().len(), 2);
        assert_eq!(history.frames()[1].id, second);
        assert_eq!(Arc::strong_count(&archive), 3);
        assert_eq!(append(&mut history, &archive), second + 1);
    }

    #[test]
    fn capacity_evicts_oldest_arcs_and_keeps_ids_monotonic() {
        let mut history = History::default();
        let old = archive(1);
        let weak = Arc::downgrade(&old);
        let first = append(&mut history, &old);
        drop(old);
        let archive = archive(2);
        let mut latest = first;

        for _ in 0..MAX_FRAMES * 2 {
            let id = append(&mut history, &archive);
            assert_eq!(id, latest + 1);
            assert!(history.frames().len() <= MAX_FRAMES);
            assert_eq!(history.selected().unwrap().id, id);
            latest = id;
        }

        assert_eq!(history.frames().len(), MAX_FRAMES);
        assert!(weak.upgrade().is_none());
        assert_eq!(
            history
                .frames()
                .iter()
                .map(|frame| frame.id)
                .collect::<Vec<_>>(),
            (latest + 1 - MAX_FRAMES as u64..=latest).collect::<Vec<_>>()
        );
        assert!(!history.select(first));
        assert!(!history.remove(first));
        assert_eq!(history.selected().unwrap().id, latest);
    }

    #[test]
    fn removing_current_frame_prefers_right_then_left_then_none() {
        let mut history = History::default();
        let archive = archive(1);
        let first = append(&mut history, &archive);
        let middle = append(&mut history, &archive);
        let last = append(&mut history, &archive);

        assert!(history.select(first));
        assert!(history.remove(first));
        assert_eq!(history.selected().unwrap().id, middle);
        let newest = append(&mut history, &archive);
        assert!(history.select(last));
        assert!(history.remove(last));
        assert_eq!(history.selected().unwrap().id, newest);
        assert!(history.remove(newest));
        assert_eq!(history.selected().unwrap().id, middle);
        assert!(history.remove(middle));
        assert!(history.frames().is_empty());
        assert!(history.selected().is_none());
        assert!(history.selected_mut().is_none());
        assert_eq!(append(&mut history, &archive), newest + 1);
    }

    #[test]
    fn removing_other_or_unknown_frames_preserves_selection() {
        let mut history = History::default();
        let archive = archive(1);
        let first = append(&mut history, &archive);
        let middle = append(&mut history, &archive);
        let last = append(&mut history, &archive);

        assert!(history.select(middle));
        assert!(!history.remove(u64::MAX));
        assert_eq!(history.frames().len(), 3);
        assert_eq!(history.selected().unwrap().id, middle);
        assert!(history.remove(first));
        assert_eq!(history.selected().unwrap().id, middle);
        assert!(history.remove(last));
        assert_eq!(history.selected().unwrap().id, middle);
        assert_eq!(history.frames().len(), 1);
        assert!(!history.remove(first));
        assert_eq!(history.selected().unwrap().id, middle);
    }

    #[test]
    fn replace_clears_frames_without_resetting_ids() {
        let mut history = History::default();
        let old = archive(1);
        let first = append(&mut history, &old);
        let last = append(&mut history, &old);
        assert!(history.select(first));
        let new = archive(2);
        let timing = ImportTiming::from_duration(Duration::from_millis(123));

        for source in [Source::Agent, Source::Compose] {
            let previous_id = history.frames().back().unwrap().id;
            history.replace(Arc::clone(&new), source, true, timing);
            assert_eq!(history.frames().len(), 1);
            let selected = history.selected().unwrap();
            assert_eq!(selected.id, previous_id + 1);
            assert!(Arc::ptr_eq(&selected.archive, &new));
            assert_eq!(selected.source, source);
            assert!(selected.show_system_composables);
            assert_eq!(selected.import_timing, timing);
            assert!(!history.select(previous_id));
        }
        assert!(!history.select(first));
        assert!(!history.select(last));
        assert_eq!(Arc::strong_count(&old), 1);
    }

    #[test]
    fn each_frame_retains_its_own_source_flag_and_timing() {
        let mut history = History::default();
        let archive = archive(1);
        let metadata = [
            (Source::Offline, false, ImportTiming::default()),
            (
                Source::Adb,
                true,
                ImportTiming::from_duration(Duration::ZERO),
            ),
            (
                Source::Agent,
                false,
                ImportTiming::from_duration(Duration::from_millis(12)),
            ),
            (
                Source::Compose,
                true,
                ImportTiming::from_duration(Duration::from_secs(3)),
            ),
        ];

        for (source, flag, timing) in metadata {
            history.append(Arc::clone(&archive), source, flag, timing);
        }
        for (frame, (source, flag, timing)) in history.frames().iter().zip(metadata) {
            assert_eq!(frame.source, source);
            assert_eq!(frame.show_system_composables, flag);
            assert_eq!(frame.import_timing, timing);
        }
    }

    #[test]
    fn selected_mut_records_completed_timing_only_on_the_selected_frame() {
        let mut history = History::default();
        let archive = archive(1);
        let first = append(&mut history, &archive);
        let last = append(&mut history, &archive);
        let timing = ImportTiming::from_duration(Duration::from_millis(250));

        assert!(history.select(first));
        history.selected_mut().unwrap().import_timing = timing;
        assert_eq!(history.selected().unwrap().import_timing, timing);
        assert!(!history.select(u64::MAX));
        assert_eq!(history.selected_mut().unwrap().id, first);
        assert!(history.select(last));
        assert_eq!(
            history.selected().unwrap().import_timing,
            ImportTiming::default()
        );
        assert_eq!(history.frames().len(), 2);
        assert_eq!(history.frames()[0].import_timing, timing);
    }

    #[test]
    fn cloned_history_shares_archives_but_isolates_candidate_changes() {
        let mut history = History::default();
        let archive = archive(1);
        let first = append(&mut history, &archive);
        let last = append(&mut history, &archive);
        let mut candidate = history.clone();
        assert!(Arc::ptr_eq(
            &history.selected().unwrap().archive,
            &candidate.selected().unwrap().archive,
        ));

        let selected = candidate.selected_mut().unwrap();
        selected.source = Source::Compose;
        selected.show_system_composables = true;
        selected.import_timing = ImportTiming::from_duration(Duration::from_millis(5));
        assert_eq!(history.selected().unwrap().source, Source::Offline);
        assert!(!history.selected().unwrap().show_system_composables);
        assert_eq!(
            history.selected().unwrap().import_timing,
            ImportTiming::default()
        );
        assert!(candidate.remove(last));
        assert_eq!(candidate.selected().unwrap().id, first);
        assert!(candidate.remove(first));
        assert!(candidate.selected().is_none());
        assert_eq!(history.frames().len(), 2);
        assert_eq!(history.selected().unwrap().id, last);
        assert_eq!(append(&mut candidate, &archive), last + 1);
        assert_eq!(append(&mut history, &archive), last + 1);
    }
}
