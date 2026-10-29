package com.androidperformancestudio.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.compose_inspection_unavailable
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
internal fun ComposeInspectionWarningBanner(
    reason: String,
    language: UiLanguage,
) {
    val colors = LocalViewerColors.current
    Text(
        text = localizedStringResource(Res.string.compose_inspection_unavailable, language) + ": " + reason,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.riskSectionBackground)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("layout-compose-fallback-warning"),
        color = colors.warning,
        fontSize = ViewerTypography.label.fontSize,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
