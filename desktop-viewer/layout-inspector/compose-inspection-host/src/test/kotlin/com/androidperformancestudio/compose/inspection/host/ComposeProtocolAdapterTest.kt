package com.androidperformancestudio.compose.inspection.host

import com.androidperformancestudio.compose.inspection.CapabilityAvailability
import com.androidperformancestudio.compose.inspection.ComposeCapability
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeProtocolAdapterTest {
    @Test
    fun `adapter preserves raw system nodes source counts and details`() {
        val strings = listOf(
            string(1, "SystemWrapper"),
            string(2, "Content"),
            string(3, "Screen.kt"),
        )
        val content = node(2, 22, 2).toBuilder()
            .setFilename(3).setPackageHash(9).setLineNumber(42).setOffset(7)
            .setRecomposeCount(5).setRecomposeSkips(3).build()
        val system = node(1, 11, 1).toBuilder().setFlags(1).addChildren(content).build()
        val tree = LayoutInspectorComposeProtocol.GetComposablesResponse.newBuilder()
            .addAllStrings(strings)
            .addRoots(LayoutInspectorComposeProtocol.ComposableRoot.newBuilder().setViewId(100).addNodes(system))
            .build()
        val parameterStrings = listOf(string(10, "title"), string(11, "Hello"), string(12, "modifier"))
        val group = LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(2)
            .addParameter(stringParameter(10, 11))
            .addParameter(stringParameter(12, 11))
            .addMergedSemantics(stringParameter(10, 11))
            .build()
        val parameters = LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
            .addAllStrings(parameterStrings).addParameterGroups(group).build()

        val frame = ComposeProtocolAdapter().convert("frame", 4, tree, parameters)

        val systemNode = frame.roots.single().nodes.single()
        val appNode = systemNode.children.single()
        assertTrue(systemNode.systemCreated)
        assertEquals("Screen.kt", appNode.source?.fileName)
        assertEquals(5, appNode.recomposeCount)
        assertEquals("Hello", frame.details.getValue(2).parameters.single().value)
        assertEquals("modifier", frame.details.getValue(2).modifiers.single().name)
        assertEquals(
            CapabilityAvailability.AVAILABLE,
            frame.capabilities.single { it.capability == ComposeCapability.PARAMETERS }.availability,
        )
    }

    @Test
    fun `details are explicitly not requested when absent`() {
        val tree = LayoutInspectorComposeProtocol.GetComposablesResponse.newBuilder()
            .addStrings(string(1, "Content"))
            .addRoots(LayoutInspectorComposeProtocol.ComposableRoot.newBuilder().addNodes(node(1, 1, 1)))
            .build()
        val frame = ComposeProtocolAdapter().convert("frame", 1, tree)
        assertEquals(4, frame.coverage.count { it.nodeId == 1L })
        assertTrue(frame.details.isEmpty())
    }

    private fun node(id: Long, anchor: Int, name: Int) =
        LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
            .setId(id).setAnchorHash(anchor).setName(name)
            .setBounds(
                LayoutInspectorComposeProtocol.Bounds.newBuilder().setLayout(
                    LayoutInspectorComposeProtocol.Rect.newBuilder().setX(1).setY(2).setW(3).setH(4),
                ),
            ).build()

    private fun string(id: Int, value: String) =
        LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(id).setStr(value).build()

    private fun stringParameter(name: Int, value: Int) =
        LayoutInspectorComposeProtocol.Parameter.newBuilder()
            .setName(name).setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING).setInt32Value(value).build()
}
