package com.androidperformancestudio.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.androidperformancestudio.presentation.CaptureSettingsSection
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

internal data class SimpleperfExportMenuModel(
    val title: String,
    val sessionPackageLabel: String,
    val reportLabel: String,
    val rawProtobufLabel: String,
    val screenshotLabel: String,
    val simpleperfReportLabel: String,
    val htmlReportLabel: String,
    val externalOpenLabel: String,
)

internal data class SimpleperfExportMenuActions(
    val onSessionPackage: () -> Unit,
    val onReport: () -> Unit,
    val onRawProtobuf: () -> Unit,
    val onScreenshot: () -> Unit,
    val onSimpleperfReport: () -> Unit,
    val onHtmlReport: () -> Unit,
    val onExternalOpen: () -> Unit,
)

internal data class SimpleperfConfigurationMenuModel(
    val title: String,
    val samplingTemplateLabel: String,
    val captureConfigurationLabel: String,
    val advancedParametersLabel: String,
    val enabled: Boolean,
)

internal data class SimpleperfFileMenuModel(
    val fileTitle: String,
    val openLabel: String,
    val exportMenu: SimpleperfExportMenuModel,
    val configurationMenu: SimpleperfConfigurationMenuModel,
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
        configurationEnabled: Boolean = true,
    ) : this(
        fileTitle = language.text(english = "File", chinese = "文件"),
        openLabel = language.text(english = "Open…", chinese = "打开…"),
        exportMenu =
            SimpleperfExportMenuModel(
                title = language.text(english = "Export", chinese = "导出"),
                sessionPackageLabel = language.text(english = "Session package", chinese = "会话包"),
                reportLabel = "JSON + CSV",
                rawProtobufLabel = language.text(english = "Raw protobuf", chinese = "原始 protobuf"),
                screenshotLabel = language.text(english = "Screenshot", chinese = "截图"),
                simpleperfReportLabel = "simpleperf report",
                htmlReportLabel = "report_html.py",
                externalOpenLabel = language.text(english = "External open", chinese = "外部打开"),
            ),
        configurationMenu =
            SimpleperfConfigurationMenuModel(
                title = language.text(english = "Configuration", chinese = "配置"),
                samplingTemplateLabel = language.text(english = "Capture Templates", chinese = "采集模板"),
                captureConfigurationLabel =
                    language.text(english = "Capture Configuration", chinese = "采集配置"),
                advancedParametersLabel = language.text(english = "Advanced Parameters", chinese = "高级参数"),
                enabled = configurationEnabled,
            ),
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
@Suppress(
    "FunctionName",
    "LongMethod",
    "LongParameterList",
    "ktlint:standard:function-naming",
)
internal fun FrameWindowScope.SimpleperfFileMenuBar(
    model: SimpleperfFileMenuModel,
    onOpen: () -> Unit,
    exportActions: SimpleperfExportMenuActions,
    onOpenRecent: (Path) -> Unit,
    onClearRecent: () -> Unit,
    onOpenCaptureSettings: (CaptureSettingsSection) -> Unit,
) {
    MenuBar {
        Menu(model.fileTitle) {
            Item(
                text = model.openLabel,
                shortcut = model.openShortcut.toKeyShortcut(),
                onClick = onOpen,
            )
            Menu(model.exportMenu.title) {
                Item(
                    text = model.exportMenu.sessionPackageLabel,
                    enabled = model.exportEnabled,
                    onClick = exportActions.onSessionPackage,
                )
                Item(
                    text = model.exportMenu.reportLabel,
                    enabled = model.exportEnabled,
                    shortcut = model.exportShortcut.toKeyShortcut(),
                    onClick = exportActions.onReport,
                )
                Item(
                    text = model.exportMenu.rawProtobufLabel,
                    enabled = model.exportEnabled,
                    onClick = exportActions.onRawProtobuf,
                )
                Item(
                    text = model.exportMenu.screenshotLabel,
                    enabled = model.exportEnabled,
                    onClick = exportActions.onScreenshot,
                )
                Item(
                    text = model.exportMenu.simpleperfReportLabel,
                    enabled = model.exportEnabled,
                    onClick = exportActions.onSimpleperfReport,
                )
                Item(
                    text = model.exportMenu.htmlReportLabel,
                    enabled = model.exportEnabled,
                    onClick = exportActions.onHtmlReport,
                )
                Item(
                    text = model.exportMenu.externalOpenLabel,
                    enabled = model.exportEnabled,
                    onClick = exportActions.onExternalOpen,
                )
            }
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
        }
        Menu(model.configurationMenu.title, enabled = model.configurationMenu.enabled) {
            Item(
                text = model.configurationMenu.samplingTemplateLabel,
                onClick = { onOpenCaptureSettings(CaptureSettingsSection.SAMPLING_TEMPLATE) },
            )
            Item(
                text = model.configurationMenu.captureConfigurationLabel,
                onClick = { onOpenCaptureSettings(CaptureSettingsSection.CAPTURE_CONFIGURATION) },
            )
            Item(
                text = model.configurationMenu.advancedParametersLabel,
                onClick = { onOpenCaptureSettings(CaptureSettingsSection.ADVANCED_PARAMETERS) },
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
