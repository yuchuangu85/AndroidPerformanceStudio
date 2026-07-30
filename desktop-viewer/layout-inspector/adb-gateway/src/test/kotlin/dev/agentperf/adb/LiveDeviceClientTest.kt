package com.androidperformancestudio.adb

import com.androidperformancestudio.protocol.CaptureFrame
import com.androidperformancestudio.protocol.CaptureFrameCodec
import com.androidperformancestudio.protocol.ProtocolCodec
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
            LiveDeviceClient(noDevices).connect("com.androidperformancestudio.sample")
        }
        assertThrows(DeviceSelectionException::class.java) {
            LiveDeviceClient(multipleDevices).connect("com.androidperformancestudio.sample")
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
            ).connect("com.androidperformancestudio.sample")

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
    fun `visible window dump uses the selected physical device and preserves binary output`() {
        val expected = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val runner = fakeRunner(
            devices = """
                List of devices attached
                emulator-5554 device product:sdk_gphone model:sdk_gphone transport_id:1
                physical-1 device product:sample model:Phone transport_id:2
            """.trimIndent(),
            visibleHierarchyResult = ProcessResult(
                exitCode = 0,
                stdout = "",
                stderr = "",
                stdoutBytes = expected,
            ),
        )

        val actual = LiveDeviceClient(runner).dumpVisibleWindowViews()

        assertArrayEquals(expected, actual)
        assertTrue(
            runner.commands.contains(
                listOf(
                    "-s", "physical-1", "exec-out",
                    "cmd", "window", "dump-visible-window-views",
                ),
            ),
        )
    }

    @Test
    fun `visible window dump rejects empty command output`() {
        val runner = fakeRunner(
            visibleHierarchyResult = ProcessResult(
                exitCode = 0,
                stdout = "",
                stderr = "",
                stdoutBytes = byteArrayOf(),
            ),
        )

        val error = assertThrows(VisibleWindowViewsUnavailableException::class.java) {
            LiveDeviceClient(runner).dumpVisibleWindowViews()
        }

        assertEquals("Visible Window View dump is empty", error.message)
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
            ).connect("com.androidperformancestudio.sample")

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
            visibleHierarchyResult = ProcessResult(
                exitCode = 0,
                stdout = "",
                stderr = "",
                stdoutBytes = EncodedHierarchyFixture.zip("com.codemx.anrdemo"),
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
        assertEquals("com.codemx.ui.RealRootLayout", snapshot.root.className)
        assertEquals(
            "com.codemx.ui.RealTitleView",
            snapshot.root.children.single().className,
        )
        assertArrayEquals(screenshot, frame.screenshotPng)
    }

    @Test
    fun `explicit systemui connection falls back to visible window adb capture`() {
        val runner = fakeRunner(
            sessionResult = ProcessResult(
                exitCode = 1,
                stdout = "",
                stderr = "run-as: package not debuggable: com.android.systemui",
            ),
            visibleHierarchyResult = ProcessResult(
                exitCode = 0,
                stdout = "",
                stderr = "",
                stdoutBytes = EncodedHierarchyFixture.systemUiWithLauncherTaskbarZip(),
            ),
            screenshotResult = ProcessResult(
                exitCode = 0,
                stdout = "",
                stderr = "",
                stdoutBytes = pngHeader(width = 1080, height = 2400),
            ),
        )

        val session = LiveDeviceClient(runner).connect("com.android.systemui")
        val frame = session.capture()
        session.close()
        val snapshot = ProtocolCodec(supportedMajor = 1).decodeSnapshot(frame.snapshotJson)

        assertEquals("com.android.systemui", session.packageName)
        assertEquals(listOf("StatusBar", "Taskbar"), snapshot.windows.map { it.title })
    }

    @Test
    fun `foreground application without Agent falls back to layout only when adb screencap is blocked`() {
        val runner = fakeRunner(
            foreground = """
                topResumedActivity=ActivityRecord{a16e8a3 u0 com.codemx.anrdemo/.MainActivity t9}
            """.trimIndent(),
            sessionResult = ProcessResult(
                exitCode = 1,
                stdout = "",
                stderr = "cat: files/agentperf/session.json: No such file or directory",
            ),
            visibleHierarchyResult = ProcessResult(
                exitCode = 0,
                stdout = "",
                stderr = "",
                stdoutBytes = EncodedHierarchyFixture.zip("com.codemx.anrdemo"),
            ),
            screenshotResult = ProcessResult(
                exitCode = 1,
                stdout = "",
                stderr = "screencap: Permission denied",
                stdoutBytes = byteArrayOf(),
            ),
        )

        val session = LiveDeviceClient(runner).connectForegroundApp()
        val frame = session.capture()
        session.close()
        val snapshot = ProtocolCodec(supportedMajor = 1).decodeSnapshot(frame.snapshotJson)

        assertEquals("com.codemx.anrdemo", session.packageName)
        assertEquals(1090, snapshot.display.widthPx)
        assertEquals(2420, snapshot.display.heightPx)
        assertTrue(snapshot.capabilities.viewHierarchy)
        assertFalse(snapshot.capabilities.screenshots)
        assertEquals("com.codemx.ui.RealRootLayout", snapshot.root.className)
        assertEquals(
            "com.codemx.ui.RealTitleView",
            snapshot.root.children.single().className,
        )
        assertTrue(frame.screenshotPng.isEmpty())
        assertTrue(
            runner.commands.any {
                it.takeLast(3) == listOf("exec-out", "screencap", "-p")
            },
        )
    }

    @Test
    fun `connected session authenticates and decodes capture frames`() {
        val expected = CaptureFrame("""{"packageName":"com.androidperformancestudio.sample"}""", byteArrayOf(1, 2, 3, 4))
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

        val session = client.connect("com.androidperformancestudio.sample")
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


    @Test
    fun `connected session keeps single window agent screenshot without adb screencap`() {
        val agentScreenshot = pngHeader(320, 640)
        val adbScreenshot = pngHeader(1080, 1920)
        val expected = CaptureFrame(
            snapshotJson = singleWindowSnapshot(width = 320, height = 640),
            screenshotPng = agentScreenshot,
        )
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
        val runner = fakeRunner(
            screenshotResult = ProcessResult(0, "", "", stdoutBytes = adbScreenshot),
        )
        val client = LiveDeviceClient(
            processRunner = runner,
            portAllocator = { server.localPort },
        )

        val session = client.connect("com.androidperformancestudio.sample")
        val actual = session.capture()
        session.close()
        serverResult.get(2, TimeUnit.SECONDS)
        server.close()
        executor.shutdownNow()

        assertEquals(expected.snapshotJson, actual.snapshotJson)
        assertArrayEquals(agentScreenshot, actual.screenshotPng)
        assertFalse(
            runner.commands.any { it.takeLast(3) == listOf("exec-out", "screencap", "-p") },
            "Single-window Agent capture should not run an extra ADB screencap",
        )
    }

    @Test
    fun `lists authorized devices for explicit selection`() {
        val runner = fakeRunner(
            devices = """
                List of devices attached
                emulator-5554 device product:sdk_gphone model:sdk_gphone transport_id:1
                physical-1 device product:sample model:Pixel_8 transport_id:2
                offline-1 offline transport_id:3
            """.trimIndent(),
        )

        val devices = LiveDeviceClient(runner).listAuthorizedDevices()

        assertEquals(listOf("emulator-5554", "physical-1"), devices.map { it.serial })
        assertEquals(listOf("sdk_gphone", "Pixel_8"), devices.map { it.model })
    }

    @Test
    fun `foreground connection can target an explicit device serial`() {
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
            ).connectForegroundApp(serial = "emulator-5554")

            assertEquals("emulator-5554", session.serial)
            session.close()
            serverResult.get(2, TimeUnit.SECONDS)
            assertTrue(
                runner.commands.any {
                    it.take(2) == listOf("-s", "emulator-5554") &&
                        it.any { argument -> argument.endsWith("session.json") }
                },
            )
        } finally {
            server.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `explicit device serial must be authorized`() {
        val runner = fakeRunner(
            devices = """
                List of devices attached
                physical-1 device product:sample model:Phone transport_id:1
                offline-1 offline transport_id:2
            """.trimIndent(),
        )

        val error = assertThrows(DeviceSelectionException::class.java) {
            LiveDeviceClient(runner).connectForegroundApp(serial = "offline-1")
        }

        assertEquals("Device offline-1 is not authorized or not connected", error.message)
    }

    @Test
    fun `visible window dump can target an explicit device serial`() {
        val expected = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
        val runner = fakeRunner(
            devices = """
                List of devices attached
                emulator-5554 device product:sdk_gphone model:sdk_gphone transport_id:1
                physical-1 device product:sample model:Phone transport_id:2
            """.trimIndent(),
            visibleHierarchyResult = ProcessResult(
                exitCode = 0,
                stdout = "",
                stderr = "",
                stdoutBytes = expected,
            ),
        )

        val actual = LiveDeviceClient(runner).dumpVisibleWindowViews(serial = "emulator-5554")

        assertArrayEquals(expected, actual)
        assertTrue(
            runner.commands.contains(
                listOf(
                    "-s", "emulator-5554", "exec-out",
                    "cmd", "window", "dump-visible-window-views",
                ),
            ),
        )
    }

    private fun fakeRunner(
        devices: String = """
            List of devices attached
            physical-1 device product:sample model:Phone transport_id:1
        """.trimIndent(),
        foreground: String = """
            topResumedActivity=ActivityRecord{abc u0 com.androidperformancestudio.sample/.MainActivity t1}
        """.trimIndent(),
        sessionResult: ProcessResult = ProcessResult(0, SESSION_JSON, ""),
        visibleHierarchyResult: ProcessResult =
            ProcessResult(1, "", "visible-window hierarchy unavailable"),
        hierarchyResult: ProcessResult = ProcessResult(1, "", "unexpected hierarchy request"),
        screenshotResult: ProcessResult = ProcessResult(1, "", "unexpected screenshot request"),
    ) = RecordingProcessRunner(
        devices,
        foreground,
        sessionResult,
        visibleHierarchyResult,
        hierarchyResult,
        screenshotResult,
    )

    private class RecordingProcessRunner(
        private val devices: String,
        private val foreground: String,
        private val sessionResult: ProcessResult,
        private val visibleHierarchyResult: ProcessResult,
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
                arguments.takeLast(4) ==
                    listOf("exec-out", "cmd", "window", "dump-visible-window-views") ->
                    visibleHierarchyResult
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


    private fun singleWindowSnapshot(width: Int, height: Int): String = """
        {
          "protocolVersion": { "major": 1, "minor": 1 },
          "packageName": "com.androidperformancestudio.sample",
          "capturedAtEpochMillis": 1,
          "display": { "widthPx": $width, "heightPx": $height, "density": 1.0 },
          "capabilities": { "viewHierarchy": true, "screenshots": true },
          "root": {
            "type": "view",
            "id": "window:activity/root",
            "className": "android.view.View",
            "bounds": { "left": 0, "top": 0, "right": $width, "bottom": $height }
          },
          "windows": [
            {
              "id": "window:activity",
              "title": "MainActivity",
              "type": "ACTIVITY",
              "bounds": { "left": 0, "top": 0, "right": $width, "bottom": $height },
              "root": {
                "type": "view",
                "id": "window:activity/root",
                "className": "android.view.View",
                "bounds": { "left": 0, "top": 0, "right": $width, "bottom": $height }
              }
            }
          ],
          "defaultWindowId": "window:activity"
        }
    """.trimIndent()

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
