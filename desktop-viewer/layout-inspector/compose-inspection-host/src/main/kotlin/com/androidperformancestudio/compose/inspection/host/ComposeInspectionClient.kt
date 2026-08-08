package com.androidperformancestudio.compose.inspection.host

import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
import com.androidperformancestudio.compose.inspection.ComposableDetail
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeParameterReference
import com.androidperformancestudio.compose.inspection.ComposeValue
import com.androidperformancestudio.compose.inspection.RecompositionObservation
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol
import java.time.Clock
import java.util.UUID

internal class ComposeInspectionClient(
    private val protocol: AospInspectorProtocolClient,
    private val adapter: ComposeProtocolAdapter = ComposeProtocolAdapter(),
    private val viewAdapter: ViewProtocolAdapter = ViewProtocolAdapter(),
    private val clock: Clock = Clock.systemUTC(),
) : ComposeFrameCaptureClient {
    private var generation = 0
    private var recompositionObservation: RecompositionObservation? = null

    override fun captureViews(packageName: String, includeAttributes: Boolean): ViewInspectionCapture {
        val command = ViewInspectorProtocol.Command.newBuilder()
            .setDumpViewsCommand(
                ViewInspectorProtocol.DumpViewsCommand.newBuilder()
                    .setIncludeAttributes(includeAttributes)
                    .setIncludeResolutionStack(false),
            )
            .build()
        val response = ViewInspectorProtocol.Response.parseFrom(
            protocol.sendInspectorCommand(AospInspectorProtocolClient.VIEW_INSPECTOR_ID, command.toByteArray()),
        )
        check(response.hasDumpViewsResponse()) { "Unexpected View inspector response" }
        return viewAdapter.convert(packageName, clock.millis(), response.dumpViewsResponse)
    }

    override fun captureTree(rootViewIds: List<Long>): ComposeInspectionFrame {
        val frames = rootViewIds.map { rootViewId ->
            val command = LayoutInspectorComposeProtocol.Command.newBuilder()
                .setGetComposablesCommand(
                    LayoutInspectorComposeProtocol.GetComposablesCommand.newBuilder()
                        .setRootViewId(rootViewId)
                        .setSkipSystemComposables(false)
                        .setGeneration(generation)
                        .setExtractAllParameters(false),
                )
                .build()
            val response = composeResponse(command)
            check(response.hasGetComposablesResponse()) { "Unexpected Compose tree response" }
            adapter.convert(
                frameId = UUID.randomUUID().toString(),
                generation = generation,
                tree = response.getComposablesResponse,
                recompositionObservation = recompositionObservation,
            )
        }
        generation += 1
        val first = frames.firstOrNull() ?: return adapter.convert(
            frameId = UUID.randomUUID().toString(),
            generation = generation,
            tree = LayoutInspectorComposeProtocol.GetComposablesResponse.getDefaultInstance(),
            recompositionObservation = recompositionObservation,
        )
        return first.copy(
            roots = frames.flatMap(ComposeInspectionFrame::roots),
            coverage = frames.flatMap(ComposeInspectionFrame::coverage),
            truncations = frames.flatMap(ComposeInspectionFrame::truncations),
            completeness = frames.maxOf(ComposeInspectionFrame::completeness),
        )
    }

    fun loadDetail(
        rootViewId: Long,
        composableId: Long,
        anchorHash: Int,
        maxRecursions: Int = 2,
        maxInitialIterableSize: Int = 5,
    ): ComposableDetail {
        require(maxRecursions in 0..10 && maxInitialIterableSize in 1..10_000)
        val command = LayoutInspectorComposeProtocol.Command.newBuilder()
            .setGetParametersCommand(
                LayoutInspectorComposeProtocol.GetParametersCommand.newBuilder()
                    .setRootViewId(rootViewId)
                    .setComposableId(composableId)
                    .setAnchorHash(anchorHash)
                    .setGeneration(generation - 1)
                    .setSkipSystemComposables(false)
                    .setMaxRecursions(maxRecursions)
                    .setMaxInitialIterableSize(maxInitialIterableSize),
            )
            .build()
        val response = composeResponse(command)
        check(response.hasGetParametersResponse()) { "Unexpected Compose parameters response" }
        return adapter.convertDetail(response.getParametersResponse)
    }

    fun loadParameterDetails(
        rootViewId: Long,
        reference: ComposeParameterReference,
        startIndex: Int,
        maxElements: Int,
        maxRecursions: Int = 2,
    ): ComposeValue {
        require(startIndex >= 0 && maxElements in 1..10_000 && maxRecursions in 0..10)
        val protoReference = LayoutInspectorComposeProtocol.ParameterReference.newBuilder()
            .setComposableId(reference.composableId)
            .setParameterIndex(reference.parameterIndex)
            .addAllCompositeIndex(reference.compositeIndex)
            .setKind(LayoutInspectorComposeProtocol.ParameterReference.Kind.valueOf(reference.kind))
            .setAnchorHash(reference.anchorHash)
        val command = LayoutInspectorComposeProtocol.Command.newBuilder()
            .setGetParameterDetailsCommand(
                LayoutInspectorComposeProtocol.GetParameterDetailsCommand.newBuilder()
                    .setRootViewId(rootViewId)
                    .setReference(protoReference)
                    .setStartIndex(startIndex)
                    .setMaxElements(maxElements)
                    .setMaxRecursions(maxRecursions)
                    .setMaxInitialIterableSize(maxElements.coerceAtMost(10_000))
                    .setGeneration(generation - 1)
                    .setSkipSystemComposables(false),
            )
            .build()
        val response = composeResponse(command)
        check(response.hasGetParameterDetailsResponse()) { "Unexpected Compose parameter-detail response" }
        return adapter.convertParameterDetails(response.getParameterDetailsResponse)
    }

    fun startRecompositionObservation() = updateRecomposition(includeCounts = true, keepCounts = false)

    fun stopRecompositionObservation() = updateRecomposition(includeCounts = false, keepCounts = true)

    fun resetRecompositionCounts() = updateRecomposition(includeCounts = true, keepCounts = false)

    private fun updateRecomposition(includeCounts: Boolean, keepCounts: Boolean) {
        val command = LayoutInspectorComposeProtocol.Command.newBuilder()
            .setUpdateSettingsCommand(
                LayoutInspectorComposeProtocol.UpdateSettingsCommand.newBuilder()
                    .setIncludeRecomposeCounts(includeCounts)
                    .setKeepRecomposeCounts(keepCounts)
                    .setDelayParameterExtractions(true)
                    .setReduceChildNesting(true),
            )
            .build()
        val response = composeResponse(command)
        check(response.hasUpdateSettingsResponse()) { "Unexpected Compose settings response" }
        val now = clock.millis()
        recompositionObservation = when {
            includeCounts -> RecompositionObservation(now, active = true, continuous = true)
            recompositionObservation != null -> recompositionObservation!!.copy(stoppedAtEpochMillis = now, active = false)
            else -> null
        }
    }

    private fun composeResponse(command: LayoutInspectorComposeProtocol.Command): LayoutInspectorComposeProtocol.Response =
        LayoutInspectorComposeProtocol.Response.parseFrom(
            protocol.sendInspectorCommand(AospInspectorProtocolClient.COMPOSE_INSPECTOR_ID, command.toByteArray()),
        ).also { response ->
            check(!response.hasUnknownCommandResponse()) { "Compose inspector rejected command" }
        }
}
