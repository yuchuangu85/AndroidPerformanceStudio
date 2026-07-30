@file:Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")

package com.androidperformancestudio.battery.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.androidperformancestudio.battery.battery_app.generated.resources.Res
import com.androidperformancestudio.battery.battery_app.generated.resources.advanced
import com.androidperformancestudio.battery.battery_app.generated.resources.clear_recent
import com.androidperformancestudio.battery.battery_app.generated.resources.export
import com.androidperformancestudio.battery.battery_app.generated.resources.export_csv
import com.androidperformancestudio.battery.battery_app.generated.resources.export_json
import com.androidperformancestudio.battery.battery_app.generated.resources.export_raw_bundle
import com.androidperformancestudio.battery.battery_app.generated.resources.file
import com.androidperformancestudio.battery.battery_app.generated.resources.import_analysis
import com.androidperformancestudio.battery.battery_app.generated.resources.no_recent_files
import com.androidperformancestudio.battery.battery_app.generated.resources.open_recent
import com.androidperformancestudio.battery.battery_app.generated.resources.reset_statistics
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Path

internal data class BatteryRecentMenuItem(
    val label: String,
    val path: Path,
)

internal data class BatteryExportMenuModel(
    val title: String,
    val jsonLabel: String,
    val csvLabel: String,
    val rawEvidenceLabel: String,
    val jsonEnabled: Boolean,
    val csvEnabled: Boolean,
    val rawEvidenceEnabled: Boolean,
)

internal data class BatteryProfilerMenuModel(
    val fileTitle: String,
    val importLabel: String,
    val importEnabled: Boolean,
    val exportMenu: BatteryExportMenuModel,
    val openRecentTitle: String,
    val noRecentLabel: String,
    val clearRecentLabel: String,
    val recentItems: List<BatteryRecentMenuItem>,
    val advancedTitle: String,
    val resetStatisticsLabel: String,
    val resetEnabled: Boolean,
)

internal fun batteryProfilerMenuModel(
    language: UiLanguage,
    recentFiles: List<Path>,
    importEnabled: Boolean,
    jsonExportEnabled: Boolean,
    csvExportEnabled: Boolean,
    rawEvidenceExportEnabled: Boolean,
    resetEnabled: Boolean,
): BatteryProfilerMenuModel =
    BatteryProfilerMenuModel(
        fileTitle = localizedStringResource(Res.string.file, language),
        importLabel = localizedStringResource(Res.string.import_analysis, language),
        importEnabled = importEnabled,
        exportMenu =
            BatteryExportMenuModel(
                title = localizedStringResource(Res.string.export, language),
                jsonLabel = localizedStringResource(Res.string.export_json, language),
                csvLabel = localizedStringResource(Res.string.export_csv, language),
                rawEvidenceLabel = localizedStringResource(Res.string.export_raw_bundle, language),
                jsonEnabled = jsonExportEnabled,
                csvEnabled = csvExportEnabled,
                rawEvidenceEnabled = rawEvidenceExportEnabled,
            ),
        openRecentTitle = localizedStringResource(Res.string.open_recent, language),
        noRecentLabel = localizedStringResource(Res.string.no_recent_files, language),
        clearRecentLabel = localizedStringResource(Res.string.clear_recent, language),
        recentItems = recentFiles.toBatteryRecentMenuItems(),
        advancedTitle = localizedStringResource(Res.string.advanced, language),
        resetStatisticsLabel = localizedStringResource(Res.string.reset_statistics, language),
        resetEnabled = resetEnabled,
    )

@Composable
internal fun FrameWindowScope.BatteryProfilerMenuBar(
    model: BatteryProfilerMenuModel,
    onImport: () -> Unit,
    onExportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onExportRawEvidence: () -> Unit,
    onOpenRecent: (Path) -> Unit,
    onClearRecent: () -> Unit,
    onResetStatistics: () -> Unit,
) {
    MenuBar {
        Menu(model.fileTitle) {
            Item(
                text = model.importLabel,
                enabled = model.importEnabled,
                onClick = onImport,
            )
            Menu(model.exportMenu.title) {
                Item(
                    text = model.exportMenu.jsonLabel,
                    enabled = model.exportMenu.jsonEnabled,
                    onClick = onExportJson,
                )
                Item(
                    text = model.exportMenu.csvLabel,
                    enabled = model.exportMenu.csvEnabled,
                    onClick = onExportCsv,
                )
                Item(
                    text = model.exportMenu.rawEvidenceLabel,
                    enabled = model.exportMenu.rawEvidenceEnabled,
                    onClick = onExportRawEvidence,
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
        Menu(model.advancedTitle) {
            Item(
                text = model.resetStatisticsLabel,
                enabled = model.resetEnabled,
                onClick = onResetStatistics,
            )
        }
    }
}

private fun List<Path>.toBatteryRecentMenuItems(): List<BatteryRecentMenuItem> {
    val normalized = map { it.toAbsolutePath().normalize() }.distinct()
    val duplicateNames =
        normalized
            .groupingBy { it.fileName?.toString().orEmpty() }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    return normalized.map { path ->
        val fileName = path.fileName?.toString().orEmpty()
        BatteryRecentMenuItem(
            label = if (fileName.isBlank() || fileName in duplicateNames) path.toString() else fileName,
            path = path,
        )
    }
}
