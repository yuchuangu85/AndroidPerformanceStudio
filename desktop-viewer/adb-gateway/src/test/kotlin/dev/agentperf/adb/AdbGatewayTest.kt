package dev.agentperf.adb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class AdbGatewayTest {
    @Test
    fun `parses authorized offline and unauthorized devices`() {
        val output = """
            List of devices attached
            emulator-5554	device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 transport_id:1
            R3CN30ABC	offline transport_id:2
            ZX1G22	unauthorized usb:1-1 transport_id:3
        """.trimIndent()

        val devices = AdbOutputParser.parseDevices(output)

        assertEquals(
            listOf(DeviceState.DEVICE, DeviceState.OFFLINE, DeviceState.UNAUTHORIZED),
            devices.map { it.state },
        )
        assertEquals("sdk_gphone64_arm64", devices.first().model)
        assertEquals(3, devices.last().transportId)
    }

    @Test
    fun `builds an adb forward to the agent local abstract socket`() {
        val command = AdbCommandFactory.forward(
            serial = "emulator-5554",
            hostPort = 39123,
            socketName = "agentperf.dev_agentperf_sample",
        )

        assertEquals(
            listOf(
                "-s", "emulator-5554",
                "forward", "tcp:39123", "localabstract:agentperf.dev_agentperf_sample",
            ),
            command,
        )
    }

    @Test
    fun `builds a run-as session command for a valid package`() {
        val command = AdbCommandFactory.readSession("R3CN30ABC", "dev.agentperf.sample")

        assertEquals(
            listOf(
                "-s", "R3CN30ABC", "shell", "run-as", "dev.agentperf.sample",
                "cat", "files/agentperf/session.json",
            ),
            command,
        )
    }

    @Test
    fun `rejects unsafe package input instead of invoking a shell`() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbCommandFactory.readSession("device", "dev.sample; rm -rf /")
        }
    }
}
