//! Route menus for the import-backed profiler workspaces.
//!
//! Memory, Network, Startup, Battery and GPU share one workspace
//! entity but not one functionality set. Each kind publishes the Compose page
//! menu shape of the adapter it shows — File > Open… (Cmd+O), Import ▸, Open
//! Recent ▸, separator, Export ▸ — so the menu strip never offers another
//! screen's commands. Rows whose capability is not wired in this build render
//! disabled, which is how Compose publishes an unavailable capability too.

use super::export;
use super::*;
use crate::shell::menubar::{MenuGroup, MenuHost};
use aps_ui_foundation::{Destination, Language, TextKey};
use gpui_kit::{prelude::*, *};

/// Page export format chosen from a menu. Mirrors `export::Format`, which has no
/// equality so it cannot travel inside a menu command directly.
#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum PageExport {
    Csv,
    Json,
}

impl PageExport {
    fn format(self) -> export::Format {
        match self {
            Self::Csv => export::Format::Csv,
            Self::Json => export::Format::Json,
        }
    }
}

/// Commands published by an import-backed route's menus.
#[derive(Clone, PartialEq, Eq)]
pub(crate) enum ImportMenuCommand {
    /// A row whose capability is not wired in this build. Compose publishes the
    /// same row disabled, so the menu keeps the shape without faking the action.
    Unavailable,
    Pick,
    SavePage(PageExport),
    CopyPage(PageExport),
    NetworkExport(network::exports::NetworkExport),
    BatteryOpenSaved(String),
    BatteryCaptureBundle,
    MemoryView(u8),
    SelectMemorySnapshot(u64),
    CloseMemorySnapshot(u64),
    MemoryImportMapping,
    MemoryImportAdvanced(self::memory::advanced::AdvancedMemoryKind),
    MemoryExportAdvanced(self::memory::advanced::AdvancedMemoryKind),
    MemoryExport(self::memory::exports::MemoryExportKind),
    GpuOpen,
    GpuReveal,
}

impl ImportWorkspace {
    /// File > Open…: the route's own file picker with the shared Cmd+O action, so
    /// the native menu shows the shortcut Compose assigns to the window's Open row.
    pub(super) fn open_row(
        group: MenuGroup<ImportMenuCommand>,
        enabled: bool,
    ) -> MenuGroup<ImportMenuCommand> {
        group.item_action_when(
            enabled,
            TextKey::ShellMenuOpen,
            ImportMenuCommand::Pick,
            crate::shell::OpenActiveRouteFile,
        )
    }

    /// Open Recent ▸. The Rust import pages do not persist a recent-file list, so
    /// the submenu reproduces Compose's empty state instead of inventing rows:
    /// a disabled "No recent files", plus the Clear Recent row Compose publishes
    /// for Battery (also disabled while there is nothing to clear).
    pub(super) fn open_recent(
        group: MenuGroup<ImportMenuCommand>,
        clear: bool,
    ) -> MenuGroup<ImportMenuCommand> {
        group.submenu(TextKey::ShellMenuOpenRecent, false, |submenu| {
            let submenu = submenu.item_when(
                false,
                TextKey::ShellMenuNoRecent,
                ImportMenuCommand::Unavailable,
            );
            if clear {
                submenu.separator().item_when(
                    false,
                    TextKey::ShellMenuClearRecent,
                    ImportMenuCommand::Unavailable,
                )
            } else {
                submenu
            }
        })
    }

    /// True when the published page's export rows can run: a page exists and no
    /// import or export already owns the workspace.
    pub(super) fn page_export_ready(&self) -> bool {
        self.page.is_some() && !self.busy && !self.export_busy
    }

    /// Export ▸ for the pages whose only export is the published page itself
    /// (Network, GPU). The rows mirror those pages' own save/copy
    /// buttons; they stay disabled until the page is published.
    pub(super) fn page_export_submenu(
        group: MenuGroup<ImportMenuCommand>,
        ready: bool,
    ) -> MenuGroup<ImportMenuCommand> {
        group.submenu(TextKey::ShellMenuExport, true, |submenu| {
            submenu
                .item_when(
                    ready,
                    TextKey::ImportSavePageJson,
                    ImportMenuCommand::SavePage(PageExport::Json),
                )
                .item_when(
                    ready,
                    TextKey::ImportSavePageCsv,
                    ImportMenuCommand::SavePage(PageExport::Csv),
                )
                .separator()
                .item_when(
                    ready,
                    TextKey::ImportCopyPageCsv,
                    ImportMenuCommand::CopyPage(PageExport::Csv),
                )
                .item_when(
                    ready,
                    TextKey::ImportCopyPageJson,
                    ImportMenuCommand::CopyPage(PageExport::Json),
                )
        })
    }

