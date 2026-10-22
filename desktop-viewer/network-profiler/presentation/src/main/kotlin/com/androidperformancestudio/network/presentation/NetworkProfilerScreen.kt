@file:Suppress("FunctionName", "LongMethod", "MagicNumber", "TooManyFunctions")

package com.androidperformancestudio.network.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidperformancestudio.contracts.CaptureArtifact
import com.androidperformancestudio.network.analysis.NetworkSummary
import com.androidperformancestudio.network.model.CallOutcome
import com.androidperformancestudio.network.model.ConnectionUse
import com.androidperformancestudio.network.model.EvidenceCompleteness
import com.androidperformancestudio.network.model.HttpCall
import com.androidperformancestudio.network.model.HttpExchange
import com.androidperformancestudio.network.model.InstrumentationMode
import com.androidperformancestudio.network.model.NetworkCaptureResult
import com.androidperformancestudio.network.model.NetworkConfidence
import com.androidperformancestudio.network.model.NetworkEvidenceSource
import com.androidperformancestudio.network.model.NetworkPhase
import com.androidperformancestudio.network.model.NetworkPhaseKind
import com.androidperformancestudio.network.presentation.generated.resources.*
import com.androidperformancestudio.network.presentation.generated.resources.Res
import com.androidperformancestudio.ui.LocalViewerColors
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.ViewerTypography
import com.androidperformancestudio.ui.localizedStringResource
import com.androidperformancestudio.ui.studio.StudioCard
import com.androidperformancestudio.ui.studio.StudioDataTable
import com.androidperformancestudio.ui.studio.StudioEvidence
import com.androidperformancestudio.ui.studio.StudioEvidenceBadge
import com.androidperformancestudio.ui.studio.StudioMetricCard
import com.androidperformancestudio.ui.studio.StudioPanel
import com.androidperformancestudio.ui.studio.StudioPanelHeader
import com.androidperformancestudio.ui.studio.StudioStatusChip
import com.androidperformancestudio.ui.studio.StudioStatusTone
import com.androidperformancestudio.ui.studio.StudioTableColumn
import com.androidperformancestudio.ui.studio.StudioTokens

public data class NetworkProfilerState(
    val deviceSerial: String = "",
    val packageName: String = "",
    val capturing: Boolean = false,
    val liveEventCount: Int = 0,
    val result: NetworkCaptureResult? = null,
    val summary: NetworkSummary? = null,
    val selectedCallId: String? = null,
    val message: String? = null,
    val error: String? = null,
    val artifact: CaptureArtifact? = null,
)

public data class NetworkProfilerActions(
    val selectCall: (String) -> Unit,
)

