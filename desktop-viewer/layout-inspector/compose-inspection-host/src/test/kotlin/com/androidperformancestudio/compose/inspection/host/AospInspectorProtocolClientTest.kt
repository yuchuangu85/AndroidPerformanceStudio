package com.androidperformancestudio.compose.inspection.host

import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AospInspectorProtocolClientTest {
    @Test
    fun `failed authentication write closes the partially constructed socket`() {
        val failure = IOException("authentication write failed")
        var closed = false
        val socket = object : Socket() {
            override fun getOutputStream(): OutputStream = object : OutputStream() {
                override fun write(value: Int) {
                    throw failure
                }
            }
            override fun close() {
                closed = true
            }
        }
        val error = assertThrows(IOException::class.java) {
            AospInspectorProtocolClient.connect(
                1234,
                InspectorSessionToken.parse("ab".repeat(32)),
                socketFactory = { socket },
            )
        }
        assertSame(failure, error)
        assertTrue(closed)
    }

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
