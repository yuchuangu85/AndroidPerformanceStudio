package com.androidperformancestudio.perfetto.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.androidperformancestudio.perfetto_app.generated.resources.Res
import com.androidperformancestudio.perfetto_app.generated.resources.clear_menu
import com.androidperformancestudio.perfetto_app.generated.resources.export
import com.androidperformancestudio.perfetto_app.generated.resources.file
import com.androidperformancestudio.perfetto_app.generated.resources.no_recent_files
import com.androidperformancestudio.perfetto_app.generated.resources.open_recent
import com.androidperformancestudio.perfetto_app.generated.resources.open_u2026
import com.androidperformancestudio.perfetto_app.generated.resources.raw_trace_pftrace
import com.androidperformancestudio.perfetto_app.generated.resources.session_package_zip
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
    val isMacOs = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)
    val openShortcut = KeyShortcut(Key.O, ctrl = !isMacOs, meta = isMacOs)

    MenuBar {
        Menu(localizedStringResource(Res.string.file, language)) {
            Item(localizedStringResource(Res.string.open_u2026, language), shortcut = openShortcut, onClick = onOpen)
            Menu(localizedStringResource(Res.string.export, language)) {
                Item(localizedStringResource(Res.string.session_package_zip, language), enabled = canExport, onClick = onExportSession)
                Item(localizedStringResource(Res.string.raw_trace_pftrace, language), enabled = canExport, onClick = onExportRawTrace)
            }
            Menu(localizedStringResource(Res.string.open_recent, language)) {
                if (recentFiles.isEmpty()) {
                    Item(localizedStringResource(Res.string.no_recent_files, language), enabled = false, onClick = {})
                } else {
                    recentFiles.forEach { path ->
                        Item(path.fileName?.toString() ?: path.toString(), onClick = { onOpenRecent(path) })
                    }
                    Separator()
                    Item(localizedStringResource(Res.string.clear_menu, language), onClick = onClearRecent)
                }
            }
        }
    }
}