@Composable
public fun NetworkProfilerScreen(
    state: NetworkProfilerState,
    actions: NetworkProfilerActions,
    language: UiLanguage,
    modifier: Modifier = Modifier,
) {
    val colors = LocalViewerColors.current
    val calls = state.result?.calls.orEmpty()
    var query by remember(state.result?.session?.id) { mutableStateOf("") }
    var outcome by remember(state.result?.session?.id) { mutableStateOf<CallOutcome?>(null) }
    var showDomains by remember { mutableStateOf(false) }
    val visibleCalls = remember(calls, query, outcome) { filterNetworkCalls(calls, query, outcome) }
    val selectedCall = visibleCalls.firstOrNull { it.callId == state.selectedCallId } ?: visibleCalls.firstOrNull()
    BoxWithConstraints(modifier.fillMaxSize()) {
        val narrow = maxWidth < 1000.dp
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(colors.workspace)
                    .padding(StudioTokens.workspacePadding)
                    .testTag("network-profiler-workspace"),
            verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
        ) {
            SummaryCards(state, language)
            EvidenceCoverage(state, language)
            state.message?.let { MessageStrip(it, StudioStatusTone.INFO) }
            state.error?.let { MessageStrip(it, StudioStatusTone.ERROR) }
            NetworkActivityPanel(calls, language)
            NetworkRequestControls(
                query = query,
                onQueryChange = { query = it },
                outcome = outcome,
                onOutcomeChange = { outcome = it },
                showDomains = showDomains,
                onShowDomains = { showDomains = it },
                visibleCount = visibleCalls.size,
                totalCount = calls.size,
                language = language,
                narrow = narrow,
            )
            val table: @Composable (Modifier) -> Unit = { tableModifier ->
                if (showDomains) {
                    NetworkDomainsPanel(visibleCalls, language, tableModifier)
                } else {
                    BoxWithConstraints(tableModifier) {
                        val tableWidth = maxWidth.coerceAtLeast(900.dp)
                        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
                            RequestTable(
                                calls = visibleCalls,
                                selectedCallId = selectedCall?.callId,
                                language = language,
                                onSelectCall = actions.selectCall,
                                modifier = Modifier.width(tableWidth).fillMaxHeight(),
                            )
                        }
                    }
                }
            }
            if (narrow) {
                Column(Modifier.weight(1f).testTag("network-stacked-panes"), verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
                    table(Modifier.weight(1f).fillMaxWidth())
                    RequestDetail(selectedCall, language, Modifier.weight(1.2f).fillMaxWidth())
                }
            } else {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)) {
                    table(Modifier.weight(1.8f).fillMaxHeight())
                    RequestDetail(selectedCall, language, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun SummaryCards(state: NetworkProfilerState, language: UiLanguage) {
    val colors = LocalViewerColors.current
    val summary = state.summary
    val completeness = state.result?.session?.completeness
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cardsWidth = maxWidth.coerceAtLeast(920.dp)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.width(cardsWidth).height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
            ) {
                StudioMetricCard(
                    label = localizedStringResource(Res.string.calls, language),
                    value = summary?.callCount?.toString() ?: "—",
                    modifier = Modifier.weight(0.72f).fillMaxHeight(),
                    tone = colors.info,
                )
                StudioMetricCard(
                    label = localizedStringResource(Res.string.failures, language),
                    value = summary?.failureCount?.toString() ?: "—",
                    modifier = Modifier.weight(0.72f).fillMaxHeight(),
                    supportingText = if ((summary?.failureCount ?: 0) > 0) localizedStringResource(Res.string.failure_attention, language) else null,
                    tone = if ((summary?.failureCount ?: 0) > 0) colors.warning else colors.info,
                )
                StudioMetricCard(
                    label = localizedStringResource(Res.string.transferred_bytes, language),
                    value = summary?.transferredBytes?.let(::formatBytes) ?: "—",
                    supportingText =
                        summary?.let {
                            localizedStringResource(
                                Res.string.transferred_support,
                                language,
                                formatBytes(it.totalRequestBytes),
                                formatBytes(it.totalResponseBytes),
                            )
                        },
                    modifier = Modifier.weight(1.05f).fillMaxHeight(),
                    tone = colors.accent,
                )
                StudioMetricCard(
                    label = localizedStringResource(Res.string.median_slow_request, language),
                    value = summary?.let { "${formatDurationMs(it.medianDurationMs)} / ${formatDurationMs(it.p95DurationMs)}" } ?: "—",
                    supportingText = localizedStringResource(Res.string.median_p95_support, language),
                    modifier = Modifier.weight(1.18f).fillMaxHeight(),
                    tone = colors.warning,
                )
                StudioMetricCard(
                    label = localizedStringResource(Res.string.evidence_completeness, language),
                    value = completeness?.status?.displayName(language) ?: "—",
                    supportingText =
                        completeness?.let {
                            localizedStringResource(
                                Res.string.coverage_counts,
                                language,
                                it.droppedEvents,
                                it.sequenceGaps,
                                summary?.missingTimingCount ?: 0,
                            )
                        },
                    modifier = Modifier.weight(1.2f).fillMaxHeight(),
                    tone =
                        when (completeness?.status) {
                            EvidenceCompleteness.COMPLETE -> colors.success
                            null -> colors.info
                            else -> colors.warning
                        },
                )
            }
        }
    }
}

