package com.androidperformancestudio.compose.inspection

import com.androidperformancestudio.protocol.Bounds
import com.androidperformancestudio.protocol.AgentCapabilities
import com.androidperformancestudio.protocol.DisplayInfo
import com.androidperformancestudio.protocol.LayoutSnapshot
import com.androidperformancestudio.protocol.PROTOCOL_VERSION_1_1
import com.androidperformancestudio.protocol.ViewNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ComposeInspectionModelTest {
    @Test
    fun `json round trip preserves frame identity and details`() {
        val document = sampleDocument()
        assertEquals(document, ComposeInspectionJson().decode(ComposeInspectionJson().encode(document)))
    }

    @Test
    fun `unknown schema is rejected without changing layout protocol`() {
        assertThrows(UnsupportedComposeInspectionSchemaException::class.java) {
            ComposeInspectionJson().decode("""{"schemaVersion":2}""")
        }
    }

    @Test
    fun `projection removes system node but keeps application descendants`() {
        val nodes = ComposeInspectionProjection.project(sampleDocument().frame)
        assertEquals(listOf("AppContent"), nodes.map { it.className })
        assertEquals("compose-inspection:2", nodes.single().id)
    }

    @Test
    fun `safe redaction preserves shape and removes runtime values`() {
        val redacted = sampleDocument().copy(privacy = ComposeArchivePrivacy.FULL_FIDELITY).redacted()
        val parameter = redacted.frame.details.getValue(2).parameters.single()
        assertEquals("String", parameter.type)
        assertEquals("<redacted>", parameter.value)
        assertEquals(ComposeArchivePrivacy.SAFE_REDACTED, redacted.privacy)
    }

    @Test
    fun `hybrid projection moves hosted Android view under its composable`() {
        val hosted = ViewNode("view:99", "android.widget.TextView", Bounds(0, 0, 10, 10))
        val root = ViewNode("view:42", "androidx.compose.ui.platform.AndroidComposeView", Bounds(0, 0, 100, 100), children = listOf(hosted))
        val snapshot = LayoutSnapshot(
            protocolVersion = PROTOCOL_VERSION_1_1,
            packageName = "sample",
            capturedAtEpochMillis = 10,
            display = DisplayInfo(100, 100, 1f),
            capabilities = AgentCapabilities(viewHierarchy = true),
            root = root,
        )
        val frame = sampleDocument().frame.copy(
            roots = listOf(
                ComposableRoot(
                    viewId = 42,
                    viewsToSkip = listOf(99),
                    nodes = listOf(
                        ComposableNode(2, 2, "AndroidView", Bounds(0, 0, 100, 100), hostedViewId = 99),
                    ),
                ),
            ),
        )

        val mergedRoot = ComposeInspectionProjection.mergeInto(snapshot, frame).root
        val composable = mergedRoot.children.single()
        assertEquals("compose-inspection:2", composable.id)
        assertEquals("view:99", composable.children.single().id)
    }

    private fun sampleDocument() = ComposeInspectionDocument(
        packageName = "sample",
        capturedAtEpochMillis = 10,
        frame = ComposeInspectionFrame(
            frameId = "sample:10:7",
            generation = 7,
            mode = ComposeInspectionMode.FULL,
            capabilities = listOf(
                ComposeCapabilityState(ComposeCapability.FULL_TREE, CapabilityAvailability.AVAILABLE),
            ),
            roots = listOf(
                ComposableRoot(
                    viewId = 42,
                    nodes = listOf(
                        ComposableNode(
                            id = 1,
                            anchorHash = 1,
                            name = "SystemWrapper",
                            bounds = Bounds(0, 0, 100, 100),
                            systemCreated = true,
                            children = listOf(
                                ComposableNode(
                                    id = 2,
                                    anchorHash = 2,
                                    name = "AppContent",
                                    bounds = Bounds(0, 0, 100, 100),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            details = mapOf(
                2L to ComposableDetail(
                    nodeId = 2,
                    anchorHash = 2,
                    parameters = listOf(ComposeValue("title", "String", "secret")),
                ),
            ),
        ),
    )
}
