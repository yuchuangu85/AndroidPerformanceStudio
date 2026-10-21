package com.androidperformancestudio.perfetto.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.perfetto_app.generated.resources.Res
import com.androidperformancestudio.perfetto_app.generated.resources.raw_trace_pftrace
import com.androidperformancestudio.perfetto_app.generated.resources.session_package_zip
import com.androidperformancestudio.ui.ActivePageMenu
import com.androidperformancestudio.ui.MenuCommand
import com.androidperformancestudio.ui.MenuShortcuts
import com.androidperformancestudio.ui.PageMenu
import com.androidperformancestudio.ui.RecentMenu
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Path

@Composable
@Suppress("ktlint:standard:function-naming")
internal fun FrameWindowScope.PerfettoFileMenuBar(
    language: UiLanguage,
    canExport: Boolean,
    recentFiles: List<Path>,
    onOpen: () -> Unit,
    onExportSession: () -> Unit,
    onExportRawTrace: () -> Unit,
    onOpenRecent: (Path) -> Unit,
    onClearRecent: () -> Unit,
) {
    ActivePageMenu(
        PageMenu(
            onOpen = onOpen,
            recent =
                RecentMenu(
                    items =
                        recentFiles.map { path ->
                            MenuCommand(path.fileName?.toString() ?: path.toString()) { onOpenRecent(path) }
                        },
                    onClear = onClearRecent,
                ),
            exportGroups =
                listOf(
                    listOf(
                        MenuCommand(
                            localizedStringResource(Res.string.session_package_zip, language),
                            enabled = canExport,
                            shortcut = MenuShortcuts.export(),
                            onClick = onExportSession,
                        ),
                        MenuCommand(
                            localizedStringResource(Res.string.raw_trace_pftrace, language),
                            enabled = canExport,
                            onClick = onExportRawTrace,
                        ),
                    ),
                ),
        ),
    )
}
