@file:Suppress("FunctionNaming", "LongMethod", "ktlint:standard:function-naming")

package com.androidperformancestudio.desktop

import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.*
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
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
import com.androidperformancestudio.ui.switch.MacOSSwitch
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
        Text(localizedStringResource(Res.string.layout_inspector, language), style = MaterialTheme.typography.titleLarge)
        if (persistenceError) {
            Text(
                localizedStringResource(Res.string.settings_save_failed, language),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SettingsSection(localizedStringResource(Res.string.view_and_hierarchy, language)) {
            SettingsToggleRow(
                label = localizedStringResource(Res.string.hide_invisible_hierarchy_views, language),
                checked = viewOptions.hideInvisibleHierarchyViews,
            ) { updateViewOptions(viewOptions.copy(hideInvisibleHierarchyViews = it)) }
            SettingsToggleRow(
                label = localizedStringResource(Res.string.hide_invisible_findings, language),
                checked = viewOptions.hideInvisibleFindings,
            ) { updateViewOptions(viewOptions.copy(hideInvisibleFindings = it)) }
            SettingsToggleRow(
                label = localizedStringResource(Res.string.hide_hierarchy_indices, language),
                checked = viewOptions.hideHierarchyIndices,
            ) { updateViewOptions(viewOptions.copy(hideHierarchyIndices = it)) }
            SettingsToggleRow(
                label = localizedStringResource(Res.string.show_hierarchy_ids, language),
                checked = viewOptions.showHierarchyIds,
            ) { updateViewOptions(viewOptions.copy(showHierarchyIds = it)) }
            SettingsToggleRow(
                label = localizedStringResource(Res.string.show_hierarchy_layer_visibility_buttons, language),
                checked = viewOptions.showHierarchyLayerVisibilityButtons,
            ) { updateViewOptions(viewOptions.copy(showHierarchyLayerVisibilityButtons = it)) }
            SettingsToggleRow(
                label = localizedStringResource(Res.string.show_visible_view_bounds, language),
                checked = viewOptions.showVisibleViewBounds,
            ) { updateViewOptions(viewOptions.copy(showVisibleViewBounds = it)) }
            CanvasHitTestOrderSetting(viewOptions.canvasHitTestOrder, language) {
                updateViewOptions(viewOptions.copy(canvasHitTestOrder = it))
            }
        }

        SettingsSection(localizedStringResource(Res.string.capture_archive, language)) {
            Text(
                localizedStringResource(
                    Res.string.snapshot_archive_limit_value,
                    language,
                    archiveLimits.maxSnapshotSizeMiB,
                ),
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
                localizedStringResource(Res.string.layout_snapshot_archive_limit_hint, language),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SettingsSection(localizedStringResource(Res.string.canvas_border_colors, language)) {
            CanvasColorField(
                label = localizedStringResource(Res.string.normal, language),
                color = borderColors.normal,
                defaultColor = CanvasBorderColors().normal,
                language = language,
            ) { updateBorderColors(borderColors.copy(normal = it)) }
            CanvasColorField(
                label = localizedStringResource(Res.string.hovered, language),
                color = borderColors.hovered,
                defaultColor = CanvasBorderColors().hovered,
                language = language,
            ) { updateBorderColors(borderColors.copy(hovered = it)) }
            CanvasColorField(
                label = localizedStringResource(Res.string.selected, language),
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
//        Switch(checked = checked, onCheckedChange = onCheckedChange)
        MacOSSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CanvasHitTestOrderSetting(
    selected: CanvasHitTestOrder,
    language: UiLanguage,
    onSelected: (CanvasHitTestOrder) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(localizedStringResource(Res.string.canvas_hit_test_order, language), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CanvasHitTestOrder.entries.forEach { option ->
                val label =
                    when (option) {
                        CanvasHitTestOrder.SMALL_AREA_FIRST -> localizedStringResource(Res.string.small_area_first, language)
                        CanvasHitTestOrder.Z_ORDER -> localizedStringResource(Res.string.z_order, language)
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
                Text(localizedStringResource(Res.string.reset, language))
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