@Composable
private fun EvidenceCoverage(state: NetworkProfilerState, language: UiLanguage) {
    val result = state.result ?: return
    val coverage = result.session.coverage
    val completeness = result.session.completeness
    StudioPanel(Modifier.fillMaxWidth().testTag("network-evidence-coverage")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StudioEvidenceBadge(completeness.status.toStudioEvidence(), language)
            Text(
                localizedStringResource(
                    Res.string.coverage_summary,
                    language,
                    coverage.instrumentationMode.displayName(language),
                    coverage.observedLibraries.joinToString().ifEmpty { "—" },
                    result.calls.map { it.source.displayName(language) }.distinct().joinToString().ifEmpty { "—" },
                ),
                modifier = Modifier.weight(1f),
                color = LocalViewerColors.current.primaryText,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            state.artifact?.let { artifact ->
                Text(
                    "${artifact.availableCapabilities.size} capabilities · ${artifact.limitations.size} limitations",
                    color = LocalViewerColors.current.secondaryText,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (coverage.instrumentationMode == InstrumentationMode.HAR_IMPORT ||
            result.calls.any { it.source == NetworkEvidenceSource.HAR_IMPORT }
        ) {
            Text(
                localizedStringResource(Res.string.har_timing_notice, language),
                color = LocalViewerColors.current.warning,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
        if (coverage.knownLimitations.isNotEmpty()) {
            Text(
                localizedStringResource(
                    Res.string.not_covered,
                    language,
                    coverage.knownLimitations.joinToString(),
                ),
                color = LocalViewerColors.current.secondaryText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RequestTable(
    calls: List<HttpCall>,
    selectedCallId: String?,
    language: UiLanguage,
    onSelectCall: (String) -> Unit,
    modifier: Modifier,
) {
    StudioDataTable(
        columns = requestTableColumns(language),
        title = localizedStringResource(Res.string.request_list, language),
        modifier = modifier.testTag("network-request-table"),
    ) {
        if (calls.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    localizedStringResource(Res.string.no_requests, language),
                    color = LocalViewerColors.current.secondaryText,
                )
            }
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(calls, key = { it.callId }) { call ->
                    RequestRow(
                        call = call,
                        selected = call.callId == selectedCallId,
                        language = language,
                        onClick = { onSelectCall(call.callId) },
                    )
                    HorizontalDivider(color = LocalViewerColors.current.border)
                }
            }
        }
    }
}

@Composable
private fun RequestRow(
    call: HttpCall,
    selected: Boolean,
    language: UiLanguage,
    onClick: () -> Unit,
) {
    val row = call.toPresentationRow()
    val colors = LocalViewerColors.current
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (selected) colors.selectedRow else colors.panel)
                .clickable(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 7.dp)
                .testTag("network-request-row-${call.callId}")
                .semantics {
                    contentDescription =
                        listOf(
                            row.method,
                            row.redactedUrl,
                            row.statusCode?.toString() ?: "—",
                            formatDurationNs(row.durationNs),
                            formatBytes(row.transferredBytes),
                            row.protocol ?: "—",
                            row.outcome.displayName(language),
                            row.source.displayName(language),
                        ).joinToString(" · ")
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TableCell(row.method, REQUEST_COLUMN_WEIGHTS[0], emphasized = true)
        TableCell(row.redactedUrl, REQUEST_COLUMN_WEIGHTS[1])
        TableCell(row.statusCode?.toString() ?: "—", REQUEST_COLUMN_WEIGHTS[2], statusCode = row.statusCode)
        TableCell(formatDurationNs(row.durationNs), REQUEST_COLUMN_WEIGHTS[3])
        TableCell(formatBytes(row.transferredBytes), REQUEST_COLUMN_WEIGHTS[4])
        TableCell(row.protocol ?: "—", REQUEST_COLUMN_WEIGHTS[5])
        TableCell(
            row.outcome.displayName(language),
            REQUEST_COLUMN_WEIGHTS[6],
            textColor = if (row.outcome == CallOutcome.FAILED) colors.error else null,
        )
        TableCell(row.source.displayName(language), REQUEST_COLUMN_WEIGHTS[7])
    }
}

@Composable
private fun RowScope.TableCell(
    value: String,
    weight: Float,
    emphasized: Boolean = false,
    statusCode: Int? = null,
    textColor: Color? = null,
) {
    val colors = LocalViewerColors.current
    Text(
        value,
        modifier = Modifier.weight(weight).padding(end = 6.dp),
        color =
            textColor ?: when {
                statusCode != null && statusCode >= 400 -> colors.error
                statusCode != null && statusCode in 200..399 -> colors.success
                else -> colors.rowText
            },
        fontSize = ViewerTypography.secondary.fontSize,
        lineHeight = ViewerTypography.secondary.lineHeight,
        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun RequestDetail(
    call: HttpCall?,
    language: UiLanguage,
    modifier: Modifier,
) {
    val detailDescription = call?.toPresentationRow()?.let { row ->
        "${row.method} · ${row.redactedUrl} · ${row.statusCode ?: "—"}"
    } ?: localizedStringResource(Res.string.select_a_call_to_inspect_phase_evidence, language)
    StudioPanel(
        modifier.testTag("network-request-detail").semantics { contentDescription = detailDescription },
    ) {
        StudioPanelHeader(
            title = localizedStringResource(Res.string.request_details, language),
            subtitle = call?.redactedUrl,
        )
        if (call == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    localizedStringResource(Res.string.select_a_call_to_inspect_phase_evidence, language),
                    color = LocalViewerColors.current.secondaryText,
                )
            }
        } else {
            val row = call.toPresentationRow()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(call.method, fontWeight = FontWeight.SemiBold, color = LocalViewerColors.current.primaryText)
                StudioStatusChip(row.outcome.displayName(language), row.outcome.toTone())
                Spacer(Modifier.weight(1f))
                Text(
                    localizedStringResource(
                        Res.string.response_request_bytes,
                        language,
                        formatBytes(call.totalResponseBytes),
                        formatBytes(call.totalRequestBytes),
                    ),
                    color = LocalViewerColors.current.secondaryText,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                localizedStringResource(Res.string.timing_confidence_legend, language),
                color = LocalViewerColors.current.secondaryText,
                style = MaterialTheme.typography.labelSmall,
            )
            HorizontalDivider(color = LocalViewerColors.current.border)
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap),
            ) {
                itemsIndexed(call.exchanges) { index, exchange ->
                    ExchangeDetail(exchange, index, language)
                }
            }
        }
    }
}

@Composable
private fun ExchangeDetail(exchange: HttpExchange, index: Int, language: UiLanguage) {
    StudioCard(
        modifier = Modifier.fillMaxWidth(),
        containerColor = LocalViewerColors.current.sectionBackground,
        contentColor = contentColorFor(LocalViewerColors.current.sectionBackground),
        contentPadding = 0.dp,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    localizedStringResource(Res.string.detail_exchange, language, index + 1),
                    modifier = Modifier.weight(1f),
                    color = LocalViewerColors.current.primaryText,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(exchange.statusCode?.toString() ?: "—", style = MaterialTheme.typography.labelSmall)
                Text(exchange.protocol ?: "—", style = MaterialTheme.typography.labelSmall)
                Text(exchange.connectionUse.displayName(language), style = MaterialTheme.typography.labelSmall)
            }
            exchange.tlsHandshake?.let { handshake ->
                Text(
                    localizedStringResource(
                        Res.string.tls_handshake,
                        language,
                        handshake.tlsVersion ?: "—",
                        handshake.cipherSuite ?: "—",
                    ),
                    color = LocalViewerColors.current.secondaryText,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Text(
                localizedStringResource(Res.string.phases, language),
                color = LocalViewerColors.current.secondaryText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            DETAIL_PHASES.forEach { kind ->
                val phase = exchange.phases.firstOrNull { it.kind == kind }
                PhaseRow(kind, phase, language)
            }
            HeaderEvidence(
                title = localizedStringResource(Res.string.request_headers_evidence, language),
                headers = exchange.requestHeaders,
                emptyLabel = localizedStringResource(Res.string.headers_not_captured, language),
                testTag = "network-request-headers",
            )
            BodyEvidence(
                title = localizedStringResource(Res.string.request_body_evidence, language),
                language = language,
                testTag = "network-request-body",
            )
            HeaderEvidence(
                title = localizedStringResource(Res.string.response_headers_evidence, language),
                headers = exchange.responseHeaders,
                emptyLabel = localizedStringResource(Res.string.headers_not_captured, language),
                testTag = "network-response-headers",
            )
            BodyEvidence(
                title = localizedStringResource(Res.string.response_body_evidence, language),
                language = language,
                testTag = "network-response-body",
            )
            exchange.failure?.let { failure ->
                Text(
                    listOfNotNull(failure.type, failure.message).joinToString(": "),
                    color = LocalViewerColors.current.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun HeaderEvidence(
    title: String,
    headers: Map<String, String>,
    emptyLabel: String,
    testTag: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(LocalViewerColors.current.detailRowDeep, RoundedCornerShape(4.dp))
                .padding(7.dp)
                .testTag(testTag),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            color = LocalViewerColors.current.secondaryText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (headers.isEmpty()) {
            Text(emptyLabel, color = LocalViewerColors.current.mutedText, style = MaterialTheme.typography.labelSmall)
        } else {
            headers.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, redactedValue) ->
                Text(
                    "$name: $redactedValue",
                    color = LocalViewerColors.current.rowText,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BodyEvidence(title: String, language: UiLanguage, testTag: String) {
    val unavailable = localizedStringResource(Res.string.body_not_captured, language)
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(LocalViewerColors.current.detailRowDeep, RoundedCornerShape(4.dp))
                .padding(7.dp)
                .testTag(testTag)
                .semantics { contentDescription = "$title · $unavailable" },
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            title,
            color = LocalViewerColors.current.secondaryText,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            unavailable,
            color = LocalViewerColors.current.mutedText,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun PhaseRow(kind: NetworkPhaseKind, phase: NetworkPhase?, language: UiLanguage) {
    val confidence = phase?.confidence
    val estimated = confidence == NetworkConfidence.INFERRED || confidence == NetworkConfidence.APPROXIMATED
    val colors = LocalViewerColors.current
    val phaseDescription =
        if (phase == null) {
            localizedStringResource(Res.string.not_observed, language)
        } else {
            "${formatDurationNs(phase.durationNs)} · ${phase.confidence.displayName(language)}"
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (estimated) colors.warning.copy(alpha = 0.10f) else colors.detailRowDeep)
                .estimatedEvidenceBorder(estimated, colors.warning)
                .padding(horizontal = 7.dp, vertical = 5.dp)
                .testTag("network-phase-${kind.name.lowercase()}")
                .semantics {
                    contentDescription = "${kind.displayName(language)} · $phaseDescription"
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            kind.displayName(language),
            modifier = Modifier.weight(1f),
            color = colors.rowText,
            fontSize = ViewerTypography.secondary.fontSize,
            lineHeight = ViewerTypography.secondary.lineHeight,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            phase?.durationNs?.let(::formatDurationNs)
                ?: localizedStringResource(
                    if (phase == null) Res.string.not_observed else Res.string.unavailable,
                    language,
                ),
            modifier = Modifier.width(74.dp),
            color = if (phase == null) colors.mutedText else colors.primaryText,
            fontSize = ViewerTypography.label.fontSize,
            lineHeight = ViewerTypography.label.lineHeight,
            maxLines = 1,
        )
        if (confidence == null) {
            StudioEvidenceBadge(StudioEvidence.UNAVAILABLE, language)
        } else {
            StudioEvidenceBadge(confidence.toStudioEvidence(), language)
        }
    }
}

@Composable
private fun MessageStrip(message: String, tone: StudioStatusTone) {
    StudioPanel(Modifier.fillMaxWidth()) {
        StudioStatusChip(message, tone)
    }
}

@Composable
private fun requestTableColumns(language: UiLanguage): List<StudioTableColumn> =
    listOf(
        Res.string.request_list_method,
        Res.string.request_list_url,
        Res.string.request_list_status,
        Res.string.request_list_duration,
        Res.string.request_list_bytes,
        Res.string.request_list_protocol,
        Res.string.request_list_outcome,
        Res.string.request_list_source,
    ).mapIndexed { index, resource ->
        StudioTableColumn(localizedStringResource(resource, language), REQUEST_COLUMN_WEIGHTS[index])
    }

private fun Modifier.estimatedEvidenceBorder(enabled: Boolean, color: Color): Modifier =
    if (!enabled) {
        this
    } else {
        drawWithContent {
            drawContent()
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(4.dp.toPx()),
                style =
                    Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 4.dp.toPx())),
                    ),
            )
        }
    }

private fun InstrumentationMode.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            InstrumentationMode.EXPLICIT_FACTORY -> Res.string.instrumentation_explicit_factory
            InstrumentationMode.INSTRUMENTED_PARTIAL -> Res.string.instrumentation_partial
            InstrumentationMode.HAR_IMPORT -> Res.string.instrumentation_har_import
            InstrumentationMode.RAW_IMPORT -> Res.string.instrumentation_raw_import
        },
        language,
    )

private fun NetworkConfidence.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            NetworkConfidence.EXACT -> Res.string.confidence_exact
            NetworkConfidence.DERIVED -> Res.string.confidence_derived
            NetworkConfidence.INFERRED -> Res.string.confidence_inferred
            NetworkConfidence.APPROXIMATED -> Res.string.confidence_approximated
            NetworkConfidence.PARTIAL -> Res.string.confidence_partial
            NetworkConfidence.UNKNOWN -> Res.string.confidence_unknown
        },
        language,
    )

private fun NetworkConfidence.toStudioEvidence(): StudioEvidence =
    when (this) {
        NetworkConfidence.EXACT -> StudioEvidence.EXACT
        NetworkConfidence.DERIVED -> StudioEvidence.DERIVED
        NetworkConfidence.INFERRED -> StudioEvidence.INFERRED
        NetworkConfidence.APPROXIMATED -> StudioEvidence.ESTIMATED
        NetworkConfidence.PARTIAL -> StudioEvidence.PARTIAL
        NetworkConfidence.UNKNOWN -> StudioEvidence.UNKNOWN
    }

internal fun CallOutcome.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            CallOutcome.COMPLETED -> Res.string.outcome_completed
            CallOutcome.FAILED -> Res.string.outcome_failed
            CallOutcome.CANCELLED -> Res.string.outcome_cancelled
            CallOutcome.INCOMPLETE -> Res.string.outcome_incomplete
        },
        language,
    )

private fun CallOutcome.toTone(): StudioStatusTone =
    when (this) {
        CallOutcome.COMPLETED -> StudioStatusTone.SUCCESS
        CallOutcome.FAILED -> StudioStatusTone.ERROR
        CallOutcome.CANCELLED -> StudioStatusTone.WARNING
        CallOutcome.INCOMPLETE -> StudioStatusTone.NEUTRAL
    }

private fun EvidenceCompleteness.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            EvidenceCompleteness.COMPLETE -> Res.string.completeness_complete
            EvidenceCompleteness.PARTIAL -> Res.string.confidence_partial
            EvidenceCompleteness.UNKNOWN -> Res.string.confidence_unknown
        },
        language,
    )

