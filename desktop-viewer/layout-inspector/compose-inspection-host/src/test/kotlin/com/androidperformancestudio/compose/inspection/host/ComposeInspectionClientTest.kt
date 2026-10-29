package com.androidperformancestudio.compose.inspection.host

import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol as ComposeProtocol
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ComposeInspectionClientTest {
    @Test
    fun `stability probe sends no parameter request and final read keeps generation aligned`() {
        val tree = ComposeProtocol.GetComposablesResponse.newBuilder()
            .addRoots(ComposeProtocol.ComposableRoot.newBuilder().setViewId(100)
                .addNodes(ComposeProtocol.ComposableNode.newBuilder().setId(10)))
            .build()
        val responses = listOf(
            ComposeProtocol.Response.newBuilder().setGetComposablesResponse(tree).build(),
            ComposeProtocol.Response.newBuilder().setGetComposablesResponse(tree).build(),
            ComposeProtocol.Response.newBuilder().setGetAllParametersResponse(
                ComposeProtocol.GetAllParametersResponse.newBuilder()
                    .addParameterGroups(ComposeProtocol.ParameterGroup.newBuilder().setComposableId(10)),
            ).build(),
        )
        val commands = withClient(responses) { client ->
            val probe = client.captureTree(listOf(100), includeDetails = false)
            val final = client.captureTree(listOf(100), includeDetails = true)
            assertTrue(probe.details.isEmpty())
            assertTrue(probe.coverage.isEmpty())
            assertTrue(final.details.containsKey(10L))
        }
        assertEquals(3, commands.size)
        assertFalse(commands[0].getComposablesCommand.extractAllParameters)
        assertTrue(commands[1].getComposablesCommand.extractAllParameters)
        assertTrue(commands[2].hasGetAllParametersCommand())
        assertEquals(0, commands[0].getComposablesCommand.generation)
        assertEquals(1, commands[1].getComposablesCommand.generation)
        assertEquals(1, commands[2].getAllParametersCommand.generation)
    }

    @Test
    fun `multi window capture retains every windows detail evidence`() {
        withClient(twoWindowResponses()) { client ->
            val frame = client.captureTree(listOf(100, 200), includeDetails = true)
            assertEquals(setOf(10L, 20L), frame.details.keys)
            assertEquals(listOf(100L, 200L), frame.roots.map { it.viewId })
            assertEquals(setOf(10L, 20L), frame.coverage.map { it.nodeId }.toSet())
        }
    }

    @Test
    fun `detail budget is shared across all window commands in a capture`() {
        withClient(twoWindowResponses(), ComposeProtocolAdapter(ComposeDetailLimits(maxGroups = 1))) { client ->
            val frame = client.captureTree(listOf(100, 200), includeDetails = true)
            assertEquals(setOf(10L), frame.details.keys)
            assertEquals(
                com.androidperformancestudio.compose.inspection.ComposeFrameCompleteness.INCOMPLETE_RESOURCE_LIMIT,
                frame.completeness,
            )
            assertTrue(frame.coverage.filter { it.nodeId == 20L }.all {
                it.state == com.androidperformancestudio.compose.inspection.ComposeDetailCoverageState.NOT_COLLECTED
            })
        }
    }

    private fun twoWindowResponses(): List<ComposeProtocol.Response> = listOf(100L to 10L, 200L to 20L).flatMap { (view, node) ->
        listOf(
            ComposeProtocol.Response.newBuilder().setGetComposablesResponse(
                ComposeProtocol.GetComposablesResponse.newBuilder()
                    .addRoots(ComposeProtocol.ComposableRoot.newBuilder().setViewId(view)
                        .addNodes(ComposeProtocol.ComposableNode.newBuilder().setId(node))),
            ).build(),
            ComposeProtocol.Response.newBuilder().setGetAllParametersResponse(
                ComposeProtocol.GetAllParametersResponse.newBuilder()
                    .addParameterGroups(ComposeProtocol.ParameterGroup.newBuilder().setComposableId(node)),
            ).build(),
        )
    }

    private fun withClient(
        responses: List<ComposeProtocol.Response>,
        adapter: ComposeProtocolAdapter = ComposeProtocolAdapter(),
        block: (ComposeInspectionClient) -> Unit,
    ): List<ComposeProtocol.Command> {
        val input = ByteArrayOutputStream().apply {
            responses.forEachIndexed { index, response ->
                InspectorFraming.write(this, UiInspectorProtocol.AgentMessage.newBuilder()
                    .setResponse(UiInspectorProtocol.Response.newBuilder()
                        .setCommandId(index + 1)
                        .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
                        .setInspectorMessage(UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                            .setInspectorId(AospInspectorProtocolClient.COMPOSE_INSPECTOR_ID)
                            .setPayload(response.toByteString())))
                    .build().toByteArray())
            }
        }
        val output = ByteArrayOutputStream()
        val socket = object : Socket() {
            private val incoming = ByteArrayInputStream(input.toByteArray())
            override fun getInputStream() = incoming
            override fun getOutputStream() = output
        }
        AospInspectorProtocolClient.connect(0, InspectorSessionToken.parse("ab".repeat(32)),
            socketFactory = { socket }).use { protocol ->
            block(ComposeInspectionClient(protocol, adapter = adapter))
        }
        val sent = ByteArrayInputStream(output.toByteArray())
        InspectorFraming.read(sent) // Authentication token, not a protobuf command.
        return generateSequence { if (sent.available() > 0) InspectorFraming.read(sent) else null }
            .map { UiInspectorProtocol.Command.parseFrom(it) }
            .map { ComposeProtocol.Command.parseFrom(it.inspectorMessage.payload) }
            .toList()
    }
}
