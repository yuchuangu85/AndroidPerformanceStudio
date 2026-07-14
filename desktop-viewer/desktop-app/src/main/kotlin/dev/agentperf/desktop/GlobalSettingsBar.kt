package dev.agentperf.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun GlobalSettingsBar(
    settings: ApplicationUiSettings,
    chinese: Boolean,
    onSettingsChanged: (ApplicationUiSettings) -> Unit,
) {
    Surface(tonalElevation = 2.dp, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Android Performance Studio", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            }
        }
    }
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
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.width(180.dp)) {
            Text("$label: ${optionLabel(selected)}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
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