    fn network_file_menu(&self) -> MenuGroup<ImportMenuCommand> {
        let file = Self::open_row(MenuGroup::new(TextKey::ShellMenuFile), !self.busy);
        let file = Self::open_recent(file, false);
        let ready = self.page_export_ready() && matches!(self.session, Some(Session::Network(_)));
        file.separator()
            .submenu(TextKey::ShellMenuExport, true, |menu| {
                menu.item_when(
                    ready,
                    TextKey::NetworkSaveJson,
                    ImportMenuCommand::NetworkExport(network::exports::NetworkExport::Json),
                )
                .item_when(
                    ready,
                    TextKey::NetworkSaveCsv,
                    ImportMenuCommand::NetworkExport(network::exports::NetworkExport::Csv),
                )
                .item_when(
                    ready,
                    TextKey::NetworkPartialHar,
                    ImportMenuCommand::NetworkExport(network::exports::NetworkExport::PartialHar),
                )
                .separator()
                .item_when(
                    ready,
                    TextKey::ImportCopyPageJson,
                    ImportMenuCommand::CopyPage(PageExport::Json),
                )
                .item_when(
                    ready,
                    TextKey::ImportCopyPageCsv,
                    ImportMenuCommand::CopyPage(PageExport::Csv),
                )
            })
    }

    fn gpu_menu_groups(&self) -> Vec<MenuGroup<ImportMenuCommand>> {
        let selected = self.gpu.selected.is_some();
        let file = Self::open_row(MenuGroup::new(TextKey::ShellMenuFile), !self.busy);
        let file = Self::open_recent(file, false);
        let file = Self::page_export_submenu(file.separator(), self.page_export_ready())
            .separator()
            .item_when(selected, TextKey::GpuOpen, ImportMenuCommand::GpuOpen)
            .item_when(
                selected,
                TextKey::GpuShowInFiles,
                ImportMenuCommand::GpuReveal,
            );
        vec![file]
    }

    /// Battery follows Compose's BatteryProfilerMenu: Open…, Open Recent ▸,
    /// Export ▸ and Tools > Reset Statistics. The reset remains unavailable;
    /// capture bundles require matching owned or restored capture evidence.
    fn battery_menu_groups(&self) -> Vec<MenuGroup<ImportMenuCommand>> {
        let ready = self.page_export_ready();
        let file = Self::open_row(MenuGroup::new(TextKey::ShellMenuFile), !self.busy);
        let file = file.submenu(TextKey::ShellMenuOpenRecent, true, |mut submenu| {
            if self.battery_shell_ui.sessions.is_empty() {
                submenu = submenu.item_when(
                    false,
                    TextKey::ShellMenuNoRecent,
                    ImportMenuCommand::Unavailable,
                );
            }
            for saved in self.battery_shell_ui.sessions.iter().take(20) {
                let label = format!(
                    "{} · {}",
                    saved.package_name.as_deref().unwrap_or(&saved.session_id),
                    saved.created_at.as_deref().unwrap_or(&saved.session_id)
                );
                submenu = submenu.item_when(
                    !self.busy && !self.export_busy && !self.battery_shell_ui.sessions_loading,
                    label,
                    ImportMenuCommand::BatteryOpenSaved(saved.key.clone()),
                );
            }
            submenu.separator().item_when(
                false,
                TextKey::ShellMenuClearRecent,
                ImportMenuCommand::Unavailable,
            )
        });
        let file = file
            .separator()
            .submenu(TextKey::ShellMenuExport, true, |submenu| {
                submenu
                    .item_when(
                        ready,
                        TextKey::BatterySavePageJson,
                        ImportMenuCommand::SavePage(PageExport::Json),
                    )
                    .item_when(
                        ready,
                        TextKey::BatterySavePageCsv,
                        ImportMenuCommand::SavePage(PageExport::Csv),
                    )
                    .item_when(
                        ready && self.battery_capture_artifact().is_some(),
                        TextKey::BatteryCaptureBundle,
                        ImportMenuCommand::BatteryCaptureBundle,
                    )
            });
        let tools = MenuGroup::new(TextKey::ShellMenuTools).item_when(
            false,
            TextKey::BatteryResetGlobal,
            ImportMenuCommand::Unavailable,
        );
        vec![file, tools]
    }

