package dev.agentperf.adb

import dev.agentperf.protocol.ViewNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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
    fun `builds a visible window hierarchy command for runtime view classes`() {
        assertEquals(
            listOf(
                "-s", "emulator-5554",
                "exec-out", "cmd", "window", "dump-visible-window-views",
            ),
            AdbCommandFactory.dumpVisibleWindowViews("emulator-5554"),
        )
    }

    @Test
    fun `uiautomator compatibility parser keeps available interaction attributes`() {
        val root = UiAutomatorHierarchyParser.parse(
            """
                <hierarchy rotation="0">
                  <node text="Action" resource-id="dev.sample:id/action"
                    class="android.widget.Button" bounds="[0,0][100,50]"
                    enabled="true" clickable="true" long-clickable="false"
                    focusable="true" focused="false" selected="true"
                    content-desc="Submit" visible-to-user="true" />
                </hierarchy>
            """.trimIndent(),
        ) as ViewNode

        assertEquals("root", root.id)
        assertEquals("dev.sample:id/action", root.resourceName)
        assertEquals("VISIBLE_TO_USER", root.attributes.visibility)
        assertEquals(true, root.attributes.enabled)
        assertEquals(true, root.attributes.clickable)
        assertEquals(false, root.attributes.longClickable)
        assertEquals(true, root.attributes.focusable)
        assertEquals(false, root.attributes.focused)
        assertEquals(true, root.attributes.selected)
        assertEquals("Submit", root.attributes.contentDescription)
    }

    @Test
    fun `foreground parser prefers the focused app in multi-window output`() {
        val output = """
            topResumedActivity=ActivityRecord{111 u0 org.chromium.home.pc/.HostActivity t2}
            topResumedActivity=ActivityRecord{222 u0 com.codemx.anrdemo/.MainActivity t10}
            mFocusedApp=ActivityRecord{222 u0 com.codemx.anrdemo/.MainActivity t10}
        """.trimIndent()

        assertEquals("com.codemx.anrdemo", AdbOutputParser.parseForegroundPackage(output))
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

    @Test
    fun `terminates a command that exceeds its deadline`() {
        val startedAt = System.nanoTime()

        val result = AdbProcessRunner(
            executable = "/bin/sleep",
            timeoutMillis = 50,
        ).run(listOf("5"))

        val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000
        assertEquals(AdbProcessRunner.TIMEOUT_EXIT_CODE, result.exitCode)
        assertTrue(result.stderr.contains("timed out"))
        assertTrue(elapsedMillis < 1_000, "command took ${elapsedMillis}ms")
    }
}
