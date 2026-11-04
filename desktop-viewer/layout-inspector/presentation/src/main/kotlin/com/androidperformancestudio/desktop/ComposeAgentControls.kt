@file:Suppress("FunctionNaming", "ktlint:standard:function-naming")

package com.androidperformancestudio.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.compose.inspection.host.ComposeInspectionPreflight
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.cancel
import com.androidperformancestudio.presentation.generated.resources.compose_agent_authorize
import com.androidperformancestudio.presentation.generated.resources.compose_agent_no
import com.androidperformancestudio.presentation.generated.resources.compose_agent_preflight
import com.androidperformancestudio.presentation.generated.resources.compose_agent_title
import com.androidperformancestudio.presentation.generated.resources.compose_agent_yes
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource

@Composable
internal fun ComposeAgentToggle(
    checked: Boolean,
    canEnable: Boolean,
    language: UiLanguage,
    onCheckedChange: (Boolean) -> Unit,
) {
    val enabled = checked || canEnable
    Row(
        modifier =
            Modifier
                .testTag("layout-compose-agent-toggle")
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Checkbox,
                    onValueChange = onCheckedChange,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.size(22.dp), enabled = enabled)
        Text(localizedStringResource(Res.string.compose_agent_title, language), style = ViewerTypography.label)
    }
}

@Composable
internal fun ComposeAgentAuthorizationDialog(
    preflight: ComposeInspectionPreflight,
    language: UiLanguage,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val yes = localizedStringResource(Res.string.compose_agent_yes, language)
    val no = localizedStringResource(Res.string.compose_agent_no, language)
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(localizedStringResource(Res.string.compose_agent_title, language)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                CopyableText(
                    localizedStringResource(
                        Res.string.compose_agent_preflight,
                        language,
                        preflight.serial,
                        preflight.packageName,
                        preflight.pid,
                        preflight.apiLevel,
                        preflight.abi,
                        preflight.composeVersion ?: "?",
                        preflight.inspectorSource ?: "?",
                        if (preflight.inspectorDownloadRequired) yes else no,
                        if (preflight.appRestartRequired) yes else no,
                    ),
                    language = language,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("layout-compose-agent-authorize")) {
                Text(localizedStringResource(Res.string.compose_agent_authorize, language))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(localizedStringResource(Res.string.cancel, language)) }
        },
    )
}