private fun EvidenceCompleteness.toStudioEvidence(): StudioEvidence =
    when (this) {
        EvidenceCompleteness.COMPLETE -> StudioEvidence.COMPLETE
        EvidenceCompleteness.PARTIAL -> StudioEvidence.PARTIAL
        EvidenceCompleteness.UNKNOWN -> StudioEvidence.UNKNOWN
    }

private fun ConnectionUse.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            ConnectionUse.NEW -> Res.string.connection_new
            ConnectionUse.REUSED -> Res.string.connection_reused
            ConnectionUse.UNKNOWN -> Res.string.connection_unknown
        },
        language,
    )

private fun NetworkEvidenceSource.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            NetworkEvidenceSource.OKHTTP_EVENT_LISTENER -> Res.string.source_okhttp
            NetworkEvidenceSource.HAR_IMPORT -> Res.string.source_har
            NetworkEvidenceSource.RAW_EVENT_BUNDLE -> Res.string.source_raw
        },
        language,
    )

private fun NetworkPhaseKind.displayName(language: UiLanguage): String =
    localizedStringResource(
        when (this) {
            NetworkPhaseKind.DISPATCHER_QUEUE -> Res.string.phase_dispatcher_queue
            NetworkPhaseKind.PROXY_SELECT -> Res.string.phase_proxy_select
            NetworkPhaseKind.DNS -> Res.string.phase_dns
            NetworkPhaseKind.CONNECT -> Res.string.phase_connect
            NetworkPhaseKind.TLS -> Res.string.phase_tls
            NetworkPhaseKind.REQUEST_HEADERS -> Res.string.phase_request_headers
            NetworkPhaseKind.REQUEST_BODY -> Res.string.phase_request_body
            NetworkPhaseKind.SERVER_WAIT -> Res.string.phase_server_wait
            NetworkPhaseKind.RESPONSE_HEADERS -> Res.string.phase_response_headers
            NetworkPhaseKind.RESPONSE_BODY -> Res.string.phase_response_body
            NetworkPhaseKind.CONNECTION_HELD -> Res.string.phase_connection_held
            NetworkPhaseKind.TOTAL -> Res.string.phase_total
        },
        language,
    )

private val REQUEST_COLUMN_WEIGHTS = listOf(0.65f, 2.35f, 0.58f, 0.78f, 0.72f, 0.68f, 0.82f, 0.95f)

private val DETAIL_PHASES =
    listOf(
        NetworkPhaseKind.DISPATCHER_QUEUE,
        NetworkPhaseKind.DNS,
        NetworkPhaseKind.CONNECT,
        NetworkPhaseKind.TLS,
        NetworkPhaseKind.REQUEST_HEADERS,
        NetworkPhaseKind.REQUEST_BODY,
        NetworkPhaseKind.SERVER_WAIT,
        NetworkPhaseKind.RESPONSE_HEADERS,
        NetworkPhaseKind.RESPONSE_BODY,
        NetworkPhaseKind.CONNECTION_HELD,
    )
