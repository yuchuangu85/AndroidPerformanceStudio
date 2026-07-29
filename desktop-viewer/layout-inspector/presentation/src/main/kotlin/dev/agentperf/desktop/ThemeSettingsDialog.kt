package dev.agentperf.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.ui.LocalViewerColors
import kotlin.math.roundToInt

internal object SettingsDialogStyle {
    const val TITLE_FONT_SIZE_SP = 20
    const val SECTION_TITLE_FONT_SIZE_SP = 13
    const val CONTENT_FONT_SIZE_SP = 11
    const val SECTION_SEPARATOR_COUNT = 2
    const val SEPARATOR_HEIGHT_DP = 1
    const val SEPARATOR_VERTICAL_PADDING_DP = 12
}

@Composable
internal fun SettingsDialog(
    viewDisplayOptions: ViewDisplayOptions,
    onViewDisplayOptionsChanged: (ViewDisplayOptions) -> Unit,
    archiveLimits: CaptureArchiveLimits,
    onArchiveLimitsChanged: (CaptureArchiveLimits) -> Unit,
    canvasBorderColors: CanvasBorderColors,
    onCanvasBorderColorsChanged: (CanvasBorderColors) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.panel,
        titleContentColor = colors.primaryText,
        textContentColor = colors.rowText,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = strings.layoutInspectorSettings,
                    fontSize = SettingsDialogStyle.TITLE_FONT_SIZE_SP.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                SettingsCloseButton(onDismiss)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .width(520.dp)
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                SettingsSectionTitle(strings.view)
                Spacer(Modifier.height(8.dp))
                SettingsToggleRow(
                    label = strings.showHierarchyLayerVisibilityButtons,
                    enabled = viewDisplayOptions.showHierarchyLayerVisibilityButtons,
                    onToggle = {
                        onViewDisplayOptionsChanged(
                            viewDisplayOptions.toggleHierarchyLayerVisibilityButtons(),
                        )
                    },
                )
                SettingsMenuSeparator()
                SettingsSectionTitle(strings.captureArchive)
                Spacer(Modifier.height(8.dp))
                ArchiveSnapshotLimitSetting(
                    limits = archiveLimits,
                    onLimitsChanged = onArchiveLimitsChanged,
                )
                SettingsMenuSeparator()
                SettingsSectionTitle(strings.canvasBorderColors)
                Spacer(Modifier.height(4.dp))
                CanvasColorSetting(
                    strings.defaultViewBoundsColor,
                    canvasBorderColors.normal,
                    CanvasBorderColors().normal,
                ) { onCanvasBorderColorsChanged(canvasBorderColors.copy(normal = it)) }
                CanvasColorSetting(
                    strings.hoveredViewBoundsColor,
                    canvasBorderColors.hovered,
                    CanvasBorderColors().hovered,
                ) { onCanvasBorderColorsChanged(canvasBorderColors.copy(hovered = it)) }
                CanvasColorSetting(
                    strings.selectedViewBoundsColor,
                    canvasBorderColors.selected,
                    CanvasBorderColors().selected,
                ) { onCanvasBorderColorsChanged(canvasBorderColors.copy(selected = it)) }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ArchiveSnapshotLimitSetting(
    limits: CaptureArchiveLimits,
    onLimitsChanged: (CaptureArchiveLimits) -> Unit,
) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.sectionBackground, RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.layoutSnapshotArchiveLimit,
                color = colors.primaryText,
                fontSize = SettingsDialogStyle.CONTENT_FONT_SIZE_SP.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = strings.archiveLimitValue(limits),
                color = colors.accent,
                fontSize = SettingsDialogStyle.CONTENT_FONT_SIZE_SP.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Slider(
            value = limits.snapshotSizeMultiplier.toFloat(),
            onValueChange = { value ->
                val multiplier = value.roundToInt().coerceIn(
                    CaptureArchiveLimits.MIN_SNAPSHOT_SIZE_MULTIPLIER,
                    CaptureArchiveLimits.MAX_SNAPSHOT_SIZE_MULTIPLIER,
                )
                if (multiplier != limits.snapshotSizeMultiplier) {
                    onLimitsChanged(CaptureArchiveLimits(multiplier))
                }
            },
            valueRange =
                CaptureArchiveLimits.MIN_SNAPSHOT_SIZE_MULTIPLIER.toFloat()..
                    CaptureArchiveLimits.MAX_SNAPSHOT_SIZE_MULTIPLIER.toFloat(),
            steps = CaptureArchiveLimits.MAX_SNAPSHOT_SIZE_MULTIPLIER -
                CaptureArchiveLimits.MIN_SNAPSHOT_SIZE_MULTIPLIER - 1,
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
        )
        Text(
            text = strings.layoutSnapshotArchiveLimitHint,
            color = colors.mutedText,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalViewerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.sectionBackground, RoundedCornerShape(6.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = colors.primaryText,
            fontSize = SettingsDialogStyle.CONTENT_FONT_SIZE_SP.sp,
        )
        Spacer(Modifier.weight(1f))
        SettingsToggleSwitch(enabled)
    }
}

