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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
                ThemePreference.entries.forEach { preference ->
                    SettingsPreferenceOption(
                        label = strings.themePreferenceName(preference),
                        selected = preference == selectedThemePreference,
                        onClick = { onSelectThemePreference(preference) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = strings.languageSetting,
                    color = colors.secondaryText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                LanguagePreference.entries.forEach { preference ->
                    SettingsPreferenceOption(
                        label = strings.languagePreferenceName(preference),
                        selected = preference == selectedLanguagePreference,
                        onClick = { onSelectLanguagePreference(preference) },
                    )
                }
            }
        },
        confirmButton = {},
    )
}

@Composable
private fun SettingsPreferenceOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalViewerColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
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
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = colors.accent,
                unselectedColor = colors.mutedText,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            color = colors.primaryText,
            fontSize = 13.sp,
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
