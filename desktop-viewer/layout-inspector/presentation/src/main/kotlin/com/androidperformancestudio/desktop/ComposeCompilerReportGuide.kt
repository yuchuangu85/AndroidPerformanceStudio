package com.androidperformancestudio.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.*
import com.androidperformancestudio.ui.CopyableText
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import org.jetbrains.compose.resources.StringResource

internal val COMPOSE_REPORT_CONFIGURATION =
    """
    composeCompiler {
        reportsDestination =
            layout.buildDirectory.dir("compose_compiler")
        metricsDestination =
            layout.buildDirectory.dir("compose_compiler")
    }
    """.trimIndent()

internal const val COMPOSE_REPORT_GENERATE_COMMAND = "./gradlew :app:compileReleaseKotlin"

@Composable
internal fun ComposeCompilerReportGuide(
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        GuideText(Res.string.compose_reports_purpose, language)
        GuideHeading(Res.string.compose_reports_configure, language)
        GuideText(Res.string.compose_reports_configure_hint, language)
        GuideCode(COMPOSE_REPORT_CONFIGURATION, "compose-report-configuration", language)
        GuideHeading(Res.string.compose_reports_generate, language)
        GuideText(Res.string.compose_reports_generate_hint, language)
        GuideCode(COMPOSE_REPORT_GENERATE_COMMAND, "compose-report-generate-command", language)
        GuideText(Res.string.compose_reports_output_hint, language)
        GuideHeading(Res.string.compose_reports_import, language)
        GuideText(Res.string.compose_reports_import_hint, language)
        CopyableText(
            text = localizedStringResource(Res.string.compose_reports_limitations, language),
            modifier = Modifier.fillMaxWidth(),
            color = colors.secondaryText,
            style = ViewerTypography.secondary,
            lineHeight = 18.sp,
            language = language,
        )
    }
}

@Composable
private fun GuideHeading(resource: StringResource, language: UiLanguage) {
    Text(
        text = localizedStringResource(resource, language),
        modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
        color = LocalViewerColors.current.primaryText,
        style = ViewerTypography.bodyCompact,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun GuideText(resource: StringResource, language: UiLanguage) {
    CopyableText(
        text = localizedStringResource(resource, language),
        modifier = Modifier.fillMaxWidth(),
        color = LocalViewerColors.current.primaryText,
        style = ViewerTypography.bodyCompact,
        lineHeight = 20.sp,
        language = language,
    )
}

@Composable
private fun GuideCode(text: String, tag: String, language: UiLanguage) {
    val colors = LocalViewerColors.current
    CopyableText(
        text = text,
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.workspace, RoundedCornerShape(4.dp))
                .padding(8.dp)
                .testTag(tag),
        color = colors.primaryText,
        fontFamily = FontFamily.Monospace,
        style = ViewerTypography.secondary,
        language = language,
    )
}
