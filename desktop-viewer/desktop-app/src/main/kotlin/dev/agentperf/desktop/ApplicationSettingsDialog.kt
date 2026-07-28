package dev.agentperf.desktop

import org.jetbrains.compose.resources.stringResource

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
        title = { Text(stringResource(Res.string.general_settings)) },
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
                    label = stringResource(Res.string.language),
                    selected = settings.language,
                    options = ApplicationLanguagePreference.entries,
                    optionLabel = { languagePreferenceLabel(it, chinese) },
                    onSelected = { onSettingsChanged(settings.copy(language = it)) },
                )
                PreferenceDropdown(
                    chinese = chinese,
                    label = stringResource(Res.string.theme),
                    selected = settings.theme,
                    options = ApplicationThemePreference.entries,
                    optionLabel = { themePreferenceLabel(it, chinese) },
                    onSelected = { onSettingsChanged(settings.copy(theme = it)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.done))
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
    optionLabel: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.text, label, optionLabel(selected)))
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

@Composable
internal fun languagePreferenceLabel(
    preference: ApplicationLanguagePreference,
    chinese: Boolean,
): String =
    when (preference) {
        ApplicationLanguagePreference.SYSTEM -> stringResource(Res.string.system)
        ApplicationLanguagePreference.SIMPLIFIED_CHINESE -> stringResource(Res.string.simplified_chinese)
        ApplicationLanguagePreference.ENGLISH -> stringResource(Res.string.english)
    }

@Composable
internal fun themePreferenceLabel(
    preference: ApplicationThemePreference,
    chinese: Boolean,
): String =
    when (preference) {
        ApplicationThemePreference.SYSTEM -> stringResource(Res.string.system)
        ApplicationThemePreference.LIGHT -> stringResource(Res.string.light)
        ApplicationThemePreference.DARK -> stringResource(Res.string.dark)
    }
