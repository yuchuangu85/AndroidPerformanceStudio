@file:Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")

package com.androidperformancestudio.battery.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.window.FrameWindowScope
import com.androidperformancestudio.battery.battery_app.generated.resources.Res
import com.androidperformancestudio.battery.battery_app.generated.resources.export_csv
import com.androidperformancestudio.battery.battery_app.generated.resources.export_json
import com.androidperformancestudio.battery.battery_app.generated.resources.export_raw_bundle
import com.androidperformancestudio.battery.battery_app.generated.resources.reset_statistics
import com.androidperformancestudio.ui.ActivePageMenu
import com.androidperformancestudio.ui.MenuCommand
import com.androidperformancestudio.ui.PageMenu
import com.androidperformancestudio.ui.RecentMenu
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Path

internal data class BatteryRecentMenuItem(
    val label: String,
    val path: Path,
)

internal data class BatteryExportMenuModel(
    val jsonLabel: String,
    val csvLabel: String,
    val rawEvidenceLabel: String,
    val jsonEnabled: Boolean,
    val csvEnabled: Boolean,
    val rawEvidenceEnabled: Boolean,
)

internal data class BatteryProfilerMenuModel(
    val importEnabled: Boolean,
    val exportMenu: BatteryExportMenuModel,
    val recentItems: List<BatteryRecentMenuItem>,
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
        importEnabled = importEnabled,
        exportMenu =
            BatteryExportMenuModel(
                jsonLabel = localizedStringResource(Res.string.export_json, language),
                csvLabel = localizedStringResource(Res.string.export_csv, language),
                rawEvidenceLabel = localizedStringResource(Res.string.export_raw_bundle, language),
                jsonEnabled = jsonExportEnabled,
                csvEnabled = csvExportEnabled,
                rawEvidenceEnabled = rawEvidenceExportEnabled,
            ),
        recentItems = recentFiles.toBatteryRecentMenuItems(),
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
    ActivePageMenu(
        PageMenu(
            onOpen = onImport,
            openEnabled = model.importEnabled,
            recent =
                RecentMenu(
                    items = model.recentItems.map { item -> MenuCommand(item.label) { onOpenRecent(item.path) } },
                    onClear = onClearRecent,
                ),
            exportGroups =
                listOf(
                    listOf(
                        MenuCommand(model.exportMenu.jsonLabel, enabled = model.exportMenu.jsonEnabled, onClick = onExportJson),
                        MenuCommand(model.exportMenu.csvLabel, enabled = model.exportMenu.csvEnabled, onClick = onExportCsv),
                        MenuCommand(
                            model.exportMenu.rawEvidenceLabel,
                            enabled = model.exportMenu.rawEvidenceEnabled,
                            onClick = onExportRawEvidence,
                        ),
                    ),
                ),
            tools = {
                Item(model.resetStatisticsLabel, enabled = model.resetEnabled, onClick = onResetStatistics)
            },
        ),
    )
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
