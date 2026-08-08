package com.androidperformancestudio.desktop

import com.androidperformancestudio.application.InspectorState
import com.androidperformancestudio.compose.inspection.CapabilityAvailability
import com.androidperformancestudio.compose.inspection.ComposableDetail
import com.androidperformancestudio.compose.inspection.ComposableNode as InspectedNode
import com.androidperformancestudio.compose.inspection.ComposableRoot
import com.androidperformancestudio.compose.inspection.ComposeCapability
import com.androidperformancestudio.compose.inspection.ComposeCapabilityState
import com.androidperformancestudio.compose.inspection.ComposeInspectionDocument
import com.androidperformancestudio.compose.inspection.ComposeInspectionFrame
import com.androidperformancestudio.compose.inspection.ComposeInspectionMode
import com.androidperformancestudio.compose.inspection.ComposeSourceLocation
import com.androidperformancestudio.compose.inspection.ComposeValue
import com.androidperformancestudio.protocol.AgentCapabilities
import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.CURRENT_PROTOCOL_VERSION
import com.androidperformancestudio.protocol.ComposeNode
import com.androidperformancestudio.protocol.DisplayInfo
import com.androidperformancestudio.protocol.LayoutSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeInspectionPresenterTest {
    @Test
    fun `selected projected node shows source parameters and counts`() {
        val bounds = Bounds(0, 0, 10, 10)
        val snapshot = LayoutSnapshot(
            protocolVersion = CURRENT_PROTOCOL_VERSION,
            packageName = "sample",
            capturedAtEpochMillis = 10,
            display = DisplayInfo(10, 10, 1f),
            capabilities = AgentCapabilities(composeSemantics = true),
            root = ComposeNode("compose-inspection:7", "Content", bounds),
        )
        val inspection = ComposeInspectionDocument(
            packageName = "sample",
            capturedAtEpochMillis = 10,
            frame = ComposeInspectionFrame(
                frameId = "sample:10:1",
                generation = 1,
                mode = ComposeInspectionMode.FULL,
                capabilities = listOf(
                    ComposeCapabilityState(ComposeCapability.FULL_TREE, CapabilityAvailability.AVAILABLE),
                ),
                roots = listOf(
                    ComposableRoot(
                        1,
                        listOf(
                            InspectedNode(
                                id = 7,
                                anchorHash = 70,
                                name = "Content",
                                bounds = bounds,
                                source = ComposeSourceLocation(3, "Screen.kt", 42, 8),
                                recomposeCount = 4,
                                skipCount = 2,
                            ),
                        ),
                    ),
                ),
                details = mapOf(
                    7L to ComposableDetail(
                        nodeId = 7,
                        anchorHash = 70,
                        parameters = listOf(ComposeValue("title", "String", "Hello")),
                    ),
                ),
            ),
        )

        val model = InspectorPresenter.present(
            InspectorState(
                snapshot = snapshot,
                selectedNodeId = "compose-inspection:7",
                composeInspection = inspection,
            ),
        )

        val sections = model.details.sections.associateBy { it.title }
        assertEquals("Screen.kt", sections.getValue("COMPOSE SOURCE").rows.first { it.label == "File" }.value)
        assertTrue(sections.getValue("PARAMETERS").rows.single().value.contains("Hello"))
        assertEquals("4", sections.getValue("RECOMPOSITION").rows.first { it.label == "Recompose count" }.value)
    }
}
