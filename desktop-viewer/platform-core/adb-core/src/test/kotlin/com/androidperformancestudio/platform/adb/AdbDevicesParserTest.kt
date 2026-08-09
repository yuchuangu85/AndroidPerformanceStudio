package com.androidperformancestudio.platform.adb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdbDevicesParserTest {
    @Test
    fun `parses all device states attributes and daemon noise`() {
        val devices =
            AdbDevicesParser().parse(
                """
                * daemon not running; starting now at tcp:5037
                * daemon started successfully
                List of devices attached
                emulator-5554 device product:sdk model:Pixel_8 device:emu transport_id:1 feature:new
                R58M unauthorized usb:1-2 transport_id:2
                192.168.0.2:5555 offline
                ???????????? no permissions (user in plugdev group); see [https://developer.android.com/]
                future reconnecting transport_id:9
                """.trimIndent(),
            )

        assertEquals(5, devices.size)
        assertEquals(AdbDeviceState.ONLINE, devices[0].state)
        assertEquals("Pixel_8", devices[0].model)
        assertEquals("new", devices[0].attributes["feature"])
        assertEquals(AdbDeviceState.UNAUTHORIZED, devices[1].state)
        assertEquals(AdbDeviceState.OFFLINE, devices[2].state)
        assertEquals(AdbDeviceState.NO_PERMISSIONS, devices[3].state)
        assertEquals("user in plugdev group", devices[3].statusDetail)
        assertEquals(AdbDeviceState.UNKNOWN, devices[4].state)
        assertEquals("reconnecting", devices[4].rawState)
    }

    @Test
    fun `rejects malformed device lines`() {
        assertFailsWith<AdbOutputParseException> {
            AdbDevicesParser().parse("List of devices attached\nserial-without-state")
        }
    }
}
