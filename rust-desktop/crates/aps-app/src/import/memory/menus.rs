//! Memory Profiler route menus.
//!
//! The HPROF adapter keeps its view modes, snapshot tabs and page state in the
//! memory module, so its menus live here next to that state instead of in the
//! shared import menu module. The File menu follows Compose's
//! MemoryProfilerFileMenu: Open…, Import ▸, Open Recent ▸, separator, Export ▸
//! in its three groups. Capabilities that are not wired in this build render
//! disabled, exactly as Compose renders an unavailable capability.

use super::super::menus::ImportMenuCommand;
use super::super::*;
use super::advanced::AdvancedMemoryKind;
use super::exports::MemoryExportKind;
use super::state::MemoryViewMode;
use crate::shell::menubar::MenuGroup;
use aps_ui_foundation::{Language, TextKey};

/// Default Diff pair: the newest inactive snapshot is A and the active
/// snapshot is B. Snapshot tab identities are monotonic in import order, so the
/// maximum non-active id is the most recently imported other snapshot.
pub(super) fn default_comparison_pair(
    tabs: &[(u64, String, bool)],
    active: u64,
) -> Option<(u64, u64)> {
    let newest_inactive = tabs
        .iter()
        .filter(|(id, _, _)| *id != active)
        .map(|(id, _, _)| *id)
        .max()?;
    Some((newest_inactive, active))
}

/// Memory view modes in menu order. The shared import menu command carries the
/// index so the command enum does not have to expose this module's type.
pub(crate) fn memory_view_mode(index: u8) -> Option<MemoryViewMode> {
    MemoryViewMode::PRIMARY
        .into_iter()
        .chain([
            MemoryViewMode::LeakAnalysis,
            MemoryViewMode::BitmapDump,
            MemoryViewMode::NativeHeap,
            MemoryViewMode::SampledArt,
        ])
        .nth(index as usize)
}

