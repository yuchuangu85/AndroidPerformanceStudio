package dev.agentperf.adb

import dev.agentperf.protocol.CaptureFrame
import dev.agentperf.protocol.CaptureFrameCodec
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class DeviceSelectionException(message: String) : IllegalStateException(message)
class ForegroundAppUnavailableException(message: String) : IllegalStateException(message)
class VisibleWindowViewsUnavailableException(message: String) : IllegalStateException(message)
class AgentUnavailableException(
    val packageName: String,
    cause: Throwable,
) : IllegalStateException("Foreground app $packageName is not AgentPerf-enabled", cause)

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
    fun connectForegroundApp(): ConnectedDeviceSession {
        val device = selectDevice()
        val packageName = foregroundPackageName(device.serial)
        return try {
            connect(device, packageName)
        } catch (_: AgentUnavailableException) {
            ConnectedDeviceSession(
                serial = device.serial,
                packageName = packageName,
                processRunner = processRunner,
                socketConnector = socketConnector,
                fallbackCapture = AdbFallbackCapture(
                    serial = device.serial,
                    packageName = packageName,
                    processRunner = processRunner,
                )::capture,
            )
        }
    }

    fun connect(packageName: String): ConnectedDeviceSession =
        connect(selectDevice(), packageName)

    fun dumpVisibleWindowViews(): ByteArray {
        val device = selectDevice()
        val result = checkedRun(AdbCommandFactory.dumpVisibleWindowViews(device.serial))
        if (result.stdoutBytes.isEmpty()) {
            throw VisibleWindowViewsUnavailableException("Visible Window View dump is empty")
        }
        return result.stdoutBytes
    }

    fun foregroundPackageName(serial: String): String =
        checkedRun(AdbCommandFactory.foregroundActivity(serial))
            .stdout
            .let(AdbOutputParser::parseForegroundPackage)
            ?: throw ForegroundAppUnavailableException("No foreground Android application found")

    private fun selectDevice(): AdbDevice {
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
        return device
    }

    private fun connect(device: AdbDevice, packageName: String): ConnectedDeviceSession {
        val sessionDocument = try {
            checkedRun(AdbCommandFactory.readSession(device.serial, packageName)).stdout
        } catch (error: AdbCommandException) {
            throw AgentUnavailableException(packageName, error)
        }
        val descriptor = sessionDocument
            .let(AgentSessionDescriptor::parse)
        val port = portAllocator()
        checkedRun(AdbCommandFactory.forward(device.serial, port, descriptor.socketName))
        val session = ConnectedDeviceSession(
            serial = device.serial,
            packageName = packageName,
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
    val packageName: String,
    private val hostPort: Int? = null,
    private val token: String? = null,
    private val processRunner: ProcessRunner,
    private val socketConnector: (Int) -> Socket,
    private val captureFrameCodec: CaptureFrameCodec = CaptureFrameCodec(),
    private val fallbackCapture: (() -> CaptureFrame)? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    internal fun authenticate() {
        val agentPort = requireNotNull(hostPort)
        val agentToken = requireNotNull(token)
        socketConnector(agentPort).use { socket ->
            socket.getOutputStream().write("PING $agentToken\n".toByteArray())
            socket.getOutputStream().flush()
            val response = socket.getInputStream().bufferedReader().readLine()
            check(response == "PONG 1.0") { "Agent authentication failed: ${response.orEmpty()}" }
        }
    }

    fun capture(): CaptureFrame {
        check(!closed.get()) { "Device session is closed" }
        fallbackCapture?.let { return it() }
        val agentPort = requireNotNull(hostPort)
        val agentToken = requireNotNull(token)
        return socketConnector(agentPort).use { socket ->
            socket.getOutputStream().write("CAPTURE $agentToken\n".toByteArray())
            socket.getOutputStream().flush()
            captureFrameCodec.read(socket.getInputStream())
        }
    }

    fun isForegroundAppCurrent(): Boolean {
        val arguments = AdbCommandFactory.foregroundActivity(serial)
        val result = processRunner.run(arguments)
        if (result.exitCode != 0) throw AdbCommandException(arguments, result)
        val currentPackage = AdbOutputParser.parseForegroundPackage(result.stdout)
            ?: throw ForegroundAppUnavailableException("No foreground Android application found")
        return currentPackage == packageName
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        hostPort?.let { processRunner.run(AdbCommandFactory.removeForward(serial, it)) }
    }
}
