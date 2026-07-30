package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.desktop_app.generated.resources.Res
import com.androidperformancestudio.desktop_app.generated.resources.*

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
    language: UiLanguage,
    onSettingsChanged: (ApplicationUiSettings) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(localizedStringResource(Res.string.general_settings, language)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .width(ApplicationSettingsDialogStyle.CONTENT_WIDTH_DP.dp)
                        .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PreferenceDropdown(
                    language = language,
                    label = localizedStringResource(Res.string.language, language),
                    selected = settings.language,
                    options = ApplicationLanguagePreference.entries,
                    optionLabel = { languagePreferenceLabel(it, language) },
                    onSelected = { onSettingsChanged(settings.copy(language = it)) },
                )
                PreferenceDropdown(
                    language = language,
                    label = localizedStringResource(Res.string.theme, language),
                    selected = settings.theme,
                    options = ApplicationThemePreference.entries,
                    optionLabel = { themePreferenceLabel(it, language) },
                    onSelected = { onSettingsChanged(settings.copy(theme = it)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(localizedStringResource(Res.string.done, language))
            }
        },
    )
}

@Composable
private fun <T> PreferenceDropdown(
    language: UiLanguage,
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
            Text(localizedStringResource(Res.string.text, language, label, optionLabel(selected)))
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
    language: UiLanguage,
): String =
    when (preference) {
        ApplicationLanguagePreference.SYSTEM -> localizedStringResource(Res.string.system, language)
        ApplicationLanguagePreference.SIMPLIFIED_CHINESE -> localizedStringResource(Res.string.simplified_chinese, language)
        ApplicationLanguagePreference.ENGLISH -> localizedStringResource(Res.string.english, language)
    }

internal fun themePreferenceLabel(
    preference: ApplicationThemePreference,
    language: UiLanguage,
): String =
    when (preference) {
        ApplicationThemePreference.SYSTEM -> localizedStringResource(Res.string.system, language)
        ApplicationThemePreference.LIGHT -> localizedStringResource(Res.string.light, language)
        ApplicationThemePreference.DARK -> localizedStringResource(Res.string.dark, language)
    }
