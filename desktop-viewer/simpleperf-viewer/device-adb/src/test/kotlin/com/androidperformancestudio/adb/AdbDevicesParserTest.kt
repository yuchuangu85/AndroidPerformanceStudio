package com.androidperformancestudio.adb

import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbDeviceState
import com.androidperformancestudio.platform.adb.AdbDevicesParser
import com.androidperformancestudio.platform.adb.AdbOutputParseException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AdbDevicesParserTest {
    @Test
    fun `parses multiple long-format devices and preserves properties`() {
        val output =
            """
            List of devices attached
            emulator-5554 device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1
            R58M123456 unauthorized usb:1-2 transport_id:2
            192.168.1.20:5555 offline transport_id:3
            """.trimIndent()

        val result = AdbDevicesParser().parse(output)

        assertEquals(3, result.size)
        assertEquals(
            AdbDevice(
                serial = "emulator-5554",
                state = AdbDeviceState.ONLINE,
                rawState = "device",
                product = "sdk_gphone64_arm64",
                model = "sdk_gphone64_arm64",
                device = "emu64a",
                transportId = 1,
                attributes =
                    mapOf(
                        "product" to "sdk_gphone64_arm64",
                        "model" to "sdk_gphone64_arm64",
                        "device" to "emu64a",
                        "transport_id" to "1",
                    ),
            ),
            result[0],
        )
        assertEquals(AdbDeviceState.UNAUTHORIZED, result[1].state)
        assertEquals("1-2", result[1].attributes["usb"])
        assertEquals(AdbDeviceState.OFFLINE, result[2].state)
        assertEquals("192.168.1.20:5555", result[2].serial)
    }

    @Test
    fun `parses no-permissions status without treating message as properties`() {
        val output =
            """
            List of devices attached
            ???????????? no permissions (user in plugdev group; are your udev rules wrong?); see [http://developer.android.com/tools/device.html]
            """.trimIndent()

        val device = AdbDevicesParser().parse(output).single()

        assertEquals(AdbDeviceState.NO_PERMISSIONS, device.state)
        assertEquals("no permissions", device.rawState)
        assertEquals("user in plugdev group; are your udev rules wrong?", device.statusDetail)
        assertEquals(emptyMap(), device.attributes)
    }

    @Test
    fun `keeps unknown future states instead of dropping the device`() {
        val output = "List of devices attached\nserial-1 reconnecting transport_id:8"

        val device =
            AdbDevicesParser().parse(output).single()

        assertEquals(AdbDeviceState.UNKNOWN, device.state)
        assertEquals("reconnecting", device.rawState)
        assertNull(device.statusDetail)
        assertEquals("8", device.attributes["transport_id"])
    }

    @Test
    fun `returns an empty list when no devices are attached`() {
        assertEquals(emptyList(), AdbDevicesParser().parse("List of devices attached\n\n"))
    }

    @Test
    fun `rejects malformed non-empty device lines`() {
        assertFailsWith<AdbOutputParseException> {
            AdbDevicesParser().parse("List of devices attached\nserial-without-state")
        }
    }
}
