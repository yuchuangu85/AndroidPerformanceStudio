package com.androidperformancestudio.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.compose_inspection_hide_details
import com.androidperformancestudio.presentation.generated.resources.compose_inspection_project_hint
import com.androidperformancestudio.presentation.generated.resources.compose_inspection_show_details
import com.androidperformancestudio.presentation.generated.resources.compose_inspection_unavailable
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui_components.generated.resources.icon_collapse
import com.androidperformancestudio.ui_components.generated.resources.icon_expand
import org.jetbrains.compose.resources.painterResource
import com.androidperformancestudio.ui_components.generated.resources.Res as UiRes

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
internal fun ComposeInspectionWarningBanner(
    reason: String,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    var expanded by remember(reason) { mutableStateOf(false) }
    val toggleLabel =
        localizedStringResource(
            if (expanded) Res.string.compose_inspection_hide_details else Res.string.compose_inspection_show_details,
            language,
        )
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.riskSectionBackground)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("layout-compose-fallback-warning"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CopyableText(
                text = localizedStringResource(Res.string.compose_inspection_unavailable, language) + "\n" +
                    localizedStringResource(Res.string.compose_inspection_project_hint, language),
                modifier = Modifier.weight(1f).testTag("layout-compose-fallback-warning-summary"),
                color = colors.warning,
                fontSize = ViewerTypography.label.fontSize,
                language = language,
            )
            TextButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.semantics { contentDescription = toggleLabel },
            ) {
                Text(toggleLabel, style = ViewerTypography.label, color = colors.warning)
                Icon(
                    painter = painterResource(if (expanded) UiRes.drawable.icon_expand else UiRes.drawable.icon_collapse),
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.warning,
                )
            }
        }
        if (expanded) {
            ComposeInspectionErrorDetails(reason, language)
        }
    }
}

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
private fun ComposeInspectionErrorDetails(
    reason: String,
    language: UiLanguage,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .testTag("layout-compose-fallback-warning-details")
                .verticalScroll(rememberScrollState()),
    ) {
        CopyableText(
            text = reason,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = LocalViewerColors.current.warning,
            fontSize = ViewerTypography.label.fontSize,
            language = language,
        )
    }
}
