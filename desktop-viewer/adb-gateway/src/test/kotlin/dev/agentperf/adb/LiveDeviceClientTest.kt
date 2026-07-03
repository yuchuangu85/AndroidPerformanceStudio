package dev.agentperf.adb

import dev.agentperf.protocol.CaptureFrame
import dev.agentperf.protocol.CaptureFrameCodec
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
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
    ) = RecordingProcessRunner(devices)

    private class RecordingProcessRunner(
        private val devices: String,
    ) : ProcessRunner {
        val commands = mutableListOf<List<String>>()

        override fun run(arguments: List<String>): ProcessResult {
            commands += arguments
            return when {
                arguments == listOf("devices", "-l") -> ProcessResult(0, devices, "")
                arguments.any { it.endsWith("session.json") } -> ProcessResult(0, SESSION_JSON, "")
                arguments.contains("forward") -> ProcessResult(0, "", "")
                else -> error("Unexpected command: $arguments")
            }
        }
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
