package com.androidperformancestudio.presentation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidperformancestudio.application.FlameGraphDetailsState
import com.androidperformancestudio.application.FlameGraphFrameDetails
import com.androidperformancestudio.presentation.generated.resources.SimpleperfViewerRes
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.visualization.FirefoxFlameGraphStyle

internal data class FlameGraphDetailsContent(
    val title: String,
    val lines: List<String>,
    val selectedLineIndex: Int? = null,
    val monospace: Boolean = false,
)

internal object FlameGraphDetailsPresenter {
    fun content(
        state: FlameGraphDetailsState,
        language: UiLanguage = UiLanguage.ENGLISH,
    ): FlameGraphDetailsContent? =
        when (state) {
            FlameGraphDetailsState.Closed -> null
            is FlameGraphDetailsState.Loading ->
                FlameGraphDetailsContent(
                    title = localizedStringResource(SimpleperfViewerRes.sp_details_loading_frame_details, language),
                    lines =
                        listOf(
                            localizedStringResource(SimpleperfViewerRes.sp_details_resolving_frame_details, language),
                        ),
                )
            is FlameGraphDetailsState.Ready -> content(state.details, language)
        }

    private fun content(
        details: FlameGraphFrameDetails,
        language: UiLanguage,
    ): FlameGraphDetailsContent =
        when (details) {
            is FlameGraphFrameDetails.Source ->
                FlameGraphDetailsContent(
                    title =
                        buildString {
                            append("Source · ")
                            append(details.file.fileName?.toString() ?: details.file.toString())
                            append(':')
                            append(details.line)
                            details.column?.let { append(':').append(it) }
                        },
                    lines = details.text,
                    selectedLineIndex = (details.line - 1).coerceAtLeast(0).takeIf { details.text.isNotEmpty() },
                    monospace = true,
                )
            is FlameGraphFrameDetails.Disassembly ->
                FlameGraphDetailsContent(
                    title = disassemblyTitle(details),
                    lines = details.text,
                    monospace = true,
                )
            is FlameGraphFrameDetails.SymbolFallback ->
                FlameGraphDetailsContent(
                    title = localizedStringResource(SimpleperfViewerRes.sp_details_symbol_details, language),
                    lines =
                        listOfNotNull(
                            "Function: ${details.function}",
                            localizedStringResource(
                                SimpleperfViewerRes.sp_details_resource_value_format,
                                language,
                                details.resource,
                            ),
                            "Address: ${details.address.hex()}",
                            "Library offset: ${details.libraryOffset.hex()}",
                            details.buildId?.let { "Build ID: $it" },
                            "Reason: ${details.reason}",
                        ),
                )
        }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FirefoxFrameDetailsBottomBox(
    state: FlameGraphDetailsState,
    onClose: () -> Unit,
    style: FirefoxFlameGraphStyle,
    modifier: Modifier = Modifier,
) {
    val content = FlameGraphDetailsPresenter.content(state, currentSimpleperfLanguage()) ?: return
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("flame-details")
                .semantics {
                    contentDescription = content.title
                    liveRegion = LiveRegionMode.Polite
                },
        color = style.panelSurface.toComposeColor(),
        contentColor = style.canvasForeground.toComposeColor(),
        border = BorderStroke(1.dp, style.viewportBorder.toComposeColor()),
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(content.title, color = style.canvasForeground.toComposeColor(), fontSize = 12.sp)
                Text(
                    localizedStringResource(
                        SimpleperfViewerRes.sp_details_close,
                        currentSimpleperfLanguage(),
                    ),
                    modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 5.dp, vertical = 2.dp),
                    color = style.canvasForeground.toComposeColor(),
                    fontSize = 10.sp,
                )
            }
            Column(
                modifier =
                    Modifier
                        .heightIn(max = DETAILS_PANEL_MAX_HEIGHT_DP.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                content.lines.forEachIndexed { index, line ->
                    val lineModifier =
                        if (index == content.selectedLineIndex) {
                            Modifier.background(style.selectedLineSurface.toComposeColor()).fillMaxWidth()
                        } else {
                            Modifier.fillMaxWidth()
                        }
                    Text(
                        text = line,
                        modifier = lineModifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        color = style.canvasForeground.toComposeColor(),
                        fontSize = 10.sp,
                        fontFamily = if (content.monospace) FontFamily.Monospace else null,
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
internal fun FlameGraphDetailsPanel(
    state: FlameGraphDetailsState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FirefoxFrameDetailsBottomBox(state, onClose, rememberFirefoxFlameGraphStyle(), modifier)
}

private fun disassemblyTitle(details: FlameGraphFrameDetails.Disassembly): String =
    "Disassembly · ${details.binary.fileName?.toString() ?: details.binary} @ ${details.address.hex()}"

private fun Long.hex(): String = "0x${toString(HEX_RADIX)}"

private const val HEX_RADIX = 16
private const val DETAILS_PANEL_MAX_HEIGHT_DP = 220
