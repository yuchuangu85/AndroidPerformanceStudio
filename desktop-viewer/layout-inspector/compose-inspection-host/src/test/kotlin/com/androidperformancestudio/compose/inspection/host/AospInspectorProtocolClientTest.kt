package com.androidperformancestudio.compose.inspection.host

import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AospInspectorProtocolClientTest {
    @Test
    fun `auth token is the first frame before any protobuf command`() {
        val token = InspectorSessionToken.parse("ab".repeat(32))
        val observedToken = CompletableFuture<String>()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val worker = Thread {
                server.accept().use { socket ->
                    observedToken.complete(InspectorFraming.read(socket.getInputStream()).toString(Charsets.UTF_8))
                    val command = UiInspectorProtocol.Command.parseFrom(InspectorFraming.read(socket.getInputStream()))
                    val response = UiInspectorProtocol.Response.newBuilder()
                        .setCommandId(command.commandId)
                        .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
                        .setGetVersion(
                            UiInspectorProtocol.GetVersionResponse.newBuilder()
                                .putVersions(AospInspectorProtocolClient.COMPOSE_UI_LIBRARY_ID, "1.11.4"),
                        )
                        .build()
                    InspectorFraming.write(
                        socket.getOutputStream(),
                        UiInspectorProtocol.AgentMessage.newBuilder().setResponse(response).build().toByteArray(),
                    )
                }
            }.apply { start() }

            AospInspectorProtocolClient.connect(server.localPort, token).use { client ->
                assertEquals("1.11.4", client.getComposeVersion())
            }
            worker.join(2_000)
        }
        assertEquals(token.value, observedToken.get())
    }
}
