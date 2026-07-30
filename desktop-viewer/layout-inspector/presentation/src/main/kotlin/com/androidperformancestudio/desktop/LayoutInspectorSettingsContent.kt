@file:Suppress("FunctionNaming", "LongMethod", "ktlint:standard:function-naming")

package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiLanguage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Complete Layout Inspector settings page used by the unified desktop settings window. */
@Composable
public fun LayoutInspectorSettingsContent(
    language: UiLanguage = UiLanguage.ENGLISH,
    onSettingsChanged: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewOptionsStore = remember { ViewDisplayOptionsStore.desktop() }
    val archiveLimitsStore = remember { CaptureArchiveLimitsStore.desktop() }
    val borderColorStore = remember { CanvasBorderColorStore.desktop() }
    var viewOptions by remember { mutableStateOf(viewOptionsStore.load()) }
    var archiveLimits by remember { mutableStateOf(archiveLimitsStore.load()) }
    var borderColors by remember { mutableStateOf(borderColorStore.load()) }
    var persistenceError by remember { mutableStateOf(false) }
    val strings = ViewerStrings.forLanguage(language)

    fun notifySaved(saved: Boolean) {
        persistenceError = !saved
        onSettingsChanged()
    }

    fun updateViewOptions(updated: ViewDisplayOptions) {
        viewOptions = updated
        notifySaved(viewOptionsStore.save(updated))
    }

    fun updateBorderColors(updated: CanvasBorderColors) {
        borderColors = updated
        notifySaved(borderColorStore.save(updated))
    }

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(strings.layoutInspector, style = MaterialTheme.typography.titleLarge)
        if (persistenceError) {
            Text(
                strings.settingsSaveFailed,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SettingsSection(strings.viewAndHierarchy) {
            SettingsToggleRow(
                label = strings.hideInvisibleHierarchyViews,
                checked = viewOptions.hideInvisibleHierarchyViews,
            ) { updateViewOptions(viewOptions.copy(hideInvisibleHierarchyViews = it)) }
            SettingsToggleRow(
                label = strings.hideInvisibleFindings,
                checked = viewOptions.hideInvisibleFindings,
            ) { updateViewOptions(viewOptions.copy(hideInvisibleFindings = it)) }
            SettingsToggleRow(
                label = strings.hideHierarchyIndices,
                checked = viewOptions.hideHierarchyIndices,
            ) { updateViewOptions(viewOptions.copy(hideHierarchyIndices = it)) }
            SettingsToggleRow(
                label = strings.showHierarchyIds,
                checked = viewOptions.showHierarchyIds,
            ) { updateViewOptions(viewOptions.copy(showHierarchyIds = it)) }
            SettingsToggleRow(
                label = strings.showHierarchyLayerVisibilityButtons,
                checked = viewOptions.showHierarchyLayerVisibilityButtons,
            ) { updateViewOptions(viewOptions.copy(showHierarchyLayerVisibilityButtons = it)) }
            SettingsToggleRow(
                label = strings.showVisibleViewBounds,
                checked = viewOptions.showVisibleViewBounds,
            ) { updateViewOptions(viewOptions.copy(showVisibleViewBounds = it)) }
            CanvasHitTestOrderSetting(viewOptions.canvasHitTestOrder, strings) {
                updateViewOptions(viewOptions.copy(canvasHitTestOrder = it))
            }
        }

        SettingsSection(strings.captureArchive) {
            Text(
                strings.snapshotArchiveLimit(archiveLimits.maxSnapshotSizeMiB),
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(
                value = archiveLimits.snapshotSizeMultiplier.toFloat(),
                onValueChange = { value ->
                    archiveLimits =
                        CaptureArchiveLimits(
                            value
                                .roundToInt()
                                .coerceIn(
                                    CaptureArchiveLimits.MIN_SNAPSHOT_SIZE_MULTIPLIER,
                                    CaptureArchiveLimits.MAX_SNAPSHOT_SIZE_MULTIPLIER,
                                ),
                        )
                },
                onValueChangeFinished = {
                    notifySaved(archiveLimitsStore.save(archiveLimits))
                },
                valueRange =
                    CaptureArchiveLimits.MIN_SNAPSHOT_SIZE_MULTIPLIER.toFloat()..
                        CaptureArchiveLimits.MAX_SNAPSHOT_SIZE_MULTIPLIER.toFloat(),
                steps =
                    CaptureArchiveLimits.MAX_SNAPSHOT_SIZE_MULTIPLIER -
                        CaptureArchiveLimits.MIN_SNAPSHOT_SIZE_MULTIPLIER - 1,
            )
            Text(
                strings.layoutSnapshotArchiveLimitHint,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SettingsSection(strings.canvasBorderColors) {
            CanvasColorField(
                label = strings.normal,
                color = borderColors.normal,
                defaultColor = CanvasBorderColors().normal,
                language = language,
            ) { updateBorderColors(borderColors.copy(normal = it)) }
            CanvasColorField(
                label = strings.hovered,
                color = borderColors.hovered,
                defaultColor = CanvasBorderColors().hovered,
                language = language,
            ) { updateBorderColors(borderColors.copy(hovered = it)) }
            CanvasColorField(
                label = strings.selected,
                color = borderColors.selected,
                defaultColor = CanvasBorderColors().selected,
                language = language,
            ) { updateBorderColors(borderColors.copy(selected = it)) }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(7.dp))
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CanvasHitTestOrderSetting(
    selected: CanvasHitTestOrder,
    strings: ViewerStrings,
    onSelected: (CanvasHitTestOrder) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(strings.canvasHitTestOrder, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CanvasHitTestOrder.entries.forEach { option ->
                val label =
                    when (option) {
                        CanvasHitTestOrder.SMALL_AREA_FIRST -> strings.smallAreaFirst
                        CanvasHitTestOrder.Z_ORDER -> strings.zOrder
                    }
                if (option == selected) {
                    Button(onClick = { onSelected(option) }) { Text(label) }
                } else {
                    OutlinedButton(onClick = { onSelected(option) }) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun CanvasColorField(
    label: String,
    color: CanvasArgb,
    defaultColor: CanvasArgb,
    language: UiLanguage,
    onColorChanged: (CanvasArgb) -> Unit,
) {
    var value by remember(color) { mutableStateOf(color.toHex()) }
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(label, modifier = Modifier.width(82.dp), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.size(20.dp).background(color.toComposeColor(), RoundedCornerShape(10.dp)))
            OutlinedTextField(
                value = value,
                onValueChange = { updated ->
                    value = updated
                    CanvasArgb.parse(updated)?.let(onColorChanged)
                },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    value = defaultColor.toHex()
                    onColorChanged(defaultColor)
                },
            ) {
                Text(ViewerStrings.forLanguage(language).reset)
            }
        }
        Row(
            modifier = Modifier.padding(start = 90.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            canvasColorPresets.forEach { preset ->
                Box(
                    Modifier
                        .size(20.dp)
                        .background(preset.toComposeColor(), RoundedCornerShape(10.dp))
                        .clickable {
                            value = preset.toHex()
                            onColorChanged(preset)
                        },
                )
            }
        }
    }
}
