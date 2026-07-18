package dev.agentperf.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal object ApplicationSettingsDialogStyle {
    const val CONTENT_WIDTH_DP = 420
    const val DROPDOWN_WIDTH_DP = 420
}

@Composable
internal fun ApplicationSettingsDialog(
    settings: ApplicationUiSettings,
    chinese: Boolean,
    onSettingsChanged: (ApplicationUiSettings) -> Unit,
    onOpenUserGuide: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (chinese) "通用设置" else "General Settings") },
        text = {
            Column(
                modifier =
                    Modifier
                        .width(ApplicationSettingsDialogStyle.CONTENT_WIDTH_DP.dp)
                        .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PreferenceDropdown(
                    label = if (chinese) "语言" else "Language",
                    selected = settings.language,
                    options = ApplicationLanguagePreference.entries,
                    optionLabel = { languagePreferenceLabel(it, chinese) },
                    onSelected = { onSettingsChanged(settings.copy(language = it)) },
                )
                PreferenceDropdown(
                    label = if (chinese) "主题" else "Theme",
                    selected = settings.theme,
                    options = ApplicationThemePreference.entries,
                    optionLabel = { themePreferenceLabel(it, chinese) },
                    onSelected = { onSettingsChanged(settings.copy(theme = it)) },
                )
                OutlinedButton(
                    onClick = onOpenUserGuide,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (chinese) "在浏览器中打开用户指南" else "Open User Guide in Browser")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (chinese) "完成" else "Done")
            }
        },
    )
}

@Composable
private fun <T> PreferenceDropdown(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("$label: ${optionLabel(selected)}")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.width(ApplicationSettingsDialogStyle.DROPDOWN_WIDTH_DP.dp),
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                )
            }
        }
    }
}

private fun languagePreferenceLabel(
    preference: ApplicationLanguagePreference,
    chinese: Boolean,
): String =
    when (preference) {
        ApplicationLanguagePreference.SYSTEM -> if (chinese) "跟随系统" else "System"
        ApplicationLanguagePreference.SIMPLIFIED_CHINESE -> if (chinese) "简体中文" else "Simplified Chinese"
        ApplicationLanguagePreference.ENGLISH -> if (chinese) "英文" else "English"
    }

private fun themePreferenceLabel(
    preference: ApplicationThemePreference,
    chinese: Boolean,
): String =
    when (preference) {
        ApplicationThemePreference.SYSTEM -> if (chinese) "跟随系统" else "System"
        ApplicationThemePreference.LIGHT -> if (chinese) "浅色" else "Light"
        ApplicationThemePreference.DARK -> if (chinese) "深色" else "Dark"
    }
