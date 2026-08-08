package com.androidperformancestudio.desktop

import com.androidperformancestudio.compose.inspection.ComposableNode
import com.androidperformancestudio.compose.inspection.ComposableRoot
import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeInspectionMode
import com.androidperformancestudio.compose.inspection.RecompositionObservation
import com.androidperformancestudio.protocol.Bounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecompositionHeatTest {
    @Test
    fun `heat uses frame deltas and clears when observation stops`() {
        val tracker = RecompositionHeatTracker()

        val first = tracker.sample(document(capturedAt = 2_000, count = 2, active = true))
        val second = tracker.sample(document(capturedAt = 2_500, count = 5, active = true))

        assertEquals(0.2f, first.getValue("compose-inspection:7"))
        assertEquals(0.6f, second.getValue("compose-inspection:7"))
        assertTrue(tracker.sample(document(capturedAt = 3_000, count = 5, active = false)).isEmpty())
    }

    private fun document(capturedAt: Long, count: Int, active: Boolean) = ComposeInspectionDocument(
        packageName = "example",
        capturedAtEpochMillis = capturedAt,
        frame = ComposeInspectionFrame(
            frameId = capturedAt.toString(),
            generation = 1,
            mode = ComposeInspectionMode.FULL,
            capabilities = emptyList(),
            roots = listOf(
                ComposableRoot(
                    viewId = 1,
                    nodes = listOf(
                        ComposableNode(
                            id = 7,
                            anchorHash = 9,
                            name = "Greeting",
                            bounds = Bounds(0, 0, 10, 10),
                            recomposeCount = count,
                        ),
                    ),
                ),
            ),
            recompositionObservation = RecompositionObservation(
                startedAtEpochMillis = 1_000,
                stoppedAtEpochMillis = if (active) null else capturedAt,
                active = active,
                continuous = true,
            ),
        ),
    )
}