impl ImportWorkspace {
    pub(crate) fn memory_menu_groups(
        &self,
        language: Language,
    ) -> Vec<MenuGroup<ImportMenuCommand>> {
        let loaded = self.memory_state.snapshot.is_some();
        let current = self.memory_state.view_mode;
        let file = Self::open_row(MenuGroup::new(TextKey::ShellMenuFile), !self.busy);
        // Compose memory imports add a dataset to the loaded session. The Rust
        // HPROF import is the Java heap import (it opens another snapshot tab);
        // mapping deobfuscates the loaded heap; Native/ART traces use their
        // dedicated adapters and never replace the Java snapshot evidence.
        let file = file.submenu(TextKey::ShellMenuImport, true, |menu| {
            menu.item_when(
                self.can_open_home_artifact(),
                TextKey::MemoryAppMenuImportJavaHeap,
                ImportMenuCommand::Pick,
            )
            .item_when(
                !self.busy && !self.export_busy,
                TextKey::MemoryAppMenuImportNativeHeap,
                ImportMenuCommand::MemoryImportAdvanced(AdvancedMemoryKind::Native),
            )
            .item_when(
                !self.busy && !self.export_busy,
                TextKey::MemoryAppMenuImportSampledArt,
                ImportMenuCommand::MemoryImportAdvanced(AdvancedMemoryKind::Art),
            )
            .item_when(
                self.memory_mapping_enabled(),
                TextKey::MemoryAppMenuImportMapping,
                ImportMenuCommand::MemoryImportMapping,
            )
        });
        let file = Self::open_recent(file, false);
        // Compose groups: raw/standard HPROF, native heap, ART sample | CSV,
        // class instances, object investigation, diff, snapshot JSON,
        // investigation report | bitmap dump, bitmap comparison. Rows backed by
        // a live Rust adapter follow the current snapshot/selection; rows with
        // no adapter stay disabled exactly like Compose's unavailable state.
        let raw = self.memory_export_enabled(MemoryExportKind::RawHprof);
        let histogram = self.memory_export_enabled(MemoryExportKind::HistogramCsv);
        let instances = self.memory_export_enabled(MemoryExportKind::ClassInstancesCsv);
        let object = self.memory_export_enabled(MemoryExportKind::ObjectInvestigationJson);
        let diff = self.memory_export_enabled(MemoryExportKind::DiffCsv);
        let snapshot_json = self.memory_export_enabled(MemoryExportKind::SnapshotJson);
        let report = self.memory_export_enabled(MemoryExportKind::InvestigationReport);
        let file = file
            .separator()
            .submenu(TextKey::ShellMenuExport, true, |menu| {
                menu.item_when(
                    raw,
                    TextKey::MemoryAppExportRawHprof,
                    ImportMenuCommand::MemoryExport(MemoryExportKind::RawHprof),
                )
                .item_when(
                    false,
                    TextKey::MemoryAppExportStandardHprof,
                    ImportMenuCommand::Unavailable,
                )
                .item_when(
                    self.advanced_memory_export_enabled(AdvancedMemoryKind::Native),
                    TextKey::MemoryAppExportNativeHeapTrace,
                    ImportMenuCommand::MemoryExportAdvanced(AdvancedMemoryKind::Native),
                )
                .item_when(
                    self.advanced_memory_export_enabled(AdvancedMemoryKind::Art),
                    TextKey::MemoryAppExportSampledArtTrace,
                    ImportMenuCommand::MemoryExportAdvanced(AdvancedMemoryKind::Art),
                )
                .separator()
                .item_when(
                    histogram,
                    TextKey::MemoryAppExportCsv,
                    ImportMenuCommand::MemoryExport(MemoryExportKind::HistogramCsv),
                )
                .item_when(
                    instances,
                    TextKey::MemoryAppExportClassInstances,
                    ImportMenuCommand::MemoryExport(MemoryExportKind::ClassInstancesCsv),
                )
                .item_when(
                    object,
                    TextKey::MemoryAppExportObjectInvestigation,
                    ImportMenuCommand::MemoryExport(MemoryExportKind::ObjectInvestigationJson),
                )
                .item_when(
                    diff,
                    TextKey::MemoryAppExportDiff,
                    ImportMenuCommand::MemoryExport(MemoryExportKind::DiffCsv),
                )
                .item_when(
                    snapshot_json,
                    TextKey::MemoryAppExportSnapshotJson,
                    ImportMenuCommand::MemoryExport(MemoryExportKind::SnapshotJson),
                )
                .item_when(
                    report,
                    TextKey::MemoryAppExportInvestigationReport,
                    ImportMenuCommand::MemoryExport(MemoryExportKind::InvestigationReport),
                )
                .separator()
                .item_when(
                    self.advanced_memory_export_enabled(AdvancedMemoryKind::Bitmap),
                    TextKey::MemoryAppExportBitmapDump,
                    ImportMenuCommand::MemoryExportAdvanced(AdvancedMemoryKind::Bitmap),
                )
                .item_when(
                    false,
                    TextKey::MemoryAppExportBitmapComparison,
                    ImportMenuCommand::Unavailable,
                )
            });
        // Open snapshot tabs stay real Rust functionality, so they keep their
        // place after Compose's File rows instead of disappearing.
        let mut file = file;
        let tabs = self.memory_snapshot_tabs();
        if !tabs.is_empty() {
            file = file.separator();
            for (id, label, active) in &tabs {
                file = file.check(
                    label.clone(),
                    *active,
                    ImportMenuCommand::SelectMemorySnapshot(*id),
                );
            }
            if loaded {
                file = file.separator();
                for (id, label, _) in &tabs {
                    let close =
                        language.format(TextKey::MemoryPageCloseSnapshot, &[("arg_1", label)]);
                    file = file.item(close, ImportMenuCommand::CloseMemorySnapshot(*id));
                }
            }
        }
        let mut view = MenuGroup::new(TextKey::ShellMenuView);
        for (index, mode) in MemoryViewMode::PRIMARY
            .into_iter()
            .chain([
                MemoryViewMode::LeakAnalysis,
                MemoryViewMode::BitmapDump,
                MemoryViewMode::NativeHeap,
                MemoryViewMode::SampledArt,
            ])
            .enumerate()
        {
            view = view.check(
                mode.label(),
                current == mode,
                ImportMenuCommand::MemoryView(index as u8),
            );
        }
        vec![file, view]
    }
}
#[cfg(test)]
mod tests {
    use super::*;

    fn tabs(ids_and_active: &[(u64, bool)]) -> Vec<(u64, String, bool)> {
        ids_and_active
            .iter()
            .map(|(id, active)| (*id, format!("snapshot-{id}.hprof"), *active))
            .collect()
    }

    #[::core::prelude::v1::test]
    fn default_diff_pair_uses_newest_inactive_against_active() {
        let tabs = tabs(&[(1, false), (2, false), (3, true)]);
        assert_eq!(default_comparison_pair(&tabs, 3), Some((2, 3)));
        // Active is not necessarily the newest snapshot.
        assert_eq!(default_comparison_pair(&tabs, 1), Some((3, 1)));
        assert_eq!(default_comparison_pair(&tabs, 2), Some((3, 2)));
        // A single open snapshot has no baseline.
        assert_eq!(default_comparison_pair(&tabs[..1], 1), None);
        assert_eq!(default_comparison_pair(&[], 1), None);
    }
}
