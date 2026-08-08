package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame

internal interface ComposeFrameCaptureClient {
    fun captureViews(packageName: String, includeAttributes: Boolean): ViewInspectionCapture

    fun captureTree(rootViewIds: List<Long>): ComposeInspectionFrame
}

internal data class StableComposeCapture(
    val views: ViewInspectionCapture,
    val compose: ComposeInspectionFrame,
)

/**
 * Obtains a frame using a bounded double-collect protocol.
 *
 * View Inspector and Compose Inspector do not expose a shared transaction or frame token. A
 * single View -> Compose sequence can therefore merge two different UI states. Collecting
 * View-A/Compose-A/View-B/Compose-B/View-C and requiring both structural reads to agree proves
 * that the returned middle view and second Compose tree were surrounded by the same stable state.
 */
internal class StableComposeFrameCapture(
    private val client: ComposeFrameCaptureClient,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) {
    init {
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }

    fun capture(packageName: String): StableComposeCapture {
        repeat(maxAttempts) {
            val viewA = client.captureViews(packageName, includeAttributes = false)
            val composeA = client.captureTree(viewA.rootViewIds)
            val viewB = client.captureViews(packageName, includeAttributes = false)
            val composeB = client.captureTree(viewB.rootViewIds)
            val viewC = client.captureViews(packageName, includeAttributes = false)

            val stableViews = viewA.structuralSnapshot() == viewB.structuralSnapshot() &&
                viewB.structuralSnapshot() == viewC.structuralSnapshot()
            val stableCompose = composeA.structuralRoots() == composeB.structuralRoots()
            val rootsMatch = composeA.rootViewIds() == viewA.rootViewIds &&
                composeB.rootViewIds() == viewB.rootViewIds
            if (stableViews && stableCompose && rootsMatch) {
                return StableComposeCapture(viewB, composeB)
            }
        }
        error("Target changed during Compose frame capture; try again")
    }

    private fun ViewInspectionCapture.structuralSnapshot() = snapshot.copy(capturedAtEpochMillis = 0)

    private fun ComposeInspectionFrame.structuralRoots() = roots.map { root ->
        root.copy(nodes = root.nodes.map { it.withoutObservationCounts() })
    }

    private fun ComposeInspectionFrame.rootViewIds(): List<Long> = roots.map { it.viewId }

    private fun ComposableNode.withoutObservationCounts(): ComposableNode = copy(
        recomposeCount = null,
        skipCount = null,
        children = children.map { it.withoutObservationCounts() },
    )

    private companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
