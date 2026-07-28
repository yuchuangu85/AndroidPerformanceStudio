package com.androidperformancestudio.perfetto.app

import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.perfetto_app.generated.resources.Res
import com.androidperformancestudio.perfetto_app.generated.resources.*

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import java.nio.file.Path

@Composable
@Suppress("ktlint:standard:function-naming")
internal fun FrameWindowScope.PerfettoFileMenuBar(
    chinese: Boolean,
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
        Menu(localizedStringResource(Res.string.file, chinese)) {
            Item(localizedStringResource(Res.string.open_u2026, chinese), shortcut = openShortcut, onClick = onOpen)
            Menu(localizedStringResource(Res.string.export, chinese)) {
                Item(localizedStringResource(Res.string.session_package_zip, chinese), enabled = canExport, onClick = onExportSession)
                Item(localizedStringResource(Res.string.raw_trace_pftrace, chinese), enabled = canExport, onClick = onExportRawTrace)
            }
            Menu(localizedStringResource(Res.string.open_recent, chinese)) {
                if (recentFiles.isEmpty()) {
                    Item(localizedStringResource(Res.string.no_recent_files, chinese), enabled = false, onClick = {})
                } else {
                    recentFiles.forEach { path ->
                        Item(path.fileName?.toString() ?: path.toString(), onClick = { onOpenRecent(path) })
                    }
                    Separator()
                    Item(localizedStringResource(Res.string.clear_menu, chinese), onClick = onClearRecent)
                }
            }
        }
    }
}