    /// Startup follows Compose's StartupProfilerFileMenu: Open… and Export ▸ with
    /// the CSV row before the JSON row.
    fn startup_menu_groups(&self) -> Vec<MenuGroup<ImportMenuCommand>> {
        let ready = self.page_export_ready();
        let file = Self::open_row(MenuGroup::new(TextKey::ShellMenuFile), !self.busy);
        let file = Self::open_recent(file, false);
        let file = file
            .separator()
            .submenu(TextKey::ShellMenuExport, true, |submenu| {
                submenu
                    .item_when(
                        ready,
                        TextKey::StartupSaveCsv,
                        ImportMenuCommand::SavePage(PageExport::Csv),
                    )
                    .item_when(
                        ready,
                        TextKey::StartupSaveJson,
                        ImportMenuCommand::SavePage(PageExport::Json),
                    )
            });
        vec![file]
    }
}

impl MenuHost for ImportWorkspace {
    type Command = ImportMenuCommand;

    fn menu_groups(&self, language: Language) -> Vec<MenuGroup<Self::Command>> {
        match self.kind {
            Destination::Memory => self.memory_menu_groups(language),
            Destination::Network => vec![self.network_file_menu()],
            Destination::Startup => self.startup_menu_groups(),
            Destination::Battery => self.battery_menu_groups(),
            Destination::Gpu => self.gpu_menu_groups(),
            _ => Vec::new(),
        }
    }

