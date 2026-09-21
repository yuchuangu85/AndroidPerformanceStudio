package com.androidperformancestudio.adb

import kotlin.test.Test
import kotlin.test.assertEquals

class AndroidDevicePropertiesTest {
    @Test
    fun `capability display name falls back to manufacturer for an unknown model`() {
        val properties =
            AndroidDeviceProperties(
                serial = "UCSCU8AQWG6LJFQS",
                model = "unknown",
                abis = listOf("arm64-v8a"),
                sdkInt = 35,
                androidVersion = "15",
                manufacturer = "TCL",
            )

        assertEquals("TCL", properties.capabilityDisplayName)
    }

    @Test
    fun `capability display name keeps a usable model`() {
        val properties =
            AndroidDeviceProperties(
                serial = "serial-1",
                model = "Pixel 8",
                abis = listOf("arm64-v8a"),
                sdkInt = 35,
                androidVersion = "15",
                manufacturer = "Google",
            )

        assertEquals("Pixel 8", properties.capabilityDisplayName)
    }
}
