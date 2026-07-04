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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsDialog(
    selectedThemePreference: ThemePreference,
    onSelectThemePreference: (ThemePreference) -> Unit,
    selectedLanguagePreference: LanguagePreference,
    onSelectLanguagePreference: (LanguagePreference) -> Unit,
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
                    text = strings.settings,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                SettingsCloseButton(onDismiss)
            }
        },
        text = {
            Column(
                modifier = Modifier.width(360.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = strings.theme,
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemePreference.entries.forEach { preference ->
                        ThemePreferenceOption(
                            label = strings.themePreferenceName(preference),
                            selected = preference == selectedThemePreference,
                            onClick = { onSelectThemePreference(preference) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = strings.languageSetting,
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                LanguagePreferenceDropdown(
                    selectedPreference = selectedLanguagePreference,
                    onSelectPreference = onSelectLanguagePreference,
                )
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun ThemePreferenceOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    Row(
        modifier = modifier
            .background(
                color = if (selected) {
                    colors.accent.copy(alpha = 0.12f)
                } else {
                    colors.sectionBackground
                },
                shape = RoundedCornerShape(6.dp),
            )
            .selectable(
                selected = selected,
                onClick = onClick,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier.size(18.dp),
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.accent,
                unselectedColor = colors.mutedText,
            ),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            color = colors.primaryText,
            fontSize = 12.sp,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun LanguagePreferenceDropdown(
    selectedPreference: LanguagePreference,
    onSelectPreference: (LanguagePreference) -> Unit,
) {
    val colors = LocalViewerColors.current
    val strings = LocalViewerStrings.current
    var dropdownState by remember { mutableStateOf(SettingsDropdownState()) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.sectionBackground, RoundedCornerShape(6.dp))
                .clickable { dropdownState = dropdownState.toggle() }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = strings.languagePreferenceName(selectedPreference),
                color = colors.primaryText,
                fontSize = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            Canvas(Modifier.size(9.dp)) {
                val strokeWidth = 1.3.dp.toPx()
                val top = if (dropdownState.expanded) size.height * 0.7f else size.height * 0.3f
                val bottom = if (dropdownState.expanded) size.height * 0.3f else size.height * 0.7f
                drawLine(
                    color = colors.secondaryText,
                    start = Offset(0f, top),
                    end = Offset(size.width / 2f, bottom),
                    strokeWidth = strokeWidth,
                )
                drawLine(
                    color = colors.secondaryText,
                    start = Offset(size.width / 2f, bottom),
                    end = Offset(size.width, top),
                    strokeWidth = strokeWidth,
                )
            }
        }
        DropdownMenu(
            expanded = dropdownState.expanded,
            onDismissRequest = { dropdownState = dropdownState.dismiss() },
            modifier = Modifier
                .width(360.dp)
                .background(colors.panel),
        ) {
            LanguagePreference.entries.forEach { preference ->
                val selected = preference == selectedPreference
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (selected) {
                                "✓  ${strings.languagePreferenceName(preference)}"
                            } else {
                                "    ${strings.languagePreferenceName(preference)}"
                            },
                            color = if (selected) colors.accent else colors.primaryText,
                            fontSize = 12.sp,
                        )
                    },
                    onClick = {
                        dropdownState = dropdownState.dismiss()
                        onSelectPreference(preference)
                    },
                )
            }
        }
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
