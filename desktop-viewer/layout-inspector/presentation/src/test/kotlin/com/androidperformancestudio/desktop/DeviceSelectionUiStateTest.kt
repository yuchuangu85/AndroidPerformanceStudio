package com.androidperformancestudio.desktop

import com.androidperformancestudio.platform.adb.AdbDevice
import com.androidperformancestudio.platform.adb.AdbDeviceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DeviceSelectionUiStateTest {
    @Test
    fun `device choices prefer model label and keep serial identity`() {
        val choices = deviceChoices(
            listOf(
                AdbDevice(serial = "emulator-5554", state = AdbDeviceState.ONLINE, model = "sdk_gphone"),
                AdbDevice(serial = "R3CN30ABC", state = AdbDeviceState.ONLINE, model = "Pixel_8"),
            ),
        )

        assertEquals(
            listOf(
                DeviceChoiceModel("emulator-5554", "sdk_gphone · emulator-5554"),
                DeviceChoiceModel("R3CN30ABC", "Pixel_8 · R3CN30ABC"),
            ),
            choices,
        )
    }

    @Test
    fun `selected serial falls back to the only connected device`() {
        assertEquals(null, sanitizeSelectedDeviceSerial("missing", emptyList()))
        assertEquals(
            "physical-1",
            sanitizeSelectedDeviceSerial(
                "physical-1",
                listOf(AdbDevice("physical-1", AdbDeviceState.ONLINE)),
            ),
        )
        assertEquals(
            "physical-1",
            sanitizeSelectedDeviceSerial(
                null,
                listOf(AdbDevice("physical-1", AdbDeviceState.ONLINE)),
            ),
        )
        assertEquals(
            null,
            sanitizeSelectedDeviceSerial(
                null,
                listOf(
                    AdbDevice("physical-1", AdbDeviceState.ONLINE),
                    AdbDevice("physical-2", AdbDeviceState.ONLINE),
                ),
            ),
        )
    }
}
