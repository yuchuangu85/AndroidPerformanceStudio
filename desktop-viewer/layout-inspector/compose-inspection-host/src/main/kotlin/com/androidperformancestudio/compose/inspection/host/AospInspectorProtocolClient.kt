package com.androidperformancestudio.compose.inspection.host

import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import com.google.protobuf.ByteString
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@JvmInline
value class InspectorSessionToken private constructor(val value: String) {
    companion object {
        fun generate(random: SecureRandom = SecureRandom()): InspectorSessionToken =
            InspectorSessionToken(HexFormat.of().formatHex(ByteArray(32).also(random::nextBytes)))

        fun parse(value: String): InspectorSessionToken {
            require(value.matches(Regex("[0-9a-f]{64}"))) { "Inspector session token must be 256-bit lowercase hex" }
            return InspectorSessionToken(value)
        }
    }
}

class InspectorAgentException(message: String) : IllegalStateException(message)

class AospInspectorProtocolClient private constructor(
    private val socket: Socket,
    token: InspectorSessionToken,
    private val onEvent: (UiInspectorProtocol.Event) -> Unit,
) : AutoCloseable {
    private val nextCommandId = AtomicInteger(1)
    private val closed = AtomicBoolean(false)

    init {
        InspectorFraming.write(socket.getOutputStream(), token.value.toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun getComposeVersion(): String? {
        val response = send(
            UiInspectorProtocol.Command.newBuilder().setGetVersion(
                UiInspectorProtocol.GetVersionCommand.newBuilder()
                    .addLibraryIds(COMPOSE_UI_LIBRARY_ID),
            ),
        )
        return response.getVersion.versionsMap[COMPOSE_UI_LIBRARY_ID]
    }

    @Synchronized
    fun createInspector(inspectorId: String, deviceDexPath: String) {
        require(inspectorId.isNotBlank() && deviceDexPath.startsWith('/'))
        send(
            UiInspectorProtocol.Command.newBuilder().setCreateInspector(
                UiInspectorProtocol.CreateInspectorCommand.newBuilder()
                    .setInspectorId(inspectorId)
                    .setDexPath(deviceDexPath),
            ),
        )
    }

    @Synchronized
    fun sendInspectorCommand(inspectorId: String, payload: ByteArray): ByteArray {
        val response = send(
            UiInspectorProtocol.Command.newBuilder().setInspectorMessage(
                UiInspectorProtocol.InspectorMessageCommand.newBuilder()
                    .setInspectorId(inspectorId)
                    .setPayload(ByteString.copyFrom(payload)),
            ),
        )
        check(response.inspectorMessage.inspectorId == inspectorId) { "Inspector response ID mismatch" }
        return response.inspectorMessage.payload.toByteArray()
    }

    @Synchronized
    fun shutdownAgent() {
        if (closed.get()) return
        send(
            UiInspectorProtocol.Command.newBuilder().setShutdown(
                UiInspectorProtocol.ShutdownCommand.getDefaultInstance(),
            ),
        )
    }

    private fun send(builder: UiInspectorProtocol.Command.Builder): UiInspectorProtocol.Response {
        check(!closed.get()) { "Inspector connection is closed" }
        val commandId = nextCommandId.getAndIncrement()
        InspectorFraming.write(
            socket.getOutputStream(),
            builder.setCommandId(commandId).build().toByteArray(),
        )
        while (true) {
            val message = UiInspectorProtocol.AgentMessage.parseFrom(
                InspectorFraming.read(socket.getInputStream()),
            )
            if (message.hasEvent()) {
                if (message.event.hasCrash()) {
                    throw InspectorAgentException(
                        "Compose inspector agent crashed: ${message.event.crash.errorMessage}",
                    )
                }
                onEvent(message.event)
                continue
            }
            check(message.hasResponse()) { "Inspector sent an empty message" }
            val response = message.response
            check(response.commandId == commandId) { "Inspector response command ID mismatch" }
            if (response.status != UiInspectorProtocol.Response.Status.SUCCESS) {
                throw InspectorAgentException(response.errorMessage.ifBlank { "Inspector command failed" })
            }
            return response
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) socket.close()
    }

    companion object {
        const val VIEW_INSPECTOR_ID = "ui.inspector.inspectors.view.inspector"
        const val COMPOSE_INSPECTOR_ID = "layoutinspector.compose.inspection"
        const val COMPOSE_UI_LIBRARY_ID = "androidx.compose.ui:ui"

        fun connect(
            hostPort: Int,
            token: InspectorSessionToken,
            onEvent: (UiInspectorProtocol.Event) -> Unit = {},
            socketFactory: (Int) -> Socket = { port ->
                Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 5_000 }
            },
        ): AospInspectorProtocolClient = AospInspectorProtocolClient(
            socket = socketFactory(hostPort),
            token = token,
            onEvent = onEvent,
        )
    }
}
