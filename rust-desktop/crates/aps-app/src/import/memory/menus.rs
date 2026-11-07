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
use super::state::MemoryViewMode;
use crate::shell::menubar::MenuGroup;
use aps_ui_foundation::{Language, TextKey};

/// Memory view modes in menu order. The shared import menu command carries the
/// index so the command enum does not have to expose this module's type.
pub(crate) fn memory_view_mode(index: u8) -> Option<MemoryViewMode> {
    MemoryViewMode::PRIMARY
        .into_iter()
        .chain([MemoryViewMode::LeakAnalysis])
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
        // the native heap, ART sample and mapping imports are not wired yet.
        let file = file.submenu(TextKey::ShellMenuImport, true, |menu| {
            menu.item_when(
                self.can_open_home_artifact(),
                TextKey::MemoryAppMenuImportJavaHeap,
                ImportMenuCommand::Pick,
            )
            .item_when(
                false,
                TextKey::MemoryAppMenuImportNativeHeap,
                ImportMenuCommand::Unavailable,
            )
            .item_when(
                false,
                TextKey::MemoryAppMenuImportSampledArt,
                ImportMenuCommand::Unavailable,
            )
            .item_when(
                false,
                TextKey::MemoryAppMenuImportMapping,
                ImportMenuCommand::Unavailable,
            )
        });
        let file = Self::open_recent(file, false);
        // Compose groups: raw/standard HPROF, native heap, ART sample | CSV,
        // class instances, object investigation, diff, snapshot JSON,
        // investigation report | bitmap dump, bitmap comparison. No memory
        // export is wired to a page control in this build, so every row keeps
        // Compose's unavailable (disabled) state.
        let file = file
            .separator()
            .submenu(TextKey::ShellMenuExport, true, |menu| {
                menu.item_when(
                    false,
                    TextKey::MemoryAppExportRawHprof,
                    ImportMenuCommand::Unavailable,
                )
                .item_when(
                    false,
                    TextKey::MemoryAppExportStandardHprof,
                    ImportMenuCommand::Unavailable,
                )
                .item_when(
                    false,
                    TextKey::MemoryAppExportNativeHeapTrace,
                    ImportMenuCommand::Unavailable,
                )
                .item_when(
                    false,
                    TextKey::MemoryAppExportSampledArtTrace,
                    ImportMenuCommand::Unavailable,
                )
                .separator()
                .item_when(
                    false,
                    TextKey::MemoryAppExportCsv,
                    ImportMenuCommand::Unavailable,
                )
                .item_when(
                    false,
                    TextKey::MemoryAppExportClassInstances,
                    ImportMenuCommand::Unavailable,
                )
                .item_when(
                    false,
                    TextKey::MemoryAppExportObjectInvestigation,
                    ImportMenuCommand::Unavailable,
                )
                .item_when(
                    false,
                    TextKey::MemoryAppExportDiff,
                    ImportMenuCommand::Unavailable,
                )
                .item_when(
                    false,
                    TextKey::MemoryAppExportSnapshotJson,
                    ImportMenuCommand::Unavailable,
                )
                .item_when(
                    false,
                    TextKey::MemoryAppExportInvestigationReport,
                    ImportMenuCommand::Unavailable,
                )
                .separator()
                .item_when(
                    false,
                    TextKey::MemoryAppExportBitmapDump,
                    ImportMenuCommand::Unavailable,
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
            .chain([MemoryViewMode::LeakAnalysis])
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
