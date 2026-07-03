package dev.agentperf.adb

import dev.agentperf.protocol.CaptureFrame
import dev.agentperf.protocol.CaptureFrameCodec
import dev.agentperf.protocol.ProtocolCodec
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LiveDeviceClientTest {
    @Test
    fun `session descriptor parses the persisted Agent document`() {
        val descriptor = AgentSessionDescriptor.parse(SESSION_JSON)

        assertEquals(1, descriptor.protocolMajor)
        assertEquals(0, descriptor.protocolMinor)
        assertEquals("agentperf.dev_agentperf_sample", descriptor.socketName)
        assertEquals("secret", descriptor.token)
    }

    @Test
    fun `connecting requires exactly one authorized device`() {
        val noDevices = fakeRunner(
            devices = "List of devices attached\n",
        )
        val multipleDevices = fakeRunner(
            devices = """
                List of devices attached
                first device model:One
                second device model:Two
            """.trimIndent(),
        )

        assertThrows(DeviceSelectionException::class.java) {
            LiveDeviceClient(noDevices).connect("dev.agentperf.sample")
        }
        assertThrows(DeviceSelectionException::class.java) {
            LiveDeviceClient(multipleDevices).connect("dev.agentperf.sample")
        }
    }

    @Test
    fun `connecting prefers a physical device when an emulator is also authorized`() {
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val serverResult = executor.submit {
            server.accept().use { socket ->
                assertEquals("PING secret", socket.getInputStream().bufferedReader().readLine())
                socket.getOutputStream().write("PONG 1.0\n".toByteArray())
            }
        }
        val runner = fakeRunner(
            devices = """
                List of devices attached
                emulator-5554 device product:sdk_gphone model:sdk_gphone transport_id:1
                physical-1 device product:sample model:Phone transport_id:2
            """.trimIndent(),
        )

        try {
            val session = LiveDeviceClient(
                processRunner = runner,
                portAllocator = { server.localPort },
            ).connect("dev.agentperf.sample")

            assertEquals("physical-1", session.serial)
            session.close()
            serverResult.get(2, TimeUnit.SECONDS)
            assertTrue(
                runner.commands.any {
                    it.take(2) == listOf("-s", "physical-1") &&
                        it.any { argument -> argument.endsWith("session.json") }
                },
            )
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `foreground connection targets the currently resumed application`() {
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val serverResult = executor.submit {
            server.accept().use { socket ->
                assertEquals("PING secret", socket.getInputStream().bufferedReader().readLine())
                socket.getOutputStream().write("PONG 1.0\n".toByteArray())
            }
        }
        val runner = fakeRunner(
            foreground = """
                ACTIVITY MANAGER ACTIVITIES
                  topResumedActivity=ActivityRecord{a16e8a3 u0 com.codemx.anrdemo/.MainActivity t9}
            """.trimIndent(),
        )

        try {
            val session = LiveDeviceClient(
                processRunner = runner,
                portAllocator = { server.localPort },
            ).connectForegroundApp()

            assertEquals("com.codemx.anrdemo", session.packageName)
            session.close()
            serverResult.get(2, TimeUnit.SECONDS)
            assertTrue(
                runner.commands.any {
                    it.take(2) == listOf("-s", "physical-1") &&
                        it.any { argument -> argument == "com.codemx.anrdemo" }
                },
            )
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `connected session detects when another application becomes foreground`() {
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val serverResult = executor.submit {
            server.accept().use { socket ->
                assertEquals("PING secret", socket.getInputStream().bufferedReader().readLine())
                socket.getOutputStream().write("PONG 1.0\n".toByteArray())
            }
        }
        val runner = fakeRunner(
            foreground = """
                topResumedActivity=ActivityRecord{a16e8a3 u0 com.codemx.anrdemo/.MainActivity t9}
            """.trimIndent(),
        )

        try {
            val session = LiveDeviceClient(
                processRunner = runner,
                portAllocator = { server.localPort },
            ).connect("dev.agentperf.sample")

            assertFalse(session.isForegroundAppCurrent())
            session.close()
            serverResult.get(2, TimeUnit.SECONDS)
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `failed adb fallback reports an actionable error`() {
        val runner = fakeRunner(
            foreground = """
                topResumedActivity=ActivityRecord{a16e8a3 u0 com.codemx.anrdemo/.MainActivity t9}
            """.trimIndent(),
            sessionResult = ProcessResult(
                exitCode = 1,
                stdout = "",
                stderr = "run-as: package not debuggable: com.codemx.anrdemo",
            ),
        )

        val session = LiveDeviceClient(runner).connectForegroundApp()

        val error = assertThrows(IllegalStateException::class.java) {
            session.capture()
        }
        session.close()

        assertEquals(
            "Unable to capture foreground app com.codemx.anrdemo through ADB",
            error.message,
        )
    }

    @Test
    fun `foreground application without Agent falls back to adb capture`() {
        val screenshot = pngHeader(width = 1080, height = 2400)
        val runner = fakeRunner(
            foreground = """
                topResumedActivity=ActivityRecord{a16e8a3 u0 com.codemx.anrdemo/.MainActivity t9}
            """.trimIndent(),
            sessionResult = ProcessResult(
                exitCode = 1,
                stdout = "",
                stderr = "cat: files/agentperf/session.json: No such file or directory",
            ),
            hierarchyResult = ProcessResult(
                exitCode = 0,
                stdout = """
                    <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
                    <hierarchy rotation="0">
                      <node text="" resource-id="" class="android.widget.FrameLayout"
                        package="com.codemx.anrdemo" bounds="[0,0][1080,2400]">
                        <node text="New application" resource-id="com.codemx.anrdemo:id/title"
                          class="android.widget.TextView" package="com.codemx.anrdemo"
                          bounds="[40,80][600,160]" />
                      </node>
                    </hierarchy>
                """.trimIndent(),
                stderr = "",
            ),
            screenshotResult = ProcessResult(
                exitCode = 0,
                stdout = "",
                stderr = "",
                stdoutBytes = screenshot,
            ),
        )

        val session = LiveDeviceClient(runner).connectForegroundApp()
        val frame = session.capture()
        session.close()
        val snapshot = ProtocolCodec(supportedMajor = 1).decodeSnapshot(frame.snapshotJson)

        assertEquals("com.codemx.anrdemo", session.packageName)
        assertEquals(1080, snapshot.display.widthPx)
        assertEquals(2400, snapshot.display.heightPx)
        assertEquals("android.widget.FrameLayout", snapshot.root.className)
        assertEquals("New application", snapshot.root.children.single().let { child ->
            (child as dev.agentperf.protocol.ViewNode).text
        })
        assertArrayEquals(screenshot, frame.screenshotPng)
    }

    @Test
    fun `connected session authenticates and decodes capture frames`() {
        val expected = CaptureFrame("""{"packageName":"dev.agentperf.sample"}""", byteArrayOf(1, 2, 3, 4))
        val server = ServerSocket(0)
        val executor = Executors.newSingleThreadExecutor()
        val serverResult = executor.submit {
            server.accept().use { socket ->
                assertEquals("PING secret", socket.getInputStream().bufferedReader().readLine())
                socket.getOutputStream().write("PONG 1.0\n".toByteArray())
            }
            server.accept().use { socket ->
                assertEquals("CAPTURE secret", socket.getInputStream().bufferedReader().readLine())
                CaptureFrameCodec().write(expected, socket.getOutputStream())
            }
        }
        val runner = fakeRunner()
        val client = LiveDeviceClient(
            processRunner = runner,
            portAllocator = { server.localPort },
        )

        val session = client.connect("dev.agentperf.sample")
        val actual = session.capture()
        session.close()
        serverResult.get(2, TimeUnit.SECONDS)
        server.close()
        executor.shutdownNow()

        assertEquals(expected.snapshotJson, actual.snapshotJson)
        assertArrayEquals(expected.screenshotPng, actual.screenshotPng)
        assertTrue(
            runner.commands.contains(
                listOf(
                    "-s", "physical-1", "forward",
                    "tcp:${server.localPort}", "localabstract:agentperf.dev_agentperf_sample",
                ),
            ),
        )
        assertTrue(
            runner.commands.contains(
                listOf("-s", "physical-1", "forward", "--remove", "tcp:${server.localPort}"),
            ),
        )
    }

    private fun fakeRunner(
        devices: String = """
            List of devices attached
            physical-1 device product:sample model:Phone transport_id:1
        """.trimIndent(),
        foreground: String = """
            topResumedActivity=ActivityRecord{abc u0 dev.agentperf.sample/.MainActivity t1}
        """.trimIndent(),
        sessionResult: ProcessResult = ProcessResult(0, SESSION_JSON, ""),
        hierarchyResult: ProcessResult = ProcessResult(1, "", "unexpected hierarchy request"),
        screenshotResult: ProcessResult = ProcessResult(1, "", "unexpected screenshot request"),
    ) = RecordingProcessRunner(
        devices,
        foreground,
        sessionResult,
        hierarchyResult,
        screenshotResult,
    )

    private class RecordingProcessRunner(
        private val devices: String,
        private val foreground: String,
        private val sessionResult: ProcessResult,
        private val hierarchyResult: ProcessResult,
        private val screenshotResult: ProcessResult,
    ) : ProcessRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(arguments: List<String>): ProcessResult {
            commands += arguments
            return when {
                arguments == listOf("devices", "-l") -> ProcessResult(0, devices, "")
                arguments.takeLast(4) == listOf("shell", "dumpsys", "activity", "activities") ->
                    ProcessResult(0, foreground, "")
                arguments.any { it.endsWith("session.json") } -> sessionResult
                arguments.takeLast(4) == listOf("exec-out", "uiautomator", "dump", "/dev/tty") ->
                    hierarchyResult
                arguments.takeLast(3) == listOf("exec-out", "screencap", "-p") -> screenshotResult
                arguments.contains("forward") -> ProcessResult(0, "", "")
                else -> error("Unexpected command: $arguments")
            }
        }
    }

    private fun pngHeader(width: Int, height: Int): ByteArray =
        ByteArray(24).apply {
            byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            ).copyInto(this)
            "IHDR".toByteArray().copyInto(this, destinationOffset = 12)
            writeInt(16, width)
            writeInt(20, height)
        }

    private fun ByteArray.writeInt(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }

    private companion object {
        val SESSION_JSON = """
            {
              "protocolMajor": 1,
              "protocolMinor": 0,
              "socketName": "agentperf.dev_agentperf_sample",
              "token": "secret"
            }
        """.trimIndent()
    }
}
