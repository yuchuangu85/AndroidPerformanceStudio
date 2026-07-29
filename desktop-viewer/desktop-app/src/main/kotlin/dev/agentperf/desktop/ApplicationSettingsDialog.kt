package dev.agentperf.desktop

import com.androidperformancestudio.ui.localizedStringResource
import dev.agentperf.desktop_app.generated.resources.Res
import dev.agentperf.desktop_app.generated.resources.*

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
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localizedStringResource(Res.string.general_settings, chinese)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .width(ApplicationSettingsDialogStyle.CONTENT_WIDTH_DP.dp)
                        .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PreferenceDropdown(
                    chinese = chinese,
                    label = localizedStringResource(Res.string.language, chinese),
                    selected = settings.language,
                    options = ApplicationLanguagePreference.entries,
                    optionLabel = { languagePreferenceLabel(it, chinese) },
                    onSelected = { onSettingsChanged(settings.copy(language = it)) },
                )
                PreferenceDropdown(
                    chinese = chinese,
                    label = localizedStringResource(Res.string.theme, chinese),
                    selected = settings.theme,
                    options = ApplicationThemePreference.entries,
                    optionLabel = { themePreferenceLabel(it, chinese) },
                    onSelected = { onSettingsChanged(settings.copy(theme = it)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(localizedStringResource(Res.string.done, chinese))
            }
        },
    )
}

@Composable
private fun <T> PreferenceDropdown(
    chinese: Boolean,
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
            Text(localizedStringResource(Res.string.text, chinese, label, optionLabel(selected)))
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

internal fun languagePreferenceLabel(
    preference: ApplicationLanguagePreference,
    chinese: Boolean,
): String =
    when (preference) {
        ApplicationLanguagePreference.SYSTEM -> localizedStringResource(Res.string.system, chinese)
        ApplicationLanguagePreference.SIMPLIFIED_CHINESE -> localizedStringResource(Res.string.simplified_chinese, chinese)
        ApplicationLanguagePreference.ENGLISH -> localizedStringResource(Res.string.english, chinese)
    }

internal fun themePreferenceLabel(
    preference: ApplicationThemePreference,
    chinese: Boolean,
): String =
    when (preference) {
        ApplicationThemePreference.SYSTEM -> localizedStringResource(Res.string.system, chinese)
        ApplicationThemePreference.LIGHT -> localizedStringResource(Res.string.light, chinese)
        ApplicationThemePreference.DARK -> localizedStringResource(Res.string.dark, chinese)
    }
