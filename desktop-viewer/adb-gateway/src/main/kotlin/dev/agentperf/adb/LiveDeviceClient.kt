package dev.agentperf.adb

import dev.agentperf.protocol.CaptureFrame
import dev.agentperf.protocol.CaptureFrameCodec
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class DeviceSelectionException(message: String) : IllegalStateException(message)

class AdbCommandException(
    val arguments: List<String>,
    val result: ProcessResult,
) : IllegalStateException(
    "adb ${arguments.joinToString(" ")} failed (${result.exitCode}): " +
        (result.stderr.ifBlank { result.stdout }).trim(),
)

class LiveDeviceClient(
    private val processRunner: ProcessRunner = AdbProcessRunner(),
    private val portAllocator: () -> Int = {
        ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
    },
    private val socketConnector: (Int) -> Socket = { port ->
        Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = SOCKET_TIMEOUT_MILLIS }
    },
) {
    fun connect(packageName: String): ConnectedDeviceSession {
        val devices = checkedRun(listOf("devices", "-l")).stdout
            .let(AdbOutputParser::parseDevices)
            .filter { it.state == DeviceState.DEVICE }
        val physicalDevices = devices.filterNot { it.serial.startsWith(EMULATOR_SERIAL_PREFIX) }
        val device = when {
            physicalDevices.size == 1 -> physicalDevices.single()
            physicalDevices.isEmpty() && devices.size == 1 -> devices.single()
            else -> null
        }
        if (device == null) {
            throw DeviceSelectionException(
                "Expected exactly one authorized device, found ${devices.size}",
            )
        }
        val descriptor = checkedRun(AdbCommandFactory.readSession(device.serial, packageName))
            .stdout
            .let(AgentSessionDescriptor::parse)
        val port = portAllocator()
        checkedRun(AdbCommandFactory.forward(device.serial, port, descriptor.socketName))
        val session = ConnectedDeviceSession(
            serial = device.serial,
            hostPort = port,
            token = descriptor.token,
            processRunner = processRunner,
            socketConnector = socketConnector,
        )
        return try {
            session.authenticate()
            session
        } catch (error: Throwable) {
            session.close()
            throw error
        }
    }

    private fun checkedRun(arguments: List<String>): ProcessResult =
        processRunner.run(arguments).also { result ->
            if (result.exitCode != 0) throw AdbCommandException(arguments, result)
        }

    private companion object {
        const val EMULATOR_SERIAL_PREFIX = "emulator-"
        const val SOCKET_TIMEOUT_MILLIS = 5_000
    }
}

class ConnectedDeviceSession internal constructor(
    val serial: String,
    private val hostPort: Int,
    private val token: String,
    private val processRunner: ProcessRunner,
    private val socketConnector: (Int) -> Socket,
    private val captureFrameCodec: CaptureFrameCodec = CaptureFrameCodec(),
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    internal fun authenticate() {
        socketConnector(hostPort).use { socket ->
            socket.getOutputStream().write("PING $token\n".toByteArray())
            socket.getOutputStream().flush()
            val response = socket.getInputStream().bufferedReader().readLine()
            check(response == "PONG 1.0") { "Agent authentication failed: ${response.orEmpty()}" }
        }
    }

    fun capture(): CaptureFrame {
        check(!closed.get()) { "Device session is closed" }
        return socketConnector(hostPort).use { socket ->
            socket.getOutputStream().write("CAPTURE $token\n".toByteArray())
            socket.getOutputStream().flush()
            captureFrameCodec.read(socket.getInputStream())
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        processRunner.run(AdbCommandFactory.removeForward(serial, hostPort))
    }
}