@Composable
private fun SettingsToggleSwitch(enabled: Boolean) {
    val colors = LocalViewerColors.current
    Box(
        modifier =
            Modifier
                .width(30.dp)
                .height(16.dp)
                .background(
                    color = if (enabled) {
                        colors.accent.copy(alpha = 0.55f)
                    } else {
                        colors.switchTrackOff
                    },
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(2.dp),
        contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .background(
                    color = if (enabled) Color.White else colors.switchThumbOff,
                    shape = RoundedCornerShape(50),
                ),
        )
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    val colors = LocalViewerColors.current
    Text(
        text = text,
        color = colors.secondaryText,
        fontSize = SettingsDialogStyle.SECTION_TITLE_FONT_SIZE_SP.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun SettingsMenuSeparator() {
    val colors = LocalViewerColors.current
    Box(
        modifier = Modifier
            .padding(vertical = SettingsDialogStyle.SEPARATOR_VERTICAL_PADDING_DP.dp)
            .fillMaxWidth()
            .height(SettingsDialogStyle.SEPARATOR_HEIGHT_DP.dp)
            .background(colors.border),
    )
}

@Composable
private fun CanvasColorSetting(
    label: String,
    value: CanvasArgb,
    defaultValue: CanvasArgb,
    onValueChanged: (CanvasArgb) -> Unit,
) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    var text by remember(value) { mutableStateOf(value.toHex()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = colors.primaryText,
            fontSize = SettingsDialogStyle.CONTENT_FONT_SIZE_SP.sp,
            modifier = Modifier.width(120.dp),
        )
        canvasColorPresets.forEach { preset ->
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(18.dp)
                    .background(preset.toComposeColor(), RoundedCornerShape(9.dp))
                    .clickable {
                        text = preset.toHex()
                        onValueChanged(preset)
                    },
            )
        }
        BasicTextField(
            value = text,
            onValueChange = { updated ->
                text = updated
                CanvasArgb.parse(updated)?.let(onValueChanged)
            },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = colors.primaryText,
                fontSize = SettingsDialogStyle.CONTENT_FONT_SIZE_SP.sp,
            ),
            modifier = Modifier
                .padding(start = 8.dp)
                .width(86.dp)
                .background(colors.sectionBackground, RoundedCornerShape(4.dp))
                .padding(4.dp),
        )
        Text(
            strings.reset,
            color = colors.accent,
            fontSize = 10.sp,
            modifier = Modifier
                .padding(start = 6.dp)
                .clickable {
                    text = defaultValue.toHex()
                    onValueChanged(defaultValue)
                },
        )
    }
}

@Composable
private fun SettingsCloseButton(onClick: () -> Unit) {
    val colors = LocalViewerColors.current
    Box(
        modifier = Modifier
            .size(24.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val strokeWidth = 1.4.dp.toPx()
            drawLine(
                color = colors.mutedText,
                start = Offset.Zero,
                end = Offset(size.width, size.height),
                strokeWidth = strokeWidth,
            )
            drawLine(
                color = colors.mutedText,
                start = Offset(size.width, 0f),
                end = Offset(0f, size.height),
                strokeWidth = strokeWidth,
            )
        }
    }
}
