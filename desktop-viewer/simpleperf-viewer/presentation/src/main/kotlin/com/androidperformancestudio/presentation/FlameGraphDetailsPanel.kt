package com.androidperformancestudio.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.androidperformancestudio.application.FlameGraphDetailsState
import com.androidperformancestudio.application.FlameGraphFrameDetails

internal data class FlameGraphDetailsContent(
    val title: String,
    val lines: List<String>,
    val selectedLineIndex: Int? = null,
    val monospace: Boolean = false,
)

internal object FlameGraphDetailsPresenter {
    fun content(state: FlameGraphDetailsState): FlameGraphDetailsContent? =
        when (state) {
            FlameGraphDetailsState.Closed -> null
            is FlameGraphDetailsState.Loading ->
                FlameGraphDetailsContent(
                    title = "Loading frame details…",
                    lines = listOf("Resolving source, symbols, or disassembly for the selected frame."),
                )
            is FlameGraphDetailsState.Ready -> content(state.details)
        }

    private fun content(details: FlameGraphFrameDetails): FlameGraphDetailsContent =
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
                    title = "Symbol details",
                    lines =
                        listOfNotNull(
                            "Function: ${details.function}",
                            "Resource: ${details.resource}",
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
internal fun FlameGraphDetailsPanel(
    state: FlameGraphDetailsState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = FlameGraphDetailsPresenter.content(state) ?: return
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .testTag("flame-details")
                .semantics {
                    contentDescription = content.title
                    liveRegion = LiveRegionMode.Polite
                },
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(content.title, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onClose) { Text(localizedSimpleperfText("Close")) }
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
                            Modifier.background(MaterialTheme.colorScheme.secondaryContainer).fillMaxWidth()
                        } else {
                            Modifier.fillMaxWidth()
                        }
                    Text(
                        text = line,
                        modifier = lineModifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (content.monospace) FontFamily.Monospace else null,
                    )
                }
            }
        }
    }
}

private fun disassemblyTitle(details: FlameGraphFrameDetails.Disassembly): String =
    "Disassembly · ${details.binary.fileName?.toString() ?: details.binary} @ ${details.address.hex()}"

private fun Long.hex(): String = "0x${toString(HEX_RADIX)}"

private const val HEX_RADIX = 16
private const val DETAILS_PANEL_MAX_HEIGHT_DP = 220
