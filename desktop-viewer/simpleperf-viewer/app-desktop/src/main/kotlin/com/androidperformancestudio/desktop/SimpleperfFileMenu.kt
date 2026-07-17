package com.androidperformancestudio.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import java.nio.file.Path

internal data class SimpleperfMenuShortcut(
    val key: Key,
    val ctrl: Boolean,
    val meta: Boolean,
)

internal data class SimpleperfRecentMenuItem(
    val label: String,
    val path: Path,
)

internal data class SimpleperfFileMenuModel(
    val fileTitle: String,
    val openLabel: String,
    val exportLabel: String,
    val openRecentTitle: String,
    val noRecentLabel: String,
    val clearRecentLabel: String,
    val recentItems: List<SimpleperfRecentMenuItem>,
    val exportEnabled: Boolean,
    val openShortcut: SimpleperfMenuShortcut,
    val exportShortcut: SimpleperfMenuShortcut,
) {
    constructor(
        language: SimpleperfLanguage,
        recentSessions: List<Path>,
        exportEnabled: Boolean,
        isMacOs: Boolean,
    ) : this(
        fileTitle = language.text(english = "File", chinese = "文件"),
        openLabel = language.text(english = "Open…", chinese = "打开…"),
        exportLabel = language.text(english = "Export…", chinese = "导出…"),
        openRecentTitle = language.text(english = "Open Recent", chinese = "最近打开"),
        noRecentLabel = language.text(english = "No Recent Sessions", chinese = "没有最近会话"),
        clearRecentLabel = language.text(english = "Clear Menu", chinese = "清除菜单"),
        recentItems = recentSessions.toRecentMenuItems(),
        exportEnabled = exportEnabled,
        openShortcut = primaryShortcut(Key.O, isMacOs),
        exportShortcut = primaryShortcut(Key.E, isMacOs),
    )
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FrameWindowScope.SimpleperfFileMenuBar(
    model: SimpleperfFileMenuModel,
    onOpen: () -> Unit,
    onExport: () -> Unit,
    onOpenRecent: (Path) -> Unit,
    onClearRecent: () -> Unit,
) {
    MenuBar {
        Menu(model.fileTitle) {
            Item(
                text = model.openLabel,
                shortcut = model.openShortcut.toKeyShortcut(),
                onClick = onOpen,
            )
            Menu(model.openRecentTitle) {
                if (model.recentItems.isEmpty()) {
                    Item(text = model.noRecentLabel, enabled = false, onClick = {})
                } else {
                    model.recentItems.forEach { item ->
                        Item(text = item.label, onClick = { onOpenRecent(item.path) })
                    }
                    Separator()
                    Item(text = model.clearRecentLabel, onClick = onClearRecent)
                }
            }
            Separator()
            Item(
                text = model.exportLabel,
                enabled = model.exportEnabled,
                shortcut = model.exportShortcut.toKeyShortcut(),
                onClick = onExport,
            )
        }
    }
}

private fun List<Path>.toRecentMenuItems(): List<SimpleperfRecentMenuItem> {
    val normalized = map { it.toAbsolutePath().normalize() }.distinct()
    val duplicateNames =
        normalized
            .groupingBy { it.fileName?.toString().orEmpty() }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    return normalized.map { path ->
        val fileName = path.fileName?.toString().orEmpty()
        SimpleperfRecentMenuItem(
            label = if (fileName.isBlank() || fileName in duplicateNames) path.toString() else fileName,
            path = path,
        )
    }
}

private fun SimpleperfLanguage.text(
    english: String,
    chinese: String,
): String =
    when (this) {
        SimpleperfLanguage.SIMPLIFIED_CHINESE -> chinese
        SimpleperfLanguage.ENGLISH -> english
    }

private fun primaryShortcut(
    key: Key,
    isMacOs: Boolean,
): SimpleperfMenuShortcut =
    SimpleperfMenuShortcut(
        key = key,
        ctrl = !isMacOs,
        meta = isMacOs,
    )

private fun SimpleperfMenuShortcut.toKeyShortcut(): KeyShortcut =
    KeyShortcut(
        key = key,
        ctrl = ctrl,
        meta = meta,
    )