    fn run_menu_command(
        &mut self,
        command: Self::Command,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        match command {
            ImportMenuCommand::Unavailable => {}
            ImportMenuCommand::Pick => self.pick(cx),
            ImportMenuCommand::SavePage(format) => self.save_page(format.format(), cx),
            ImportMenuCommand::CopyPage(format) => self.copy_page(format.format(), cx),
            ImportMenuCommand::NetworkExport(format) => self.save_network_session(format, cx),
            ImportMenuCommand::BatteryOpenSaved(key) => self.open_battery_saved_session(key, cx),
            ImportMenuCommand::BatteryCaptureBundle => self.save_battery_capture_bundle(cx),
            ImportMenuCommand::MemoryView(index) => {
                if let Some(mode) = self::memory::menus::memory_view_mode(index) {
                    self.memory_set_view_mode(mode, cx);
                }
            }
            ImportMenuCommand::SelectMemorySnapshot(id) => self.select_memory_snapshot(id, cx),
            ImportMenuCommand::CloseMemorySnapshot(id) => self.close_memory_snapshot(id, cx),
            ImportMenuCommand::MemoryImportMapping => self.memory_pick_mapping(cx),
            ImportMenuCommand::MemoryImportAdvanced(kind) => self.import_advanced_memory(kind, cx),
            ImportMenuCommand::MemoryExportAdvanced(kind) => self.export_advanced_memory(kind, cx),
            ImportMenuCommand::MemoryExport(kind) => self.memory_export(kind, cx),
            ImportMenuCommand::GpuOpen => self.gpu_open(cx),
            ImportMenuCommand::GpuReveal => self.gpu_reveal(cx),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::import::memory::state::MemoryViewMode;
    use crate::shell::menubar::{RouteMenuBuild, StripRow};

    const ROUTES: [Destination; 5] = [
        Destination::Memory,
        Destination::Network,
        Destination::Startup,
        Destination::Battery,
        Destination::Gpu,
    ];

    /// Flattens one route menu exactly as the fallback strip renders it: a
    /// submenu is a header row followed by its children.
    fn strip_rows(group: MenuGroup<ImportMenuCommand>) -> Vec<StripRow> {
        let mut build = RouteMenuBuild::default();
        build.push(group, Language::English, |_command, _window, _cx| {});
        build.strip.remove(0).rows
    }

    fn row_labels(rows: &[StripRow]) -> Vec<String> {
        rows.iter()
            .map(|row| match row {
                StripRow::Separator => "—".to_owned(),
                StripRow::Header(label) | StripRow::Item { label, .. } => label.to_string(),
            })
            .collect()
    }

    fn label(key: TextKey) -> String {
        Language::English.text(key).to_owned()
    }

    fn enabled_states(rows: &[StripRow]) -> Vec<(String, bool)> {
        rows.iter()
            .filter_map(|row| match row {
                StripRow::Item { label, enabled, .. } => Some((label.to_string(), *enabled)),
                _ => None,
            })
            .collect()
    }

    #[::core::prelude::v1::test]
    fn every_import_route_publishes_a_file_menu_with_its_own_content() {
        for kind in ROUTES {
            let workspace = ImportWorkspace::new(kind, Language::English);
            let groups = workspace.menu_groups(Language::English);
            assert!(!groups.is_empty(), "{kind:?} publishes no menus");
            assert_eq!(
                groups[0].title(),
                TextKey::ShellMenuFile,
                "{kind:?} first menu is File"
            );
        }
        // Memory keeps its own View menu; Battery adds Compose's Tools menu.
        assert_eq!(
            ImportWorkspace::new(Destination::Memory, Language::English)
                .menu_groups(Language::English)
                .len(),
            2
        );
        assert_eq!(
            ImportWorkspace::new(Destination::Battery, Language::English)
                .menu_groups(Language::English)
                .len(),
            2
        );
    }

    #[::core::prelude::v1::test]
    fn file_menus_follow_the_compose_order() {
        for kind in ROUTES {
            let workspace = ImportWorkspace::new(kind, Language::English);
            let rows = strip_rows(workspace.menu_groups(Language::English).remove(0));
            let labels = row_labels(&rows);
            assert_eq!(labels[0], label(TextKey::ShellMenuOpen), "{kind:?} Open…");
            // Compose's FileMenuItems order is Open…, Import ▸, Open Recent ▸,
            // separator, Export ▸; Memory is the only import route with imports.
            let import = labels
                .iter()
                .position(|row| row == &label(TextKey::ShellMenuImport));
            let open_recent = labels
                .iter()
                .position(|row| row == &label(TextKey::ShellMenuOpenRecent));
            if kind == Destination::Memory {
                assert_eq!(import, Some(1), "{kind:?} Import ▸ follows Open…");
                assert!(
                    open_recent
                        .zip(import)
                        .is_some_and(|(recent, import)| recent > import),
                    "{kind:?} Import ▸ precedes Open Recent ▸"
                );
            } else {
                assert_eq!(import, None, "{kind:?} publishes no Import ▸");
                assert_eq!(open_recent, Some(1), "{kind:?} Open Recent ▸ follows Open…");
            }
            assert!(
                labels.contains(&label(TextKey::ShellMenuExport)),
                "{kind:?} publishes Export ▸"
            );
            // The first row is the shared route picker: always enabled, and the
            // native menu resolves its Cmd+O key equivalent from the action.
            assert!(enabled_states(&rows)[0].1, "{kind:?} Open… is available");
        }
    }

    #[::core::prelude::v1::test]
    fn open_recent_matches_compose_empty_state() {
        // Memory/Startup pages publish no persisted recents: one disabled row.
        let memory = ImportWorkspace::new(Destination::Memory, Language::English);
        let rows = strip_rows(memory.menu_groups(Language::English).remove(0));
        let labels = row_labels(&rows);
        let recent = labels
            .iter()
            .position(|row| row == &label(TextKey::ShellMenuOpenRecent))
            .expect("Open Recent row");
        assert_eq!(labels[recent + 1], label(TextKey::ShellMenuNoRecent));
        assert!(!labels.contains(&label(TextKey::ShellMenuClearRecent)));
        // Compose Battery publishes onClear, so its submenu keeps Clear Recent.
        let battery = ImportWorkspace::new(Destination::Battery, Language::English);
        let rows = strip_rows(battery.menu_groups(Language::English).remove(0));
        assert!(row_labels(&rows).contains(&label(TextKey::ShellMenuClearRecent)));
    }

    #[::core::prelude::v1::test]
    fn page_exports_stay_disabled_until_a_page_is_published() {
        for kind in ROUTES {
            let workspace = ImportWorkspace::new(kind, Language::English);
            let rows = strip_rows(workspace.menu_groups(Language::English).remove(0));
            let export = row_labels(&rows)
                .iter()
                .position(|row| row == &label(TextKey::ShellMenuExport))
                .expect("Export submenu");
            // Every row after the Export header is an export row until the next
            // header; none of them may run without a published page.
            let mut items = 0;
            for row in rows.iter().skip(export + 1) {
                match row {
                    StripRow::Item { enabled, .. } => {
                        items += 1;
                        assert!(!enabled, "{kind:?} export row enabled without a page");
                    }
                    StripRow::Separator => {}
                    StripRow::Header(_) => break,
                }
            }
            assert!(items >= 2, "{kind:?} publishes no export rows");
        }
    }

    #[::core::prelude::v1::test]
    fn battery_tools_reset_statistics_is_disabled_without_a_capture_adapter() {
        let workspace = ImportWorkspace::new(Destination::Battery, Language::English);
        let groups = workspace.menu_groups(Language::English);
        let tools = groups
            .iter()
            .find(|group| group.title() == TextKey::ShellMenuTools)
            .expect("Battery publishes Tools");
        assert_eq!(tools.len(), 1);
        assert_eq!(tools.enabled(), 0);
    }

    #[::core::prelude::v1::test]
    fn memory_keeps_its_view_group_for_the_view_modes() {
        let workspace = ImportWorkspace::new(Destination::Memory, Language::English);
        let groups = workspace.menu_groups(Language::English);
        let view = groups
            .iter()
            .find(|group| group.title() == TextKey::ShellMenuView)
            .expect("Memory publishes View");
        assert_eq!(view.len(), 8);
        assert_eq!(view.enabled(), 8);
        for (index, expected) in [
            MemoryViewMode::Dashboard,
            MemoryViewMode::Classes,
            MemoryViewMode::Dominators,
            MemoryViewMode::Diff,
            MemoryViewMode::LeakAnalysis,
            MemoryViewMode::BitmapDump,
            MemoryViewMode::NativeHeap,
            MemoryViewMode::SampledArt,
        ]
        .into_iter()
        .enumerate()
        {
            assert_eq!(memory::menus::memory_view_mode(index as u8), Some(expected));
        }
        assert_eq!(memory::menus::memory_view_mode(8), None);
    }

    #[::core::prelude::v1::test]
    fn memory_export_rows_follow_the_compose_groups() {
        let workspace = ImportWorkspace::new(Destination::Memory, Language::English);
        let rows = strip_rows(workspace.menu_groups(Language::English).remove(0));
        let labels = row_labels(&rows);
        let export = labels
            .iter()
            .position(|row| row == &label(TextKey::ShellMenuExport))
            .expect("Export submenu");
        let rows = &rows[export + 1..];
        let mut items = 0;
        let mut separators = 0;
        for row in rows {
            match row {
                StripRow::Item { enabled, .. } => {
                    items += 1;
                    // No HPROF, table, report or bitmap export is wired to a
                    // Rust button yet, so no memory export row may run.
                    assert!(!enabled, "memory export row enabled without an adapter");
                }
                StripRow::Separator => separators += 1,
                StripRow::Header(_) => break,
            }
        }
        // Compose groups: heap/trace (4) | tables and reports (6) | bitmaps (2).
        assert_eq!(items, 12);
        assert_eq!(separators, 2);
    }

    #[::core::prelude::v1::test]
    fn memory_import_rows_keep_order_and_enable_real_advanced_adapters() {
        let workspace = ImportWorkspace::new(Destination::Memory, Language::English);
        let rows = strip_rows(workspace.menu_groups(Language::English).remove(0));
        let labels = row_labels(&rows);
        let import = labels
            .iter()
            .position(|row| row == &label(TextKey::ShellMenuImport))
            .expect("Import submenu");
        let expected = [
            "Java Heap…",
            "Native Heap Trace…",
            "ART Sample Trace…",
            "ProGuard/R8 Mapping…",
        ];
        for (index, expected) in expected.iter().enumerate() {
            assert_eq!(&labels[import + 1 + index], expected);
        }
        let enabled = enabled_states(&rows[import + 1..import + 5]);
        assert!(enabled[0].1, "HPROF import is wired");
        assert!(enabled[1].1, "native heap trace adapter is wired");
        assert!(enabled[2].1, "ART trace adapter is wired");
        assert!(!enabled[3].1, "mapping import is not wired");
    }

    #[::core::prelude::v1::test]
    fn memory_exports_enable_from_the_published_snapshot_and_selection() {
        let mut workspace = ImportWorkspace::new(Destination::Memory, Language::English);
        workspace.memory_state.snapshot =
            Some(std::sync::Arc::new(aps_memory::MemorySnapshot::default()));
        let enabled = enabled_states(&strip_rows(
            workspace.menu_groups(Language::English).remove(0),
        ));
        let state = |key: TextKey| {
            let label = label(key);
            enabled
                .iter()
                .find(|(row_label, _)| row_label == &label)
                .map(|(_, enabled)| *enabled)
        };
        // Snapshot-scoped exports open with the snapshot; raw HPROF also needs
        // the retained source path/session; selection exports need their page.
        assert_eq!(state(TextKey::MemoryAppExportCsv), Some(true));
        assert_eq!(state(TextKey::MemoryAppExportSnapshotJson), Some(true));
        assert_eq!(
            state(TextKey::MemoryAppExportInvestigationReport),
            Some(true)
        );
        assert_eq!(state(TextKey::MemoryAppExportRawHprof), Some(false));
        assert_eq!(state(TextKey::MemoryAppExportClassInstances), Some(false));
        assert_eq!(
            state(TextKey::MemoryAppExportObjectInvestigation),
            Some(false)
        );
        assert_eq!(state(TextKey::MemoryAppExportDiff), Some(false));
        // No adapter exists for these rows in any state.
        assert_eq!(state(TextKey::MemoryAppExportStandardHprof), Some(false));
        assert_eq!(state(TextKey::MemoryAppExportNativeHeapTrace), Some(false));
        assert_eq!(state(TextKey::MemoryAppExportSampledArtTrace), Some(false));
        assert_eq!(state(TextKey::MemoryAppExportBitmapDump), Some(false));
        assert_eq!(state(TextKey::MemoryAppExportBitmapComparison), Some(false));
        // Mapping import needs a live worker-backed session, not just a snapshot.
        assert_eq!(state(TextKey::MemoryAppMenuImportMapping), Some(false));

        workspace.memory_state.instances =
            Some(std::sync::Arc::new(aps_memory::InstancesPage::default()));
        workspace.memory_state.detail =
            Some(std::sync::Arc::new(aps_memory::InstanceDetail::default()));
        workspace.memory_state.comparison = Some(std::sync::Arc::new(
            crate::import::memory::state::ComparisonReport::default(),
        ));
        let enabled = enabled_states(&strip_rows(
            workspace.menu_groups(Language::English).remove(0),
        ));
        let state = |key: TextKey| {
            let label = label(key);
            enabled
                .iter()
                .find(|(row_label, _)| row_label == &label)
                .map(|(_, enabled)| *enabled)
        };
        assert_eq!(state(TextKey::MemoryAppExportClassInstances), Some(true));
        assert_eq!(
            state(TextKey::MemoryAppExportObjectInvestigation),
            Some(true)
        );
        assert_eq!(state(TextKey::MemoryAppExportDiff), Some(true));
    }

    #[::core::prelude::v1::test]
    fn startup_exports_csv_before_json_like_compose() {
        let workspace = ImportWorkspace::new(Destination::Startup, Language::English);
        let rows = strip_rows(workspace.menu_groups(Language::English).remove(0));
        let labels = row_labels(&rows);
        let export = labels
            .iter()
            .position(|row| row == &label(TextKey::ShellMenuExport))
            .expect("Export submenu");
        assert_eq!(labels[export + 1], label(TextKey::StartupSaveCsv));
        assert_eq!(labels[export + 2], label(TextKey::StartupSaveJson));
    }
}
